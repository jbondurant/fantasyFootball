import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** The band arithmetic: the posterior's variance, the game noise, the quantiles and the score. */
public class RosBandsTest {

    @Test
    public void theRatesVarianceShrinksWithGamesAndTheMeansAddsGameNoise(){
        // within 4 (sd 2), kappa 9: after 1 game the rate's variance is 4/10, after 9 games 4/18
        assertEquals(0.4, RosBands.rateVariance(4, 9, 1), 1e-9);
        assertEquals(4.0 / 18, RosBands.rateVariance(4, 9, 9), 1e-9);
        // the mean of the next 16 games adds 4/16 of game noise
        assertEquals(0.4 + 0.25, RosBands.meanVariance(4, 9, 1, 16), 1e-9);
        assertTrue(RosBands.meanVariance(4, 9, 1, 1) > RosBands.meanVariance(4, 9, 1, 16), "one game to come is noisier than sixteen");
    }

    @Test
    public void theScatterIsTheLevelsOrTheMansOwn(){
        assertEquals(0.64 * 100, RosBands.scatter(0.64, 10, 0.5, 20, false), 1e-9, "level scale: within x level^2");
        assertEquals(0.25 * 400, RosBands.scatter(0.64, 10, 0.5, 20, true), 1e-9, "rate scale: cv^2 x mean^2");
    }

    @Test
    public void aBandIsTheMeanPlusMultipliersOfTheSd(){
        RosBands.Band b = RosBands.band(10, 4, -1.5, 2.0);
        assertEquals(10, b.mean(), 1e-9);
        assertEquals(2, b.sd(), 1e-9);
        assertEquals(7, b.low(), 1e-9);
        assertEquals(14, b.high(), 1e-9, "an asymmetric pair of multipliers gives an asymmetric band");
    }

    @Test
    public void quantilesInterpolateAndTheIntervalScorePunishesMisses(){
        double[] sorted = {1, 2, 3, 4, 5};
        assertEquals(3, RosBands.quantile(sorted, 0.5), 1e-9);
        assertEquals(1.4, RosBands.quantile(sorted, 0.1), 1e-9);
        assertEquals(5, RosBands.quantile(sorted, 1.0), 1e-9);
        assertTrue(Double.isNaN(RosBands.quantile(new double[0], 0.5)));
        assertEquals(4.0, RosBands.intervalScore(8, 12, 10, 0.8), 1e-9, "inside: the width alone");
        assertEquals(4.0 + 10 * 2, RosBands.intervalScore(8, 12, 14, 0.8), 1e-9, "two points above at 80%: 2/0.2 per point");
        assertEquals(4.0 + 10 * 1, RosBands.intervalScore(8, 12, 7, 0.8), 1e-9);
    }

    @Test
    public void aSeasonClusteredMeanCarriesItsOwnBar(){
        java.util.Map<String, java.util.List<Double>> per = new java.util.TreeMap<>();
        per.put("a", java.util.List.of(1.0, 1.0));
        per.put("b", java.util.List.of(3.0));
        per.put("c", java.util.List.of());
        double[] m = RosBands.overSeasons(per);
        assertEquals(2.0, m[0], 1e-9, "the mean of the season means, an empty season ignored");
        assertEquals(1.0, m[1], 1e-9, "sd of {1,3} is sqrt(2), over sqrt(2) seasons");
        assertEquals(2, (int) m[2]);
    }
}
