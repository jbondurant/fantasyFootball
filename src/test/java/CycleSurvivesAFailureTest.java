import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * ONE BAD CYCLE MUST NOT END THE NIGHT.
 *
 * Draft2026 warms both engines once - now including the survival table - and
 * then loops. Between LiveDraft.freeze and LiveDraft.thaw the cycle calls three
 * things that are declared `throws Exception` and only ONE of them is guarded:
 *
 *     LiveDraft.freeze(draftID)          <- fetches Sleeper; can throw
 *     roundNow(...)                      <- unguarded
 *     LiveBoard.answer(...)              <- unguarded
 *     DraftNight.answer(...)             <- wrapped in try/catch already
 *     LiveBoard.stillNeeds(...)          <- unguarded
 *     LiveDraft.thaw()                   <- last statement, NOT a finally
 *
 * So anything the board model throws - or one refused HTTP read from Sleeper on
 * a draft-night network - propagates out of main and the process exits. Justin
 * is at a sixty-second clock and would have to pay the whole warm again, which
 * is the one cost this class exists to avoid paying twice.
 *
 * DraftNight's own loop already has exactly this guard around its answer(); the
 * board model, added later, never got one. The asymmetry is the fault: the
 * FASTER, more trusted engine is the one that can kill the session.
 *
 * The thaw belongs in a finally for the same reason - a snapshot that outlives
 * its cycle is a board frozen for the rest of the draft, which is worse than no
 * snapshot at all because it looks completely normal.
 */
public class CycleSurvivesAFailureTest {

    private static String draft2026() throws Exception {
        return Files.readString(Path.of("src/main/java/Draft2026.java"));
    }

    /** The freeze/thaw pair must be a try/finally, not two statements in a row. */
    @Test
    public void theThawIsInAFinally() throws Exception {
        String source = draft2026();
        int freeze = source.indexOf("LiveDraft.freeze(draftID)");
        int thaw = source.indexOf("LiveDraft.thaw()");
        assertTrue(freeze > 0 && thaw > freeze, "the cycle must freeze then thaw");
        String between = source.substring(freeze, thaw);
        assertTrue(between.contains("try {"),
                "everything between the freeze and the thaw must sit in a try -"
                        + " otherwise a throw leaves the board frozen and, in"
                        + " Draft2026, exits the tool");
        assertTrue(source.substring(0, thaw).lastIndexOf("finally")
                        > source.substring(0, thaw).lastIndexOf("try {"),
                "the thaw must be the finally of that try, so it runs on the way"
                        + " out however the cycle ends");
    }

    /**
     * The board model must be caught the way Model A already is.
     *
     * Not "must never throw" - it can, on a board nobody anticipated - but a
     * throw must cost one cycle and not the warm engine behind it.
     */
    @Test
    public void aThrowFromTheBoardModelCostsOneCycleNotTheSession() throws Exception {
        String source = draft2026();
        int answer = source.indexOf("LiveBoard.answer(");
        assertTrue(answer > 0, "Draft2026 must call the board model");
        String before = source.substring(0, answer);
        int cycleTop = before.lastIndexOf("long began = System.nanoTime();");
        int freeze = before.lastIndexOf("LiveDraft.freeze(draftID)");
        int guard = before.lastIndexOf("try {");
        assertTrue(cycleTop > 0 && freeze > cycleTop,
                "the cycle must start, then snapshot the board");
        // The guard has to open before the freeze, because the freeze reads
        // Sleeper and is itself the likeliest thing in the cycle to throw.
        assertTrue(guard > cycleTop && guard < freeze,
                "LiveBoard.answer must sit inside a try that opens at the top of"
                        + " this cycle and covers the freeze too; DraftNight.answer"
                        + " has had that guard all along, and without it a throw"
                        + " here exits main and loses the warm engine");
    }

    /** Whatever the cycle does, the next one must read a fresh board. */
    @Test
    public void aFailedCycleLeavesNoStaleSnapshot(){
        LiveDraft.freezeWith(java.util.List.of("a", "b", "c"));
        assertEquals(3, LiveDraft.frozenSize(), "the fixture must be frozen");
        try {
            throw new IllegalStateException("the board model fell over");
        }
        catch(RuntimeException expected){
            LiveDraft.thaw();
        }
        assertEquals(-1, LiveDraft.frozenSize(),
                "a cycle that threw must still release its snapshot");
    }
}
