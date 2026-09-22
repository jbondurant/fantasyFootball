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

    /**
     * How stale the player metadata may get before it is refetched.
     *
     * IT USED TO BE FOREVER. The condition was `if the file does not exist`, so
     * this fifteen-megabyte file was downloaded once and never again - on
     * 2026-09-07, week one, it was dated August 24th and would have stayed there
     * all season. Every name, position and team in the repo came from that
     * snapshot, which means a player who changed teams after the draft, or a man
     * added to a roster since, was either wrong or invisible: the waiver search
     * looks men up through here, so it cannot claim anybody it has never heard
     * of.
     *
     * It is not day-cached like the small feeds because fifteen megabytes a day
     * for data that changes weekly is a poor trade. A week is the compromise,
     * and unlike "never" it is a number that can be argued with.
     */
    static final int STALE_AFTER_DAYS = Integer.getInteger("playerMetaDays", 7);

    public static ArrayList<Player> getPlayerMetaData() throws IOException {
        File f = new File("./sleeperDataPlayerAPI.json");
        boolean missing = !f.exists() || f.isDirectory();
        boolean stale = !missing && f.lastModified()
                < System.currentTimeMillis() - STALE_AFTER_DAYS * 24L * 60 * 60 * 1000;
        if(missing || stale){
            System.out.println(missing ? "player metadata missing, downloading"
                    : "player metadata is more than " + STALE_AFTER_DAYS + " days old, refreshing");
            downloadRawPlayerMetaData();
        }
        return cleanRawPlayerMetaData();
    }





}
