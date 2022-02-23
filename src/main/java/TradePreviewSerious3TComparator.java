import java.util.Comparator;

public class TradePreviewSerious3TComparator implements Comparator<TradePreviewSerious3T> {
    @Override
    public int compare(TradePreviewSerious3T tps1, TradePreviewSerious3T tps2) {
        if(tps1.improvementT1 < tps2.improvementT1){
            return 1;
        }
        else if(tps1.improvementT1 > tps2.improvementT1){
            return -1;
        }
        return 0;
    }
}
