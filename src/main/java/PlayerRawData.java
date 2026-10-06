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


    /**
     * Sleeper id -> birth date, for the men the database dates (about nine in
     * ten skill players). A man without one is absent, never given a default
     * age: FeatureScreen tests age only where it is known.
     */
    public static java.util.Map<String, java.time.LocalDate> birthDates() throws IOException {
        java.util.Map<String, java.time.LocalDate> out = new java.util.HashMap<>();
        JsonObject all;
        try (FileReader reader = new FileReader("sleeperDataPlayerAPI.json")) {
            all = JsonParser.parseReader(reader).getAsJsonObject();
        }
        for(String id : all.keySet()){
            String born = optionalString(all.getAsJsonObject(id), "birth_date");
            if(born.matches("\\d{4}-\\d{2}-\\d{2}")){
                out.put(id, java.time.LocalDate.parse(born));
            }
        }
        return out;
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
     * THEN IT WAS A WEEK, and in season a week is wrong too. The injury tag,
     * practice report and depth chart live in this file and move daily. On
     * 2026-09-28 it was from 2026-09-21 and still had Mike Evans Questionable
     * (hip) while Sleeper's projection feed, fetched that morning, had him Out
     * (ribs) - two pages of the same console reading two different weeks. So
     * while the regular season is on (LeagueWeek.inSeason) a file fetched on an
     * earlier calendar day is stale, the policy the small feeds use and the date
     * DataStamp prints; out of season the week stands, since fifteen megabytes
     * a day for a file that changes weekly is a poor trade. -PplayerMetaDays
     * overrides both.
     */
    static final int STALE_AFTER_DAYS = 7;

    /** Whether a file fetched on {@code fetched} must be refetched on {@code today}. */
    static boolean stale(java.time.LocalDate fetched, java.time.LocalDate today, boolean inSeason, Integer overrideDays){
        int days = overrideDays != null ? overrideDays : inSeason ? 1 : STALE_AFTER_DAYS;
        return !fetched.plusDays(days).isAfter(today);
    }

    /** In season by Sleeper's state; a state that cannot be read falls back to the weekly policy rather than failing the run. */
    private static boolean inSeasonOrWeekly(){
        try {
            return LeagueWeek.inSeason();
        }
        catch(RuntimeException unreadable){
            return false;
        }
    }

    /** The file, refetched first if it is missing or stale. */
    private static void ensureFresh() throws IOException {
        File f = new File("./sleeperDataPlayerAPI.json");
        boolean missing = !f.exists() || f.isDirectory();
        java.time.LocalDate fetched = missing ? null : java.time.Instant.ofEpochMilli(f.lastModified())
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        boolean inSeason = inSeasonOrWeekly();
        if(missing || stale(fetched, java.time.LocalDate.now(), inSeason, Integer.getInteger("playerMetaDays"))){
            System.out.println(missing ? "player metadata missing, downloading"
                    : "player metadata from " + fetched + " is stale ("
                            + (inSeason ? "daily in season" : "weekly out of season") + "), refreshing");
            downloadRawPlayerMetaData();
        }
    }

    public static ArrayList<Player> getPlayerMetaData() throws IOException {
        ensureFresh();
        return cleanRawPlayerMetaData();
    }

    /**
     * The whole player database as Sleeper serves it, id -> record, through the
     * same expiry. For the readers that need fields Player does not carry - the
     * injury tag, practice report, depth chart - and used to open the file
     * directly, which read whatever was on disk whether or not it had expired.
     */
    public static JsonObject database() throws IOException {
        ensureFresh();
        try (FileReader reader = new FileReader("sleeperDataPlayerAPI.json")) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }





}
