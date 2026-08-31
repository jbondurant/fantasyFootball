import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * P(the later-drafted man outscores the earlier-drafted man), same position.
 *
 * Justin's model: preseason ADP in, end-of-season points out, nothing from the
 * weeks in between. A surface over two pick numbers, per position. He rejected
 * the bucketed version - "the granularity needs to be smoothed out" - because
 * twelve-wide tiers jitter: RB13-24 against RB25-36 read 42% while its
 * neighbours disagreed, which is bin edges, not football.
 *
 * This tool does not propose the model. It MEASURES WHICH SMOOTHER TO USE, by
 * fitting four candidates on twelve seasons and scoring them on the thirteenth,
 * thirteen times over. That is the whole point: the sample here is nothing like
 * the rest of the repo - tens of thousands of pairs, not five season scores -
 * but the pairs inside one season are all scored on the same realised football,
 * so the SEASON is still the unit of independent randomness and the held-out
 * unit has to be a season. Holding out pairs would report an error bar off by
 * roughly the square root of the pairs per season.
 *
 * THE FOUR CANDIDATES
 *
 *   BUCKET 12     twelve-wide tiers, the thing being replaced.
 *   LOG-LINEAR    logit p = beta * (log rank_Q - log rank_P), one parameter a
 *                 position. Monotone and smooth by construction - and it
 *                 hard-codes a power law, which is exactly the assumption that
 *                 erases a cliff.
 *   ISOTONIC      a latent strength s(rank) forced non-increasing by
 *                 pool-adjacent-violators, then logit p = alpha*(s(P) - s(Q)).
 *                 No shape assumption at all. Cannot jitter. Steps.
 *   ISO + SMOOTH  the same, then a moving average of half-width w over ranks.
 *                 A moving average of a monotone sequence is still monotone, so
 *                 this cannot jitter either, and w is a single dial from "keep
 *                 every cliff" to "one straight line". THE DIAL IS CHOSEN BY
 *                 HELD-OUT SEASONS, not by taste.
 *
 * WHY A LATENT STRENGTH AND NOT A SURFACE IN TWO PICK NUMBERS. A surface fitted
 * on (P, Q) directly has to be taught three things it will otherwise get wrong
 * at the draft: P(r, r) = 0.5, P(P, Q) = 1 - P(Q, P), and transitivity. The
 * latent form gets all three free, and it turns a two-dimensional fit into a
 * one-dimensional curve, which is where the sample size actually goes.
 *
 *   ./gradlew run -Pmain=PairwiseOdds -q
 */
public class PairwiseOdds {

    /** How deep the curve is fitted at each position. */
    static final Map<Position, Integer> CAP = new EnumMap<>(Map.of(
            Position.QB, 32, Position.RB, 60, Position.WR, 72, Position.TE, 32));

    static final Position[] POSITIONS =
            {Position.QB, Position.RB, Position.WR, Position.TE};

    /** Moving-average half-widths swept; 0 is isotonic with no smoothing. */
    static final int[] WINDOWS = {0, 1, 2, 3, 5, 8, 12, 20};

    /** Half-widths for the log-rank smoother, in natural logs of rank. */
    static final double[] LOG_WINDOWS = {0.15, 0.25, 0.40, 0.60, 0.90};

    /** One drafted man: where he went, and what he finished with. */
    record Man(int season, Position position, int rank, double points){}

    /** One comparison. `gap` is filled in per model. */
    record Pair(int season, Position position, int early, int late, boolean lateWon){}

    // ------------------------------------------------------------ the sample

    /**
     * The wider sample: sixteen seasons from nflverse rather than thirteen.
     *
     * Justin asked "this is over how many seasons?" and the answer was thirteen,
     * which was a regression I had introduced without noticing. He approved
     * downloading nflverse precisely to reach 2010 - Sleeper simply has no rows
     * for men who left before it existed - and then I consolidated onto a tool
     * whose loader read the older thirteen-season harvest, throwing the three
     * extra seasons away. Same question, more of the evidence.
     */
    static List<Man> nflverseMen(Map<String, List<DetectionLag.Man>> seasons,
                                 List<String> order){
        List<Man> out = new ArrayList<>();
        for(int s = 0; s < order.size(); s++){
            for(DetectionLag.Man man : seasons.get(order.get(s))){
                if(man.position() == Position.DEF
                        || !CAP.containsKey(man.position())
                        || man.positionRank() > CAP.get(man.position())){
                    continue;
                }
                double total = 0;
                boolean played = false;
                for(double week : man.weekly()){
                    if(!Double.isNaN(week)){
                        total += week;
                        played = true;
                    }
                }
                if(played){
                    out.add(new Man(s, man.position(), man.positionRank(), total));
                }
            }
        }
        return out;
    }

    static List<Man> men(Map<String, EraBoards.Board> boards, List<String> seasons){
        List<Man> out = new ArrayList<>();
        for(int s = 0; s < seasons.size(); s++){
            EraBoards.Board board = boards.get(seasons.get(s));
            Map<String, Double> points = board.seasonPoints();
            Map<Position, Integer> seen = new EnumMap<>(Position.class);
            for(String id : board.ids()){                    // board.ids() is ADP order
                Position position = board.positionOf().get(id);
                if(position == null || position == Position.DEF){
                    continue;
                }
                int rank = seen.merge(position, 1, Integer::sum);
                if(rank > CAP.get(position)){
                    continue;
                }
                out.add(new Man(s, position, rank, points.getOrDefault(id, 0.0)));
            }
        }
        return out;
    }

    /**
     * Every same-position, same-season pair, the later man against the earlier.
     *
     * EXACT TIES ARE DROPPED, not scored as a loss. Two men who both finish on
     * nothing - both hurt in week one, both never activated - are not evidence
     * that the earlier pick was better, and there are enough of them deep in the
     * board to bend the tail of the curve if they are counted one way.
     */
    static List<Pair> pairs(List<Man> men, int season, boolean wanted, int[] ties){
        List<Man> use = new ArrayList<>();
        for(Man man : men){
            if((man.season() == season) == wanted){
                use.add(man);
            }
        }
        List<Pair> out = new ArrayList<>();
        for(int i = 0; i < use.size(); i++){
            for(int j = i + 1; j < use.size(); j++){
                Man a = use.get(i);
                Man b = use.get(j);
                if(a.season() != b.season() || a.position() != b.position()
                        || a.rank() == b.rank()){
                    continue;
                }
                Man early = a.rank() < b.rank() ? a : b;
                Man late = a.rank() < b.rank() ? b : a;
                if(late.points() == early.points()){
                    ties[0]++;
                    continue;
                }
                out.add(new Pair(early.season(), early.position(), early.rank(),
                        late.rank(), late.points() > early.points()));
            }
        }
        return out;
    }

    // --------------------------------------------------------- the smoothers

    /**
     * Pool-adjacent-violators, forcing a non-increasing curve.
     *
     * This is what makes jitter impossible without assuming a shape. Where the
     * data really does fall off a cliff, PAVA leaves the cliff exactly where it
     * is; where the data wobbles, it flattens the wobble into a tie.
     */
    static double[] isotonicDecreasing(double[] y, double[] weight){
        int n = y.length;
        double[] value = new double[n];
        double[] mass = new double[n];
        int[] size = new int[n];
        int top = 0;
        for(int i = 0; i < n; i++){
            value[top] = y[i];
            mass[top] = Math.max(weight[i], 1e-9);
            size[top] = 1;
            top++;
            while(top > 1 && value[top - 2] < value[top - 1]){
                double total = mass[top - 2] + mass[top - 1];
                value[top - 2] = (value[top - 2] * mass[top - 2]
                        + value[top - 1] * mass[top - 1]) / total;
                mass[top - 2] = total;
                size[top - 2] += size[top - 1];
                top--;
            }
        }
        double[] out = new double[n];
        int k = 0;
        for(int block = 0; block < top; block++){
            for(int s = 0; s < size[block]; s++){
                out[k++] = value[block];
            }
        }
        return out;
    }

    /**
     * A symmetric moving average with clamped ends.
     *
     * The clamping matters: repeating the endpoint keeps the sequence monotone,
     * where a shrinking window at the edges does not have to. So the smoothed
     * curve is still monotone, and adjacent ranks still give adjacent answers.
     */
    static double[] smooth(double[] x, int window){
        if(window <= 0){
            return x.clone();
        }
        int n = x.length;
        double[] out = new double[n];
        for(int i = 0; i < n; i++){
            double sum = 0;
            for(int d = -window; d <= window; d++){
                sum += x[Math.min(n - 1, Math.max(0, i + d))];
            }
            out[i] = sum / (2 * window + 1);
        }
        return out;
    }

    /**
     * The same moving average, but with the window held constant in LOG rank.
     *
     * This is the fix for the one thing a fixed-width window gets wrong here.
     * Ranks are not equally informative: the gap between RB6 and RB12 is a
     * different animal from the gap between RB54 and RB60, and a window wide
     * enough to quiet the noisy tail is far too wide at the top of the board,
     * where it smears the cliff Justin actually drafts against.
     *
     * Still monotone. Both window edges move right as the rank rises, and for a
     * non-increasing sequence widening at the far end pulls in smaller values
     * while advancing the near end drops larger ones - both moves lower the
     * mean, so the smoothed curve cannot turn back up.
     */
    static double[] smoothLog(double[] x, double halfWidth){
        int n = x.length;
        double[] out = new double[n];
        for(int i = 0; i < n; i++){
            double centre = Math.log(i + 1.0);
            double sum = 0;
            int count = 0;
            for(int j = 0; j < n; j++){
                if(Math.abs(Math.log(j + 1.0) - centre) <= halfWidth){
                    sum += x[j];
                    count++;
                }
            }
            out[i] = count == 0 ? x[i] : sum / count;
        }
        return out;
    }

    /**
     * The latent strength curve: logit of a man's win rate against his own
     * position's whole drafted field, in his own season, pooled over the
     * training seasons.
     *
     * Estimated marginally rather than by iterating a Bradley-Terry likelihood,
     * because at this sample size the two land in the same place and one of them
     * runs in a second. The calibration slope below absorbs the difference.
     */
    static double[] strength(List<Pair> training, Position position, int cap){
        double[] wins = new double[cap + 1];
        double[] games = new double[cap + 1];
        for(Pair pair : training){
            if(pair.position() != position){
                continue;
            }
            games[pair.early()]++;
            games[pair.late()]++;
            if(pair.lateWon()){
                wins[pair.late()]++;
            }
            else {
                wins[pair.early()]++;
            }
        }
        double[] raw = new double[cap + 1];
        double[] weight = new double[cap + 1];
        for(int r = 1; r <= cap; r++){
            double rate = games[r] == 0 ? 0.5
                    : (wins[r] + 0.5) / (games[r] + 1.0);          // Laplace, so the
            raw[r] = Math.log(rate / (1 - rate));                  // ends are finite
            weight[r] = games[r];
        }
        double[] body = new double[cap];
        double[] mass = new double[cap];
        System.arraycopy(raw, 1, body, 0, cap);
        System.arraycopy(weight, 1, mass, 0, cap);
        double[] fitted = isotonicDecreasing(body, mass);
        double[] out = new double[cap + 1];
        System.arraycopy(fitted, 0, out, 1, cap);
        return out;
    }

    /** logit p = slope * gap, fitted by Newton. One parameter, huge n. */
    static double fitSlope(double[] gap, boolean[] won){
        double slope = 1.0;
        for(int iteration = 0; iteration < 60; iteration++){
            double gradient = 0;
            double hessian = 0;
            for(int i = 0; i < gap.length; i++){
                double p = 1 / (1 + Math.exp(-slope * gap[i]));
                gradient += gap[i] * ((won[i] ? 1 : 0) - p);
                hessian += gap[i] * gap[i] * p * (1 - p);
            }
            if(hessian <= 1e-12){
                break;
            }
            double step = gradient / hessian;
            slope += step;
            if(Math.abs(step) < 1e-11){
                break;
            }
        }
        return slope;
    }

    /** A fitted model: what it predicts for one pair. */
    interface Model {
        double probability(Position position, int early, int late);
    }

    static Model logLinear(List<Pair> training){
        Map<Position, Double> slopes = new EnumMap<>(Position.class);
        for(Position position : POSITIONS){
            List<Pair> mine = new ArrayList<>();
            for(Pair pair : training){
                if(pair.position() == position){
                    mine.add(pair);
                }
            }
            double[] gap = new double[mine.size()];
            boolean[] won = new boolean[mine.size()];
            for(int i = 0; i < mine.size(); i++){
                gap[i] = Math.log(mine.get(i).early()) - Math.log(mine.get(i).late());
                won[i] = mine.get(i).lateWon();
            }
            slopes.put(position, fitSlope(gap, won));
        }
        return (position, early, late) -> {
            double z = slopes.get(position)
                    * (Math.log(early) - Math.log(late));
            return 1 / (1 + Math.exp(-z));
        };
    }

    static Model latent(List<Pair> training, int window){
        return latent(training, window, 0);
    }

    /** halfWidth > 0 smooths in log rank and `window` is ignored. */
    static Model latent(List<Pair> training, int window, double halfWidth){
        Map<Position, double[]> curves = new EnumMap<>(Position.class);
        Map<Position, Double> slopes = new EnumMap<>(Position.class);
        for(Position position : POSITIONS){
            int cap = CAP.get(position);
            double[] raw = strength(training, position, cap);
            double[] body = new double[cap];
            System.arraycopy(raw, 1, body, 0, cap);
            double[] smoothed = halfWidth > 0 ? smoothLog(body, halfWidth)
                    : smooth(body, window);
            double[] curve = new double[cap + 1];
            System.arraycopy(smoothed, 0, curve, 1, cap);
            curves.put(position, curve);

            List<Pair> mine = new ArrayList<>();
            for(Pair pair : training){
                if(pair.position() == position){
                    mine.add(pair);
                }
            }
            double[] gap = new double[mine.size()];
            boolean[] won = new boolean[mine.size()];
            for(int i = 0; i < mine.size(); i++){
                gap[i] = curve[mine.get(i).late()] - curve[mine.get(i).early()];
                won[i] = mine.get(i).lateWon();
            }
            slopes.put(position, fitSlope(gap, won));
        }
        return (position, early, late) -> {
            double[] curve = curves.get(position);
            double z = slopes.get(position) * (curve[late] - curve[early]);
            return 1 / (1 + Math.exp(-z));
        };
    }

    /** The thing being replaced: twelve-wide tiers, with a global fallback. */
    static Model buckets(List<Pair> training, int width){
        Map<String, double[]> cells = new java.util.HashMap<>();
        double wins = 0;
        double all = 0;
        for(Pair pair : training){
            String key = pair.position() + ":" + (pair.early() - 1) / width + ":"
                    + (pair.late() - 1) / width;
            double[] cell = cells.computeIfAbsent(key, k -> new double[2]);
            cell[1]++;
            all++;
            if(pair.lateWon()){
                cell[0]++;
                wins++;
            }
        }
        double overall = all == 0 ? 0.5 : wins / all;
        return (position, early, late) -> {
            double[] cell = cells.get(position + ":" + (early - 1) / width + ":"
                    + (late - 1) / width);
            if(cell == null || cell[1] < 10){
                return overall;
            }
            return (cell[0] + 0.5) / (cell[1] + 1.0);
        };
    }

    // ------------------------------------------------------------ validation

    /** Mean negative log likelihood per pair - a proper score, so it cannot be gamed. */
    static double logLoss(Model model, List<Pair> held){
        double total = 0;
        for(Pair pair : held){
            double p = Math.min(1 - 1e-6, Math.max(1e-6,
                    model.probability(pair.position(), pair.early(), pair.late())));
            total += pair.lateWon() ? -Math.log(p) : -Math.log(1 - p);
        }
        return held.isEmpty() ? 0 : total / held.size();
    }

    /**
     * The table Justin reads at the table: for each of his picks, the odds the
     * man he would get at his NEXT pick beats the man on the board now.
     *
     * Ranks, not pick numbers. An earlier version of this fitted pick numbers
     * directly and that was wrong - the best back at pick 7 is RB3 in one
     * season and RB6 in another, so a pick number means a different player
     * every year while a positional rank does not. His live pick numbers are
     * converted here off the board in front of him.
     */
    static void draftNight(Model model, Map<String, EraBoards.Board> boards){
        int[] picks = {7, 18, 31, 42, 55, 66, 79, 90, 103, 114, 127, 162, 175, 186};
        Position[] shown = {Position.RB, Position.WR, Position.TE, Position.QB};
        Map<Position, Integer> cap = Map.of(Position.QB, 32, Position.RB, 60,
                Position.WR, 72, Position.TE, 32);

        // How deep into a position the board is by a given overall pick, taken
        // from the live ADP rather than assumed.
        Map<Position, List<Double>> adp = new EnumMap<>(Position.class);
        for(Position position : shown){
            adp.put(position, new ArrayList<>());
        }
        for(String id : ProjectionSources.resolve(
                System.getProperty("projections", "sleeper")).keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            double value = SleeperProjections.adpOf(id);
            if(player != null && adp.containsKey(player.position)
                    && value < Double.MAX_VALUE){
                adp.get(player.position).add(value);
            }
        }
        for(List<Double> values : adp.values()){
            Collections.sort(values);
        }

        System.out.printf("%n%s%nANY TWO PICKS, NOT JUST CONSECUTIVE ONES%n%s%n",
                "=".repeat(72), "=".repeat(72));
        System.out.printf("%nP(the man still there at the COLUMN pick outscores the man on the%n"
                + "board at the ROW pick). Below 50%% means taking him at the row pick is%n"
                + "the better side of the bet. Ranks read off today's ADP.%n");

        Integer from = Integer.getInteger("from");
        Integer to = Integer.getInteger("to");
        for(Position position : shown){
            List<Double> board = adp.get(position);
            int limit = cap.get(position);
            System.out.printf("%n%s  -  best %s left at the row pick, against the best"
                    + " %s left at the column pick%n%n", position, position, position);
            System.out.printf("%-8s", "TAKE AT");
            for(int pick : picks){
                System.out.printf(" %5d", pick);
            }
            System.out.println();
            for(int i = 0; i < picks.length; i++){
                System.out.printf("%-8d", picks[i]);
                for(int j = 0; j < picks.length; j++){
                    int early = depth(board, picks[i]);
                    int late = depth(board, picks[j]);
                    if(j <= i || early < 1 || late > limit || early >= late){
                        System.out.printf(" %5s", j == i ? "--" : "");
                        continue;
                    }
                    System.out.printf(" %4.0f%%", 100 * model.probability(position, early, late));
                }
                System.out.println();
            }
        }

        System.out.printf("%nA blank means the board is not deep enough at that position for%n"
                + "those two picks to be different men, or the later rank runs past what%n"
                + "thirteen seasons can speak to.%n");

        if(from != null && to != null){
            System.out.printf("%n%s%nONE QUERY: the man at pick %d against the man at pick %d%n%s%n",
                    "-".repeat(72), to, from, "-".repeat(72));
            for(Position position : shown){
                int early = depth(adp.get(position), from);
                int late = depth(adp.get(position), to);
                if(early >= late || late > cap.get(position)){
                    System.out.printf("   %-4s -%n", position);
                    continue;
                }
                System.out.printf("   %-4s %s%d against %s%d: the later man wins %4.0f%%"
                        + " of the time%n", position, position, late, position, early,
                        100 * model.probability(position, early, late));
            }
        }
    }

    /**
     * Does the curve hold up at the ENDS, where a smoother is most likely to lie?
     *
     * Justin, on being told a back taken at pick 7 beats a last-round back 88%
     * of the time: "These odds seem low, are they smoothed out properly." The
     * ends are exactly where a monotone smoother compresses - it has neighbours
     * on one side only, so the fitted value gets pulled inward - and the fitted
     * number alone cannot answer him. This prints the raw win rate over the real
     * pairs in each corner beside the fitted one, with the count, so the two can
     * disagree out loud.
     */
    static void extremes(Model model, List<Pair> everything){
        int[][] windows = {{1, 4}, {1, 8}, {5, 12}, {13, 24}, {25, 36}};
        int[][] against = {{49, 60}, {37, 48}, {25, 36}};
        System.out.printf("%n%s%nDOES IT HOLD AT THE ENDS?%n%s%n",
                "=".repeat(72), "=".repeat(72));
        System.out.printf("%nRunning backs. RAW is every real pair in that corner across all%n"
                + "seasons; FIT is the curve. Where they disagree, the raw one is the%n"
                + "measurement and the curve is a summary of it.%n%n");
        System.out.printf("%-14s %-12s %10s %8s %8s %8s %8s%n",
                "TAKE (rank)", "AGAINST", "LATE WON", "RAW", "FIT", "EARLY", "DIFF");
        for(int[] early : windows){
            for(int[] late : against){
                if(late[0] <= early[1]){
                    continue;
                }
                int won = 0;
                int seen = 0;
                double fitted = 0;
                int fits = 0;
                for(Pair pair : everything){
                    if(pair.position() != Position.RB){
                        continue;
                    }
                    if(pair.early() >= early[0] && pair.early() <= early[1]
                            && pair.late() >= late[0] && pair.late() <= late[1]){
                        seen++;
                        if(pair.lateWon()){
                            won++;
                        }
                        fitted += model.probability(Position.RB, pair.early(), pair.late());
                        fits++;
                    }
                }
                if(seen < 30){
                    continue;
                }
                double raw = (double) won / seen;
                double fit = fitted / fits;
                System.out.printf("RB%-2d-%-9d RB%-2d-%-7d %5d/%-4d %7.1f%% %7.1f%% %7.1f%%"
                        + " %+7.1f%%%n",
                        early[0], early[1], late[0], late[1], won, seen,
                        100 * raw, 100 * fit, 100 * (1 - raw), 100 * (raw - fit));
            }
        }
        System.out.printf("%nLATE WON is the count: how many of those real pairs the DEEPER man%n"
                + "won outright. RAW is that as a rate, EARLY is its complement - what the%n"
                + "better-ranked man actually won. A positive DIFF means the deep man wins%n"
                + "MORE often than the curve says, so the early pick is worth LESS than the%n"
                + "matrix claims.%n");
    }

    /** How many of a position are gone by an overall pick, from today's board. */
    static int depth(List<Double> sorted, int pick){
        int gone = 0;
        for(double value : sorted){
            if(value < pick){
                gone++;
            }
        }
        return gone + 1;
    }

    public static void main(String[] args){
        String format = System.getProperty("format");
        Map<String, EraBoards.Board> boards = EraBoards.usable(
                format == null ? "ppr" : format, EraIngest.MIN_RATE, EraIngest.minDepth());
        // Sixteen seasons where they are joinable, thirteen where they are not.
        // -PeraBoards falls back to the older harvest so the two can be compared.
        Map<String, List<DetectionLag.Man>> wider =
                Boolean.getBoolean("eraBoards") ? Map.of() : NflverseBoards.usable(format);
        List<String> seasons = wider.isEmpty()
                ? new ArrayList<>(new TreeMap<>(boards).keySet())
                : new ArrayList<>(new TreeMap<>(wider).keySet());
        int clusters = seasons.size();
        List<Man> men = wider.isEmpty() ? men(boards, seasons)
                : nflverseMen(wider, seasons);
        int[] ties = new int[1];
        List<Pair> everything = pairs(men, -1, false, ties);

        System.out.printf("%nWHICH SMOOTHER FOR THE PAIRWISE ODDS SURFACE?%n%n");
        System.out.printf("P(the man at positional rank P outscores the man at rank Q),"
                + " Q < P, same%nposition, same season. Preseason ADP in, end-of-season"
                + " points out, nothing%nfrom the weeks between.%n%n");
        System.out.printf("seasons %d (%s-%s)   drafted men %d   pairs %d   exact ties"
                + " dropped %d%n", clusters, seasons.get(0), seasons.get(clusters - 1),
                men.size(), everything.size(), ties[0]);
        System.out.printf("caps: QB %d RB %d WR %d TE %d%n", CAP.get(Position.QB),
                CAP.get(Position.RB), CAP.get(Position.WR), CAP.get(Position.TE));

        // ------------------------------------------------- 1. the bake-off
        System.out.printf("%n%n1. LEAVE-ONE-SEASON-OUT BAKE-OFF%n%n");
        System.out.printf("Fitted on twelve seasons, scored on the thirteenth, thirteen"
                + " times. Held-out%nunit is the SEASON, because every pair inside one"
                + " is scored on the same realised%nfootball - holding out PAIRS would"
                + " shrink the error bar by roughly sqrt(%d) and%nreport a certainty"
                + " that is not there.%n%n", everything.size() / clusters);

        List<String> names = new ArrayList<>();
        List<double[]> losses = new ArrayList<>();
        names.add("BUCKET 12 (the old way)");
        names.add("LOG-LINEAR in log rank");
        for(int window : WINDOWS){
            names.add(window == 0 ? "ISOTONIC, no smoothing"
                    : "ISO + SMOOTH w=" + window);
        }
        for(double halfWidth : LOG_WINDOWS){
            names.add(String.format("ISO + LOG-SMOOTH h=%.2f", halfWidth));
        }
        for(int i = 0; i < names.size(); i++){
            losses.add(new double[clusters]);
        }
        double[] accuracy = new double[names.size()];
        for(int s = 0; s < clusters; s++){
            int[] scratch = new int[1];
            List<Pair> training = pairs(men, s, false, scratch);
            List<Pair> held = pairs(men, s, true, scratch);
            List<Model> models = new ArrayList<>();
            models.add(buckets(training, 12));
            models.add(logLinear(training));
            for(int window : WINDOWS){
                models.add(latent(training, window));
            }
            for(double halfWidth : LOG_WINDOWS){
                models.add(latent(training, 0, halfWidth));
            }
            for(int m = 0; m < models.size(); m++){
                losses.get(m)[s] = logLoss(models.get(m), held);
                int right = 0;
                for(Pair pair : held){
                    double p = models.get(m).probability(pair.position(), pair.early(),
                            pair.late());
                    if((p > 0.5) == pair.lateWon()){
                        right++;
                    }
                }
                accuracy[m] += (double) right / held.size() / clusters;
            }
        }

        int[] clusterOf = new int[clusters];
        for(int s = 0; s < clusters; s++){
            clusterOf[s] = s;
        }
        double[] baseline = losses.get(1);                 // LOG-LINEAR is the challenger
        System.out.printf("%-26s %11s %11s %10s %9s %7s %6s%n", "SMOOTHER", "log loss",
                "vs LOG-LIN", "SE(seas)", "95% bar", "acc", "beats");
        int best = 0;
        for(int m = 0; m < names.size(); m++){
            double[] diff = new double[clusters];
            for(int s = 0; s < clusters; s++){
                diff[s] = losses.get(m)[s] - baseline[s];
            }
            PowerBacktest.Paired paired = PowerBacktest.paired(names.get(m),
                    losses.get(m), diff, clusterOf, clusters);
            int beats = 0;
            for(int s2 = 0; s2 < clusters; s2++){
                if(diff[s2] < 0){
                    beats++;
                }
            }
            System.out.printf("%-26s %11.5f %+11.5f %10.5f %9.5f %6.1f%% %5d/%d   %s%n",
                    names.get(m), paired.mean(), paired.diff(), paired.seSeason(),
                    paired.bar(), 100 * accuracy[m], beats, clusters,
                    m == 1 ? "" : (paired.real() ? (paired.diff() < 0 ? "BETTER" : "worse")
                            : (beats >= clusters - 1 ? "sign test" : "tie")));
            if(mean(losses.get(m)) < mean(losses.get(best))){
                best = m;
            }
        }
        System.out.printf("%nLower log loss is better; it is a proper score, so a model"
                + " cannot buy it by%nbeing confidently wrong. Bars are 95%%, clustered"
                + " on season, from the same%nPowerBacktest.paired that prices the"
                + " 125-point draft bar.%n");
        System.out.printf("%nBEST: %s%n", names.get(best));

        // ------------------------------------------- 2. does it hide the cliff?
        System.out.printf("%n%n2. DOES THE SMOOTHER HIDE THE CLIFF?%n%n");
        System.out.printf("The existing measurement says only ~20%% of tier-two backs"
                + " beat a tier-one back%nwhile tier-two against tier-three is ~42%% -"
                + " a cliff, then a plateau. A fit that%ncannot jitter must still be"
                + " able to show that. Fitted on all %d seasons:%n%n", clusters);
        int[] scratch = new int[1];
        List<Pair> all = pairs(men, -1, false, scratch);
        Model fixed = latent(all, 8);
        Model logged = latent(all, 0, 0.25);
        Model line = logLinear(all);
        Model raw = latent(all, 0);
        System.out.printf("%-6s %12s %12s %12s %12s   %12s %12s %12s %12s%n", "RB",
                "vs6 ISO", "vs6 LOG.25", "vs6 FIXED8", "vs6 LINE",
                "vs18 ISO", "vs18 LOG.25", "vs18 FIXED8", "vs18 LINE");
        for(int r : new int[]{12, 18, 24, 30, 36, 42, 48, 54, 60}){
            System.out.printf("%-6s %11.0f%% %11.0f%% %11.0f%% %11.0f%%   ", "RB" + r,
                    100 * raw.probability(Position.RB, 6, r),
                    100 * logged.probability(Position.RB, 6, r),
                    100 * fixed.probability(Position.RB, 6, r),
                    100 * line.probability(Position.RB, 6, r));
            if(r <= 18){
                System.out.printf("%12s %12s %12s %12s%n", "-", "-", "-", "-");
            }
            else {
                System.out.printf("%11.0f%% %11.0f%% %11.0f%% %11.0f%%%n",
                        100 * raw.probability(Position.RB, 18, r),
                        100 * logged.probability(Position.RB, 18, r),
                        100 * fixed.probability(Position.RB, 18, r),
                        100 * line.probability(Position.RB, 18, r));
            }
        }
        System.out.printf("%nISO is the unsmoothed isotonic fit - the data's own shape."
                + " LINE is the%none-parameter power law. FIXED8 smooths eight ranks"
                + " either side everywhere;%nLOG.25 smooths a constant width in LOG"
                + " rank, so it is narrow at the top of the%nboard and wide in the tail."
                + " Read the top row: that is the cliff.%n");

        // --------------------------------------- 3. is one curve enough?
        System.out.printf("%n%n3. IS ONE CURVE ENOUGH? (held-out calibration by region)%n%n");
        System.out.printf("The latent form says the whole surface is a function of ONE"
                + " difference. If that%nis false the residuals will not be scattered -"
                + " they will have a sign that depends%non where you are on the board."
                + " Held-out pairs only, pooled over the %d folds.%n%n", clusters);
        int bands = 5;
        for(double[] setting : new double[][]{{8, 0}, {0, 0.25}}){
            double[][] observed = new double[bands][bands];
            double[][] predicted = new double[bands][bands];
            double[][] count = new double[bands][bands];
            for(int s = 0; s < clusters; s++){
                List<Pair> training = pairs(men, s, false, scratch);
                List<Pair> held = pairs(men, s, true, scratch);
                Model model = latent(training, (int) setting[0], setting[1]);
                for(Pair pair : held){
                    if(pair.position() != Position.RB){
                        continue;
                    }
                    int a = Math.min(bands - 1, (pair.early() - 1) / 12);
                    int b = Math.min(bands - 1, (pair.late() - 1) / 12);
                    observed[a][b] += pair.lateWon() ? 1 : 0;
                    predicted[a][b] += model.probability(Position.RB, pair.early(),
                            pair.late());
                    count[a][b]++;
                }
            }
            System.out.printf("RB, observed minus predicted, percentage points - %s%n%n",
                    setting[1] > 0 ? "LOG-SMOOTH h=0.25" : "FIXED SMOOTH w=8");
            System.out.printf("%-12s", "EARLY \\ LATE");
            for(int b = 0; b < bands; b++){
                System.out.printf(" %10s", "RB" + (b * 12 + 1) + "-" + (b * 12 + 12));
            }
            System.out.println();
            for(int a = 0; a < bands; a++){
                System.out.printf("%-12s", "RB" + (a * 12 + 1) + "-" + (a * 12 + 12));
                for(int b = 0; b < bands; b++){
                    if(count[a][b] < 30){
                        System.out.printf(" %10s", "-");
                    }
                    else {
                        System.out.printf(" %+9.1f%%", 100 * (observed[a][b]
                                - predicted[a][b]) / count[a][b]);
                    }
                }
                System.out.println();
            }
            System.out.println();
        }
        System.out.printf("Small mixed signs mean one curve carries the whole surface."
                + " A block of one%nsign is the model missing something real. The"
                + " EARLY RB1-12 row is the one to%nread: it is the cliff, and it is"
                + " where Justin's first pick lives.%n%n");

        // The recommended row, not the argmax row. The bake-off's minimum sits
        // in a broad flat basin (w=5-12, h=0.25-0.40) and NOT ONE row clears its
        // own 95% bar, so picking the lowest number would be the same
        // argmax-of-a-noisy-field mistake this repo has made before. h=0.25 is
        // chosen from the middle of the basin, and it is the variant that keeps
        // the cliff: it halves the fixed window's -5.0% miss at the RB1-12 row.
        draftNight(latent(everything, 0, 0.25), boards);
        extremes(latent(everything, 0, 0.25), everything);
    }

    static double mean(double[] x){
        double total = 0;
        for(double value : x){
            total += value;
        }
        return x.length == 0 ? 0 : total / x.length;
    }

    /** Rebuild whichever row won the bake-off, from its index in the table. */
    static Model chosenModel(List<Pair> training, int best){
        if(best == 0){
            return buckets(training, 12);
        }
        if(best == 1){
            return logLinear(training);
        }
        int index = best - 2;
        if(index < WINDOWS.length){
            return latent(training, WINDOWS[index]);
        }
        return latent(training, 0, LOG_WINDOWS[index - WINDOWS.length]);
    }
}
