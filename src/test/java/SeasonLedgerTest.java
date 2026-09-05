import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** The ledger is append-only and its verdict was decided before the season. */
public class SeasonLedgerTest {

    @Test
    public void aWeekAlreadyRecordedIsNeverAppendedTwice(){
        List<String> file = List.of("# header", "week,manager,scored,best_possible,from_bench,promoted",
                "1,justinb314,118.40,131.10,22.00,2", "1,BHier,101.00,109.00,0.00,0",
                "2,justinb314,95.20,110.00,10.50,1");
        assertEquals(java.util.Set.of(1, 2), SeasonLedger.weeksRecorded(file));
        assertTrue(SeasonLedger.weeksRecorded(List.of("# only a comment")).isEmpty());
    }

    @Test
    public void rowsComeOutInAStableOrderWhateverOrderTheyWentIn(){
        List<SeasonLedger.Row> rows = List.of(
                new SeasonLedger.Row(2, "BHier", 100, 110, 5, 1),
                new SeasonLedger.Row(1, "justinb314", 118.4, 131.1, 22, 2),
                new SeasonLedger.Row(1, "BHier", 101, 109, 0, 0));
        assertEquals(List.of("1,BHier,101.00,109.00,0.00,0",
                        "1,justinb314,118.40,131.10,22.00,2",
                        "2,BHier,100.00,110.00,5.00,1"),
                SeasonLedger.rowsFor(rows));
    }

    @Test
    public void theVerdictIsTheOneWrittenDownBeforeTheSeason(){
        assertTrue(SeasonLedger.verdict(9, 6, 0.25).startsWith("THE BENCH PAID"),
                "three places better is the pre-registered threshold");
        assertTrue(SeasonLedger.verdict(9, 7, 0.25).startsWith("NULL RESULT"),
                "two places better was called noise in advance");
        assertTrue(SeasonLedger.verdict(9, 9, 0.25).startsWith("THE BENCH DID NOT PAY"));
        assertTrue(SeasonLedger.verdict(9, 11, 0.25).startsWith("THE BENCH DID NOT PAY"));
        assertTrue(SeasonLedger.verdict(9, 4, 0.05).contains("hot roster"),
                "a good rank with no promotion is not the bench being vindicated");
    }

    @Test
    public void rankIsByPointsBiggestFirst(){
        Map<String, Double> totals = Map.of("a", 100.0, "b", 300.0, "c", 200.0);
        assertEquals(1, SeasonLedger.rankOf(totals, "b"));
        assertEquals(3, SeasonLedger.rankOf(totals, "a"));
        assertEquals(-1, SeasonLedger.rankOf(totals, "nobody"));
    }

    /**
     * The pre-registration is about where he FINISHES. Rendered after one Sunday
     * it would announce a verdict off a single week, which is the opposite of
     * what writing the test down first was for.
     */
    @Test
    public void noFinalVerdictBeforeTheSeasonIsFinished(){
        String midSeason = SeasonLedger.verdict(9, 2, 0.30, 3, 14);
        assertTrue(midSeason.startsWith("STANDING after 3 of 14 weeks (NOT the verdict)"), midSeason);
        assertFalse(midSeason.contains("THE BENCH PAID"), "a three-week lead is not a finding");
        assertTrue(midSeason.contains("will not be read until week 14"));

        String finished = SeasonLedger.verdict(9, 2, 0.30, 14, 14);
        assertTrue(finished.startsWith("THE BENCH PAID"), finished);
        assertEquals(SeasonLedger.verdict(9, 2, 0.30), finished, "once complete it is the plain reading");
    }
}
