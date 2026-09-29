import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import PlayerImportAndSetup.Position;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The next man up: his team's leader at his position went down in the week
 * just played. Built on the week that taught it - Baltimore, 2026 week 1:
 * Zay Flowers (the receiver the market drafted first) played 20 of 68 snaps,
 * Rashod Bateman 53, and Bateman drew four bids the next morning.
 */
public class NextManUpTest {

    static NextManUp.Line wr(String team, double snaps, double teamSnaps){
        return new NextManUp.Line(team, Position.WR, snaps, teamSnaps);
    }

    static final Map<String, Double> ADP = Map.of("flowers", 30.0, "bateman", 140.0, "moore", 280.0,
            "chase", 2.0, "higgins", 25.0);

    @Test
    public void weekOneBaltimore(){
        Map<Integer, Map<String, NextManUp.Line>> weeks = Map.of(1, Map.of(
                "flowers", wr("BAL", 20, 68), "bateman", wr("BAL", 53, 68), "moore", wr("BAL", 25, 68),
                "chase", wr("CIN", 60, 64), "higgins", wr("CIN", 58, 64)));
        Map<String, String> promoted = NextManUp.promoted(weeks, 1, ADP);
        assertEquals(Map.of("bateman", "flowers"), promoted,
                "Flowers played under half of Bateman's share; Cincinnati's leader played his full game");
    }

    @Test
    public void aLeaderAlreadyOutIsOldNewsAndAByeIsNotAGame(){
        Map<Integer, Map<String, NextManUp.Line>> weeks = new HashMap<>();
        weeks.put(1, Map.of("flowers", wr("BAL", 60, 68), "bateman", wr("BAL", 40, 68)));
        weeks.put(2, Map.of("bateman", wr("BAL", 55, 60)));          // Flowers out: the event
        weeks.put(3, Map.of("bateman", wr("BAL", 57, 62)));          // still out: old news
        assertEquals(Map.of("bateman", "flowers"), NextManUp.promoted(weeks, 2, ADP));
        assertEquals(Map.of(), NextManUp.promoted(weeks, 3, ADP), "a leader out for the second week is not a new event");

        Map<Integer, Map<String, NextManUp.Line>> bye = new HashMap<>();
        bye.put(4, Map.of("flowers", wr("BAL", 62, 66), "bateman", wr("BAL", 41, 66)));
        bye.put(5, Map.of("chase", wr("CIN", 60, 64)));                // Baltimore on its bye
        bye.put(6, Map.of("flowers", wr("BAL", 12, 70), "bateman", wr("BAL", 60, 70)));
        assertEquals(Map.of("bateman", "flowers"), NextManUp.promoted(bye, 6, ADP),
                "the game before week 6 was week 4 - the bye has no lines and is skipped");
    }

    @Test
    public void theNextManIsTheOneWithTheSnapsAndAnUndraftedRoomHasNoLeader(){
        Map<Integer, Map<String, NextManUp.Line>> weeks = Map.of(1, Map.of(
                "flowers", wr("BAL", 0, 68), "bateman", wr("BAL", 30, 68), "moore", wr("BAL", 50, 68)));
        assertEquals(Map.of("moore", "flowers"), NextManUp.promoted(weeks, 1, ADP),
                "the man the snaps went to, not the better pick behind him");
        Map<Integer, Map<String, NextManUp.Line>> nobody = Map.of(1, Map.of(
                "a", wr("NYJ", 0, 60), "b", wr("NYJ", 50, 60)));
        assertEquals(Map.of(), NextManUp.promoted(nobody, 1, ADP), "no drafted man there: no leader to lose");
    }

    @Test
    public void wentDownAgainstHisOwnPastOrTheManBehindHim(){
        assertTrue(NextManUp.wentDown(List.of(), false, 0, 0.29, 0.78), "week 1: under half of the next man's share");
        assertFalse(NextManUp.wentDown(List.of(), false, 0, 0.45, 0.55), "a committee is not an injury");
        assertFalse(NextManUp.wentDown(List.of(), true, 0, 0.0, 0.8), "lines before and never a snap: already out");
        assertTrue(NextManUp.wentDown(List.of(0.9, 0.85), true, 0.88, 0.2, 0.7));
        assertFalse(NextManUp.wentDown(List.of(0.9, 0.85), true, 0.1, 0.0, 0.7), "down the week before too: old news");
        assertFalse(NextManUp.wentDown(List.of(0.9), true, 0.9, 0.6, 0.4), "two thirds of his usual is a normal week");
    }

    @Test
    public void linesComeFromTheStatsArrayWithTheTeamOfTheWeek(){
        String body = "[{\"team\":\"BAL\",\"player_id\":\"7\",\"player\":{\"position\":\"WR\"},\"stats\":{\"off_snp\":53,\"tm_off_snp\":68}},"
                + "{\"team\":\"BAL\",\"player_id\":\"8\",\"player\":{\"position\":\"K\"},\"stats\":{}},"
                + "{\"team\":null,\"player_id\":\"9\",\"player\":{\"position\":\"RB\"},\"stats\":{\"off_snp\":10}},"
                + "{\"team\":\"KC\",\"player_id\":\"10\",\"player\":{\"position\":\"TE\"},\"stats\":{\"off_snp\":null}}]";
        Map<String, NextManUp.Line> lines = NextManUp.lines(body);
        assertEquals(2, lines.size(), "a kicker and a man without a team are skipped");
        assertEquals(53.0 / 68, lines.get("7").share(), 1e-9);
        assertEquals(0.0, lines.get("10").share(), 1e-9, "no snaps recorded reads as none");
        assertEquals("KC", lines.get("10").team());
    }
}
