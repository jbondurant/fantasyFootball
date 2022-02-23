import java.util.ArrayList;
import java.util.PriorityQueue;

public class Rank {

    int rankNum;
    Player player;


    public Rank(int rn, Player p){
        rankNum = rn;
        player = p;
    }


    public static ArrayList<Rank> scoringToRanking(PriorityQueue<Score> scoringQueue){
        ArrayList<Rank> ranking = new ArrayList<Rank>();

        int rankIndex = 1;
        while(!scoringQueue.isEmpty()){
            Player p = scoringQueue.poll().player;
            Rank rank = new Rank(rankIndex, p);
            ranking.add(rank);
            rankIndex++;
        }
        return ranking;

    }
}
