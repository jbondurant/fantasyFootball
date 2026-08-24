import PlayerImportAndSetup.Position;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Rest-of-season projections from FantasyPros CSV exports saved next to the
 * project, e.g. FantasyPros_2026_Ros_QB_Rankings.csv.
 *
 * These are downloaded by hand from a FantasyPros account, so nothing here can
 * fetch them for you; the season prefix follows the configured league instead of
 * being frozen at 2023. Loading is lazy - selecting a different projection
 * source no longer requires the files to be present.
 */
public class CSVProjectionsFP {

    private static String filepathStart(String position){
        return "FantasyPros_" + AAAConfiguration.getInstance().getSeason() + "_Ros_" + position + "_Rankings";
    }

    private static ArrayList<Score> projectionsFPQB;
    private static ArrayList<Score> projectionsFPFlex;
    private static ArrayList<Score> projectionsFPDEF;

    private static synchronized void initialize(){
        if(projectionsFPQB != null){
            return;
        }
        ArrayList<Score> flex = new ArrayList<>();
        flex.addAll(parseCsvAny(filepathStart("RB"), Position.RB));
        flex.addAll(parseCsvAny(filepathStart("WR"), Position.WR));
        flex.addAll(parseCsvAny(filepathStart("TE"), Position.TE));
        projectionsFPFlex = flex;
        projectionsFPDEF = parseCsvAny(filepathStart("DST"), Position.DEF);
        projectionsFPQB = parseCsvAny(filepathStart("QB"), Position.QB);
    }

    public static ArrayList<Score> getQBProjections(){
        initialize();
        return projectionsFPQB;
    }
    public static ArrayList<Score> getFlexProjections(){
        initialize();
        return projectionsFPFlex;
    }
    public static ArrayList<Score> getDEFProjections(){
        initialize();
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
        initialize();
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
        try {
            return Files.readString(Path.of(filePath));
        } catch (IOException e) {
            throw new RuntimeException("missing " + filePath
                    + " - export it from FantasyPros and drop it in the project root", e);
        }
    }


    public static void main(String[] args){
        System.out.println("QB rows: " + getQBProjections().size());
    }
}
