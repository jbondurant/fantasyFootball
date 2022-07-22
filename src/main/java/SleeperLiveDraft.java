import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;

public class SleeperLiveDraft {

    public static String webURLSerious = "https://api.sleeper.app/v1/draft/725192044087148544/picks";
    public static String webURLFun = "https://api.sleeper.app/v1/draft/707299245186691073/picks";

    public static String sampleFinishedMockID = "739938185269968896";

    //DO NOT USE IO UTILITIES
    //IT MUST UPDATE EVERY CALL, NOT ONCE PER DAY

    public static LiveDraftInfo getLiveDraftInfoFunBulk(String webURL, boolean isFun){
        String webData = WebUrlUtility.getLiveWebPage(webURL);

        JsonParser jp = new JsonParser();
        JsonElement jsonElement = jp.parse(webData);
        JsonArray jsonPlayers = jsonElement.getAsJsonArray();


        ArrayList<Player> draftedPlayers = new ArrayList<Player>();
        ArrayList<Player> rosterPlayers = new ArrayList<Player>();

        for (JsonElement jsonPlayer : jsonPlayers) {
            JsonObject apiObject = jsonPlayer.getAsJsonObject();

            JsonObject playerMetadata = apiObject.getAsJsonObject("metadata");
            String position = playerMetadata.get("position").getAsString();
            String team = playerMetadata.get("team").getAsString();

            Player player;
            if(!position.equals("DEF")) {
                int playerSID = apiObject.get("player_id").getAsInt();
                player = Player.getPlayerFromSID(playerSID);
            }
            else{
                player = Player.getPlayerDefense(team);
            }

            String pickedBy = apiObject.get("picked_by").getAsString();

            if(pickedBy.equals(HumanOfInterest.humanID)){
                rosterPlayers.add(player);
                draftedPlayers.add(player);
            }
            else{
                draftedPlayers.add(player);
            }
        }
        LiveDraftInfo ldifb = new LiveDraftInfo(draftedPlayers, rosterPlayers, isFun);
        return ldifb;
    }


    public static LiveDraftInfo getDraftedPlayersMock(String mockDraftID, boolean isFun){
        String mockURL = "https://api.sleeper.app/v1/draft/" + mockDraftID + "/picks";
        return getLiveDraftInfoFunBulk(mockURL, isFun);
    }


    public static LiveDraftInfo getDraftedPlayersFun(){
        return getLiveDraftInfoFunBulk(webURLFun, true);
    }

    public static LiveDraftInfo getDraftedPlayersSerious(){
        return getLiveDraftInfoFunBulk(webURLSerious, false);
    }

//TODO instead of checking key, try in diff db just adding everything, then later making averages for each key
// and figure out how to batch add to db

    //TODO most realistic select permutation,
    // make humanSimulator constructor for selected permutation
    // run like 100k drafts for that permutation
    // save the data in HashMap<String, Pair<double, int>> (keyID, (AvScore, numTimes))
    // calculate the averages in java
    // then send to db
    // will need to add method to add weighted(multiple drafts) score to db

    //for permutations, have method to make all permutations then pic say perms (140,150) for

    public static void main(String[] args){
        int roundPick = 2; //todo needs to be updated all the time
        boolean isFun = false;
        String draftID = "855593342313009152";
        int numDraftsOnFly = 300;
        LiveDraftInfo ldifb = getDraftedPlayersMock(draftID, isFun);
        LiveDraftInfo.LiveDraftPotentialMoveAnalyzer(ldifb);
        ArrayList<Position> positionsWanted = HumanStrategy.nonPermutedFun();
        if(!isFun){
            positionsWanted = HumanStrategy.nonPermutedSerious();
        }
        OnTheFlySimulationRunner.runDraftsOnTheFly(numDraftsOnFly, roundPick,isFun, positionsWanted, ldifb);
        int y=1;
    }



}
