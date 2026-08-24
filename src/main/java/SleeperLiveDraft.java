import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class SleeperLiveDraft {

    /** This season's draft picks, live. */
    public static String getWebURLSerious(){
        return AAAConfiguration.draftPicksWebURL(AAAConfiguration.getInstance().getDraftID());
    }

    //DO NOT USE IO UTILITIES
    //IT MUST UPDATE EVERY CALL, NOT ONCE PER DAY
    public static LiveDraftInfo getLiveDraftInfo(String webURL, boolean isFun){
        String webData = WebUrlUtility.getLiveWebPage(webURL);
        JsonParser jp = new JsonParser();
        JsonElement jsonElement = jp.parse(webData);
        JsonArray jsonPlayers = jsonElement.getAsJsonArray();

        ArrayList<Player> draftedPlayers = new ArrayList<Player>();
        ArrayList<Player> rosterPlayers = new ArrayList<Player>();

        String myID = HumanOfInterest.humanID();
        for (JsonElement jsonPlayer : jsonPlayers) {
            JsonObject apiObject = jsonPlayer.getAsJsonObject();
            // player_id carries the team abbreviation for a defense, so it is
            // read as a string and dispatched on rather than parsed as an int.
            Player player = Player.getPlayerFromSIDV2(apiObject.get("player_id").getAsString());
            if(player == null){
                continue;
            }
            JsonElement pickedByElement = apiObject.get("picked_by");
            if(pickedByElement != null && !pickedByElement.isJsonNull()
                    && pickedByElement.getAsString().equals(myID)){
                rosterPlayers.add(player);
            }
            draftedPlayers.add(player);
        }
        LiveDraftInfo ldifb = new LiveDraftInfo(draftedPlayers, rosterPlayers, isFun);
        return ldifb;
    }
    public static LiveDraftInfo getDraftedPlayersMock(String mockDraftID, boolean isFun){
        String mockURL = "https://api.sleeper.app/v1/draft/" + mockDraftID + "/picks";
        return getLiveDraftInfo(mockURL, isFun);
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration aaaConfiguration = AAAConfiguration.getInstance();
        Instant start = Instant.now();
        boolean isFun = false;
        String draftID = aaaConfiguration.getDraftID();
        int numDraftsOnFly = 300;
        int qbADPChange = 18;//at least 6, if not 12
        int minMaxStartSize = 2;
        int numThreads = 5;
        int numTeams = SleeperLeague.getSeriousLeague().sleeperDraftInfo.usersInfo.size();
        if(numTeams == 0){
            numTeams = 12;
        }
        ArrayList<Keeper> keepers = aaaConfiguration.getTodaysKeepers();
        ArrayList positionsWanted = HumanStrategy.nonPermutedPositions(1,4,4,1);

        LiveDraftInfo ldifb = getDraftedPlayersMock(draftID, isFun);
        int numDraftedPlayers = ldifb.draftedPlayers.size();
        int currentRound = (numDraftedPlayers / numTeams) + 1;
        LiveDraftInfo.LiveDraftPotentialMoveAnalyzer(ldifb.bestAvailablePlayers);
        System.out.println("---------------");

        List<DraftRunsResults> draftRunsResults = OnTheFlySimulationRunner.runDraftsWithKeepersMultipleThreads(numDraftsOnFly, currentRound, positionsWanted, ldifb, qbADPChange, keepers, minMaxStartSize, numThreads);

        DraftRunsResults.printDraftRunResults(ldifb, draftRunsResults);

        Instant end = Instant.now();
        Duration timeElapsed = Duration.between(start, end);
        System.out.println(timeElapsed);
    }

}
