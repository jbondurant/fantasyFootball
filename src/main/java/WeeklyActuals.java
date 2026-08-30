import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What every player actually scored, week by week.
 *
 * The repo has only ever held season totals, and a season total has already
 * absorbed the weeks a man missed - which is why every model built on one was
 * blind to bench value, and why LiveInsurance reported STARTS = 0% for every
 * candidate. Weekly points are what the starter-sum objective is defined over:
 *
 *     V(R) = SUM over weeks of bestNine(who is up that week, that week's points)
 *
 * They also replace two assumptions with measurement. WHICH weeks a man missed
 * stops being scattered at random, and the correlation between missing games
 * and scoring badly stops being assumed away.
 *
 * Cached forever - a finished week does not change.
 */
public class WeeklyActuals {

    /** The regular season has run 18 weeks since 2021; every season here is 2021+. */
    public static final int WEEKS = 18;

    static String raw(String season, int week){
        return InOutUtilities.getCachedForever(
                "https://api.sleeper.app/v1/stats/nfl/regular/" + season + "/" + week,
                "sleeperWeekActuals" + season + "w" + week);
    }

    /** sleeper id -> half-PPR points that week. Absent means he did not score. */
    public static Map<String, Double> pointsBySleeperID(String season, int week){
        JsonObject rows = JsonParser.parseString(raw(season, week)).getAsJsonObject();
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

    /**
     * Who actually played that week, by gp.
     *
     * Kept separate from scoring on purpose: a man who played and scored two
     * points is available-and-bad, which the lineup optimiser must be allowed
     * to bench, while a man who did not play is unavailable and cannot be
     * started at all. Collapsing them would lose exactly the distinction the
     * objective turns on.
     */
    public static Set<String> playedBySleeperID(String season, int week){
        JsonObject rows = JsonParser.parseString(raw(season, week)).getAsJsonObject();
        Set<String> played = new HashSet<>();
        for(Map.Entry<String, JsonElement> entry : rows.entrySet()){
            if(!entry.getValue().isJsonObject()){
                continue;
            }
            JsonElement games = entry.getValue().getAsJsonObject().get("gp");
            if(games != null && !games.isJsonNull() && games.getAsDouble() > 0){
                played.add(entry.getKey());
            }
        }
        return played;
    }

    /** Every week of a season summed per player - the reconciliation check. */
    public static Map<String, Double> seasonSumBySleeperID(String season){
        Map<String, Double> total = new HashMap<>();
        for(int week = 1; week <= WEEKS; week++){
            for(Map.Entry<String, Double> entry : pointsBySleeperID(season, week).entrySet()){
                total.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }
        return total;
    }
}
