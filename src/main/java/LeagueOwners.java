import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

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

    /** Today's rosters and users, through the day's cache. */
    public static Map<String, String> today(AAAConfiguration configuration){
        return byPlayer(configuration.getTodaysRosterWebPageSerious(),
                InOutUtilities.getTodaysWebPage(configuration.getUsersWebURL(),
                        AAAConfiguration.filepathStartUsers + configuration.getLeagueID()));
    }
}
