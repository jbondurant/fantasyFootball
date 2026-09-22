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

    /** A week of THIS season whose games are all played, so its numbers can never change again. */
    public static boolean finished(int week){
        return finished(season(), week);
    }

    /**
     * The same question for any season: a week of a season already gone is
     * over whatever its number, and no week of a season not yet begun is.
     *
     * The week-number-only form read week 5 of 2021 as unfinished all through
     * a September, because the NFL was on week 3 - so a past season's frozen
     * numbers were fetched again every day under a live cache name. A season
     * is half the question and was missing from it.
     */
    public static boolean finished(String season, int week){
        JsonObject state = state();
        int sameSeason = season.compareTo(state.get("season").getAsString());
        if(sameSeason != 0){
            return sameSeason < 0;
        }
        return week < state.get("week").getAsInt();
    }

    /** Raw week feed, read through the policy that matches whether the week is done. */
    static String feed(String url, String immutableName, String liveName, String season, int week){
        return finished(season, week)
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
        return projectedFrom(feed(url, "sleeperWeekProjection" + season + "w" + week,
                "sleeperLiveProjection" + season + "w" + week, season, week));
    }

    /** The same league scoring over any read of a week's projection feed - a dated cache file, or today's. */
    static Map<String, Double> projectedFrom(String body){
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
        if(!finished(season, week)){
            throw new IllegalStateException("week " + week + " of " + season
                    + " is not finished (the NFL is on week " + state().get("week").getAsInt()
                    + "), so it has no actuals; asking now would cache an empty week forever");
        }
        return LeagueActuals.weeklyPoints(season, week);
    }

    /**
     * What every man has scored in a week so far, league-scored, whether or not
     * the week is over. A finished week is {@link #actual}; the live week is the
     * day's read of the stats feed - a PARTIAL week, absent men unplayed rather
     * than scoreless, which the caller must say in its header. Same cache
     * policy as every other feed here: the live name is dated, the finished
     * name is the one {@link WeeklyActuals} keeps forever.
     */
    public static Map<String, Double> actualSoFar(String season, int week){
        if(finished(season, week)){
            return actual(season, week);
        }
        return LeagueActuals.leagueWeeklyPoints(actualsBody(season, week));
    }

    /**
     * The league's matchups for a week - every roster, its started ten and the
     * points the league recorded - through the same policy: frozen once the
     * week is over (the name {@code LineupPromotion} keeps), the day's read
     * while it is live. Reading a live week through the forever cache would
     * freeze Sunday-afternoon scores into the record, which is the trap this
     * class exists to close.
     */
    public static String matchups(String leagueID, int week){
        String url = "https://api.sleeper.app/v1/league/" + leagueID + "/matchups/" + week;
        return feed(url, "sleeperMatchups" + leagueID + "w" + week,
                "sleeperLiveMatchups" + leagueID + "w" + week, season(), week);
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

    /**
     * The raw stats feed of a week under the same policy: frozen once the week
     * is over (the name {@link WeeklyActuals} keeps), the day's read while it
     * is live. For readers that need the stat line itself - usage, snaps -
     * and not only the score.
     */
    public static String actualsBody(String season, int week){
        String url = "https://api.sleeper.app/v1/stats/nfl/regular/" + season + "/" + week;
        return feed(url, "sleeperWeekActuals" + season + "w" + week,
                "sleeperLiveActuals" + season + "w" + week, season, week);
    }

    /**
     * A week's transactions under the policy: frozen once the week is over
     * (an empty week is a real answer there - TRAPS #85), the day's read while
     * it is live, so a claim placed this morning is in the log this afternoon.
     * {@link LeagueTransactions#transactionsRaw} routes the configured league
     * here; past seasons stay on the forever cache, every week of them being over.
     */
    public static String transactions(String leagueID, int week){
        String url = "https://api.sleeper.app/v1/league/" + leagueID + "/transactions/" + week;
        return finished(week)
                ? InOutUtilities.getCachedForeverAllowingEmpty(url, "sleeperTxns" + leagueID + "w" + week)
                : InOutUtilities.getTodaysWebPage(url, "sleeperLiveTxns" + leagueID + "w" + week);
    }
}
