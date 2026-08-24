import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;

//Poorly designed
public class SleeperLeague{

    public static String filepathStartSeriousLeague = "seriousLeagueSleeper";

    private static String myID(){
        return HumanOfInterest.humanID();
    }

    League league;
    String name;
    String leagueID;
    String draftID;
    SleeperDraftInfo sleeperDraftInfo;

    public SleeperLeague(League l, String n, String lid, String did, SleeperDraftInfo sdi){
        league = l;
        name = n;
        leagueID = lid;
        draftID = did;
        sleeperDraftInfo = sdi;
    }


    private static SleeperLeague cachedSeriousLeague;

    /**
     * The configured league. Parsing it hits three endpoints, and roughly every
     * scoring path asks for it, so the result is held onto.
     */
    public static synchronized SleeperLeague getSeriousLeague(){
        if(cachedSeriousLeague == null){
            String seriousLeagueWebsite = getTodaysWebPageSerious();
            SleeperDraftInfo seriousDraft = SleeperDraftInfo.getSeriousDraft();
            cachedSeriousLeague = parseWebsite(seriousLeagueWebsite, seriousDraft);
        }
        return cachedSeriousLeague;
    }


    private static String getTodaysWebPageSerious(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        return InOutUtilities.getTodaysWebPage(
                "https://api.sleeper.app/v1/league/" + configuration.getLeagueID(),
                filepathStartSeriousLeague + configuration.getLeagueID());
    }

    public static SleeperLeague parseWebsite(String websiteData, SleeperDraftInfo sdi){
        JsonParser jp = new JsonParser();
        JsonElement jsonElementLeague = jp.parse(websiteData);
        JsonObject jsonObjectLeague = jsonElementLeague.getAsJsonObject();
        JsonObject scoringParameters = jsonObjectLeague.getAsJsonObject("scoring_settings");
        double passYards = scoringParameters.get("pass_yd").getAsDouble();
        double passTD = scoringParameters.get("pass_td").getAsDouble();
        double passInt = scoringParameters.get("pass_int").getAsDouble();
        double rushYards = scoringParameters.get("rush_yd").getAsDouble();
        double rushTD = scoringParameters.get("rush_td").getAsDouble();
        double reception = scoringParameters.get("rec").getAsDouble();
        double recYards = scoringParameters.get("rec_yd").getAsDouble();
        double recTD = scoringParameters.get("rec_td").getAsDouble();
        double fumbleLost = scoringParameters.get("fum_lost").getAsDouble();
        String name = "";
        if(!jsonObjectLeague.get("name").isJsonNull()) {
            name = jsonObjectLeague.get("name").getAsString();
        }
        String leagueID = "";
        if(!jsonObjectLeague.get("league_id").isJsonNull()) {
            leagueID = jsonObjectLeague.get("league_id").getAsString();
        }
        String draftID = "";
        if(!jsonObjectLeague.get("draft_id").isJsonNull()) {
            draftID = jsonObjectLeague.get("draft_id").getAsString();
        }
        double[] parameters = new double[9];
        parameters[0] = passYards;
        parameters[1] = passTD;
        parameters[2] = passInt;
        parameters[3] = rushYards;
        parameters[4] = rushTD;
        parameters[5] = reception;
        parameters[6] = recYards;
        parameters[7] = recTD;
        parameters[8] = fumbleLost;
        ArrayList<User> users = sdi.usersInfo;
        LeagueScoringSettings leagueScoringSettings = new LeagueScoringSettings(parameters);
        ArrayList<Player> undraftedPlayers = Player.getDraftablePlayers();
        League league = new League(leagueScoringSettings, users, undraftedPlayers);
        SleeperLeague sleeperLeague = new SleeperLeague(league, name, leagueID, draftID, sdi);
        return sleeperLeague;
    }

    private static ArrayList<Score> cachedScoreList;

    public static synchronized ArrayList<Score> getScoreList(){
        if(cachedScoreList != null){
            return cachedScoreList;
        }
        SleeperLeague seriousL = SleeperLeague.getSeriousLeague();
        LeagueScoringSettings seriousSettings = seriousL.league.leagueScoringSettings;
        FantasyProsScore seriousScores = new FantasyProsScore(seriousSettings);
        cachedScoreList = seriousScores.fantasyProsScoreLeagueAdjusted;
        return cachedScoreList;
    }

    public static HashMap<String, Double> getScoreMap(){
        HashMap<String, Double> scoreMap = new HashMap<>();
        ArrayList<Score> scoreList = getScoreList();
        for(Score s : scoreList){
            if(s.player != null && s.player.sportRadarID != null){
                scoreMap.put(s.player.sportRadarID, s.score);
            }
        }
        return scoreMap;
    }

    public static double scoreSleeperDraft(SleeperLeague sleeperLeague, boolean isFun){
        ArrayList<Score> scoreList = getScoreList();
        double totalScore = 0;
        for(User user : sleeperLeague.sleeperDraftInfo.usersInfo) {
            if (user.userID.equals(myID())) {
                Roster roster = user.roster;
                for(Player player : roster.draftedPlayers){
                    double playerScore = Player.scorePlayer(scoreList, player);
                    totalScore += playerScore;
                }
            }
        }
        return totalScore;
    }
}
