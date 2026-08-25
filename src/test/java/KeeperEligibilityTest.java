import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * A keeper can be held three consecutive years. Counting which year someone is
 * on decides whether they can be kept again, so the count has to be right.
 */
class KeeperEligibilityTest {

    private static JsonArray draft(String playerID, int round, Boolean keeper){
        String flag = keeper == null ? "null" : keeper.toString();
        return JsonParser.parseString("[{\"player_id\":\"" + playerID + "\",\"round\":" + round
                + ",\"pick_no\":" + (round * 12 - 5) + ",\"is_keeper\":" + flag + "}]").getAsJsonArray();
    }

    @Test
    void aPlayerDraftedLastYearIsOnHisFirstKeeperYear(){
        List<JsonArray> history = List.of(draft("1", 8, null), draft("1", 9, null));
        Assertions.assertEquals(0, KeeperEligibility.consecutiveYearsBefore("1", history));
    }

    @Test
    void consecutiveKeeperSeasonsAreCounted(){
        // kept last season and the one before, so this would be his third
        List<JsonArray> history = List.of(draft("1", 6, true), draft("1", 7, true), draft("1", 8, null));
        Assertions.assertEquals(2, KeeperEligibility.consecutiveYearsBefore("1", history));
    }

    @Test
    void theRunStopsAtTheSeasonHeWasDrafted(){
        // kept last year, but drafted the year before that: the run is one
        List<JsonArray> history = List.of(draft("1", 7, true), draft("1", 8, null), draft("1", 9, true));
        Assertions.assertEquals(1, KeeperEligibility.consecutiveYearsBefore("1", history),
                "an older keeper season across a gap does not extend the current run");
    }

    @Test
    void aPlayerAbsentFromLastSeasonHasNoRun(){
        List<JsonArray> history = List.of(draft("999", 4, true));
        Assertions.assertEquals(0, KeeperEligibility.consecutiveYearsBefore("1", history));
    }

    @Test
    void noHistoryMeansNoRun(){
        Assertions.assertEquals(0, KeeperEligibility.consecutiveYearsBefore("1", List.of()));
    }
}
