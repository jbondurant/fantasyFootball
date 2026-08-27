import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    /**
     * Sleeper player id -> that season's projections scored under the
     * LEAGUE's settings (6-pt passing TDs) - the numbers the managers were
     * actually looking at in their draft room, unlike the raw 4-pt feed.
     * Assumes the league's scoring has been stable across the chain.
     */
    public static Map<String, Double> leaguePointsBySleeperID(AAAConfiguration configuration,
                                                              String season){
        LeagueScoringSettings scoring =
                SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        Map<String, Double> out = new HashMap<>();
        for(JsonElement row : forSeason(configuration, season)){
            JsonObject object = row.getAsJsonObject();
            JsonElement stats = object.get("stats");
            JsonElement id = object.get("player_id");
            if(stats == null || !stats.isJsonObject() || id == null || id.isJsonNull()){
                continue;
            }
            out.put(id.getAsString(),
                    SleeperProjections.scoreStatLine(stats.getAsJsonObject(), scoring));
        }
        return out;
    }

    /** Players within their first maxYears seasons, by rookie_year metadata. */
    public static Set<String> youngForSeason(AAAConfiguration configuration, String season,
                                             int maxYears){
        Set<String> out = new HashSet<>();
        int year = Integer.parseInt(season);
        for(JsonElement row : forSeason(configuration, season)){
            JsonObject object = row.getAsJsonObject();
            JsonElement playerElement = object.get("player");
            if(playerElement == null || !playerElement.isJsonObject()){
                continue;
            }
            JsonElement metadataElement = playerElement.getAsJsonObject().get("metadata");
            if(metadataElement == null || !metadataElement.isJsonObject()){
                continue;
            }
            JsonElement rookieYear = metadataElement.getAsJsonObject().get("rookie_year");
            if(rookieYear == null || rookieYear.isJsonNull()){
                continue;
            }
            try {
                if(year - Integer.parseInt(rookieYear.getAsString()) <= maxYears){
                    out.add(object.get("player_id").getAsString());
                }
            } catch (NumberFormatException ignored){
            }
        }
        return out;
    }

    /** Sleeper player id -> NFL team that season, from the frozen snapshot. */
    public static Map<String, String> teamBySleeperID(AAAConfiguration configuration, String season){
        Map<String, String> out = new HashMap<>();
        for(JsonElement row : forSeason(configuration, season)){
            JsonObject object = row.getAsJsonObject();
            JsonElement team = object.get("team");
            JsonElement id = object.get("player_id");
            if(id != null && !id.isJsonNull() && team != null && !team.isJsonNull()){
                out.put(id.getAsString(), team.getAsString());
            }
        }
        return out;
    }

    /** Players who were rookies that season, by Sleeper's rookie_year metadata. */
    public static Set<String> rookiesForSeason(AAAConfiguration configuration, String season){
        Set<String> out = new HashSet<>();
        for(JsonElement row : forSeason(configuration, season)){
            JsonObject object = row.getAsJsonObject();
            JsonElement playerElement = object.get("player");
            if(playerElement == null || !playerElement.isJsonObject()){
                continue;
            }
            JsonElement metadataElement = playerElement.getAsJsonObject().get("metadata");
            if(metadataElement == null || !metadataElement.isJsonObject()){
                continue;
            }
            JsonElement rookieYear = metadataElement.getAsJsonObject().get("rookie_year");
            if(rookieYear != null && !rookieYear.isJsonNull()
                    && season.equals(rookieYear.getAsString())){
                out.add(object.get("player_id").getAsString());
            }
        }
        return out;
    }

    /** Sleeper player id -> that season's preseason half-PPR ADP. */
    public static Map<String, Double> adpBySleeperID(AAAConfiguration configuration, String season){
        return statBySleeperID(configuration, season, "adp_half_ppr");
    }

    /** Sleeper player id -> that season's raw projected points (4-pt passing TDs). */
    public static Map<String, Double> rawPointsBySleeperID(AAAConfiguration configuration, String season){
        return statBySleeperID(configuration, season, "pts_half_ppr");
    }

    /** Projected passing TDs - the 6-pt display-discrepancy diagnostic's input. */
    public static Map<String, Double> passTdBySleeperID(AAAConfiguration configuration,
                                                        String season){
        return statBySleeperID(configuration, season, "pass_td");
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
