import java.util.Comparator;

public class RankTierComparator implements Comparator<RankTier> {

    @Override
    public int compare(RankTier rt1, RankTier rt2) {
        if(rt1.rankNum < rt2.rankNum){
            return -1;
        }
        else if(rt1.rankNum > rt2.rankNum){
            return 1;
        }
        return 0;
    }
}
