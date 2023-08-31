import com.google.gson.*;

import java.util.ArrayList;
import java.util.HashSet;

public class AAAConfiguration {

    private String leagueID;
    private String myUsername;
    private String myNameForLeague;

    public AAAConfiguration(String leagueID, String myUsername, String myNameForLeague){
        this.leagueID = leagueID;
        this.myUsername = myUsername;
        this.myNameForLeague = myNameForLeague;
    }

    public String getLeagueID() {
        return leagueID;
    }

    public String getMyID(){
        return InOutUtilities.getThisMonthsMyID(myUsername);
    }

    public String getMyNameForLeague(){
        return myNameForLeague;
    }

    public String getRosterWebURL(){
        return "https://api.sleeper.app/v1/league/" + this.leagueID + "/rosters";
    }

    private String getDraftsWebURL(){
        return "https://api.sleeper.app/v1/league/" + this.leagueID + "/drafts";
    }

    private String getDraftPicksWebURL(){
        return "https://api.sleeper.app/v1/draft/" + getDraftFromLeagueIfOnlyOneDraft() + "/picks";
    }

    public static String filepathStartSeriousRosters = "seriousRostersForKeepers";
    public static String filepathStartDrafts = "draftsData";
    public static String filepathStartDraft = "draftData";

    public String getTodaysRosterWebPageSerious(){//todo 2023 move this out into utilities or something
        return InOutUtilities.getTodaysWebPage(this.getRosterWebURL(), filepathStartSeriousRosters);
    }

    public String getTodaysDrafts(){
        return InOutUtilities.getTodaysWebPage(this.getDraftsWebURL(), filepathStartDrafts);
    }

    public String getTodaysDraftPicks(){// todo 2023 perhaps change this to not cache;
        return InOutUtilities.getTodaysWebPage(this.getDraftPicksWebURL(), filepathStartDraft);
    }

    public ArrayList<JsonElement> getTodaysRoster() {
        String websiteData = getTodaysRosterWebPageSerious();
        JsonParser jp = new JsonParser();
        JsonArray unparsedRosters = jp.parse(websiteData).getAsJsonArray();
        ArrayList<JsonElement> jsonRosters = new ArrayList<>();
        for (JsonElement jsonRoster : unparsedRosters) {
            jsonRosters.add(jsonRoster);
        }
        return jsonRosters;
    }

    public HashSet<Integer> getTodaysKeeperPlayerIDs(){
        HashSet<Integer> playerIDs = new HashSet<>();
        ArrayList<JsonElement> rosters = getTodaysRoster();
        for(JsonElement unparsedRoster : rosters){
            JsonObject apiObject = unparsedRoster.getAsJsonObject();
            if(apiObject.get("keepers").isJsonNull()){
               continue;
            }
            JsonArray jsonArray = apiObject.getAsJsonArray("keepers");
            for (JsonElement jsonElement : jsonArray){
                //Player keeper = Player.getPlayerFromSID(jsonElement.getAsInt());
                playerIDs.add(jsonElement.getAsInt());
            }
        }
        return playerIDs;
    }

    public ArrayList<Keeper> getTodaysKeepers(){
        ArrayList<Keeper> keepers = new ArrayList<>();

        HashSet<Integer> keeperPlayerIDs = getTodaysKeeperPlayerIDs();
        String draftData = getTodaysDraftPicks();
        JsonParser jp = new JsonParser();
        JsonArray unparsedPicks = jp.parse(draftData).getAsJsonArray();
        for (JsonElement jsonPick : unparsedPicks) {
            int playerID = ((JsonObject) jsonPick).get("player_id").getAsInt();
            if(!keeperPlayerIDs.contains(playerID)){
                continue;
            }
            int playerRound = ((JsonObject) jsonPick).get("round").getAsInt();
            String teamOwnerID = ((JsonObject) jsonPick).get("picked_by").getAsString();
            Player player = Player.getPlayerFromSID(playerID);
            keepers.add(new Keeper(teamOwnerID, player, playerRound));
        }
        return keepers;
    }

    public String getDraftFromLeagueIfOnlyOneDraft(){
        String apiData = getTodaysDrafts();
        JsonParser jp = new JsonParser();
        JsonArray unparsedDrafts = jp.parse(apiData).getAsJsonArray();
        ArrayList<JsonElement> jsonDrafts = new ArrayList<>();
        for (JsonElement jsonRoster : unparsedDrafts) {
            jsonDrafts.add(jsonRoster);
        }
        if(jsonDrafts.size()!=1){
            throw new RuntimeException("league has more than 1 draft");
        }
        return ((JsonObject) jsonDrafts.get(0)).get("draft_id").getAsString();
    }

    public static void main(String[] args){
        AAAConfiguration aaaConfiguration = new AAAConfigurationSleeperLeague();
        ArrayList<Keeper> abc = aaaConfiguration.getTodaysKeepers();
        int a = 1;
    }

}
