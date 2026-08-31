import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What really happened, scored under THIS LEAGUE's rules.
 *
 * The repo has always graded outcomes with Sleeper's precomputed pts_half_ppr
 * field - {@link WeeklyActuals#pointsBySleeperID} and
 * {@link HistoricalActuals#pointsBySleeperID} both hand it back untouched. That
 * field is one fixed scoring, and it is not Justin's. Two rules differ, both
 * measured by {@link ScoringRuleAudit} rather than assumed:
 *
 *   pass_td          the league pays 6, the feed pays 4
 *   fum              the league charges -1 for EVERY fumble; the feed charges
 *                    nothing from 2023 on (it did charge -1 in 2021)
 *   pts_allow_14_20  the league pays a defence 1 for holding a team to 14-20,
 *                    the feed pays 0
 *
 * Projections were already recomputed from component stats
 * ({@link SleeperProjections#scoreStatLine}), so a plan has been chosen on
 * 6-point quarterbacks and graded on 4-point ones. Everything downstream of
 * that - when to take a quarterback, what a defence is worth - inherits the
 * lean.
 *
 * DEFAULTS TO THE OLD BEHAVIOUR ON PURPOSE. Flipping the outcome measure under
 * a running experiment mixes two units in one table, so the corrected path is
 * opt-in:
 *
 *     ./gradlew run -Pmain=PlanBacktest -PleagueScoredActuals=true
 *
 * With the flag absent, every dispatcher below returns byte-identical numbers
 * to the code it replaced. Anything that grades outcomes - a backtest, an
 * outcome pool, a new season being added to either - should call the
 * dispatchers rather than the raw feed, so one switch moves all of it at once.
 */
public class LeagueActuals {

    /** -PleagueScoredActuals=true. Absent means the old pts_half_ppr grading. */
    public static final String FLAG = "leagueScoredActuals";

    public static boolean enabled(){
        return Boolean.getBoolean(FLAG);
    }

    // ------------------------------------------------------------------
    // Dispatchers: what a grader calls. Flag off = the existing feed field.
    // ------------------------------------------------------------------

    /** Skill-position season totals, keyed by sleeper id. */
    public static Map<String, Double> seasonPoints(String season){
        return enabled() ? leagueSeasonPoints(season)
                : HistoricalActuals.pointsBySleeperID(season);
    }

    /** Team-defence season totals, keyed by team abbreviation. */
    public static Map<String, Double> seasonDefencePoints(String season){
        return enabled() ? leagueSeasonDefencePoints(season)
                : HistoricalActuals.defencePointsBySleeperID(season);
    }

    /** One week for everybody - skill players and defences in the same map. */
    public static Map<String, Double> weeklyPoints(String season, int week){
        return enabled() ? leagueWeeklyPoints(season, week)
                : WeeklyActuals.pointsBySleeperID(season, week);
    }

    // ------------------------------------------------------------------
    // The corrected path, always league-scored whatever the flag says. The
    // audit needs both measures in one run, so these stay reachable.
    // ------------------------------------------------------------------

    public static Map<String, Double> leagueSeasonPoints(String season){
        return seasonScored(HistoricalActuals.raw(season), false, leagueScoring());
    }

    public static Map<String, Double> leagueSeasonDefencePoints(String season){
        return seasonScored(defenceRaw(season), true, leagueScoring());
    }

    /**
     * A week, league-scored.
     *
     * The weekly endpoint is one flat map with no position on it, so a defence
     * is recognised by its id being one of the 32 team abbreviations from that
     * season's defence feed. Sniffing the stat line instead would be fragile in
     * both directions: the response also carries TEAM_* rows that aggregate a
     * club's whole offence and would otherwise be scored as if they were a man.
     */
    public static Map<String, Double> leagueWeeklyPoints(String season, int week){
        LeagueScoringSettings scoring = leagueScoring();
        Set<String> defences = defenceIDs(season);
        JsonObject rows = JsonParser.parseString(WeeklyActuals.raw(season, week))
                .getAsJsonObject();
        Map<String, Double> points = new HashMap<>();
        for(Map.Entry<String, JsonElement> entry : rows.entrySet()){
            if(!entry.getValue().isJsonObject()){
                continue;
            }
            JsonObject stats = entry.getValue().getAsJsonObject();
            // Absent pts_half_ppr means Sleeper scored him nothing at all - an
            // inactive, or a man with only snap counts. The old path skipped
            // those rows, and "no entry" is what the lineup filler reads as
            // "did not play", so the two paths must agree on WHICH ids exist.
            JsonElement half = stats.get("pts_half_ppr");
            if(half == null || half.isJsonNull()){
                continue;
            }
            points.put(entry.getKey(),
                    score(stats, defences.contains(entry.getKey()), scoring));
        }
        return points;
    }

    // ------------------------------------------------------------------
    // Scoring one stat line.
    // ------------------------------------------------------------------

    /**
     * Points for one real stat line.
     *
     * Deliberately NOT SleeperProjections.scoreStatLine. That one scores a
     * PROJECTED line, and the projection feed publishes no fumbles beyond
     * fum_lost, no return touchdowns, and for a defence only a stub - which is
     * why it falls back to pts_half_ppr the moment it sees one. Real stat lines
     * carry all of it, so the actuals side needs its own scorer, and pointing
     * the projection path at this one would change every projected total in the
     * repo.
     */
    public static double score(JsonObject stats, boolean defence, LeagueScoringSettings lss){
        return defence ? scoreDefence(stats, lss) : scoreSkill(stats, lss);
    }

    public static double scoreSkill(JsonObject stats, LeagueScoringSettings lss){
        double passing = stat(stats, "pass_yd") * lss.passYard
                + stat(stats, "pass_td") * lss.passTD
                + stat(stats, "pass_int") * lss.interception
                + stat(stats, "pass_2pt") * lss.passTwoPoint;
        double rushing = stat(stats, "rush_yd") * lss.rushYard
                + stat(stats, "rush_td") * lss.rushTD
                + stat(stats, "rush_2pt") * lss.rushTwoPoint;
        double receiving = stat(stats, "rec") * lss.reception
                + stat(stats, "rec_yd") * lss.receivingYard
                + stat(stats, "rec_td") * lss.receivingTD
                + stat(stats, "rec_2pt") * lss.receivingTwoPoint;
        // Both, and they stack: a lost fumble is also a fumble.
        double turnovers = stat(stats, "fum_lost") * lss.fumbleLost
                + stat(stats, "fum") * lss.fumble;
        double loose = stat(stats, "st_td") * lss.specialTeamsTD
                + stat(stats, "st_ff") * lss.specialTeamsForcedFumble
                + stat(stats, "st_fum_rec") * lss.specialTeamsFumbleRecovery
                + stat(stats, "fum_rec_td") * lss.fumbleRecoveryTD;
        return passing + rushing + receiving + turnovers + loose;
    }

    public static double scoreDefence(JsonObject stats, LeagueScoringSettings lss){
        double plays = stat(stats, "sack") * lss.sack
                + stat(stats, "int") * lss.defenceInterception
                + stat(stats, "fum_rec") * lss.fumbleRecovery
                + stat(stats, "ff") * lss.forcedFumble
                + stat(stats, "safe") * lss.safety
                + stat(stats, "blk_kick") * lss.blockedKick;
        double scores = stat(stats, "def_td") * lss.defenceTD
                + stat(stats, "def_st_td") * lss.defenceSpecialTeamsTD
                + stat(stats, "def_st_ff") * lss.defenceSpecialTeamsForcedFumble
                + stat(stats, "def_st_fum_rec") * lss.defenceSpecialTeamsFumbleRecovery;
        // Each band is a COUNT OF GAMES held to that range, not a flag, so a
        // season total sums seventeen of them.
        double allowed = stat(stats, "pts_allow_0") * lss.pointsAllowed0
                + stat(stats, "pts_allow_1_6") * lss.pointsAllowed1to6
                + stat(stats, "pts_allow_7_13") * lss.pointsAllowed7to13
                + stat(stats, "pts_allow_14_20") * lss.pointsAllowed14to20
                + stat(stats, "pts_allow_21_27") * lss.pointsAllowed21to27
                + stat(stats, "pts_allow_28_34") * lss.pointsAllowed28to34
                + stat(stats, "pts_allow_35p") * lss.pointsAllowed35plus;
        return plays + scores + allowed;
    }

    static double stat(JsonObject stats, String key){
        JsonElement element = stats.get(key);
        return element == null || element.isJsonNull() ? 0.0 : element.getAsDouble();
    }

    // ------------------------------------------------------------------
    // Plumbing.
    // ------------------------------------------------------------------

    public static LeagueScoringSettings leagueScoring(){
        return SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
    }

    static String defenceRaw(String season){
        return InOutUtilities.getCachedForever(
                "https://api.sleeper.app/stats/nfl/" + season
                        + "?season_type=regular&position[]=DEF&order_by=pts_half_ppr",
                "sleeperActualsDef" + season);
    }

    private static final Map<String, Set<String>> defenceIDCache = new HashMap<>();

    /** The 32 team-defence ids for a season, e.g. DEN. */
    public static synchronized Set<String> defenceIDs(String season){
        return defenceIDCache.computeIfAbsent(season, s -> {
            Set<String> ids = new HashSet<>();
            for(JsonElement element : JsonParser.parseString(defenceRaw(s)).getAsJsonArray()){
                JsonObject row = element.getAsJsonObject();
                if(row.has("player_id")){
                    ids.add(row.get("player_id").getAsString());
                }
            }
            return ids;
        });
    }

    private static Map<String, Double> seasonScored(String data, boolean defence,
                                                    LeagueScoringSettings scoring){
        Map<String, Double> points = new HashMap<>();
        for(JsonElement element : JsonParser.parseString(data).getAsJsonArray()){
            JsonObject row = element.getAsJsonObject();
            JsonObject stats = row.getAsJsonObject("stats");
            if(stats == null || !row.has("player_id")){
                continue;
            }
            JsonElement half = stats.get("pts_half_ppr");
            if(half == null || half.isJsonNull()){
                continue;
            }
            points.put(row.get("player_id").getAsString(), score(stats, defence, scoring));
        }
        return points;
    }
}
