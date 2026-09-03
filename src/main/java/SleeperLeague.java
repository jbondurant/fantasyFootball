import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;

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
        JsonElement jsonElementLeague = JsonParser.parseString(websiteData);
        JsonObject jsonObjectLeague = jsonElementLeague.getAsJsonObject();
        JsonObject scoringParameters = jsonObjectLeague.getAsJsonObject("scoring_settings");
        LeagueScoringSettings leagueScoringSettings =
                LeagueScoringSettings.fromSleeperScoringSettings(scoringParameters);
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
        ArrayList<User> users = sdi.usersInfo;
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
        cachedScoreList = SleeperProjections.getScoreList(seriousL.league.leagueScoringSettings);
        return cachedScoreList;
    }

    /**
     * My best startable lineup after a draft: QB, RB, RB, WR, WR, WR, TE,
     * FLEX, FLEX, DEF.
     *
     * This used to add up all sixteen drafted players. A bench that never
     * starts scored the same as the lineup, so the simulator was rewarding
     * depth over a starter, and treating a second elite quarterback in a
     * one-quarterback league as though he doubled your season. Every roster
     * comparison in the trade finder already scored the lineup; the draft
     * simulation was the odd one out.
     */
    public static double scoreSleeperDraft(SleeperLeague sleeperLeague, boolean isFun){
        ArrayList<Score> scoreList = getScoreList();
        for(User user : sleeperLeague.sleeperDraftInfo.usersInfo) {
            if (user.userID.equals(myID())) {
                return scoreBestStartingLineup(user, scoreList);
            }
        }
        return 0.0;
    }

    /** The same, for any manager. */
    public static double scoreBestStartingLineup(User user, ArrayList<Score> scoreList){
        ArrayList<Score> scored = new ArrayList<>();
        for(Player player : user.roster.draftedPlayers){
            if(player == null){
                continue;
            }
            scored.add(new Score(Player.scorePlayer(scoreList, player), player));
        }
        return new ScoredRoster(user.userID, scored).scoreBestROSStartingLineup();
    }
}
