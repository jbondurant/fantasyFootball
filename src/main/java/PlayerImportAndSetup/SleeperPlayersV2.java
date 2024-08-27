package PlayerImportAndSetup;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class SleeperPlayersV2 {

    public static int year = LocalDate.now().getYear();

    public static String FILEPATH_START = "FantasyFootballCalculatorForSleeperIds";

    public static String WEB_URL = "https://fantasyfootballcalculator.com/api/v1/adp/half-ppr?teams=12&year=" + year + "&position=all";


    public static HashMap<String, SleeperPlayerV2> getSleeperPlayersV2AsMap() {
        HashMap<String, SleeperPlayerV2> customIdToSleeperPlayerV2 = new HashMap<>();
        for(SleeperPlayerV2 sleeperPlayerV2 : sleeperPlayersV2){
            customIdToSleeperPlayerV2.put(sleeperPlayerV2.getPlayerV2().customID, sleeperPlayerV2);
        }
        return customIdToSleeperPlayerV2;
    }

    private static HashSet<SleeperPlayerV2> sleeperPlayersV2 = new HashSet<>();

    public static String getTodaysWebPage(){
        return InOutUtilitiesV2.getTodaysWebPage(WEB_URL, FILEPATH_START);
    }

    static{
        String entireHTML = getTodaysWebPage();
        intializeAllPlayers();
    }



    public static HashSet<SleeperPlayerV2> intializeAllPlayers() {
        File f = new File("./sleeperDataPlayerAPI.json");
        try {
            if(!f.exists() || f.isDirectory()) {
                downloadRawPlayerMetaData();
            }
            sleeperPlayersV2 = cleanRawPlayerMetaData();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return sleeperPlayersV2;
    }
    private static void downloadRawPlayerMetaData() throws IOException {
        String webURL = "https://api.sleeper.app/v1/players/nfl";
        String allData = WebUrlUtilityV2.urlToString(webURL);


        try (PrintWriter out = new PrintWriter("sleeperDataPlayerAPI.json")) {
            out.println(allData);
        }
    }

    public static HashSet<SleeperPlayerV2> cleanRawPlayerMetaData() throws IOException {

        JsonParser parser = new JsonParser();

        Object obj = parser.parse(new FileReader("sleeperDataPlayerAPI.json"));
        JsonObject jsonObject = (JsonObject) obj;

        Set<String> keySet = jsonObject.keySet();
        HashSet<SleeperPlayerV2> sleeperPlayersV2 = new HashSet<>();

        for (String key : keySet) {

            JsonObject playerJson = (JsonObject) jsonObject.get(key);

            String sleeperID = "";
            if(playerJson.get("player_id") != null && (!playerJson.get("player_id").isJsonNull())) {
                sleeperID = playerJson.get("player_id").getAsString();
            }

            String firstName = "";
            if(playerJson.get("first_name") != null && (!playerJson.get("first_name").isJsonNull())){
                firstName = playerJson.get("first_name").getAsString();
            }

            String lastName = "";
            if(playerJson.get("last_name") != null && (!playerJson.get("last_name").isJsonNull())){
                lastName = playerJson.get("last_name").getAsString();
            }
            String team = "";

            if(playerJson.get("team") != null && (!playerJson.get("team").isJsonNull())){
                team = playerJson.get("team").getAsString();
            }
            HashSet<Position> positions =  new HashSet();
            if(playerJson.get("fantasy_positions") != null && (!playerJson.get("fantasy_positions").isJsonNull())){
                for(JsonElement positionElement : playerJson.get("fantasy_positions").getAsJsonArray()){
                    String currentPos = positionElement.getAsString();
                    if(Position.isStandardPosition(currentPos)){
                        positions.add(Position.valueOf(currentPos));
                    }
                }
                if(positions.size()==0){
                    positions.add(Position.OTHER);
                }
            }
            SleeperPlayerV2 sleeperPlayerV2 = SleeperPlayerV2.playerFromSleeper(sleeperID, firstName, lastName, team, positions);
            sleeperPlayersV2.add(sleeperPlayerV2);
        }
        return sleeperPlayersV2;
    }



}
