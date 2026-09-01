import PlayerImportAndSetup.Position;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The simulated room must draft each position roughly when the real one does.
 *
 * Everything the board model concludes rests on this. It does not consult a
 * rule about defences; it simulates the other eleven managers and prices what
 * survives. If the simulated room reaches for a position the real room leaves
 * alone, the model correctly concludes it must reach too - from a false
 * premise.
 *
 * Measured 2026-09-01 (DefenceReality section 4): across five real drafts this
 * league took 58 defences, NONE before round 10, median round 15. The simulated
 * room was taking 19% of them in rounds 1-9 and one as early as round 4,
 * because the feature set had positional intercepts for QB, RB and TE but not
 * DEF, and no measure of draft depth at all - so a depth-2 tree could not
 * express "this position AND late".
 *
 * The fix was general - complete the intercepts, add the depth feature - and
 * the guard is general too: every position is checked against its own history.
 */
public class RoomTimingTest {

    @Test
    public void theSimulatedRoomDoesNotReachForAPositionTheRealRoomLeavesAlone()
            throws Exception {
        // PIN THE SCHEDULE. LiveSetup.forTonight honours an existing
        // scheduleRounds so the nine-round tools still work, which means a test
        // that ran earlier in this JVM and set it to 9 leaks into this one -
        // and a nine-round board carries no defences at all, so the whole test
        // asserts nothing. It failed exactly that way. System properties are
        // shared mutable state and test order is not guaranteed.
        String was = System.getProperty("scheduleRounds");
        System.setProperty("scheduleRounds", "16");
        try {
        LiveSetup setup = LiveSetup.forTonight();
        DraftSimulator simulator = setup.simulator;

        Map<Position, List<Integer>> simulated = new EnumMap<>(Position.class);
        for(int trial = 0; trial < 25; trial++){
            Map<String, Integer> takenAt =
                    simulator.simulateOnce(new Random(4_242_000L + 7919L * trial));
            for(Map.Entry<String, Integer> entry : takenAt.entrySet()){
                Player player = Player.getPlayerFromSIDV2(entry.getKey());
                DraftSimulator.Slot slot = simulator.slotAt(entry.getValue());
                if(player != null && slot != null){
                    simulated.computeIfAbsent(player.position, u -> new ArrayList<>())
                            .add(slot.round());
                }
            }
        }

        // DIAGNOSE, DO NOT GUESS. This passes alone and fails in the full
        // suite, which means some earlier class leaves global state behind. Two
        // hypotheses have already been wrong, so the message now carries the
        // ambient state and one run settles it.
        int defencesOnBoard = 0;
        for(String id : simulator.players()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null && player.position == Position.DEF){
                defencesOnBoard++;
            }
        }
        int slots = 0;
        for(int p = 1; p <= 200; p++){
            if(simulator.slotAt(p) != null){
                slots++;
            }
        }
        String ambient = String.format(
                " [scheduleRounds=%s, DraftPlanner.scheduleRounds()=%d, board=%d,"
                        + " defences on board=%d, schedule slots=%d]",
                System.getProperty("scheduleRounds"), DraftPlanner.scheduleRounds(),
                simulator.players().size(), defencesOnBoard, slots);

        List<Integer> defences = simulated.get(Position.DEF);
        assertNotNull(defences,
                "the simulated board must contain defences at all" + ambient);
        assertTrue(defences.size() > 50,
                "expected plenty of simulated defence picks, got " + defences.size());
        double early = defences.stream().filter(round -> round <= 9).count()
                / (double) defences.size();
        assertTrue(early <= 0.03,
                "the simulated room takes " + Math.round(100 * early) + "% of its"
                        + " defences in rounds 1-9; this league has taken NONE"
                        + " before round 10 in five drafts, and a room that"
                        + " reaches makes the model reach");

        // And the skill positions must not have been dragged late by the fix.
        for(Position position : List.of(Position.RB, Position.WR)){
            List<Integer> rounds = simulated.get(position);
            assertNotNull(rounds, position + " must be drafted in the simulation");
            double earlySkill = rounds.stream().filter(round -> round <= 9).count()
                    / (double) rounds.size();
            assertTrue(earlySkill > 0.45,
                    position + " goes in rounds 1-9 " + Math.round(100 * earlySkill)
                            + "% of the time in simulation against about 64% in"
                            + " the real drafts - the depth feature must not have"
                            + " pushed the skill positions late");
        }
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
