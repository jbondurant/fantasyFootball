import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.checkerframework.checker.units.qual.A;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

public class CSVProjectionsFP {

    public static String filepathStartQB = "FantasyPros_2022_Ros_QB_Rankings";
    public static String filepathStartRBHalf = "FantasyPros_2022_Ros_RB_Rankings";
    public static String filepathStartWRHalf = "FantasyPros_2022_Ros_WR_Rankings";
    public static String filepathStartTEHalf = "FantasyPros_2022_Ros_TE_Rankings";
    public static String filepathStartDEF = "FantasyPros_2022_Ros_DST_Rankings";

    private static final ArrayList<Score> projectionsFPQB;
    private static final ArrayList<Score> projectionsFPFlex;
    private static final ArrayList<Score> projectionsFPDEF;

    static{
        projectionsFPQB = parseCsvAny(filepathStartQB, Position.QB);
        ArrayList<Score> projectionsFPRB = parseCsvAny(filepathStartRBHalf, Position.RB);
        ArrayList<Score> projectionsFPWR = parseCsvAny(filepathStartWRHalf, Position.WR);
        ArrayList<Score> projectionsFPTE = parseCsvAny(filepathStartTEHalf, Position.TE);
        projectionsFPFlex = new ArrayList<>();
        projectionsFPFlex.addAll(projectionsFPRB);
        projectionsFPFlex.addAll(projectionsFPWR);
        projectionsFPFlex.addAll(projectionsFPTE);
        projectionsFPDEF = parseCsvAny(filepathStartDEF, Position.DEF);
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


    private static ArrayList<Score> parseCsvAny(String thePage, Position position) {

        ArrayList<Score> projections = new ArrayList<>();

        String entireHTML = readCsv(thePage);

        for (String playerString : entireHTML.split("\n")) {
            String rankString = playerString.split(",")[0].split("\"")[1];
            String nameString = playerString.split(",")[1].split("\"")[1];
            String projString = playerString.split(",")[5].split("\"")[1];
            if(rankString.equals("RK")){
                continue;
            }

            double proj = Double.valueOf(projString);
            //System.out.println(nameString);
            //System.out.println(proj);
            Player p = Player.getPlayerFromNameAndPos(nameString.toLowerCase(), position);


            Score score = new Score(proj, p);
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



    public static String readCsv(String filename){
        String filePath = "./" + filename + ".csv";
        String webpage = null;
        try {
            webpage = Files.readString(Path.of(filePath));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return webpage;
    }


    public static void main(String[] args){
        String s = readCsv(filepathStartQB);
    }
}
