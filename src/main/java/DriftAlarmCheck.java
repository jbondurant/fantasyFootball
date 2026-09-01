import java.util.*;

/**
 * How often does the drift warning fire on a CLEAN board?
 *
 * It must be zero. The first version fired at 137 of 169 refreshes on a clean
 * replay because slotOf() skips keeper slots and its pick number therefore runs
 * ahead of the count - and told Justin to distrust the board model from round 3
 * onward. A false alarm that makes him abandon the tool is worse than the silent
 * fault it was added to catch.
 *
 *   ./gradlew run -Pmain=DriftAlarmCheck -Pkeepers=Tuten,Purdy -q
 */
public class DriftAlarmCheck {
    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", "16");
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        var earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        DraftSimulator simulator = planner.simulator();

        // Replay a clean draft one pick at a time, exactly as a refresh would.
        DraftSimulator.SimState state = simulator.initialState();
        Random random = new Random(20260901L);
        List<String> taken = new ArrayList<>();
        int aheadFires = 0;
        int behindFires = 0;
        int refreshes = 0;
        while(simulator.slotOf(state) != null && taken.size() < 190){
            DraftSimulator.Slot slot = simulator.slotOf(state);
            refreshes++;
            if(slot.pickNumber() > taken.size() + 1){
                aheadFires++;
            }
            if(slot.pickNumber() < taken.size() + 1){
                behindFires++;
            }
            String before = null;
            for(String id : planner.points().keySet()){
                if(state.takenAtOf(id) == null){
                    before = id;
                    break;
                }
            }
            state = simulator.branchWith(state, before);
            taken.add(before);
        }
        System.out.printf("%nCLEAN REPLAY, %d refreshes%n%n", refreshes);
        System.out.printf("   simulator AHEAD of the count  %d times  <- keeper slots,"
                + " expected, NOT drift%n", aheadFires);
        System.out.printf("   simulator BEHIND the count    %d times  <- the real fault%n",
                behindFires);
        System.out.printf("%nthe warning now fires only on BEHIND, so a clean board is"
                + " silent.%nthe old test was inequality, which fired on every AHEAD too.%n");
    }
}
