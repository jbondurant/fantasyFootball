import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;

public class ToDelPair {
    int numTimesAv;
    double average;

    public ToDelPair(int nta, double a){
        numTimesAv = nta;
        average = a;
    }

    public static ToDelPair getCompoundAvPair(ArrayList<ToDelPair> allPairs){
        if(allPairs.size() == 0){
            return new ToDelPair(0, 0.0);
        }
        int weightAsInt = 0;
        BigDecimal totalWeightedScore = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        for(ToDelPair toDelPair : allPairs){
            double weightDouble = toDelPair.numTimesAv * 1.0;
            BigDecimal weight = BigDecimal.valueOf(weightDouble);
            BigDecimal score = BigDecimal.valueOf(toDelPair.average);
            totalWeightedScore = totalWeightedScore.add(weight.multiply(score));
            totalWeight = totalWeight.add(weight);
            weightAsInt += toDelPair.numTimesAv;
        }
        BigDecimal averageBigDecimal = totalWeightedScore.divide(totalWeight, RoundingMode.HALF_UP);
        double average = averageBigDecimal.doubleValue();
        return new ToDelPair(weightAsInt, average);
    }
}
