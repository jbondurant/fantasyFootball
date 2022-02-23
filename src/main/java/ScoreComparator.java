import java.util.Comparator;

public class ScoreComparator implements Comparator<Score> {

    //Double check my -1 and 1 are not mixed up

    @Override
    public int compare(Score s1, Score s2) {
        if(s1.score < s2.score){
            return 1;
        }
        else if(s1.score > s2.score){
            return -1;
        }
        return 0;
    }
}
