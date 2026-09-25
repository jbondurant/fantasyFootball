import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * A defence's season number is the sum of its weekly projections, whose rows
 * carry every category the league pays for; the season feed's DEF row carries
 * four of eight (TRAPS #138). Only a season's worth of weeks makes a season.
 */
public class DefenceWeeklyPriceTest {

    @Test
    public void weeklyFeedsAreSummedWithTheWeeksTheyCover(){
        Map<String, double[]> summed = LeagueWeek.sumWeeks(List.of(
                Map.of("SEA", 7.0, "4034", 15.0), Map.of("SEA", 8.5), Map.of("4034", 12.0)));
        assertArrayEquals(new double[]{15.5, 2}, summed.get("SEA"), 1e-9);
        assertArrayEquals(new double[]{27.0, 2}, summed.get("4034"), 1e-9);
    }

    @Test
    public void onlyADefenceWithASeasonOfWeeksIsRepriced(){
        Map<String, Double> priced = SleeperProjections.defencesFromWeeks(Map.of(
                "SEA", new double[]{131.2, 17},
                "NE", new double[]{120.0, 16},
                "CHI", new double[]{22.0, 3},           // weekly feeds not all published: keeps the stub
                "4034", new double[]{250.0, 17},        // a man is priced from his stat line, never here
                "TEAM_SEA", new double[]{90.0, 17}));   // a team line is not a defence
        assertEquals(Map.of("SEA", 131.2, "NE", 120.0), priced);
        assertEquals(16, SleeperProjections.DEFENCE_MIN_WEEKS, "seventeen games in eighteen weeks, one missing allowed");
    }
}
