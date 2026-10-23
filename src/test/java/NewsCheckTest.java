import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The news tool's pure parts: the IR rule, the trending feed, usage shares, bye and next game. */
public class NewsCheckTest {

    /** This league's switches on 2026-09-29: two slots; Out, Doubtful and COV allowed; Sus, NA and DNR not. */
    private static final String SETTINGS = "{\"reserve_slots\":2,\"reserve_allow_out\":1,\"reserve_allow_doubtful\":1,"
            + "\"reserve_allow_sus\":0,\"reserve_allow_na\":0,\"reserve_allow_cov\":1,\"reserve_allow_dnr\":0}";

    @Test
    public void irEligibilityFollowsTheLeagueSwitches(){
        NewsCheck.ReserveRule rule = NewsCheck.ReserveRule.of(JsonParser.parseString(SETTINGS).getAsJsonObject());
        assertEquals(2, rule.slots());
        for(String yes : List.of("IR", "PUP", "Out", "Doubtful", "COV")){
            assertTrue(rule.eligible(yes), yes);
        }
        for(String no : List.of("Questionable", "Sus", "NA", "DNR")){
            assertFalse(rule.eligible(no), no);
        }
        assertFalse(rule.eligible(null), "a healthy man is not IR-eligible");
    }

    @Test
    public void withNoReserveSlotsNobodyIsEligible(){
        JsonObject none = JsonParser.parseString(SETTINGS.replace("\"reserve_slots\":2", "\"reserve_slots\":0"))
                .getAsJsonObject();
        assertFalse(NewsCheck.ReserveRule.of(none).eligible("IR"));
    }

    @Test
    public void trendingKeepsTheFeedsOrderAndCounts(){
        Map<String, Integer> hot = NewsCheck.trending("[{\"player_id\":\"12495\",\"count\":41210},"
                + "{\"player_id\":\"MIA\",\"count\":900},{\"count\":3}]");
        assertEquals(List.of("12495", "MIA"), List.copyOf(hot.keySet()));
        assertEquals(41210, hot.get("12495"));
    }

    private static Map<String, NewsCheck.WeekLine> week(Object... rows){
        Map<String, NewsCheck.WeekLine> out = new java.util.LinkedHashMap<>();
        for(int i = 0; i < rows.length; i += 2){
            out.put((String) rows[i], (NewsCheck.WeekLine) rows[i + 1]);
        }
        return out;
    }

    /**
     * Newest first: a game he missed counts as zero, a bye is skipped and does
     * not use up one of the three, and the fourth game back is not read.
     */
    @Test
    public void usageIsPooledOverHisTeamsLastThreeGames(){
        List<Map<String, NewsCheck.WeekLine>> weeks = List.of(
                week("him", new NewsCheck.WeekLine("MIA", 50, 60, 5), "mate", new NewsCheck.WeekLine("MIA", 40, 60, 15)),
                week("mate", new NewsCheck.WeekLine("MIA", 60, 70, 10)),                        // he missed it
                week("other", new NewsCheck.WeekLine("BUF", 60, 60, 20)),                       // Miami's bye
                week("him", new NewsCheck.WeekLine("MIA", 30, 70, 2), "mate", new NewsCheck.WeekLine("MIA", 70, 70, 8)),
                week("him", new NewsCheck.WeekLine("MIA", 70, 70, 30)));                        // a fourth game: not read
        NewsCheck.Usage usage = NewsCheck.usage("him", "MIA", weeks, 3);
        assertEquals(3, usage.games());
        assertEquals((50.0 + 0 + 30) / (60 + 70 + 70), usage.snapShare(), 1e-12);
        assertEquals((5.0 + 0 + 2) / (20 + 10 + 10), usage.targetShare(), 1e-12);
    }

    /** A man traded mid-season is measured against the club his own row names that week. */
    @Test
    public void aTradedManIsMeasuredAgainstTheTeamHePlayedFor(){
        List<Map<String, NewsCheck.WeekLine>> weeks = List.of(
                week("him", new NewsCheck.WeekLine("NYJ", 10, 50, 1), "jet", new NewsCheck.WeekLine("NYJ", 50, 50, 9)),
                week("him", new NewsCheck.WeekLine("MIA", 40, 40, 4), "fin", new NewsCheck.WeekLine("MIA", 40, 40, 4)));
        NewsCheck.Usage usage = NewsCheck.usage("him", "NYJ", weeks, 3);
        assertEquals(2, usage.games());
        assertEquals(50.0 / 90, usage.snapShare(), 1e-12);
        assertTrue(Double.isNaN(NewsCheck.usage("nobody", "KC", weeks, 3).snapShare()),
                "a club with no rows has no share, not a zero");
    }

    private static NflverseGames.Game game(int week, String day, String time, String home, String away){
        return new NflverseGames.Game("g" + week + home, "2026", week, day, time, home, away, null, null, false, false,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    public void byeAndNextOpponentComeOffTheSchedule(){
        List<NflverseGames.Game> games = List.of(
                game(1, "2026-09-13", "13:00", "MIA", "NE"),
                game(2, "2026-09-20", "13:00", "BUF", "MIA"),
                game(4, "2026-10-04", "13:00", "LA", "MIA"),
                game(5, "2026-10-11", "16:25", "MIA", "NYJ"),
                game(3, "2026-09-27", "13:00", "NE", "BUF"));
        assertEquals(3, NewsCheck.bye(games, "2026", "MIA", 5));
        assertNull(NewsCheck.bye(games, "2025", "MIA", 5), "a season the file does not carry has no known bye");
        LocalDateTime tuesday = LocalDateTime.parse("2026-09-29T09:00");
        assertEquals("w4 @LAR", NewsCheck.next(games, "2026", "MIA", 3, tuesday), "nflverse's LA is Sleeper's LAR");
        assertEquals("w5 v NYJ", NewsCheck.next(games, "2026", "MIA", 3, LocalDateTime.parse("2026-10-04T13:00")),
                "a game that has kicked off is not next");
    }

    @Test
    public void aNamedManIsFoundWithOrWithoutHisSuffix(){
        JsonObject db = JsonParser.parseString("{\"12495\":{\"full_name\":\"Ollie Gordon\",\"team\":\"MIA\"},"
                + "\"1\":{\"full_name\":\"Ollie Gordon\",\"team\":null},\"MIA\":{\"first_name\":\"Miami\",\"last_name\":\"Dolphins\",\"team\":\"MIA\"}}")
                .getAsJsonObject();
        assertEquals(List.of("12495"), NewsCheck.named("Ollie Gordon II", db), "the II is dropped, and a man with no club is not him");
        assertEquals(List.of("MIA"), NewsCheck.named("MIA", db));
        assertEquals(Set.of(), Set.copyOf(NewsCheck.named("Nobody Here", db)));
    }
}
