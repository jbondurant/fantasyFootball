import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;

public class DefenseUtility {

    public static String filepathStart = "fantasyProsDefRank";
    public static String webURL = "https://www.fantasypros.com/nfl/rankings/dst-cheatsheets.php";

    public static HashMap<String, String> teamToID = new HashMap<>();
    static{
        teamToID = parseTodaysWebPage();
    }

    public static String getDefenseID(String teamAbr){
        String sportRadarID = teamToID.get(teamAbr);
        return sportRadarID;
    }

    public static String getTodaysWebPage(){
        return InOutUtilities.getTodaysWebPage(webURL, filepathStart);
    }

    private static HashMap<String, String> parseTodaysWebPage(){
        String entireHTML = getTodaysWebPage();
        String ecrDataStart = entireHTML.split("var ecrData = ")[1].split("\"players\":")[1];
        String ecrData = ecrDataStart.split("var sosData")[0].split(",\"experts_available\":")[0];

        JsonParser jp = new JsonParser();
        JsonElement jsonElement = jp.parse(ecrData);
        JsonArray jsonPlayers = jsonElement.getAsJsonArray();

        HashMap<String, String> teamToID = new HashMap<String, String>();
        for (JsonElement jsonPlayer : jsonPlayers) {
            JsonObject apiObject = jsonPlayer.getAsJsonObject();
            String sportRadarID = apiObject.get("sportsdata_id").getAsString();
            String teamAbr = apiObject.get("player_team_id").getAsString();
            teamToID.put(teamAbr, sportRadarID);
        }
        return teamToID;
    }

}
