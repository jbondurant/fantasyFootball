import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * ACTUAL season fantasy points from Sleeper's stats API - the outcome side
 * of the reality program. The repo has projected for days; this is the first
 * time it ingests what actually happened. Cached forever per season (the
 * past does not change).
 */
public class HistoricalActuals {

    /** sleeper id -> actual half-PPR points for the season. */
    public static Map<String, Double> pointsBySleeperID(String season){
        String url = "https://api.sleeper.app/stats/nfl/" + season
                + "?season_type=regular&position[]=QB&position[]=RB&position[]=TE"
                + "&position[]=WR&order_by=pts_half_ppr";
        String data = InOutUtilities.getCachedForever(url, "sleeperActualsFinal" + season);
        JsonArray rows = JsonParser.parseString(data).getAsJsonArray();
        Map<String, Double> points = new HashMap<>();
        for(JsonElement element : rows){
            JsonObject row = element.getAsJsonObject();
            JsonObject stats = row.getAsJsonObject("stats");
            if(stats == null || !row.has("player_id")){
                continue;
            }
            JsonElement half = stats.get("pts_half_ppr");
            if(half != null && !half.isJsonNull()){
                points.put(row.get("player_id").getAsString(), half.getAsDouble());
            }
        }
        return points;
    }
}
