import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;


public class Player {
    public String firstName;
    public String lastName;
    public String team;
    public Position position;

    public int yahooID;
    public int sleeperID;
    public String sportRadarID;
    public int fantasyProsID;

    public static ArrayList<Player> draftablePlayers = new ArrayList<Player>();
    private static HashMap<String, Player> playersFromSRID = new HashMap<>();
    private static HashMap<Integer, Player> playerMapSleeperOffense = new HashMap<Integer, Player>();
    private static HashMap<String, Player> playerMapInfo = new HashMap<String, Player>();
    private static HashMap<String, Player> playerMapFullNameInfo = new HashMap<String, Player>();
    private static HashMap<String, Player> playerDefenseMap = new HashMap<String, Player>();

    static{
        initializePlayers();
        initializePlayersForNameSearch();
        initializePlayerDefenseMap();

    }

    public Player(String fn, String ln, String t, Position p, int yID, int sID, String srID, int fpID){
        firstName = fn;
        lastName = ln;
        team = t;
        position = p;
        yahooID = yID;
        sleeperID = sID;
        sportRadarID = srID;
        fantasyProsID = fpID;
    }

    public static Player getPlayer(String sportRadar_ID){
        Player player = playersFromSRID.get(sportRadar_ID);
        return player;
    }

    public static Player getPlayerFromSID(int sleeper_ID){
        Player player = playerMapSleeperOffense.get(sleeper_ID);
        return player;
    }

    public static Player getPlayerFromSIDV2(String sleeperID){
        //System.out.println(sleeperID);
        boolean onlyDigits = sleeperID.matches("[0-9]+");
        if(onlyDigits){
            int sleeperIDAsInt = Integer.parseInt(sleeperID);
            Player x = getPlayerFromSID(sleeperIDAsInt);
            return x;
        }
        else{
            Player x =  getPlayerDefense(sleeperID);
            if(x == null){
                int k=1;
            }
            return x;
        }
    }

    public static Player getPlayerFromNameAndPos(String allName, Position position){
        allName = allName.replace(".", "").toLowerCase();
        if(allName.endsWith(" ii") || allName.endsWith(" iii") || allName.endsWith(" v") || allName.endsWith(" jr") || allName.endsWith(" sr")){
            allName = allName.substring(0, allName.lastIndexOf(" "));
        }
        if(allName.equals("isiah pacheco")){
            allName = "isaih pacheco";
        }
        if(allName.equals("gabe davis")){
            allName = "gabriel davis";
        }
        if(allName.equals("scotty miller")){
            allName = "scott miller";
        }
        //System.out.println(playerMapFullNameInfo.keySet());
        String info = allName + position.toString().toLowerCase();
        Player p = playerMapFullNameInfo.get(info);
        if(p == null){
            if(position.equals("DEF")) {
                p = getPlayerDefense(allName);
                System.out.println("defense not found: " + allName);
            }
            System.out.println("player not found: " + allName);
        }
        return p;
    }

    public static Player getPlayerFromInfo(String lastName, String firstName, String pos, String team){
        String info = lastName + pos + team;
        info = info.toLowerCase();
        Player p = playerMapInfo.get(info);
        if(p == null){
            if(pos.equals("DEF")) {
                p = getPlayerDefense(team);
            }
        }
        return p;
    }

    public static Player getPlayerDefense(String team){
        return playerDefenseMap.get(team);
    }


    public static void initializePlayers() {
        HashMap<String, Player> playerMap = new HashMap<String, Player>();
        HashMap<Integer, Player> playerMapFP = new HashMap<Integer, Player>();
        HashMap<Integer, Player> playerMapSO = new HashMap<Integer, Player>();
        ArrayList<Player> allPlayers = null;
        try {
            allPlayers = PlayerRawData.getPlayerMetaData();
        } catch (IOException e) {
            e.printStackTrace();
        }
        finally {
            int y = allPlayers.size();

            for (Player player : allPlayers) {
                String sportRadarID = player.sportRadarID;
                int fpID = player.fantasyProsID;
                int sIDNum = player.sleeperID;
                playerMap.put(sportRadarID, player);
                playerMapFP.put(fpID, player);
                playerMapSO.put(sIDNum, player);
            }
            playersFromSRID = playerMap;
            draftablePlayers = allPlayers;
            playerMapSleeperOffense = playerMapSO;
        }
    }

    public static void initializePlayersForNameSearch() {
        HashMap<String, Player> playerMapFromInfo = new HashMap<String, Player>();
        HashMap<String, Player> playerMapFromFullNameInfo = new HashMap<String, Player>();

        ArrayList<Player> allPlayers = null;
        try {
            allPlayers = PlayerRawData.getPlayerMetaData();
        } catch (IOException e) {
            e.printStackTrace();
        }
        for(Player player : allPlayers){
            String lastName = player.lastName;
            String pos = player.position.toString();
            String team = player.team;
            String info = lastName + pos + team;
            info = info.toLowerCase();
            playerMapFromInfo.put(info, player);

            String firstName = player.firstName;
            String info2 = firstName + " " + lastName + pos;
            info2 = info2.toLowerCase();
            info2 = info2.replace(".", "");
            if(pos.equals(Position.OTHER)){
                continue;
            }
            playerMapFromFullNameInfo.put(info2, player);
        }
        playerMapInfo = playerMapFromInfo;
        playerMapFullNameInfo = playerMapFromFullNameInfo;
    }

    public static void initializePlayerDefenseMap() {
        HashMap<String, Player> playerMapFromDef = new HashMap<String, Player>();
        ArrayList<Player> allPlayers = null;
        try {
            allPlayers = PlayerRawData.getPlayerMetaData();
        } catch (IOException e) {
            e.printStackTrace();
        }
        for(Player player : allPlayers){
            String pos = player.position.toString();
            if(pos == "DEF"){
                String team = player.team;
                playerMapFromDef.put(team, player);
            }
        }
        playerDefenseMap = playerMapFromDef;
    }

    public static double scorePlayer(ArrayList<Score> scoreList, Player p){
        for(Score score : scoreList){
            if(score.player != null && score.player.sportRadarID.equals(p.sportRadarID)){
                return score.score;
            }
        }
        return 0.0;
    }
}


