import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.checkerframework.checker.units.qual.A;

import java.util.ArrayList;
import java.util.HashMap;

public class FantasyProsUtility {


    public static String filepathStart = "fantasyProsRankForID";
    public static String webURL = "https://www.fantasypros.com/nfl/rankings/half-point-ppr-cheatsheets.php";

    public static HashMap<String, Integer> sridToFPID = new HashMap<String, Integer>();
    public static HashMap<Integer, String> fpidToSRID = new HashMap<Integer, String>();

    public static RankOrderedPlayers rop = null;

    static{
        initializeMap();
    }

    public static int getFPID(String sr){
        int fpID = -1;
        if(sridToFPID.containsKey(sr)) {
            fpID = sridToFPID.get(sr);
        }
        return fpID;
    }

    public static String getSRID(int fp){
        String srID = "";
        if(fpidToSRID.containsKey(fp)) {
            srID = fpidToSRID.get(fp);
        }
        return srID;
    }

    public static String getTodaysWebPage(){
        return InOutUtilities.getTodaysWebPage(webURL, filepathStart);
    }


    private static void initializeMap(){
        ArrayList<Rank> rankingForHardcodedChosenExperts = new ArrayList<>();

        String entireHTML = getTodaysWebPage();
        String ecrDataStart = entireHTML.split("var ecrData = ")[1].split("\"players\":")[1];
        String ecrData = ecrDataStart.split("var sosData")[0].split(",\"experts_available\":")[0];

        JsonParser jp = new JsonParser();
        JsonElement jsonElement = jp.parse(ecrData);
        JsonArray jsonPlayers = jsonElement.getAsJsonArray();

        ArrayList<Rank> todaysRankings = new ArrayList<Rank>();
        for (JsonElement jsonPlayer : jsonPlayers) {
            JsonObject apiObject = jsonPlayer.getAsJsonObject();

            String sportRadarID = "";
            if(!apiObject.get("sportsdata_id").isJsonNull()) {
                sportRadarID = apiObject.get("sportsdata_id").getAsString();
            }

            int fantasyProsID = -1;
            if(!apiObject.get("player_id").isJsonNull()) {
                fantasyProsID = apiObject.get("player_id").getAsInt();
            }


            if(!apiObject.get("pos_rank").isJsonNull()) {
                String posRank = apiObject.get("pos_rank").getAsString();
                if(posRank.toLowerCase().startsWith("dst")){
                    posRank = posRank.substring(3);
                    int rank = Integer.parseInt(posRank);
                    Player player = Player.getPlayer(sportRadarID);// sport radar and sport data seem used interchangeably...
                    Rank r = new Rank(rank, player);
                    rankingForHardcodedChosenExperts.add(r);
                }
                else if(posRank.toLowerCase().startsWith("qb") || posRank.toLowerCase().startsWith("rb") || posRank.toLowerCase().startsWith("wr") || posRank.toLowerCase().startsWith("te")){
                    posRank = posRank.substring(2);
                    int rank = Integer.parseInt(posRank);
                    Player player = Player.getPlayer(sportRadarID);

                    Rank r = new Rank(rank, player);
                    rankingForHardcodedChosenExperts.add(r);
                }

            }

            sridToFPID.put(sportRadarID, fantasyProsID);
            fpidToSRID.put(fantasyProsID, sportRadarID);
        }
        rop = new RankOrderedPlayers(rankingForHardcodedChosenExperts);
    }


}
