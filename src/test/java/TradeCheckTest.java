import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.util.Arrays;
import java.util.List;

/** The workload table's arithmetic: shares of his club's work in a week, pooled over its games. */
public class TradeCheckTest {

    private static String row(String id, String team, String position, String stats){
        return "{\"player_id\":\"" + id + "\",\"team\":\"" + team + "\",\"player\":{\"position\":\"" + position + "\"},\"stats\":{" + stats + "}}";
    }

    private static JsonArray week(String... rows){
        return JsonParser.parseString("[" + String.join(",", rows) + "]").getAsJsonArray();
    }

    @Test
    public void sharesAreOfHisClubsRunningBacksAndOfAllItsTargets(){
        JsonArray rows = week(
                row("him", "LV", "RB", "\"off_snp\":25,\"tm_off_snp\":63,\"rush_att\":2,\"rush_rz_att\":1,\"rec_tgt\":4"),
                row("mate", "LV", "RB", "\"off_snp\":38,\"tm_off_snp\":63,\"rush_att\":14,\"rush_rz_att\":3,\"rec_tgt\":2"),
                row("qb", "LV", "QB", "\"off_snp\":63,\"tm_off_snp\":63,\"rush_att\":6,\"rush_rz_att\":2"),
                row("wr", "LV", "WR", "\"off_snp\":60,\"tm_off_snp\":63,\"rec_tgt\":14"),
                row("other", "NYJ", "RB", "\"off_snp\":50,\"tm_off_snp\":70,\"rush_att\":20,\"rec_tgt\":5"));
        TradeCheck.Work w = TradeCheck.work("him", "LV", rows);
        assertEquals(new TradeCheck.Work(25, 63, 2, 16, 4, 20, 1, 4), w, "a quarterback's runs are not running-back carries");
        assertEquals("40%", TradeCheck.cell(w.snaps(), w.teamSnaps(), false));
        assertEquals("2 13%", TradeCheck.cell(w.carries(), w.rbCarries(), true));
    }

    @Test
    public void aMissedGameIsZeroAndAByeIsNothing(){
        JsonArray played = week(row("mate", "LV", "RB", "\"off_snp\":60,\"tm_off_snp\":60,\"rush_att\":20"));
        assertEquals(new TradeCheck.Work(0, 60, 0, 20, 0, 0, 0, 0), TradeCheck.work("him", "LV", played));
        assertNull(TradeCheck.work("him", "LV", week(row("other", "NYJ", "RB", "\"rush_att\":20"))), "his club had no game");
    }

    @Test
    public void aTradedManIsMeasuredAgainstTheClubHePlayedFor(){
        JsonArray rows = week(
                row("him", "NYJ", "RB", "\"off_snp\":10,\"tm_off_snp\":50,\"rush_att\":5"),
                row("jet", "NYJ", "RB", "\"off_snp\":40,\"tm_off_snp\":50,\"rush_att\":15"),
                row("raider", "LV", "RB", "\"off_snp\":60,\"tm_off_snp\":60,\"rush_att\":30"));
        assertEquals(new TradeCheck.Work(10, 50, 5, 20, 0, 0, 0, 0), TradeCheck.work("him", "LV", rows));
    }

    @Test
    public void pooledIsPerGameOverTheGamesHisClubPlayed(){
        List<TradeCheck.Work> weeks = Arrays.asList(
                new TradeCheck.Work(40, 60, 10, 20, 2, 30, 1, 2),
                null,
                new TradeCheck.Work(20, 60, 4, 20, 4, 30, 0, 2));
        TradeCheck.Work p = TradeCheck.pooled(weeks);
        assertEquals(new TradeCheck.Work(30, 60, 7, 20, 3, 30, 0.5, 2), p);
        assertEquals("7 35%", TradeCheck.cell(p.carries(), p.rbCarries(), true));
        assertEquals("0.5 25%", TradeCheck.cell(p.rzCarries(), p.rbRzCarries(), true));
        assertNull(TradeCheck.pooled(Arrays.asList(null, null)));
    }
}
