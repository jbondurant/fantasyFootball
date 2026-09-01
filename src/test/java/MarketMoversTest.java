import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

/**
 * The market-movers report ranks ADP moves relatively, names the day an
 * event landed, and reads a cause out of Sleeper's metadata when there is one.
 */
public class MarketMoversTest {

    private static MarketMovers.Row row(String id, double points, double adp, String injury, String team, long news){
        return new MarketMovers.Row(id, id, "RB", team, points, adp, injury, null, news);
    }

    private static MarketMovers.Move move(MarketMovers.Row from, MarketMovers.Row to){
        return new MarketMovers.Move(from, to, List.of(), null, 0);
    }

    @Test
    public void fourPicksAtTheTopOutranksTwentyAtTheBottom(){
        MarketMovers.Move early = move(row("early", 0, 10, null, "A", 0), row("early", 0, 14, null, "A", 0));
        MarketMovers.Move late = move(row("late", 0, 150, null, "A", 0), row("late", 0, 170, null, "A", 0));
        MarketMovers.Move tiny = move(row("tiny", 0, 100, null, "A", 0), row("tiny", 0, 102, null, "A", 0));
        List<MarketMovers.Move> ranked = MarketMovers.adpMovers(List.of(late, tiny, early), 3);
        assertEquals(2, ranked.size(), "a two-pick wobble is under the floor");
        assertEquals("early", ranked.get(0).to().id(), "10 -> 14 is a 40% move; 150 -> 170 is 13%");
    }

    @Test
    public void undraftedMenNeverRankAsAdpMovers(){
        MarketMovers.Move ghost = move(row("g", 0, 999, null, "A", 0), row("g", 0, 180, null, "A", 0));
        assertTrue(MarketMovers.adpMovers(List.of(ghost), 3).isEmpty(),
                "999 -> 180 is 'entered the pool', not a move within it");
    }

    @Test
    public void aStepIsOneDayCarryingTheMove(){
        int[] step = MarketMovers.stepIndex(List.of(100.0, 100.0, 88.0, 88.0));
        assertEquals(2, step[0]);
        assertEquals(100, step[1], "one day carried all of it");
        int[] drift = MarketMovers.stepIndex(List.of(100.0, 97.0, 94.0, 91.0));
        assertEquals(33, drift[1], "no single day carries a drift");
        int[] flat = MarketMovers.stepIndex(List.of(50.0, 50.0, 50.0));
        assertEquals(-1, flat[0], "a flat series has no step");
    }

    @Test
    public void flagsReadTheCauseOutOfTheMetadata(){
        LocalDate start = LocalDate.of(2026, 8, 25);
        long newsInWindow = LocalDate.of(2026, 8, 29).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        long newsBefore = LocalDate.of(2026, 8, 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        String f = MarketMovers.flags(row("x", 0, 0, null, "BUF", 0), row("x", 0, 0, "IR", "MIA", newsInWindow), start);
        assertTrue(f.contains("injury healthy -> IR"), f);
        assertTrue(f.contains("team BUF -> MIA"), f);
        assertTrue(f.contains("news 08-29"), f);
        String quiet = MarketMovers.flags(row("y", 0, 0, null, "BUF", 0), row("y", 0, 0, null, "BUF", newsBefore), start);
        assertEquals("", quiet, "nothing changed and the news predates the window: the data has no explanation");
    }
}
