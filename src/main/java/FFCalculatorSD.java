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


    /**
     * Sleeper player id -> that season's ADP standard deviation, centered on
     * the season median so a missing player reads as typical (zero). Past
     * seasons are immutable and cached forever; matching is by name and
     * position since NFL teams drift. Any fetch or parse trouble returns an
     * empty map - the feature column just goes dark for that season.
     */
    public static java.util.HashMap<String, Double> centeredSpreadBySleeperID(String season){
        java.util.HashMap<String, Double> bySleeperID = new java.util.HashMap<>();
        try {
            String url = "https://fantasyfootballcalculator.com/api/v1/adp/half-ppr?teams=12&year="
                    + season;
            String data = season.equals(getSeason())
                    ? InOutUtilities.getTodaysWebPage(url, filepathStartSerious + season)
                    : InOutUtilities.getCachedForever(url, "ffCalculatorSDFinal" + season);
            JsonObject allData = JsonParser.parseString(data).getAsJsonObject();
            for(JsonElement jsonPlayer : allData.get("players").getAsJsonArray()){
                JsonObject apiObject = jsonPlayer.getAsJsonObject();
                String position = apiObject.get("position").getAsString();
                if(!position.equals("QB") && !position.equals("RB")
                        && !position.equals("WR") && !position.equals("TE")){
                    continue;
                }
                Player player = Player.getPlayerFromNameAndPos(
                        apiObject.get("name").getAsString(),
                        PlayerImportAndSetup.Position.valueOf(position));
                if(player != null){
                    bySleeperID.put(player.sleeperIDString,
                            apiObject.get("stdev").getAsDouble());
                }
            }
            if(bySleeperID.isEmpty()){
                return bySleeperID;
            }
            java.util.ArrayList<Double> values = new java.util.ArrayList<>(bySleeperID.values());
            values.sort(Double::compareTo);
            double median = values.get(values.size() / 2);
            bySleeperID.replaceAll((id, sd) -> sd - median);
        } catch (RuntimeException problem){
            bySleeperID.clear();
        }
        return bySleeperID;
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
        JsonElement jsonElement = JsonParser.parseString(webData);
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
        JsonElement jsonElement = JsonParser.parseString(webData);
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
