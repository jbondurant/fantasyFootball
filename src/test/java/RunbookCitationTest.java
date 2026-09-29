import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.Test;

/**
 * A runbook reference printed on Justin's screen must still point at the rule.
 *
 * RosterRules names the runbook when it refuses a second quarterback. It used to
 * name a LINE NUMBER, and the runbook is a living document that gets edited
 * above that line - which happened on 2026-09-07, when an in-season section was
 * prepended and every line moved by 108. So the citation is a HEADING now, and
 * this checks the heading exists and still carries the rule.
 *
 * A line number is a citation with an expiry date nobody can see. A heading
 * survives an edit above it, and when somebody deletes the section outright this
 * test says so instead of silently pointing at whatever moved into its place.
 */
public class RunbookCitationTest {

    @Test
    public void everyRunbookHeadingCitedInCodeStillHoldsTheRule() throws Exception {
        String runbook = Files.readString(Path.of("RUNBOOK.md"));
        StringBuilder sources = new StringBuilder();
        try(var files = Files.walk(Path.of("src", "main", "java"))){
            for(Path path : files.filter(p -> p.toString().endsWith(".java")).toList()){
                sources.append(Files.readString(path)).append('\n');
            }
        }
        String rules = sources.toString();
        // two forms: quoted in a comment, and bare inside the user-facing string
        // that RosterRules actually prints. Both are citations and both rot.
        Matcher matcher = Pattern.compile(
                "RUNBOOK\\.md(?: \"([^\"]+)\"|, ([^)\\n]+)\\))").matcher(rules);
        int checked = 0;
        while(matcher.find()){
            String heading = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            // the runbook writes an em dash where the java source cannot
            String pattern = Pattern.quote(heading).replace("-", "\\E[-\\u2013\\u2014]\\Q");
            Matcher inFile = Pattern.compile("^#+ *" + pattern + "\\s*$",
                    Pattern.MULTILINE).matcher(runbook);
            assertTrue(inFile.find(),
                    "a source cites the runbook heading \"" + heading + "\" and no such"
                            + " heading exists; the section was renamed or deleted");
            // and the section must not be empty - a heading with nothing under it
            // is a citation pointing at a hole
            int from = inFile.end();
            int to = runbook.indexOf("\n## ", from);
            String section = runbook.substring(from, to < 0 ? runbook.length() : to).trim();
            assertTrue(section.length() > 40,
                    "the section under \"" + heading + "\" is empty or nearly so, which is"
                            + " a citation pointing at a hole");
            checked++;
        }
        assertTrue(checked >= 1, "expected some source to cite a runbook heading at least once");
    }

    /** And no line-number citation may creep back in. */
    @Test
    public void noCodeCitesTheRunbookByLineNumber() throws Exception {
        List<String> offenders = new ArrayList<>();
        try(var files = Files.walk(Path.of("src", "main", "java"))){
            for(Path path : files.filter(p -> p.toString().endsWith(".java")).toList()){
                if(Pattern.compile("RUNBOOK\\.md:\\d+").matcher(Files.readString(path)).find()){
                    offenders.add(path.getFileName().toString());
                }
            }
        }
        assertEquals(List.of(), offenders,
                "these cite RUNBOOK.md by line number, which breaks the next time anything"
                        + " is inserted above it: " + offenders);
    }
}
