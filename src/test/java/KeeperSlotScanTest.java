import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * "My next pick" must never be a keeper slot.
 *
 * Found by an adversarial audit the night before the draft, and it was certain
 * to fire. LiveBoard and Draft2026 both located Justin's next pick by scanning
 * for a slot whose manager is him, and neither asked `keeperSlot()`. Rounds 12
 * and 13 ARE his and select nobody - Tuten sits in pick 138 and Purdy in 151 -
 * so from about pick 113 until 152 the tool priced a pick that does not exist
 * and answered "nothing legal" on every refresh. That is precisely the stretch
 * where the tight end and the defence still have to be found.
 *
 * `slotOf()`, which finds where the DRAFT is, always asked. Only the scans for
 * where JUSTIN is did not, which is why nothing caught it: the tool worked
 * perfectly until the draft reached round 12.
 */
public class KeeperSlotScanTest {

    @Test
    void noKeeperSlotIsEverSelectableAsMyNextPick() throws Exception {
        // Both live tools set this at startup; without it the simulator builds
        // NINE rounds, Justin has nine picks and no keeper slots at all - which
        // is why the first version of this test failed and is worth knowing:
        // the fault is invisible on the default schedule.
        String was = System.getProperty("scheduleRounds");
        System.setProperty("scheduleRounds", "16");
        try {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        var earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        DraftSimulator simulator = planner.simulator();

        int mine = 0;
        int keeperSlotsOfMine = 0;
        for(int pick = 1; pick <= 200; pick++){
            DraftSimulator.Slot slot = simulator.slotAt(pick);
            if(slot == null || !planner.me().equals(slot.manager())){
                continue;
            }
            if(slot.keeperSlot()){
                keeperSlotsOfMine++;
            }
            else {
                mine++;
            }
        }
        assertEquals(2, keeperSlotsOfMine,
                "Tuten and Purdy occupy two slots that are Justin's and select nobody"
                        + " - if this is not 2 the fixture has changed and the scans"
                        + " below mean something different");
        assertEquals(14, mine,
                "fourteen live picks after the two keeper rounds are removed");

        // The fix itself: the scan LiveBoard and Draft2026 use must skip them.
        int chosen = -1;
        for(int p = 130; p <= 200; p++){
            DraftSimulator.Slot slot = simulator.slotAt(p);
            if(slot != null && planner.me().equals(slot.manager())
                    && !slot.keeperSlot()){
                chosen = p;
                break;
            }
        }
        assertEquals(162, chosen,
                "scanning forward from pick 130 must land on 162, not on Tuten's"
                        + " keeper slot at 138");
        }
        finally {
            if(was == null){
                System.clearProperty("scheduleRounds");
            }
            else {
                System.setProperty("scheduleRounds", was);
            }
        }
    }
}
