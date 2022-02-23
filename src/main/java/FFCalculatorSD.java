import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class FFCalculatorSD {

    public static String filepathStartSerious = "ffCalculatorSDSerious";
    public static String webURLSerious = "https://fantasyfootballcalculator.com/api/v1/adp/half-ppr?teams=12&year=2021&position=all";

    public static String filepathStartFun = "ffCalculatorSDFun";
    public static String webURLFun = "https://fantasyfootballcalculator.com/api/v1/adp/2qb?teams=10&year=2021&position=all";

    public static ArrayList<StandardDevPlayer> funPlayerSD;
    public static ArrayList<StandardDevPlayer> seriousPlayerSD;

    public static HashMap<String, Double> playerSRIDToSDMapFun;
    public static HashMap<String, Double> playerSRIDToSDMapSerious;


    static{

        funPlayerSD = initializeFunSD();
        seriousPlayerSD = initializeSeriousSD();
        playerSRIDToSDMapFun = initializeFunSDMap();
        playerSRIDToSDMapSerious = initializeSeriousSDMap();
    }


    private static String getTodaysWebPageFun(){
        return InOutUtilities.getTodaysWebPage(webURLFun, filepathStartFun);
    }
    private static String getTodaysWebPageSerious(){
        return InOutUtilities.getTodaysWebPage(webURLSerious, filepathStartSerious);
    }

    private static HashMap<String, Double> initializeFunSDMap(){
        String webData = getTodaysWebPageFun();
        return parsePageMap(webData);
    }
    private static HashMap<String, Double> initializeSeriousSDMap(){
        String webData = getTodaysWebPageSerious();
        return parsePageMap(webData);
    }

    private static ArrayList<StandardDevPlayer> initializeFunSD(){
        String webData = getTodaysWebPageFun();
        return parsePage(webData);
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
            String lastName = fullName.split(" ")[1];


            if(position.equals("QB") && team.equals("KC")){
                int y=0;
            }


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
            String lastName = fullName.split(" ")[1];

            if(position.equals("QB") && team.equals("KC")){
                int y=0;
            }

            double sd = apiObject.get("stdev").getAsDouble();
            Player player = Player.getPlayerFromInfo(lastName, firstName, position, team);




            if(player == null){
                continue;
            }

            //Agholor and others weren't in the list of unrecognized players because
            //I was looking at the 2qb league where he's not in the top 200

            //System.out.println(player.firstName + " " + player.lastName);

            String playerSRID = player.sportRadarID;
            allPlayerSDMap.put(playerSRID, sd);
        }

        return allPlayerSDMap;
    }


    public static void main(String[] args){
        ArrayList<StandardDevPlayer> a = initializeFunSD();
    }


}
