import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * Sleeper's WEEK-level projections - the only historical projection this repo
 * can honestly train on.
 *
 * The season endpoint (/v1/projections/nfl/regular/<year>) serves rest-of-
 * season values frozen at season end: on 2021 only 27% of players match the
 * real dated August snapshot, and one reads 323.66 in August against -5.28
 * today. It knows who got hurt.
 *
 * A WEEK-1 projection cannot. It had to be made before week 1 was played.
 * Tested on 2021 it correlates 0.914 with the genuine August snapshot and
 * 0.703 with the actual week-1 outcome - the shape of a forecast, not of a
 * result.
 *
 * Use it as a RANKING signal only. It is one game against one defence, so
 * multiplying by 17 would carry that single matchup into a season estimate.
 */
public class WeeklyProjections {

    /** sleeper id -> projected half-PPR points for that week. */
    public static Map<String, Double> pointsBySleeperID(String season, int week){
        String url = "https://api.sleeper.app/v1/projections/nfl/regular/"
                + season + "/" + week;
        String data = InOutUtilities.getCachedForever(url,
                "sleeperWeekProjection" + season + "w" + week);
        JsonObject rows = JsonParser.parseString(data).getAsJsonObject();
        Map<String, Double> points = new HashMap<>();
        for(Map.Entry<String, JsonElement> entry : rows.entrySet()){
            if(!entry.getValue().isJsonObject()){
                continue;
            }
            JsonElement half = entry.getValue().getAsJsonObject().get("pts_half_ppr");
            if(half != null && !half.isJsonNull()){
                points.put(entry.getKey(), half.getAsDouble());
            }
        }
        return points;
    }
}
