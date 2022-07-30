import java.util.ArrayList;
import java.util.HashMap;
import java.util.PriorityQueue;

public class StrategyBot extends Strategy{



    PriorityQueue<Rank> deviatedRanking;

    public StrategyBot(PriorityQueue<Rank> devRank){
        deviatedRanking = devRank;
    }

    //jacksonville jaguars have no srid
    public static StrategyBot getSleeperFunStrategy(){
        ArrayList<DecimalRank> decimalRanking = SleeperADP.playerRankFun;
        HashMap<String, Double> apsd = FFCalculatorSD.playerSRIDToSDMapFun;

        PriorityQueue<Rank> deviatedRankingQueue = DecimalRank.makeDeviatedRanking(decimalRanking, apsd, 0);
        StrategyBot sleeperFunStrategy = new StrategyBot(deviatedRankingQueue);
        return sleeperFunStrategy;

    }

    public static StrategyBot  getSleeperSeriousStrategy(int qbADPChange){
        ArrayList<DecimalRank> decimalRanking = SleeperADP.playerRankSerious;
        HashMap<String, Double> apsd = FFCalculatorSD.playerSRIDToSDMapSerious;
        PriorityQueue<Rank> deviatedRankingQueue = DecimalRank.makeDeviatedRanking(decimalRanking, apsd, qbADPChange);
        StrategyBot sleeperSeriousStrategy = new StrategyBot(deviatedRankingQueue);
        return sleeperSeriousStrategy;
    }

    @Override
    public Player selectPlayer() {
        Rank rank = deviatedRanking.poll();
        return rank.player;
    }

    //slow
    @Override
    public void removeDraftedPlayer(Player p){
        Rank rankToRemove = null;
        for(Rank rank : deviatedRanking){

            //messes up with only the jacksonville jaguars
            if(rank.player.sportRadarID == null){
               int y=1;
                continue;
            }
            if(rank.player.sportRadarID.equals(p.sportRadarID)){
                rankToRemove = rank;
                break;
            }
        }
        deviatedRanking.remove(rankToRemove);
    }
}
