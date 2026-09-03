import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * A man the board does not carry is still a pick that happened.
 *
 * Justin's roster was built by asking the simulator which slot each id landed
 * in, and DraftSimulator.stateAfter only places a man it carries - anyone past
 * ADP 250 is not one. So a deep sleeper he drafted himself vanished from his
 * own roster: the tool read a roster one short, priced the pick he had already
 * spent, and stayed one short for the rest of the draft.
 */
public class OffBoardPickTest {

    private static DraftPlanner planner() throws Exception {
        // minePicks walks the SEAT SCHEDULE, so the schedule has to be the
        // one Draft2026 builds or this tests a different set of seats.
        System.setProperty("scheduleRounds", "16");
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        return DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
    }

    @Test
    public void aManTheBoardDoesNotCarryStillCountsAsMyPick() throws Exception {
        DraftPlanner planner = planner();
        DraftSimulator simulator = planner.simulator();

        // Fill every live seat up to and including Justin's first with ids the
        // board has never heard of. His seat must still be attributed to him.
        List<String> taken = new ArrayList<>();
        int live = 0;
        int mineAt = -1;
        for(int p = 1; p <= 200; p++){
            DraftSimulator.Slot seat = simulator.slotAt(p);
            if(seat == null || seat.keeperSlot()){
                continue;
            }
            live++;
            taken.add("not-a-real-player-" + live);
            if(planner.me().equals(seat.manager())){
                mineAt = live;
                break;
            }
        }
        assertTrue(mineAt > 0, "the schedule must contain a seat of Justin's");

        List<String> mine = LiveBoard.minePicks(planner, simulator, taken);
        assertTrue(mine.contains("not-a-real-player-" + mineAt),
                "a pick Justin really made must be on his roster even when the"
                        + " board has never heard of the man");
        assertEquals(planner.myKeeperIDs().size() + 1, mine.size(),
                "exactly one of those seats was his");
    }

    @Test
    public void nobodyElsesPickLandsOnMyRoster() throws Exception {
        DraftPlanner planner = planner();
        DraftSimulator simulator = planner.simulator();
        List<String> taken = new ArrayList<>();
        int live = 0;
        int mineAt = -1;
        for(int p = 1; p <= 200 && live < 40; p++){
            DraftSimulator.Slot seat = simulator.slotAt(p);
            if(seat == null || seat.keeperSlot()){
                continue;
            }
            live++;
            taken.add("unknown-" + live);
            if(mineAt < 0 && planner.me().equals(seat.manager())){
                mineAt = live;
            }
        }
        List<String> mine = LiveBoard.minePicks(planner, simulator, taken);
        for(String id : mine){
            if(planner.myKeeperIDs().contains(id)){
                continue;
            }
            assertTrue(id.startsWith("unknown-"), "unexpected id " + id);
        }
        // Twelve teams, forty live picks: three or four seats are his.
        int drafted = mine.size() - planner.myKeeperIDs().size();
        assertTrue(drafted >= 3 && drafted <= 4,
                "expected 3-4 of 40 live seats to be his, got " + drafted);
    }
}
