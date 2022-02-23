import java.util.Comparator;

public class TradePreviewSeriousComparator implements Comparator<TradePreviewSerious> {
    @Override
    public int compare(TradePreviewSerious tps1, TradePreviewSerious tps2) {
        if(tps1.improvementT1 < tps2.improvementT1){
            return 1;
        }
        else if(tps1.improvementT1 > tps2.improvementT1){
            return -1;
        }
        return 0;
    }
}
