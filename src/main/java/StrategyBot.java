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
        //TODO remove test
        /*for(DecimalRank decimalRank : decimalRanking){
            if(decimalRank.player.position.equals(Position.DEF)){
                int y=1;
            }
        }*/
        PriorityQueue<Rank> deviatedRankingQueue = DecimalRank.makeDeviatedRanking(decimalRanking, apsd);
        //TODO remove test again
        /*for(Rank rank : deviatedRankingQueue){
            if(rank.player.position.equals(Position.DEF)){
                int y=1;
            }
        }*/
        StrategyBot sleeperFunStrategy = new StrategyBot(deviatedRankingQueue);
        return sleeperFunStrategy;

    }

    public static StrategyBot  getSleeperSeriousStrategy(){
        ArrayList<DecimalRank> decimalRanking = SleeperADP.playerRankSerious;
        HashMap<String, Double> apsd = FFCalculatorSD.playerSRIDToSDMapSerious;
        PriorityQueue<Rank> deviatedRankingQueue = DecimalRank.makeDeviatedRanking(decimalRanking, apsd);
        StrategyBot sleeperSeriousStrategy = new StrategyBot(deviatedRankingQueue);
        return sleeperSeriousStrategy;
    }

    public static void main(String[] args){
        StrategyBot a = getSleeperFunStrategy();
        StrategyBot b = getSleeperSeriousStrategy();
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
