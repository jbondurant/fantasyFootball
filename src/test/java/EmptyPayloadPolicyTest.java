import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * "Empty" means two different things and only the caller knows which.
 *
 * For a stats or projection feed it means "not yet" - the 2026 week-1 stats
 * endpoint answered {} five days before kickoff - and freezing that would have
 * been the answer all season. For a week of transactions it means "none", and
 * week 18 of a finished season will say so forever. The first guard turned the
 * second case into a fatal error on any machine without the files already
 * cached, which is a regression this pins.
 */
public class EmptyPayloadPolicyTest {

    @Test
    public void theFeedsThatMeanNotYetRefuseToBeFrozen() throws Exception {
        String source = Files.readString(Path.of("src", "main", "java", "LeagueWeek.java"));
        assertTrue(source.contains("getCachedForever(url, immutableName)"),
                "a finished week is still kept forever");
        assertTrue(source.contains("getTodaysWebPage(url, liveName)"),
                "a live week is day-cached, never frozen");
    }

    @Test
    public void theFeedsWhoseEmptyIsAnAnswerSaySo() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("src", "main", "java", "LeagueTransactions.java"));
        boolean allowed = false;
        for(int i = 0; i < lines.size(); i++){
            if(lines.get(i).contains("static String transactionsRaw")){
                for(int j = i; j < Math.min(i + 5, lines.size()); j++){
                    allowed |= lines.get(j).contains("getCachedForeverAllowingEmpty");
                }
            }
        }
        assertTrue(allowed, "a quiet week of transactions is a real empty answer and must still cache;"
                + " week 18 of every finished season is [] forever");
    }
}
