import com.google.common.collect.Collections2;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.checkerframework.checker.units.qual.A;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class TradeFinderThreeTeams {

    public static String webURLSerious = "https://api.sleeper.app/v1/league/725192042375917568/rosters";
    public static String webURLFun = "https://api.sleeper.app/v1/league/707299245186691072/rosters";
    public static String myID = HumanOfInterest.humanID;

    public static String filepathStartSerious = "eagueRostersCurrentSerious";
    public static String filepathStartFun= "leagueRostersCurrentFun";


    private static String getTodaysWebPageSerious(){
        return InOutUtilities.getTodaysWebPage(webURLSerious, filepathStartSerious);
    }
    private static String getTodaysWebPageFun(){
        return InOutUtilities.getTodaysWebPage(webURLFun, filepathStartFun);
    }

    public static ArrayList<FPRosterSerious> getFPProjPointsRostersSerious(){
        ArrayList<FPRosterSerious> allRostersSerious = new ArrayList<>();

        String webData = getTodaysWebPageSerious();

        JsonParser jp = new JsonParser();
        JsonElement jsonElement = jp.parse(webData);
        JsonArray jsonMembers = jsonElement.getAsJsonArray();

        for (JsonElement jsonMember : jsonMembers) {

            JsonObject apiObject = jsonMember.getAsJsonObject();

            String ownerID = "";
            ArrayList<Player> allPlayersOfTeam = new ArrayList<>();


            if(!apiObject.get("owner_id").isJsonNull()) {
                ownerID = apiObject.get("owner_id").getAsString();
            }


            JsonArray allWeirdIDs = apiObject.getAsJsonArray("players");
            //JsonArray allWeirdIDs = playersWeirdID.getAsJsonArray();
            for(JsonElement playerWeirdID : allWeirdIDs){
                String idStringWeird = playerWeirdID.getAsString();
                Player tempPlayer = Player.getPlayerFromSIDV2(idStringWeird);
                if(tempPlayer == null){
                    System.out.println("Here is a null player mistake");
                }
                allPlayersOfTeam.add(tempPlayer);
            }
            FPRosterSerious tempMemberSerious = new FPRosterSerious(ownerID, allPlayersOfTeam);
            allRostersSerious.add(tempMemberSerious);

        }
        return allRostersSerious;

    }

    public static void scoreAllRosters(ArrayList<FPRosterSerious> allRosters){
        for(FPRosterSerious fpRost : allRosters){
            System.out.println( "userId:\t" + fpRost.userID + "\t ROS best lineup score:\t" + fpRost.scoreBestROSStartingLineup());
        }
    }

    public static PriorityQueue<TradePreviewSerious3T> doubleSwapTradeFinderAll(ArrayList<FPRosterSerious> allRosters, String lastNameToTrade, int minMine, int minTheirs, boolean ignoreJake){

        PriorityQueue<TradePreviewSerious3T> allTrades = new PriorityQueue<>(5, new TradePreviewSerious3TComparator());
        for(int i=0; i<allRosters.size()-2; i++){
            for(int j=i+1; j<allRosters.size()-1; j++){
                ArrayList<FPRosterSerious> allRostersCopy = new ArrayList<>();
                for(FPRosterSerious fpRos : allRosters){
                    allRostersCopy.add(FPRosterSerious.makeCopy(fpRos));
                }
                PriorityQueue<TradePreviewSerious3T> singleTeamTrades = doubleSwapTradeFinderSingleTeam(allRostersCopy, i, j, lastNameToTrade, minMine, minTheirs, ignoreJake);
                allTrades.addAll(singleTeamTrades);
            }
        }
        return allTrades;
    }

    public static PriorityQueue<TradePreviewSerious3T> doubleSwapTradeFinderAllWithN(ArrayList<FPRosterSerious> allRosters, int teamN, int lastTeamStart, int lastTeamEnd, String lastNameToTrade, int minMine, int minTheirs, boolean ignoreJake){

        PriorityQueue<TradePreviewSerious3T> allTrades = new PriorityQueue<>(5, new TradePreviewSerious3TComparator());

        //TODO might be -2
        for(int k=0; k<allRosters.size()-1; k++){
            /*if(k==teamN){
                continue;
            }*/
            if(k<lastTeamStart){
                continue;
            }
            if(k == lastTeamEnd){
                break;
            }

            ArrayList<FPRosterSerious> allRostersCopy = new ArrayList<>();
            for(FPRosterSerious fpRos : allRosters){
                allRostersCopy.add(FPRosterSerious.makeCopy(fpRos));
            }

            PriorityQueue<TradePreviewSerious3T> singleTeamTrades = doubleSwapTradeFinderSingleTeam(allRostersCopy, teamN, k, lastNameToTrade, minMine, minTheirs, ignoreJake);
            allTrades.addAll(singleTeamTrades);
        }

        return allTrades;
    }


    public static PriorityQueue<TradePreviewSerious3T> doubleSwapTradeFinderSingleTeam(ArrayList<FPRosterSerious> allRosters, int teamNum1, int teamNum2, String lastNameToTrade, int minMine, int minTheirs, boolean ignoreJake){
        PriorityQueue<TradePreviewSerious3T> allTrades = new PriorityQueue<>(5, new TradePreviewSerious3TComparator());
        FPRosterSerious myRoster = allRosters.get(0);//make compiler happy
        for(FPRosterSerious roster : allRosters){
            if(roster.userID.equals(myID)){
                myRoster = roster;
            }
        }
        allRosters.remove(myRoster);

        FPRosterSerious otr1 = allRosters.get(teamNum1);
        //TODO logic here means you don't loop j=i+1
        allRosters.remove(otr1);
        System.out.println("all rosters size after 2 removal:\t" + allRosters.size());

        FPRosterSerious otr2 = allRosters.get(teamNum2);
        if(ignoreJake){//TODO is it ok
            if(otr2.userID.equals("724919475115225088")){
                return allTrades;
            }
        }
        int numInner6Loop = 0;
        for(int w=0; w < myRoster.draftedPlayersWithProj.size()-1; w++){
            for(int x = w+1; x < myRoster.draftedPlayersWithProj.size(); x++){

                //TODO remove
                //
                if(!lastNameToTrade.equals("")) {
                    if (!myRoster.draftedPlayersWithProj.get(w).player.lastName.equals(lastNameToTrade)) {
                        if (!myRoster.draftedPlayersWithProj.get(x).player.lastName.equals(lastNameToTrade)) {
                            continue;
                        }
                    }
                }
                //

                for(int y=0; y<otr1.draftedPlayersWithProj.size()-1; y++){
                    for(int z=y+1; z<otr1.draftedPlayersWithProj.size(); z++){
                        for(int a=0; a<otr2.draftedPlayersWithProj.size()-1; a++) {
                            for (int b = a + 1; b < otr2.draftedPlayersWithProj.size(); b++) {
                                numInner6Loop++;
                                if(numInner6Loop % 10000 == 0) {
                                    System.out.println(numInner6Loop + "\t" + teamNum1 + "\t" + teamNum2);
                                }
                                Score t1p1 = myRoster.draftedPlayersWithProj.get(w);
                                Score t1p2 = myRoster.draftedPlayersWithProj.get(x);
                                Score t2p1 = otr1.draftedPlayersWithProj.get(y);
                                Score t2p2 = otr1.draftedPlayersWithProj.get(z);
                                Score t3p1 = otr2.draftedPlayersWithProj.get(a);
                                Score t3p2 = otr2.draftedPlayersWithProj.get(b);

                                ArrayList<Character> noPerm = new ArrayList<>();
                                noPerm.add('a');
                                noPerm.add('b');
                                noPerm.add('c');
                                noPerm.add('d');
                                noPerm.add('e');
                                noPerm.add('f');
                                Iterator<List<Character>> allPerms = Collections2.orderedPermutations(noPerm).iterator();
                                Collection<List<Character>> allPermsCol = Collections2.orderedPermutations(noPerm);
                                int allPermsWithNonValidSize = allPermsCol.size();

                                int numPermsValid =0;
                                int numTotalPerm = 0;
                                while(allPerms.hasNext()){
                                    List<Character> perm = allPerms.next();
                                    numTotalPerm++;
                                    if(perm.get(0) < perm.get(1) && perm.get(2) < perm.get(3) && perm.get(4) < perm.get(5)){
                                        if(perm.get(0) == 'a' && perm.get(1) == 'b'){
                                            continue;
                                        }
                                        if(perm.get(2) == 'c' && perm.get(3) == 'd'){
                                            continue;
                                        }
                                        if(perm.get(4) == 'e' && perm.get(5) == 'f'){
                                            continue;
                                        }
                                        numPermsValid++;
                                        //System.out.println("NumValidPerm:\t" + numPermsValid + "\tNumTotalPerm:\t" + numTotalPerm + "\tout of:\t" + allPermsWithNonValidSize);
                                        TradePreviewSerious3T tps = new TradePreviewSerious3T(myRoster, otr1, otr2, t1p1, t1p2, t2p1, t2p2, t3p1, t3p2, perm, minMine, minTheirs);
                                        //TODO set bar
                                        if(tps.improvementT1 > minMine && tps.improvementT2 > minTheirs && tps.improvementT3 >  minTheirs ) {
                                            allTrades.add(tps);
                                        }
                                    }
                                }


                            }
                        }
                    }
                }
            }
        }

        return allTrades;

    }

    public static void tradeFinder3TRunner(int teamN, int lastTeamStart, int lastTeamEnd, String tradedPlayerLastName, int minMine, int minTheirs, boolean ignoreJake) throws IOException {

        ArrayList<FPRosterSerious> xyz = getFPProjPointsRostersSerious();
        scoreAllRosters(xyz);
        //PriorityQueue<TradePreviewSerious> xyz2 = doubleSwapTradeFinderSingleTeam(xyz, 0);
        PriorityQueue<TradePreviewSerious3T> xyz2 = doubleSwapTradeFinderAllWithN(xyz, teamN, lastTeamStart,lastTeamEnd, tradedPlayerLastName, minMine, minTheirs, ignoreJake);


        ArrayList<TradePreviewSerious3T> allT0 = new ArrayList<>();
        ArrayList<TradePreviewSerious3T> allT1 = new ArrayList<>();
        ArrayList<TradePreviewSerious3T> allT2 = new ArrayList<>();
        ArrayList<TradePreviewSerious3T> allT3 = new ArrayList<>();
        ArrayList<TradePreviewSerious3T> allT4 = new ArrayList<>();
        ArrayList<TradePreviewSerious3T> allT5 = new ArrayList<>();
        ArrayList<TradePreviewSerious3T> allT6 = new ArrayList<>();
        ArrayList<TradePreviewSerious3T> allT7 = new ArrayList<>();
        ArrayList<TradePreviewSerious3T> allT8 = new ArrayList<>();
        ArrayList<TradePreviewSerious3T> allT9 = new ArrayList<>();
        ArrayList<TradePreviewSerious3T> allT10 = new ArrayList<>();
        ArrayList<TradePreviewSerious3T> allT11 = new ArrayList<>();


        while(!xyz2.isEmpty()){
            TradePreviewSerious3T temp = xyz2.poll();

            if(temp.improvementT2 > 25.0 && temp.improvementT3 > 25.0) {
                allT11.add(temp);
            }
            else if(temp.improvementT2 > 25.0 && temp.improvementT3 > 18.0) {
                allT10.add(temp);
            }
            else if(temp.improvementT2 > 25.0 && temp.improvementT3 > 10.0) {
                allT9.add(temp);
            }
            else if(temp.improvementT2 > 25.0 && temp.improvementT3 > 0.0) {
                allT8.add(temp);
            }
            else if(temp.improvementT2 > 18.0 && temp.improvementT3 > 25.0) {
                allT7.add(temp);
            }
            else if(temp.improvementT2 > 18.0 && temp.improvementT3 > 18.0) {
                allT6.add(temp);
            }
            else if(temp.improvementT2 > 18.0 && temp.improvementT3 > 10.0) {
                allT5.add(temp);
            }
            else if(temp.improvementT2 > 18.0 && temp.improvementT3 > 0.0) {
                allT4.add(temp);
            }
            else if(temp.improvementT2 > 10.0 && temp.improvementT3 > 10.0) {
                allT3.add(temp);
            }
            else if(temp.improvementT2 > 10.0 && temp.improvementT3 > 0.0) {
                allT2.add(temp);
            }
            else if(temp.improvementT2 > 0.0 && temp.improvementT3 > 10.0) {
                allT1.add(temp);
            }
            else if(temp.improvementT2 > 0.0 && temp.improvementT3 > 0.0) {
                allT0.add(temp);
            }

        }

        ArrayList<String> t11Strings = new ArrayList<>();
        ArrayList<String> t10Strings = new ArrayList<>();
        ArrayList<String> t9Strings = new ArrayList<>();
        ArrayList<String> t8Strings = new ArrayList<>();
        ArrayList<String> t7Strings = new ArrayList<>();
        ArrayList<String> t6Strings = new ArrayList<>();
        ArrayList<String> t5Strings = new ArrayList<>();
        ArrayList<String> t4Strings = new ArrayList<>();
        ArrayList<String> t3Strings = new ArrayList<>();
        ArrayList<String> t2Strings = new ArrayList<>();
        ArrayList<String> t1Strings = new ArrayList<>();
        ArrayList<String> t0Strings = new ArrayList<>();

        for(TradePreviewSerious3T temp : allT11){
            String t11TempString = TradePreviewSerious3T.printTradePreview(temp);
            t11Strings.add(t11TempString);
        }
        for(TradePreviewSerious3T temp : allT10){
            String t10TempString = TradePreviewSerious3T.printTradePreview(temp);
            t10Strings.add(t10TempString);
        }
        for(TradePreviewSerious3T temp : allT9){
            String t9TempString = TradePreviewSerious3T.printTradePreview(temp);
            t9Strings.add(t9TempString);
        }
        for(TradePreviewSerious3T temp : allT8){
            String t8TempString = TradePreviewSerious3T.printTradePreview(temp);
            t8Strings.add(t8TempString);
        }
        for(TradePreviewSerious3T temp : allT7){
            String t7TempString = TradePreviewSerious3T.printTradePreview(temp);
            t7Strings.add(t7TempString);
        }
        for(TradePreviewSerious3T temp : allT6){
            String t6TempString = TradePreviewSerious3T.printTradePreview(temp);
            t6Strings.add(t6TempString);
        }
        for(TradePreviewSerious3T temp : allT5){
            String t5TempString = TradePreviewSerious3T.printTradePreview(temp);
            t5Strings.add(t5TempString);
        }
        for(TradePreviewSerious3T temp : allT4){
            String t4TempString = TradePreviewSerious3T.printTradePreview(temp);
            t4Strings.add(t4TempString);
        }
        for(TradePreviewSerious3T temp : allT3){
            String t3TempString = TradePreviewSerious3T.printTradePreview(temp);
            t3Strings.add(t3TempString);
        }
        for(TradePreviewSerious3T temp : allT2){
            String t2TempString = TradePreviewSerious3T.printTradePreview(temp);
            t2Strings.add(t2TempString);
        }
        for(TradePreviewSerious3T temp : allT1){
            String t1TempString = TradePreviewSerious3T.printTradePreview(temp);
            t1Strings.add(t1TempString);
        }
        for(TradePreviewSerious3T temp : allT0){
            String t0TempString = TradePreviewSerious3T.printTradePreview(temp);
            t0Strings.add(t0TempString);
        }

        String fileString = "x" + teamN + "x" + lastTeamStart + "to" + lastTeamEnd + "x" + tradedPlayerLastName;

        FileWriter writer11 = new FileWriter("t11" + fileString + ".txt");
        for(String str: t11Strings) {
            writer11.write(str + System.lineSeparator());
        }
        writer11.close();

        FileWriter writer10 = new FileWriter("t10" + fileString + ".txt");
        for(String str: t10Strings) {
            writer10.write(str + System.lineSeparator());
        }
        writer10.close();

        FileWriter writer9 = new FileWriter("t9" + fileString + ".txt");
        for(String str: t9Strings) {
            writer9.write(str + System.lineSeparator());
        }
        writer9.close();

        FileWriter writer8 = new FileWriter("t8" + fileString + ".txt");
        for(String str: t8Strings) {
            writer8.write(str + System.lineSeparator());
        }
        writer8.close();

        FileWriter writer7 = new FileWriter("t7" + fileString + ".txt");
        for(String str: t7Strings) {
            writer7.write(str + System.lineSeparator());
        }
        writer7.close();

        FileWriter writer6 = new FileWriter("t6" + fileString + ".txt");
        for(String str: t6Strings) {
            writer6.write(str + System.lineSeparator());
        }
        writer6.close();

        FileWriter writer5 = new FileWriter("t5" + fileString + ".txt");
        for(String str: t5Strings) {
            writer5.write(str + System.lineSeparator());
        }
        writer5.close();

        FileWriter writer4 = new FileWriter("t4" + fileString + ".txt");
        for(String str: t4Strings) {
            writer4.write(str + System.lineSeparator());
        }
        writer4.close();

        FileWriter writer3 = new FileWriter("t3" + fileString + ".txt");
        for(String str: t3Strings) {
            writer3.write(str + System.lineSeparator());
        }
        writer3.close();

        FileWriter writer2 = new FileWriter("t2" + fileString + ".txt");
        for(String str: t2Strings) {
            writer2.write(str + System.lineSeparator());
        }
        writer2.close();

        FileWriter writer1 = new FileWriter("t1" + fileString + ".txt");
        for(String str: t1Strings) {
            writer1.write(str + System.lineSeparator());
        }
        writer1.close();

        FileWriter writer0 = new FileWriter("t0" + fileString + ".txt");
        for(String str: t0Strings) {
            writer0.write(str + System.lineSeparator());
        }
        writer0.close();
    }

    //xyz, 0, 0, 1 is me, kev, georgis
    // xyz, 0, 1, 2 is me, kev, simon

    // xyz, 0, 2, 3 is me, kev, itsabust
    // xyz 5, 7, 8, is me sirardo, jeremy
    public static void main(String[] args) throws IOException {
        //0<1 is kevin
        //1<2 is renteez
        //2<3 is simon
        //3<4 is itsabust
        //4<5 is jake *
        //5<6 is sirardo *
        //6<7 is radicals?
        //7<8 is alex?
        //8<9 is tys broken neck?
        //9<10 is russellMania?
        //10<11 is hamrliks? *
        //ends at 10; <11
        for(int i=3; i<4; i++){
            String tradedPlayerLastName = "Jefferson";
            int teamN = i;
            int lastTeamStart = 0;
            int lastTeamEnd = 10;
            int minMine = 14;
            int minTheirs = 0;
            boolean ignoreJake = true;

            tradeFinder3TRunner(teamN, lastTeamStart, lastTeamEnd, tradedPlayerLastName, minMine, minTheirs, ignoreJake);

        }

        int a = 1;

    }

}
