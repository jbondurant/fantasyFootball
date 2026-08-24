import java.util.ArrayList;
import java.util.List;

/**
 * The "only show me trades involving X" knobs at the top of TradeFinder.main.
 *
 * These were four near-identical loops that each pulled a first and last name
 * out of the configured string with `name.split(" ")[0]` and `[1]` and compared
 * them field by field. That is wrong for any name that is not exactly two
 * words: "Amon-Ra St. Brown" split that way looks for a surname of "St.", and
 * Sleeper records the surname as "St. Brown", so the filter silently matched
 * nobody. A one word entry threw ArrayIndexOutOfBoundsException.
 *
 * Names are compared through Player.normalizeName, which is what the
 * FantasyPros join already uses: it strips punctuation and generational
 * suffixes, so "Amon-Ra St. Brown", "amon-ra st brown" and "Marvin Harrison"
 * for "Marvin Harrison Jr." all match.
 */
public class TradeFilter {

    /** Everyone team 1 would be giving up in this trade. */
    public static List<Player> playersGiven(TradePreviewSerious trade){
        return playersOf(trade.t1p1Score, trade.t1p2Score, trade.t1p3Score);
    }

    /** Everyone team 1 would be getting back. */
    public static List<Player> playersReceived(TradePreviewSerious trade){
        return playersOf(trade.t2p1Score, trade.t2p2Score, trade.t2p3Score);
    }

    private static List<Player> playersOf(Score... scores){
        List<Player> players = new ArrayList<>();
        for(Score score : scores){
            if(score != null && score.player != null){
                players.add(score.player);
            }
        }
        return players;
    }

    public static boolean includes(List<Player> players, String playerName){
        String wanted = Player.normalizeName(playerName);
        if(wanted.isEmpty()){
            return false;
        }
        for(Player player : players){
            if(Player.normalizeName(player.firstName + " " + player.lastName).equals(wanted)){
                return true;
            }
        }
        return false;
    }

    public static boolean includesAll(List<Player> players, List<String> playerNames){
        for(String playerName : playerNames){
            if(!includes(players, playerName)){
                return false;
            }
        }
        return true;
    }

    public static boolean includesAny(List<Player> players, List<String> playerNames){
        for(String playerName : playerNames){
            if(includes(players, playerName)){
                return true;
            }
        }
        return false;
    }

}
