import PlayerImportAndSetup.Position;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;


public class Player {
    public String firstName;
    public String lastName;
    public String team;
    public Position position;

    public int yahooID;
    public int sleeperID;
    public String sleeperIDString;
    public String sportRadarID;
    public int fantasyProsID;

    private static ArrayList<Player> draftablePlayers = new ArrayList<Player>();
    private static HashMap<String, Player> playersFromSRID = new HashMap<>();
    private static HashMap<Integer, Player> playerMapSleeperOffense = new HashMap<Integer, Player>();
    private static HashMap<String, Player> playerMapInfo = new HashMap<String, Player>();
    private static HashMap<String, Player> playerMapFullNameInfo = new HashMap<String, Player>();
    private static HashMap<String, Player> playerDefenseMap = new HashMap<String, Player>();

    // FantasyPros dropped the shared "sportsdata_id" in 2025, so their pages are
    // now joined to Sleeper on name + team + position instead.
    private static HashMap<String, Player> playersByNameTeamPosition = new HashMap<>();
    private static HashMap<String, Player> playersByNamePosition = new HashMap<>();
    private static HashMap<String, Player> playersByNameTeam = new HashMap<>();
    private static HashSet<String> ambiguousNamePositions = new HashSet<>();
    private static HashSet<String> ambiguousNameTeams = new HashSet<>();

    /**
     * FantasyPros publishes some players under a nickname. Left-hand side is the
     * normalized FantasyPros name, right-hand side the normalized Sleeper name.
     */
    private static final HashMap<String, String> FANTASY_PROS_NAME_ALIASES = new HashMap<>();
    static {
        FANTASY_PROS_NAME_ALIASES.put("hollywoodbrown", "marquisebrown");
        FANTASY_PROS_NAME_ALIASES.put("juicewells", "antwanewells");
        FANTASY_PROS_NAME_ALIASES.put("chiptrayanum", "deamontetrayanum");
        FANTASY_PROS_NAME_ALIASES.put("bamknight", "zonovanknight");
    }

    private static ArrayList<Player> allPlayersCache = null;

    private static final Set<String> NAME_SUFFIXES = Set.of("jr", "sr", "ii", "iii", "iv", "v");

    private static boolean indexed = false;

    /**
     * Building the indexes needs the Sleeper player list, i.e. the network.
     * It used to happen in a static initialiser, which meant merely mentioning
     * this class - constructing a Player, reading a constant - pulled 14MB down
     * before anything else could run, and made the scoring and roster logic
     * impossible to test offline. Lookups now index on first use; construction
     * and the pure name-matching helpers need nothing.
     */
    private static synchronized void ensureIndexed(){
        if(indexed){
            return;
        }
        indexed = true;
        initializePlayers();
        initializePlayersForNameSearch();
        initializePlayerDefenseMap();
        initializeFantasyProsNameIndex();
    }

    /** Every player Sleeper knows about. */
    public static ArrayList<Player> getDraftablePlayers(){
        ensureIndexed();
        return draftablePlayers;
    }

    /**
     * Seam for tests: index a handmade player list instead of Sleeper's, so the
     * lookup logic can be exercised without the network.
     */
    static synchronized void indexForTest(ArrayList<Player> players){
        allPlayersCache = players;
        indexed = false;
        ensureIndexed();
    }

    /** Drops anything indexForTest set up. */
    static synchronized void resetIndexForTest(){
        allPlayersCache = null;
        indexed = false;
        playersFromSRID = new HashMap<>();
        playerMapSleeperOffense = new HashMap<>();
        playerMapInfo = new HashMap<>();
        playerMapFullNameInfo = new HashMap<>();
        playerDefenseMap = new HashMap<>();
        playersByNameTeamPosition = new HashMap<>();
        playersByNamePosition = new HashMap<>();
        playersByNameTeam = new HashMap<>();
        ambiguousNamePositions = new HashSet<>();
        ambiguousNameTeams = new HashSet<>();
        draftablePlayers = new ArrayList<>();
    }

    /**
     * The Sleeper player dump is 14MB and was being re-read and re-parsed once
     * per initializer. Read it once.
     */
    private static synchronized ArrayList<Player> allPlayers(){
        if(allPlayersCache == null){
            try {
                allPlayersCache = PlayerRawData.getPlayerMetaData();
            } catch (IOException e) {
                throw new RuntimeException("could not load the sleeper player list", e);
            }
        }
        return allPlayersCache;
    }

    public static ArrayList<Player> getCopyOfList(ArrayList<Player> draftedPlayers) {
        ArrayList<Player> copy = new ArrayList<>();
        for(Player p : draftedPlayers){
            copy.add(getCopy(p));
        }
        return copy;
    }

    public static Player getCopy(Player player){
        return new Player(player.firstName,
                player.lastName,
                player.team,
                player.position,
                player.yahooID,
                player.sleeperID,
                player.sportRadarID,
                player.fantasyProsID,
                player.sleeperIDString);
    }

    public Player(String fn, String ln, String t, Position p, int yID, int sID, String srID, int fpID, String sIDString){
        firstName = fn;
        lastName = ln;
        team = t;
        position = p;
        yahooID = yID;
        sleeperID = sID;
        sportRadarID = srID;
        fantasyProsID = fpID;
        sleeperIDString = sIDString;
    }

    public static Player getPlayer(String sportRadar_ID){
        ensureIndexed();
        Player player = playersFromSRID.get(sportRadar_ID);
        return player;
    }

    public static Player getPlayerFromSID(int sleeper_ID){
        ensureIndexed();
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
            return getPlayerDefense(sleeperID);
        }
    }

    public static Player getPlayerFromNameAndPos(String allName, Position position){
        ensureIndexed();
        allName = allName.replace(".", "").toLowerCase();
        if(allName.endsWith(" ii") || allName.endsWith(" iii") || allName.endsWith(" v") || allName.endsWith(" jr") || allName.endsWith(" sr")){
            allName = allName.substring(0, allName.lastIndexOf(" "));
        }
        String info = allName + position.toString().toLowerCase();
        Player p = playerMapFullNameInfo.get(info);
        if(p == null){
            if(position.equals(Position.DEF)) {
                p = getPlayerDefense(allName);
            }
        }
        if(p == null){
            // The name/team/position matcher handles renames and nicknames.
            p = getPlayerFromFantasyPros(allName, null, position);
        }
        return p;
    }

    public static Player getPlayerFromInfo(String lastName, String firstName, String pos, String team){
        ensureIndexed();
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
        ensureIndexed();
        return playerDefenseMap.get(team);
    }


    public static void initializePlayers() {
        HashMap<String, Player> playerMap = new HashMap<String, Player>();
        HashMap<Integer, Player> playerMapFP = new HashMap<Integer, Player>();
        HashMap<Integer, Player> playerMapSO = new HashMap<Integer, Player>();
        ArrayList<Player> allPlayers = allPlayers();
        {
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

        ArrayList<Player> allPlayers = allPlayers();
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
        ArrayList<Player> allPlayers = allPlayers();
        for(Player player : allPlayers){
            if(player.position.equals(Position.DEF)){
                playerMapFromDef.put(player.team, player);
            }
        }
        playerDefenseMap = playerMapFromDef;
    }

    /**
     * Look a FantasyPros row up in the Sleeper player list.
     *
     * Tries name + position + team first, then falls back to name + position
     * when the two sites disagree about a player's team (which happens all
     * offseason). A bare name+position that maps to more than one Sleeper
     * player is treated as no match rather than a coin flip.
     */
    public static Player getPlayerFromFantasyPros(String fullName, String team, Position position){
        if(fullName == null || position == null){
            return null;
        }
        ensureIndexed();
        if(position.equals(Position.DEF)){
            return getPlayerDefense(normalizeTeam(team));
        }
        String name = normalizeName(fullName);
        name = FANTASY_PROS_NAME_ALIASES.getOrDefault(name, name);
        String normalizedTeam = normalizeTeam(team);

        Player player = playersByNameTeamPosition.get(nameTeamPositionKey(name, normalizedTeam, position));
        if(player != null){
            return player;
        }

        String namePositionKey = namePositionKey(name, position);
        if(!ambiguousNamePositions.contains(namePositionKey)){
            player = playersByNamePosition.get(namePositionKey);
            if(player != null){
                return player;
            }
        }

        // Last resort: the two sites sometimes disagree about a player's
        // position (a converted TE listed as a WR, say). Name plus team is a
        // strong enough signal on its own.
        if(normalizedTeam.isEmpty()){
            return null;
        }
        String nameTeamKey = nameTeamKey(name, normalizedTeam);
        if(ambiguousNameTeams.contains(nameTeamKey)){
            return null;
        }
        return playersByNameTeam.get(nameTeamKey);
    }

    private static void initializeFantasyProsNameIndex(){
        HashMap<String, Player> byNameTeamPosition = new HashMap<>();
        HashMap<String, Player> byNamePosition = new HashMap<>();
        HashMap<String, Player> byNameTeam = new HashMap<>();
        HashSet<String> ambiguous = new HashSet<>();
        HashSet<String> ambiguousTeams = new HashSet<>();

        for(Player player : allPlayers()){
            if(player.position.equals(Position.OTHER) || player.position.equals(Position.DEF)){
                continue;
            }
            String name = normalizeName(player.firstName + " " + player.lastName);
            if(name.isEmpty()){
                continue;
            }
            String team = normalizeTeam(player.team);

            byNameTeamPosition.putIfAbsent(nameTeamPositionKey(name, team, player.position), player);

            if(!team.isEmpty()){
                String nameTeamKey = nameTeamKey(name, team);
                Player sameNameAndTeam = byNameTeam.putIfAbsent(nameTeamKey, player);
                if(sameNameAndTeam != null && !sameNameAndTeam.sleeperIDString.equals(player.sleeperIDString)){
                    ambiguousTeams.add(nameTeamKey);
                }
            }

            String namePositionKey = namePositionKey(name, player.position);
            Player existing = byNamePosition.get(namePositionKey);
            if(existing == null){
                byNamePosition.put(namePositionKey, player);
            }
            else if(!existing.sleeperIDString.equals(player.sleeperIDString)){
                // Prefer whichever of the two is actually on a roster; only give
                // up when both are.
                boolean existingHasTeam = !normalizeTeam(existing.team).isEmpty();
                boolean playerHasTeam = !team.isEmpty();
                if(playerHasTeam && !existingHasTeam){
                    byNamePosition.put(namePositionKey, player);
                }
                else if(playerHasTeam == existingHasTeam){
                    ambiguous.add(namePositionKey);
                }
            }
        }

        playersByNameTeamPosition = byNameTeamPosition;
        playersByNamePosition = byNamePosition;
        playersByNameTeam = byNameTeam;
        ambiguousNamePositions = ambiguous;
        ambiguousNameTeams = ambiguousTeams;
    }

    private static String nameTeamPositionKey(String name, String team, Position position){
        return name + "|" + team + "|" + position.name();
    }

    private static String namePositionKey(String name, Position position){
        return name + "|" + position.name();
    }

    private static String nameTeamKey(String name, String team){
        return name + "|" + team;
    }

    /** "Marvin Harrison Jr." and "Marvin Harrison" have to land on the same key. */
    static String normalizeName(String fullName){
        if(fullName == null){
            return "";
        }
        String cleaned = fullName.toLowerCase(Locale.ROOT)
                .replace("'", "")
                .replace("\u2019", "")
                .replace(".", " ");
        String[] tokens = cleaned.split("[^a-z]+");
        StringBuilder builder = new StringBuilder();
        List<String> kept = new ArrayList<>();
        for(String token : tokens){
            if(!token.isEmpty()){
                kept.add(token);
            }
        }
        // Only a trailing suffix is dropped - "Vernon V" keeps its surname.
        while(kept.size() > 2 && NAME_SUFFIXES.contains(kept.get(kept.size() - 1))){
            kept.remove(kept.size() - 1);
        }
        for(String token : kept){
            builder.append(token);
        }
        return builder.toString();
    }

    /** FantasyPros says JAC and FA where Sleeper says JAX and "". */
    static String normalizeTeam(String team){
        if(team == null){
            return "";
        }
        String normalized = team.trim().toUpperCase(Locale.ROOT);
        if(normalized.equals("FA")){
            return "";
        }
        if(normalized.equals("JAC")){
            return "JAX";
        }
        if(normalized.equals("OAK")){
            return "LV";
        }
        return normalized;
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


