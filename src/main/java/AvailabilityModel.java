import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

/**
 * What is realistically still on the board at a given pick.
 *
 * Each player's landing spot is drawn around a blend of his ADP and where this
 * league's scoring says he belongs; the board takes the first N draws; what is
 * left is availability with reach risk priced in. Keeper-occupied picks do not
 * consume a player from the pool - a round spent on a keeper takes nobody off
 * the board - so N is the number of real selections before the pick, not the
 * pick number.
 *
 * PICK_STANDARD_DEVIATION and VALUE_WEIGHT are tuned by DraftBacktest against
 * a held-out season, never hand-set. Instances can be built with explicit
 * values so the backtest can search the grid.
 */
public class AvailabilityModel {

    /** Tuned by DraftBacktest on 2024, reported on 2025. */
    public static final double PICK_STANDARD_DEVIATION = 20.0;
    public static final double VALUE_WEIGHT = 0.25;

    private final double pickStandardDeviation;
    private final double valueWeight;
    /** Null means Gaussian; set means bootstrap the learned residuals. */
    private DisplacementModel learnedDisplacement;
    private final List<String> ids = new ArrayList<>();
    private final Map<String, Integer> indexOf = new HashMap<>();
    private final Map<String, Double> expectedPick = new HashMap<>();
    private final Map<String, Double> points = new HashMap<>();
    private final Map<String, Position> positions = new HashMap<>();
    private final TreeSet<Integer> occupiedPicks = new TreeSet<>();

    private AvailabilityModel(double pickStandardDeviation, double valueWeight){
        this.pickStandardDeviation = pickStandardDeviation;
        this.valueWeight = valueWeight;
    }

    /** Current-season build: ADP from SleeperProjections, tuned constants. */
    public static AvailabilityModel build(Map<String, Double> projectedPoints,
                                          Map<Position, Double> leagueBias){
        Map<String, Double> adp = new HashMap<>();
        for(String sleeperID : projectedPoints.keySet()){
            adp.put(sleeperID, SleeperProjections.adpOf(sleeperID));
        }
        return build(projectedPoints, leagueBias, adp, PICK_STANDARD_DEVIATION, VALUE_WEIGHT);
    }

    /**
     * Build on the learned displacement: players located at their rank in the
     * keeper-thinned pool, deviations bootstrapped from the league's own
     * history rather than drawn from a Gaussian.
     */
    public static AvailabilityModel buildLearned(Map<String, Double> projectedPoints,
                                                 Map<String, Double> adpBySleeperID,
                                                 DisplacementModel displacement){
        AvailabilityModel model = build(projectedPoints, Map.of(), adpBySleeperID, 0.0, 0.0);
        // Relocate everyone to par rank in selection space.
        List<Map.Entry<String, Double>> byAdp = new ArrayList<>();
        for(String sleeperID : model.ids){
            byAdp.add(Map.entry(sleeperID, adpBySleeperID.get(sleeperID)));
        }
        byAdp.sort(Map.Entry.comparingByValue());
        for(int rank = 0; rank < byAdp.size(); rank++){
            model.expectedPick.put(byAdp.get(rank).getKey(), (double) (rank + 1));
        }
        model.learnedDisplacement = displacement;
        return model;
    }

    /** Fully explicit build, for the backtest and the tuning grid. */
    public static AvailabilityModel build(Map<String, Double> projectedPoints,
                                          Map<Position, Double> leagueBias,
                                          Map<String, Double> adpBySleeperID,
                                          double pickStandardDeviation,
                                          double valueWeight){
        AvailabilityModel model = new AvailabilityModel(pickStandardDeviation, valueWeight);

        List<Map.Entry<String, Double>> byValue = new ArrayList<>();
        for(Map.Entry<String, Double> entry : projectedPoints.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null || !StartingLineup.isSkillPosition(player.position) || entry.getValue() <= 0){
                continue;
            }
            byValue.add(entry);
        }
        byValue.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        Map<String, Integer> valueRank = new HashMap<>();
        for(int i = 0; i < byValue.size(); i++){
            valueRank.put(byValue.get(i).getKey(), i + 1);
        }

        for(Map.Entry<String, Double> entry : byValue){
            String sleeperID = entry.getKey();
            Double adp = adpBySleeperID.get(sleeperID);
            if(adp == null || adp > 900){
                continue;
            }
            Player player = Player.getPlayerFromSIDV2(sleeperID);
            double bias = leagueBias.getOrDefault(player.position, 0.0);
            double blended = (1 - valueWeight) * (adp + bias) + valueWeight * valueRank.get(sleeperID);
            model.indexOf.put(sleeperID, model.ids.size());
            model.ids.add(sleeperID);
            model.expectedPick.put(sleeperID, blended);
            model.points.put(sleeperID, entry.getValue());
            model.positions.put(sleeperID, player.position);
        }
        return model;
    }

    /**
     * Keep the standard blended locations but draw deviations from the given
     * model instead of a Gaussian - for testing whether the learned SHAPE
     * helps once the location layer is held fixed.
     */
    public AvailabilityModel withDisplacement(DisplacementModel displacement){
        this.learnedDisplacement = displacement;
        return this;
    }

    /**
     * Overall pick numbers held by keepers. A keeper's round costs its owner
     * the pick but takes nobody off the draftable board.
     */
    public AvailabilityModel withOccupiedPicks(Collection<Integer> keeperPickNumbers){
        occupiedPicks.addAll(keeperPickNumbers);
        return this;
    }

    /** Real selections made before this overall pick. */
    int effectiveSelectionsBefore(int pick){
        return Math.max(pick - 1 - occupiedPicks.headSet(pick).size(), 0);
    }

    /** Each player's rank in one simulated draft order. */
    private int[] drawRanks(Random random){
        int n = ids.size();
        double[] landing = new double[n];
        Integer[] order = new Integer[n];
        for(int i = 0; i < n; i++){
            String sleeperID = ids.get(i);
            double location = expectedPick.get(sleeperID);
            double deviation = learnedDisplacement == null
                    ? random.nextGaussian() * pickStandardDeviation
                    : learnedDisplacement.sample(random, (int) Math.round(location),
                            positions.get(sleeperID));
            landing[i] = location + deviation;
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(landing[a], landing[b]));
        int[] rank = new int[n];
        for(int r = 0; r < n; r++){
            rank[order[r]] = r;
        }
        return rank;
    }

    /**
     * Survival of every player at every checkpoint, from shared draws - one
     * sort per trial instead of one per question.
     */
    public Map<String, double[]> survivalMatrix(int[] checkpointPicks, int trials, long seed){
        Random random = new Random(seed);
        int n = ids.size();
        int[][] available = new int[n][checkpointPicks.length];
        int[] gone = new int[checkpointPicks.length];
        for(int c = 0; c < checkpointPicks.length; c++){
            gone[c] = effectiveSelectionsBefore(checkpointPicks[c]);
        }
        for(int trial = 0; trial < trials; trial++){
            int[] rank = drawRanks(random);
            for(int i = 0; i < n; i++){
                for(int c = 0; c < checkpointPicks.length; c++){
                    if(rank[i] >= gone[c]){
                        available[i][c]++;
                    }
                }
            }
        }
        Map<String, double[]> matrix = new HashMap<>();
        for(int i = 0; i < n; i++){
            double[] row = new double[checkpointPicks.length];
            for(int c = 0; c < checkpointPicks.length; c++){
                row[c] = available[i][c] / (double) trials;
            }
            matrix.put(ids.get(i), row);
        }
        return matrix;
    }

    /** How often this player is still on the board at this pick. */
    public double probabilityAvailable(String sleeperID, int pick, int trials, long seed){
        Integer index = indexOf.get(sleeperID);
        if(index == null){
            return 0.0;
        }
        Random random = new Random(seed);
        int gone = effectiveSelectionsBefore(pick);
        int survived = 0;
        for(int trial = 0; trial < trials; trial++){
            if(drawRanks(random)[index] >= gone){
                survived++;
            }
        }
        return survived / (double) trials;
    }

    /** The average best player at this position still there at this pick. */
    public double expectedBestAvailable(Position position, int pick, int trials, long seed){
        Random random = new Random(seed);
        int gone = effectiveSelectionsBefore(pick);
        int n = ids.size();
        double total = 0.0;
        for(int trial = 0; trial < trials; trial++){
            int[] rank = drawRanks(random);
            double best = 0.0;
            int bestRank = Integer.MAX_VALUE;
            for(int i = 0; i < n; i++){
                if(rank[i] >= gone && rank[i] < bestRank
                        && positions.get(ids.get(i)).equals(position)){
                    bestRank = rank[i];
                    best = points.get(ids.get(i));
                }
            }
            total += best;
        }
        return total / trials;
    }

    /**
     * What waiting is worth: his points when he survives to the next pick, the
     * next best at the position when he does not.
     */
    public double expectedIfYouWait(String sleeperID, Position position,
                                    int nextPick, int trials, long seed){
        double survives = probabilityAvailable(sleeperID, nextPick, trials, seed);
        double him = points.getOrDefault(sleeperID, 0.0);
        double fallback = expectedBestAvailable(position, nextPick, trials, seed + 1);
        return survives * him + (1 - survives) * fallback;
    }

    public double pointsOf(String sleeperID){
        return points.getOrDefault(sleeperID, 0.0);
    }

    public java.util.Set<String> known(){
        return points.keySet();
    }

}
