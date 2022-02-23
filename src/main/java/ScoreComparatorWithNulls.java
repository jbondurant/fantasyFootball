import java.util.Comparator;

public class ScoreComparatorWithNulls implements Comparator<Score> {

    @Override
    public int compare(Score s1, Score s2) {
        if(s1 == null){
            if(s2 == null){
                return 0;
            }
            return 1;
        }
        if(s2 == null){
            return -1;
        }
        if(s1.score < s2.score){
            return 1;
        }
        else if(s1.score > s2.score){
            return -1;
        }
        return 0;
    }
}
