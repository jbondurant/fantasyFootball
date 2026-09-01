import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.Test;

/**
 * Every command DRAFT-READY tells Justin to type must exist.
 *
 * That document is what he reads at 20:45 with sixty seconds a pick, and it
 * names a dozen `-Pmain=` entry points. A renamed or deleted class turns one of
 * them into a stack trace at exactly the moment he has no time to debug it -
 * and this is a repo where LateRoundTargets was recommended on screen for
 * weeks after it stopped being the right tool.
 *
 * Cheap to check, so there is no reason it should ever be wrong.
 */
public class DraftReadyCommandsTest {

    /** DIAGNOSTIC.md names every tool its numbers came from; those must exist too. */
    @Test
    public void everyToolTheDiagnosticCitesExists() throws Exception {
        String doc = Files.readString(Path.of("DIAGNOSTIC.md"));
        // Tools are cited in backticks, sometimes with flags: `RoomFidelity -PfullRounds=true`.
        // A tool is CamelCase with a lowercase letter in it; SCREAMING_CASE like
        // `MOST` is a constant and must not be mistaken for one - the first run of
        // this test flagged BoardValue.MOST as a missing tool.
        Matcher matcher = Pattern.compile("`([A-Z][A-Za-z0-9]*[a-z][A-Za-z0-9]*)(?: -P[^`]*)?`")
                .matcher(doc);
        Set<String> cited = new LinkedHashSet<>();
        while(matcher.find()){
            cited.add(matcher.group(1));
        }
        List<String> missing = new ArrayList<>();
        for(String name : cited){
            boolean main = Files.exists(Path.of("src/main/java/" + name + ".java"));
            boolean test = Files.exists(Path.of("src/test/java/" + name + ".java"));
            if(!main && !test){
                missing.add(name);
            }
        }
        assertTrue(cited.size() >= 15,
                "expected DIAGNOSTIC.md to cite many tools, found " + cited.size());
        assertEquals(List.of(), missing,
                "DIAGNOSTIC.md cites tools that do not exist: " + missing
                        + " - a number whose tool is gone cannot be reproduced");
    }

    @Test
    public void everyMainNamedInTheDocumentExists() throws Exception {
        String doc = Files.readString(Path.of("DRAFT-READY.md"));
        Matcher matcher = Pattern.compile("-Pmain=([A-Za-z0-9_]+)").matcher(doc);
        List<String> missing = new ArrayList<>();
        Set<String> named = new LinkedHashSet<>();
        while(matcher.find()){
            named.add(matcher.group(1));
        }
        for(String main : named){
            if(!Files.exists(Path.of("src/main/java/" + main + ".java"))){
                missing.add(main);
            }
        }
        assertTrue(named.size() >= 6,
                "expected DRAFT-READY to name several entry points, found "
                        + named.size() + ": " + named);
        assertEquals(List.of(), missing,
                "DRAFT-READY tells him to run classes that do not exist: "
                        + missing);
    }

    @Test
    public void everyNamedMainReallyHasAMain() throws Exception {
        String doc = Files.readString(Path.of("DRAFT-READY.md"));
        Matcher matcher = Pattern.compile("-Pmain=([A-Za-z0-9_]+)").matcher(doc);
        List<String> notRunnable = new ArrayList<>();
        while(matcher.find()){
            Path path = Path.of("src/main/java/" + matcher.group(1) + ".java");
            if(Files.exists(path)
                    && !Files.readString(path).contains("public static void main")){
                notRunnable.add(matcher.group(1));
            }
        }
        assertEquals(List.of(), notRunnable,
                "named as -Pmain but has no main method: " + notRunnable);
    }

    @Test
    public void theGradleTasksItNamesExist() throws Exception {
        String doc = Files.readString(Path.of("DRAFT-READY.md"));
        String build = Files.readString(Path.of("build.gradle"));
        for(String task : List.of("smokeTest", "check")){
            if(doc.contains("./gradlew " + task)){
                assertTrue(build.contains("'" + task + "'")
                                || build.contains("tasks.named('" + task + "')"),
                        "DRAFT-READY names ./gradlew " + task
                                + " but build.gradle does not define it");
            }
        }
    }
}
