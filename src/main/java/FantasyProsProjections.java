import java.util.ArrayList;

public class FantasyProsProjections {


    public static String filepathStartQB = "fantasyProsProjectionQB";
    public static String filepathStartFlexHalf = "fantasyProsProjectionFlexHalf";
    public static String filepathStartDEF = "fantasyProsProjectionDEF";

    public static String webURLQB = "https://www.fantasypros.com/nfl/projections/qb.php?week=draft";
    public static String webURLFlexHalf = "https://www.fantasypros.com/nfl/projections/flex.php?week=draft&scoring=HALF&week=draft";
    public static String webURLDEF = "https://www.fantasypros.com/nfl/projections/dst.php?week=draft";



    private static final ArrayList<QBProjection> projectionsFPQB;
    private static final ArrayList<FlexProjection> projectionsFPFlex;
    private static final ArrayList<DEFProjection> projectionsFPDEF;

    static{
        projectionsFPQB = parseTodaysWebPageQB();
        projectionsFPFlex = parseTodaysWebPageFlex();
        projectionsFPDEF = parseTodaysWebPageDEF();
    }

    public static ArrayList<QBProjection> getQBProjections(){
        return projectionsFPQB;
    }
    public static ArrayList<FlexProjection> getFlexProjections(){
        return projectionsFPFlex;
    }
    public static ArrayList<DEFProjection> getDEFProjections(){
        return projectionsFPDEF;
    }


    private static String getTodaysWebPageQB(){
        return InOutUtilities.getTodaysWebPage(webURLQB, filepathStartQB);
    }
    private static String getTodaysWebPageFlexHalf(){
        return InOutUtilities.getTodaysWebPage(webURLFlexHalf, filepathStartFlexHalf);
    }
    private static String getTodaysWebPageDEF(){
        return InOutUtilities.getTodaysWebPage(webURLDEF, filepathStartDEF);
    }

    private static ArrayList<QBProjection> parseTodaysWebPageQB() {

        ArrayList<QBProjection> projections = new ArrayList<QBProjection>();

        String entireHTML = getTodaysWebPageQB();
        String[] lines = entireHTML.split("\r\n|\r|\n");
        String[] linesCleaned = new String[lines.length];
        for(int i = 0; i<lines.length; i++){
            linesCleaned[i] = lines[i].trim();
        }

        ArrayList<String> importantNumbers = new ArrayList<>();
        for(String line : linesCleaned){
            if(line.startsWith("<td class=\"center\">")){
                String cleanNumberStringStart = line.split("<td class=\"center\">")[1];
                String cleanNumberString = cleanNumberStringStart.split("</td>")[0];
                String noCommaString = cleanNumberString.replace(",", "");
                importantNumbers.add(noCommaString);
            }
            if(line.startsWith("<tr class=\"mpb-player-")){
                String cleanNumberStringStart = line.split("fp-id-")[1];
                String cleanNumberString = cleanNumberStringStart.split("\" fp-player-name")[0];
                importantNumbers.add(cleanNumberString);
            }
            if(line.startsWith("<td class=\"center\" data-sort-value=")){
                String cleanNumberStringStart = line.split("\">")[1];
                String cleanNumberString = cleanNumberStringStart.split("</td>")[0];
                importantNumbers.add(cleanNumberString);
            }
        }

        //trailing players all called Tom Coughlin since ID utility methods aren't perfect
        double[] proj = new double[10];
        String playerSRID = "";

        for(int i=0; i < importantNumbers.size(); i++){
            int mod = i % 11;

            if(mod == 0){
                String idString = importantNumbers.get(i);
                int fpID = Integer.parseInt(idString);
                playerSRID = FantasyProsUtility.getSRID(fpID);

                proj = new double[10];
            }
            else if(mod < 10){
                double val = Double.parseDouble(importantNumbers.get(i));
                proj[mod-1] = val;

            }
            else if(mod == 10){
                double val = Double.parseDouble(importantNumbers.get(i));
                proj[mod-1] = val;

                Player playerQB = Player.getPlayer(playerSRID);
                QBProjection quarterbackProj = new QBProjection(proj, playerQB);
                projections.add(quarterbackProj);
            }
        }
        return projections;
    }



    private static ArrayList<FlexProjection> parseTodaysWebPageFlex() {

        ArrayList<FlexProjection> projections = new ArrayList<FlexProjection>();

        String entireHTML = getTodaysWebPageFlexHalf();
        String[] lines = entireHTML.split("\r\n|\r|\n");
        String[] linesCleaned = new String[lines.length];
        for(int i = 0; i<lines.length; i++){
            linesCleaned[i] = lines[i].trim();
        }

        ArrayList<String> importantNumbers = new ArrayList<>();
        for(String line : linesCleaned){
            if(line.startsWith("<td style=\"text-align: center;\">")){
                String cleanNumberStringStart = line.split("<td class=\"center\">")[1];
                String cleanNumberString = cleanNumberStringStart.split("</td>")[0];
                String noCommaString = cleanNumberString.replace(",", "");
                importantNumbers.add(noCommaString);
            }
            if(line.startsWith("<td class=\"center\">")){
                String cleanNumberStringStart = line.split("<td class=\"center\">")[1];
                String cleanNumberString = cleanNumberStringStart.split("</td>")[0];
                String noCommaString = cleanNumberString.replace(",", "");
                importantNumbers.add(noCommaString);
            }
            if(line.startsWith("<tr class=\"mpb-player-")){
                String cleanNumberStringStart = line.split("fp-id-")[1];
                String cleanNumberString = cleanNumberStringStart.split("\" fp-player-name")[0];
                importantNumbers.add(cleanNumberString);
            }
            if(line.startsWith("<td class=\"center\" data-sort-value=")){
                String cleanNumberStringStart = line.split("\">")[1];
                String cleanNumberString = cleanNumberStringStart.split("</td>")[0];
                importantNumbers.add(cleanNumberString);
            }
        }

        //trailing players all called Tom Coughlin since ID utility methods aren't perfect
        double[] proj = new double[8];
        String playerSRID = "";

        for(int i=0; i < importantNumbers.size(); i++){
            int mod = i % 9;

            if(mod == 0){
                String idString = importantNumbers.get(i);
                int fpID = Integer.parseInt(idString);
                playerSRID = FantasyProsUtility.getSRID(fpID);

                proj = new double[8];
            }
            else if(mod < 8){
                double val = Double.parseDouble(importantNumbers.get(i));
                proj[mod-1] = val;

            }
            else if(mod == 8){
                double val = Double.parseDouble(importantNumbers.get(i));
                proj[mod-1] = val;

                Player playerFlex = Player.getPlayer(playerSRID);
                FlexProjection flexProj = new FlexProjection(proj, playerFlex);
                projections.add(flexProj);
            }
        }
        return projections;
    }



    private static ArrayList<DEFProjection> parseTodaysWebPageDEF() {

        ArrayList<DEFProjection> projections = new ArrayList<DEFProjection>();

        String entireHTML = getTodaysWebPageDEF();
        String[] lines = entireHTML.split("\r\n|\r|\n");
        String[] linesCleaned = new String[lines.length];
        for(int i = 0; i<lines.length; i++){
            linesCleaned[i] = lines[i].trim();
        }

        ArrayList<String> importantNumbers = new ArrayList<>();
        for(String line : linesCleaned){
            if(line.startsWith("<td class=\"center\">")){
                String cleanNumberStringStart = line.split("<td class=\"center\">")[1];
                String cleanNumberString = cleanNumberStringStart.split("</td>")[0];
                String noCommaString = cleanNumberString.replace(",", "");
                importantNumbers.add(noCommaString);
            }
            if(line.startsWith("<tr class=\"mpb-player-")){
                String cleanNumberStringStart = line.split("fp-id-")[1];
                String cleanNumberString = cleanNumberStringStart.split("\" fp-player-name")[0];
                importantNumbers.add(cleanNumberString);
            }
            if(line.startsWith("<td class=\"center\" data-sort-value=")){
                String cleanNumberStringStart = line.split("\">")[1];
                String cleanNumberString = cleanNumberStringStart.split("</td>")[0];
                importantNumbers.add(cleanNumberString);
            }
        }

        //trailing players all called Tom Coughlin since ID utility methods aren't perfect
        double[] proj = new double[9];
        String playerSRID = "";

        for(int i=0; i < importantNumbers.size(); i++){
            int mod = i % 10;

            if(mod == 0){
                String idString = importantNumbers.get(i);
                int fpID = Integer.parseInt(idString);
                playerSRID = FantasyProsUtility.getSRID(fpID);

                proj = new double[9];
            }
            else if(mod < 9){
                double val = Double.parseDouble(importantNumbers.get(i));
                proj[mod-1] = val;

            }
            else if(mod == 9){
                double val = Double.parseDouble(importantNumbers.get(i));
                proj[mod-1] = val;

                Player playerDEF = Player.getPlayer(playerSRID);
                DEFProjection defenseProj = new DEFProjection(proj, playerDEF);
                projections.add(defenseProj);
            }
        }
        return projections;
    }



    public static void main(String[] args){
        ArrayList<QBProjection> projectionsQB = parseTodaysWebPageQB();
        ArrayList<FlexProjection> projectionsFlex = parseTodaysWebPageFlex();
        ArrayList<DEFProjection> projectionsDEF = parseTodaysWebPageDEF();
    }

}
