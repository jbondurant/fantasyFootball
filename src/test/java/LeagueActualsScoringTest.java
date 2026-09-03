import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The actuals side of the scoring.
 *
 * StatLineScoringTest covers the PROJECTION scorer. This covers the one that
 * grades what really happened, which has to handle three things the projection
 * feed never publishes: every fumble rather than only the lost ones, return
 * touchdowns, and a real defensive stat line.
 *
 * The stat lines below are copied verbatim out of Sleeper's cached season
 * responses, so each test asserts two things at once - that the league scorer
 * adds up, and that scoring the SAME line at the feed's own values reproduces
 * the pts_half_ppr Sleeper published for it. The second half is what licenses
 * calling those values "standard" anywhere else.
 */
class LeagueActualsScoringTest {

    /** The 2026 league: half PPR, 6-point passing TDs, -1 a fumble, 1 for 14-20. */
    private static LeagueScoringSettings league(){
        return LeagueScoringSettings.fromSleeperScoringSettings(stats(
                "{\"pass_yd\":0.04,\"pass_td\":6.0,\"pass_int\":-1.0,\"rush_yd\":0.1,"
                + "\"rush_td\":6.0,\"rec\":0.5,\"rec_yd\":0.1,\"rec_td\":6.0,"
                + "\"fum_lost\":-2.0,\"fum\":-1.0,\"pass_2pt\":2.0,\"rush_2pt\":2.0,"
                + "\"rec_2pt\":2.0,\"st_td\":6.0,\"st_ff\":1.0,\"st_fum_rec\":1.0,"
                + "\"fum_rec_td\":6.0,\"sack\":1.0,\"int\":2.0,\"fum_rec\":2.0,"
                + "\"def_td\":6.0,\"safe\":2.0,\"blk_kick\":2.0,\"ff\":1.0,"
                + "\"def_st_td\":6.0,\"def_st_ff\":1.0,\"def_st_fum_rec\":1.0,"
                + "\"pts_allow_0\":10.0,\"pts_allow_1_6\":7.0,\"pts_allow_7_13\":4.0,"
                + "\"pts_allow_14_20\":1.0,\"pts_allow_21_27\":0.0,"
                + "\"pts_allow_28_34\":-1.0,\"pts_allow_35p\":-4.0}"));
    }

    private static JsonObject stats(String json){
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** Lamar Jackson, 2024, from sleeperActualsFinal2024. */
    private static JsonObject lamar2024(){
        return stats("{\"pass_yd\":4172,\"pass_td\":41,\"pass_int\":4,\"rush_yd\":915,"
                + "\"rush_td\":4,\"rush_2pt\":1,\"fum\":10,\"fum_lost\":5,"
                + "\"pts_half_ppr\":434.38}");
    }

    /** Denver, 2024, from sleeperActualsDef2024 - the season's top defence. */
    private static JsonObject denver2024(){
        return stats("{\"sack\":63,\"int\":15,\"fum_rec\":8,\"def_td\":5,\"safe\":2,"
                + "\"ff\":12,\"def_st_fum_rec\":1,\"fg_blkd\":1,"
                + "\"pts_allow_0\":1,\"pts_allow_1_6\":1,\"pts_allow_7_13\":5,"
                + "\"pts_allow_14_20\":4,\"pts_allow_21_27\":2,\"pts_allow_28_34\":3,"
                + "\"pts_allow_35p\":1,\"pts_half_ppr\":186.0}");
    }

    @Test
    void theFeedValuesRebuildSleepersOwnNumberForASkillPlayer(){
        // The whole audit rests on this: "standard" is not Sleeper's advertised
        // default for a new league, it is what the published field is made of.
        Assertions.assertEquals(434.38,
                LeagueActuals.scoreSkill(lamar2024(), LeagueScoringSettings.halfPprFeed()),
                0.011);
    }

    @Test
    void theFeedValuesRebuildSleepersOwnNumberForADefence(){
        Assertions.assertEquals(186.0,
                LeagueActuals.scoreDefence(denver2024(), LeagueScoringSettings.halfPprFeed()),
                0.011);
    }

    @Test
    void aPassingTouchdownIsWorthSixAndAFumbleCostsOne(){
        double scored = LeagueActuals.scoreSkill(lamar2024(), league());

        double expected = 4172 * 0.04 + 41 * 6 - 4 + 915 * 0.1 + 4 * 6 + 2
                - 5 * 2      // fumbles lost
                - 10;        // and every fumble, which the feed stopped charging
        Assertions.assertEquals(506.38, expected, 0.011);
        Assertions.assertEquals(expected, scored, 0.011);

        // 82 points of passing touchdown back, 10 of fumbles charged: net +72.0
        Assertions.assertEquals(72.0, scored - 434.38, 0.011);
    }

    @Test
    void aLostFumbleIsAlsoAFumbleAndCostsBoth(){
        JsonObject one = stats("{\"rush_yd\":0,\"fum\":1,\"fum_lost\":1}");

        Assertions.assertEquals(-3.0, LeagueActuals.scoreSkill(one, league()), 0.0001);
    }

    @Test
    void aDefenceGainsExactlyTheFourteenToTwentyBand(){
        double graded = LeagueActuals.scoreDefence(denver2024(),
                LeagueScoringSettings.halfPprFeed());
        double scored = LeagueActuals.scoreDefence(denver2024(), league());

        // Denver held four opponents to 14-20 points. The league pays 1 for
        // each of those games and the feed pays nothing; nothing else differs.
        Assertions.assertEquals(4.0, scored - graded, 0.011);
    }

    @Test
    void pointsAllowedBandsAreGameCountsNotFlags(){
        // Seventeen games, all of them in the same band. Reading the band as a
        // yes/no would score this 4 instead of 68.
        JsonObject stingy = stats("{\"pts_allow_7_13\":17}");

        Assertions.assertEquals(68.0, LeagueActuals.scoreDefence(stingy, league()), 0.0001);
    }

    @Test
    void aReturnTouchdownIsScoredForASkillPlayer(){
        // Sleeper's own field pays these too, so this is agreement, not a gap -
        // but the actuals scorer has to know about them or it would disagree.
        JsonObject returner = stats("{\"rec\":40,\"rec_yd\":500,\"st_td\":2}");

        double expected = 40 * 0.5 + 500 * 0.1 + 2 * 6;
        Assertions.assertEquals(expected, LeagueActuals.scoreSkill(returner, league()), 0.0001);
        Assertions.assertEquals(expected,
                LeagueActuals.scoreSkill(returner, LeagueScoringSettings.halfPprFeed()), 0.0001);
    }

    @Test
    void aCategoryTheLeagueDoesNotListCannotInventAGap(){
        // The fallback for an unlisted rule is the feed's own value, so a league
        // that is silent about fumbles scores them exactly as pts_half_ppr does.
        LeagueScoringSettings quiet =
                LeagueScoringSettings.fromSleeperScoringSettings(stats("{\"rec\":0.5}"));

        Assertions.assertEquals(
                LeagueActuals.scoreSkill(lamar2024(), LeagueScoringSettings.halfPprFeed()),
                LeagueActuals.scoreSkill(lamar2024(), quiet), 0.011);
    }

    @Test
    void theOldPositionalConstructorStillScoresADefenceLikeTheFeed(){
        // Every caller that predates the defence categories builds settings this
        // way. If the new fields defaulted to zero, those callers would silently
        // start scoring defences at nothing - the 0.0 bug, again.
        LeagueScoringSettings positional = new LeagueScoringSettings(
                new double[]{0.04, 6.0, -1.0, 0.1, 6.0, 0.5, 0.1, 6.0, -2.0});

        Assertions.assertEquals(186.0,
                LeagueActuals.scoreDefence(denver2024(), positional), 0.011);
    }

    @Test
    void theCorrectedPathIsOffUnlessAskedFor(){
        // The operational constraint, asserted rather than trusted: other work
        // is running against the old measure and must not be moved under it.
        Assertions.assertFalse(LeagueActuals.enabled(),
                "leagueScoredActuals must default to the existing pts_half_ppr grading");
    }
}
