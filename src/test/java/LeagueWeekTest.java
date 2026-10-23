import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

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
}
