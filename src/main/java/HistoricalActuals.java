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

    /**
     * Team defences, which the skill-position call deliberately excludes.
     *
     * A SEPARATE cache key on purpose. getCachedForever names its file after
     * the prefix, not the URL, so widening the existing request's position list
     * would have kept serving the old skill-only file forever and looked like
     * defences simply did not exist.
     */
    public static Map<String, Double> defencePointsBySleeperID(String season){
        String url = "https://api.sleeper.app/stats/nfl/" + season
                + "?season_type=regular&position[]=DEF&order_by=pts_half_ppr";
        String data = InOutUtilities.getCachedForever(url, "sleeperActualsDef" + season);
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

    /**
     * Actual season points scored under the LEAGUE's settings, skill and
     * defence together.
     *
     * pointsBySleeperID returns Sleeper's pts_half_ppr, which is a FOUR-point
     * passing touchdown. This league pays six. Comparing a league-scored
     * projection (HistoricalProjections.leaguePointsBySleeperID) against a
     * pts_half_ppr actual therefore understates every quarterback's season by
     * roughly sixty points and makes quarterback projections look worse than
     * they are - the same class of units error that once printed 0.0 for
     * defences. Anything joining projection to outcome wants this method.
     */
    public static Map<String, Double> leaguePointsBySleeperID(String season){
        LeagueScoringSettings scoring =
                SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        Map<String, Double> points = new HashMap<>();
        for(String data : new String[]{raw(season), rawDefence(season)}){
            for(JsonElement element : JsonParser.parseString(data).getAsJsonArray()){
                JsonObject row = element.getAsJsonObject();
                JsonObject stats = row.getAsJsonObject("stats");
                if(stats == null || !row.has("player_id")){
                    continue;
                }
                points.put(row.get("player_id").getAsString(),
                        SleeperProjections.scoreStatLine(stats, scoring));
            }
        }
        return points;
    }

    static String rawDefence(String season){
        String url = "https://api.sleeper.app/stats/nfl/" + season
                + "?season_type=regular&position[]=DEF&order_by=pts_half_ppr";
        return InOutUtilities.getCachedForever(url, "sleeperActualsDef" + season);
    }

    /** sleeper id -> games actually played that season. */
    public static Map<String, Integer> gamesPlayedBySleeperID(String season){
        JsonArray rows = JsonParser.parseString(raw(season)).getAsJsonArray();
        Map<String, Integer> played = new HashMap<>();
        for(JsonElement element : rows){
            JsonObject row = element.getAsJsonObject();
            JsonObject stats = row.getAsJsonObject("stats");
            if(stats == null || !row.has("player_id")){
                continue;
            }
            JsonElement games = stats.get("gp");
            if(games != null && !games.isJsonNull()){
                played.put(row.get("player_id").getAsString(), games.getAsInt());
            }
        }
        return played;
    }

    static String raw(String season){
        String url = "https://api.sleeper.app/stats/nfl/" + season
                + "?season_type=regular&position[]=QB&position[]=RB&position[]=TE"
                + "&position[]=WR&order_by=pts_half_ppr";
        return InOutUtilities.getCachedForever(url, "sleeperActualsFinal" + season);
    }

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
