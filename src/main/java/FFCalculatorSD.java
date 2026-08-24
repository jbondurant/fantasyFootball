import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class FFCalculatorSD {

    public static String getSeason(){
        return AAAConfiguration.getInstance().getSeason();
    }

    public static String filepathStartSerious = "ffCalculatorSDSerious";

    public static String getWebURLSerious(){
        return "https://fantasyfootballcalculator.com/api/v1/adp/half-ppr?teams=12&year=" + getSeason() + "&position=all";
    }

    public static ArrayList<StandardDevPlayer> seriousPlayerSD;

    public static HashMap<String, Double> playerSRIDToSDMapSerious;


    static{
        playerSRIDToSDMapSerious = initializeSeriousSDMap();
    }


    private static String getTodaysWebPageSerious(){
        return InOutUtilities.getTodaysWebPage(getWebURLSerious(), filepathStartSerious + getSeason());
    }
    private static HashMap<String, Double> initializeSeriousSDMap(){
        String webData = getTodaysWebPageSerious();
        return parsePageMap(webData);
    }

    private static ArrayList<StandardDevPlayer> initializeSeriousSD(){
        String webData = getTodaysWebPageSerious();
        return parsePage(webData);
    }

    private static ArrayList<StandardDevPlayer> parsePage(String webData){
        ArrayList<StandardDevPlayer> allPlayerSD = new ArrayList<StandardDevPlayer>();

        JsonParser jp = new JsonParser();
        JsonElement jsonElement = jp.parse(webData);
        JsonObject allData = jsonElement.getAsJsonObject();

        JsonElement jsonPlayersElement = allData.get("players");

        JsonArray jsonPlayers = jsonPlayersElement.getAsJsonArray();

        int size = jsonPlayers.size();

        for (JsonElement jsonPlayer : jsonPlayers) {
            JsonObject apiObject = jsonPlayer.getAsJsonObject();

            String fullName = apiObject.get("name").getAsString();
            String position = apiObject.get("position").getAsString();
            String team = apiObject.get("team").getAsString();
            String firstName = fullName.split(" ")[0];
            String lastName = fullName.split(" ").length > 1 ? fullName.split(" ")[1] : "";

            double sd = apiObject.get("stdev").getAsDouble();
            Player player = Player.getPlayerFromInfo(lastName, firstName, position, team);
            StandardDevPlayer sdp = new StandardDevPlayer(sd, player);
            allPlayerSD.add(sdp);
        }

        return allPlayerSD;
    }


    private static HashMap<String, Double> parsePageMap(String webData){
        HashMap<String, Double> allPlayerSDMap = new HashMap<String, Double>();

        JsonParser jp = new JsonParser();
        JsonElement jsonElement = jp.parse(webData);
        JsonObject allData = jsonElement.getAsJsonObject();

        JsonElement jsonPlayersElement = allData.get("players");

        JsonArray jsonPlayers = jsonPlayersElement.getAsJsonArray();

        int size = jsonPlayers.size();

        for (JsonElement jsonPlayer : jsonPlayers) {
            JsonObject apiObject = jsonPlayer.getAsJsonObject();

            String fullName = apiObject.get("name").getAsString();
            String position = apiObject.get("position").getAsString();
            String team = apiObject.get("team").getAsString();
            String firstName = fullName.split(" ")[0];
            String lastName = fullName.split(" ").length > 1 ? fullName.split(" ")[1] : "";

            double sd = apiObject.get("stdev").getAsDouble();
            Player player = Player.getPlayerFromInfo(lastName, firstName, position, team);
            if(player == null || player.sportRadarID == null){
                continue;
            }

            String playerSRID = player.sportRadarID;
            allPlayerSDMap.put(playerSRID, sd);
        }

        return allPlayerSDMap;
    }


    public static void main(String[] args){
        System.out.println("draft-position standard deviations for " + initializeSeriousSDMap().size() + " players");
    }


}
