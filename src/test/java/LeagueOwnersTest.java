import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

/** The owner column reads rosters, not draft picks, so waiver moves count. */
public class LeagueOwnersTest {

    @Test
    public void rostersMapEveryHeldManToHisManager(){
        String rosters = "[{\"owner_id\":\"u1\",\"players\":[\"5850\",\"SEA\"]},"
                + "{\"owner_id\":\"u2\",\"players\":[\"9997\"]},"
                + "{\"owner_id\":null,\"players\":[\"1\"]}]";
        String users = "[{\"user_id\":\"u1\",\"display_name\":\"JakeSK\"},{\"user_id\":\"u2\",\"display_name\":\"BHier\"}]";
        Map<String, String> owner = LeagueOwners.byPlayer(rosters, users);
        assertEquals("JakeSK", owner.get("5850"));
        assertEquals("JakeSK", owner.get("SEA"), "a defence is held like anyone else");
        assertEquals("BHier", owner.get("9997"));
        assertNull(owner.get("1"), "an unowned roster holds nobody");
        assertNull(owner.get("404"), "a free agent has no owner");
    }
}
