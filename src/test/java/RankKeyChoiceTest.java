import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import PlayerImportAndSetup.Position;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The two orders rank the same men, and a band's mean leaves the man's own season out. */
public class RankKeyChoiceTest {

    private static RankKeyChoice.Man man(String season, String id, double adp, double projection, double realised){
        return new RankKeyChoice.Man(season, id, Position.WR, adp, adp, projection, realised);
    }

    @Test
    public void adpRanksAscendingAndProjectionRanksDescending(){
        List<RankKeyChoice.Man> men = List.of(
                man("2024", "cheap", 90, 300, 0),     // late pick, best projection
                man("2024", "dear", 5, 100, 0),       // first pick, worst projection
                man("2024", "middle", 40, 200, 0));
        Map<String, Integer> byAdp = RankKeyChoice.rank(men, RankKeyChoice.Key.SLEEPER_ADP);
        Map<String, Integer> byProjection = RankKeyChoice.rank(men, RankKeyChoice.Key.PROJECTION);
        assertEquals(Map.of("dear", 0, "middle", 1, "cheap", 2), byAdp, "lowest ADP is rank 0");
        assertEquals(Map.of("cheap", 0, "middle", 1, "dear", 2), byProjection, "highest projection is rank 0");
    }

    @Test
    public void aBandMeanExcludesTheManOwnSeason(){
        Map<String, List<RankKeyChoice.Man>> bySeason = Map.of(
                "2024", List.of(man("2024", "a", 1, 100, 500)),
                "2025", List.of(man("2025", "b", 1, 100, 100)),
                "2026", List.of(man("2026", "c", 1, 100, 300)));
        Map<String, Map<String, Integer>> ranks = new HashMap<>();
        ranks.put("2024", Map.of("a", 0));
        ranks.put("2025", Map.of("b", 0));
        ranks.put("2026", Map.of("c", 0));
        // a's band mean is b and c only: (100 + 300) / 2
        assertEquals(200.0, RankKeyChoice.bandMean(bySeason, ranks, bySeason.get("2024").get(0), 12), 1e-9);
    }

    @Test
    public void aPerfectOrderScoresOneAndAReversedOneScoresMinusOne(){
        List<RankKeyChoice.Man> men = List.of(man("2025", "x", 1, 300, 300),
                man("2025", "y", 2, 200, 200), man("2025", "z", 3, 100, 100));
        Map<String, Double> realised = Map.of("x", 300.0, "y", 200.0, "z", 100.0);
        assertEquals(1.0, RankKeyChoice.spearman(men, Map.of("x", 0, "y", 1, "z", 2), realised), 1e-9);
        assertEquals(-1.0, RankKeyChoice.spearman(men, Map.of("x", 2, "y", 1, "z", 0), realised), 1e-9);
    }
}
