import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * The printed legend must name the column the code actually ranks on.
 *
 * It said "VS WAIT ... is what to rank on" while the sort ranked on END TEAM.
 * At pick 79 of the audit's seed 0 those two columns pick different players,
 * so a reader following the instruction on screen takes a receiver while the
 * tool recommends a defence. Prose drift, in the one place the user reads.
 */
public class TableLegendTest {

    private static String board() throws Exception {
        return new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/java/LiveBoard.java")));
    }

    @Test
    public void theLegendDoesNotTellTheReaderToRankOnVsWait() throws Exception {
        String source = board();
        int said = source.indexOf("what to rank on");
        if(said >= 0){
            String around = source.substring(Math.max(0, said - 400), said);
            assertFalse(around.contains("VS WAIT is that minus"),
                    "the legend must not name VS WAIT as the ranking column -"
                            + " the code ranks on END TEAM");
        }
    }

    @Test
    public void theLegendNamesEndTeamAsTheRanking() throws Exception {
        assertTrue(board().contains("END TEAM is what the verdict ranks on"),
                "the legend must say which column decides");
    }

    @Test
    public void endTeamIsPrintedWithEnoughPrecisionToBreakATie() throws Exception {
        String source = board();
        assertFalse(source.contains("%8.1f %7.1f %7.0f"),
                "END printed at %7.0f showed WR 1921 and TE 1921 as equal at"
                        + " pick 66 while the verdict silently chose one");
    }
}
