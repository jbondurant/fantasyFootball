import com.google.common.collect.Collections2;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class TradeFinderThreeTeams {

    public static String myID = HumanOfInterest.humanID;

    private static ArrayList<List<Character>> allPermsCleaned = new ArrayList<>();

    static {
        ArrayList<Character> noPerm = new ArrayList<>();
        noPerm.add('a');
        noPerm.add('b');
        noPerm.add('c');
        noPerm.add('d');
        noPerm.add('e');
        noPerm.add('f');
        Iterator<List<Character>> allPerms = Collections2.orderedPermutations(noPerm).iterator();
        while(allPerms.hasNext()){
            List<Character> perm = allPerms.next();
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
                allPermsCleaned.add(perm);
            }
        }
    }

    public static ArrayList<ScoredRoster> getFPProjPointsRostersSerious(ProjectionSource projectionSource,
                                                                        AAAConfiguration aaaConfiguration){
        ArrayList<ScoredRoster> allRostersSerious = new ArrayList<>();

        String webData = aaaConfiguration.getTodaysRosterWebPageSerious();

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
            ScoredRoster tempMemberSerious = new ScoredRoster(ownerID, allPlayersOfTeam, projectionSource);
            allRostersSerious.add(tempMemberSerious);

        }
        return allRostersSerious;

    }

    public static void scoreAllRosters(ArrayList<ScoredRoster> allRosters){
        for(ScoredRoster fpRost : allRosters){
            System.out.println( "userId:\t" + fpRost.userID + "\t ROS best lineup score:\t" + fpRost.scoreBestROSStartingLineup());
        }
    }

    public static PriorityQueue<TradePreviewSerious3T> doubleSwapTradeFinderAll(ArrayList<ScoredRoster> allRosters, String lastNameToTrade, int minMine, int minTheirs, boolean ignoreJake){

        PriorityQueue<TradePreviewSerious3T> allTrades = new PriorityQueue<>(5, new TradePreviewSerious3TComparator());
        for(int i=0; i<allRosters.size()-2; i++){
            for(int j=i+1; j<allRosters.size()-1; j++){
                ArrayList<ScoredRoster> allRostersCopy = new ArrayList<>();
                for(ScoredRoster fpRos : allRosters){
                    allRostersCopy.add(ScoredRoster.makeCopy(fpRos));
                }
                PriorityQueue<TradePreviewSerious3T> singleTeamTrades = doubleSwapTradeFinderSingleTeam(allRostersCopy, i, j, lastNameToTrade, minMine, minTheirs, ignoreJake);
                allTrades.addAll(singleTeamTrades);
            }
        }
        return allTrades;
    }

    public static PriorityQueue<TradePreviewSerious3T> doubleSwapTradeFinderAllWithN(ArrayList<ScoredRoster> allRosters, int teamN, int lastTeamStart, int lastTeamEnd, String lastNameToTrade, int minMine, int minTheirs, boolean ignoreJake){

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

            ArrayList<ScoredRoster> allRostersCopy = new ArrayList<>();
            for(ScoredRoster fpRos : allRosters){
                allRostersCopy.add(ScoredRoster.makeCopy(fpRos));
            }

            PriorityQueue<TradePreviewSerious3T> singleTeamTrades = doubleSwapTradeFinderSingleTeam(allRostersCopy, teamN, k, lastNameToTrade, minMine, minTheirs, ignoreJake);
            allTrades.addAll(singleTeamTrades);
        }

        return allTrades;
    }


    public static PriorityQueue<TradePreviewSerious3T> doubleSwapTradeFinderSingleTeam(ArrayList<ScoredRoster> allRosters, int teamNum1, int teamNum2, String lastNameToTrade, int minMine, int minTheirs, boolean ignoreJake){
        PriorityQueue<TradePreviewSerious3T> allTrades = new PriorityQueue<>(5, new TradePreviewSerious3TComparator());
        ScoredRoster myRoster = allRosters.get(0);//make compiler happy
        for(Score s : myRoster.draftedPlayersWithProj){
            if(s.player.lastName.equals("Herbert")){
                System.out.println("something wrong herbert");
            }
        }
        for(ScoredRoster roster : allRosters){
            if(roster.userID.equals(myID)){
                myRoster = roster;
            }
        }
        allRosters.remove(myRoster);

        ScoredRoster otr1 = allRosters.get(teamNum1);
        //TODO logic here means you don't loop j=i+1
        allRosters.remove(otr1);
        System.out.println("all rosters size after 2 removal:\t" + allRosters.size());

        ScoredRoster otr2 = allRosters.get(teamNum2);
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

                                for(List<Character> perm : allPermsCleaned){
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

        return allTrades;

    }

    public static void tradeFinder3TRunner(int teamN,
                                           int lastTeamStart,
                                           int lastTeamEnd,
                                           String tradedPlayerLastName,
                                           int minMine,
                                           int minTheirs,
                                           boolean ignoreJake,
                                           ProjectionSource projectionSource,
                                           AAAConfiguration aaaConfiguration) throws IOException {

        ArrayList<ScoredRoster> xyz = getFPProjPointsRostersSerious(projectionSource, aaaConfiguration);
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
            boolean isTradeGivingDefense = isTradeGivingDefense(temp);
            if(isTradeGivingDefense){
                continue;
            }
            boolean isTradeKeepingPlayer = isTradeKeepingPlayer(temp);
            if(isTradeKeepingPlayer){
                continue;
            }
            boolean isSuperSelfishTrade = isTradeSuperSelfish(temp);
            if(isSuperSelfishTrade){
                continue;
            }

            if(temp.improvementT2 > 7.0 && temp.improvementT3 > 9.0) {
                allT11.add(temp);
            }
            else if(temp.improvementT2 > 9.0 && temp.improvementT3 > 7.0) {
                allT10.add(temp);
            }
            else if(temp.improvementT2 > 7.0 && temp.improvementT3 > 7.0) {
                allT9.add(temp);
            }
            else if(temp.improvementT2 > 5.0 && temp.improvementT3 > 7.0) {
                allT8.add(temp);
            }
            else if(temp.improvementT2 > 7.0 && temp.improvementT3 > 5.0) {
                allT7.add(temp);
            }
            else if(temp.improvementT2 > 5.0 && temp.improvementT3 > 5.0) {
                allT6.add(temp);
            }
            else if(temp.improvementT2 > 0.0 && temp.improvementT3 > 5.0) {
                allT5.add(temp);
            }
            else if(temp.improvementT2 > 5.0 && temp.improvementT3 > 0.0) {
                allT4.add(temp);
            }
            else if(temp.improvementT2 > 3.0 && temp.improvementT3 > 3.0) {
                allT3.add(temp);
            }
            else if(temp.improvementT2 > 3.0 && temp.improvementT3 > 0.0) {
                allT2.add(temp);
            }
            else if(temp.improvementT2 > 0.0 && temp.improvementT3 > 3.0) {
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

        FileWriter writer11 = new FileWriter( fileString + "t11" + ".txt");
        for(String str: t11Strings) {
            writer11.write(str + System.lineSeparator());
        }
        writer11.close();

        FileWriter writer10 = new FileWriter(fileString + "t10" + ".txt");
        for(String str: t10Strings) {
            writer10.write(str + System.lineSeparator());
        }
        writer10.close();

        FileWriter writer9 = new FileWriter(fileString + "t9" + ".txt");
        for(String str: t9Strings) {
            writer9.write(str + System.lineSeparator());
        }
        writer9.close();

        FileWriter writer8 = new FileWriter(fileString + "t8" + ".txt");
        for(String str: t8Strings) {
            writer8.write(str + System.lineSeparator());
        }
        writer8.close();

        FileWriter writer7 = new FileWriter(fileString + "t7" + ".txt");
        for(String str: t7Strings) {
            writer7.write(str + System.lineSeparator());
        }
        writer7.close();

        FileWriter writer6 = new FileWriter(fileString + "t6" + ".txt");
        for(String str: t6Strings) {
            writer6.write(str + System.lineSeparator());
        }
        writer6.close();

        FileWriter writer5 = new FileWriter(fileString + "t5" + ".txt");
        for(String str: t5Strings) {
            writer5.write(str + System.lineSeparator());
        }
        writer5.close();

        FileWriter writer4 = new FileWriter(fileString + "t4" + ".txt");
        for(String str: t4Strings) {
            writer4.write(str + System.lineSeparator());
        }
        writer4.close();

        FileWriter writer3 = new FileWriter(fileString + "t3" + ".txt");
        for(String str: t3Strings) {
            writer3.write(str + System.lineSeparator());
        }
        writer3.close();

        FileWriter writer2 = new FileWriter(fileString + "t2" + ".txt");
        for(String str: t2Strings) {
            writer2.write(str + System.lineSeparator());
        }
        writer2.close();

        FileWriter writer1 = new FileWriter(fileString + "t1" + ".txt");
        for(String str: t1Strings) {
            writer1.write(str + System.lineSeparator());
        }
        writer1.close();

        FileWriter writer0 = new FileWriter(fileString + "t0" + ".txt");
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
        AAAConfiguration aaaConfiguration = new AAAConfigurationSleeperLeague();
        ProjectionSource projectionSource = ProjectionSource.PRESEASON_FP_SITE;
        for(int i=0; i<1; i++){//todo change back to 11
            String tradedPlayerLastName = "Henry";
            int teamN = i;
            int lastTeamStart = 0;//this is the range for the teams that are the 3rd one to trade
            int lastTeamEnd = 10;//so team 1 is us, team 2 is i, and team 3 is in this range
            int minMine = 14;//because of some calls to like allrosters.remove. you want the end above to be 10
            int minTheirs = 0;
            boolean ignoreJake = false;
            boolean inSeason = true; //todo

            tradeFinder3TRunner(teamN, lastTeamStart, lastTeamEnd, tradedPlayerLastName, minMine, minTheirs, ignoreJake, projectionSource, aaaConfiguration);

        }

        int a = 1;

    }

    public static boolean isTradeSuperSelfish(TradePreviewSerious3T tps){
        Player given1  = tps.t1p1Score.player;
        Player given2 = tps.t1p2Score.player;

        Player received1 = tps.t1Received1.player;
        Player received2 = tps.t1Received2.player;

        if(given1.sportRadarID.equals(received1.sportRadarID)){//11
            if(given2.position.equals(received2.position)){
                return true;
            }
        }
        else if(given1.sportRadarID.equals(received2.sportRadarID)){//12
            if(given2.position.equals(received1.position)){
                return true;
            }
        }
        else if(given2.sportRadarID.equals(received1.sportRadarID)){//21
            if(given1.position.equals(received2.position)){
                return true;
            }
        }
        else if(given2.sportRadarID.equals(received2.sportRadarID)){//22
            if(given1.position.equals(received1.position)){
                return true;
            }
        }
        return false;
    }

    public static boolean isTradeKeepingPlayer(TradePreviewSerious3T tps){
        Player given1  = tps.t1p1Score.player;
        Player given2 = tps.t1p2Score.player;

        Player received1 = tps.t1Received1.player;
        Player received2 = tps.t1Received2.player;

        if(given1.sportRadarID.equals(received1.sportRadarID)){//11
            return true;
        }
        else if(given1.sportRadarID.equals(received2.sportRadarID)){//12
            return true;
        }
        else if(given2.sportRadarID.equals(received1.sportRadarID)){//21
            return true;
        }
        else if(given2.sportRadarID.equals(received2.sportRadarID)){//22
            return true;
        }
        return false;
    }

    public static boolean isTradeGivingDefense(TradePreviewSerious3T tps){
        Player given1  = tps.t1p1Score.player;
        Player given2 = tps.t1p2Score.player;

        if(given1.position.equals(Position.DEF)){//11
            return true;
        }
        else if(given2.position.equals(Position.DEF)){//12
            return true;
        }
        return false;
    }

}
