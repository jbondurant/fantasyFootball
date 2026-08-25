import com.google.gson.JsonParser;

import java.util.Map;

/**
 * What round a player last cost, which is what they cost to keep.
 *
 * TradeFinder's round filter called a class named HardcodedDraftUtil that was
 * never committed, so the project did not compile. Rather than reinstating a
 * hand-typed table, the rounds are read from the previous season's draft.
 */
public class DraftRoundUtil {

    /** A player nobody drafted is treated as costing a last-round pick. */
    public static final int UNDRAFTED_ROUND = Keeper.UNDRAFTED_ROUND_COST;

    private static Map<String, Integer> sleeperIDToRound;

    private static synchronized Map<String, Integer> rounds(){
        if(sleeperIDToRound == null){
            java.util.List<com.google.gson.JsonArray> history =
                    AAAConfiguration.getInstance().getPreviousDraftPicks();
            sleeperIDToRound = history.isEmpty()
                    ? new java.util.HashMap<>()
                    : KeeperPricing.roundsByPlayerID(history.get(0));
        }
        return sleeperIDToRound;
    }

    public static int getRoundPlayer(Player player){
        if(player == null || player.sleeperIDString == null){
            return UNDRAFTED_ROUND;
        }
        return rounds().getOrDefault(player.sleeperIDString, UNDRAFTED_ROUND);
    }

}
