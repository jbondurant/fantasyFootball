import java.math.BigDecimal;
import java.math.RoundingMode;

public class AverageUtility {

    public static double calculateAverage(double currAverage, int num, double newValue){
        BigDecimal newestValue = BigDecimal.valueOf(newValue);
        BigDecimal currentAverage = BigDecimal.valueOf(currAverage);
        double numberAsDouble = num * 1.0;
        BigDecimal number = BigDecimal.valueOf(numberAsDouble);
        BigDecimal newTotalSum = currentAverage.multiply(number).add(newestValue);
        number = number.add(BigDecimal.ONE);
        BigDecimal average = newTotalSum.divide(number, RoundingMode.HALF_UP);
        double averageAsDouble = average.doubleValue();
        return averageAsDouble;
    }


    public static double calculateAverageWeighted(double currAverage, int num, double newValue, int newNumTimes){
        BigDecimal newestValue = BigDecimal.valueOf(newValue);
        BigDecimal currentAverage = BigDecimal.valueOf(currAverage);
        double numberAsDouble = num * 1.0;
        double newNumTimesAsDouble = newNumTimes * 1.0;
        BigDecimal newestNumTimes = BigDecimal.valueOf(newNumTimesAsDouble);
        BigDecimal number = BigDecimal.valueOf(numberAsDouble);

        BigDecimal oldWeighted = currentAverage.multiply(number);
        BigDecimal newWeighted = newestValue.multiply(newestNumTimes);
        BigDecimal newTotalSum = oldWeighted.add(newWeighted);
        BigDecimal totalWeight = number.add(newestNumTimes);
        BigDecimal average = newTotalSum.divide(totalWeight, RoundingMode.HALF_UP);
        double averageAsDouble = average.doubleValue();
        return averageAsDouble;
    }
}
