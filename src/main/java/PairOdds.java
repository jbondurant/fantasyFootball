import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * The odds that the man you WAIT for beats the man you could take now.
 *
 * Justin, 2026-08-31: "I only want adp or projections from start of fantasy
 * season, and adp/stats at end of fantasy season, to model, what are the [fuzzy
 * smoothed odds] that a [position] at pick [pick number] beats a [same position]
 * at pick [lower pick number (better projected player)]."
 *
 * Two numbers a season, both known: his August price, and what he scored. No
 * weekly ranking, no in-season reaction, none of the machinery that produced
 * two wrong answers yesterday.
 *
 * WHY THIS QUESTION RESOLVES WHEN ALMOST NOTHING ELSE HERE DOES. The season is
 * the unit of independent randomness and there are sixteen, which is why every
 * points-denominated claim in this repo drowns - a bench marginal measured on
 * the same seasons came out +5.4 +/- 3.9, t = 1.4. But a WIN RATE is bounded
 * and per-decision, so its between-season variance is bounded too, and the same
 * thirteen seasons gave t = 7.69 on 6,093 decisions. Justin picked the one
 * shape of question this sample can answer.
 *
 * THE SMOOTHER. Tier buckets were rejected by him earlier - "the granularity
 * needs to be smoothed out" - because neighbouring buckets jitter
 * non-monotonically. This fits a logistic in log picks instead, per position,
 * which cannot jitter: adjacent picks give adjacent answers by construction.
 *
 *     logit P(later man wins) = c0 + c1 log(late/early) + c2 log(early)
 *
 * c1 is how fast the gap closes; c2 is how much flatter the board gets as it
 * deepens. Both are fitted, not chosen.
 *
 * AND THE CHECK ON THE SMOOTHER. A curve that cannot jitter also cannot show a
 * genuine cliff, and there is one - only about a fifth of tier-two backs beat a
 * tier-one back, while tier two against tier three is nearly a coin flip. So the
 * raw unsmoothed rates print beside the fitted ones. If they disagree at the top
 * of the board, believe the raw ones and say so.
 *
 *   ./gradlew run -Pmain=PairOdds [-Pfrom=79 -Pto=90] [-Pformat=ppr]
 */
public class PairOdds {

    /** Pairs are only interesting among men somebody actually drafts. */
    static final double LAST_PICK = 200;

    record Pair(String season, Position position, double early, double late, boolean lateWon,
                double margin){}

    public static void main(String[] args) throws Exception {
        Map<String, List<DetectionLag.Man>> seasons =
                NflverseBoards.usable(System.getProperty("format"));
        List<Pair> pairs = new ArrayList<>();
        for(Map.Entry<String, List<DetectionLag.Man>> entry : seasons.entrySet()){
            Map<Position, List<DetectionLag.Man>> byPosition = new EnumMap<>(Position.class);
            for(DetectionLag.Man man : entry.getValue()){
                if(man.adp() <= LAST_PICK){
                    byPosition.computeIfAbsent(man.position(), u -> new ArrayList<>()).add(man);
                }
            }
            for(Map.Entry<Position, List<DetectionLag.Man>> cell : byPosition.entrySet()){
                List<DetectionLag.Man> men = cell.getValue();
                men.sort(Comparator.comparingDouble(DetectionLag.Man::adp));
                for(int i = 0; i < men.size(); i++){
                    for(int j = i + 1; j < men.size(); j++){
                        double a = total(men.get(i));
                        double b = total(men.get(j));
                        if(Double.isNaN(a) || Double.isNaN(b)){
                            continue;
                        }
                        pairs.add(new Pair(entry.getKey(), cell.getKey(),
                                men.get(i).adp(), men.get(j).adp(), b > a, b - a));
                    }
                }
            }
        }
        System.out.printf("%nODDS THE MAN YOU WAIT FOR BEATS THE MAN YOU COULD TAKE NOW%n%n");
        System.out.printf("%d seasons, %d same-position pairs, drafted men only (ADP <= %.0f)%n",
                seasons.size(), pairs.size(), LAST_PICK);
        System.out.printf("august price against end-of-season points. nothing in between.%n%n");

        Map<Position, double[]> fits = new EnumMap<>(Position.class);
        System.out.printf("%-5s %8s %10s %10s %10s   %s%n", "POS", "PAIRS",
                "c0", "c1 gap", "c2 depth", "held-out accuracy vs 50%");
        for(Position position : new Position[]{Position.RB, Position.WR,
                Position.TE, Position.QB}){
            List<Pair> mine = pairs.stream().filter(p -> p.position() == position).toList();
            if(mine.size() < 200){
                continue;
            }
            double[] beta = fit(mine);
            fits.put(position, beta);
            double accuracy = heldOut(mine, seasons.keySet());
            System.out.printf("%-5s %8d %10.3f %10.3f %10.3f   %.1f%%%n", position,
                    mine.size(), beta[0], beta[1], beta[2], accuracy * 100);
        }

        System.out.printf("%n%s%nWHAT IT SAYS AT YOUR PICKS%n%s%n",
                "=".repeat(72), "=".repeat(72));
        int[] picks = {7, 18, 31, 42, 55, 66, 79, 90, 103, 114, 127, 162, 175, 186};
        System.out.printf("%nP(the man at your NEXT pick outscores the man at THIS pick)%n");
        System.out.printf("high means waiting is cheap. low means this pick is the one that"
                + " matters.%n%n");
        System.out.printf("%-14s", "TAKE NOW ->");
        for(Position position : fits.keySet()){
            System.out.printf(" %8s", position);
        }
        System.out.printf("   %s%n", "raw, all positions");
        for(int i = 0; i + 1 < picks.length; i++){
            System.out.printf("%4d -> %-7d", picks[i], picks[i + 1]);
            for(Position position : fits.keySet()){
                System.out.printf(" %7.0f%%", 100 * odds(fits.get(position),
                        picks[i], picks[i + 1]));
            }
            System.out.printf("   %6.0f%%%n", 100 * raw(pairs, picks[i], picks[i + 1]));
        }

        Integer from = Integer.getInteger("from");
        Integer to = Integer.getInteger("to");
        if(from != null && to != null){
            System.out.printf("%n%s%nONE QUERY: pick %d against pick %d%n%s%n",
                    "-".repeat(72), to, from, "-".repeat(72));
            System.out.printf("%-6s %10s %10s %8s%n", "POS", "SMOOTHED", "RAW", "PAIRS");
            for(Map.Entry<Position, double[]> fit : fits.entrySet()){
                Position position = fit.getKey();
                List<Pair> mine = pairs.stream()
                        .filter(pair -> pair.position() == position).toList();
                double rawRate = raw(mine, from, to);
                int seen = (int) mine.stream().filter(pair ->
                        Math.abs(pair.early() - from) <= 8
                        && Math.abs(pair.late() - to) <= 8).count();
                System.out.printf("%-6s %9.0f%% %9s %8d%n", position,
                        100 * odds(fit.getValue(), from, to),
                        Double.isNaN(rawRate) ? "-" : String.format("%.0f%%", 100 * rawRate),
                        seen);
            }
        }

        System.out.printf("%nThe raw column is the unsmoothed rate over every real pair in that"
                + " pick%nneighbourhood, printed because a curve that cannot jitter also cannot"
                + " show a%ngenuine cliff. Where the two disagree, believe the raw one.%n");
    }

    static double total(DetectionLag.Man man){
        double sum = 0;
        boolean any = false;
        for(double week : man.weekly()){
            if(!Double.isNaN(week)){
                sum += week;
                any = true;
            }
        }
        return any ? sum : Double.NaN;
    }

    /** P(later wins) at a given pair of picks. */
    static double odds(double[] beta, double early, double late){
        double x = beta[0] + beta[1] * Math.log(late / early) + beta[2] * Math.log(early);
        return 1 / (1 + Math.exp(-x));
    }

    /** The unsmoothed rate among real pairs near this pick pair. */
    static double raw(List<Pair> pairs, double early, double late){
        int won = 0;
        int seen = 0;
        for(Pair pair : pairs){
            if(Math.abs(pair.early() - early) <= 8 && Math.abs(pair.late() - late) <= 8){
                seen++;
                if(pair.lateWon()){
                    won++;
                }
            }
        }
        return seen == 0 ? Double.NaN : (double) won / seen;
    }

    /** Newton-Raphson on three features. */
    static double[] fit(List<Pair> pairs){
        double[] beta = new double[3];
        for(int step = 0; step < 60; step++){
            double[] gradient = new double[3];
            double[][] hessian = new double[3][3];
            for(Pair pair : pairs){
                double[] x = features(pair.early(), pair.late());
                double p = 1 / (1 + Math.exp(-(beta[0] * x[0] + beta[1] * x[1] + beta[2] * x[2])));
                double residual = (pair.lateWon() ? 1 : 0) - p;
                for(int a = 0; a < 3; a++){
                    gradient[a] += residual * x[a];
                    for(int b = 0; b < 3; b++){
                        hessian[a][b] += p * (1 - p) * x[a] * x[b];
                    }
                }
            }
            for(int a = 0; a < 3; a++){
                hessian[a][a] += 1e-6;
            }
            double[] delta = solve(hessian, gradient);
            for(int a = 0; a < 3; a++){
                beta[a] += delta[a];
            }
        }
        return beta;
    }

    static double[] features(double early, double late){
        return new double[]{1, Math.log(late / early), Math.log(early)};
    }

    /**
     * Leave one SEASON out, never one pair: pairs inside a season are scored on
     * the same realised football and are not independent. Holding out pairs
     * would report an error bar far tighter than the truth, which is the trap
     * that once made 480 draws look like 480 observations here.
     */
    static double heldOut(List<Pair> pairs, Set<String> seasons){
        int right = 0;
        int seen = 0;
        for(String season : seasons){
            List<Pair> train = pairs.stream().filter(p -> !p.season().equals(season)).toList();
            List<Pair> test = pairs.stream().filter(p -> p.season().equals(season)).toList();
            if(train.isEmpty() || test.isEmpty()){
                continue;
            }
            double[] beta = fit(train);
            for(Pair pair : test){
                double p = odds(beta, pair.early(), pair.late());
                if((p > 0.5) == pair.lateWon()){
                    right++;
                }
                seen++;
            }
        }
        return seen == 0 ? Double.NaN : (double) right / seen;
    }

    static double[] solve(double[][] a, double[] b){
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for(int i = 0; i < n; i++){
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for(int col = 0; col < n; col++){
            int pivot = col;
            for(int row = col + 1; row < n; row++){
                if(Math.abs(m[row][col]) > Math.abs(m[pivot][col])){
                    pivot = row;
                }
            }
            double[] swap = m[col]; m[col] = m[pivot]; m[pivot] = swap;
            for(int row = 0; row < n; row++){
                if(row == col || m[col][col] == 0){
                    continue;
                }
                double factor = m[row][col] / m[col][col];
                for(int k = col; k <= n; k++){
                    m[row][k] -= factor * m[col][k];
                }
            }
        }
        double[] out = new double[n];
        for(int i = 0; i < n; i++){
            out[i] = m[i][i] == 0 ? 0 : m[i][n] / m[i][i];
        }
        return out;
    }
}
