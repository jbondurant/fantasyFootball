import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ONE BASIS FOR "WHAT CAN BE KEPT NEXT YEAR", checked in the source.
 *
 * The same bug landed five times because the fix kept being applied to whichever
 * tool Justin happened to be reading. `KeeperChooser.eligibleCandidates` prices
 * against `getPreviousDraftPicks` - "every EARLIER draft" - which is correct for
 * the decision made last August and wrong for next year's, and every tool that
 * asked it the forward question got 2025 prices: Skattebo a keeper he is not, Bo
 * Nix not a keeper he is.
 *
 * This is a source test rather than a behavioural one on purpose. The behaviour
 * is already covered by NextYearKeepersTest; what was never covered is the thing
 * that actually went wrong, which is a NEW call site appearing with the old
 * wiring. A test that runs the tools cannot see that. A test that reads them can.
 */
public class KeeperBasisTest {

    /** The in-season tools whose keeper numbers Justin acts on. */
    private static final List<String> FORWARD_LOOKING = List.of(
            "LeagueConsole.java", "TradeMarket.java", "TradeStability.java");

    private static String source(String name) throws Exception {
        Path path = Path.of("src", "main", "java", name);
        assertTrue(Files.exists(path), name + " has been renamed or removed; this test names it"
                + " explicitly, so update the list rather than deleting the check");
        return codeOnly(Files.readString(path));
    }

    /**
     * The source with its comments removed.
     *
     * The first version of this test read the raw file and failed on all three
     * tools - every one of which had a comment EXPLAINING that it no longer
     * calls the old entry point. A test looking for call sites that matches the
     * prose describing the fix reports the fix as the bug.
     */
    static String codeOnly(String java){
        String withoutBlocks = java.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder out = new StringBuilder();
        for(String line : withoutBlocks.split("\n", -1)){
            int slashes = line.indexOf("//");
            out.append(slashes < 0 ? line : line.substring(0, slashes)).append('\n');
        }
        return out.toString();
    }

    /** The stripper itself, on the shapes that made the first version wrong. */
    @Test
    public void commentsAreNotCode(){
        assertFalse(codeOnly("        // it read KeeperChooser.eligibleCandidates before\n")
                .contains("eligibleCandidates"), "a line comment is not a call");
        assertFalse(codeOnly("/* KeeperChooser.eligibleCandidates\n   over two lines */\n")
                .contains("eligibleCandidates"), "a block comment is not a call either");
        assertTrue(codeOnly("x = KeeperChooser.eligibleCandidates(c, u); // as it was\n")
                .contains("eligibleCandidates"), "and real code on a commented line still counts");
    }

    @Test
    public void noInSeasonToolPricesNextYearOffLastYearsDraft() throws Exception {
        List<String> offenders = new ArrayList<>();
        for(String name : FORWARD_LOOKING){
            if(source(name).contains("KeeperChooser.eligibleCandidates")){
                offenders.add(name);
            }
        }
        assertEquals(List.of(), offenders,
                "these price keepers off getPreviousDraftPicks, which in this season is the 2025"
                        + " board - the basis for a decision already made. Next year's prices come"
                        + " from NextYearKeepers.forThisLeague");
    }

    @Test
    public void theyAllGoThroughTheOneEntryPoint() throws Exception {
        for(String name : FORWARD_LOOKING){
            assertTrue(source(name).contains("NextYearKeepers.forThisLeague")
                            || source(name).contains("NextYearKeepers.roundsForThisLeague"),
                    name + " does not ask NextYearKeepers for this league's keeper prices, so it is"
                            + " getting them from somewhere else");
        }
    }

    /**
     * And the assembly itself lives in one file. Three callers each pulled the
     * earlier boards, stringified them and fed them to `consecutiveYears` by
     * hand; a fourth would have been written the same way and been just as easy
     * to leave behind.
     */
    @Test
    public void onlyNextYearKeepersAssemblesTheCap() throws Exception {
        List<String> assemblers = new ArrayList<>();
        try(var files = Files.list(Path.of("src", "main", "java"))){
            for(Path path : files.toList()){
                String name = path.getFileName().toString();
                if(!name.endsWith(".java") || name.equals("NextYearKeepers.java")){
                    continue;
                }
                if(Files.readString(path).contains("NextYearKeepers.consecutiveYears")){
                    assemblers.add(name);
                }
            }
        }
        assertEquals(List.of(), assemblers,
                "the consecutive-year cap is assembled outside NextYearKeepers, which is the"
                        + " duplication that let TradeStability keep the old basis unnoticed");
    }
}
