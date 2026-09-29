import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import PlayerImportAndSetup.Position;

import java.util.List;
import java.util.Map;

/** ESPN's weekly rows parsed, scored in the league's units, and matched; and the three-way blend weight. */
public class EspnWeeklyTest {

    private static final String BODY = "{\"players\":["
            + "{\"player\":{\"fullName\":\"Wide Out\",\"defaultPositionId\":3,\"stats\":["
            + "{\"statSourceId\":1,\"statSplitTypeId\":1,\"scoringPeriodId\":5,\"seasonId\":2023,"
            + "\"stats\":{\"42\":80.0,\"43\":1.0,\"53\":6.0}},"
            + "{\"statSourceId\":1,\"statSplitTypeId\":1,\"scoringPeriodId\":6,\"seasonId\":2023,"
            + "\"stats\":{\"42\":40.0,\"53\":3.0}},"
            + "{\"statSourceId\":0,\"statSplitTypeId\":1,\"scoringPeriodId\":5,\"seasonId\":2023,"
            + "\"stats\":{\"42\":200.0}},"
            + "{\"statSourceId\":1,\"statSplitTypeId\":1,\"scoringPeriodId\":5,\"seasonId\":2022,"
            + "\"stats\":{\"42\":999.0}}]}},"
            + "{\"player\":{\"fullName\":\"Nobody Known\",\"defaultPositionId\":2,\"stats\":["
            + "{\"statSourceId\":1,\"statSplitTypeId\":1,\"scoringPeriodId\":5,\"seasonId\":2023,\"stats\":{\"24\":50.0}}]}},"
            + "{\"player\":{\"fullName\":\"A Kicker\",\"defaultPositionId\":5,\"stats\":[]}}"
            + "]}";

    @Test
    public void onlyWeeklyProjectionsOfTheSeasonAreReadAndScoredInLeagueUnits(){
        LeagueScoringSettings half = LeagueScoringSettings.halfPprFeed();
        EspnWeekly.Season s = EspnWeekly.weeklyFrom(BODY, "2023", half,
                (name, position) -> name.equals("Wide Out") ? "wr1" : null);
        assertEquals(2, s.men(), "the kicker is not one of the four positions");
        assertEquals(1, s.matched(), "one of the two skill men has a Sleeper id");
        // 80 yards x 0.1 + 1 TD x 6 + 6 catches x 0.5 = 17.0 under half-PPR receiving
        assertEquals(8.0 + half.receivingTD + 6 * half.reception, s.byWeek().get(5).get("wr1"), 1e-9);
        assertEquals(4.0 + 3 * half.reception, s.byWeek().get(6).get("wr1"), 1e-9);
        assertEquals(2, s.byWeek().size(), "an actual (source 0) and another season's projection are not read");
    }

    @Test
    public void theThreeWayWeightFindsTheSourceTheOutcomeTracks(){
        // rows {posterior, sleeper, espn, y}
        List<double[]> tracksEspn = List.of(new double[]{10, 6, 9, 9}, new double[]{8, 12, 7, 7}, new double[]{15, 9, 12, 12});
        assertArrayEquals(new double[]{0, 1}, RosModel.blendWeights3(tracksEspn), 1e-9);
        List<double[]> tracksPosterior = List.of(new double[]{10, 6, 9, 10}, new double[]{8, 12, 7, 8}, new double[]{15, 9, 12, 15});
        assertArrayEquals(new double[]{1, 0}, RosModel.blendWeights3(tracksPosterior), 1e-9);
        List<double[]> tracksSleeper = List.of(new double[]{10, 6, 9, 6}, new double[]{8, 12, 7, 12}, new double[]{15, 9, 12, 9});
        assertArrayEquals(new double[]{0, 0}, RosModel.blendWeights3(tracksSleeper), 1e-9, "all weight left on Sleeper");
        assertEquals(0.25 * 10 + 0.25 * 9 + 0.5 * 6, RosModel.blend3(0.25, 0.25, 10, 6, 9), 1e-9);
    }
}
