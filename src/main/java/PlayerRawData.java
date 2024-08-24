import PlayerImportAndSetup.Position;
import com.google.gson.*;

import java.io.*;
import java.util.*;

public class PlayerRawData {


    public static void main(String[] args) throws IOException {
        getPlayerMetaData();
        int a = 3;
    }





    private static void downloadRawPlayerMetaData() throws IOException {
        String webURL = "https://api.sleeper.app/v1/players/nfl";
        String allData = WebUrlUtility.urlToString(webURL);


        try (PrintWriter out = new PrintWriter("sleeperDataPlayerAPI.json")) {
            out.println(allData);
        }
    }

    public static ArrayList<Player> cleanRawPlayerMetaData() throws IOException {

        JsonParser parser = new JsonParser();

        Object obj = parser.parse(new FileReader("sleeperDataPlayerAPI.json"));
        JsonObject jsonObject = (JsonObject) obj;

        Set<String> keySet = jsonObject.keySet();
        ArrayList<Player> players = new ArrayList<Player>();




        for (String key : keySet) {

            JsonObject playerJson = (JsonObject) jsonObject.get(key);

            String firstName = "";
            if(!playerJson.get("first_name").isJsonNull()){
                firstName = playerJson.get("first_name").getAsString();
            }

            String lastName = "";
            if(!playerJson.get("last_name").isJsonNull()){
                lastName = playerJson.get("last_name").getAsString();
            }
            String team = "";
            if(!playerJson.get("team").isJsonNull()){
                team = playerJson.get("team").getAsString();
            }
            String positionString = "";
            if(!playerJson.get("fantasy_positions").isJsonNull()){
                positionString = playerJson.get("fantasy_positions").getAsJsonArray().get(0).getAsString();
            }
            Position position = Position.OTHER;
            if(Position.isStandardPosition(positionString)){
                position = Position.valueOf(positionString);
            }

            int yahooID = -1;
            int sleeperID = -1;
            String sIDString = "";
            String sportRadarID = "";
            int fpID = -1;
            if(!position.equals(Position.DEF)) {
                if (!playerJson.get("yahoo_id").isJsonNull()) {
                    yahooID = playerJson.get("yahoo_id").getAsInt();
                }
                if (!playerJson.get("player_id").isJsonNull()) {
                    sleeperID = playerJson.get("player_id").getAsInt();
                    sIDString = playerJson.get("player_id").getAsString();
                }
                if (!playerJson.get("sportradar_id").isJsonNull()) {
                    sportRadarID = playerJson.get("sportradar_id").getAsString();
                }
                //fpID = FantasyProsPlayersV2.getFPID(sportRadarID);
            }
            else{
                String xyz = team;
                sportRadarID = DefenseUtility.getDefenseID(team);
                if (!playerJson.get("player_id").isJsonNull()) {
                    sIDString = playerJson.get("player_id").getAsString();
                }
                //System.out.println(team + "\t" + sportRadarID);
            }
            //System.out.println(firstName + "\t" + lastName);
            if(lastName.equals("Coughlin")){
                int r=1;
            }


            Player player = new Player(firstName, lastName, team, position, yahooID, sleeperID, sportRadarID, fpID, sIDString);
            players.add(player);

        }
        return players;

    }


    public static ArrayList<Player> getPlayerMetaData() throws IOException {
        File f = new File("./sleeperDataPlayerAPI.json");
        if(!f.exists() || f.isDirectory()) {
            downloadRawPlayerMetaData();
        }
        return cleanRawPlayerMetaData();
    }





}
