import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A flag can be spelled right, listed in build.gradle, forwarded by the build,
 * and STILL be a constant - because something else in the forked JVM owns that
 * property name and answers first.
 *
 * {@link BoardValue} found this on 2026-08 with `depth`, which reads back "0"
 * whatever is passed, and renamed its own knob to `lookahead`; the comment there
 * ends "Worth a test of its own." It was not written, and on 2026-09-06
 * LeagueConsole read `Integer.getInteger("depth", 6)`, got 0, and shipped a
 * "what this trade opens up" column in which every chain was one step deep - so
 * it printed the immediate gain twice and looked like a working feature.
 * TradeMarket read the same name behind an `if(depth > 1)` guard, where a stolen
 * zero does not shorten the trading-power section but deletes it silently, and
 * RankKeyChoice read it as a search depth that would have judged nothing.
 *
 * This is the test that comment asked for. ProseDriftTest cannot catch this
 * class: it checks that a flag the code READS is FORWARDED, and `depth` was
 * both. What it cannot see is the value being taken in between.
 */
public class StolenFlagTest {

    /**
     * Property names that are not ours to use. Add to this list, never remove:
     * a name that once read back somebody else's value is not safe to reclaim.
     */
    static final Set<String> TAKEN = Set.of("depth");

    static List<Path> mainSources() throws Exception {
        try(var files = Files.walk(Path.of("src", "main", "java"))){
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    void noSourceReadsAPropertyNameThatIsAlreadyTaken() throws Exception {
        Pattern property = Pattern.compile(
                "(?:System\\.getProperty|Boolean\\.getBoolean|Integer\\.getInteger"
                        + "|Long\\.getLong)\\(\\s*\"([a-zA-Z]+)\"");
        List<String> offences = new ArrayList<>();
        for(Path path : mainSources()){
            Matcher matcher = property.matcher(Files.readString(path));
            while(matcher.find()){
                if(TAKEN.contains(matcher.group(1))){
                    offences.add(path.getFileName() + " reads \"" + matcher.group(1) + "\"");
                }
            }
        }
        assertEquals(List.of(), offences,
                "these read a system property whose name is owned by something else in the"
                        + " forked JVM, so the value arrives stolen and the default never"
                        + " applies - rename the flag (see BoardValue.LOOKAHEAD): " + offences);
    }

    /**
     * And the build must not offer a flag it cannot actually deliver.
     *
     * SLICED TO THE FORWARDING LIST AND STRIPPED OF COMMENTS, for the reason
     * ProseDriftTest.forwardedByBuild already gives: the comments inside that
     * list quote the names, so a knob DELETED from it still reads as forwarded
     * from the line recording its deletion. The first version of this test
     * scanned the whole file and failed on the two comments the same change
     * added to explain why 'depth' had been retired - a test red on the tree
     * that introduces it, and red because of its own prose.
     */
    @Test
    void theBuildDoesNotForwardATakenName() throws Exception {
        String gradle = Files.readString(Path.of("build.gradle"));
        int from = gradle.indexOf("['sims'");
        int to = gradle.indexOf("].each", from);
        assertTrue(from > 0 && to > from,
                "the knob-forwarding list in build.gradle has been restructured;"
                        + " this lint needs updating with it");
        StringBuilder code = new StringBuilder();
        for(String line : gradle.substring(from, to).lines().toList()){
            int comment = line.indexOf("//");
            code.append(comment < 0 ? line : line.substring(0, comment)).append('\n');
        }
        Set<String> forwarded = new TreeSet<>();
        Matcher quoted = Pattern.compile("'([a-zA-Z]+)'").matcher(code);
        while(quoted.find()){
            forwarded.add(quoted.group(1));
        }
        for(String taken : TAKEN){
            assertFalse(forwarded.contains(taken),
                    "build.gradle forwards -P" + taken + ", which promises a knob the JVM"
                            + " overwrites before any code reads it");
        }
    }

    /** The comment-stripping above must actually be load-bearing. */
    @Test
    void aRetiredNameMentionedOnlyInACommentIsNotForwarded() throws Exception {
        String gradle = Files.readString(Path.of("build.gradle"));
        assertTrue(gradle.contains("'depth'"),
                "this test is about a retired name surviving in prose; if no comment quotes"
                        + " 'depth' any more it proves nothing and should be removed");
        int from = gradle.indexOf("['sims'");
        int to = gradle.indexOf("].each", from);
        for(String line : gradle.substring(from, to).lines().toList()){
            int comment = line.indexOf("//");
            String code = comment < 0 ? line : line.substring(0, comment);
            assertFalse(code.contains("'depth'"),
                    "a real forwarding entry for the taken name is back: " + line.trim());
        }
    }
}
