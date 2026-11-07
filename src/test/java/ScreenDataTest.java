import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import PlayerImportAndSetup.Position;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The screen's population rules, each one a pre-registration finding: played
 * is gp, a played zero stays in, the row joins its game on opponent and date,
 * week 17 of 2018-2020 is out, and the schedule is read from both sides.
 */
public class ScreenDataTest {

    static final String BODY = "["
            + "{\"player_id\":\"1\",\"team\":\"BUF\",\"opponent\":\"MIA\",\"date\":\"2020-10-04\",\"player\":{\"position\":\"WR\",\"last_name\":\"A\"},"
            + "\"stats\":{\"gp\":1,\"gs\":1,\"rec_tgt\":8,\"rec\":6,\"rec_yd\":80,\"rec_td\":1,\"pts_half_ppr\":17}},"
            + "{\"player_id\":\"2\",\"team\":\"BUF\",\"opponent\":\"MIA\",\"date\":\"2020-10-04\",\"player\":{\"position\":\"TE\",\"last_name\":\"B\"},"
            + "\"stats\":{\"gp\":1,\"rec_tgt\":2}},"
            + "{\"player_id\":\"3\",\"team\":\"BUF\",\"opponent\":\"MIA\",\"date\":\"2020-10-04\",\"player\":{\"position\":\"RB\",\"last_name\":\"C\"},"
            + "\"stats\":{\"gms_active\":1}},"
            + "{\"player_id\":\"4\",\"team\":\"BUF\",\"opponent\":\"MIA\",\"date\":\"2020-10-04\",\"player\":{\"position\":\"K\",\"last_name\":\"D\"},"
            + "\"stats\":{\"gp\":1}},"
            + "{\"player_id\":\"5\",\"team\":null,\"opponent\":null,\"player\":{\"position\":\"WR\",\"last_name\":\"E\"},\"stats\":{\"gp\":1}}"
            + "]";

    @Test
    public void playedIsGpAndAPlayedZeroIsKeptAtZero(){
        LeagueScoringSettings scoring = LeagueScoringSettings.halfPprFeed();
        Map<String, ScreenData.Line> lines = ScreenData.lines(BODY, scoring);
        assertEquals(3, lines.size(), "a kicker and a man without a team are not skill rows");
        assertTrue(lines.get("1").played());
        assertEquals(8.0 + scoring.receivingTD + 6 * scoring.reception, lines.get("1").points(), 1e-9, "scored under the league's rules");
        assertTrue(lines.get("2").played(), "gp with no scoring stat: he played");
        assertEquals(0, lines.get("2").points(), 1e-9, "and scored zero - kept, not dropped");
        assertFalse(lines.get("3").played(), "active but no gp: did not play");
        assertEquals(8, lines.get("1").touches(), 1e-9, "carries + targets");
        assertEquals(6, lines.get("1").opportunities(), 1e-9, "carries + catches, what Sleeper projects");
    }

    static NflverseGames.Side side(String season, int week, String team, String opponent, String day){
        return new NflverseGames.Side("g", season, week, team, opponent, true, false, day, "Sunday", "13:00", 1.0, 44.0,
                7, 7, false, "outdoors", "grass", 60.0, 5.0, null, "c", "d", "s", team, 3.0);
    }

    @Test
    public void theRowJoinsItsGameOnOpponentAndDateAndWeek17Of2020IsOut(){
        LeagueScoringSettings scoring = LeagueScoringSettings.halfPprFeed();
        ScreenData.Season s = new ScreenData.Season("2020");
        s.lines.put(4, ScreenData.lines(BODY, scoring));
        s.projectedPoints.put(4, Map.of("1", 12.0, "2", 3.0, "3", 9.0));
        s.lines.put(17, ScreenData.lines(BODY.replace("2020-10-04", "2020-12-27"), scoring));
        s.projectedPoints.put(17, Map.of("1", 12.0));
        Map<String, NflverseGames.Side> sides = new HashMap<>();
        sides.put(NflverseGames.key("2020", 4, "BUF"), side("2020", 4, "BUF", "MIA", "2020-10-04"));
        sides.put(NflverseGames.key("2020", 17, "BUF"), side("2020", 17, "BUF", "MIA", "2020-12-27"));
        ScreenData.Audit audit = new ScreenData.Audit();
        List<ScreenData.Row> rows = ScreenData.population(Map.of("2020", s), List.of("2020"), sides, 5.0, audit);
        assertEquals(1, rows.size(), "the WR; the TE is projected under 5, the RB did not play, week 17 of 2020 is the season's last");
        assertEquals("1", rows.get(0).id());
        assertEquals(1, audit.of("2020")[4], "the RB projected 9 who did not play is counted, not kept");

        sides.put(NflverseGames.key("2020", 4, "BUF"), side("2020", 4, "BUF", "NYJ", "2020-10-04"));
        ScreenData.Audit wrong = new ScreenData.Audit();
        assertTrue(ScreenData.population(Map.of("2020", s), List.of("2020"), sides, 5.0, wrong).isEmpty(),
                "a schedule that disagrees on the opponent does not join");
        assertEquals(1, wrong.of("2020")[3]);
        assertEquals(16, ScreenData.lastPopulationWeek("2020"));
        assertEquals(17, ScreenData.lastPopulationWeek("2021"));
    }

    @Test
    public void aManProjectedToThrowIsAQuarterbackThatWeek(){
        LeagueScoringSettings scoring = LeagueScoringSettings.halfPprFeed();
        ScreenData.Season s = new ScreenData.Season("2020");
        s.lines.put(11, ScreenData.lines(BODY.replace("2020-10-04", "2020-11-22"), scoring));
        s.projectedPoints.put(11, Map.of("2", 21.0));
        com.google.gson.JsonObject hill = new com.google.gson.JsonObject();
        hill.addProperty("pass_att", 23.5);
        s.projected.put(11, Map.of("2", hill));
        Map<String, NflverseGames.Side> sides = Map.of(NflverseGames.key("2020", 11, "BUF"), side("2020", 11, "BUF", "MIA", "2020-11-22"));
        ScreenData.Audit audit = new ScreenData.Audit();
        List<ScreenData.Row> rows = ScreenData.population(Map.of("2020", s), List.of("2020"), sides, 5.0, audit);
        assertEquals(1, rows.size());
        assertEquals(Position.QB, rows.get(0).position(), "the database says TE; 23.5 projected attempts say QB");
        assertEquals(1, audit.of("2020")[5], "and the audit counts it");
    }

    @Test
    public void theScheduleIsReadFromBothSidesInSleepersCodes(){
        List<String> lines = List.of(
                "game_id,season,game_type,week,gameday,weekday,gametime,away_team,away_score,home_team,home_score,location,result,"
                        + "total,away_rest,home_rest,spread_line,total_line,div_game,roof,surface,temp,wind,away_qb_id,home_qb_id,"
                        + "away_coach,home_coach,stadium_id",
                "2019_01_PIT_LA,2019,REG,1,2019-09-08,Sunday,16:25,PIT,20,LA,27,Home,7,47,7,7,3.5,48.5,0,outdoors,grass ,NA,NA,q1,q2,Tomlin,McVay,LAX01",
                "2019_20_X_Y,2019,POST,20,2020-01-12,Sunday,15:05,KC,1,TEN,2,Home,1,3,7,7,1,50,0,dome,,NA,NA,,,a,b,S");
        Map<String, NflverseGames.Side> sides = NflverseGames.sides(lines);
        assertEquals(2, sides.size(), "one regular-season game, both sides; the playoff game is not read");
        NflverseGames.Side rams = sides.get(NflverseGames.key("2019", 1, "LAR"));
        NflverseGames.Side steelers = sides.get(NflverseGames.key("2019", 1, "PIT"));
        assertNotNull(rams, "nflverse's LA is Sleeper's LAR");
        assertEquals(3.5, rams.spread(), 1e-9);
        assertEquals(-3.5, steelers.spread(), 1e-9, "the away side's spread is the home spread negated");
        assertEquals((48.5 + 3.5) / 2, rams.impliedTotal(), 1e-9);
        assertEquals("LAR", steelers.opponent());
        assertEquals("LAR", steelers.homeTeam());
        assertNull(rams.temp(), "NA is unknown, never zero");
        assertEquals("McVay", rams.coach());
        assertEquals("Tomlin", rams.opponentCoach());
        assertEquals(-7, steelers.margin(), 1e-9);
    }

    @Test
    public void franchisesFollowTheirMoves(){
        assertEquals("LV", ScreenData.franchise("OAK"));
        assertEquals("LAC", ScreenData.franchise("SD"));
        assertEquals("LAR", ScreenData.franchise("STL"));
        assertEquals("LAR", ScreenData.franchise("LA"));
        assertEquals("BUF", ScreenData.franchise("BUF"));
    }
}
