import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** Tier parsing and the rank-to-points transplant, offline. */
class BorisChenTiersTest {

    private static final Player ALPHA = TestPlayers.player("Al", "Alpha", "AAA", Position.QB, 801);
    private static final Player BRAVO = TestPlayers.player("Bo", "Bravo", "BBB", Position.QB, 802);
    private static final Player CHARLIE = TestPlayers.player("Cy", "Charlie", "CCC", Position.QB, 803);

    @BeforeEach
    void register(){
        Player.indexForTest(TestPlayers.listOf(ALPHA, BRAVO, CHARLIE));
    }

    @AfterEach
    void reset(){
        Player.resetIndexForTest();
    }

    @Test
    void tiersParseInOrderAndDropUnknownNames(){
        List<List<Player>> tiers = BorisChenTiers.parse(
                "Tier 1: Al Alpha, Bo Bravo\nTier 2: Nobody Matchable\nTier 3: Cy Charlie\n",
                Position.QB);

        Assertions.assertEquals(2, tiers.size(), "the unmatchable tier vanishes");
        Assertions.assertEquals(List.of(ALPHA, BRAVO), tiers.get(0));
        Assertions.assertEquals(List.of(CHARLIE), tiers.get(1));
    }

    @Test
    void tierMatesShareTheMeanOfTheirRankRangeOnTheCurve(){
        List<List<Player>> tiers = List.of(List.of(ALPHA, BRAVO), List.of(CHARLIE));
        Map<String, Double> points = BorisChenTiers.pointsFromTiers(tiers,
                List.of(400.0, 380.0, 300.0, 250.0));

        Assertions.assertEquals(390.0, points.get("801"), 1e-9,
                "tier one spans ranks 1-2: mean of 400 and 380");
        Assertions.assertEquals(390.0, points.get("802"), 1e-9,
                "tier mates are interchangeable by construction");
        Assertions.assertEquals(300.0, points.get("803"), 1e-9,
                "the next tier starts where the last one ended");
    }

    @Test
    void ranksPastTheCurveScoreZeroRatherThanExploding(){
        Map<String, Double> points = BorisChenTiers.pointsFromTiers(
                List.of(List.of(ALPHA), List.of(BRAVO)), List.of(400.0));
        Assertions.assertEquals(400.0, points.get("801"), 1e-9);
        Assertions.assertEquals(0.0, points.get("802"), 1e-9);
    }
}
