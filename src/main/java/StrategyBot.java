import java.util.ArrayList;
import java.util.HashMap;
import java.util.PriorityQueue;

public class StrategyBot extends Strategy{



    PriorityQueue<Rank> deviatedRanking;

    public StrategyBot(PriorityQueue<Rank> devRank){
        deviatedRanking = devRank;
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
        return rank == null ? null : rank.player;
    }

    /**
     * Takes a player off this bot's board.
     *
     * Matched on sportRadarID, which is null for a good few players - every
     * defense among them - and those were skipped outright, so a drafted player
     * stayed on the board and could be drafted again by the same bot later in
     * the simulation. Matched on the sleeper id now.
     */
    @Override
    public void removeDraftedPlayer(Player p){
        if(p == null){
            return;
        }
        Rank rankToRemove = null;
        for(Rank rank : deviatedRanking){
            if(rank.player != null && samePlayer(rank.player, p)){
                rankToRemove = rank;
                break;
            }
        }
        if(rankToRemove != null){
            deviatedRanking.remove(rankToRemove);
        }
    }

    private static boolean samePlayer(Player a, Player b){
        if(a == b){
            return true;
        }
        if(a.sleeperIDString != null && !a.sleeperIDString.isEmpty()){
            return a.sleeperIDString.equals(b.sleeperIDString);
        }
        return a.sportRadarID != null && a.sportRadarID.equals(b.sportRadarID);
    }
}
