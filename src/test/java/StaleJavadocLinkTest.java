import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import org.junit.jupiter.api.Test;

/**
 * A {@code @link} must name a class this repo still has.
 *
 * `./gradlew javadoc` was red for so long that nobody ran it, and hiding in the
 * twenty errors were two {@code @link}s to StatLineProjections and
 * SleeperStatProjections - classes that no longer exist. That is the same
 * prose-drift family this repo has hit ten times: documentation naming
 * something the code does not have.
 *
 * javadoc now catches it, but only if somebody runs javadoc. This runs in the
 * suite that actually gets run.
 */
public class StaleJavadocLinkTest {

    @Test
    public void everyLinkedClassExists() throws Exception {
        Path main = Path.of("src/main/java");
        // Every type this repo declares, NESTED ONES INCLUDED. The first
        // version of this collected file names only and then flagged
        // DraftBacktest.Season, LeagueTransactions.Year and BoardValue.Selection
        // as missing - they are nested types, javadoc resolves them fine, and
        // the test was wrong rather than the code.
        Set<String> known = new HashSet<>();
        Pattern declared = Pattern.compile(
                "\\b(?:class|interface|enum|record)\\s+([A-Z][A-Za-z0-9_]*)");
        try(Stream<Path> files = Files.walk(main)){
            for(Path path : files.filter(p -> p.toString().endsWith(".java")).toList()){
                known.add(path.getFileName().toString().replace(".java", ""));
                Matcher inside = declared.matcher(Files.readString(path));
                while(inside.find()){
                    known.add(inside.group(1));
                }
            }
        }
        Pattern link = Pattern.compile("\\{@link\\s+([A-Z][A-Za-z0-9_]*)");
        List<String> stale = new ArrayList<>();
        int checked = 0;
        try(Stream<Path> files = Files.walk(main)){
            for(Path path : files.filter(p -> p.toString().endsWith(".java")).toList()){
                Matcher matcher = link.matcher(Files.readString(path));
                while(matcher.find()){
                    checked++;
                    String target = matcher.group(1);
                    // Skip JDK and library types - only OUR classes are checkable
                    // this cheaply, and those are the ones that get deleted.
                    if(known.contains(target) || isLibrary(target)){
                        continue;
                    }
                    stale.add(path.getFileName() + " links to " + target
                            + ", which this repo does not have");
                }
            }
        }
        assertTrue(checked > 20,
                "expected to find real @link tags to check, found " + checked);
        assertEquals(List.of(), stale,
                "javadoc names classes that do not exist: " + stale);
    }

    private static boolean isLibrary(String name){
        return List.of("String", "Map", "List", "Set", "Random", "Math",
                "Integer", "Double", "Object", "Comparator", "Collection",
                "Optional", "Stream", "Exception", "RuntimeException").contains(name);
    }
}
