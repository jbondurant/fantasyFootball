import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** The claim reader on Sleeper's transaction shape, and how claims group into contests. */
public class WaiverLogTest {

    private static final String BODY = "["
            + "{\"type\":\"waiver\",\"status\":\"complete\",\"adds\":{\"4034\":1},\"drops\":{\"999\":1},\"roster_ids\":[1],"
            + "\"settings\":{\"waiver_bid\":7,\"seq\":3},\"created\":100,\"status_updated\":500,\"metadata\":{\"notes\":\"Your waiver claim was processed successfully!\"}},"
            + "{\"type\":\"waiver\",\"status\":\"failed\",\"adds\":{\"4034\":1},\"drops\":null,\"roster_ids\":[2],"
            + "\"settings\":{\"waiver_bid\":2,\"seq\":9},\"created\":120,\"status_updated\":500,\"metadata\":{\"notes\":\"This player was claimed by another owner.\"}},"
            + "{\"type\":\"waiver\",\"status\":\"failed\",\"adds\":{\"4034\":1},\"drops\":null,\"roster_ids\":[3],"
            + "\"settings\":{\"waiver_bid\":9,\"seq\":1},\"created\":130,\"status_updated\":500,\"metadata\":{\"notes\":\"Unfortunately, your roster will have too many players after this transaction.\"}},"
            + "{\"type\":\"free_agent\",\"status\":\"complete\",\"adds\":{\"5\":1},\"drops\":null,\"roster_ids\":[4],\"settings\":null,\"created\":140,\"status_updated\":140},"
            + "{\"type\":\"trade\",\"status\":\"complete\",\"adds\":{\"6\":1,\"7\":2},\"drops\":{\"6\":2,\"7\":1},\"roster_ids\":[1,2],\"settings\":null,\"created\":150,\"status_updated\":150}"
            + "]";

    @Test
    public void onlyWaiverClaimsAreClaimsAndEveryFieldIsRead(){
        List<WaiverLog.Claim> claims = WaiverLog.claims(BODY, 2);
        assertEquals(3, claims.size(), "a free-agent add and a trade are not claims");
        WaiverLog.Claim won = claims.get(0);
        assertEquals("4034", won.playerID());
        assertEquals(1, won.rosterID());
        assertEquals(7, won.bid());
        assertTrue(won.won());
        assertEquals(500, won.cleared());
        assertEquals(List.of("999"), won.dropped());
        assertEquals(2, won.week());
        assertFalse(WaiverLog.diedForRoom(won));
        assertFalse(WaiverLog.diedForRoom(claims.get(1)), "outbid, not out of room");
        assertTrue(WaiverLog.diedForRoom(claims.get(2)), "Sleeper's own note says the roster was full");
    }

    @Test
    public void aContestIsOneManAtOneClearingMomentWithBidsHighestFirst(){
        Map<String, List<WaiverLog.Claim>> contests = WaiverLog.contests(WaiverLog.claims(BODY, 2));
        assertEquals(1, contests.size());
        List<WaiverLog.Claim> contest = contests.values().iterator().next();
        assertEquals(3, contest.size());
        assertEquals(9, contest.get(0).bid(), "the biggest bid first, even though it died for room");
        assertEquals(7, contest.get(1).bid());
        assertTrue(contest.get(1).won(), "the winner is the highest bid that had room");
        assertEquals(2, contest.get(2).bid());
    }
}
