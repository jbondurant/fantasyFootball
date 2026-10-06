import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * A man on injured reserve is inside `players` AND `reserve`. He is owned - not
 * a free agent - and he cannot start or be the drop. Every roster join read
 * `players` alone until 2026-09-13.
 */
public class LeagueOwnersReserveTest {

    private static final String ROSTERS = "[{\"roster_id\":1,\"owner_id\":\"u1\",\"players\":[\"a\",\"b\",\"c\"],\"reserve\":[\"c\"]},"
            + "{\"roster_id\":2,\"owner_id\":\"u2\",\"players\":[\"d\"],\"reserve\":null}]";
    private static final String USERS = "[{\"user_id\":\"u1\",\"display_name\":\"One\"},{\"user_id\":\"u2\",\"display_name\":\"Two\"}]";

    @Test
    public void aReserveManIsOwnedButNotActive(){
        assertEquals(Set.of("c"), LeagueOwners.reserveOf(ROSTERS), "the reserve list, league-wide; a null reserve is nobody");
        assertEquals("One", LeagueOwners.byPlayer(ROSTERS, USERS).get("c"), "ownership keeps him: he is not a free agent");
    }
}
