import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Sleeper's own pts_half_ppr pays 4 points for a passing touchdown; this league
 * pays 6. The old code bolted the difference on afterwards with a hardcoded
 * `pts += numPassTD * (6.0 - 4.0)`. Points are recomputed from the projected
 * stat line under the league's real settings instead, so these are the sums
 * that every roster total and trade evaluation rests on.
 */
class StatLineScoringTest {

    /** The 2026 league: half PPR, 6 point passing touchdowns. */
    private static LeagueScoringSettings leagueSettings(){
        return new LeagueScoringSettings(new double[]{
                0.04,   // pass yard
                6.0,    // pass td
                -1.0,   // interception
                0.1,    // rush yard
                6.0,    // rush td
                0.5,    // reception
                0.1,    // receiving yard
                6.0,    // receiving td
                -2.0    // fumble lost
        });                     // two point conversions default to 2.0
    }

    private static JsonObject stats(String json){
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void aQuarterbackIsPaidSixPerPassingTouchdown(){
        JsonObject allen = stats("{\"pass_yd\":3650,\"pass_td\":27,\"pass_int\":10,"
                + "\"rush_yd\":535,\"rush_td\":11,\"fum_lost\":3,\"pts_half_ppr\":361.5}");

        double expected = 3650 * 0.04 + 27 * 6 - 10 * 1 + 535 * 0.1 + 11 * 6 - 3 * 2;

        Assertions.assertEquals(411.5, expected, 0.0001);
        Assertions.assertEquals(expected, SleeperProjections.scoreStatLine(allen, leagueSettings()), 0.0001);
    }

    @Test
    void sleepersOwnTotalIsNotUsedForAPasser(){
        // Regression: the four-point assumption baked into pts_half_ppr is worth
        // ~54 points on a starting quarterback, which is a whole draft round.
        JsonObject allen = stats("{\"pass_yd\":3650,\"pass_td\":27,\"pass_int\":10,"
                + "\"rush_yd\":535,\"rush_td\":11,\"fum_lost\":3,\"pts_half_ppr\":361.5}");

        double scored = SleeperProjections.scoreStatLine(allen, leagueSettings());

        Assertions.assertNotEquals(361.5, scored, 0.0001);
        Assertions.assertEquals(411.5, scored, 0.0001);
    }

    @Test
    void aReceiverIsPaidHalfAPointPerCatch(){
        JsonObject nacua = stats("{\"rec\":107,\"rec_yd\":1400,\"rec_td\":10,"
                + "\"rush_yd\":55,\"fum_lost\":1,\"pts_half_ppr\":259.0}");

        double expected = 107 * 0.5 + 1400 * 0.1 + 10 * 6 + 55 * 0.1 - 1 * 2;

        Assertions.assertEquals(257.0, expected, 0.0001);
        Assertions.assertEquals(expected, SleeperProjections.scoreStatLine(nacua, leagueSettings()), 0.0001);
    }

    @Test
    void aDefenseKeepsSleepersNumber(){
        // Nothing in LeagueScoringSettings scores sacks or points allowed.
        JsonObject rams = stats("{\"sack\":52,\"int\":15,\"fum_rec\":11,\"def_td\":2,\"pts_half_ppr\":106.0}");

        Assertions.assertEquals(106.0, SleeperProjections.scoreStatLine(rams, leagueSettings()), 0.0001);
    }

    @Test
    void aPlayerProjectedForNothingScoresNothing(){
        // Not to be confused with a defense: the stat line is offensive, the
        // numbers are just zero.
        JsonObject deepBench = stats("{\"rec\":0,\"rec_yd\":0,\"rec_td\":0,\"pts_half_ppr\":88.0}");

        Assertions.assertEquals(0.0, SleeperProjections.scoreStatLine(deepBench, leagueSettings()), 0.0001);
    }

    @Test
    void missingCategoriesCountAsZeroRatherThanThrowing(){
        JsonObject sparse = stats("{\"rush_yd\":100}");

        Assertions.assertEquals(10.0, SleeperProjections.scoreStatLine(sparse, leagueSettings()), 0.0001);
    }

    @Test
    void aNullCategoryCountsAsZero(){
        // Sleeper writes null rather than omitting, for players with no carries.
        JsonObject withNulls = stats("{\"rec\":50,\"rec_yd\":600,\"rec_td\":null,\"rush_td\":null}");

        Assertions.assertEquals(50 * 0.5 + 600 * 0.1, SleeperProjections.scoreStatLine(withNulls, leagueSettings()), 0.0001);
    }

    @Test
    void twoPointConversionsAreScored(){
        // The league pays 2 for each and LeagueScoringSettings modelled none of
        // them, so anyone who converted was scored short.
        JsonObject converter = stats("{\"rec\":50,\"rec_yd\":600,\"rec_2pt\":2,\"rush_2pt\":1}");

        double withoutConversions = 50 * 0.5 + 600 * 0.1;

        Assertions.assertEquals(withoutConversions + 3 * 2,
                SleeperProjections.scoreStatLine(converter, leagueSettings()), 0.0001);
    }

    @Test
    void scoringIsReadByNameFromTheLeague(){
        JsonObject sleeperSettings = stats("{\"pass_yd\":0.04,\"pass_td\":6.0,\"pass_int\":-1.0,"
                + "\"rush_yd\":0.1,\"rush_td\":6.0,\"rec\":0.5,\"rec_yd\":0.1,\"rec_td\":6.0,"
                + "\"fum_lost\":-2.0,\"pass_2pt\":2.0,\"rush_2pt\":2.0,\"rec_2pt\":2.0}");

        LeagueScoringSettings settings = LeagueScoringSettings.fromSleeperScoringSettings(sleeperSettings);

        Assertions.assertEquals(6.0, settings.passTD, 0.0001);
        Assertions.assertEquals(0.5, settings.reception, 0.0001);
        Assertions.assertEquals(2.0, settings.receivingTwoPoint, 0.0001);
    }

    @Test
    void aCategoryTheLeagueDoesNotListFallsBackToTheSleeperDefault(){
        LeagueScoringSettings settings =
                LeagueScoringSettings.fromSleeperScoringSettings(stats("{\"rec\":1.0}"));

        Assertions.assertEquals(1.0, settings.reception, 0.0001);
        Assertions.assertEquals(4.0, settings.passTD, 0.0001, "sleeper's default is 4 point passing tds");
    }

    @Test
    void anEmptyStatLineFallsBackRatherThanThrowing(){
        Assertions.assertEquals(0.0, SleeperProjections.scoreStatLine(stats("{}"), leagueSettings()), 0.0001);
    }
}
