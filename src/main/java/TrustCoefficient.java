import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The trust coefficient, estimated in the shape the objective actually uses it.
 *
 * RiskDiscountedValue believes a projection this far:
 *
 *     believed = neighbourhood_mean + trust x (projection - neighbourhood_mean)
 *
 * and takes `trust` from PositionPredictability.reliability(), which is a
 * SPEARMAN RANK CORRELATION between preseason board rank and realised points,
 * computed over the whole position. Two things are wrong with borrowing it.
 *
 * It is the wrong statistic. A rank correlation is scale-free: it says whether
 * the ordering survives, not how far a points gap shrinks. The coefficient the
 * formula needs is a regression SLOPE in points - "a man projected ten points
 * above his neighbours finishes how many above them?" - and a rank correlation
 * is not an estimate of that quantity at any sample size.
 *
 * It is measured on the wrong set. Spearman is computed across the entire
 * position, where telling RB4 from RB55 is easy; the formula applies it to a
 * gap inside a plus-or-minus-six window, where telling RB10 from RB14 is
 * nearly impossible. Predicting an ordering over a wide range and predicting
 * it inside a narrow one are different problems with different answers, and
 * the wide one always scores better.
 *
 * So estimate the thing itself. Rank each position by preseason projection,
 * take every player's gap to his own neighbourhood mean, and regress his
 * realised gap on it through the origin - because a man sitting exactly at his
 * neighbourhood mean must be predicted at that mean. That slope IS the trust
 * coefficient, it is measured on the same window the objective uses, and it
 * changes as the window changes, which is what makes the window and the trust
 * one constant rather than two.
 *
 * Projections and outcomes are both scored under THIS league's settings, six
 * points a passing touchdown. Sleeper's pts_half_ppr pays four, and joining a
 * six-point projection to a four-point outcome puts a false slope on every
 * quarterback.
 *
 *   ./gradlew run -Pmain=TrustCoefficient [-Pwindow=6]
 */
public class TrustCoefficient {

    static final Position[] ALL = {Position.QB, Position.RB, Position.WR, Position.TE,
            Position.DEF};

    /**
     * How deep each position is worth measuring: down to replacement.
     *
     * The objective never compares two men below the replacement rank - both
     * are floored at it - so a slope fitted on waiver-wire noise would be
     * measuring a comparison the model cannot make.
     */
    static final Map<Position, Integer> DEPTH = new EnumMap<>(Position.class);
    static {
        DEPTH.put(Position.QB, 21);
        DEPTH.put(Position.RB, 61);
        DEPTH.put(Position.WR, 81);
        DEPTH.put(Position.TE, 19);
        DEPTH.put(Position.DEF, 13);
    }

    /** One player's gap to his neighbourhood, projected and realised. */
    public record Gap(Position position, double projectedGap, double realisedGap,
                      double projection, double actual){}

    /**
     * Every (projected gap, realised gap) pair in the seasons before `before`.
     *
     * A player inside the depth with no stat line is a season that never
     * happened - a holdout, a torn achilles in August - and scores zero. He is
     * kept rather than dropped: dropping him would fit the slope only on
     * players who made it to the field, which is the survivorship that makes
     * every projection look trustworthy.
     */
    public static List<Gap> gaps(AAAConfiguration configuration, int window,
                                 String excluded){
        List<Gap> out = new ArrayList<>();
        int current = Integer.parseInt(configuration.getSeason());
        for(int year = current - 5; year < current; year++){
            String season = String.valueOf(year);
            if(season.equals(excluded)){
                continue;
            }
            Map<String, Double> projected;
            Map<String, Double> actual;
            try {
                projected = HistoricalProjections.leaguePointsBySleeperID(configuration,
                        season);
                actual = HistoricalActuals.leaguePointsBySleeperID(season);
            }
            catch(RuntimeException unavailable){
                continue;
            }
            Map<Position, List<String>> byPosition = new EnumMap<>(Position.class);
            for(String id : projected.keySet()){
                Player player = Player.getPlayerFromSIDV2(id);
                if(player != null){
                    byPosition.computeIfAbsent(player.position, u -> new ArrayList<>())
                            .add(id);
                }
            }
            for(Position position : ALL){
                List<String> ids = byPosition.get(position);
                if(ids == null){
                    continue;
                }
                ids.sort(Comparator.comparingDouble(
                        (String id) -> -projected.getOrDefault(id, 0.0)));
                int depth = Math.min(ids.size(), DEPTH.getOrDefault(position, 40));
                for(int rank = 0; rank < depth; rank++){
                    int from = Math.max(0, rank - window);
                    int to = Math.min(ids.size(), rank + window + 1);
                    double projectedMean = 0;
                    double actualMean = 0;
                    for(int i = from; i < to; i++){
                        projectedMean += projected.getOrDefault(ids.get(i), 0.0);
                        actualMean += actual.getOrDefault(ids.get(i), 0.0);
                    }
                    projectedMean /= to - from;
                    actualMean /= to - from;
                    double mine = projected.getOrDefault(ids.get(rank), 0.0);
                    double his = actual.getOrDefault(ids.get(rank), 0.0);
                    out.add(new Gap(position, mine - projectedMean, his - actualMean,
                            mine, his));
                }
            }
        }
        return out;
    }

    /**
     * The origin-through slope, its standard error, and the sample behind it.
     *
     * The standard error is the point of this record. A slope through the
     * origin is Sum(xy)/Sum(xx), so its precision is set by how far the x's
     * spread - and inside a narrow rank window the x's are tiny by
     * construction. The estimate can therefore be arbitrarily noisy exactly
     * where the objective needs it, and a slope quoted without its error bar
     * would hide that. Anything whose error bar covers both 0 and 1 has not
     * been measured, it has been guessed with extra steps.
     */
    public record Fit(double raw, double clamped, double standardError, int n,
                      double spread){}

    /**
     * Clamped to [0, 1] for USE: a negative slope would mean the board is worth
     * inverting, which no sample this small can establish, and above one would
     * mean the projections are too timid, which the objective cannot express.
     * The raw value is kept for reporting, because a column of 1.000 that is
     * really a column of clamps is the kind of too-clean result this repo has
     * been burned by.
     */
    public static Fit fit(List<Gap> gaps, Position position){
        double xy = 0;
        double xx = 0;
        int n = 0;
        for(Gap gap : gaps){
            if(gap.position() != position){
                continue;
            }
            xy += gap.projectedGap() * gap.realisedGap();
            xx += gap.projectedGap() * gap.projectedGap();
            n++;
        }
        if(xx == 0 || n < 3){
            return new Fit(1.0, 1.0, Double.NaN, n, 0);
        }
        double raw = xy / xx;
        double residual = 0;
        for(Gap gap : gaps){
            if(gap.position() != position){
                continue;
            }
            double error = gap.realisedGap() - raw * gap.projectedGap();
            residual += error * error;
        }
        double standardError = Math.sqrt(residual / Math.max(1, n - 1) / xx);
        return new Fit(raw, Math.max(0.0, Math.min(1.0, raw)), standardError, n,
                Math.sqrt(xx / n));
    }

    public static double slope(List<Gap> gaps, Position position){
        return fit(gaps, position).clamped();
    }

    /** The whole trust map at one window width, ready to hand the objective. */
    public static Map<Position, Double> measured(AAAConfiguration configuration,
                                                 int window, String excluded){
        List<Gap> gaps = gaps(configuration, window, excluded);
        Map<Position, Double> out = new EnumMap<>(Position.class);
        for(Position position : ALL){
            out.put(position, slope(gaps, position));
        }
        return out;
    }

    /** How far the position's projections sit above or below its outcomes. */
    public static Map<Position, double[]> levelBias(List<Gap> gaps){
        Map<Position, double[]> out = new EnumMap<>(Position.class);
        for(Gap gap : gaps){
            double[] cell = out.computeIfAbsent(gap.position(), u -> new double[3]);
            cell[0] += gap.projection();
            cell[1] += gap.actual();
            cell[2]++;
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Map<Position, Double> shipped = PositionPredictability.reliability();

        System.out.printf("%nTHE TRUST COEFFICIENT, MEASURED IN ITS OWN FORM%n%n");
        System.out.printf("shipped values are a Spearman rank correlation over the whole"
                + " position;%nmeasured values are the regression slope of realised gap"
                + " on projected gap%ninside the window the objective actually"
                + " shrinks toward.%n%n");

        System.out.printf("%-5s %10s", "POS", "SHIPPED");
        int[] windows = {2, 4, 6, 9, 12, 18, 30};
        for(int window : windows){
            System.out.printf(" %8s", "w=" + window);
        }
        System.out.println();
        Map<Integer, Map<Position, Double>> byWindow = new HashMap<>();
        for(int window : windows){
            byWindow.put(window, measured(configuration, window, null));
        }
        for(Position position : ALL){
            System.out.printf("%-5s %10.3f", position,
                    shipped.getOrDefault(position, 1.0));
            for(int window : windows){
                System.out.printf(" %8.3f", byWindow.get(window).get(position));
            }
            System.out.println();
        }

        System.out.printf("%nRAW SLOPE PLUS-OR-MINUS ONE STANDARD ERROR"
                + " (before the [0,1] clamp)%n%n");
        System.out.printf("%-5s %10s", "POS", "SHIPPED");
        for(int window : windows){
            System.out.printf(" %16s", "w=" + window);
        }
        System.out.println();
        for(Position position : ALL){
            System.out.printf("%-5s %10.3f", position,
                    shipped.getOrDefault(position, 1.0));
            for(int window : windows){
                Fit measured = fit(gaps(configuration, window, null), position);
                System.out.printf(" %8.2f+-%-5.2f", measured.raw(),
                        measured.standardError());
            }
            System.out.println();
        }

        System.out.printf("%nA clamped 1.000 above is a raw slope at or past one -"
                + " the projections are%nnot too confident inside a narrow window, they"
                + " are if anything too timid.%nBut read the error bars first: where"
                + " one covers both 0 and 1, five seasons%ncannot say what the"
                + " coefficient is, and any value chosen inside that range%nis a"
                + " judgement call wearing a measurement's clothes. That is the"
                + " honest%nstate of every skill position at the shipped window.%n");

        List<Gap> gaps = gaps(configuration, RiskDiscountedValue.NEIGHBOURHOOD, null);
        System.out.printf("%nLEVEL BIAS AT THE SHIPPED WINDOW (w=%d)%n%n",
                RiskDiscountedValue.NEIGHBOURHOOD);
        System.out.printf("%-5s %8s %12s %12s %10s%n", "POS", "n", "projected",
                "realised", "ratio");
        Map<Position, double[]> bias = levelBias(gaps);
        for(Position position : ALL){
            double[] cell = bias.get(position);
            if(cell == null || cell[2] == 0){
                continue;
            }
            System.out.printf("%-5s %8.0f %12.1f %12.1f %10.3f%n", position, cell[2],
                    cell[0] / cell[2], cell[1] / cell[2],
                    cell[0] == 0 ? 0 : cell[1] / cell[0]);
        }
        System.out.println("\nThe ratio is the part no within-position shrinkage can"
                + " fix. Trust rescales a\nman against his neighbours; it cannot move a"
                + " whole position against another\none, and it is positions being"
                + " compared that decides which one gets a pick.");

        // A 1.4 ratio at one position and 0.85 at the rest is exactly the shape
        // a units error makes, and this repo has printed 0.0 for defences once
        // already by mixing projections with actuals. So check it season by
        // season against the WHOLE position before believing it: a real bias
        // repeats every year, a units error is usually a constant factor, and a
        // join failure shows up as a missing count.
        System.out.printf("%nIS THE DEFENCE RATIO REAL, OR A UNITS ERROR?%n%n");
        System.out.printf("%-8s %8s %12s %12s %12s %12s%n", "SEASON", "n",
                "proj mean", "actual mean", "proj top12", "actual top12");
        int current = Integer.parseInt(configuration.getSeason());
        for(int year = current - 5; year < current; year++){
            String season = String.valueOf(year);
            Map<String, Double> projected =
                    HistoricalProjections.leaguePointsBySleeperID(configuration, season);
            Map<String, Double> actual = HistoricalActuals.leaguePointsBySleeperID(season);
            List<String> defences = new ArrayList<>();
            for(String id : projected.keySet()){
                Player player = Player.getPlayerFromSIDV2(id);
                if(player != null && player.position == Position.DEF){
                    defences.add(id);
                }
            }
            defences.sort(Comparator.comparingDouble(
                    (String id) -> -projected.getOrDefault(id, 0.0)));
            double projectedSum = 0;
            double actualSum = 0;
            double projectedTop = 0;
            double actualTop = 0;
            for(int i = 0; i < defences.size(); i++){
                projectedSum += projected.getOrDefault(defences.get(i), 0.0);
                actualSum += actual.getOrDefault(defences.get(i), 0.0);
                if(i < 12){
                    projectedTop += projected.getOrDefault(defences.get(i), 0.0);
                    actualTop += actual.getOrDefault(defences.get(i), 0.0);
                }
            }
            int n = Math.max(1, defences.size());
            System.out.printf("%-8s %8d %12.1f %12.1f %12.1f %12.1f%n", season,
                    defences.size(), projectedSum / n, actualSum / n, projectedTop / 12,
                    actualTop / 12);
        }
        System.out.println("\nIf every season shows the same gap in the same direction"
                + " and the counts are\nfull, the ratio is a real conservatism in"
                + " Sleeper's defence projections, not\na join failure. It still does"
                + " not argue for drafting one early: a bias that\nlifts the best"
                + " defence lifts the replacement defence with it, and only the\ngap"
                + " between them can buy a pick.");
    }
}
