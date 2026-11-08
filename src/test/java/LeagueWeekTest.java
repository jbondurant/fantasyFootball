import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * The cache policy, which is the whole reason this class exists: a week that
 * has not happened must never be frozen as an answer.
 */
public class LeagueWeekTest {

    @Test
    public void anEmptyPayloadIsNeverSomethingToKeepForever(){
        assertTrue(InOutUtilities.emptyPayload("{}"), "the live-week stats endpoint answers {} before kickoff");
        assertTrue(InOutUtilities.emptyPayload("[]"), "the 2026 defence stats endpoint answered [] on 2026-09-04");
        assertTrue(InOutUtilities.emptyPayload("   "));
        assertTrue(InOutUtilities.emptyPayload(""));
        assertTrue(InOutUtilities.emptyPayload("null"));
        assertTrue(InOutUtilities.emptyPayload(null));
    }

    @Test
    public void aPayloadWithRowsIsFine(){
        assertFalse(InOutUtilities.emptyPayload("{\"4034\":{\"pts_half_ppr\":18.2}}"));
        assertFalse(InOutUtilities.emptyPayload("[{\"player_id\":\"BAL\"}]"));
        assertFalse(InOutUtilities.emptyPayload("0"), "a scalar is a payload, however odd");
    }

    @Test
    public void aFinishedWeekAndALiveWeekNeverShareACacheName(){
        // the two names differ by more than the week number, so no week can be
        // read through the wrong policy by an off-by-one in a file name
        String immutable = "sleeperWeekProjection2026w1";
        String live = "sleeperLiveProjection2026w1";
        assertNotEquals(immutable, live);
        assertFalse(live.startsWith(immutable), "a live name must not be a prefix of the immutable one");
        assertFalse(immutable.startsWith(live));
    }

    @Test
    public void aWhatIfScalesOnlyItsMenFromItsWeekOn(){
        Map<String, Double> week = Map.of("jeanty", 14.0, "stevenson", 10.0);
        LeagueWeek.WhatIf what = new LeagueWeek.WhatIf("2026", 9, Map.of("jeanty", 0.5, "benched", 0.0));
        assertEquals(Map.of("jeanty", 7.0, "stevenson", 10.0), LeagueWeek.scaled(week, "2026", 9, what));
        assertEquals(Map.of("jeanty", 7.0, "stevenson", 10.0), LeagueWeek.scaled(week, "2026", 18, what));
        assertEquals(week, LeagueWeek.scaled(week, "2026", 8, what), "a week already played reads as it was");
        assertEquals(week, LeagueWeek.scaled(week, "2025", 12, what), "so does another season");
        assertEquals(week, LeagueWeek.scaled(week, "2026", 12, null));
        assertFalse(LeagueWeek.scaled(week, "2026", 9, what).containsKey("benched"), "a man not projected stays absent, not zero");
    }
}
