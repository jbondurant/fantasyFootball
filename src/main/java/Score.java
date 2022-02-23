import java.util.ArrayList;

public class Score {

    double score;
    Player player;


    public Score(double s, Player p){
        score = s;
        player = p;
    }

    //not sure I should do this
    public static ArrayList<Score> rankingToScoring(ArrayList<Rank> ranking){
        ArrayList<Score> scoring = new ArrayList<Score>();

        for(Rank rank : ranking){
            Player p = rank.player;
            double scoreVal = rank.rankNum * -1.0;
            Score score = new Score(scoreVal, p);
            scoring.add(score);
        }
        return scoring;
    }

}
