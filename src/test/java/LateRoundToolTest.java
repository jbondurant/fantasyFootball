import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * The late-round tool the screen names must run the whole draft.
 *
 * LateRoundTargets never sets scheduleRounds=16, so its survival race stops at
 * pick 108 - and Justin's last three picks are 162, 175 and 186. Two on-screen
 * instructions sent him to it anyway. LiveLateRounds sets the property.
 */
public class LateRoundToolTest {

    private static String read(String path) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of(path)));
    }

    @Test
    public void liveLateRoundsRunsTheWholeDraft() throws Exception {
        assertTrue(read("src/main/java/LiveLateRounds.java")
                        .contains("System.setProperty(\"scheduleRounds\""),
                "the tool the screen recommends must schedule all 16 rounds");
    }

    @Test
    public void theScreenDoesNotTellHimToRunTheShortTool() throws Exception {
        for(String path : new String[]{"src/main/java/DraftNight.java",
                "src/main/java/LiveCommittee.java"}){
            String source = read(path);
            assertFalse(source.contains("-Pmain=LateRoundTargets"),
                    path + " tells Justin to run a tool whose survival race"
                            + " stops at pick 108");
        }
    }
}
