import java.util.ArrayList;
import java.util.PriorityQueue;

public class RankTier {

    public static int[] qbTiers = {3,5,7,9,12,15,18,21,24,27,5000}; //likely capped at 8th (21)
    public static int[] rbTiers = {3,5,8,11,14,18,22,26,31,36,41,47,53,59,66,77,80,5000}; //likely capped at 13th (47)
    public static int[] wrTiers = {3,5,8,11,14,18,22,26,31,36,41,47,53,59,66,77,80,88,96,104,5000}; // likely capped at 15th (59)
    public static int[] teTiers = {2,4,6,9,12,15,18,22,26,30,35,40,45,5000}; //likely capped at 9th (21)
    public static int[] defTiers = {4,7,11,15,19,5000}; //likely capped at 5th (15)

    int rankNum;
    int tierNum;
    Player player;


    public RankTier(int rn, int tn, Player p){
        rankNum = rn;
        tierNum = tn;
        player = p;
    }

    public static PriorityQueue<RankTier> makeTiersPosRankQueue(PriorityQueue<Rank> posRanking, int[] positionTiers){
        PriorityQueue<RankTier> rankTiersPosQueue = new PriorityQueue<RankTier>(new RankTierComparator());

        PriorityQueue<Integer> posTiers = new PriorityQueue<Integer>();

        for(int n : positionTiers){
            posTiers.add(n);
        }

        int tier = 1;
        int nextTierDelimiter = posTiers.poll();
        int posRank = 1;

        while(!posRanking.isEmpty()){
            Rank rank = posRanking.poll();
            Player p = rank.player;
            //System.out.println(p.firstName + " " + p.lastName);
            int rankNum = rank.rankNum;
            posRank = rank.rankNum;

            if(posRank < nextTierDelimiter){
                RankTier rankTier = new RankTier(rankNum, tier, p);
                rankTiersPosQueue.add(rankTier);
            }
            else{
                nextTierDelimiter = posTiers.poll();
                tier++;
                RankTier rankTier = new RankTier(rankNum, tier, p);
                rankTiersPosQueue.add(rankTier);
            }
        }
        //TODO why is defense 31 instead of 32
        return rankTiersPosQueue;

    }


}
