import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OtherLeaguesTest {

    static JsonObject league(String positions, int type, int teams, double rec){
        return JsonParser.parseString("{\"status\":\"complete\",\"total_rosters\":" + teams
                + ",\"settings\":{\"type\":" + type + "},\"scoring_settings\":{\"rec\":" + rec
                + "},\"roster_positions\":" + positions + "}").getAsJsonObject();
    }

    static final String LIKE_OURS = "[\"QB\",\"RB\",\"RB\",\"WR\",\"WR\",\"WR\",\"TE\",\"FLEX\",\"K\",\"DEF\",\"BN\",\"BN\"]";

    /** A finished 1-QB redraft or keeper league of 10-14 teams at half or full PPR; not dynasty, superflex, IDP or standard. */
    @Test
    public void onlyLeaguesShapedLikeThisOne(){
        assertTrue(OtherLeagues.likeThisOne(league(LIKE_OURS, 0, 12, 0.5)));
        assertTrue(OtherLeagues.likeThisOne(league(LIKE_OURS, 1, 10, 1.0)), "keeper, full PPR");
        assertFalse(OtherLeagues.likeThisOne(league(LIKE_OURS, 2, 12, 0.5)), "dynasty prices years");
        assertFalse(OtherLeagues.likeThisOne(league(LIKE_OURS, 0, 12, 0)), "standard scoring");
        assertFalse(OtherLeagues.likeThisOne(league(LIKE_OURS, 0, 8, 0.5)), "eight teams");
        assertFalse(OtherLeagues.likeThisOne(league(LIKE_OURS.replace("\"FLEX\"", "\"SUPER_FLEX\""), 0, 12, 0.5)), "superflex");
        assertFalse(OtherLeagues.likeThisOne(league(LIKE_OURS.replace("\"K\"", "\"LB\""), 0, 12, 0.5)), "defensive players");
    }

    /** A name one account holds ten times in a season is a contest series; nine is not. */
    @Test
    public void aNameHeldTenTimesIsAContest(){
        JsonArray list = new JsonArray();
        for(int i = 0; i < 10; i++){
            list.add(JsonParser.parseString("{\"name\":\"Megalabowl\"}"));
        }
        for(int i = 0; i < 9; i++){
            list.add(JsonParser.parseString("{\"name\":\"Office League\"}"));
        }
        assertEquals(Set.of("2021|Megalabowl"), OtherLeagues.contests("2021", list));
    }
}
