import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * Sleeper's preseason projections for a COMPLETED season - the ADP everyone
 * drafted against that year, and the raw projected points.
 *
 * Past seasons are immutable, so they are cached without a date suffix and
 * fetched exactly once. The current season must go through SleeperProjections,
 * which re-fetches daily; asking this class for it is a bug and throws.
 */
public class HistoricalProjections {

    public static JsonArray forSeason(AAAConfiguration configuration, String season){
        int asked = Integer.parseInt(season);
        int current = Integer.parseInt(configuration.getSeason());
        if(asked >= current){
            throw new IllegalArgumentException("season " + season + " is not history; "
                    + "use SleeperProjections for the current season");
        }
        String url = "https://api.sleeper.app/projections/nfl/" + season
                + "?season_type=regular&position[]=DEF&position[]=QB&position[]=RB&position[]=TE&position[]=WR"
                + "&order_by=pts_half_ppr";
        String data = InOutUtilities.getCachedForever(url, "sleeperProjectionsFinal" + season);
        return JsonParser.parseString(data).getAsJsonArray();
    }

    /** Sleeper player id -> that season's preseason half-PPR ADP. */
    public static Map<String, Double> adpBySleeperID(AAAConfiguration configuration, String season){
        return statBySleeperID(configuration, season, "adp_half_ppr");
    }

    /** Sleeper player id -> that season's raw projected points (4-pt passing TDs). */
    public static Map<String, Double> rawPointsBySleeperID(AAAConfiguration configuration, String season){
        return statBySleeperID(configuration, season, "pts_half_ppr");
    }

    private static Map<String, Double> statBySleeperID(AAAConfiguration configuration,
                                                       String season, String stat){
        Map<String, Double> out = new HashMap<>();
        for(JsonElement row : forSeason(configuration, season)){
            JsonObject object = row.getAsJsonObject();
            JsonObject stats = object.getAsJsonObject("stats");
            if(stats == null){
                continue;
            }
            JsonElement value = stats.get(stat);
            if(value != null && !value.isJsonNull()){
                out.put(object.get("player_id").getAsString(), value.getAsDouble());
            }
        }
        return out;
    }

}
