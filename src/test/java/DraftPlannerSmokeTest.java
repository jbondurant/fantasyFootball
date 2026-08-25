import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The planner against the live 2026 board: every stage decided, values in a
 * plausible band, and the snipe decomposition populated. Rollout counts are
 * small - this checks the plumbing, not the recommendation.
 */
@Tag("smoke")
class DraftPlannerSmokeTest {

    @Test
    void theNoKeeperPlanCoversEveryLivePickWithSaneValues(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, null);

        DraftPlanner.Plan plan = planner.plan(40, 0.0, 0.10, 1L);

        int liveSlots = plan.stages().size();
        Assertions.assertTrue(liveSlots >= SelectionModel.GAME_ROUNDS - 2
                        && liveSlots <= SelectionModel.GAME_ROUNDS,
                "expected close to nine live picks, got " + liveSlots);
        Assertions.assertEquals(liveSlots, plan.positions().size());
        for(DraftPlanner.Stage stage : plan.stages()){
            Assertions.assertEquals(4, stage.options().size(),
                    "every stage weighs all four positions");
            Assertions.assertNotNull(stage.chosen());
        }
        Assertions.assertTrue(plan.mean() > 1200 && plan.mean() < 2400,
                "best-nine expectation implausible: " + plan.mean());
        Assertions.assertTrue(plan.p10() <= plan.mean());
        Assertions.assertFalse(plan.snipes().isEmpty(),
                "the wait-or-take decomposition should have rows");
    }
}
