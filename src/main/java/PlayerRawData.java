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

        int firstCount = 0;
        int lastCount  = 0;
        int teamCount = 0;
        int posCount = 0;
        int yaCount = 0;
        int sleeCount = 0;
        int srCount = 0;

        int playerCount = 0;

        for (String key : keySet) {

            playerCount++;
            JsonObject playerJson = (JsonObject) jsonObject.get(key);

            String firstName = "";
            if(!playerJson.get("first_name").isJsonNull()){
                firstCount++;
                firstName = playerJson.get("first_name").getAsString();
            }

            String lastName = "";
            if(!playerJson.get("last_name").isJsonNull()){
                lastCount++;
                lastName = playerJson.get("last_name").getAsString();
            }
            String team = "";
            if(!playerJson.get("team").isJsonNull()){
                teamCount++;
                team = playerJson.get("team").getAsString();
            }
            String positionString = "";
            if(!playerJson.get("fantasy_positions").isJsonNull()){
                posCount++;
                positionString = playerJson.get("fantasy_positions").getAsJsonArray().get(0).getAsString();
            }
            Position position = Position.OTHER;
            if(Position.isStandardPosition(positionString)){
                position = Position.valueOf(positionString);
            }

            int yahooID = -1;
            int sleeperID = -1;
            String sportRadarID = "";
            int fpID = -1;
            if(!position.equals(Position.DEF)) {
                if (!playerJson.get("yahoo_id").isJsonNull()) {
                    yaCount++;
                    yahooID = playerJson.get("yahoo_id").getAsInt();
                }
                if (!playerJson.get("player_id").isJsonNull()) {
                    sleeCount++;
                    sleeperID = playerJson.get("player_id").getAsInt();
                }
                if (!playerJson.get("sportradar_id").isJsonNull()) {
                    srCount++;
                    sportRadarID = playerJson.get("sportradar_id").getAsString();
                }
                fpID = FantasyProsUtility.getFPID(sportRadarID);
            }
            else{
                String xyz = team;
                sportRadarID = DefenseUtility.getDefenseID(team);
                //System.out.println(team + "\t" + sportRadarID);
            }
            //System.out.println(firstName + "\t" + lastName);
            if(lastName.equals("Coughlin")){
                int r=1;
            }


            Player player = new Player(firstName, lastName, team, position, yahooID, sleeperID, sportRadarID, fpID);
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
