import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mongodb.util.Hash;

import java.util.ArrayList;
import java.util.HashMap;

public class InSeasonProjectionsFP {


    public static String filepathStartQB = "fantasyProsProjectionInSeasonQB";
    public static String filepathStartRBHalf = "fantasyProsProjectionInSeasonRBHalf";
    public static String filepathStartWRHalf = "fantasyProsProjectionInSeasonWRHalf";
    public static String filepathStartTEHalf = "fantasyProsProjectionInSeasonTEHalf";
    public static String filepathStartDEF = "fantasyProsProjectionInSeasonDEF";


    public static String webURLQB = "https://www.fantasypros.com/nfl/rankings/ros-qb.php";
    public static String webURLRBHalf = "https://www.fantasypros.com/nfl/rankings/ros-half-point-ppr-rb.php";
    public static String webURLWRHalf = "https://www.fantasypros.com/nfl/rankings/ros-half-point-ppr-wr.php";
    public static String webURLTEHalf = "https://www.fantasypros.com/nfl/rankings/ros-half-point-ppr-te.php";
    public static String webURLDEF = "https://www.fantasypros.com/nfl/rankings/ros-dst.php";

    private static final ArrayList<Score> projectionsFPQB;
    private static final ArrayList<Score> projectionsFPFlex;
    private static final ArrayList<Score> projectionsFPDEF;

    static{
        projectionsFPQB = parseTodaysWebPageQB();
        ArrayList<Score> projectionsFPRB = parseTodaysWebPageRB();
        ArrayList<Score> projectionsFPWR = parseTodaysWebPageWR();
        ArrayList<Score> projectionsFPTE = parseTodaysWebPageTE();
        projectionsFPFlex = new ArrayList<>();
        projectionsFPFlex.addAll(projectionsFPRB);
        projectionsFPFlex.addAll(projectionsFPWR);
        projectionsFPFlex.addAll(projectionsFPTE);
        projectionsFPDEF = parseTodaysWebPageDEF();
    }

    public static ArrayList<Score> getQBProjections(){
        return projectionsFPQB;
    }
    public static ArrayList<Score> getFlexProjections(){
        return projectionsFPFlex;
    }
    public static ArrayList<Score> getDEFProjections(){
        return projectionsFPDEF;
    }


    private static String getTodaysWebPageQB(){
        return InOutUtilities.getTodaysWebPage(webURLQB, filepathStartQB);
    }
    private static String getTodaysWebPageRBHalf(){
        return InOutUtilities.getTodaysWebPage(webURLRBHalf, filepathStartRBHalf);
    }
    private static String getTodaysWebPageWRHalf(){
        return InOutUtilities.getTodaysWebPage(webURLWRHalf, filepathStartWRHalf);
    }
    private static String getTodaysWebPageTEHalf(){
        return InOutUtilities.getTodaysWebPage(webURLTEHalf, filepathStartTEHalf);
    }

    private static String getTodaysWebPageDEF(){
        return InOutUtilities.getTodaysWebPage(webURLDEF, filepathStartDEF);
    }


    //bad practice
    private static ArrayList<Score> parseTodaysWebPageQB() {
        return parseTodaysWebPageAny(0);
    }
    private static ArrayList<Score> parseTodaysWebPageRB() {
        return parseTodaysWebPageAny(1);
    }
    private static ArrayList<Score> parseTodaysWebPageWR() {
        return parseTodaysWebPageAny(2);
    }
    private static ArrayList<Score> parseTodaysWebPageTE() {
        return parseTodaysWebPageAny(3);
    }
    private static ArrayList<Score> parseTodaysWebPageDEF() {
        return parseTodaysWebPageAny(4);
    }

    private static ArrayList<Score> parseTodaysWebPageAny(int whatPage) {

        ArrayList<Score> projections = new ArrayList<>();

        String entireHTML = "";
        if(whatPage == 0) {
            entireHTML = getTodaysWebPageQB();
        }
        else if(whatPage == 1) {
            entireHTML = getTodaysWebPageRBHalf();
        }
        else if(whatPage == 2){
            entireHTML = getTodaysWebPageWRHalf();
        }
        else if (whatPage == 3){
            entireHTML = getTodaysWebPageTEHalf();
        }
        else{
            entireHTML = getTodaysWebPageDEF();
        }


        String ecrDataStart = entireHTML.split("var ecrData = ")[1].split("\"players\":")[1];
        String ecrData = ecrDataStart.split("var sosData")[0].split(",\"experts_available\":")[0];

        JsonParser jp = new JsonParser();
        JsonElement jsonElement = jp.parse(ecrData);
        JsonArray jsonPlayers = jsonElement.getAsJsonArray();

        for (JsonElement jsonPlayer : jsonPlayers) {
            JsonObject apiObject = jsonPlayer.getAsJsonObject();

            String sportRadarID = "";
            if(!apiObject.get("sportsdata_id").isJsonNull()) {
                sportRadarID = apiObject.get("sportsdata_id").getAsString();
            }

            if(apiObject.get("r2p_pts") == null){
                Player playerX = Player.getPlayer(sportRadarID);
                //maybe remove?
                Score tempScore = new Score(0.0, playerX);
                projections.add(tempScore);
                continue;
            }

            double rosProj = apiObject.get("r2p_pts").getAsDouble();
            Player player = Player.getPlayer(sportRadarID);
            Score score = new Score(rosProj, player);
            projections.add(score);
        }
        return projections;
    }


    public static HashMap<String, Double> playerToScoreProjFPROS(boolean is6ptsThrow){
        HashMap<String, Double> toReturn = new HashMap<>();
        for(Score score : projectionsFPQB){
            if(score != null && score.player != null) {
                //TODO correct for 6pts per qb
                double scoreToEnter = score.score;
                if(is6ptsThrow){
                    scoreToEnter = scoreToEnter * 1.18;
                }
                toReturn.put(score.player.sportRadarID, scoreToEnter);
            }
        }
        for(Score score : projectionsFPFlex){
            if(score != null && score.player != null) {
                toReturn.put(score.player.sportRadarID, score.score);
            }
        }
        for(Score score : projectionsFPDEF){
            if(score != null && score.player != null) {
                toReturn.put(score.player.sportRadarID, score.score);
            }
        }
        return toReturn;
    }





    public static void main(String[] args){
        //ArrayList<QBProjection> projectionsQB = parseTodaysWebPageQB();
        //ArrayList<FlexProjection> projectionsFlex = parseTodaysWebPageAny();
        //ArrayList<DEFProjection> projectionsDEF = parseTodaysWebPageDEF();
    }

}
