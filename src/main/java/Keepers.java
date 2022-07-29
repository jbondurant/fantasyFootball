import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.checkerframework.checker.units.qual.K;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public class Keepers {

    //todo, eventually, keepers will come from years prior, and will likely need to be manually added
    //todo hardcoded 2021 value
    public static String filepathStartSeriousLeague = "seriousOldDraftsSleeper";
    public static String draftIDHardcoded2021 = "725192044087148544";
    public static String leagueIDHardcoded2021 = "725192042375917568";
    public static String webURLSeriousRosters = "https://api.sleeper.app/v1/league/" + leagueIDHardcoded2021 + "/rosters";
    public static String filepathStartSeriousRosters = "seriousRostersForKeepers";
    public static String webURLSeriousLeague = "https://api.sleeper.app/v1/league/" + draftIDHardcoded2021 + "/drafts";
    public static String filepathDraftIDStartSeriousLeague = "seriousDraftIDPicks";

    public HashSet<Keeper> keepers;


    public Keepers(HashSet<Keeper> k){
        keepers = k;
    }


    public static Keepers getKeepersForUserHardcoded(boolean isFun, String userID, boolean allowUndrafted, int undraftedRoundCost) throws Exception {
        Keepers tooManyKeepers = getKeepersFromImmediateLastDraft(isFun, draftIDHardcoded2021, allowUndrafted, undraftedRoundCost);
        HashSet<Keeper> playersKeepers = new HashSet<>();
        for(Keeper k : tooManyKeepers.keepers){
            if(k.humanWhoCanKeep.equals(userID)){
                playersKeepers.add(k);
            }
        }
        return new Keepers(playersKeepers);
    }


    public static Keepers getKeepersFromImmediateLastDraft(boolean isFun, String draftID, boolean allowUndrafted, int undraftedRoundCost) throws Exception {
        HashMap<String, Roster> humansAndRosters = getHumansAndTheirRosters(draftID);
        //the above method doesn't work
        HashMap<Player, Integer> draftedPlayersAndRound = draftedPlayersLastYearAndRound(isFun, draftID);
        HashSet<Keeper> allKeepers = new HashSet<>();
        for(String userID : humansAndRosters.keySet()){
            ArrayList<Player> players = humansAndRosters.get(userID).draftedPlayers;
            for(Player p : players){
                if (draftedPlayersAndRound.containsKey(p)) {
                    int round = draftedPlayersAndRound.get(p);
                    Keeper keeper = new Keeper(userID, p, round);
                    allKeepers.add(keeper);
                }
                else{
                    if(allowUndrafted){
                        int round = undraftedRoundCost;
                        Keeper keeper = new Keeper(userID, p, round);
                        allKeepers.add(keeper);
                    }
                }

            }
        }
        Keepers keepers = new Keepers(allKeepers);
        return keepers;
    }


    public static HashMap<String, Roster> getHumansAndTheirRosters(String hardcodedDraftID){
        String websiteData = getTodaysRosterWebPageSerious();
        JsonParser jp = new JsonParser();
        JsonElement jsonElementDraft = jp.parse(websiteData);
        JsonArray jsonArrayRosters = jsonElementDraft.getAsJsonArray();
        HashMap<String, Roster> allRosters = new HashMap<>();
        for (JsonElement jsonDraftPick : jsonArrayRosters) {
            JsonObject apiObject = jsonDraftPick.getAsJsonObject();
            if (!apiObject.get("owner_id").isJsonNull()) {
                String userID = apiObject.get("owner_id").getAsString();
                if(!apiObject.get("players").isJsonNull()){
                    JsonArray allPIDsElements = apiObject.getAsJsonArray("players");
                    ArrayList<Player> rosterPlayers = new ArrayList<>();
                    for(JsonElement playerID : allPIDsElements) {
                        String pid = playerID.getAsString();
                        Player tempPlayer = Player.getPlayerFromSIDV2(pid);
                        rosterPlayers.add(tempPlayer);
                    }
                    Roster roster = new Roster(rosterPlayers);
                    allRosters.put(userID, roster);
                }
            }
        }
        return allRosters;
    }

    public static HashMap<Player, Integer> draftedPlayersLastYearAndRound(boolean isFun, String draftID) throws Exception {
        String websiteData;
        if(isFun) {
            throw new Exception("aaaaa");
        }
        else{
            websiteData = getDraftPicksTodaysWebPageSerious(draftID);
        }
        JsonParser jp = new JsonParser();
        JsonElement jsonElementDraft = jp.parse(websiteData);
        JsonArray jsonArrayDraft = jsonElementDraft.getAsJsonArray();
        HashMap<Player, Integer> allDraftedPlayersAndRound = new HashMap<>();
        for (JsonElement jsonDraftPick : jsonArrayDraft) {
            JsonObject apiObject = jsonDraftPick.getAsJsonObject();
            String sleeperID = "";
            if(!apiObject.get("player_id").isJsonNull()) {
                sleeperID = apiObject.get("player_id").getAsString();
                Player p = Player.getPlayerFromSIDV2(sleeperID);
                if(!apiObject.get("round").isJsonNull()){
                    int roundNum = apiObject.get("round").getAsInt();
                    allDraftedPlayersAndRound.put(p, roundNum);
                }
            }
        }
        return allDraftedPlayersAndRound;
    }

    public static String getLatestDraftID(String websiteData) {
        JsonParser jp = new JsonParser();
        JsonElement jsonElementDraft = jp.parse(websiteData);
        JsonArray jsonArrayDraft = jsonElementDraft.getAsJsonArray();
        int arrayLastIndex = jsonArrayDraft.size() - 1;
        JsonObject jsonObjectDraft = jsonArrayDraft.get(arrayLastIndex).getAsJsonObject();
        String draftID = jsonObjectDraft.get("draft_id").getAsString();
        return draftID;
    }


    private static String getTodaysRosterWebPageSerious(){
        return InOutUtilities.getTodaysWebPage(webURLSeriousRosters, filepathStartSeriousRosters);
    }

    private static String getDraftPicksTodaysWebPageSerious(String draftID){
        String webURL = "https://api.sleeper.app/v1/draft/" + draftID + "/picks";
        return InOutUtilities.getTodaysWebPage(webURL, filepathDraftIDStartSeriousLeague);
    }


}
