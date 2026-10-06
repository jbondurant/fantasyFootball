import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

/** A week's feed carries men, defences and club aggregates; only the first two are scored, only the first counts as a man. */
public class LeagueActualsWeekTest {

    @Test
    public void aClubAggregateRowIsNeverAMan(){
        assertTrue(LeagueActuals.isMan("4034"));
        assertFalse(LeagueActuals.isMan("SEA"), "a defence");
        assertFalse(LeagueActuals.isMan("TEAM_SEA"), "a club's aggregate line");
        assertFalse(LeagueActuals.isMan(null));
    }

    @Test
    public void theWeekParserDropsAggregatesAndKeepsDefences(){
        String body = "{\"4034\":{\"pts_half_ppr\":10.0,\"rec\":4,\"rec_yd\":60},"
                + "\"TEAM_SEA\":{\"pts_half_ppr\":99.0,\"rec\":30,\"rec_yd\":300},"
                + "\"SEA\":{\"pts_half_ppr\":8.0,\"sack\":3},"
                + "\"9999\":{\"gp\":1}}";
        Map<String, Double> points = LeagueActuals.leagueWeeklyPoints(body);
        assertTrue(points.containsKey("4034"), "a man with a line");
        assertTrue(points.containsKey("SEA"), "a defence with a line");
        assertFalse(points.containsKey("TEAM_SEA"), "the aggregate is not a player");
        assertFalse(points.containsKey("9999"), "played without a scoring line: absent, as the lineup filler expects");
    }
}
