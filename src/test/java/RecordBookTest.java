import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** The matchups feed read into team weeks and started-man weeks. */
public class RecordBookTest {

    private static final String BODY = "["
            + "{\"roster_id\":1,\"matchup_id\":1,\"points\":150.5,\"starters\":[\"11\",\"12\",\"0\"],\"starters_points\":[40.2,10.0,0.0]},"
            + "{\"roster_id\":2,\"matchup_id\":1,\"points\":99.0,\"starters\":[\"21\"],\"starters_points\":[9.0]},"
            + "{\"roster_id\":3,\"matchup_id\":null,\"points\":120.0,\"starters\":[\"31\"],\"starters_points\":[30.0]},"
            + "{\"roster_id\":4,\"matchup_id\":2,\"points\":0,\"starters\":[],\"starters_points\":[]}"
            + "]";
    private static final Map<Integer, String> MANAGERS = Map.of(1, "A", 2, "B", 3, "C", 4, "D");

    @Test
    public void everyScoredRosterIsAWeekAndTheOpponentComesThroughTheMatchup(){
        List<RecordBook.TeamWeek> weeks = RecordBook.teamWeeks(BODY, "2024", 5, MANAGERS, false);
        assertEquals(3, weeks.size(), "a roster with no points is an unplayed week, not a zero");
        RecordBook.TeamWeek a = weeks.get(0);
        assertEquals("A", a.manager());
        assertEquals(150.5, a.points(), 1e-9);
        assertEquals("B", a.opponent());
        assertEquals("beat B 99.0", a.result());
        assertEquals("lost to A 150.5", weeks.get(1).result());
        RecordBook.TeamWeek c = weeks.get(2);
        assertTrue(c.noGame(), "a null matchup is a week with no game scheduled");
        assertEquals("no game", c.result());
        assertTrue(RecordBook.teamWeeks(BODY, "2024", 15, MANAGERS, true).get(0).playoff());
    }

    @Test
    public void theShareIsTheBestStartersOverTheTeamsPoints(){
        List<RecordBook.ManWeek> lineup = List.of(
                new RecordBook.ManWeek("2024", 5, "A", "1", 50.0),
                new RecordBook.ManWeek("2024", 5, "A", "2", 30.0),
                new RecordBook.ManWeek("2024", 5, "A", "3", 15.0),
                new RecordBook.ManWeek("2024", 5, "A", "4", 5.0));
        assertEquals(0.5, RecordBook.shareOfTop(lineup, 100, 1), 1e-9);
        assertEquals(0.95, RecordBook.shareOfTop(lineup, 100, 3), 1e-9);
        assertEquals(1.0, RecordBook.shareOfTop(lineup, 100, 10), 1e-9, "more men than the lineup holds is the whole lineup");
        assertEquals(0.0, RecordBook.shareOfTop(lineup, 0, 1), 1e-9, "a week with no points has no share");
        assertEquals(0.0, RecordBook.shareOfTop(List.of(), 100, 1), 1e-9);
        assertEquals("2024|5|A", RecordBook.key("2024", 5, "A"));
    }

    @Test
    public void startedMenAreReadWithTheirPointsAndEmptySlotsSkipped(){
        List<RecordBook.ManWeek> men = RecordBook.manWeeks(BODY, "2024", 5, MANAGERS);
        assertEquals(4, men.size(), "the '0' slot is empty, not a man");
        assertEquals("11", men.get(0).playerID());
        assertEquals(40.2, men.get(0).points(), 1e-9);
        assertEquals("A", men.get(0).manager());
        assertEquals("31", men.get(3).playerID());
    }
}
