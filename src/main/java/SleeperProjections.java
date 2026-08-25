import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Sleeper's season projections. This is the only remaining feed that publishes
 * projected *stat lines*, so it backs both the raw points map here and the
 * league-scored projections in {@link StatLineProjections}.
 */
public class SleeperProjections {

    public static String filepathStart = "sleeperProjections";

    /**
     * The season comes off the configured league, not the wall clock. The old
     * "if it is January or February, subtract a year" rule guessed wrong every
     * time it was run outside the season it was written for.
     */
    public static String getSeason(){
        return AAAConfiguration.getInstance().getSeason();
    }

    public static String getWebURL(){
        return "https://api.sleeper.app/projections/nfl/" + getSeason()
                + "?season_type=regular&position[]=DEF&position[]=QB&position[]=RB&position[]=TE&position[]=WR"
                + "&order_by=pts_half_ppr";
    }

    private static String getTodaysWebPage(){
        return InOutUtilities.getTodaysWebPage(getWebURL(), filepathStart + getSeason());
    }

    private static JsonArray cachedProjections;

    public static synchronized JsonArray getTodaysProjections(){
        if(cachedProjections == null){
            cachedProjections = JsonParser.parseString(getTodaysWebPage()).getAsJsonArray();
        }
        return cachedProjections;
    }

    public static double optionalStat(JsonObject stats, String key){
        JsonElement element = stats.get(key);
        if(element == null || element.isJsonNull()){
            return 0.0;
        }
        return element.getAsDouble();
    }

    /**
     * Sleeper player id -> projected points under this league's scoring.
     *
     * Sleeper's own pts_half_ppr assumes 4 points per passing touchdown; rather
     * than patching that up afterwards the points are recomputed from the stat
     * line using the league's real settings.
     */
    public static HashMap<String, Double> parseTodaysWebPage() {
        LeagueScoringSettings scoringSettings = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        HashMap<String, Double> playerSIDToScore = new HashMap<>();

        for (JsonElement jsonPlayer : getTodaysProjections()) {
            JsonObject playerObject = jsonPlayer.getAsJsonObject();
            String sleeperID = playerObject.get("player_id").getAsString();
            JsonObject stats = playerObject.getAsJsonObject("stats");
            if(stats == null){
                continue;
            }
            playerSIDToScore.put(sleeperID, scoreStatLine(stats, scoringSettings));
        }
        return playerSIDToScore;
    }

    /**
     * The same projections as a list of scored players.
     *
     * The draft simulator used to reach these through a second, parallel
     * implementation - Sleeper stats were unpacked into QBProjection /
     * FlexProjection / DEFProjection and rescored by FantasyProsScore. Both
     * arrived at the same number from the same input, so the two drifted the
     * moment a scoring category was added to one and not the other, which is
     * exactly what happened with two point conversions.
     */
    public static ArrayList<Score> getScoreList(LeagueScoringSettings scoringSettings) {
        ArrayList<Score> scores = new ArrayList<>();

        for (JsonElement jsonPlayer : getTodaysProjections()) {
            JsonObject playerObject = jsonPlayer.getAsJsonObject();
            JsonObject stats = playerObject.getAsJsonObject("stats");
            if(stats == null){
                continue;
            }
            Player player = Player.getPlayerFromSIDV2(playerObject.get("player_id").getAsString());
            if(player == null){
                continue;
            }
            scores.add(new Score(scoreStatLine(stats, scoringSettings), player));
        }
        return scores;
    }

    /** Points for one projected stat line under the given league settings. */
    public static double scoreStatLine(JsonObject stats, LeagueScoringSettings lss){
        double passing = optionalStat(stats, "pass_yd") * lss.passYard
                + optionalStat(stats, "pass_td") * lss.passTD
                + optionalStat(stats, "pass_int") * lss.interception;
        double rushing = optionalStat(stats, "rush_yd") * lss.rushYard
                + optionalStat(stats, "rush_td") * lss.rushTD;
        double receiving = optionalStat(stats, "rec") * lss.reception
                + optionalStat(stats, "rec_yd") * lss.receivingYard
                + optionalStat(stats, "rec_td") * lss.receivingTD;
        double twoPointConversions = optionalStat(stats, "pass_2pt") * lss.passTwoPoint
                + optionalStat(stats, "rush_2pt") * lss.rushTwoPoint
                + optionalStat(stats, "rec_2pt") * lss.receivingTwoPoint;
        double turnovers = optionalStat(stats, "fum_lost") * lss.fumbleLost;

        // Defenses have no offensive stat line to score, so they keep Sleeper's
        // number. Decided on which categories are present rather than on the
        // total coming out at zero, so that a genuine zero - a benched running
        // back projected for nothing - is not mistaken for a defense.
        if(!hasOffensiveStats(stats)){
            return optionalStat(stats, "pts_half_ppr");
        }

        return passing + rushing + receiving + twoPointConversions + turnovers;
    }

    private static final String[] OFFENSIVE_STATS =
            {"pass_yd", "pass_td", "pass_int", "rush_yd", "rush_td", "rec", "rec_yd", "rec_td",
             "pass_2pt", "rush_2pt", "rec_2pt"};

    static boolean hasOffensiveStats(JsonObject stats){
        for(String key : OFFENSIVE_STATS){
            JsonElement element = stats.get(key);
            if(element != null && !element.isJsonNull()){
                return true;
            }
        }
        return false;
    }

    /** Sleeper player id -> current NFL team, from today's projections. */
    public static HashMap<String, String> teamBySleeperID(){
        HashMap<String, String> out = new HashMap<>();
        for(JsonElement jsonPlayer : getTodaysProjections()){
            JsonObject row = jsonPlayer.getAsJsonObject();
            JsonElement team = row.get("team");
            JsonElement id = row.get("player_id");
            if(id != null && !id.isJsonNull() && team != null && !team.isJsonNull()){
                out.put(id.getAsString(), team.getAsString());
            }
        }
        return out;
    }

    /** Players within their first maxYears seasons right now (0 = rookies). */
    public static java.util.HashSet<String> youngPlayers(int maxYears){
        java.util.HashSet<String> out = new java.util.HashSet<>();
        int season = Integer.parseInt(getSeason());
        for(JsonElement jsonPlayer : getTodaysProjections()){
            JsonObject row = jsonPlayer.getAsJsonObject();
            JsonElement playerElement = row.get("player");
            if(playerElement == null || !playerElement.isJsonObject()){
                continue;
            }
            JsonElement metadata = playerElement.getAsJsonObject().get("metadata");
            if(metadata == null || !metadata.isJsonObject()){
                continue;
            }
            JsonElement rookieYear = metadata.getAsJsonObject().get("rookie_year");
            if(rookieYear == null || rookieYear.isJsonNull()){
                continue;
            }
            try {
                if(season - Integer.parseInt(rookieYear.getAsString()) <= maxYears){
                    out.add(row.get("player_id").getAsString());
                }
            } catch (NumberFormatException ignored){
            }
        }
        return out;
    }

    private static HashMap<String, Double> cachedAdp;

    /**
     * Average draft position, half PPR, from the same projections response the
     * scoring uses. Lower means drafted earlier. Players nobody is drafting
     * come back as Double.MAX_VALUE so they sort last.
     */
    public static synchronized double adpOf(String sleeperID){
        if(cachedAdp == null){
            HashMap<String, Double> adp = new HashMap<>();
            for(JsonElement jsonPlayer : getTodaysProjections()){
                JsonObject playerObject = jsonPlayer.getAsJsonObject();
                JsonObject stats = playerObject.getAsJsonObject("stats");
                if(stats == null){
                    continue;
                }
                JsonElement value = stats.get("adp_half_ppr");
                if(value != null && !value.isJsonNull()){
                    adp.put(playerObject.get("player_id").getAsString(), value.getAsDouble());
                }
            }
            cachedAdp = adp;
        }
        return cachedAdp.getOrDefault(sleeperID, Double.MAX_VALUE);
    }

    public static void main(String[] args){
        HashMap<String, Double> scores = parseTodaysWebPage();
        System.out.println("projected " + scores.size() + " players for the " + getSeason() + " season");
    }

}
