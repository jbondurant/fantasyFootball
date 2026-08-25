import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/** The bridge arithmetic, pinned offline. */
class ProjectionBridgeTest {

    /** This league's shape: 6-pt passing TDs, half-PPR. */
    private static LeagueScoringSettings league(){
        return new LeagueScoringSettings(
                new double[]{0.04, 6.0, -1.0, 0.1, 6.0, 0.5, 0.1, 6.0, -2.0});
    }

    @Test
    void aFourPointSourceGainsTwoPerProjectedPassingTouchdown(){
        LeagueScoringSettings league = league();

        Map<String, Double> site = Map.of("qb1", 300.0, "wr1", 200.0);
        Map<String, Double> passTDs = Map.of("qb1", 30.0);
        Map<String, Double> receptions = Map.of("wr1", 90.0);

        Map<String, Double> bridged = ProjectionBridge.bridge(
                site, 4.0, 0.5, league, passTDs, receptions);

        Assertions.assertEquals(300 + 2 * 30, bridged.get("qb1"), 1e-9,
                "4-pt source + 2 per projected passing TD");
        Assertions.assertEquals(200.0, bridged.get("wr1"), 1e-9,
                "matching reception scoring passes through untouched");
    }

    @Test
    void aFullPprSourceLosesHalfAPointPerReception(){
        LeagueScoringSettings league = league();
        Map<String, Double> bridged = ProjectionBridge.bridge(
                Map.of("wr1", 250.0), league.passTD, 1.0, league,
                new HashMap<>(), Map.of("wr1", 100.0));
        Assertions.assertEquals(250 - 0.5 * 100, bridged.get("wr1"), 1e-9);
    }

    @Test
    void aPropsSheetScoresDirectlyUnderLeagueSettings(){
        // Sportsbook season props are counts: no bridge, straight to scoring.
        Player.indexForTest(TestPlayers.listOf(
                TestPlayers.player("Josh", "Allen", "BUF", PlayerImportAndSetup.Position.QB, 901)));
        try {
            Map<String, Double> scored = ProjectionBridge.parseSource(
                    java.util.List.of(
                            "name,position,pass_yd,pass_td,pass_int,rush_yd,rush_td",
                            "Josh Allen,QB,3800,28.5,10.5,550,10.5"),
                    league(), new HashMap<>(), new HashMap<>());
            // 3800*.04 + 28.5*6 + 10.5*-1 + 550*.1 + 10.5*6 = 430.5
            Assertions.assertEquals(3800 * 0.04 + 28.5 * 6 - 10.5 + 550 * 0.1 + 10.5 * 6,
                    scored.get("901"), 1e-9);
        } finally {
            Player.resetIndexForTest();
        }
    }

    @Test
    void playersMissingFromTheCountMapsPassThrough(){
        LeagueScoringSettings league = league();
        Map<String, Double> bridged = ProjectionBridge.bridge(
                Map.of("rb1", 180.0), 4.0, 0.5, league, new HashMap<>(), new HashMap<>());
        Assertions.assertEquals(180.0, bridged.get("rb1"), 1e-9,
                "no projected TDs or receptions means no adjustment");
    }
}
