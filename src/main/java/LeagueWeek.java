import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The in-season week: which one it is, what is projected, what happened, and
 * who is free. One place, because the four in-season tools all need the same
 * four things and must not disagree about any of them.
 *
 * THE CACHE POLICY IS THE POINT. A finished week is immutable and may be kept
 * forever; the LIVE week is not - its projections move all week as injuries
 * land, and its actuals do not exist until the games are played. The repo had
 * one pattern for both ({@link InOutUtilities#getCachedForever}), and using it
 * on a live week freezes Thursday's number into December. Verified on
 * 2026-09-04, five days before the season: /v1/stats/nfl/regular/2026/1
 * returned "{}". So:
 *
 *   week &lt; current   immutable   getCachedForever, name "...w&lt;week&gt;"
 *   week &gt;= current   moving      getTodaysWebPage,  name "...Live...w&lt;week&gt;"
 *
 * The two never share a cache name, so a week cannot be read through the wrong
 * policy by accident, and a week that turns final simply starts being read
 * through the other one.
 */
public class LeagueWeek {

    private static final String STATE = "https://api.sleeper.app/v1/state/nfl";

    /** Sleeper's own idea of the week - never the wall clock, never a count of Sundays. */
    public static JsonObject state(){
        return JsonParser.parseString(
                InOutUtilities.getTodaysWebPage(STATE, "nflState")).getAsJsonObject();
    }

    /** The NFL week now, or -Pweek if given. */
    public static int week(){
        Integer override = Integer.getInteger("week");
        return override != null ? override : state().get("week").getAsInt();
    }

    public static String season(){
        return state().get("season").getAsString();
    }

    /** A week whose games are all played, so its numbers can never change again. */
    public static boolean finished(int week){
        return week < state().get("week").getAsInt();
    }

    /** Raw week feed, read through the policy that matches whether the week is done. */
    static String feed(String url, String immutableName, String liveName, int week){
        return finished(week)
                ? InOutUtilities.getCachedForever(url, immutableName)
                : InOutUtilities.getTodaysWebPage(url, liveName);
    }

    /**
     * Every man's projection for this week, in the LEAGUE's points - not the
     * feed's pts_half_ppr, which pays 4 for a passing touchdown where this
     * league pays 6.
     *
     * A man ABSENT from the map is not playing: on the 2026 week-1 feed 866 men
     * carry a projection and exactly one of them is 0.0, so a bye or an inactive
     * is an absence rather than a zero. Callers must treat "no key" and "0.0" as
     * different answers.
     */
    public static Map<String, Double> projected(String season, int week){
        String url = "https://api.sleeper.app/v1/projections/nfl/regular/" + season + "/" + week;
        String body = feed(url, "sleeperWeekProjection" + season + "w" + week,
                "sleeperLiveProjection" + season + "w" + week, week);
        LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        Map<String, Double> points = new HashMap<>();
        for(Map.Entry<String, JsonElement> entry : JsonParser.parseString(body).getAsJsonObject().entrySet()){
            if(!entry.getValue().isJsonObject()){
                continue;
            }
            JsonObject stats = entry.getValue().getAsJsonObject();
            // A ROW IS NOT A PROJECTION. Sleeper publishes a row for everyone it
            // knows - 9,419 of them for 2026 week 1 - carrying draft ranks and
            // little else, and league-scoring those gives 8,554 men a tidy 0.0.
            // Absent then means nothing and "not playing" could never be
            // detected, so a bye man would be started with a straight face.
            // Only 866 rows carry an actual points projection; those are the men
            // who are playing.
            if(!stats.has("pts_half_ppr") || stats.get("pts_half_ppr").isJsonNull()){
                continue;
            }
            points.put(entry.getKey(), SleeperProjections.scoreStatLine(stats, scoring));
        }
        return points;
    }

    /**
     * What every man actually scored in a FINISHED week, league-scored.
     * Refuses a week that is not over: there is no such thing as the actuals of
     * a game not played, and the old cache would have kept the empty answer.
     */
    public static Map<String, Double> actual(String season, int week){
        if(!finished(week)){
            throw new IllegalStateException("week " + week + " of " + season
                    + " is not finished (the NFL is on week " + state().get("week").getAsInt()
                    + "), so it has no actuals; asking now would cache an empty week forever");
        }
        return LeagueActuals.weeklyPoints(season, week);
    }

    /** Player ids on somebody's roster right now. */
    public static Set<String> rostered(AAAConfiguration configuration){
        Set<String> owned = new HashSet<>();
        for(JsonElement e : JsonParser.parseString(
                configuration.getTodaysRosterWebPageSerious()).getAsJsonArray()){
            JsonObject roster = e.getAsJsonObject();
            if(!roster.has("players") || roster.get("players").isJsonNull()){
                continue;
            }
            for(JsonElement player : roster.getAsJsonArray("players")){
                owned.add(player.getAsString());
            }
        }
        return owned;
    }

    /** Of these men, the ones nobody owns - the wire. */
    public static Set<String> freeAgents(AAAConfiguration configuration, Set<String> candidates){
        Set<String> owned = rostered(configuration);
        Set<String> free = new HashSet<>(candidates);
        free.removeAll(owned);
        return free;
    }
}
