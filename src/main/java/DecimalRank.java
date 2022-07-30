import java.util.ArrayList;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Random;

public class DecimalRank {

    public double rankNum;
    public Player player;

    public DecimalRank(double rn, Player p){
        rankNum = rn;
        player = p;
    }

    public static PriorityQueue<Rank> makeDeviatedRanking(ArrayList<DecimalRank> decimalRanking, HashMap<String, Double> apsd, int qbADPChange){
        ArrayList<DecimalRank> deviatedRanking = new ArrayList<DecimalRank>();
        for(DecimalRank decimalRank : decimalRanking){
            Player p = decimalRank.player;
            double rank = decimalRank.rankNum;
            if(p.position.equals(Position.QB)){
                rank += qbADPChange;
            }
            String pSRID = p.sportRadarID;
            double sd = rank * 0.05;
            if(apsd.containsKey(pSRID)){
                sd = apsd.get(pSRID);
            }
            Random r = new Random();
            double deviatedRank = r.nextGaussian() * sd + rank;
            deviatedRank = Math.max(deviatedRank, 0.0);
            DecimalRank deviatedDecimalRank = new DecimalRank(deviatedRank, p);
            deviatedRanking.add(deviatedDecimalRank);
        }

        PriorityQueue<Score> deviatedRankingAsScoring = new PriorityQueue<Score>(5, new ScoreComparator());
        for(DecimalRank dRank : deviatedRanking){
            Player devPlayer = dRank.player;
            double devRankNum = dRank.rankNum;
            devRankNum *= -1;
            Score deviatedRankAsScore = new Score(devRankNum, devPlayer);
            deviatedRankingAsScoring.add(deviatedRankAsScore);
        }

        ArrayList<Rank> deviatedRankingAsArray = Rank.scoringToRanking(deviatedRankingAsScoring);
        PriorityQueue<Rank> deviatedRankingAsQueue = new PriorityQueue<Rank>(5, new RankComparator());
        deviatedRankingAsQueue.addAll(deviatedRankingAsArray);
        return deviatedRankingAsQueue;
    }


}
