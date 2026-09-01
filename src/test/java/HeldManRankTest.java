import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A man I already hold must not be repriced by other people's picks.
 *
 * Found by an adversarial audit. `LiveBoard` priced every man on Justin's
 * roster with `depth()`, which counts how many of that position have LEFT THE
 * BOARD - so Tuten read RB1 before a single pick was made and RB55 by round 15.
 * The best back in football, then nearly nothing, without ever playing a down.
 *
 * The second half is worse than the first. Every back he held got the SAME
 * rank, and `BoardValue.drawn` is keyed on position-and-rank, so they all drew
 * identically in all six hundred worlds. A model whose whole claim is that it is
 * roster-aware could not tell one of his backs from another.
 *
 * A man's rank is his own place on the projection curve. It does not move.
 */
public class HeldManRankTest {

    @Test
    void aHeldManKeepsHisRankHoweverTheBoardDrains() throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        var earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);

        Map<String, Integer> rankOf = LiveBoard.projectionRanks(planner, LiveBoard.kept(AAAConfiguration.getInstance()));
        assertFalse(rankOf.isEmpty(), "somebody must be ranked");

        // Justin's keepers must have real, distinct, non-trivial ranks. Under
        // the old code both read as rank 1 at their position before any pick.
        List<String> keepers = planner.myKeeperIDs();
        assertEquals(2, keepers.size(), "Tuten and Purdy");
        for(String id : keepers){
            Integer rank = rankOf.get(id);
            assertNotNull(rank, "a kept man must still be ranked - he is on a roster");
            assertTrue(rank > 1,
                    "a keeper worth keeping at round 12 or 13 is not the best man at his"
                            + " position; got rank " + rank);
        }

        // The ranking is a property of the projection pool alone, so it cannot
        // depend on a board state at all - which is the whole fix.
        Map<String, Integer> again = LiveBoard.projectionRanks(planner, LiveBoard.kept(AAAConfiguration.getInstance()));
        assertEquals(rankOf, again, "the same pool must rank the same way twice");
    }

    @Test
    void twoBacksOnMyRosterAreDifferentMen() throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        var earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);

        Map<String, Integer> rankOf = LiveBoard.projectionRanks(planner, LiveBoard.kept(AAAConfiguration.getInstance()));
        List<Integer> backs = new ArrayList<>();
        for(Map.Entry<String, Integer> entry : rankOf.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player != null && player.position == Position.RB && entry.getValue() <= 6){
                backs.add(entry.getValue());
            }
        }
        assertEquals(backs.size(), Set.copyOf(backs).size(),
                "no two backs may share a rank - sharing is what made every back on"
                        + " the roster draw identically in all six hundred worlds");
        assertTrue(backs.size() >= 5, "the top of the board must be populated");
    }
}
