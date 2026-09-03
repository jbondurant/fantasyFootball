import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The simulator's schedule must stay in step with the real draft, and when it
 * cannot, every live tool must SAY SO.
 *
 * `DraftSimulator.stateAfter` advances its schedule index only inside the
 * `board.contains(sleeperID)` guard. That is right for a keeper - the loop
 * above it has already skipped his keeper slot, so consuming a live slot as
 * well would shift everything by one. It is wrong for a real pick of a man the
 * board does not carry: a kicker, someone past the ADP cut, an id we do not
 * know. He spends a live pick, the schedule does not move, and every later pick
 * is priced one seat early - silently.
 *
 * LiveBoard detects this. DraftNight - the Model A path - did not, and Model A
 * shares the same increment. This pins both the mechanism and the detector.
 *
 * The detector is the part that needed the test. LiveBoard's version compares
 * the slot's PICK NUMBER against `taken.size() + 1`, which is only the same
 * question when no keeper slot has gone by: a keeper slot is a pick number that
 * consumes no pick, so once the draft passes one, pick number and pick count
 * part company permanently and the check fires on a perfectly clean board.
 * `DraftNight.scheduleDrift` counts LIVE slots instead, which is the quantity
 * `taken.size()` actually measures.
 */
class ScheduleDriftTest {

    private static final Player QB_A = TestPlayers.player("Al", "Arm", "AAA", Position.QB, 101);
    private static final Player RB_A = TestPlayers.player("Cy", "Cut", "CCC", Position.RB, 103);
    private static final Player RB_B = TestPlayers.player("Dee", "Dash", "DDD", Position.RB, 104);
    private static final Player WR_A = TestPlayers.player("Ed", "End", "EEE", Position.WR, 105);
    private static final Player WR_B = TestPlayers.player("Fay", "Fly", "FFF", Position.WR, 106);
    /** On Sleeper but never on our board - a kicker (Position.OTHER here) is the everyday case. */
    private static final Player KICKER = TestPlayers.player("Gus", "Goal", "GGG",
            Position.OTHER, 107);

    @BeforeEach
    void handmadeLeague(){
        Player.indexForTest(TestPlayers.listOf(QB_A, RB_A, RB_B, WR_A, WR_B, KICKER));
    }

    @AfterEach
    void cleanUp(){
        Player.resetIndexForTest();
    }

    private static Map<String, Double> adp(){
        Map<String, Double> adp = new HashMap<>();
        adp.put("103", 1.0);
        adp.put("105", 2.0);
        adp.put("104", 3.0);
        adp.put("106", 4.0);
        adp.put("101", 5.0);
        return adp;              // 107, the kicker, is NOT on the board
    }

    private static Map<String, Double> points(){
        Map<String, Double> points = new HashMap<>();
        points.put("101", 360.0);
        points.put("103", 280.0);
        points.put("104", 250.0);
        points.put("105", 270.0);
        points.put("106", 240.0);
        points.put("107", 120.0);
        return points;
    }

    /** Six live slots; `keeperAt` marks one of them as selecting nobody. */
    private static DraftSimulator simulator(int keeperAt){
        List<DraftSimulator.Slot> schedule = new ArrayList<>();
        for(int pick = 1; pick <= 6; pick++){
            schedule.add(new DraftSimulator.Slot(pick, (pick + 1) / 2,
                    pick % 2 == 1 ? "alice" : "bob", pick == keeperAt));
        }
        double[] beta = java.util.Arrays.copyOf(new double[]{1}, SelectionModel.FEATURES);
        return new DraftSimulator(schedule, new ArrayList<>(adp().keySet()), adp(), points(),
                new HashMap<>(), new SelectionModel(beta), Map.of());
    }

    @Test
    void aPickOfAManTheBoardDoesNotCarryStallsTheSchedule(){
        DraftSimulator simulator = simulator(0);

        // Two ordinary picks: the simulator is on pick 3, as it should be.
        DraftSimulator.SimState clean = simulator.stateAfter(List.of("103", "105"));
        assertEquals(3, simulator.slotOf(clean).pickNumber());

        // The same two picks with a kicker taken between them. THREE picks are
        // in, so the draft is really on pick 4 - but the kicker never reached
        // the board, so the increment inside the contains() guard never ran.
        DraftSimulator.SimState drifted = simulator.stateAfter(List.of("103", "107", "105"));
        assertEquals(3, simulator.slotOf(drifted).pickNumber(),
                "stateAfter advances only for a man on the board, so an off-board"
                        + " pick leaves the schedule one slot behind - this is the fault,"
                        + " recorded as it stands");

        // And the consequence that reaches Model A: the roster scan asks which
        // slot each id landed in, so a drifted schedule hands a man to the
        // wrong manager. Pick 2 is bob's; after the drift alice's second pick
        // is attributed there.
        assertEquals(2, drifted.takenAtOf("105"),
                "the receiver really went at pick 3 (alice's) but the drifted"
                        + " schedule records him at pick 2, which is bob's");
        assertEquals("bob", simulator.slotAt(drifted.takenAtOf("105")).manager());
    }

    @Test
    void draftNightAnnouncesTheDriftInsteadOfPricingTheWrongSlot(){
        DraftSimulator simulator = simulator(0);

        DraftSimulator.SimState clean = simulator.stateAfter(List.of("103", "105"));
        assertNull(DraftNight.scheduleDrift(simulator, clean, 2),
                "a board in step must print nothing");

        DraftSimulator.SimState drifted = simulator.stateAfter(List.of("103", "107", "105"));
        String warning = DraftNight.scheduleDrift(simulator, drifted, 3);
        assertNotNull(warning, "three picks are in and the simulator is on its third"
                + " slot - Model A must say so rather than answer for the wrong seat");
        assertTrue(warning.contains("SCHEDULE DRIFT"), warning);
    }

    /**
     * The detector must not cry wolf. A keeper slot is a pick number that
     * consumes no pick, so `slot.pickNumber() == taken.size() + 1` - the form
     * LiveBoard shipped - is false on a perfectly clean board the moment the
     * draft passes one. This league has twenty-four of them.
     */
    @Test
    void aKeeperSlotIsNotDrift(){
        DraftSimulator simulator = simulator(2);   // pick 2 selects nobody

        // Two live picks are in. They filled slots 1 and 3, so the draft is on
        // slot 4 - a pick NUMBER of 4 against a pick COUNT of 2.
        DraftSimulator.SimState state = simulator.stateAfter(List.of("103", "105"));
        assertEquals(4, simulator.slotOf(state).pickNumber());
        assertEquals(4, state.takenAtOf("105") + 1);

        assertNull(DraftNight.scheduleDrift(simulator, state, 2),
                "the keeper slot at pick 2 explains the gap exactly - warning here"
                        + " tells Justin to distrust a tool that is working");
    }

    /**
     * `branchWith` must put the pick where `slotOf` says the draft is.
     *
     * slotOf() scans FORWARD past keeper slots without writing the index back,
     * so a state whose scheduleIndex is sitting on a keeper slot reports one
     * pick and branches into another. WaitCheck - Model A's own wait-or-take
     * table - branches straight off a state from stateAfter and would drop its
     * "spend this pick elsewhere" man into the keeper slot: attributed to the
     * keeper's owner, consuming no live pick, and leaving one extra real pick
     * to be simulated before Justin's next turn. LiveInsurance, LateWaitOrTake,
     * MarginalTrace, DryRun and PolicyTournament's probe all branch the same way.
     */
    @Test
    void branchingPutsThePickWhereSlotOfSaysTheDraftIs(){
        DraftSimulator simulator = simulator(2);   // pick 2 selects nobody

        // One live pick fills slot 1, so scheduleIndex now sits on the keeper
        // slot at pick 2 and slotOf has to skip it.
        DraftSimulator.SimState state = simulator.stateAfter(List.of("103"));
        assertEquals(3, simulator.slotOf(state).pickNumber());

        DraftSimulator.SimState branch = simulator.branchWith(state, "105");
        assertEquals(3, branch.takenAtOf("105"),
                "slotOf reported pick 3, so the branch must spend pick 3 - not the"
                        + " keeper slot at pick 2, which belongs to somebody else and"
                        + " consumes no pick at all");
        assertEquals(4, simulator.slotOf(branch).pickNumber(),
                "and the branch must then be on pick 4");
    }
}
