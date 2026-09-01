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
        // EVERY STAGE WEIGHS EVERY POSITION STILL WORTH TAKING - which is not
        // always four.
        //
        // This asserted a flat 4 and passed until 2026-09-01, when the appetite
        // cap started counting the men Justin ALREADY OWNS. He keeps Purdy, the
        // cap allows two quarterbacks in total, so once the plan takes one more
        // the fourth option legitimately disappears and the stage weighs three.
        // The old assertion was written when the cap could not see keepers; it
        // was testing the bug.
        //
        // Note this test is only "no keeper" in the sense that it passes no
        // EXTRA keeper - extraKeepers are additional to the declared ones, so
        // Tuten and Purdy are on this roster regardless.
        int quarterbacksPlanned = 0;
        for(DraftPlanner.Stage stage : plan.stages()){
            int expected = quarterbacksPlanned >= 1 ? 3 : 4;
            Assertions.assertEquals(expected, stage.options().size(),
                    "a stage must weigh every position the cap still permits;"
                            + " with " + quarterbacksPlanned + " quarterback(s)"
                            + " already planned and Purdy held, that is "
                            + expected);
            Assertions.assertNotNull(stage.chosen());
            Assertions.assertTrue(
                    stage.options().stream().anyMatch(
                            option -> option.position() == stage.chosen()),
                    "the chosen position must be one it actually weighed");
            if(stage.chosen() == PlayerImportAndSetup.Position.QB){
                quarterbacksPlanned++;
            }
        }
        Assertions.assertTrue(quarterbacksPlanned <= 1,
                "he holds Purdy and the cap allows two in total, so the plan may"
                        + " add at most one - got " + quarterbacksPlanned);
        Assertions.assertTrue(plan.mean() > 1200 && plan.mean() < 2400,
                "best-nine expectation implausible: " + plan.mean());
        Assertions.assertTrue(plan.p10() <= plan.mean());
        Assertions.assertFalse(plan.snipes().isEmpty(),
                "the wait-or-take decomposition should have rows");
    }
}
