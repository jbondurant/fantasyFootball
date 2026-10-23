import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

/** The player metadata's expiry: daily in season, weekly out of it. */
public class PlayerRawDataTest {

    private static final LocalDate MONDAY = LocalDate.parse("2026-09-28");

    /** On 2026-09-28 the file was from 2026-09-21: still fresh under the week, and it had Mike Evans on the wrong injury. */
    @Test
    public void inSeasonAFileFromAnEarlierDayIsStale(){
        assertTrue(PlayerRawData.stale(LocalDate.parse("2026-09-21"), MONDAY, true, null));
        assertTrue(PlayerRawData.stale(LocalDate.parse("2026-09-27"), MONDAY, true, null),
                "yesterday's injury tags are yesterday's");
        assertFalse(PlayerRawData.stale(MONDAY, MONDAY, true, null), "fetched today is fresh");
    }

    @Test
    public void outOfSeasonTheWeekStands(){
        assertFalse(PlayerRawData.stale(LocalDate.parse("2026-09-27"), MONDAY, false, null));
        assertFalse(PlayerRawData.stale(LocalDate.parse("2026-09-22"), MONDAY, false, null));
        assertTrue(PlayerRawData.stale(LocalDate.parse("2026-09-21"), MONDAY, false, null));
    }

    @Test
    public void anExplicitDayCountOverridesBoth(){
        assertFalse(PlayerRawData.stale(LocalDate.parse("2026-09-25"), MONDAY, true, 7));
        assertTrue(PlayerRawData.stale(LocalDate.parse("2026-09-27"), MONDAY, false, 1));
    }
}
