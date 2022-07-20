import MathStuff.AvScore;
import MathStuff.AverageUtility;
import com.google.common.collect.Collections2;
import com.mongodb.*;

import java.net.UnknownHostException;
import java.util.*;

public class DraftRunnerFunBulk {

    public static HashMap<String, AvScore> runDraftsSmartBulk(int n, boolean isFun, ArrayList<Position> humanPermutationOld) {
        HashMap<String, AvScore> preDB = new HashMap<String, AvScore>();
        double topScore = Double.MIN_VALUE;
        double minScore = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            ArrayList<Position> humanPermutation = new ArrayList<>();
            for(Position position : humanPermutationOld){
                humanPermutation.add(position);
            }
            SimulationDraft simDraft = SimulationDraft.getFunSimulationPerm(humanPermutation);
            if (!isFun) {
                simDraft = SimulationDraft.getSeriousSimulationPerm(humanPermutation);
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



    public static void sendDraftReportsToDatabaseWithDuplicates9R(HashMap<String, AvScore> preDB, boolean isFun) throws UnknownHostException {
        MongoClient mongoClient = new MongoClient("localhost");
        DB database;
        if (isFun) {
            database = mongoClient.getDB("FantasyFunLeague");
        } else {
            database = mongoClient.getDB("FantasySeriousLeague");
        }
        DBCollection collection = database.getCollection("roundInfo9R");//TODO was hardcoded

        //first I get them (if not there insert directly with num times = 1)
        //then do the average utility
        //then insert back with updated average and updated numb times
        Set<String> allPreDbKeys = preDB.keySet();

        ArrayList<DBObject> allDBObjects = new ArrayList<DBObject>();

        for (String preDBKey : allPreDbKeys) {
            AvScore avScore = preDB.get(preDBKey);
            double endScore = avScore.averageScore;
            int numTimesEndScore = avScore.numTimesAverageScore;

            String tierArrID = preDBKey;



            DBObject roundReportEntry = new BasicDBObject("tierArrID", tierArrID)
                    .append("averageScore", endScore)
                    .append("numTimesAverage", numTimesEndScore);

            allDBObjects.add(roundReportEntry);

            //collection.insert(roundReportEntry);
        }
        collection.insert(allDBObjects);
    }


    public static void main(String[] args) {
        System.out.println("Hardcoded isFun");
        boolean isFun = true;



        ArrayList<Position> humanPermutation = HumanStrategy.nonPermutedFun();


        Iterator<List<Position>> allPermsIt = Collections2.orderedPermutations(humanPermutation).iterator();
        Collection<List<Position>> allPermsCol = Collections2.orderedPermutations(humanPermutation);

        System.out.println("All permutations size: " + allPermsCol.size());

        ArrayList<List<Position>> selectPermsMeta = new ArrayList<>();
        int start = 0;
        int end = 1680;//todo unhardcode

        int index=0;
        while(allPermsIt.hasNext()){
            List<Position> permutationSingleAsList = allPermsIt.next();
            ArrayList<Position> permutationSingle = new ArrayList<>();
            permutationSingle.add(Position.WR);

            for(Position posOfPerm : permutationSingleAsList){
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

            HashMap<String, AvScore> keyMap = runDraftsSmartBulk(200, isFun, selectPermutation);
            try {
                sendDraftReportsToDatabaseWithDuplicates9R(keyMap, isFun);
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
        }


    }


}
