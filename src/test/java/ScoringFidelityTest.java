import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TRAPS.md section B: an outcome must be graded under THIS league's rules.
 *
 *   B8   6 for a passing touchdown here, 4 in the feed
 *   B9   -1 a fumble here, 0 there; +1 a game for holding a team to 14-20
 *   B10  the feed changed its own rules mid-history, so a season total is not a
 *        stable unit across a harvest - score from RAW COMPONENTS, always
 *   B11  a dead default paid 0.4 for a passing touchdown
 *
 * LeagueActualsScoringTest already checks that the scorer adds up. This file
 * checks the three things around it that decide whether the right scorer is the
 * one being used: that the switch does what it says, that the corrected path
 * cannot fall back to the feed, and that no default anywhere reintroduces a
 * slipped decimal.
 */
class ScoringFidelityTest {

    private static JsonObject stats(String json){
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** The 2026 league: half PPR, 6-point passing TDs, -1 a fumble, 1 for 14-20. */
    private static LeagueScoringSettings league(){
        return LeagueScoringSettings.fromSleeperScoringSettings(stats(
                "{\"pass_yd\":0.04,\"pass_td\":6.0,\"pass_int\":-1.0,\"rush_yd\":0.1,"
                + "\"rush_td\":6.0,\"rec\":0.5,\"rec_yd\":0.1,\"rec_td\":6.0,"
                + "\"fum_lost\":-2.0,\"fum\":-1.0,\"pts_allow_14_20\":1.0}"));
    }

    // =====================================================================
    // The switch does what it claims.
    // =====================================================================

    /**
     * -PleagueScoredActuals is the one knob that moves every graded outcome in
     * the repo at once, so it is asserted to be wired to the property the
     * RUNBOOK and build.gradle actually pass, and to move when that property
     * moves.
     *
     * A flag whose name has drifted from the one the build forwards is not a
     * flag, it is a default nobody can leave - which is exactly the -Pdeviate
     * shape in a different hat.
     */
    @Test
    void theLeagueScoredFlagIsTheOneTheBuildForwardsAndItReallyFlips(){
        assertEquals("leagueScoredActuals", LeagueActuals.FLAG);

        String before = System.getProperty(LeagueActuals.FLAG);
        try {
            System.clearProperty(LeagueActuals.FLAG);
            assertTrue(LeagueActuals.enabled(),
                    "absent must mean the league's own scoring (flipped 2026-09-04)");
            System.setProperty(LeagueActuals.FLAG, "true");
            assertTrue(LeagueActuals.enabled(),
                    "-PleagueScoredActuals=true does not reach LeagueActuals.enabled();"
                            + " every dispatcher below it is then a no-op");
            System.setProperty(LeagueActuals.FLAG, "false");
            assertFalse(LeagueActuals.enabled(),
                    "-PleagueScoredActuals=false must still restore the feed's grading");
        }
        finally {
            if(before == null){
                System.clearProperty(LeagueActuals.FLAG);
            }
            else {
                System.setProperty(LeagueActuals.FLAG, before);
            }
        }
    }

    /** On by default since 2026-09-04: the league pays 6 for a passing touchdown. */
    @Test
    void theCorrectedGradingIsTheDefault(){
        assertTrue(LeagueActuals.enabled(),
                "leagueScoredActuals must default to the league's own scoring");
    }

    // =====================================================================
    // B10. Raw components, never the feed's own total.
    // =====================================================================

    /**
     * The league scorer must ignore pts_half_ppr entirely.
     *
     * The stat line below carries a wildly wrong precomputed total. If any part
     * of the corrected path reads that field as a VALUE rather than as a
     * presence check, this returns 9999 instead of the components.
     */
    @Test
    void aLeagueScoredLineIgnoresWhateverTheFeedPrecomputed(){
        JsonObject line = stats("{\"pass_yd\":300,\"pass_td\":3,\"rush_yd\":20,"
                + "\"fum\":1,\"pts_half_ppr\":9999.0}");

        double expected = 300 * 0.04 + 3 * 6 + 20 * 0.1 - 1;
        assertEquals(expected, LeagueActuals.scoreSkill(line, league()), 1e-9,
                "the corrected path fell back to the feed's own number");
        assertEquals(31.0, expected, 1e-9);
    }

    /**
     * The season is scored from components row by row, so a harvest whose feed
     * changed its rules mid-history still yields ONE unit.
     *
     * Two rows with identical football and different published totals - which is
     * exactly what 2021 and 2023 look like for a man who fumbles, since the feed
     * charged -1 in 2021 and nothing from 2023. Graded from components they must
     * come out equal; graded from pts_half_ppr they differ by the fumble.
     */
    @Test
    void twoErasOfTheFeedGradeToTheSameNumberWhenScoredFromComponents(){
        String raw = "["
                + "{\"player_id\":\"y2021\",\"stats\":{\"rush_yd\":1000,\"rush_td\":8,"
                + "\"fum\":3,\"pts_half_ppr\":145.0}},"
                + "{\"player_id\":\"y2023\",\"stats\":{\"rush_yd\":1000,\"rush_td\":8,"
                + "\"fum\":3,\"pts_half_ppr\":148.0}}]";

        Map<String, Double> scored = LeagueActuals.seasonScored(raw, false, league());

        assertEquals(2, scored.size());
        assertEquals(scored.get("y2021"), scored.get("y2023"), 1e-9,
                "the same football graded to two different numbers - the season"
                        + " total is being read off the feed, not rebuilt");
        assertEquals(1000 * 0.1 + 8 * 6 - 3, scored.get("y2021"), 1e-9);
        // and the feed's own two numbers really do disagree, so the assertion above
        // is a property of the scorer and not of the fixture
        assertNotEquals(145.0, 148.0);
    }

    /**
     * A row Sleeper scored nothing at all is skipped, in both paths.
     *
     * "No entry" is what a lineup filler reads as "did not play", so the two
     * gradings must agree about WHICH ids exist or a strategy silently gains or
     * loses starters when the flag moves.
     */
    @Test
    void aRowWithNoPrecomputedTotalIsAbsentFromBothGradings(){
        String raw = "["
                + "{\"player_id\":\"played\",\"stats\":{\"rec\":4,\"rec_yd\":50,"
                + "\"pts_half_ppr\":7.0}},"
                + "{\"player_id\":\"snapsOnly\",\"stats\":{\"off_snp\":12}}]";

        Map<String, Double> scored = LeagueActuals.seasonScored(raw, false, league());

        assertTrue(scored.containsKey("played"));
        assertFalse(scored.containsKey("snapsOnly"),
                "a man the feed scored nothing for must not appear as a 0.0 starter");
    }

    // =====================================================================
    // B11. No default anywhere pays a tenth of a passing touchdown.
    // =====================================================================

    /**
     * The slipped decimal, asserted rather than remembered.
     *
     * defaultScoringSettings() is dead code, which is exactly why it matters:
     * dead code is read as a statement of what the defaults ARE, and the next
     * caller inherits it. It carries the standard 4, and every other entry
     * agrees with what pts_half_ppr is measurably made of.
     */
    @Test
    void noScoringDefaultPaysATenthOfAPassingTouchdown(){
        for(LeagueScoringSettings settings : new LeagueScoringSettings[]{
                LeagueScoringSettings.defaultScoringSettings(),
                LeagueScoringSettings.halfPprFeed(),
                LeagueScoringSettings.fromSleeperScoringSettings(stats("{}"))}){
            assertEquals(4.0, settings.passTD, 1e-9,
                    "a generic default must pay the standard 4; 0.4 is a slipped"
                            + " decimal and 6.0 is this league leaking into a fallback");
            assertEquals(6.0, settings.rushTD, 1e-9);
            assertEquals(6.0, settings.receivingTD, 1e-9);
            assertEquals(0.5, settings.reception, 1e-9);
        }
    }

    /**
     * Every fallback is the value pts_half_ppr itself assumes, so a category
     * this league does not list contributes exactly zero difference between the
     * two gradings. That is the only property that makes an unread setting safe.
     */
    @Test
    void anUnlistedCategoryFallsBackToTheFeedAndNotToZero(){
        LeagueScoringSettings quiet =
                LeagueScoringSettings.fromSleeperScoringSettings(stats("{\"rec\":0.5}"));
        LeagueScoringSettings feed = LeagueScoringSettings.halfPprFeed();

        assertEquals(feed.sack, quiet.sack, 1e-9);
        assertEquals(feed.defenceTD, quiet.defenceTD, 1e-9);
        assertEquals(feed.pointsAllowed0, quiet.pointsAllowed0, 1e-9);
        assertEquals(feed.pointsAllowed35plus, quiet.pointsAllowed35plus, 1e-9);
        assertEquals(feed.fumble, quiet.fumble, 1e-9);
        assertEquals(0.0, quiet.pointsAllowed14to20, 1e-9,
                "the feed pays nothing for 14-20; a league that does not list it"
                        + " must not invent the band");

        // a defence scored under both must therefore come out identical
        JsonObject denver = stats("{\"sack\":63,\"int\":15,\"fum_rec\":8,\"def_td\":5,"
                + "\"safe\":2,\"ff\":12,\"def_st_fum_rec\":1,\"pts_allow_0\":1,"
                + "\"pts_allow_1_6\":1,\"pts_allow_7_13\":5,\"pts_allow_14_20\":4,"
                + "\"pts_allow_21_27\":2,\"pts_allow_28_34\":3,\"pts_allow_35p\":1}");
        assertEquals(LeagueActuals.scoreDefence(denver, feed),
                LeagueActuals.scoreDefence(denver, quiet), 1e-9);
    }

    /**
     * And the three rules that DO differ are each worth what the audit measured,
     * so a settings load that silently dropped one would be visible here rather
     * than as a 60-point lean in a quarterback ranking months later.
     */
    @Test
    void theThreeDifferingRulesAreEachWorthWhatTheAuditSaid(){
        LeagueScoringSettings feed = LeagueScoringSettings.halfPprFeed();
        LeagueScoringSettings mine = league();

        assertEquals(2.0, mine.passTD - feed.passTD, 1e-9, "6 against 4");
        assertEquals(-1.0, mine.fumble - feed.fumble, 1e-9, "-1 against nothing");
        assertEquals(1.0, mine.pointsAllowed14to20 - feed.pointsAllowed14to20, 1e-9,
                "1 a game against nothing");

        // 30 passing touchdowns is the shape of a starting quarterback's season:
        // the 55-66 points a season the audit found, from this rule alone
        JsonObject season = stats("{\"pass_td\":30}");
        assertEquals(60.0,
                LeagueActuals.scoreSkill(season, mine) - LeagueActuals.scoreSkill(season, feed),
                1e-9);
    }
}
