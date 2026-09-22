import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Who in the league holds each man right now - from the rosters endpoint, so
 * waiver moves count, not only the draft.
 */
public class LeagueOwners {

    /** player id -> manager display name, from rosters JSON and users JSON. */
    static Map<String, String> byPlayer(String rostersJson, String usersJson){
        Map<String, String> nameByUser = new HashMap<>();
        for(JsonElement e : JsonParser.parseString(usersJson).getAsJsonArray()){
            JsonObject u = e.getAsJsonObject();
            if(u.has("user_id") && u.has("display_name") && !u.get("display_name").isJsonNull()){
                nameByUser.put(u.get("user_id").getAsString(), u.get("display_name").getAsString());
            }
        }
        Map<String, String> owner = new HashMap<>();
        for(JsonElement e : JsonParser.parseString(rostersJson).getAsJsonArray()){
            JsonObject roster = e.getAsJsonObject();
            String by = roster.has("owner_id") && !roster.get("owner_id").isJsonNull()
                    ? roster.get("owner_id").getAsString() : null;
            if(by == null || !roster.has("players") || roster.get("players").isJsonNull()){
                continue;
            }
            for(JsonElement p : roster.getAsJsonArray("players")){
                owner.put(p.getAsString(), nameByUser.getOrDefault(by, by));
            }
        }
        return owner;
    }

    /**
     * The men on injured reserve, league-wide.
     *
     * Sleeper lists a reserve man in the roster's `players` array AND in its
     * `reserve` array, so a join on `players` alone counts him as active: he was
     * in every lineup, every swap search and every roster valuation until
     * 2026-09-13, on four rivals' rosters. Policy, stated once: the lineup and the
     * wire's droppable roster exclude him (he cannot start and does not hold an
     * active spot); ownership keeps him (he is not a free agent); the trade board
     * keeps him (he can be traded, and is valued on his projection).
     */
    static Set<String> reserveOf(String rostersJson){
        Set<String> reserve = new HashSet<>();
        for(JsonElement e : JsonParser.parseString(rostersJson).getAsJsonArray()){
            JsonObject roster = e.getAsJsonObject();
            if(roster.has("reserve") && roster.get("reserve").isJsonArray()){
                for(JsonElement p : roster.getAsJsonArray("reserve")){
                    if(!p.isJsonNull()){
                        reserve.add(p.getAsString());
                    }
                }
            }
        }
        return reserve;
    }

    public static Set<String> reserve(AAAConfiguration configuration){
        return reserveOf(configuration.getTodaysRosterWebPageSerious());
    }

    /** Today's rosters and users, through the day's cache. */
    public static Map<String, String> today(AAAConfiguration configuration){
        return byPlayer(configuration.getTodaysRosterWebPageSerious(),
                InOutUtilities.getTodaysWebPage(configuration.getUsersWebURL(),
                        AAAConfiguration.filepathStartUsers + configuration.getLeagueID()));
    }
}
