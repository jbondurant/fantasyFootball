
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GivenTradeAnalyzer {

   /**
    * Finds one specific trade in the files TradeFinder wrote. Run TradeFinder
    * first; this only reads its output.
    *
    * Usage: --give "Player One" [--give "Player Two"] --take "Player Three" ...
    * The names used to be edited into the source, which meant the checked-in
    * copy always named players from whatever season it was last run in.
    */
   public static void main(String[] args) throws FileNotFoundException {
       ArrayList<String> givenPlayers = new ArrayList<>();
       ArrayList<String> takenPlayers = new ArrayList<>();

       ArrayList<String> current = null;
       for(String arg : args){
           if(arg.equals("--give")){
               current = givenPlayers;
           }
           else if(arg.equals("--take")){
               current = takenPlayers;
           }
           else if(current != null){
               current.add(arg);
           }
       }

       if(givenPlayers.isEmpty() || takenPlayers.isEmpty()){
           System.out.println("usage: GivenTradeAnalyzer --give \"Player One\" --take \"Player Two\"");
           System.out.println("(run TradeFinder first - this searches the files it writes)");
           return;
       }

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



       boolean foundAnyFile = false;
       // TradeFinder writes t0 through t10.
       for(int i=0; i<=10; i++){
           String fileName = fileStringStart + "t" + i  + fileString + ".txt";
           File file = new File(fileName);
           if(!file.exists()){
               continue;
           }
           foundAnyFile = true;

           Scanner fileScanner = new Scanner(file);

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
       if(!foundAnyFile){
           System.out.println("no " + fileStringStart + "t*" + fileString
                   + ".txt files here - run TradeFinder first");
           return;
       }
       System.out.println("that trade does not appear in TradeFinder's output");
   }

}
