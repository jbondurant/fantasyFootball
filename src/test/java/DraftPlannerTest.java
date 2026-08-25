import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The planner on a handmade board, offline. The centerpiece is MODEL.md's
 * acceptance case: a solid RB now against a star WR now, where waiting on the
 * WR position wins on expectation but collapses when the star is sniped -
 * the recommendation must flip as lambda rises.
 */
class DraftPlannerTest {

    private static double[] beta(double... leading){
        return java.util.Arrays.copyOf(leading, SelectionModel.FEATURES);
    }

    private static final Player RB_STAR = TestPlayers.player("Rex", "Star", "AAA", Position.RB, 301);
    private static final Player RB_POOR = TestPlayers.player("Rod", "Poor", "BBB", Position.RB, 302);
    private static final Player WR_STAR = TestPlayers.player("Walt", "Star", "CCC", Position.WR, 303);
    private static final Player WR_POOR = TestPlayers.player("Wes", "Poor", "DDD", Position.WR, 304);
    private static final Player QB_F1 = TestPlayers.player("Quin", "One", "EEE", Position.QB, 305);
    private static final Player QB_F2 = TestPlayers.player("Quil", "Two", "FFF", Position.QB, 306);
    private static final Player TE_F1 = TestPlayers.player("Ted", "One", "GGG", Position.TE, 307);
    private static final Player TE_F2 = TestPlayers.player("Tom", "Two", "HHH", Position.TE, 308);

    @BeforeEach
    void handmadeLeague(){
        Player.indexForTest(TestPlayers.listOf(
                RB_STAR, RB_POOR, WR_STAR, WR_POOR, QB_F1, QB_F2, TE_F1, TE_F2));
    }

    @AfterEach
    void cleanUp(){
        Player.resetIndexForTest();
    }

    @Test
    void bestNineFillsFixedSlotsThenFlexesTheLeftovers(){
        Map<String, Double> points = new HashMap<>();
        points.put("301", 120.0);   // RB
        points.put("302", 20.0);    // RB
        points.put("303", 150.0);   // WR
        points.put("304", 40.0);    // WR
        points.put("305", 90.0);    // QB
        points.put("306", 80.0);    // QB - second QB can never count
        points.put("307", 50.0);    // TE
        points.put("308", 30.0);    // TE - flexes

        double score = StartingLineup.bestNine(points.keySet(), points);

        // QB 90 + RB 120+20 + WR 150+40 + TE 50 + flex TE 30. The second QB
        // contributes nothing; only seven of the nine slots can be filled.
        Assertions.assertEquals(90 + 120 + 20 + 150 + 40 + 50 + 30, score, 1e-9);
    }

    @Test
    void bestNineOfNobodyIsZero(){
        Assertions.assertEquals(0.0, StartingLineup.bestNine(List.of(), new HashMap<>()), 1e-9);
    }

    // The acceptance scenario: my picks 1 and 4, opponents at 2 and 3.
    //   RB branch: RB star (120) now; WR star (150, mid ADP) usually survives
    //     to pick 4, but opponents reach for him sometimes - fallback WR 40.
    //   WR branch: WR star (150) now; the RB star (top ADP) is nearly always
    //     gone by pick 4 - fallback RB 20, low mean but almost no variance.
    private static DraftPlanner planner(){
        Map<String, Double> points = new HashMap<>();
        points.put("301", 120.0);
        points.put("302", 20.0);
        points.put("303", 150.0);
        points.put("304", 40.0);
        points.put("305", 5.0);
        points.put("306", 3.0);
        points.put("307", 4.0);
        points.put("308", 2.0);
        Map<String, Double> adp = new HashMap<>();
        adp.put("301", 1.5);
        adp.put("305", 2.5);
        adp.put("307", 3.5);
        adp.put("303", 5.0);
        adp.put("306", 6.0);
        adp.put("308", 7.0);
        adp.put("304", 40.0);
        adp.put("302", 50.0);

        List<DraftSimulator.Slot> schedule = List.of(
                new DraftSimulator.Slot(1, 1, "me", false),
                new DraftSimulator.Slot(2, 1, "opp", false),
                new DraftSimulator.Slot(3, 1, "opp", false),
                new DraftSimulator.Slot(4, 2, "me", false));
        DraftSimulator simulator = new DraftSimulator(schedule, new ArrayList<>(adp.keySet()),
                adp, points, new HashMap<>(),
                new SelectionModel(beta(2)), Map.of());
        return new DraftPlanner(simulator, "me", List.of(), points);
    }

    @Test
    void pureExpectationWaitsOnTheReceiver(){
        DraftPlanner.Plan plan = planner().plan(600, 0.0, 0.10, 11L);

        Assertions.assertEquals(Position.RB, plan.stages().get(0).chosen(),
                "at lambda 0 the mean-optimal line takes the RB and waits on WR");
        Assertions.assertTrue(plan.mean() > 220,
                "RB-then-WR should average above 220, got " + plan.mean());
        Assertions.assertTrue(plan.p10() < plan.mean() - 40,
                "the sniped tail should show up in p10");
    }

    @Test
    void riskAversionFlipsToTheBirdInHand(){
        DraftPlanner.Plan plan = planner().plan(600, 2.0, 0.10, 11L);

        Assertions.assertEquals(Position.WR, plan.stages().get(0).chosen(),
                "at lambda 2 the certain 150 should beat the sometimes-sniped wait");
    }

    @Test
    void theSnipeDecompositionNamesTheThreat(){
        DraftPlanner.Plan plan = planner().plan(600, 0.0, 0.10, 11L);

        DraftPlanner.SnipeRow wrRow = plan.snipes().stream()
                .filter(row -> row.pickNumber() == 1 && row.position() == Position.WR)
                .findFirst().orElseThrow();
        Assertions.assertEquals("Walt Star", wrRow.usualTarget());
        Assertions.assertTrue(wrRow.probabilityGone() > 0.03 && wrRow.probabilityGone() < 0.6,
                "snipe probability implausible: " + wrRow.probabilityGone());
        // Fallback is Wes Poor (150 - 40 = 110), or worse on the rare rollout
        // where the opponents also took him and the WR cupboard is bare.
        Assertions.assertTrue(wrRow.meanDropWhenGone() >= 110.0 - 1e-9
                        && wrRow.meanDropWhenGone() <= 150.0,
                "drop when sniped should be 110-150, got " + wrRow.meanDropWhenGone());
    }

    @Test
    void aKeeperCountsTowardTheNineWithoutSpendingAPick(){
        // Same board, but I already hold the WR star as an out-of-game keeper:
        // both my picks are free for other positions and he still scores.
        Map<String, Double> points = new HashMap<>();
        points.put("301", 120.0);
        points.put("303", 150.0);
        points.put("305", 90.0);
        Map<String, Double> adp = new HashMap<>();
        adp.put("301", 1.0);
        adp.put("305", 2.0);

        List<DraftSimulator.Slot> schedule = List.of(
                new DraftSimulator.Slot(1, 1, "me", false),
                new DraftSimulator.Slot(2, 1, "me", false));
        DraftSimulator simulator = new DraftSimulator(schedule, new ArrayList<>(adp.keySet()),
                adp, points, new HashMap<>(), new SelectionModel(beta(5)), Map.of());
        DraftPlanner planner = new DraftPlanner(simulator, "me", List.of("303"), points);

        DraftPlanner.Plan plan = planner.plan(50, 0.0, 0.10, 5L);

        Assertions.assertEquals(120 + 150 + 90, plan.mean(), 1e-9,
                "kept WR + drafted RB and QB should all count");
    }
}
