import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public class Keepers {

    public static String filepathStartSeriousLeague = "seriousOldDraftsSleeper";

    public static String filepathDraftIDStartSeriousLeague = "seriousDraftIDPicks";

    public HashSet<Keeper> keepers;


    public Keepers(HashSet<Keeper> k){
        keepers = k;
    }


    /**
     * Everyone this user could keep: the players currently on their roster,
     * priced at the round they went in last season's draft.
     *
     * The old version read a draft id pasted in from 2022 and then remapped a
     * handful of user ids by hand to paper over people who had left the league.
     * Both now come from the API - the previous season is reachable through the
     * league's previous_league_id, and rosters carry current owners.
     */
    public static Keepers getKeepersForUser(boolean isFun, String userID, boolean allowUndrafted, int undraftedRoundCost, AAAConfiguration aaaConfiguration) throws Exception {
        String previousDraftID = aaaConfiguration.getPreviousDraftID();
        if(previousDraftID == null){
            throw new Exception("league " + aaaConfiguration.getLeagueID()
                    + " has no previous season, so there are no keeper prices to read");
        }
        Keepers tooManyKeepers = getKeepersFromImmediateLastDraft(isFun, previousDraftID, allowUndrafted, undraftedRoundCost, aaaConfiguration);
        HashSet<Keeper> playersKeepers = new HashSet<>();
        for(Keeper k : tooManyKeepers.keepers){
            if(k.humanWhoCanKeep.equals(userID)){
                playersKeepers.add(k);
            }
        }
        return new Keepers(playersKeepers);
    }


    public static Keepers getKeepersFromImmediateLastDraft(boolean isFun,
                                                           String draftID,
                                                           boolean allowUndrafted,
                                                           int undraftedRoundCost,
                                                           AAAConfiguration aaaConfiguration) throws Exception {
        HashMap<String, Roster> humansAndRosters = getHumansAndTheirRosters(draftID, aaaConfiguration);
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


    public static HashMap<String, Roster> getHumansAndTheirRosters(String hardcodedDraftID, AAAConfiguration aaaConfiguration){
        String websiteData = aaaConfiguration.getTodaysRosterWebPageSerious();
        JsonElement jsonElementDraft = JsonParser.parseString(websiteData);
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
                        if(tempPlayer != null){
                            rosterPlayers.add(tempPlayer);
                        }
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
        JsonElement jsonElementDraft = JsonParser.parseString(websiteData);
        JsonArray jsonArrayDraft = jsonElementDraft.getAsJsonArray();
        HashMap<Player, Integer> allDraftedPlayersAndRound = new HashMap<>();
        for (JsonElement jsonDraftPick : jsonArrayDraft) {
            JsonObject apiObject = jsonDraftPick.getAsJsonObject();
            String sleeperID = "";
            if(!apiObject.get("player_id").isJsonNull()) {
                sleeperID = apiObject.get("player_id").getAsString();
                Player p = Player.getPlayerFromSIDV2(sleeperID);
                if(p != null && !apiObject.get("round").isJsonNull()){
                    int roundNum = apiObject.get("round").getAsInt();
                    allDraftedPlayersAndRound.put(p, roundNum);
                }
            }
        }
        return allDraftedPlayersAndRound;
    }

    public static String getLatestDraftID(String websiteData) {
        JsonElement jsonElementDraft = JsonParser.parseString(websiteData);
        JsonArray jsonArrayDraft = jsonElementDraft.getAsJsonArray();
        int arrayLastIndex = jsonArrayDraft.size() - 1;
        JsonObject jsonObjectDraft = jsonArrayDraft.get(arrayLastIndex).getAsJsonObject();
        String draftID = jsonObjectDraft.get("draft_id").getAsString();
        return draftID;
    }

    private static String getDraftPicksTodaysWebPageSerious(String draftID){
        String webURL = "https://api.sleeper.app/v1/draft/" + draftID + "/picks";
        return InOutUtilities.getTodaysWebPage(webURL, filepathDraftIDStartSeriousLeague);
    }

}
