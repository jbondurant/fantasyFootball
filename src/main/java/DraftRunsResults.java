import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

public class DraftRunsResults {

    List<List<Position>> allDraftStartPositions;
    HashMap<List<Position>, Double> headToAverageScore;

    List<Position> positionsToDraft;
    HashMap<Position, Double> startToAverageScore;

    int numRuns;

    public DraftRunsResults(List<List<Position>> allDraftStartPositions,
                            HashMap<List<Position>, Double> headToAverageScore,
                            List<Position> positionsToDraft,
                            HashMap<Position, Double> startToAverageScore,
                            int numRuns){
        this.allDraftStartPositions = allDraftStartPositions;
        this.headToAverageScore = headToAverageScore;
        this.positionsToDraft = positionsToDraft;
        this.startToAverageScore = startToAverageScore;
        this.numRuns = numRuns;
    }

    private static DraftRunsResults makeAverageDraftRunResults(List<DraftRunsResults> allDraftRunResults){
        int numSimulationsInHeadSum = 0;
        int numSimulationsInStartSum = 0;
        HashMap<List<Position>, BigDecimal> headToTotalScoreSum = new HashMap<>();
        HashMap<Position, BigDecimal> startToTotalScoreSum = new HashMap<>();

        for(Position positionToDraft : allDraftRunResults.get(0).positionsToDraft){
            startToTotalScoreSum.put(positionToDraft, BigDecimal.ZERO);
        }
        for(List<Position> positionStartsToDraft : allDraftRunResults.get(0).allDraftStartPositions){
            headToTotalScoreSum.put(positionStartsToDraft, BigDecimal.ZERO);
        }

        for(DraftRunsResults drr : allDraftRunResults){
            BigDecimal numSimulations = BigDecimal.valueOf(drr.numRuns);
            numSimulationsInHeadSum += drr.numRuns;
            numSimulationsInStartSum += drr.numRuns;
            for(List<Position> headPositions : drr.headToAverageScore.keySet()){
                BigDecimal sumScoreForDRR = numSimulations.multiply(BigDecimal.valueOf(drr.headToAverageScore.get(headPositions)));
                BigDecimal currentSum = headToTotalScoreSum.get(headPositions);
                headToTotalScoreSum.put(headPositions, currentSum.add(sumScoreForDRR));
            }
            for(Position startPosition : drr.startToAverageScore.keySet()){
                BigDecimal sumScoreForDRR = numSimulations.multiply(BigDecimal.valueOf(drr.startToAverageScore.get(startPosition)));
                BigDecimal currentSum = startToTotalScoreSum.get(startPosition);
                startToTotalScoreSum.put(startPosition, currentSum.add(sumScoreForDRR));
            }
        }


        HashMap<List<Position>, Double> headToAverageScore = new HashMap<>();
        HashMap<Position, Double> startToAverageScore = new HashMap<>();

        for(List<Position> headPositions : headToTotalScoreSum.keySet()) {
            double averageScore = headToTotalScoreSum.get(headPositions).divide(BigDecimal.valueOf(numSimulationsInHeadSum), RoundingMode.HALF_UP).doubleValue();
            headToAverageScore.put(headPositions, averageScore);
        }
        for(Position startPosition : startToTotalScoreSum.keySet()){
            double averageScore = startToTotalScoreSum.get(startPosition).divide(BigDecimal.valueOf(numSimulationsInStartSum), RoundingMode.HALF_UP).doubleValue();
            startToAverageScore.put(startPosition, averageScore);
        }

        return new DraftRunsResults(allDraftRunResults.get(0).allDraftStartPositions,
                headToAverageScore,
                allDraftRunResults.get(0).positionsToDraft,
                startToAverageScore,
                numSimulationsInStartSum);


    }

    public static void printDraftRunResults(LiveDraftInfo ldifb, List<DraftRunsResults> allDraftRunsResults) {
        DraftRunsResults averageDraftRunResults = makeAverageDraftRunResults(allDraftRunsResults);

        double scoreQB = 0.0;
        double scoreRB = 0.0;
        double scoreWR = 0.0;
        double scoreTE = 0.0;

        for(Position position : averageDraftRunResults.positionsToDraft){
            double scoreForPosition = averageDraftRunResults.startToAverageScore.get(position);
            if(position.equals(Position.QB)){
                scoreQB = scoreForPosition;
            }
            if(position.equals(Position.RB)){
                scoreRB = scoreForPosition;
            }
            if(position.equals(Position.WR)){
                scoreWR = scoreForPosition;
            }
            if(position.equals(Position.TE)){
                scoreTE = scoreForPosition;
            }
        }


        BestAvailablePlayers bap = ldifb.bestAvailablePlayers;
        ArrayList<Score> scoreList = SleeperLeague.getScoreList();

        Player pQB1 = bap.quarterbackRT1.player;
        double sQB1 = Player.scorePlayer(scoreList, pQB1);
        Player pQB2 = bap.quarterbackRT2.player;
        double sQB2 = Player.scorePlayer(scoreList, pQB2) - sQB1;
        Player pQB3 = bap.quarterbackRT3.player;
        double sQB3 = Player.scorePlayer(scoreList, pQB3) - sQB1;
        sQB1 = 0.0;
        Player pRB1 = bap.runningBackRT1.player;
        double sRB1 = Player.scorePlayer(scoreList, pRB1);
        Player pRB2 = bap.runningBackRT2.player;
        double sRB2 = Player.scorePlayer(scoreList, pRB2) - sRB1;
        Player pRB3 = bap.runningBackRT3.player;
        double sRB3 = Player.scorePlayer(scoreList, pRB3) - sRB1;
        sRB1 = 0.0;
        Player pWR1 = bap.wideReceiverRT1.player;
        double sWR1 = Player.scorePlayer(scoreList, pWR1);
        Player pWR2 = bap.wideReceiverRT2.player;
        double sWR2 = Player.scorePlayer(scoreList, pWR2) - sWR1;
        Player pWR3 = bap.wideReceiverRT3.player;
        double sWR3 = Player.scorePlayer(scoreList, pWR3) - sWR1;
        sWR1 = 0.0;
        Player pTE1 = bap.tightEndRT1.player;
        double sTE1 = Player.scorePlayer(scoreList, pTE1);
        Player pTE2 = bap.tightEndRT2.player;
        double sTE2 = Player.scorePlayer(scoreList, pTE2) - sTE1;
        Player pTE3 = bap.tightEndRT3.player;
        double sTE3 = Player.scorePlayer(scoreList, pTE3) - sTE1;
        sTE1 = 0.0;

        Score s1 = new Score(scoreQB + sQB1, pQB1);
        Score s2 = new Score(scoreQB + sQB2, pQB2);
        Score s3 = new Score(scoreQB + sQB3, pQB3);
        Score s4 = new Score(scoreRB + sRB1, pRB1);
        Score s5 = new Score(scoreRB + sRB2, pRB2);
        Score s6 = new Score(scoreRB + sRB3, pRB3);
        Score s7 = new Score(scoreWR + sWR1, pWR1);
        Score s8 = new Score(scoreWR + sWR2, pWR2);
        Score s9 = new Score(scoreWR + sWR3, pWR3);
        Score s10 = new Score(scoreTE + sTE1, pTE1);
        Score s11 = new Score(scoreTE + sTE2, pTE2);
        Score s12 = new Score(scoreTE + sTE3, pTE3);
        ArrayList<Score> allKeeperScores = new ArrayList<>();
        allKeeperScores.add(s1);
        allKeeperScores.add(s2);
        allKeeperScores.add(s3);
        allKeeperScores.add(s4);
        allKeeperScores.add(s5);
        allKeeperScores.add(s6);
        allKeeperScores.add(s7);
        allKeeperScores.add(s8);
        allKeeperScores.add(s9);
        allKeeperScores.add(s10);
        allKeeperScores.add(s11);
        allKeeperScores.add(s12);

        PriorityQueue<Score> playersInBestScoreOrder = new PriorityQueue<Score>(5, new ScoreComparator());
        for(Score s : allKeeperScores){
            playersInBestScoreOrder.add(s);
        }
        while(playersInBestScoreOrder.size() > 0) {
            Score s = playersInBestScoreOrder.poll();
            String name = s.player.firstName + " " + s.player.lastName;
            System.out.println("Pick " + name + " to Score:\t" + s.score);
        }
    }

}
