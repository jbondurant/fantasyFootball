import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** The simulator's mechanics on a handmade board - no network, no Sleeper. */
class DraftSimulatorTest {

    /** Pads a leading-coefficient vector out to however many features exist. */
    private static double[] beta(double... leading){
        return java.util.Arrays.copyOf(leading, SelectionModel.FEATURES);
    }

    private static final Player QB_A = TestPlayers.player("Al", "Arm", "AAA", Position.QB, 101);
    private static final Player QB_B = TestPlayers.player("Bo", "Boot", "BBB", Position.QB, 102);
    private static final Player RB_A = TestPlayers.player("Cy", "Cut", "CCC", Position.RB, 103);
    private static final Player RB_B = TestPlayers.player("Dee", "Dash", "DDD", Position.RB, 104);
    private static final Player WR_A = TestPlayers.player("Ed", "End", "EEE", Position.WR, 105);
    private static final Player WR_B = TestPlayers.player("Fay", "Fly", "FFF", Position.WR, 106);

    @BeforeEach
    void handmadeLeague(){
        Player.indexForTest(TestPlayers.listOf(QB_A, QB_B, RB_A, RB_B, WR_A, WR_B));
    }

    @AfterEach
    void cleanUp(){
        Player.resetIndexForTest();
    }

    private static Map<String, Double> adp(){
        Map<String, Double> adp = new HashMap<>();
        adp.put("103", 1.0);   // RB_A the consensus first pick
        adp.put("105", 2.0);
        adp.put("104", 3.0);
        adp.put("106", 4.0);
        adp.put("101", 5.0);
        adp.put("102", 6.0);
        return adp;
    }

    private static Map<String, Double> points(){
        Map<String, Double> points = new HashMap<>();
        points.put("101", 360.0);
        points.put("102", 330.0);
        points.put("103", 280.0);
        points.put("104", 250.0);
        points.put("105", 270.0);
        points.put("106", 240.0);
        return points;
    }

    private static DraftSimulator simulator(double[] beta, List<DraftSimulator.Slot> schedule,
                                            Map<String, Map<Position, Integer>> keeperRosters){
        return new DraftSimulator(schedule, new ArrayList<>(adp().keySet()), adp(), points(),
                keeperRosters, new SelectionModel(beta), Map.of());
    }

    private static List<DraftSimulator.Slot> twoManagerSchedule(boolean secondSlotIsKeeper){
        return List.of(
                new DraftSimulator.Slot(1, 1, "alice", false),
                new DraftSimulator.Slot(2, 1, "bob", secondSlotIsKeeper),
                new DraftSimulator.Slot(3, 2, "bob", false),
                new DraftSimulator.Slot(4, 2, "alice", false));
    }

    @Test
    void keeperSlotsConsumeNoPlayer(){
        DraftSimulator simulator = simulator(beta(1),
                twoManagerSchedule(true), new HashMap<>());
        Map<String, Integer> takenAt = simulator.simulateOnce(new Random(1));

        Assertions.assertEquals(3, takenAt.size(), "three live slots should take three players");
        Assertions.assertFalse(takenAt.containsValue(2), "the keeper slot must select nobody");
    }

    @Test
    void aDominantAdpModelDraftsTheConsensusFirstPickFirst(){
        DraftSimulator simulator = simulator(beta(12),
                twoManagerSchedule(false), new HashMap<>());
        int consensusFirst = 0;
        Random random = new Random(3);
        for(int trial = 0; trial < 50; trial++){
            if(Integer.valueOf(1).equals(simulator.simulateOnce(random).get("103"))){
                consensusFirst++;
            }
        }
        Assertions.assertTrue(consensusFirst >= 45,
                "ADP-1 player went first only " + consensusFirst + "/50 times");
    }

    @Test
    void saturationSteersAManagerWithAKeptQuarterbackAway(){
        // Alice already holds a QB; with a strong saturation penalty she should
        // avoid a second one even when the QB is the best ADP on the board.
        Map<String, Double> qbFirstAdp = adp();
        qbFirstAdp.put("101", 0.5);
        Map<String, Map<Position, Integer>> rosters = new HashMap<>();
        Map<Position, Integer> alice = new EnumMap<>(Position.class);
        alice.put(Position.QB, 1);
        rosters.put("alice", alice);

        DraftSimulator simulator = new DraftSimulator(
                List.of(new DraftSimulator.Slot(1, 1, "alice", false)),
                new ArrayList<>(qbFirstAdp.keySet()), qbFirstAdp, points(), rosters,
                new SelectionModel(beta(2, 0, 0, -10)), Map.of());

        Random random = new Random(5);
        for(int trial = 0; trial < 30; trial++){
            Map<String, Integer> takenAt = simulator.simulateOnce(random);
            Assertions.assertFalse(takenAt.containsKey("101") || takenAt.containsKey("102"),
                    "a saturated manager still took a QB");
        }
    }

    @Test
    void survivalStartsCertainAndOnlyFalls(){
        DraftSimulator simulator = simulator(beta(3),
                twoManagerSchedule(false), new HashMap<>());
        Map<String, double[]> matrix = simulator.survivalMatrix(new int[]{1, 3, 5}, 80, 9L);

        Assertions.assertEquals(6, matrix.size());
        for(Map.Entry<String, double[]> entry : matrix.entrySet()){
            double[] row = entry.getValue();
            Assertions.assertEquals(1.0, row[0], 1e-9,
                    entry.getKey() + " must survive to pick 1 by definition");
            for(int c = 1; c < row.length; c++){
                Assertions.assertTrue(row[c] <= row[c - 1] + 1e-9,
                        entry.getKey() + " survival rose from checkpoint " + (c - 1) + " to " + c);
                Assertions.assertTrue(row[c] >= 0 && row[c] <= 1);
            }
        }
    }

    @Test
    void theSameSeedReplaysTheSameDraft(){
        DraftSimulator simulator = simulator(beta(2, 1),
                twoManagerSchedule(false), new HashMap<>());
        Assertions.assertEquals(simulator.simulateOnce(new Random(42)),
                simulator.simulateOnce(new Random(42)));
    }

    @Test
    void realFirstRoundIgnoresKeepersAndLateRounds(){
        JsonArray picks = new JsonArray();
        picks.add(pick("101", "alice", 1, 1, true));    // kept QB does not count
        picks.add(pick("103", "alice", 3, 27, false));  // RB, not a QB
        picks.add(pick("102", "bob", 4, 40, false));    // bob's real first QB
        picks.add(pick("102", "carol", 10, 111, false));// outside the nine-round game

        Map<String, Integer> first = DraftSimulator.realFirstRound(picks, Position.QB);
        Assertions.assertEquals(Map.of("bob", 4), first);
    }

    private static JsonObject pick(String playerID, String manager, int round, int pickNumber,
                                   boolean keeper){
        JsonObject pick = new JsonObject();
        pick.addProperty("player_id", playerID);
        pick.addProperty("picked_by", manager);
        pick.addProperty("round", round);
        pick.addProperty("pick_no", pickNumber);
        pick.addProperty("is_keeper", keeper);
        return pick;
    }

}
