import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;

public class SleeperADP {

    public static ArrayList<DecimalRank> playerRankFun = new ArrayList<DecimalRank>();
    public static ArrayList<DecimalRank> playerRankSerious = new ArrayList<DecimalRank>();

    static{
        initializeBothRanks();
    }

    public static void initializeBothRanks(){
        ArrayList<DecimalRank> prFun = new ArrayList<DecimalRank>();
        ArrayList<DecimalRank> prSerious = new ArrayList<DecimalRank>();

        // Same feed as SleeperProjections - the ADP fields ride along with the
        // stat projections, so there is no reason to fetch it twice.
        JsonArray jsonPlayers = SleeperProjections.getTodaysProjections();

        for (JsonElement jsonPlayer : jsonPlayers) {
            JsonObject apiObject = jsonPlayer.getAsJsonObject();

            JsonObject stats = apiObject.getAsJsonObject("stats");
            if(stats == null){
                continue;
            }

            // Undrafted players sort to the back of the board.
            double adpHalfPPR = adp(stats, "adp_half_ppr");
            double adp2QB = adp(stats, "adp_2qb");

            Player player = Player.getPlayerFromSIDV2(apiObject.get("player_id").getAsString());
            if(player != null) {
                DecimalRank rankFun = new DecimalRank(adp2QB, player);
                DecimalRank rankSerious = new DecimalRank(adpHalfPPR, player);
                prFun.add(rankFun);
                prSerious.add(rankSerious);
            }
        }
        playerRankFun = prFun;
        playerRankSerious = prSerious;
    }


    private static final double UNDRAFTED_ADP = 1000.0;

    private static double adp(JsonObject stats, String key){
        JsonElement element = stats.get(key);
        if(element == null || element.isJsonNull()){
            return UNDRAFTED_ADP;
        }
        return element.getAsDouble();
    }

    public static void main(String[] args){
        initializeBothRanks();
        System.out.println("half-ppr ADP for " + playerRankSerious.size() + " players");
    }
}
