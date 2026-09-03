import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/** The ESPN and CBS adapters' pure parts, offline. */
class SourceAdaptersTest {

    @Test
    void espnStatIdsMapToSleeperKeys(){
        JsonObject espn = new JsonObject();
        espn.addProperty("3", 3953.7);    // pass yds
        espn.addProperty("4", 27.5);      // pass td
        espn.addProperty("20", 10.7);     // int
        espn.addProperty("24", 584.5);    // rush yds
        espn.addProperty("25", 11.2);     // rush td
        espn.addProperty("99", 123.0);    // unmapped id stays out

        JsonObject stats = EspnProjections.toSleeperKeys(espn);

        Assertions.assertEquals(3953.7, stats.get("pass_yd").getAsDouble(), 1e-9);
        Assertions.assertEquals(27.5, stats.get("pass_td").getAsDouble(), 1e-9);
        Assertions.assertEquals(10.7, stats.get("pass_int").getAsDouble(), 1e-9);
        Assertions.assertEquals(584.5, stats.get("rush_yd").getAsDouble(), 1e-9);
        Assertions.assertNull(stats.get("99"));
    }

    @Test
    void espnPicksTheSeasonProjectionNotWeeklyOrActuals(){
        JsonObject player = new JsonObject();
        JsonArray stats = new JsonArray();
        stats.add(statSet(1, 1, "2026", 22.0));    // weekly projection
        stats.add(statSet(0, 0, "2025", 380.0));   // last season actuals
        stats.add(statSet(1, 0, "2026", 410.0));   // THE season projection
        player.add("stats", stats);

        JsonObject chosen = EspnProjections.seasonProjection(player, "2026");
        Assertions.assertEquals(410.0, chosen.get("0").getAsDouble(), 1e-9);
    }

    private static JsonObject statSet(int sourceId, int splitTypeId, String season, double marker){
        JsonObject set = new JsonObject();
        set.addProperty("statSourceId", sourceId);
        set.addProperty("statSplitTypeId", splitTypeId);
        set.addProperty("seasonId", season);
        JsonObject values = new JsonObject();
        values.addProperty("0", marker);
        set.add("stats", values);
        return set;
    }

    @Test
    void cbsQuarterbackColumnsLandOnTheRightKeys(){
        // name cell + the 15 numeric columns of the live 2026 layout
        List<String> cells = List.of("<span>Josh Allen</span>", "17", "488", "334", "3717",
                "218.6", "30", "13", "100.2", "125", "611", "4.9", "11", "4", "419.1", "24.7");

        JsonObject stats = CbsProjections.parseRow("QB", cells);

        Assertions.assertEquals(3717, stats.get("pass_yd").getAsDouble(), 1e-9);
        Assertions.assertEquals(30, stats.get("pass_td").getAsDouble(), 1e-9);
        Assertions.assertEquals(13, stats.get("pass_int").getAsDouble(), 1e-9);
        Assertions.assertEquals(611, stats.get("rush_yd").getAsDouble(), 1e-9);
        Assertions.assertEquals(11, stats.get("rush_td").getAsDouble(), 1e-9);
        Assertions.assertEquals(4, stats.get("fum_lost").getAsDouble(), 1e-9);
    }

    @Test
    void cbsRunningBackColumnsLandOnTheRightKeys(){
        List<String> cells = List.of("<span>Bijan Robinson</span>", "17", "302", "1505", "5.0",
                "11", "101", "78", "793", "46.6", "10.2", "4", "2", "391.8", "23.0");

        JsonObject stats = CbsProjections.parseRow("RB", cells);

        Assertions.assertEquals(1505, stats.get("rush_yd").getAsDouble(), 1e-9);
        Assertions.assertEquals(11, stats.get("rush_td").getAsDouble(), 1e-9);
        Assertions.assertEquals(78, stats.get("rec").getAsDouble(), 1e-9);
        Assertions.assertEquals(793, stats.get("rec_yd").getAsDouble(), 1e-9);
        Assertions.assertEquals(4, stats.get("rec_td").getAsDouble(), 1e-9);
        Assertions.assertEquals(2, stats.get("fum_lost").getAsDouble(), 1e-9);
    }

    @Test
    void aRowWithNonNumericCellsIsRejectedNotMisread(){
        Assertions.assertNull(CbsProjections.parseRow("QB",
                List.of("<span>Header</span>", "gp", "att", "cmp")));
    }
}
