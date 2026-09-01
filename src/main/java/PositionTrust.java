import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * HOW MUCH OF A POSITION'S PROJECTED GAP ACTUALLY MATERIALISES?
 *
 * The value curve says RB1 is worth 300 and RB16 is worth 180, DEF1 is worth
 * 106 and DEF16 is worth 86. The live model spends picks on those gaps. The
 * question this answers, for EVERY position on the same footing, is whether
 * the gap is real - whether men drafted near the top of a position really do
 * outscore men drafted lower down, and by how much of what was projected.
 *
 * Justin, 2026-09-01: "I'm using the defense since it's easily diagnosible, but
 * make sure fix takes care of all positions." Quite right - a fix that names
 * DEF is a rule, not a model. This names no position.
 *
 * THE MEASUREMENT. Within a season and position, take the men in preseason
 * order and split them into four buckets by rank: 1-4, 5-8, 9-12, 13-16. Each
 * bucket has a PROJECTED mean (what the curve promised) and a REALISED mean
 * (what the season delivered). Regress realised spread on projected spread
 * through the position mean:
 *
 *     trust = sum(projected_gap * realised_gap) / sum(projected_gap^2)
 *
 * At trust 1 the whole projected gap shows up. At trust 0 the buckets all
 * finish the same and the ordering bought nothing. Pooled over every era
 * season, so the standard error is over SEASONS - the unit of independent
 * randomness in this repo.
 *
 *   ./gradlew run -Pmain=PositionTrust -q
 */
public class PositionTrust {

    private static final int[][] BUCKETS = {{1, 4}, {5, 8}, {9, 12}, {13, 16}};

    /** Measured trust per position, cached for the session. */
    private static Map<Position, double[]> measured;

    public static void main(String[] args) throws Exception {
        Map<Position, double[]> trust = measure();
        System.out.printf("%nhow much of a position's projected gap materialises.%n"
                + "buckets of four by preseason rank, pooled over era seasons,%n"
                + "standard error clustered on SEASON.%n%n");
        System.out.printf("%-5s %8s %10s %10s %10s %s%n", "POS", "seasons", "slope",
                "+- 1 s.e.", "spearman", "reading");
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE,
                Position.QB, Position.DEF}){
            double[] value = trust.get(position);
            if(value == null){
                continue;
            }
            String reading = value[0] + 2 * value[1] < 1.0
                    ? "believes LESS than projected, significantly"
                    : value[0] - 2 * value[1] > 0
                            ? "cannot be told from full belief"
                            : "cannot be told from ZERO - the order buys nothing provable";
            List<Double> rhos = spearmanPerSeason.get(position);
            double rho = rhos == null || rhos.isEmpty() ? Double.NaN
                    : rhos.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            System.out.printf("%-5s %8.0f %10.3f %10.3f %10.3f %s%n", position, value[2],
                    value[0], value[1], rho, reading);
        }
        System.out.printf("%nthe model believes each position this far, and no further."
                + "%na position whose trust cannot be told from zero has a FLAT curve:%n"
                + "every man at it worth the same in expectation, so there is nothing%n"
                + "to gain by reaching for one early.%n");
    }

    /** Rank correlation of PROJECTED points with realised, same data as the slope. */
    static final Map<Position, List<Double>> spearmanPerSeason = new EnumMap<>(Position.class);

    /** Trust per position: {trust, standard error, seasons}. */
    public static synchronized Map<Position, double[]> measure(){
        if(measured != null){
            return measured;
        }
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Map<Position, List<Double>> perSeason = new EnumMap<>(Position.class);
        for(String season : configuration.getPreviousSeasons()){
            Map<String, Double> projected;
            Map<String, Double> realised;
            Map<String, Double> realisedDefence;
            try {
                projected = HistoricalProjections.rawPointsBySleeperID(configuration, season);
                realised = LeagueActuals.seasonPoints(season);
                realisedDefence = LeagueActuals.seasonDefencePoints(season);
            }
            catch(RuntimeException unavailable){
                continue;
            }
            for(Position position : new Position[]{Position.RB, Position.WR,
                    Position.TE, Position.QB, Position.DEF}){
                Map<String, Double> outcome = position == Position.DEF
                        ? realisedDefence : realised;
                Double slope = seasonSlope(position, projected, outcome);
                if(slope != null){
                    perSeason.computeIfAbsent(position, u -> new ArrayList<>()).add(slope);
                }
                Double rho = seasonSpearman(position, projected, outcome);
                if(rho != null){
                    spearmanPerSeason.computeIfAbsent(position, u -> new ArrayList<>())
                            .add(rho);
                }
            }
        }
        Map<Position, double[]> out = new EnumMap<>(Position.class);
        for(Map.Entry<Position, List<Double>> entry : perSeason.entrySet()){
            List<Double> values = entry.getValue();
            if(values.size() < 3){
                continue;
            }
            double mean = values.stream().mapToDouble(Double::doubleValue)
                    .average().orElse(1);
            double sumSquares = 0;
            for(double value : values){
                sumSquares += (value - mean) * (value - mean);
            }
            double sd = Math.sqrt(sumSquares / (values.size() - 1));
            out.put(entry.getKey(), new double[]{mean, sd / Math.sqrt(values.size()),
                    values.size()});
        }
        measured = out;
        return measured;
    }

    /**
     * One season, one position: the regression slope of realised points on
     * PROJECTED points, through both means, over the draftable range.
     *
     * Units are the ones the shrinkage formula needs - "a man projected ten
     * points above his position's mean finished how many above it?" - so a
     * slope of 1 means the whole projected gap arrived and 0 means none of it
     * did. No synthetic scale: both sides are real points.
     */
    private static Double seasonSlope(Position position, Map<String, Double> projected,
                                      Map<String, Double> realised){
        List<double[]> pairs = new ArrayList<>();
        List<Map.Entry<String, Double>> men = new ArrayList<>();
        for(Map.Entry<String, Double> entry : projected.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player != null && player.position == position && entry.getValue() > 0){
                men.add(entry);
            }
        }
        men.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        int depth = Math.min(men.size(), DRAFTABLE.getOrDefault(position, 24));
        for(int i = 0; i < depth; i++){
            Double scored = realised.get(men.get(i).getKey());
            if(scored != null && scored > 0){
                pairs.add(new double[]{men.get(i).getValue(), scored});
            }
        }
        if(pairs.size() < 8){
            return null;
        }
        double meanProjected = pairs.stream().mapToDouble(p -> p[0]).average().orElse(0);
        double meanRealised = pairs.stream().mapToDouble(p -> p[1]).average().orElse(0);
        double numerator = 0;
        double denominator = 0;
        for(double[] pair : pairs){
            double x = pair[0] - meanProjected;
            numerator += x * (pair[1] - meanRealised);
            denominator += x * x;
        }
        return denominator <= 0 ? null : numerator / denominator;
    }

    /**
     * The same men, scored on ORDER rather than on points.
     *
     * Two statistics because they answer different questions and DISAGREED for
     * defences, which is the whole reason this exists. The slope asks how much
     * of a points gap arrives; the rank correlation asks whether the ordering
     * survives at all. A position can post a believable slope on five noisy
     * seasons while its ordering carries nothing, and only reporting both makes
     * that visible instead of letting whichever was computed first decide.
     */
    private static Double seasonSpearman(Position position, Map<String, Double> projected,
                                         Map<String, Double> realised){
        List<Map.Entry<String, Double>> men = new ArrayList<>();
        for(Map.Entry<String, Double> entry : projected.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player != null && player.position == position && entry.getValue() > 0){
                men.add(entry);
            }
        }
        men.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        int depth = Math.min(men.size(), DRAFTABLE.getOrDefault(position, 24));
        List<double[]> pairs = new ArrayList<>();
        for(int i = 0; i < depth; i++){
            Double scored = realised.get(men.get(i).getKey());
            if(scored != null && scored > 0){
                pairs.add(new double[]{i + 1, scored});
            }
        }
        if(pairs.size() < 8){
            return null;
        }
        List<double[]> byOutcome = new ArrayList<>(pairs);
        byOutcome.sort((a, b) -> Double.compare(b[1], a[1]));
        Map<Double, Integer> outcomeRank = new HashMap<>();
        for(int i = 0; i < byOutcome.size(); i++){
            outcomeRank.put(byOutcome.get(i)[1], i + 1);
        }
        int n = pairs.size();
        double sum = 0;
        for(double[] pair : pairs){
            double d = pair[0] - outcomeRank.get(pair[1]);
            sum += d * d;
        }
        return 1 - (6 * sum) / ((double) n * (n * n - 1));
    }

    /** How deep each position is really drafted - the range the curve is used over. */
    private static final Map<Position, Integer> DRAFTABLE = new EnumMap<>(Map.of(
            Position.QB, 24, Position.RB, 40, Position.WR, 48,
            Position.TE, 20, Position.DEF, 20));
}
