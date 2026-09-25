import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

    /**
     * The regular season is under way, by Sleeper's state. This is when the
     * other shops change what their season addresses serve (AdpSnapshot's
     * archive names them for it); before the first game a rest-of-season
     * number and a season number are the same number.
     */
    public static boolean inSeason(){
        JsonElement type = state().get("season_type");
        return type != null && !type.isJsonNull() && "regular".equals(type.getAsString());
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
     * A week's transactions in the season being played, through the DAY's cache
     * whatever week it is. {@link LeagueTransactions#transactionsRaw} routes only
     * the configured league here; a past season stays on the forever cache,
     * every run of it having settled long ago.
     *
     * A WEEK BEING OVER DOES NOT SETTLE ITS TRANSACTIONS. Sleeper files a claim
     * under the leg it was CREATED in and clears it days later: eight bids on
     * Emanuel Wilson were filed under week 2 and settled in the 09-23 run, a day
     * after the week counter moved to 3. Freezing week 2 the moment it was
     * "finished" kept the list as it stood before that run - the winning claims
     * missing, the contest reading as one bid, and Sleeper's own FAAB counters
     * disagreeing with the log for four managers (TRAPS #143). The season's own
     * weeks are small and few; re-reading them daily costs nothing and is the
     * only way the log is true.
     */
    public static String transactions(String leagueID, int week){
        String url = "https://api.sleeper.app/v1/league/" + leagueID + "/transactions/" + week;
        return InOutUtilities.getTodaysWebPage(url, "sleeperLiveTxns" + leagueID + "w" + week);
    }

    /**
     * How many of the season's weeks Sleeper projects each man to play: a week
     * with no row for him is a bye, an injury or a benching. Sleeper's season
     * number is, to within its rounding, the sum of these weekly numbers
     * (WeeklyFeedAudit), so this count is the availability its season total
     * already carries - the unit a per-game rate has to be multiplied by to be
     * compared with it.
     */
    public static Map<String, Integer> projectedWeeks(String season){
        List<Map<String, Double>> weeks = new ArrayList<>();
        for(int w = 1; w <= WeeklyActuals.WEEKS; w++){
            weeks.add(projected(season, w));
        }
        return countWeeks(weeks);
    }

    /**
     * Each man's weekly projections of a season summed over the weeks that
     * {@code include} admits, league-scored: id -> {points, weeks carrying a
     * projection}. The week count travels with the sum because a sum over
     * weekly feeds not yet published is small for want of weeks, not of
     * points, and a caller must be able to tell.
     */
    public static Map<String, double[]> summed(String season, java.util.function.IntPredicate include){
        List<Map<String, Double>> weeks = new ArrayList<>();
        for(int w = 1; w <= WeeklyActuals.WEEKS; w++){
            if(include.test(w)){
                weeks.add(projected(season, w));
            }
        }
        return sumWeeks(weeks);
    }

    /** id -> {sum of his values over the maps, maps he appears in}. */
    static Map<String, double[]> sumWeeks(List<Map<String, Double>> weeks){
        Map<String, double[]> out = new HashMap<>();
        for(Map<String, Double> week : weeks){
            for(Map.Entry<String, Double> e : week.entrySet()){
                double[] s = out.computeIfAbsent(e.getKey(), k -> new double[2]);
                s[0] += e.getValue();
                s[1]++;
            }
        }
        return out;
    }

    /**
     * Every skill man's box-score row of a week WITH HIS TEAM that week - the
     * /stats array form, which carries team and opponent where the /v1 map
     * {@link #actualsBody} reads does not. A teammate's injury can only be
     * read through the team (FaabDemand's next man up, via {@link NextManUp}),
     * and a man traded in October is on a different one before and after, so
     * the team is taken from the week, never from today's player database.
     * Checked 2026-09-25: 618 rows for 2025 week 5, every one with a team.
     */
    public static String teamStatsBody(String season, int week){
        String url = "https://api.sleeper.app/stats/nfl/" + season + "/" + week
                + "?season_type=regular&position[]=QB&position[]=RB&position[]=WR&position[]=TE";
        return feed(url, "sleeperTeamStats" + season + "w" + week,
                "sleeperLiveTeamStats" + season + "w" + week, season, week);
    }

    /** The number of maps each key appears in. */
    static Map<String, Integer> countWeeks(List<Map<String, Double>> weeks){
        Map<String, Integer> out = new HashMap<>();
        for(Map<String, Double> week : weeks){
            for(String id : week.keySet()){
                out.merge(id, 1, Integer::sum);
            }
        }
        return out;
    }
}
