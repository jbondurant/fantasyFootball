import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What is realistically still on the board at a given pick.
 *
 * Replacement level used to be a fixed rank - "QB12 projects 347, so a
 * quarterback is worth his points above 347". That assumes you reliably get
 * exactly the last starter at the position, and two things break it:
 *
 *  - Someone reaches. If the quarterback you were counting on goes three picks
 *    early you get the next one down, not the one you priced against.
 *  - ADP is calibrated to standard scoring. This league pays six points for a
 *    passing touchdown, so Brock Purdy is the sixth most valuable quarterback
 *    on the board and the fifteenth by ADP. A manager drafting off value rather
 *    than ADP takes him nine slots earlier than ADP predicts, and every
 *    quarterback behind him shifts up.
 *
 * So availability is drawn rather than assumed: each player's draft position is
 * sampled around a blend of his ADP and where this league's scoring says he
 * belongs, and replacement is the average best player left at that position.
 * That prices in the reach risk instead of ignoring it.
 */
public class AvailabilityModel {

    /**
     * How far a pick lands from its expected position. The backtest put the
     * median board error at 18 picks, which is close to a standard deviation
     * of 20 for a roughly normal spread.
     */
    public static final double PICK_STANDARD_DEVIATION = 20.0;

    /**
     * How much weight the league's own scoring gets against raw ADP.
     *
     * Zero means everyone drafts straight off Sleeper's standard-scoring ADP.
     * One means everyone has corrected for six-point passing touchdowns. This
     * league's history says the truth is near the ADP end - the average first
     * quarterback goes in round 7 and quarterbacks fall 20 picks past ADP here,
     * which is the opposite of what correcting for scoring would produce - but
     * it only takes one manager to change, so it is not zero either.
     */
    public static final double VALUE_WEIGHT = 0.25;

    private final Map<String, Double> expectedPick = new HashMap<>();
    private final Map<String, Double> points = new HashMap<>();
    private final Map<String, Position> positions = new HashMap<>();

    public static AvailabilityModel build(Map<String, Double> projectedPoints,
                                          Map<Position, Double> leagueBias){
        AvailabilityModel model = new AvailabilityModel();

        // Where this league's scoring says each player belongs, by ranking
        // everyone on points above their own position's replacement.
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
            Player player = Player.getPlayerFromSIDV2(sleeperID);
            double adp = SleeperProjections.adpOf(sleeperID);
            if(adp > 900){
                continue;
            }
            double bias = leagueBias.getOrDefault(player.position, 0.0);
            double blended = (1 - VALUE_WEIGHT) * (adp + bias) + VALUE_WEIGHT * valueRank.get(sleeperID);
            model.expectedPick.put(sleeperID, blended);
            model.points.put(sleeperID, entry.getValue());
            model.positions.put(sleeperID, player.position);
        }
        return model;
    }

    /**
     * The average best player at this position still on the board at this pick,
     * over repeated draws. This is replacement level, with reach risk in it.
     */
    public double expectedBestAvailable(Position position, int pick, int trials, long seed){
        Random random = new Random(seed);
        List<String> everyone = new ArrayList<>(expectedPick.keySet());
        if(everyone.isEmpty()){
            return 0.0;
        }
        int gone = Math.max(pick - 1, 0);

        double total = 0.0;
        for(int trial = 0; trial < trials; trial++){
            // Draw a landing spot for every player, then let the board take the
            // first `gone` of them. Checking each player against the pick
            // independently does not do this - the number taken drifts, and
            // taking the best of whoever survived biases replacement upwards.
            List<double[]> drawn = new ArrayList<>(everyone.size());
            for(int i = 0; i < everyone.size(); i++){
                String sleeperID = everyone.get(i);
                drawn.add(new double[]{
                        expectedPick.get(sleeperID) + random.nextGaussian() * PICK_STANDARD_DEVIATION,
                        i});
            }
            drawn.sort((a, b) -> Double.compare(a[0], b[0]));

            double best = 0.0;
            for(int rank = gone; rank < drawn.size(); rank++){
                String sleeperID = everyone.get((int) drawn.get(rank)[1]);
                if(positions.get(sleeperID).equals(position)){
                    best = points.get(sleeperID);
                    break;   // the list is in draft order, so this is the best left
                }
            }
            total += best;
        }
        return total / trials;
    }


    /**
     * How often this player is still on the board at this pick.
     *
     * The decision this exists for: take him now, or gamble he lasts until your
     * next pick and spend this one elsewhere. That trade only has an answer if
     * you can put a number on the gamble.
     */
    public double probabilityAvailable(String sleeperID, int pick, int trials, long seed){
        if(!expectedPick.containsKey(sleeperID)){
            return 0.0;
        }
        Random random = new Random(seed);
        List<String> everyone = new ArrayList<>(expectedPick.keySet());
        int gone = Math.max(pick - 1, 0);
        int survived = 0;

        for(int trial = 0; trial < trials; trial++){
            List<double[]> drawn = new ArrayList<>(everyone.size());
            for(int i = 0; i < everyone.size(); i++){
                drawn.add(new double[]{
                        expectedPick.get(everyone.get(i)) + random.nextGaussian() * PICK_STANDARD_DEVIATION,
                        i});
            }
            drawn.sort((a, b) -> Double.compare(a[0], b[0]));
            for(int rank = gone; rank < drawn.size(); rank++){
                if(everyone.get((int) drawn.get(rank)[1]).equals(sleeperID)){
                    survived++;
                    break;
                }
            }
        }
        return survived / (double) trials;
    }

    /**
     * What waiting is worth: take him now, or spend this pick elsewhere and
     * hope he lasts. Returns the expected points at the position if you wait,
     * which is his value when he survives and the next best when he does not.
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
