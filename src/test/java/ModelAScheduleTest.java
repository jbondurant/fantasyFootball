import PlayerImportAndSetup.Position;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Model A's proven domain must not depend on the schedule it was built with.
 *
 * `DraftPlanner`'s own main runs the NINE-round game, and that is the shape
 * DRAFT-READY records. `Draft2026` runs Model A at SIXTEEN. If those disagreed
 * in rounds 1-7, the doc would be describing a model Justin never sees - and
 * the header said "nine-round plan" under both, so nothing on screen would have
 * told him.
 *
 * They agree. This is what keeps that true.
 */
public class ModelAScheduleTest {

    // CAPTURE ONCE. planAt() is called twice per test - nine rounds then
    // sixteen - and this used to record the previous value on EVERY call, so
    // the second capture stored "9" and @AfterEach restored 9 rather than
    // whatever the JVM started with. That leaks a nine-round schedule into
    // every test that runs after this class in the same JVM, and a nine-round
    // board carries no defences at all.
    private String was;
    private boolean captured;

    @AfterEach
    public void restore(){
        captured = false;
        if(was == null){
            System.clearProperty("scheduleRounds");
        }
        else {
            System.setProperty("scheduleRounds", was);
        }
    }

    private DraftPlanner.Plan planAt(String rounds) throws Exception {
        if(!captured){
            was = System.getProperty("scheduleRounds");
            captured = true;
        }
        System.setProperty("scheduleRounds", rounds);
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        return planner.plan(120, 0.0, 0.10, DraftSimulator.SEED);
    }

    /** The nine-round plan's mean for a position at one of its stages. */
    private static double meanOf(DraftPlanner.Stage stage, Position position){
        for(DraftPlanner.PositionValue option : stage.options()){
            if(option.position() == position){
                return option.mean();
            }
        }
        return Double.NaN;
    }

    /**
     * Where the nine-round plan has a CLEAR preference - its chosen position
     * beats the one the sixteen-round plan chose by more than two standard
     * errors of the rollout mean, the repo's own tie convention - the two
     * schedules must agree. Where the gap is inside that noise the round is a
     * coin flip, and a coin flip may land differently under a different tail:
     * the round-2 RB/WR choice did exactly that on 2026-09-01 (the same pick
     * Justin faced live at 18, where the board model called it inside the
     * noise). Demanding an identical shape there was asserting the seed, not
     * the model.
     */
    @Test
    public void theProvenRoundsAgreeAcrossSchedules() throws Exception {
        DraftPlanner.Plan nine = planAt("9");
        DraftPlanner.Plan sixteen = planAt("16");
        assertTrue(nine.positions().size() >= 7 && sixteen.positions().size() >= 7,
                "both schedules must reach round 7");
        double tie = 2 * nine.standardError();
        List<String> clearDisagreements = new ArrayList<>();
        List<String> coinFlips = new ArrayList<>();
        for(int round = 0; round < 7; round++){
            Position a = nine.positions().get(round);
            Position b = sixteen.positions().get(round);
            if(a == b){
                continue;
            }
            DraftPlanner.Stage stage = nine.stages().get(round);
            double gap = meanOf(stage, a) - meanOf(stage, b);
            String line = String.format("round %d: nine %s, sixteen %s, nine's gap %.1f vs tie %.1f",
                    round + 1, a, b, gap, tie);
            (gap > tie ? clearDisagreements : coinFlips).add(line);
        }
        if(!coinFlips.isEmpty()){
            System.out.println("schedules differ only at coin flips: " + coinFlips);
        }
        assertTrue(clearDisagreements.isEmpty(),
                "Model A is only trusted in rounds 1-7 and DRAFT-READY records"
                        + " the nine-round shape, but Draft2026 runs the"
                        + " sixteen-round one. They disagree where the nine-round"
                        + " plan is NOT a coin flip: " + clearDisagreements);
    }

    @Test
    public void theSixteenRoundPlanIsLongerThanTheNine() throws Exception {
        assertTrue(planAt("16").positions().size() > planAt("9").positions().size(),
                "the sixteen-round schedule must give Justin more live seats -"
                        + " if not, scheduleRounds is not reaching the planner");
    }
}
