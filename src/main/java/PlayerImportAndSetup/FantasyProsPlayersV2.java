package PlayerImportAndSetup;

import PlayerImportAndSetup.FantasyProsPlayerV2;
import PlayerImportAndSetup.PlayerV2;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class FantasyProsPlayersV2 {

    private static String FILEPATH_START = "fantasyProsRankForID";
    private static String WEB_URL = "https://www.fantasypros.com/nfl/rankings/half-point-ppr-cheatsheets.php";

    private static HashSet<FantasyProsPlayerV2> fantasyProsPlayersV2 = new HashSet<>();

    static{
        String entireHTML = getTodaysWebPage();
        intializeAllPlayers(entireHTML);
    }

    public static HashSet<FantasyProsPlayerV2> getFantasyProsPlayersV2(){
        return  fantasyProsPlayersV2;
    }

    public static String getTodaysWebPage(){
        return InOutUtilitiesV2.getTodaysWebPage(WEB_URL, FILEPATH_START);
    }

    public static HashSet<FantasyProsPlayerV2> intializeAllPlayers(String entireHTML) {
        // Built fresh each call. It used to add into the static set, so calling
        // this twice in one JVM doubled the player count (FantasyProsPlayerV2
        // has no equals/hashCode, so the duplicates all stuck).
        HashSet<FantasyProsPlayerV2> players = new HashSet<>();

        JsonArray jsonPlayers = EcrDataExtractor.extract(entireHTML).getAsJsonArray("players");

        for (JsonElement jsonPlayer : jsonPlayers) {
            JsonObject apiObject = jsonPlayer.getAsJsonObject();

            String fantasyProsID = "";
            String playerName = "";
            String playerShortName = "";


            if(!apiObject.get("player_id").isJsonNull()) {
                fantasyProsID = apiObject.get("player_id").getAsString();
            }


            if(!apiObject.get("player_name").isJsonNull()) {
                playerName = apiObject.get("player_name").getAsString();
            }
            /*if(playerName.equals("Taysom Hill")){
                int x=1;
            }*/

            if(!apiObject.get("player_short_name").isJsonNull()) {
                playerShortName = apiObject.get("player_short_name").getAsString();
            }


            String playerTeamID= "";
            if(!apiObject.get("player_team_id").isJsonNull()) {
                playerTeamID = apiObject.get("player_team_id").getAsString();
            }

            String playerPositions= "";
            if(!apiObject.get("player_positions").isJsonNull()) {
                playerPositions = apiObject.get("player_positions").getAsString();
            }

            Double rankAverage = null;
            if((apiObject.get("rank_ave") != null) && (!apiObject.get("rank_ave").isJsonNull())) {
                rankAverage = apiObject.get("rank_ave").getAsDouble();
            }

            FantasyProsPlayerV2 fantasyProsPlayerV2 = FantasyProsPlayerV2.playerFromFP(fantasyProsID, playerName, playerShortName, playerTeamID, playerPositions, rankAverage);
            players.add(fantasyProsPlayerV2);
        }
        fantasyProsPlayersV2 = players;
        return players;
    }
}
