import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import PlayerImportAndSetup.Position;

import java.util.List;
import java.util.Map;

/** The swap search, and the refusal to move on noise. */
public class TuesdaySwapTest {

    private static final Map<String, String> NAMES = Map.of(
            "keep", "Keep Him", "weak", "Weak Man", "free1", "Free One", "free2", "Free Two");
    private static final Map<String, Position> POSITIONS = Map.of(
            "keep", Position.RB, "weak", Position.WR, "free1", Position.WR, "free2", Position.TE);

    /** A toy objective: the roster is worth the sum of these, so swaps are exact. */
    private static double worth(List<String> ids){
        Map<String, Double> value = Map.of("keep", 100.0, "weak", 10.0, "free1", 30.0, "free2", 12.0);
        return ids.stream().mapToDouble(id -> value.getOrDefault(id, 0.0)).sum();
    }

    @Test
    public void everyPairIsSearchedAndTheBestOneLeads(){
        List<TuesdaySwap.Swap> swaps = TuesdaySwap.search(List.of("keep", "weak"),
                List.of("free1", "free2"), NAMES, POSITIONS, TuesdaySwapTest::worth);
        assertEquals(4, swaps.size(), "two free men against two roster spots");
        assertEquals("free1", swaps.get(0).addId());
        assertEquals("weak", swaps.get(0).dropId(), "drop the weakest, not the best");
        assertEquals(20.0, swaps.get(0).gain(), 1e-9, "30 in for 10 out");
        assertTrue(swaps.get(swaps.size() - 1).gain() < 0, "dropping the good man is a loss and is shown as one");
    }

    private static final Map<String, Double> PROJECTED = Map.of(
            "keep", 100.0, "weak", 10.0, "free1", 30.0, "free2", 12.0);

    private static List<TuesdaySwap.Priced> priced(List<String> roster, List<String> free){
        return TuesdaySwap.price(
                TuesdaySwap.search(roster, free, NAMES, POSITIONS, TuesdaySwapTest::worth),
                roster, free, NAMES, POSITIONS, PROJECTED, TuesdaySwapTest::worth);
    }

    @Test
    public void doNothingIsTheAnswerWheneverTheBestPairIsInsideTheNoise(){
        List<TuesdaySwap.Swap> swaps = TuesdaySwap.search(List.of("keep", "weak"),
                List.of("free2"), NAMES, POSITIONS, TuesdaySwapTest::worth);
        assertEquals(2.0, swaps.get(0).gain(), 1e-9, "12 in for 10 out is a real but tiny gain");
        List<TuesdaySwap.Priced> rows = priced(List.of("keep", "weak"), List.of("free2"));
        assertNull(TuesdaySwap.recommend(rows, 6.8), "inside the objective's own seed spread: no move");
        assertNotNull(TuesdaySwap.recommend(rows, 1.0), "with a lower floor it would be named");
        assertNull(TuesdaySwap.recommend(List.of(), 6.8), "an empty wire is also do-nothing");
    }

    /**
     * THE REPORT AND THE PAGE RANK THE SAME WAY, because they now run the same
     * function. Before 2026-09-08 this ranking lived only in LeagueConsole: the
     * report named its best add off the raw pair and the page off the completed
     * plan, and on 2026-09-08 they named Tyler Shough and Baker Mayfield from
     * the same snapshot of the feeds.
     */
    @Test
    public void theBestRowIsTheCompletedPlanNotTheRawPair(){
        List<TuesdaySwap.Priced> rows = priced(List.of("keep", "weak"), List.of("free1", "free2"));
        assertFalse(rows.isEmpty(), "two free men against two roster spots produce rows");
        for(int i = 1; i < rows.size(); i++){
            assertTrue(rows.get(i - 1).worth() >= rows.get(i).worth(),
                    "priced rows come back best-plan-first, which is what recommend() reads");
        }
        assertEquals("free1", rows.get(0).swap().addId(), "the 30-point man leads");
        assertEquals("weak", rows.get(0).swap().dropId(), "dropped for the 10-point man");
        assertEquals(20.0, rows.get(0).worth(), 1e-9, "30 in for 10 out, and nothing to refill");
        java.util.Set<String> added = new java.util.HashSet<>();
        for(TuesdaySwap.Priced row : rows){
            assertTrue(added.add(row.swap().addId()), "one row per man, not one per pair");
        }
    }

    @Test
    public void theRosterStaysTheSameSize(){
        List<TuesdaySwap.Swap> swaps = TuesdaySwap.search(List.of("keep", "weak"),
                List.of("free1"), NAMES, POSITIONS, ids -> {
                    assertEquals(2, ids.size(), "a swap adds one and drops one, never grows the roster");
                    return worth(ids);
                });
        assertEquals(2, swaps.size());
    }
}
