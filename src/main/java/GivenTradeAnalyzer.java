import org.checkerframework.checker.units.qual.A;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GivenTradeAnalyzer {

   public static void main(String[] args) throws FileNotFoundException {
       ArrayList<String> givenPlayers = new ArrayList<>();
       ArrayList<String> takenPlayers = new ArrayList<>();

       //givenPlayers.add("Zach Pascal");
       //givenPlayers.add("D'Andre Swift");
       //givenPlayers.add("Darren Waller");
       givenPlayers.add("Kyler Murray");

       //takenPlayers.add("Terry McLaurin");
       takenPlayers.add("Josh Jacobs");
       //takenPlayers.add("Darrell Henderson");


       analyzeTwoTeamTrade(givenPlayers, takenPlayers);
   }



   public static void analyzeTwoTeamTrade(ArrayList<String> givenNames, ArrayList<String> takenNames) throws FileNotFoundException {
       String fileStringStart = "twoTeamTrade";
       String fileString = "Xignoring0Xreq0";

       int numGiven = givenNames.size();

       String regexStringGiven = "^";
       for(String name : givenNames) {
           regexStringGiven += "(?=.*\\b" + name + "\\b)";
       }
       regexStringGiven += ".*$";

       String regexStringTaken = "^";
       for(String name : takenNames) {
           regexStringTaken += "(?=.*\\b" + name + "\\b)";
       }
       regexStringTaken += ".*$";



       for(int i=0; i<10; i++){
           //System.out.println("Looking at file " + i);
           String fileName = fileStringStart + "t" + i  + fileString + ".txt";

           Scanner fileScanner = new Scanner(new File(fileName));

           Pattern patternGiven =  Pattern.compile(regexStringGiven);
           Matcher matcherGiven = null;

           Pattern patternTaken =  Pattern.compile(regexStringTaken);
           Matcher matcherTaken = null;

           while(fileScanner.hasNextLine()){
               String line = fileScanner.nextLine();
               matcherGiven = patternGiven.matcher(line);
               if(matcherGiven.find()){
                   int numAnds = line.split("and").length -1;
                   if(numAnds != numGiven -1){
                       continue;
                   }
                   String givenLine = line;
                   String line2 = fileScanner.nextLine();
                   matcherTaken = patternTaken.matcher(line2);
                   if(matcherTaken.find()) {
                       System.out.println(line);
                       System.out.println(line2);
                       for(int j=0; j<4; j++) {
                           String lineNext = fileScanner.nextLine();
                           System.out.println(lineNext);
                       }
                       fileScanner.close();
                       return;
                   }
               }
           }
           fileScanner.close();
       }
       return;
   }

}
