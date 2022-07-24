import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.LocalDate;
import java.util.ArrayList;

public class SleeperADP {

    public static int year = LocalDate.now().getYear();
    public static String filepathStart = "sleeperADPRanking";
    public static String webURLBoth = "https://api.sleeper.app/projections/nfl/" + year + "?season_type=regular&position[]=DEF&position[]=QB&position[]=RB&position[]=TE&position[]=WR&order_by=pts_half_ppr";
    public static ArrayList<DecimalRank> playerRankFun = new ArrayList<DecimalRank>();
    public static ArrayList<DecimalRank> playerRankSerious = new ArrayList<DecimalRank>();

    static{
        initializeBothRanks();
    }



    private static String getTodaysWebPage(){
        return InOutUtilities.getTodaysWebPage(webURLBoth, filepathStart);
    }

    public static void initializeBothRanks(){
        ArrayList<DecimalRank> prFun = new ArrayList<DecimalRank>();
        ArrayList<DecimalRank> prSerious = new ArrayList<DecimalRank>();

        String webData = getTodaysWebPage();

        JsonParser jp = new JsonParser();
        JsonElement jsonElement = jp.parse(webData);
        JsonArray jsonPlayers = jsonElement.getAsJsonArray();


        for (JsonElement jsonPlayer : jsonPlayers) {
            JsonObject apiObject = jsonPlayer.getAsJsonObject();

            //String fullName = apiObject.get("name").getAsString();
            //String position = apiObject.get("position").getAsString();
            String team = "";
            if(!apiObject.get("team").isJsonNull()) {
                team = apiObject.get("team").getAsString();
            }

            JsonObject stats = apiObject.getAsJsonObject("stats");

            double adpHalfPPR = 1000.0;
            double adp2QB = 1000.0;
            if(stats.has("adp_half_ppr")){
                if(!stats.get("adp_half_ppr").isJsonNull()) {
                    adpHalfPPR = stats.get("adp_half_ppr").getAsDouble();
                }
            }

            if(stats.has("adp_2qb")){
                if(!stats.get("adp_2qb").isJsonNull()) {
                    adp2QB = stats.get("adp_2qb").getAsDouble();
                }
            }

            JsonObject playerInfo = apiObject.getAsJsonObject("player");
            String position = playerInfo.get("position").getAsString();

            Player player;



            if(!position.equals("DEF")) {
                 int playerSID = apiObject.get("player_id").getAsInt();
                 player = Player.getPlayerFromSID(playerSID);

            }
            else{
                player = Player.getPlayerDefense(team);
            }
            if(player == null){
                int p=0;
            }
            else {
                DecimalRank rankFun = new DecimalRank(adp2QB, player);
                DecimalRank rankSerious = new DecimalRank(adpHalfPPR, player);
                prFun.add(rankFun);
                prSerious.add(rankSerious);
            }
        }
        playerRankFun = prFun;
        playerRankSerious = prSerious;
    }


    public static void main(String[] args){
        initializeBothRanks();
    }
}
