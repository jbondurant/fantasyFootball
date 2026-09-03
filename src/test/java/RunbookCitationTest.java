import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.Test;

/**
 * A line number cited in code must still point at the rule it cites.
 *
 * RosterRules prints "RUNBOOK.md:191" on Justin's screen when it refuses a
 * second quarterback. RUNBOOK.md is a living document that gets edited above
 * that line; nothing checked that 191 still says what the code says it says.
 */
public class RunbookCitationTest {

    @Test
    public void everyRunbookLineCitedInCodeStillHoldsTheRule() throws Exception {
        List<String> runbook = Files.readAllLines(Path.of("RUNBOOK.md"));
        String rules = Files.readString(Path.of("src/main/java/RosterRules.java"));
        Matcher matcher = Pattern.compile("RUNBOOK\\.md:(\\d+)").matcher(rules);
        int checked = 0;
        while(matcher.find()){
            int line = Integer.parseInt(matcher.group(1));
            assertTrue(line >= 1 && line <= runbook.size(),
                    "RosterRules cites RUNBOOK.md:" + line + " but the file has "
                            + runbook.size() + " lines");
            String text = runbook.get(line - 1).toLowerCase();
            assertTrue(text.contains("qb") && (text.contains("keeper") || text.contains("stash")),
                    "RosterRules cites RUNBOOK.md:" + line + " for the second-QB stash"
                            + " rule, but that line now reads: " + runbook.get(line - 1));
            checked++;
        }
        assertTrue(checked >= 1, "expected RosterRules to cite RUNBOOK.md at least once");
    }
}
