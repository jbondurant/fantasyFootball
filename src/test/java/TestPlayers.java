import PlayerImportAndSetup.Position;

import java.util.ArrayList;

/** Handmade players, so the logic tests do not need Sleeper's 14MB dump. */
public final class TestPlayers {

    private TestPlayers(){}

    public static Player player(String firstName, String lastName, String team, Position position, int sleeperID){
        return new Player(firstName, lastName, team, position,
                -1, sleeperID, "sr-" + sleeperID, -1, String.valueOf(sleeperID));
    }

    /** Defenses are keyed by team abbreviation on both sides at Sleeper. */
    public static Player defense(String city, String nickname, String team){
        return new Player(city, nickname, team, Position.DEF, -1, -1, team, -1, team);
    }

    public static ArrayList<Player> listOf(Player... players){
        ArrayList<Player> list = new ArrayList<>();
        for(Player player : players){
            list.add(player);
        }
        return list;
    }
}
