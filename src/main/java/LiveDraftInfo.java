import com.mongodb.*;

import java.net.UnknownHostException;
import java.util.ArrayList;

public class LiveDraftInfo {



    boolean isFunLeague;
    ArrayList<Player> draftedPlayers;
    ArrayList<Player> rosterPlayers;


    BestAvailablePlayers bestAvailablePlayers;
    String dbKey;

    //TODO add best tiers available info
    public LiveDraftInfo(ArrayList<Player> dp, ArrayList<Player> rp, boolean isFun){
        isFunLeague = isFun;
        draftedPlayers = dp;
        rosterPlayers = rp;
        String partialArrangementEncoded = RoundReport.encodeStringPartialArrangement(getPartialArrangement(rp));
        bestAvailablePlayers = getBestAvailablePlayers(dp, isFun);
        //TODO make best available players depth 3
        // you'll need to do it after best available players
        // since using poll instead of peek will mess things up
        String bestAvailableTiersEncoded = getBestAvailableTiersEncoded(bestAvailablePlayers);
        dbKey = bestAvailableTiersEncoded + partialArrangementEncoded;

    }

    public static  String getBestAvailableTiersEncoded(BestAvailablePlayers bap){
        String tiersString = "";
        tiersString += bap.quarterbackRT.tierNum;
        tiersString += bap.runningBackRT.tierNum;
        tiersString += bap.wideReceiverRT.tierNum;
        tiersString += bap.tightEndRT.tierNum;
        return tiersString;
    }

    public static ArrayList<Position> getPartialArrangement(ArrayList<Player> rosterPlayers){
        //order verified
        ArrayList<Position> partialArrangement = new ArrayList<Position>();
        for(Player rosterPlayer : rosterPlayers){
            Position pos = rosterPlayer.position;
            partialArrangement.add(pos);
        }
        return partialArrangement;
    }

    public static BestAvailablePlayers getBestAvailablePlayers(ArrayList<Player> draftedPlayers, boolean isFun){
        RankOrderedPlayers rop;
        RankTierOrderedPlayers rtop;
        if(isFun){
            rop = RankOrderedPlayers.getRankOrderedPlayerFPSerious();
            rtop= new RankTierOrderedPlayers(rop);
        }
        else{
            rop = RankOrderedPlayers.getRankOrderedPlayerFPSerious();
            rtop = new RankTierOrderedPlayers(rop);
        }

        for(Player player : draftedPlayers){
            rtop.removePlayer(player);
        }

        BestAvailablePlayers bap = new BestAvailablePlayers(rtop);
        return bap;
    }

    public static void LiveDraftPotentialMoveAnalyzer(LiveDraftInfo ldi){

        boolean isFun = ldi.isFunLeague;
        MongoClient mongoClient = null;
        try {
            mongoClient = new MongoClient("localhost");
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        DB database;
        if(isFun){
            database = mongoClient.getDB("FantasyFunLeague");
        }
        else{
            database = mongoClient.getDB("FantasySeriousLeague");
        }
        DBCollection collection = database.getCollection("roundInfo9R");//TODO was changed hardcoded

        String dataBaseKey = ldi.dbKey;
        String dbKeyNextQB = dataBaseKey + "1";
        String dbKeyNextRB = dataBaseKey + "2";
        String dbKeyNextWR = dataBaseKey + "3";
        String dbKeyNextTE = dataBaseKey + "4";
        String dbKeyNextDEF = dataBaseKey + "5";

        String bestQBFirstName = ldi.bestAvailablePlayers.quarterbackRT.player.firstName;
        String bestQBLastName = ldi.bestAvailablePlayers.quarterbackRT.player.lastName;
        int bestQBTier = ldi.bestAvailablePlayers.quarterbackRT.tierNum;
        String bestQBFullName = bestQBFirstName + " " + bestQBLastName;

        String bestRBFirstName = ldi.bestAvailablePlayers.runningBackRT.player.firstName;
        String bestRBLastName = ldi.bestAvailablePlayers.runningBackRT.player.lastName;
        int bestRBTier = ldi.bestAvailablePlayers.runningBackRT.tierNum;
        String bestRBFullName = bestRBFirstName + " " + bestRBLastName;

        String bestWRFirstName = ldi.bestAvailablePlayers.wideReceiverRT.player.firstName;
        String bestWRLastName = ldi.bestAvailablePlayers.wideReceiverRT.player.lastName;
        int bestWRTier = ldi.bestAvailablePlayers.wideReceiverRT.tierNum;
        String bestWRFullName = bestWRFirstName + " " + bestWRLastName;

        String bestTEFirstName = ldi.bestAvailablePlayers.tightEndRT.player.firstName;
        String bestTELastName = ldi.bestAvailablePlayers.tightEndRT.player.lastName;
        int bestTETier = ldi.bestAvailablePlayers.tightEndRT.tierNum;
        String bestTEFullName = bestTEFirstName + " " + bestTELastName;

        String bestDEFFirstName = ldi.bestAvailablePlayers.defenseRT.player.firstName;
        String bestDEFLastName = ldi.bestAvailablePlayers.defenseRT.player.lastName;
        int bestDEFTier = ldi.bestAvailablePlayers.defenseRT.tierNum;
        String bestDEFFullName = bestDEFFirstName + " " + bestDEFLastName;

        System.out.println("Given the projections on FantasyPros and your league settings");

        //TODO make modular by passing 5 arguments so db doesnt restart each time
        BasicDBObject queryQBPos = new BasicDBObject();
        queryQBPos.put("tierArrID", dbKeyNextQB);
        DBObject entryQBPos = collection.findOne(queryQBPos);
        int entrySizeQBPos = collection.find(queryQBPos).limit(1).size();
        boolean keyFoundQBPos = true;
        if(entryQBPos == null || entrySizeQBPos == 0){
            keyFoundQBPos = false;
        }
        int numTimesSimulatedBestQBPos = 0;
        double averageScoreBestQBPos = 0.0;
        if(keyFoundQBPos){
            numTimesSimulatedBestQBPos = (int) entryQBPos.get("numTimesAverage");
            averageScoreBestQBPos = (double) entryQBPos.get("averageScore");

        }
        // bad start
        //
        BasicDBObject queryRBPos = new BasicDBObject();
        queryRBPos.put("tierArrID", dbKeyNextRB);
        DBObject entryRBPos = collection.findOne(queryRBPos);
        int entrySizeRBPos = collection.find(queryRBPos).limit(1).size();
        boolean keyFoundRBPos = true;
        if(entryRBPos == null || entrySizeRBPos == 0){
            keyFoundRBPos = false;
        }
        int numTimesSimulatedBestRBPos = 0;
        double averageScoreBestRBPos = 0.0;
        if(keyFoundRBPos){
            numTimesSimulatedBestRBPos = (int) entryRBPos.get("numTimesAverage");
            averageScoreBestRBPos = (double) entryRBPos.get("averageScore");

        }
        //
        BasicDBObject queryWRPos = new BasicDBObject();
        queryWRPos.put("tierArrID", dbKeyNextWR);
        DBObject entryWRPos = collection.findOne(queryWRPos);
        int entrySizeWRPos = collection.find(queryWRPos).limit(1).size();
        boolean keyFoundWRPos = true;
        if(entryWRPos == null || entrySizeWRPos == 0){
            keyFoundWRPos = false;
        }
        int numTimesSimulatedBestWRPos = 0;
        double averageScoreBestWRPos = 0.0;
        if(keyFoundWRPos){
            numTimesSimulatedBestWRPos = (int) entryWRPos.get("numTimesAverage");
            averageScoreBestWRPos = (double) entryWRPos.get("averageScore");

        }
        //
        BasicDBObject queryTEPos = new BasicDBObject();
        queryTEPos.put("tierArrID", dbKeyNextTE);
        DBObject entryTEPos = collection.findOne(queryTEPos);
        int entrySizeTEPos = collection.find(queryTEPos).limit(1).size();
        boolean keyFoundTEPos = true;
        if(entryTEPos == null || entrySizeTEPos == 0){
            keyFoundTEPos = false;
        }
        int numTimesSimulatedBestTEPos = 0;
        double averageScoreBestTEPos = 0.0;
        if(keyFoundTEPos){
            numTimesSimulatedBestTEPos = (int) entryTEPos.get("numTimesAverage");
            averageScoreBestTEPos = (double) entryTEPos.get("averageScore");

        }
        //
        BasicDBObject queryDEFPos = new BasicDBObject();
        queryDEFPos.put("tierArrID", dbKeyNextDEF);
        DBObject entryDEFPos = collection.findOne(queryDEFPos);
        int entrySizeDEFPos = collection.find(queryDEFPos).limit(1).size();
        boolean keyFoundDEFPos = true;
        if(entryDEFPos == null || entrySizeDEFPos == 0){
            keyFoundDEFPos = false;
        }
        int numTimesSimulatedBestDEFPos = 0;
        double averageScoreBestDEFPos = 0.0;
        if(keyFoundDEFPos){
            numTimesSimulatedBestDEFPos = (int) entryDEFPos.get("numTimesAverage");
            averageScoreBestDEFPos = (double) entryDEFPos.get("averageScore");

        }
        //
        //bad end






        System.out.println("Best QB is:\t" + bestQBFullName +
                "\t tier is:\t" + bestQBTier +
                "\tAvScore:\t" + averageScoreBestQBPos +
                "\tNumSimulations\t" + numTimesSimulatedBestQBPos);

        System.out.println("Best RB is:\t" + bestRBFullName +
                "\t tier is:\t" + bestRBTier +
                "\tAvScore:\t" + averageScoreBestRBPos +
                "\tNumSimulations\t" + numTimesSimulatedBestRBPos);

        System.out.println("Best WR is:\t" + bestWRFullName +
                "\t tier is:\t" + bestWRTier +
                "\tAvScore:\t" + averageScoreBestWRPos +
                "\tNumSimulations\t" + numTimesSimulatedBestWRPos);

        System.out.println("Best TE is:\t" + bestTEFullName +
                "\t tier is:\t" + bestQBTier +
                "\tAvScore:\t" + averageScoreBestTEPos +
                "\tNumSimulations\t" + numTimesSimulatedBestTEPos);

        System.out.println("Best DEF is:\t" + bestDEFFullName +
                "\t tier is:\t" + bestDEFTier +
                "\tAvScore:\t" + averageScoreBestDEFPos +
                "\tNumSimulations\t" + numTimesSimulatedBestDEFPos);
    }

}
