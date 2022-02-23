import java.util.Comparator;

public class RankComparator implements Comparator <Rank> {

    //Double check my -1 and 1 are not mixed up



    @Override
    public int compare(Rank r1, Rank r2) {
        if(r1.rankNum < r2.rankNum){
            return -1;
        }
        else if(r1.rankNum > r2.rankNum){
            return 1;
        }
        return 0;
    }
}
