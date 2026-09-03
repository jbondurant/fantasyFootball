import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * A pick that changed hands after warm must be reported, not walked past.
 *
 * The seat schedule is built once from draft_order and cached for the life of
 * the process. A mid-draft pick trade swaps who owns a seat on Sleeper and
 * changes nothing in the tool - minePicks keeps walking the stale schedule and
 * hands picks to the wrong manager, silently. The slot-count drift detector
 * cannot see an owner swap. This can, from the picked_by the feed already sends.
 */
public class PickTradeTest {

    private static String scheduled(int pick){
        // twelve seats, snake, seat 7 is "me"
        int inRound = ((pick - 1) % 12) + 1;
        int round = (pick - 1) / 12 + 1;
        int seat = round % 2 == 1 ? inRound : 13 - inRound;
        return "manager-" + seat;
    }

    @Test
    public void aCleanBoardRaisesNoWarning(){
        Map<Integer, String> actual = new HashMap<>();
        for(int pick = 1; pick <= 30; pick++){
            actual.put(pick, scheduled(pick));
        }
        assertNull(DraftNight.scheduleOwnerDrift(PickTradeTest::scheduled, actual),
                "every pick made by the manager the schedule expected is not a mismatch");
    }

    @Test
    public void aSwappedPickIsNamed(){
        Map<Integer, String> actual = new HashMap<>();
        for(int pick = 1; pick <= 30; pick++){
            actual.put(pick, scheduled(pick));
        }
        // seats 3 and 9 traded their round-2 picks after warm
        actual.put(16, scheduled(22));
        actual.put(22, scheduled(16));
        String warning = DraftNight.scheduleOwnerDrift(PickTradeTest::scheduled, actual);
        assertNotNull(warning, "a traded pick must be reported");
        assertTrue(warning.contains("2 of 30"), "it must say how many were compared: " + warning);
        assertTrue(warning.contains("pick 16") && warning.contains("pick 22"),
                "it must name the swapped picks: " + warning);
        assertTrue(warning.contains("RESTART"), "it must tell him what to do");
    }

    @Test
    public void picksOffTheScheduleAreLeftToTheSlotCountDetector(){
        Map<Integer, String> actual = Map.of(999, "manager-1");
        assertNull(DraftNight.scheduleOwnerDrift(p -> null, actual),
                "an unknown pick number is the slot-count detector's job, not this one's");
    }

    /**
     * A harness freeze must freeze BOTH halves of the snapshot.
     *
     * freeze() captures picks and owners together; freezeWith(), used by every
     * offline harness, captured picks only - so the owner half fell through to
     * a live network fetch inside tools meant to run offline, and compared real
     * owners against simulated pick numbers. A bogus draft id makes the proof
     * self-evident: a real fetch would throw; an empty map means none happened.
     */
    @Test
    public void aHarnessFreezeLeavesNoOwnersToFetch() throws Exception {
        try {
            LiveDraft.freezeWith(List.of("sim-1", "sim-2"));
            Map<Integer, String> owners = LiveDraft.livePickOwners("not-a-real-draft-id");
            assertTrue(owners.isEmpty(),
                    "simulated picks have no real owners; the check must have"
                            + " nothing to compare and must not fetch");
        }
        finally {
            LiveDraft.thaw();
        }
    }

    /**
     * Keeper picks are never compared. A keeper cannot change hands mid-draft,
     * and a mock built from the league copies its keepers in with no league
     * user on them - the 2026-09-01 rehearsal alarmed on 22 of 24 such picks
     * before the mock had started. On the real draft all 24 matched anyway.
     */
    @Test
    public void keeperPicksAreNotCompared(){
        List<com.google.gson.JsonObject> picks = List.of(
                com.google.gson.JsonParser.parseString(
                        "{\"pick_no\":32,\"is_keeper\":true,\"picked_by\":\"\"}").getAsJsonObject(),
                com.google.gson.JsonParser.parseString(
                        "{\"pick_no\":8,\"is_keeper\":false,\"picked_by\":\"u8\"}").getAsJsonObject(),
                com.google.gson.JsonParser.parseString(
                        "{\"pick_no\":9,\"picked_by\":\"u9\"}").getAsJsonObject());
        Map<Integer, String> owners = LiveDraft.liveOwners(picks);
        assertEquals(Map.of(8, "u8", 9, "u9"), owners, "the keeper at 32 is not in the map");
    }
}
