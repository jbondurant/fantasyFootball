import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.PriorityQueue;

public class TradeFinder {

    private static ScoredRoster locateMyRoster(ArrayList<ScoredRoster> allRosters, String myID) {
        ScoredRoster myRoster = null;
        for(ScoredRoster roster : allRosters){
            if(roster.userID.equals(myID)){
                myRoster = roster;
            }
        }
        return myRoster;
    }

    public static PriorityQueue<TradePreviewSerious> singleSwapTradeFinderSingleTeam(ArrayList<ScoredRoster> allRosters, int teamNum, String myID){
        PriorityQueue<TradePreviewSerious> allTrades = new PriorityQueue<>(5, new TradePreviewSeriousComparator());
        ScoredRoster myRoster = locateMyRoster(allRosters, myID);
        allRosters.remove(myRoster);

        ScoredRoster otherTeamsRoster = allRosters.get(teamNum);
//TODO make roster copies
        for(int w=0; w < myRoster.draftedPlayersWithProj.size(); w++){
            for(int y=0; y<otherTeamsRoster.draftedPlayersWithProj.size(); y++){
                Score t1p1 = myRoster.draftedPlayersWithProj.get(w);
                Score t2p1 = otherTeamsRoster.draftedPlayersWithProj.get(y);
                if(t1p1.player.firstName.equals("Diontae") && t2p1.player.firstName.equals("David")){
                    int d=1;
                }

                TradePreviewSerious tps = new TradePreviewSerious(myRoster, otherTeamsRoster, t1p1, t2p1);
                allTrades.add(tps);

            }

        }

        return allTrades;

    }

    public static PriorityQueue<TradePreviewSerious> doubleSwapTradeFinderSingleTeam(ArrayList<ScoredRoster> allRosters, int teamNum, String myID){
        PriorityQueue<TradePreviewSerious> allTrades = new PriorityQueue<>(5, new TradePreviewSeriousComparator());
        ScoredRoster myRoster = locateMyRoster(allRosters, myID);
        allRosters.remove(myRoster);

        ScoredRoster otherTeamsRoster = allRosters.get(teamNum);
//TODO make roster copies
        for(int w=0; w < myRoster.draftedPlayersWithProj.size()-1; w++){
            for(int x = w+1; x < myRoster.draftedPlayersWithProj.size(); x++){
                for(int y=0; y<otherTeamsRoster.draftedPlayersWithProj.size()-1; y++){
                    for(int z=y+1; z<otherTeamsRoster.draftedPlayersWithProj.size(); z++){
                        Score t1p1 = myRoster.draftedPlayersWithProj.get(w);
                        Score t1p2 = myRoster.draftedPlayersWithProj.get(x);
                        Score t2p1 = otherTeamsRoster.draftedPlayersWithProj.get(y);
                        Score t2p2 = otherTeamsRoster.draftedPlayersWithProj.get(z);

                        TradePreviewSerious tps = new TradePreviewSerious(myRoster, otherTeamsRoster, t1p1, t1p2, t2p1, t2p2);
                        allTrades.add(tps);

                    }
                }
            }
        }

        return allTrades;

    }

    public static PriorityQueue<TradePreviewSerious> tripleSwapTradeFinderSingleTeam(ArrayList<ScoredRoster> allRosters, int teamNum, String myID){
        PriorityQueue<TradePreviewSerious> allTrades = new PriorityQueue<>(5, new TradePreviewSeriousComparator());
        ScoredRoster myRoster = locateMyRoster(allRosters, myID);
        allRosters.remove(myRoster);

        ScoredRoster otherTeamsRoster = allRosters.get(teamNum);

        for(int a1=0; a1 < myRoster.draftedPlayersWithProj.size()-2; a1++){
            for(int a2 = a1+1; a2 < myRoster.draftedPlayersWithProj.size()-1; a2++){
                for(int a3 = a2+1; a3 < myRoster.draftedPlayersWithProj.size(); a3++) {
                    for (int b1 = 0; b1 < otherTeamsRoster.draftedPlayersWithProj.size() - 2; b1++) {
                        for (int b2 = b1 + 1; b2 < otherTeamsRoster.draftedPlayersWithProj.size()-1; b2++) {
                            for (int b3 = b2 + 1; b3 < otherTeamsRoster.draftedPlayersWithProj.size(); b3++) {
                                Score t1p1 = myRoster.draftedPlayersWithProj.get(a1);
                                Score t1p2 = myRoster.draftedPlayersWithProj.get(a2);
                                Score t1p3 = myRoster.draftedPlayersWithProj.get(a3);
                                Score t2p1 = otherTeamsRoster.draftedPlayersWithProj.get(b1);
                                Score t2p2 = otherTeamsRoster.draftedPlayersWithProj.get(b2);
                                Score t2p3 = otherTeamsRoster.draftedPlayersWithProj.get(b3);

                                TradePreviewSerious tps = new TradePreviewSerious(myRoster, otherTeamsRoster, t1p1, t1p2, t1p3, t2p1, t2p2, t2p3);
                                allTrades.add(tps);
                            }
                        }
                    }
                }
            }
        }

        return allTrades;

    }


    public static void main(String[] args) throws IOException {
        AAAConfiguration configuration = new AAAConfigurationSleeperLeague();
        boolean onlyOne = true;
        boolean onlyTwo = false;
        boolean toCrop = false;
        ProjectionSource projectionSource = ProjectionSource.IN_SEASON_FP_SITE;
        boolean roundFilter = false;
        ArrayList<String> tradersToIgnore = new ArrayList<>();
        //tradersToIgnore.add("606234521821577216");//tommyrads
        //tradersToIgnore.add("464471023782195200"); //itsabust
        //tradersToIgnore.add("605534791072305152"); //Tsayydeja
        //tradersToIgnore.add("725953800816373760");//Hamrliks
        //tradersToIgnore.add("740473448551366656"); //patek
        //tradersToIgnore.add("452603383455412224");//kevinDA
        //tradersToIgnore.add("459267987174584320");//doddi
        ArrayList<String> playersToGive = new ArrayList<>();
        //playersToGive.add("Diontae Johnson");


        ArrayList<String> playersNotToGive = new ArrayList<>();
        //playersNotToGive.add("James Conner");

        HashSet<String> givenPlayersToIgnore = new HashSet<>();
        givenPlayersToIgnore.add("Amari Cooper");
        givenPlayersToIgnore.add("Zay Flowers");
        givenPlayersToIgnore.add("Garrett Wilson");
        givenPlayersToIgnore.add("Diontae Johnson");
        givenPlayersToIgnore.add("Jaylen Warren");
        givenPlayersToIgnore.add("Christian Watson");

        ArrayList<String> givenPlayersToRequire = new ArrayList<>();
        //givenPlayersToRequire.add("Brandin Cooks");

        ArrayList<ScoredRoster> scoredRosters = getProjPointsRosters(configuration, projectionSource);
        printRostersWitPointsAndPlayerPoints(scoredRosters);
        printRostersByPoints(scoredRosters);
        ScoredRoster.printWorstStartingQBRosterOrder(scoredRosters);

        PriorityQueue<TradePreviewSerious> tradePreviews;
        if(onlyOne){
            tradePreviews = singleSwapTradeFinderAll(scoredRosters, configuration);
        }
        else if(onlyTwo){
            tradePreviews = doubleSwapTradeFinderAll(scoredRosters, configuration);
        }
        else {
            tradePreviews = singleDoubleTripleSwapFinderAll(scoredRosters, configuration);
        }

        if(onlyOne) {
            ArrayList<TradePreviewSerious> xyz2Arr = new ArrayList<>();
            Iterator<TradePreviewSerious> it = tradePreviews.iterator();
            while (it.hasNext()) {
                xyz2Arr.add(it.next());
            }

            int xyz2Len = tradePreviews.size();
            for (int i = 0; i < xyz2Len; i++) {
                tradePreviews.poll();
            }

            for (int i = 0; i < xyz2Arr.size() - 1; i++) {
                boolean hasDup = false;
                for (int j = i + 1; j < xyz2Arr.size(); j++) {
                    TradePreviewSerious tps1 = xyz2Arr.get(i);
                    TradePreviewSerious tps2 = xyz2Arr.get(j);
                    if(tps1.t1p1Score.player.sportRadarID == null){
                        //System.out.println("null srid is from " + tps1.t1p1Score.player.firstName
                         //       + " "
                         //       +  tps1.t1p1Score.player.lastName );
                        continue;
                    }
                    if (tps1.t1p1Score.player.sportRadarID.equals(tps2.t1p1Score.player.sportRadarID)) {
                        if (tps1.t2p1Score.player.sportRadarID.equals(tps2.t2p1Score.player.sportRadarID)) {
                            hasDup = true;
                            break;
                        }
                    }
                }
                if (!hasDup) {
                    tradePreviews.add(xyz2Arr.get(i));
                }
            }
        }

        ArrayList<Keeper> hardcodedKeepersArray = Keeper.hardcodedAllPotentialKeepers();
        HashSet<Player> hardcodedKeepers = new HashSet<>();
        for(Keeper k : hardcodedKeepersArray){
            hardcodedKeepers.add(k.player);
        }

        if(roundFilter){
            ArrayList<TradePreviewSerious> xyz2Arr = new ArrayList<>();
            Iterator<TradePreviewSerious> it = tradePreviews.iterator();
            while (it.hasNext()) {
                xyz2Arr.add(it.next());
            }

            int xyz2Len = tradePreviews.size();
            for (int i = 0; i < xyz2Len; i++) {
                tradePreviews.poll();
            }

            for (int i = 0; i < xyz2Arr.size() - 1; i++) {
                boolean passesRoundVibe = false;
                TradePreviewSerious tps1 = xyz2Arr.get(i);
                Player t1p1 = tps1.t1p1Score.player;
                Player t2p1 = tps1.t2p1Score.player;

                if(HardcodedDraftUtil.getRoundPlayer(t1p1) <= HardcodedDraftUtil.getRoundPlayer(t2p1)){
                    if(!hardcodedKeepers.contains(t1p1) && !hardcodedKeepers.contains(t2p1)) {
                        passesRoundVibe = true;
                    }
                }

                if (passesRoundVibe) {
                    tradePreviews.add(xyz2Arr.get(i));
                }
            }
        }

        ArrayList<TradePreviewSerious> allT0 = new ArrayList<>();
        ArrayList<TradePreviewSerious> allT1 = new ArrayList<>();
        ArrayList<TradePreviewSerious> allT2 = new ArrayList<>();
        ArrayList<TradePreviewSerious> allT3 = new ArrayList<>();
        ArrayList<TradePreviewSerious> allT4 = new ArrayList<>();
        ArrayList<TradePreviewSerious> allT5 = new ArrayList<>();
        ArrayList<TradePreviewSerious> allT6 = new ArrayList<>();
        ArrayList<TradePreviewSerious> allT7 = new ArrayList<>();
        ArrayList<TradePreviewSerious> allT8 = new ArrayList<>();
        ArrayList<TradePreviewSerious> allT9 = new ArrayList<>();
        ArrayList<TradePreviewSerious> allT10 = new ArrayList<>();

        ArrayList<String> playersOfIgnoredTraders = new ArrayList<>();
        for(String ignoredTrader : tradersToIgnore){
            for(ScoredRoster fpRos : scoredRosters){
                if(ignoredTrader.equals(fpRos.userID)){
                    for(Score score : fpRos.draftedPlayersWithProj){
                        playersOfIgnoredTraders.add(score.player.sportRadarID);
                    }
                }
            }
        }


        while(!tradePreviews.isEmpty()){

            TradePreviewSerious temp = tradePreviews.poll();

            boolean foundIgnoredPlayer = false;
            for(String playerOfIgnoredTraded : playersOfIgnoredTraders){

                String pID = temp.t2p1Score.player.sportRadarID;
                if(pID.equals(playerOfIgnoredTraded)){
                    foundIgnoredPlayer = true;
                    break;
                }
            }
            if(foundIgnoredPlayer){
                continue;
            }

            boolean allRequiredPlayersFound = true;
            for(String name : givenPlayersToRequire) {
                boolean requiredPlayerFound = false;
                String givenFirstName = name.split(" ")[0];
                String givenLastName = name.split(" ")[1];
                if(temp.t2p1Score.player.firstName.equals(givenFirstName) && temp.t2p1Score.player.lastName.equals(givenLastName)){
                    requiredPlayerFound = true;
                }
                if(temp.t2p2Score != null){
                    if(temp.t2p2Score.player.firstName.equals(givenFirstName) && temp.t2p2Score.player.lastName.equals(givenLastName)){
                        requiredPlayerFound = true;
                    }
                }
                if(temp.t2p3Score != null) {
                    if (temp.t2p3Score.player.firstName.equals(givenFirstName) && temp.t2p3Score.player.lastName.equals(givenLastName)) {
                        requiredPlayerFound = true;
                    }
                }
                if(!requiredPlayerFound){
                    allRequiredPlayersFound = false;
                    break;
                }
            }
            if(!allRequiredPlayersFound){
                continue;
            }


            boolean allRequiredGivenPlayersFound = true;
            for(String name : playersToGive) {
                boolean requiredGivenPlayerFound = false;
                String givenFirstName = name.split(" ")[0];
                String givenLastName = name.split(" ")[1];
                if(temp.t1p1Score.player.firstName.equals(givenFirstName) && temp.t1p1Score.player.lastName.equals(givenLastName)){
                    requiredGivenPlayerFound = true;
                }
                if(temp.t1p2Score != null){
                    if(temp.t1p2Score.player.firstName.equals(givenFirstName) && temp.t1p2Score.player.lastName.equals(givenLastName)){
                        requiredGivenPlayerFound = true;
                    }
                }
                if(temp.t1p3Score != null) {
                    if (temp.t1p3Score.player.firstName.equals(givenFirstName) && temp.t1p3Score.player.lastName.equals(givenLastName)) {
                        requiredGivenPlayerFound = true;
                    }
                }
                if(!requiredGivenPlayerFound){
                    allRequiredGivenPlayersFound = false;
                    break;
                }
            }
            if(!allRequiredGivenPlayersFound){
                continue;
            }



            boolean playerToignoreFound = false;
            for(String name : givenPlayersToIgnore) {
                String givenFirstName = name.split(" ")[0];
                String givenLastName = name.split(" ")[1];
                if(temp.t2p1Score.player.firstName.equals(givenFirstName) && temp.t2p1Score.player.lastName.equals(givenLastName)){
                    playerToignoreFound = true;
                }
                if(temp.t2p2Score != null){
                    if(temp.t2p2Score.player.firstName.equals(givenFirstName) && temp.t2p2Score.player.lastName.equals(givenLastName)){
                        playerToignoreFound = true;
                    }
                }
                if(temp.t2p3Score != null) {
                    if (temp.t2p3Score.player.firstName.equals(givenFirstName) && temp.t2p3Score.player.lastName.equals(givenLastName)) {
                        playerToignoreFound = true;
                    }
                }
            }
            if(playerToignoreFound){
                continue;
            }



            boolean playerNotToGiveFound = false;
            for(String name : playersNotToGive) {
                String givenFirstName = name.split(" ")[0];
                String givenLastName = name.split(" ")[1];
                if(temp.t1p1Score.player.firstName.equals(givenFirstName) && temp.t1p1Score.player.lastName.equals(givenLastName)){
                    playerNotToGiveFound = true;
                }
                if(temp.t1p2Score != null){
                    if(temp.t1p2Score.player.firstName.equals(givenFirstName) && temp.t1p2Score.player.lastName.equals(givenLastName)){
                        playerNotToGiveFound = true;
                    }
                }
                if(temp.t1p3Score != null) {
                    if (temp.t1p3Score.player.firstName.equals(givenFirstName) && temp.t1p3Score.player.lastName.equals(givenLastName)) {
                        playerNotToGiveFound = true;
                    }
                }
            }
            if(playerNotToGiveFound){
                continue;
            }



            if(temp.improvementT2 > 5.0 && temp.improvementT2 < 10.0) {
                allT10.add(temp);
            }
            if(temp.improvementT2 > 4.0) {
                allT9.add(temp);
            }
            else if(temp.improvementT2 > 3.0) {
                allT8.add(temp);
            }
            else if(temp.improvementT2 > 2.0) {
                allT7.add(temp);
            }
            else if(temp.improvementT2 > 0.5) {
                allT6.add(temp);
            }
            else if(temp.improvementT2 > -2.0) {
                allT5.add(temp);
            }
            else if(temp.improvementT2 > -4.0) {
                allT4.add(temp);
            }
            else if(temp.improvementT2 > -6.0) {
                allT3.add(temp);
            }
            else if(temp.improvementT2 > -8.0) {
                allT2.add(temp);
            }
            else if(temp.improvementT2 > -10.0) {
                allT1.add(temp);
            }
            else if(temp.improvementT2 > -12.0) {
                allT0.add(temp);
            }
        }

        if(toCrop) {
            int cropSize = 300;
            int t0Max = Math.min(cropSize, allT0.size());
            int t1Max = Math.min(cropSize, allT1.size());
            int t2Max = Math.min(cropSize, allT2.size());
            int t3Max = Math.min(cropSize, allT3.size());
            int t4Max = Math.min(cropSize, allT4.size());
            int t5Max = Math.min(cropSize, allT5.size());
            int t6Max = Math.min(cropSize, allT6.size());
            int t7Max = Math.min(cropSize, allT7.size());
            int t8Max = Math.min(cropSize, allT8.size());
            int t9Max = Math.min(cropSize, allT9.size());
            int t10Max = Math.min(cropSize, allT10.size());

            allT0 = new ArrayList<TradePreviewSerious>(allT0.subList(0, t0Max));
            allT1 = new ArrayList<TradePreviewSerious>(allT1.subList(0, t1Max));
            allT2 = new ArrayList<TradePreviewSerious>(allT2.subList(0, t2Max));
            allT3 = new ArrayList<TradePreviewSerious>(allT3.subList(0, t3Max));
            allT4 = new ArrayList<TradePreviewSerious>(allT4.subList(0, t4Max));
            allT5 = new ArrayList<TradePreviewSerious>(allT5.subList(0, t5Max));
            allT6 = new ArrayList<TradePreviewSerious>(allT6.subList(0, t6Max));
            allT7 = new ArrayList<TradePreviewSerious>(allT7.subList(0, t7Max));
            allT8 = new ArrayList<TradePreviewSerious>(allT8.subList(0, t8Max));
            allT9 = new ArrayList<TradePreviewSerious>(allT9.subList(0, t9Max));
            allT10 = new ArrayList<TradePreviewSerious>(allT10.subList(0, t10Max));
        }


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

        for(TradePreviewSerious temp : allT10){
            String t10TempString = TradePreviewSerious.printTradePreviewString(temp);
            t10Strings.add(t10TempString);
        }
        for(TradePreviewSerious temp : allT9){
            String t9TempString = TradePreviewSerious.printTradePreviewString(temp);
            t9Strings.add(t9TempString);
        }
        for(TradePreviewSerious temp : allT8){
            String t8TempString = TradePreviewSerious.printTradePreviewString(temp);
            t8Strings.add(t8TempString);
        }
        for(TradePreviewSerious temp : allT7){
            String t7TempString = TradePreviewSerious.printTradePreviewString(temp);
            t7Strings.add(t7TempString);
        }
        for(TradePreviewSerious temp : allT6){
            String t6TempString = TradePreviewSerious.printTradePreviewString(temp);
            t6Strings.add(t6TempString);
        }
        for(TradePreviewSerious temp : allT5){
            String t5TempString = TradePreviewSerious.printTradePreviewString(temp);
            t5Strings.add(t5TempString);
        }
        for(TradePreviewSerious temp : allT4){
            String t4TempString = TradePreviewSerious.printTradePreviewString(temp);
            t4Strings.add(t4TempString);
        }
        for(TradePreviewSerious temp : allT3){
            String t3TempString = TradePreviewSerious.printTradePreviewString(temp);
            t3Strings.add(t3TempString);
        }
        for(TradePreviewSerious temp : allT2){
            String t2TempString = TradePreviewSerious.printTradePreviewString(temp);
            t2Strings.add(t2TempString);
        }
        for(TradePreviewSerious temp : allT1){
            String t1TempString = TradePreviewSerious.printTradePreviewString(temp);
            t1Strings.add(t1TempString);
        }
        for(TradePreviewSerious temp : allT0){
            String t0TempString = TradePreviewSerious.printTradePreviewString(temp);
            t0Strings.add(t0TempString);
        }
        String fileStringStart = "twoTeamTrade";
        String fileString = "Xignoring" + givenPlayersToIgnore.size() + "Xreq" + givenPlayersToRequire.size();


        FileWriter writer10 = new FileWriter(fileStringStart + "t10" + fileString + ".txt");
        for(String str: t10Strings) {
            writer10.write(str + System.lineSeparator());
        }
        writer10.close();

        FileWriter writer9 = new FileWriter(fileStringStart + "t9" + fileString + ".txt");
        for(String str: t9Strings) {
            writer9.write(str + System.lineSeparator());
        }
        writer9.close();

        FileWriter writer8 = new FileWriter(fileStringStart + "t8" + fileString + ".txt");
        for(String str: t8Strings) {
            writer8.write(str + System.lineSeparator());
        }
        writer8.close();

        FileWriter writer7 = new FileWriter(fileStringStart + "t7" + fileString + ".txt");
        for(String str: t7Strings) {
            writer7.write(str + System.lineSeparator());
        }
        writer7.close();

        FileWriter writer6 = new FileWriter(fileStringStart + "t6" + fileString + ".txt");
        for(String str: t6Strings) {
            writer6.write(str + System.lineSeparator());
        }
        writer6.close();

        FileWriter writer5 = new FileWriter(fileStringStart + "t5" + fileString + ".txt");
        for(String str: t5Strings) {
            writer5.write(str + System.lineSeparator());
        }
        writer5.close();

        FileWriter writer4 = new FileWriter(fileStringStart + "t4" + fileString + ".txt");
        for(String str: t4Strings) {
            writer4.write(str + System.lineSeparator());
        }
        writer4.close();

        FileWriter writer3 = new FileWriter(fileStringStart + "t3" + fileString + ".txt");
        for(String str: t3Strings) {
            writer3.write(str + System.lineSeparator());
        }
        writer3.close();

        FileWriter writer2 = new FileWriter(fileStringStart + "t2" + fileString + ".txt");
        for(String str: t2Strings) {
            writer2.write(str + System.lineSeparator());
        }
        writer2.close();

        FileWriter writer1 = new FileWriter(fileStringStart + "t1" + fileString + ".txt");
        for(String str: t1Strings) {
            writer1.write(str + System.lineSeparator());
        }
        writer1.close();

        FileWriter writer0 = new FileWriter(fileStringStart + "t0" + fileString + ".txt");
        for(String str: t0Strings) {
            writer0.write(str + System.lineSeparator());
        }
        writer0.close();

    }

    private static void printRostersWitPointsAndPlayerPoints(ArrayList<ScoredRoster> xyz) {
        for(ScoredRoster fpRos : xyz){
            System.out.print("roster of " + HumanOfInterest.getHumanFromID(fpRos.userID) + "\n");
            for(Score s : fpRos.draftedPlayersWithProj){
                System.out.println("\t" + s.player.firstName + " " + s.player.lastName + "\t score:\t" + s.score);
            }
        }
    }

    public static PriorityQueue<TradePreviewSerious> singleDoubleTripleSwapFinderAll(ArrayList<ScoredRoster> allRosters, AAAConfiguration aaaConfiguration){
        PriorityQueue<TradePreviewSerious> doubleTripleSwaps = new PriorityQueue<>(5, new TradePreviewSeriousComparator());
        PriorityQueue<TradePreviewSerious> singleSwaps = singleSwapTradeFinderAll(allRosters, aaaConfiguration);
        PriorityQueue<TradePreviewSerious> doubleSwaps = doubleSwapTradeFinderAll(allRosters, aaaConfiguration);
        PriorityQueue<TradePreviewSerious> tripleSwaps = tripleSwapTradeFinderAll(allRosters, aaaConfiguration);
        doubleTripleSwaps.addAll(singleSwaps);
        doubleTripleSwaps.addAll(doubleSwaps);
        doubleTripleSwaps.addAll(tripleSwaps);
        return doubleTripleSwaps;
    }

    private static ArrayList<ScoredRoster> getCopyOfAllRosters(ArrayList<ScoredRoster> allRosters) {
        ArrayList<ScoredRoster> allRostersCopy = new ArrayList<>();
        for(ScoredRoster fpRos : allRosters){
            allRostersCopy.add(ScoredRoster.makeCopy(fpRos));
        }
        return allRostersCopy;
    }

    public static PriorityQueue<TradePreviewSerious> singleSwapTradeFinderAll(ArrayList<ScoredRoster> allRosters, AAAConfiguration aaaConfiguration){

        PriorityQueue<TradePreviewSerious> allTrades = new PriorityQueue<>(5, new TradePreviewSeriousComparator());
        for(int i=0; i<allRosters.size()-1; i++){
            ArrayList<ScoredRoster> allRostersCopy = getCopyOfAllRosters(allRosters);
            PriorityQueue<TradePreviewSerious> singleTeamTrades = singleSwapTradeFinderSingleTeam(allRostersCopy, i, aaaConfiguration.getMyID());
            allTrades.addAll(singleTeamTrades);
        }
        return allTrades;
    }

    public static PriorityQueue<TradePreviewSerious> doubleSwapTradeFinderAll(ArrayList<ScoredRoster> allRosters, AAAConfiguration aaaConfiguration){

        PriorityQueue<TradePreviewSerious> allTrades = new PriorityQueue<>(5, new TradePreviewSeriousComparator());
        for(int i=0; i<allRosters.size()-1; i++){
            ArrayList<ScoredRoster> allRostersCopy = getCopyOfAllRosters(allRosters);
            PriorityQueue<TradePreviewSerious> singleTeamTrades = doubleSwapTradeFinderSingleTeam(allRostersCopy, i, aaaConfiguration.getMyID());
            allTrades.addAll(singleTeamTrades);
        }
        return allTrades;
    }

    public static PriorityQueue<TradePreviewSerious> tripleSwapTradeFinderAll(ArrayList<ScoredRoster> allRosters, AAAConfiguration aaaConfiguration){

        PriorityQueue<TradePreviewSerious> allTrades = new PriorityQueue<>(5, new TradePreviewSeriousComparator());
        for(int i=0; i<allRosters.size()-1; i++){
            ArrayList<ScoredRoster> allRostersCopy = getCopyOfAllRosters(allRosters);
            PriorityQueue<TradePreviewSerious> singleTeamTradesTriple = tripleSwapTradeFinderSingleTeam(allRostersCopy, i, aaaConfiguration.getMyID());
            allTrades.addAll(singleTeamTradesTriple);
        }
        return allTrades;
    }

    public static ArrayList<ScoredRoster> getProjPointsRosters(AAAConfiguration configuration, ProjectionSource projectionSource){
        ArrayList<ScoredRoster> allRosters = new ArrayList<>();
        for (JsonObject sleeperRoster : getTodaysSleeperRosters(configuration)) {
            String ownerID = getOwnerID(sleeperRoster);
            ArrayList<Player> allPlayersOfTeam = getSleeperPlayersUsingWeirdIDs(sleeperRoster);
            //this is stupid. We set the score inside here. Let's parametrize this
            allRosters.add(new ScoredRoster(ownerID, allPlayersOfTeam, projectionSource));
        }
        return allRosters;

    }

    private static ArrayList<Player> getSleeperPlayersUsingWeirdIDs(JsonObject sleeperRoster) {
        ArrayList<Player> allPlayersOfTeam = new ArrayList<>();
        JsonArray allWeirdIDs = sleeperRoster.getAsJsonArray("players");
        for(JsonElement playerWeirdID : allWeirdIDs){
            Player tempPlayer = Player.getPlayerFromSIDV2(playerWeirdID.getAsString());
            if(tempPlayer == null){
                System.out.println("Here is a null player mistake");
            }
            allPlayersOfTeam.add(tempPlayer);
        }
        return allPlayersOfTeam;
    }

    private static String getOwnerID(JsonObject sleeperRoster) {
        String ownerID = "";
        if(!sleeperRoster.get("owner_id").isJsonNull()) {
            ownerID = sleeperRoster.get("owner_id").getAsString();
        }
        return ownerID;
    }

    public static void printRostersByPoints(ArrayList<ScoredRoster> allRosters){
        ArrayList allTeamOwners = new ArrayList<>();
        for(ScoredRoster fpRost : allRosters){
            TeamOwner teamOwner = TeamOwner.initializeTeamOwnerFromSleeperUserID(fpRost.userID, fpRost.scoreBestROSStartingLineup());
            allTeamOwners.add(teamOwner);
        }
        TeamOwner.printTeamOwnersByPoints(allTeamOwners);
    }

    private static String getTodaysSleeperRosterWebPage(AAAConfiguration configuration){
        return InOutUtilities.getTodaysWebPage(configuration.getRosterWebURL(),
                configuration.getMyNameForLeague());
    }

    private static ArrayList<JsonObject> getTodaysSleeperRosters(AAAConfiguration configuration) {
        String webData = getTodaysSleeperRosterWebPage(configuration);
        JsonElement jsonElement = new JsonParser().parse(webData);
        JsonArray jsonMembers = jsonElement.getAsJsonArray();
        ArrayList<JsonObject> jsonObjects = new ArrayList<>();
        for(JsonElement sleeperRoster : jsonMembers) {
            JsonObject apiObject = sleeperRoster.getAsJsonObject();
            jsonObjects.add(apiObject);
        }
        return jsonObjects;
    }
}
