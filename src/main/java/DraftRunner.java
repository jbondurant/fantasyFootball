import com.google.common.collect.Collections2;
import com.mongodb.*;
import org.bson.types.ObjectId;
import org.checkerframework.checker.units.qual.A;


import java.net.UnknownHostException;
import java.util.*;

public class DraftRunner {


    public static ArrayList<DraftReport> runDrafts(int n, boolean isFun) {
        ArrayList<DraftReport> multiDraftReport = new ArrayList<DraftReport>();

        double topScore = Double.MIN_VALUE;
        double minScore = Double.MAX_VALUE;


        int numQB1 = 0;
        int numRB1 = 0;
        int numWR1 = 0;
        int numTE1 = 0;
        double averageQB1Calc = 0.0;
        double averageRB1Calc = 0.0;
        double averageWR1Calc = 0.0;
        double averageTE1Calc = 0.0;

        /*
        ArrayList<Double> qb1Scores = new ArrayList<Double>();
        ArrayList<Double> rb1Scores = new ArrayList<Double>();
        ArrayList<Double> wr1Scores = new ArrayList<Double>();
        ArrayList<Double> te1Scores = new ArrayList<Double>();
         */

        for (int i = 0; i < n; i++) {
            SimulationDraft simDraft = SimulationDraft.getSeriousSimulation();
            if (isFun) {
                simDraft = SimulationDraft.getFunSimulation();
            }
            int posEncoded = (int) simDraft.draftReport.roundReports.get(0).partialArrangement[0];


            double draftScore = simDraft.scoreDraft(isFun);

            if (posEncoded == 1) {
                //qb1Scores.add(draftScore);
                averageQB1Calc = AverageUtility.calculateAverage(averageQB1Calc, numQB1, draftScore);
                numQB1++;
            } else if (posEncoded == 2) {
                //rb1Scores.add(draftScore);
                averageRB1Calc = AverageUtility.calculateAverage(averageRB1Calc, numRB1, draftScore);
                numRB1++;
            } else if (posEncoded == 3) {
                //wr1Scores.add(draftScore);
                averageWR1Calc = AverageUtility.calculateAverage(averageWR1Calc, numWR1, draftScore);
                numWR1++;
            } else if (posEncoded == 4) {
                //te1Scores.add(draftScore);
                averageTE1Calc = AverageUtility.calculateAverage(averageTE1Calc, numTE1, draftScore);
                numTE1++;
            }

            topScore = draftScore > topScore ? draftScore : topScore;
            minScore = draftScore < minScore ? draftScore : minScore;
            multiDraftReport.add(simDraft.draftReport);
        }
        /*
        double qbAv = qb1Scores.stream().mapToDouble(d -> d).average().orElse(0.0);
        double rbAv = rb1Scores.stream().mapToDouble(d -> d).average().orElse(0.0);
        double wrAv = wr1Scores.stream().mapToDouble(d -> d).average().orElse(0.0);
        double teAv = te1Scores.stream().mapToDouble(d -> d).average().orElse(0.0);

        System.out.println("qb1 average:\t" + qbAv);
        System.out.println("rb1 average:\t" + rbAv);
        System.out.println("wr1 average:\t" + wrAv);
        System.out.println("te1 average:\t" + teAv);
        */

        System.out.println("Calculated qb1 average:\t" + averageQB1Calc);
        System.out.println("Calculated rb1 average:\t" + averageRB1Calc);
        System.out.println("Calculated wr1 average:\t" + averageWR1Calc);
        System.out.println("Calculated te1 average:\t" + averageTE1Calc);

        System.out.println("top: " + topScore);
        System.out.println("min: " + minScore);
        return multiDraftReport;
    }




    //if time for fun get average points per position

    public static void sendDraftReportsToDatabase(ArrayList<DraftReport> draftReports, boolean isFun) throws UnknownHostException {
        MongoClient mongoClient = new MongoClient("localhost");
        DB database;
        if (isFun) {
            database = mongoClient.getDB("FantasyFunLeague");
        } else {
            database = mongoClient.getDB("FantasySeriousLeague");
        }
        DBCollection collection = database.getCollection("roundInfo");

        //first I get them (if not there insert directly with num times = 1)
        //then do the average utility
        //then insert back with updated average and updated numb times

        for (DraftReport draftReport : draftReports) {
            for (RoundReport roundReport : draftReport.roundReports) {
                String tierArrID = roundReport.idForDB;
                //ObjectId mongoID = new ObjectId(keyID);

                double endScore = roundReport.endScore;
                int initialNumTimesAverage = 1;

                //middle of program
                //todo remove
                boolean keyFound = false;

                //DBObject oldEntry = collection.findOne(keyID);
                //String queryString = "{_id : \"" + keyID + "\"}";
                //DBObject oldEntry = collection.findOne(queryString);

                BasicDBObject query = new BasicDBObject();
                query.put("tierArrID", tierArrID);
                DBObject oldEntry = collection.findOne(query);
                int entrySize = collection.find(query).limit(1).size();
                if (oldEntry == null) {
                    int uy = 1;
                }
                if (entrySize == 0) {
                    keyFound = false;
                } else {
                    if (oldEntry == null) {
                        int y = 1;
                    }
                    keyFound = true;
                }

                if (!keyFound) {
                    DBObject roundReportEntry = new BasicDBObject("tierArrID", tierArrID)
                            .append("averageScore", endScore)
                            .append("numTimesAverage", initialNumTimesAverage);
                    collection.insert(roundReportEntry);
                } else {
                    double oldAverage = (double) oldEntry.get("averageScore");
                    int oldNumTimes = (int) oldEntry.get("numTimesAverage");
                    int newNumTimes = oldNumTimes + 1;
                    double newAverage = AverageUtility.calculateAverage(oldAverage, oldNumTimes, endScore);

                    //TODO update instead of delete and new
                    collection.remove(query);
                    DBObject roundReportEntry = new BasicDBObject("tierArrID", tierArrID)
                            .append("averageScore", newAverage)
                            .append("numTimesAverage", newNumTimes);
                    collection.insert(roundReportEntry);
                }


            }
        }


    }


    public static HashMap<String, AvScore> runDraftsSmart(int n, boolean isFun, ArrayList<Position> humanPermutationOld) {

        HashMap<String, AvScore> preDB = new HashMap<String, AvScore>();

        double topScore = Double.MIN_VALUE;
        double minScore = Double.MAX_VALUE;



        for (int i = 0; i < n; i++) {
            ArrayList<Position> humanPermutation = new ArrayList<>();
            for(Position position : humanPermutationOld){
                humanPermutation.add(position);
            }
            SimulationDraft simDraft = SimulationDraft.getSeriousSimulationPerm(humanPermutation);
            if (isFun) {
                simDraft = SimulationDraft.getFunSimulationPerm(humanPermutation);
            }
            int posEncoded = (int) simDraft.draftReport.roundReports.get(0).partialArrangement[0];

            double draftScore = simDraft.scoreDraft(isFun);


            topScore = draftScore > topScore ? draftScore : topScore;
            minScore = draftScore < minScore ? draftScore : minScore;

            ArrayList<RoundReport> rrs = simDraft.draftReport.roundReports;
            for (RoundReport rr : rrs) {
                String keyForHashMap = rr.idForDB;
                double endScore = rr.endScore;

                if (preDB.containsKey(keyForHashMap)) {
                    AvScore as = preDB.get(keyForHashMap);
                    double oldAverage = as.averageScore;
                    int oldNumTimes = as.numTimesAverageScore;
                    int newNumTimes = oldNumTimes + 1;
                    double newAverage = AverageUtility.calculateAverage(oldAverage, oldNumTimes, endScore);
                    AvScore updatedAvScore = new AvScore(newAverage, newNumTimes);
                    preDB.remove(keyForHashMap);
                    preDB.put(keyForHashMap, updatedAvScore);
                } else {
                    AvScore firstScore = new AvScore(endScore, 1);
                    preDB.put(keyForHashMap, firstScore);
                }


            }

        }

        System.out.println("top: " + topScore);
        System.out.println("min: " + minScore);
        return preDB;
    }


    public static void sendDraftReportsToDatabaseSmart(HashMap<String, AvScore> preDB, boolean isFun) throws UnknownHostException {
        MongoClient mongoClient = new MongoClient("localhost");
        DB database;
        if (isFun) {
            database = mongoClient.getDB("FantasyFunLeague");
        } else {
            database = mongoClient.getDB("FantasySeriousLeague");
        }
        DBCollection collection = database.getCollection("roundInfo");

        //first I get them (if not there insert directly with num times = 1)
        //then do the average utility
        //then insert back with updated average and updated numb times
        Set<String> allPreDbKeys = preDB.keySet();

        int numUnder15 = 0;
        int num20AndAbove = 0;
        int numKey12OrLonger = 0;
        for (String preDBKey : allPreDbKeys) {
            AvScore avScore = preDB.get(preDBKey);
            double endScore = avScore.averageScore;
            int numTimesEndScore = avScore.numTimesAverageScore;
            //TODO uncomment
            //TODO again
            if(numTimesEndScore < 1){

                if(preDBKey.length()<11) {
                    numUnder15++;
                    continue;
                }
                else{
                    numKey12OrLonger++;
                }
            }
            else{
                num20AndAbove++;
            }

            String tierArrID = preDBKey;

            boolean keyFound = false;

            BasicDBObject query = new BasicDBObject();
            query.put("tierArrID", tierArrID);
            DBObject oldEntry = collection.findOne(query);
            int entrySize = collection.find(query).limit(1).size();
            if (oldEntry == null) {
                int uy = 1;
            }
            if (entrySize == 0) {
                keyFound = false;
            } else {
                if (oldEntry == null) {
                    int y = 1;
                }
                keyFound = true;
            }

            if (!keyFound) {
                DBObject roundReportEntry = new BasicDBObject("tierArrID", tierArrID)
                        .append("averageScore", endScore)
                        .append("numTimesAverage", numTimesEndScore);
                collection.insert(roundReportEntry);
            } else {
                double oldAverage = (double) oldEntry.get("averageScore");
                int oldNumTimes = (int) oldEntry.get("numTimesAverage");
                int newNumTimes = oldNumTimes + numTimesEndScore;
                double newAverage = AverageUtility.calculateAverageWeighted(oldAverage, oldNumTimes, endScore, numTimesEndScore);

                //TODO update instead of delete and new
                collection.remove(query);
                DBObject roundReportEntry = new BasicDBObject("tierArrID", tierArrID)
                        .append("averageScore", newAverage)
                        .append("numTimesAverage", newNumTimes);
                collection.insert(roundReportEntry);

            }

        }
        System.out.println("num under 20:\t" + numUnder15
                + "\tnum 20 and above:\t"+ num20AndAbove + "\t num11OrLonger\t" + numKey12OrLonger);
    }


    public static void main(String[] args) {
        boolean isFun = false;
        //
        //
        //
        /*
        ArrayList<DraftReport> draftReports = runDrafts(30000, isFun);
        try {
            sendDraftReportsToDatabase(draftReports, isFun);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        */
        //
        //
        //



        ArrayList<Position> humanPermutationNoR1 = HumanStrategy.nonPermutedSeriousNoR3();

        if(isFun){
            try {
                humanPermutationNoR1 = HumanStrategy.nonPermutedFunNoR3();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        Iterator<List<Position>> allPermsIt = Collections2.orderedPermutations(humanPermutationNoR1).iterator();
        Collection<List<Position>> allPermsCol = Collections2.orderedPermutations(humanPermutationNoR1);

        System.out.println("All permutations size: " + allPermsCol.size());

        ArrayList<List<Position>> selectPermsMeta = new ArrayList<>();
        int start = 5;
        int end = 105;

        int index=0;
        while(allPermsIt.hasNext()){
            List<Position> permutationSingleNoR1 = allPermsIt.next();
            ArrayList<Position> permutationSingle = new ArrayList<>();
            permutationSingle.add(Position.RB);//round 1
            permutationSingle.add(Position.TE);//TODO put back to TE
            permutationSingle.add(Position.WR);
            for(Position posOfPerm : permutationSingleNoR1){
                permutationSingle.add(posOfPerm);
            }

            if(index>=start){
                selectPermsMeta.add(permutationSingle);
            }
            if(index>= end){
                break;
            }
            index++;
        }

        System.out.println("select perms size: " + selectPermsMeta.size());
        for(int i = 0; i < selectPermsMeta.size(); i++){
            System.out.println("i perm is: " + i);
            List<Position> selectPermutationList = selectPermsMeta.get(i);
            ArrayList<Position> selectPermutation = new ArrayList<>();
            for(Position position : selectPermutationList){
                selectPermutation.add(position);
            }

            HashMap<String, AvScore> keyMap = runDraftsSmart(100, isFun, selectPermutation);
            try {
                sendDraftReportsToDatabaseSmart(keyMap, isFun);
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
        }


    }


}
