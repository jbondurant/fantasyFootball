import com.mongodb.*;

import java.net.UnknownHostException;
import java.util.ArrayList;

public class LiveDraftInfoFunBulk {

//TODO

    boolean isFunLeague;
    ArrayList<Player> draftedPlayers;
    ArrayList<Player> rosterPlayers;


    BestAvailablePlayers bestAvailablePlayers;
    String dbKey;

    public LiveDraftInfoFunBulk(ArrayList<Player> dp, ArrayList<Player> rp, boolean isFun){
        isFunLeague = isFun;
        draftedPlayers = dp;
        rosterPlayers = rp;
        String partialArrangementEncoded = RoundReport.encodeStringPartialArrangement(getPartialArrangement(rp));
        bestAvailablePlayers = getBestAvailablePlayers(dp, isFun);

        String bestAvailableTiersEncoded = getBestAvailableTiersEncodedFunBulk(bestAvailablePlayers);
        dbKey = bestAvailableTiersEncoded + partialArrangementEncoded;

    }

    public static  String getBestAvailableTiersEncodedFunBulk(BestAvailablePlayers bap){
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

    public static void LiveDraftPotentialMoveAnalyzer(LiveDraftInfoFunBulk ldifb){

        boolean isFun = ldifb.isFunLeague;
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

        String dataBaseKey = ldifb.dbKey;
        String dbKeyNextQBPos = dataBaseKey + "1";
        String dbKeyNextRBPos = dataBaseKey + "2";
        String dbKeyNextWRPos = dataBaseKey + "3";
        String dbKeyNextTEPos = dataBaseKey + "4";
        String dbKeyNextDEFPos = dataBaseKey + "5";

        String bestQBFirstName = ldifb.bestAvailablePlayers.quarterbackRT.player.firstName;
        String bestQBLastName = ldifb.bestAvailablePlayers.quarterbackRT.player.lastName;
        int bestQBTier = ldifb.bestAvailablePlayers.quarterbackRT.tierNum;
        String bestQBFullName = bestQBFirstName + " " + bestQBLastName;

        String bestRBFirstName = ldifb.bestAvailablePlayers.runningBackRT.player.firstName;
        String bestRBLastName = ldifb.bestAvailablePlayers.runningBackRT.player.lastName;
        int bestRBTier = ldifb.bestAvailablePlayers.runningBackRT.tierNum;
        String bestRBFullName = bestRBFirstName + " " + bestRBLastName;

        String bestWRFirstName = ldifb.bestAvailablePlayers.wideReceiverRT.player.firstName;
        String bestWRLastName = ldifb.bestAvailablePlayers.wideReceiverRT.player.lastName;
        int bestWRTier = ldifb.bestAvailablePlayers.wideReceiverRT.tierNum;
        String bestWRFullName = bestWRFirstName + " " + bestWRLastName;

        String bestTEFirstName = ldifb.bestAvailablePlayers.tightEndRT.player.firstName;
        String bestTELastName = ldifb.bestAvailablePlayers.tightEndRT.player.lastName;
        int bestTETier = ldifb.bestAvailablePlayers.tightEndRT.tierNum;
        String bestTEFullName = bestTEFirstName + " " + bestTELastName;

        String bestDEFFirstName = ldifb.bestAvailablePlayers.defenseRT.player.firstName;
        String bestDEFLastName = ldifb.bestAvailablePlayers.defenseRT.player.lastName;
        int bestDEFTier = ldifb.bestAvailablePlayers.defenseRT.tierNum;
        String bestDEFFullName = bestDEFFirstName + " " + bestDEFLastName;

        System.out.println("Given the projections on FantasyPros and your league settings");

        BasicDBObject queryQBPos = new BasicDBObject();
        queryQBPos.put("tierArrID", dbKeyNextQBPos);
        DBCursor entryQBPosAll = collection.find(queryQBPos);
        int entrySizeQBPos = collection.find(queryQBPos).limit(1).size();
        boolean keyFoundQBPos = true;
        if(entrySizeQBPos == 0){
            keyFoundQBPos = false;
        }

        int numTimesSimulatedBestQBPos = 0;
        double averageScoreBestQBPos = 0.0;



        if(keyFoundQBPos){
            ArrayList<ToDelPair> allPairs = new ArrayList<>();
            for (DBObject entryQBPos : entryQBPosAll) {
                int nta = (int) entryQBPos.get("numTimesAverage");
                double a = (double) entryQBPos.get("averageScore");
                allPairs.add(new ToDelPair(nta, a));
            }
            ToDelPair compoundAvPair = ToDelPair.getCompoundAvPair(allPairs);
            numTimesSimulatedBestQBPos = compoundAvPair.numTimesAv;
            averageScoreBestQBPos = compoundAvPair.average;
        }

        //TODO for non QB

        //
        //Bad start
        BasicDBObject queryRBPos = new BasicDBObject();
        queryRBPos.put("tierArrID", dbKeyNextRBPos);
        DBCursor entryRBPosAll = collection.find(queryRBPos);
        int entrySizeRBPos = collection.find(queryRBPos).limit(1).size();
        boolean keyFoundRBPos = true;
        if(entrySizeRBPos == 0){
            keyFoundRBPos = false;
        }

        int numTimesSimulatedBestRBPos = 0;
        double averageScoreBestRBPos = 0.0;



        if(keyFoundRBPos){
            ArrayList<ToDelPair> allPairs = new ArrayList<>();
            for (DBObject entryRBPos : entryRBPosAll) {
                int nta = (int) entryRBPos.get("numTimesAverage");
                double a = (double) entryRBPos.get("averageScore");
                allPairs.add(new ToDelPair(nta, a));
            }
            ToDelPair compoundAvPair = ToDelPair.getCompoundAvPair(allPairs);
            numTimesSimulatedBestRBPos = compoundAvPair.numTimesAv;
            averageScoreBestRBPos = compoundAvPair.average;
        }
        BasicDBObject queryWRPos = new BasicDBObject();
        queryWRPos.put("tierArrID", dbKeyNextWRPos);
        DBCursor entryWRPosAll = collection.find(queryWRPos);
        int entrySizeWRPos = collection.find(queryWRPos).limit(1).size();
        boolean keyFoundWRPos = true;
        if(entrySizeWRPos == 0){
            keyFoundWRPos = false;
        }

        int numTimesSimulatedBestWRPos = 0;
        double averageScoreBestWRPos = 0.0;



        if(keyFoundWRPos){
            ArrayList<ToDelPair> allPairs = new ArrayList<>();
            for (DBObject entryWRPos : entryWRPosAll) {
                int nta = (int) entryWRPos.get("numTimesAverage");
                double a = (double) entryWRPos.get("averageScore");
                allPairs.add(new ToDelPair(nta, a));
            }
            ToDelPair compoundAvPair = ToDelPair.getCompoundAvPair(allPairs);
            numTimesSimulatedBestWRPos = compoundAvPair.numTimesAv;
            averageScoreBestWRPos = compoundAvPair.average;
        }

        BasicDBObject queryTEPos = new BasicDBObject();
        queryTEPos.put("tierArrID", dbKeyNextTEPos);
        DBCursor entryTEPosAll = collection.find(queryTEPos);
        int entrySizeTEPos = collection.find(queryTEPos).limit(1).size();
        boolean keyFoundTEPos = true;
        if(entrySizeTEPos == 0){
            keyFoundTEPos = false;
        }

        int numTimesSimulatedBestTEPos = 0;
        double averageScoreBestTEPos = 0.0;



        if(keyFoundTEPos){
            ArrayList<ToDelPair> allPairs = new ArrayList<>();
            for (DBObject entryTEPos : entryTEPosAll) {
                int nta = (int) entryTEPos.get("numTimesAverage");
                double a = (double) entryTEPos.get("averageScore");
                allPairs.add(new ToDelPair(nta, a));
            }
            ToDelPair compoundAvPair = ToDelPair.getCompoundAvPair(allPairs);
            numTimesSimulatedBestTEPos = compoundAvPair.numTimesAv;
            averageScoreBestTEPos = compoundAvPair.average;
        }

        BasicDBObject queryDEFPos = new BasicDBObject();
        queryDEFPos.put("tierArrID", dbKeyNextDEFPos);
        DBCursor entryDEFPosAll = collection.find(queryDEFPos);
        int entrySizeDEFPos = collection.find(queryDEFPos).limit(1).size();
        boolean keyFoundDEFPos = true;
        if(entrySizeDEFPos == 0){
            keyFoundDEFPos = false;
        }

        int numTimesSimulatedBestDEFPos = 0;
        double averageScoreBestDEFPos = 0.0;



        if(keyFoundDEFPos){
            ArrayList<ToDelPair> allPairs = new ArrayList<>();
            for (DBObject entryDEFPos : entryDEFPosAll) {
                int nta = (int) entryDEFPos.get("numTimesAverage");
                double a = (double) entryDEFPos.get("averageScore");
                allPairs.add(new ToDelPair(nta, a));
            }
            ToDelPair compoundAvPair = ToDelPair.getCompoundAvPair(allPairs);
            numTimesSimulatedBestDEFPos = compoundAvPair.numTimesAv;
            averageScoreBestDEFPos = compoundAvPair.average;
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
                "\t tier is:\t" + bestTETier +
                "\tAvScore:\t" + averageScoreBestTEPos +
                "\tNumSimulations\t" + numTimesSimulatedBestTEPos);

        System.out.println("Best DEF is:\t" + bestDEFFullName +
                "\t tier is:\t" + bestDEFTier +
                "\tAvScore:\t" + averageScoreBestDEFPos +
                "\tNumSimulations\t" + numTimesSimulatedBestDEFPos);
    }

}
