import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A man's rank is his place in the source's order, not his place among the men
 * whose names happened to join (TRAPS #80). A board carrying source ranks reads
 * its expected values off those ranks; a synthetic board ranks by list position.
 */
public class SourceRankTest {

    private static OutcomeDistributions.Season season(int rank, double perGame){
        return new OutcomeDistributions.Season("wr" + rank, Position.WR, rank, 17, perGame, 1.0, perGame * 17);
    }

    @Test
    public void aBoardWithSourceRanksReadsExpectedValuesOffThoseRanks(){
        // pool: rank r is worth 100 - 10r a game, three seasons per rank so the smoothing has data
        Map<String, List<OutcomeDistributions.Season>> pool = new HashMap<>();
        for(int rank = 0; rank < 8; rank++){
            for(int copy = 0; copy < 3; copy++){
                pool.computeIfAbsent("WR:0", u -> new ArrayList<>()).add(season(rank, 100 - 10 * rank));
            }
        }
        Map<String, Position> positionOf = Map.of("a", Position.WR, "b", Position.WR, "c", Position.WR);
        List<String> ids = List.of("a", "b", "c");
        // source ranks 0, 1, 4: two men between b and c did not join
        PlanBacktest.Board withGaps = new PlanBacktest.Board("t", ids, positionOf, List.of(), Map.of("a", 0, "b", 1, "c", 4));
        PlanBacktest.Board byIndex = new PlanBacktest.Board("t", ids, positionOf, List.of());
        assertEquals(Map.of("a", 0, "b", 1, "c", 2), byIndex.rankOf(), "the four-argument board ranks by list position");
        Map<String, Double> gaps = WeeklyStarterValue.expectedFromRank(withGaps, pool);
        Map<String, Double> index = WeeklyStarterValue.expectedFromRank(byIndex, pool);
        assertEquals(gaps.get("a"), index.get("a"), 1e-9, "same rank, same expectation");
        assertTrue(gaps.get("c") < index.get("c"), "c at source rank 4 expects less than c at index rank 2: "
                + gaps.get("c") + " vs " + index.get("c"));
    }

    @Test
    public void tiersComeFromTheSourceRank(){
        Map<String, Position> positionOf = Map.of("a", Position.RB, "b", Position.RB);
        PlanBacktest.Board board = new PlanBacktest.Board("t", List.of("a", "b"), positionOf, List.of(), Map.of("a", 3, "b", 13));
        assertEquals(Map.of("a", 0, "b", 1), board.tiersOf(), "b is the 14th back in the source, tier 1, though only second on the board");
    }
}
