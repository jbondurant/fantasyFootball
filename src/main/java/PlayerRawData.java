import PlayerImportAndSetup.Position;
import com.google.gson.*;

import java.io.*;
import java.util.*;

public class PlayerRawData {


    public static void main(String[] args) throws IOException {
        System.out.println("loaded " + getPlayerMetaData().size() + " players from sleeper");
    }





    private static void downloadRawPlayerMetaData() throws IOException {
        String webURL = "https://api.sleeper.app/v1/players/nfl";
        String allData = WebUrlUtility.urlToString(webURL);


        try (PrintWriter out = new PrintWriter("sleeperDataPlayerAPI.json")) {
            out.println(allData);
        }
    }

    public static ArrayList<Player> cleanRawPlayerMetaData() throws IOException {

        JsonObject jsonObject;
        try (FileReader reader = new FileReader("sleeperDataPlayerAPI.json")) {
            jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
        }

        Set<String> keySet = jsonObject.keySet();
        ArrayList<Player> players = new ArrayList<Player>();




        for (String key : keySet) {

            JsonObject playerJson = (JsonObject) jsonObject.get(key);

            String firstName = optionalString(playerJson, "first_name");
            String lastName = optionalString(playerJson, "last_name");
            String team = optionalString(playerJson, "team");
            Position position = readPosition(playerJson);

            int yahooID = -1;
            int sleeperID = -1;
            String sIDString = optionalString(playerJson, "player_id");
            String sportRadarID = "";
            int fpID = -1;
            if(!position.equals(Position.DEF)) {
                String yahoo = optionalString(playerJson, "yahoo_id");
                if(!yahoo.isEmpty()){
                    yahooID = Integer.parseInt(yahoo);
                }
                if(sIDString.matches("[0-9]+")){
                    sleeperID = Integer.parseInt(sIDString);
                }
                sportRadarID = optionalString(playerJson, "sportradar_id");
            }
            else{
                // A defense has no sportradar id; its team abbreviation is its id.
                sportRadarID = DefenseUtility.getDefenseID(team);
            }

            Player player = new Player(firstName, lastName, team, position, yahooID, sleeperID, sportRadarID, fpID, sIDString);
            players.add(player);

        }
        return players;

    }


    private static String optionalString(JsonObject object, String key){
        JsonElement element = object.get(key);
        if(element == null || element.isJsonNull()){
            return "";
        }
        return element.getAsString();
    }

    /**
     * Sleeper reports a player's real position in "position" and their fantasy
     * eligibility in "fantasy_positions". Reading only fantasy_positions[0] hid
     * anyone whose first eligibility is defensive - Travis Hunter came through
     * as ["DB","WR"] and so was filed as OTHER and never matched to a ranking.
     */
    private static Position readPosition(JsonObject playerJson){
        String position = optionalString(playerJson, "position");
        if(Position.isStandardPosition(position)){
            return Position.valueOf(position);
        }
        JsonElement fantasyPositions = playerJson.get("fantasy_positions");
        if(fantasyPositions != null && fantasyPositions.isJsonArray()){
            for(JsonElement candidate : fantasyPositions.getAsJsonArray()){
                if(candidate.isJsonNull()){
                    continue;
                }
                String fantasyPosition = candidate.getAsString();
                if(Position.isStandardPosition(fantasyPosition)){
                    return Position.valueOf(fantasyPosition);
                }
            }
        }
        return Position.OTHER;
    }

    public static ArrayList<Player> getPlayerMetaData() throws IOException {
        File f = new File("./sleeperDataPlayerAPI.json");
        if(!f.exists() || f.isDirectory()) {
            downloadRawPlayerMetaData();
        }
        return cleanRawPlayerMetaData();
    }





}
