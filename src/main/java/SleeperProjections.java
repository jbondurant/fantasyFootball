import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.LocalDate;
import java.util.HashMap;

public class SleeperProjections {
    public static String webURL = "";
    public static String filepathStart = "sleeperProjections";


    static{
        LocalDate currentdate = LocalDate.now();
        int year = currentdate.getYear();
        int currentMonth = currentdate.getMonth().getValue();
        if(currentMonth <= 2){
            year--;
        }
        webURL = "https://api.sleeper.app/projections/nfl/" + year + "?season_type=regular&position[]=DEF&position[]=QB&position[]=RB&position[]=TE&position[]=WR&order_by=pts_half_ppr";

    }

    private static String getTodaysWebPage(){
        return InOutUtilities.getTodaysWebPage(webURL, filepathStart);
    }

    public static HashMap<String, Double> parseTodaysWebPage() {
        String entireHTML = getTodaysWebPage();

        JsonParser jp = new JsonParser();
        JsonArray jsonPlayers = jp.parse(entireHTML).getAsJsonArray();
        HashMap<String, Double> playerSIDToScore = new HashMap<>();
        for (JsonElement jsonPlayer : jsonPlayers) {
            JsonObject playerObject = jsonPlayer.getAsJsonObject();
            String sleeperID = playerObject.get("player_id").getAsString();
            Player player = Player.getPlayerFromSIDV2(sleeperID);
            JsonObject statsObject = playerObject.getAsJsonObject("stats");
            double pts = 0.0;
            if(statsObject.get("pts_half_ppr") != null) {
                pts = statsObject.get("pts_half_ppr").getAsDouble();
            }
            else{
                //System.out.println(player.firstName + " " + player.lastName);
            }
            double numPassTD = 0.0;
            if(statsObject.get("pass_td") != null) {
                numPassTD = statsObject.get("pass_td").getAsDouble();
            }
            pts += numPassTD * (6.0 - 4.0); //hardcoded 6pts per td
            Score score = new Score(pts, player);
            String sid = String.valueOf(player.sleeperIDString);
            //System.out.println(sid);
            if(sid == null || sid == ""){
                if(player.position.equals(Position.DEF)){
                    int a=1;
                }
                else {
                    int k = 1;
                }
            }
            playerSIDToScore.put(sid, score.score);
        }
        return playerSIDToScore;
    }
    public static void main(String[] args){
        parseTodaysWebPage();
    }

}
