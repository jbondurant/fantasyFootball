import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The two halves of the draft screen must answer about the same board.
 *
 * Draft2026 reads Sleeper's picks three times per cycle - roundNow,
 * LiveBoard.answer, DraftNight.answer - and Model A takes sixteen seconds, so
 * without a freeze a manager picking mid-cycle makes the board model and Model
 * A describe different boards. During a run at a position, which is precisely
 * what the board model exists to catch.
 */
public class OneBoardPerCycleTest {

    @Test
    public void notFrozenByDefault(){
        LiveDraft.thaw();
        assertEquals(-1, LiveDraft.frozenSize(),
                "a thawed LiveDraft must report no snapshot");
    }

    @Test
    public void thawIsIdempotent(){
        LiveDraft.thaw();
        LiveDraft.thaw();
        assertEquals(-1, LiveDraft.frozenSize());
    }

    @Test
    public void draft2026FreezesAndThawsEveryCycle() throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/java/Draft2026.java")));
        assertTrue(source.contains("LiveDraft.freeze(draftID)"),
                "Draft2026 must take one snapshot per cycle");
        assertTrue(source.contains("LiveDraft.thaw()"),
                "Draft2026 must release the snapshot so the next cycle sees"
                        + " new picks - a freeze that never thaws is a board"
                        + " frozen for the whole draft, which is worse");
        assertTrue(source.indexOf("LiveDraft.freeze(draftID)")
                        < source.indexOf("LiveDraft.thaw()"),
                "the freeze must precede the thaw in the cycle");
    }

    @Test
    public void everyLivePicksCallerGoesThroughTheFreeze() throws Exception {
        // livePicks is the single door. If a second fetch path appears, the
        // freeze stops covering the screen and this test should fail.
        String source = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/java/LiveDraft.java")));
        int fetches = source.split("getLiveWebPage", -1).length - 1;
        assertEquals(1, fetches,
                "exactly one place may fetch the picks endpoint, or freezing"
                        + " it cannot guarantee one board per screen");
    }
}
