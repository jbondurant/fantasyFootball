import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.checkerframework.checker.units.qual.A;

import java.util.ArrayList;
import java.util.HashMap;

public class FantasyProsUtility {


    public static String filepathStart = "fantasyProsRankForID";
    public static String webURL = "https://www.fantasypros.com/nfl/rankings/half-point-ppr-cheatsheets.php";

    public static HashMap<String, Integer> sridToFPID = new HashMap<String, Integer>();
    public static HashMap<Integer, String> fpidToSRID = new HashMap<Integer, String>();


    static{
        initializeMap();
    }

    public static int getFPID(String sr){
        int fpID = -1;
        if(sridToFPID.containsKey(sr)) {
            fpID = sridToFPID.get(sr);
        }
        return fpID;
    }

    public static String getSRID(int fp){
        String srID = "";
        if(fpidToSRID.containsKey(fp)) {
            srID = fpidToSRID.get(fp);
        }
        return srID;
    }

    public static String getTodaysWebPage(){
        return InOutUtilities.getTodaysWebPage(webURL, filepathStart);
    }


    private static void initializeMap(){
        ArrayList<Rank> rankingForHardcodedChosenExperts = new ArrayList<>();

        String entireHTML = getTodaysWebPage();
        String ecrDataStart = entireHTML.split("var ecrData = ")[1].split("\"players\":")[1];
        String ecrData = ecrDataStart.split("var sosData")[0].split(",\"experts_available\":")[0];

        JsonParser jp = new JsonParser();
        JsonElement jsonElement = jp.parse(ecrData);
        JsonArray jsonPlayers = jsonElement.getAsJsonArray();

        ArrayList<Rank> todaysRankings = new ArrayList<Rank>();
        for (JsonElement jsonPlayer : jsonPlayers) {
            JsonObject apiObject = jsonPlayer.getAsJsonObject();

            String sportRadarID = "";
            if(!apiObject.get("sportsdata_id").isJsonNull()) {
                sportRadarID = apiObject.get("sportsdata_id").getAsString();
            }

            int fantasyProsID = -1;
            if(!apiObject.get("player_id").isJsonNull()) {
                fantasyProsID = apiObject.get("player_id").getAsInt();
            }

            sridToFPID.put(sportRadarID, fantasyProsID);
            fpidToSRID.put(fantasyProsID, sportRadarID);
        }
    }


}
