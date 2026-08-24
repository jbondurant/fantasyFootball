import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

public class TradeFinder {

    /**
     * Pulls my roster out of the list, leaving the teams I could trade with.
     *
     * Used to return null when it could not find me, which then failed several
     * frames away with a NullPointerException and nothing pointing at the
     * cause.
     */
    /**
     * Trades are filed by how much they help the other side, on the theory that
     * a trade nobody else wants is not a trade. Ascending floors: a trade lands
     * in the highest tier whose floor it clears, and anything below the lowest
     * floor is not written down at all.
     */
    private static final double[] TIER_FLOORS = {-20.0, 0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 35.0, 40.0};

    /**
     * An extra bucket, overlapping the ones above, for trades that are a big
     * win for them without being so lopsided that they smell like a mistake.
     */
    static final int STANDOUT_TIER = TIER_FLOORS.length;

    static boolean isStandoutTrade(double improvementForThem){
        return improvementForThem > 45.0 && improvementForThem < 200.0;
    }

    static int tierFor(double improvementForThem){
        for(int tier = TIER_FLOORS.length - 1; tier >= 0; tier--){
            if(improvementForThem > TIER_FLOORS[tier]){
                return tier;
            }
        }
        return -1;
    }

    private static void writeTier(String filePath, List<TradePreviewSerious> trades) throws IOException {
        try (FileWriter writer = new FileWriter(filePath)) {
            for(TradePreviewSerious trade : trades){
                writer.write(TradePreviewSerious.printTradePreviewString(trade) + System.lineSeparator());
            }
        }
    }

    private static ScoredRoster takeMyRoster(ArrayList<ScoredRoster> allRosters, String myID) {
        for(ScoredRoster roster : allRosters){
            if(roster.userID.equals(myID)){
                allRosters.remove(roster);
                return roster;
            }
        }
        throw new IllegalArgumentException("no roster in this league belongs to user " + myID);
    }

    public static PriorityQueue<TradePreviewSerious> singleSwapTradeFinderSingleTeam(ArrayList<ScoredRoster> allRosters, int teamNum, String myID){
        PriorityQueue<TradePreviewSerious> allTrades = new PriorityQueue<>(5, new TradePreviewSeriousComparator());
        ScoredRoster myRoster = takeMyRoster(allRosters, myID);
        ScoredRoster otherTeamsRoster = allRosters.get(teamNum);
        for(int w=0; w < myRoster.draftedPlayersWithProj.size(); w++){
            for(int y=0; y<otherTeamsRoster.draftedPlayersWithProj.size(); y++){
                Score t1p1 = myRoster.draftedPlayersWithProj.get(w);
                Score t2p1 = otherTeamsRoster.draftedPlayersWithProj.get(y);
                TradePreviewSerious tps = new TradePreviewSerious(myRoster, otherTeamsRoster, t1p1, t2p1);
                allTrades.add(tps);

            }

        }

        return allTrades;

    }

    public static PriorityQueue<TradePreviewSerious> doubleSwapTradeFinderSingleTeam(ArrayList<ScoredRoster> allRosters, int teamNum, String myID){
        PriorityQueue<TradePreviewSerious> allTrades = new PriorityQueue<>(5, new TradePreviewSeriousComparator());
        ScoredRoster myRoster = takeMyRoster(allRosters, myID);
        ScoredRoster otherTeamsRoster = allRosters.get(teamNum);
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
        ScoredRoster myRoster = takeMyRoster(allRosters, myID);
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
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        boolean onlyOne = false;
        boolean onlyTwo = false;
        boolean toCrop = false;
        // Sleeper is the only source with real projected points now - see
        // InSeasonProjectionsFP for why the FantasyPros ones are gone.
        ProjectionSource projectionSource = ProjectionSource.SLEEPER;
        boolean roundFilter = false;
        ArrayList<String> tradersToIgnore = new ArrayList<>();
        /*tradersToIgnore.add("452603383455412224");// "Kevin";}
        tradersToIgnore.add("459267987174584320");// "d0ddi";}
        tradersToIgnore.add("464471023782195200");// "itsabust";}
        tradersToIgnore.add("603709077557669888");// "Renteez";}
        tradersToIgnore.add("606234521821577216");// "tommyrads";}
        tradersToIgnore.add("724919475115225088");// "JakeSK";}
        tradersToIgnore.add("725379562434830336");// "jerem9604";}
        tradersToIgnore.add("725953800816373760");// "Hamrliks";}
        tradersToIgnore.add("740473448551366656");// "patekxwater";}
        tradersToIgnore.add("853719913725030400");// "BHier";}
        tradersToIgnore.add("604377190016016384");// "JFMarino";}*/

        ArrayList<String> playersToGive = new ArrayList<>();
        //playersToGive.add("Breece Hall");

        ArrayList<String> playersNotToGive = new ArrayList<>();
        //playersNotToGive.add("James Conner");

        HashSet<String> givenPlayersToIgnore = new HashSet<>();
        //givenPlayersToIgnore.add("Amari Cooper");

        ArrayList<String> givenPlayersToRequire = new ArrayList<>();
        //givenPlayersToRequire.add("DK Metcalf");


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
                        if (tps1.t2p1Score.player.sportRadarID!=null
                            && tps1.t2p1Score.player.sportRadarID.equals(tps2.t2p1Score.player.sportRadarID)){

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

        ArrayList<Keeper> hardcodedKeepersArray = Keeper.allPotentialKeepers();
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

                if(DraftRoundUtil.getRoundPlayer(t1p1) <= DraftRoundUtil.getRoundPlayer(t2p1)){
                    if(!hardcodedKeepers.contains(t1p1) && !hardcodedKeepers.contains(t2p1)) {
                        passesRoundVibe = true;
                    }
                }

                if (passesRoundVibe) {
                    tradePreviews.add(xyz2Arr.get(i));
                }
            }
        }

        // One bucket per "how much this helps the other side" tier, see tierFor.
        List<List<TradePreviewSerious>> tiers = new ArrayList<>();
        for(int tier = 0; tier <= STANDOUT_TIER; tier++){
            tiers.add(new ArrayList<>());
        }

        ArrayList<String> playersOfIgnoredTraders = new ArrayList<>();
        for(String ignoredTrader : tradersToIgnore){
            for(ScoredRoster fpRos : scoredRosters){
                if(ignoredTrader.equals(fpRos.userID)){
                    for(Score score : fpRos.draftedPlayersWithProj){
                        if(projectionSource.equals(ProjectionSource.SLEEPER)){
                            playersOfIgnoredTraders.add(score.player.sleeperIDString);
                        }
                        playersOfIgnoredTraders.add(score.player.sportRadarID);
                    }
                }
            }
        }


        while(!tradePreviews.isEmpty()){

            TradePreviewSerious temp = tradePreviews.poll();
            boolean foundIgnoredPlayer = false;
            for(String playerOfIgnoredTraded : playersOfIgnoredTraders){
                if(projectionSource.equals(ProjectionSource.SLEEPER)){
                    String sID = temp.t2p1Score.player.sleeperIDString;
                    if(sID.equals(playerOfIgnoredTraded)){
                        foundIgnoredPlayer = true;
                        break;
                    }
                }
                else{
                    String pID = temp.t2p1Score.player.sportRadarID;
                    if(pID.equals(playerOfIgnoredTraded)){
                        foundIgnoredPlayer = true;
                        break;
                    }
                }

            }
            if(foundIgnoredPlayer){
                continue;
            }

            List<Player> given = TradeFilter.playersGiven(temp);
            List<Player> received = TradeFilter.playersReceived(temp);

            if(!TradeFilter.includesAll(received, givenPlayersToRequire)){
                continue;
            }
            if(!TradeFilter.includesAll(given, playersToGive)){
                continue;
            }
            if(TradeFilter.includesAny(received, new ArrayList<>(givenPlayersToIgnore))){
                continue;
            }
            if(TradeFilter.includesAny(given, playersNotToGive)){
                continue;
            }

            int tier = tierFor(temp.improvementT2);
            if(tier >= 0){
                tiers.get(tier).add(temp);
            }
            if(isStandoutTrade(temp.improvementT2)){
                tiers.get(STANDOUT_TIER).add(temp);
            }
        }

        if(toCrop) {
            int cropSize = 300;
            for(int tier = 0; tier <= STANDOUT_TIER; tier++){
                List<TradePreviewSerious> bucket = tiers.get(tier);
                tiers.set(tier, new ArrayList<>(bucket.subList(0, Math.min(cropSize, bucket.size()))));
            }
        }

        String fileStringStart = "twoTeamTrade";
        String fileString = "Xignoring" + givenPlayersToIgnore.size() + "Xreq" + givenPlayersToRequire.size();

        for(int tier = 0; tier <= STANDOUT_TIER; tier++){
            writeTier(fileStringStart + "t" + tier + fileString + ".txt", tiers.get(tier));
        }

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

    private static int countOpponents(ArrayList<ScoredRoster> allRosters, String myID){
        int mine = 0;
        for(ScoredRoster roster : allRosters){
            if(roster.userID.equals(myID)){
                mine++;
            }
        }
        if(mine == 0){
            throw new IllegalArgumentException("no roster in this league belongs to user " + myID);
        }
        return allRosters.size() - mine;
    }

    private static ArrayList<ScoredRoster> getCopyOfAllRosters(ArrayList<ScoredRoster> allRosters) {
        ArrayList<ScoredRoster> allRostersCopy = new ArrayList<>();
        for(ScoredRoster fpRos : allRosters){
            allRostersCopy.add(ScoredRoster.makeCopy(fpRos));
        }
        return allRostersCopy;
    }

    public static PriorityQueue<TradePreviewSerious> singleSwapTradeFinderAll(ArrayList<ScoredRoster> allRosters, AAAConfiguration aaaConfiguration){
        return singleSwapTradeFinderAll(allRosters, aaaConfiguration.getMyID());
    }

    public static PriorityQueue<TradePreviewSerious> singleSwapTradeFinderAll(ArrayList<ScoredRoster> allRosters, String myID){
        PriorityQueue<TradePreviewSerious> allTrades = new PriorityQueue<>(5, new TradePreviewSeriousComparator());
        // One pass per team I could trade with, which is everyone but me. The
        // bound used to be allRosters.size() - 1, which only lined up because
        // taking my roster out happens to leave exactly that many.
        int opponents = countOpponents(allRosters, myID);
        for(int i = 0; i < opponents; i++){
            ArrayList<ScoredRoster> allRostersCopy = getCopyOfAllRosters(allRosters);
            allTrades.addAll(singleSwapTradeFinderSingleTeam(allRostersCopy, i, myID));
        }
        return allTrades;
    }

    public static PriorityQueue<TradePreviewSerious> doubleSwapTradeFinderAll(ArrayList<ScoredRoster> allRosters, AAAConfiguration aaaConfiguration){
        return doubleSwapTradeFinderAll(allRosters, aaaConfiguration.getMyID());
    }

    public static PriorityQueue<TradePreviewSerious> doubleSwapTradeFinderAll(ArrayList<ScoredRoster> allRosters, String myID){
        PriorityQueue<TradePreviewSerious> allTrades = new PriorityQueue<>(5, new TradePreviewSeriousComparator());
        // One pass per team I could trade with, which is everyone but me. The
        // bound used to be allRosters.size() - 1, which only lined up because
        // taking my roster out happens to leave exactly that many.
        int opponents = countOpponents(allRosters, myID);
        for(int i = 0; i < opponents; i++){
            ArrayList<ScoredRoster> allRostersCopy = getCopyOfAllRosters(allRosters);
            allTrades.addAll(doubleSwapTradeFinderSingleTeam(allRostersCopy, i, myID));
        }
        return allTrades;
    }

    public static PriorityQueue<TradePreviewSerious> tripleSwapTradeFinderAll(ArrayList<ScoredRoster> allRosters, AAAConfiguration aaaConfiguration){
        return tripleSwapTradeFinderAll(allRosters, aaaConfiguration.getMyID());
    }

    public static PriorityQueue<TradePreviewSerious> tripleSwapTradeFinderAll(ArrayList<ScoredRoster> allRosters, String myID){
        PriorityQueue<TradePreviewSerious> allTrades = new PriorityQueue<>(5, new TradePreviewSeriousComparator());
        // One pass per team I could trade with, which is everyone but me. The
        // bound used to be allRosters.size() - 1, which only lined up because
        // taking my roster out happens to leave exactly that many.
        int opponents = countOpponents(allRosters, myID);
        for(int i = 0; i < opponents; i++){
            ArrayList<ScoredRoster> allRostersCopy = getCopyOfAllRosters(allRosters);
            allTrades.addAll(tripleSwapTradeFinderSingleTeam(allRostersCopy, i, myID));
        }
        return allTrades;
    }

    public static ArrayList<ScoredRoster> getProjPointsRosters(AAAConfiguration configuration, ProjectionSource projectionSource){
        ArrayList<ScoredRoster> allRosters = new ArrayList<>();
        for (JsonObject sleeperRoster : getTodaysSleeperRosters(configuration)) {
            String ownerID = getOwnerID(sleeperRoster);
            ArrayList<Player> allPlayersOfTeam = getSleeperPlayersUsingWeirdIDs(sleeperRoster);
            allRosters.add(new ScoredRoster(ownerID, allPlayersOfTeam, projectionSource));
        }
        return allRosters;

    }

    private static ArrayList<Player> getSleeperPlayersUsingWeirdIDs(JsonObject sleeperRoster) {
        ArrayList<Player> allPlayersOfTeam = new ArrayList<>();
        JsonArray allWeirdIDs = sleeperRoster.getAsJsonArray("players");
        if(allWeirdIDs == null){
            // An expansion team, or one whose players were all dropped.
            return allPlayersOfTeam;
        }
        for(JsonElement playerWeirdID : allWeirdIDs){
            String sleeperID = playerWeirdID.getAsString();
            Player tempPlayer = Player.getPlayerFromSIDV2(sleeperID);
            if(tempPlayer == null){
                // Naming the id matters: the old message said only "Here is a
                // null player mistake", which told you nothing about who.
                System.out.println("no sleeper player for id " + sleeperID + "; leaving them off the roster");
                continue;
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
        ArrayList<TeamOwner> allTeamOwners = new ArrayList<>();
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
        JsonElement jsonElement = JsonParser.parseString(webData);
        JsonArray jsonMembers = jsonElement.getAsJsonArray();
        ArrayList<JsonObject> jsonObjects = new ArrayList<>();
        for(JsonElement sleeperRoster : jsonMembers) {
            JsonObject apiObject = sleeperRoster.getAsJsonObject();
            jsonObjects.add(apiObject);
        }
        return jsonObjects;
    }
}
