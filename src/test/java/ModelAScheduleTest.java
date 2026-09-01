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

    private String was;

    @AfterEach
    public void restore(){
        if(was == null){
            System.clearProperty("scheduleRounds");
        }
        else {
            System.setProperty("scheduleRounds", was);
        }
    }

    private List<Position> planAt(String rounds) throws Exception {
        was = System.getProperty("scheduleRounds");
        System.setProperty("scheduleRounds", rounds);
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        return planner.plan(120, 0.0, 0.10, DraftSimulator.SEED).positions();
    }

    @Test
    public void theProvenRoundsAgreeAcrossSchedules() throws Exception {
        List<Position> nine = planAt("9");
        List<Position> sixteen = planAt("16");
        assertTrue(nine.size() >= 7 && sixteen.size() >= 7,
                "both schedules must reach round 7");
        assertEquals(nine.subList(0, 7), sixteen.subList(0, 7),
                "Model A is only trusted in rounds 1-7 and DRAFT-READY records"
                        + " the nine-round shape, but Draft2026 runs the"
                        + " sixteen-round one. They must not disagree there.");
    }

    @Test
    public void theSixteenRoundPlanIsLongerThanTheNine() throws Exception {
        assertTrue(planAt("16").size() > planAt("9").size(),
                "the sixteen-round schedule must give Justin more live seats -"
                        + " if not, scheduleRounds is not reaching the planner");
    }
}
