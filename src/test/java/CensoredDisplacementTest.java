import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The censored MLE, on synthetic data where the true distribution is known.
 * Offline - no network anywhere in the fit.
 */
class CensoredDisplacementTest {

    /** Draw from a known split normal: mu 10, left 6, right 18. */
    private static double trueDraw(Random random){
        double left = 6, right = 18;
        if(random.nextDouble() < left / (left + right)){
            return 10 - Math.abs(random.nextGaussian()) * left;
        }
        return 10 + Math.abs(random.nextGaussian()) * right;
    }

    @Test
    void theFitRecoversAKnownDistributionThroughCensoring(){
        Random random = new Random(20260825L);
        List<CensoredDisplacement.Row> rows = new ArrayList<>();
        int censored = 0;
        for(int i = 0; i < 700; i++){
            double value = trueDraw(random);
            double threshold = 15 + random.nextDouble() * 20;
            if(value > threshold){
                rows.add(new CensoredDisplacement.Row(Position.QB, 60, threshold, true));
                censored++;
            }
            else {
                rows.add(new CensoredDisplacement.Row(Position.QB, 60, value, false));
            }
        }
        Assertions.assertTrue(censored > 100, "the test needs real censoring, got " + censored);

        CensoredDisplacement fitted = CensoredDisplacement.fitFromRows(rows);

        Assertions.assertEquals(10.0, fitted.mu(60, Position.QB), 2.5, "location");
        Assertions.assertEquals(6.0, fitted.sigmaLeft(60), 3.0, "reach-side scale");
        Assertions.assertEquals(18.0, fitted.sigmaRight(60), 5.0,
                "fall-side scale, recoverable only because censored rows count");
    }

    @Test
    void theSplitNormalMathIsConsistent(){
        double mu = 5, left = 4, right = 12;
        Assertions.assertEquals(left / (left + right),
                CensoredDisplacement.cdf(mu, mu, left, right), 0.001,
                "mass left of the mode is sigmaL over the sum");
        Assertions.assertTrue(CensoredDisplacement.cdf(-30, mu, left, right) < 0.01);
        Assertions.assertTrue(CensoredDisplacement.cdf(60, mu, left, right) > 0.99);
        double previous = 0;
        for(double x = -30; x <= 60; x += 1){
            double value = CensoredDisplacement.cdf(x, mu, left, right);
            Assertions.assertTrue(value >= previous, "cdf must be monotone");
            previous = value;
        }
    }

    @Test
    void scalingWidensDeviationsAroundTheLocationOnly(){
        List<CensoredDisplacement.Row> rows = new ArrayList<>();
        Random random = new Random(7);
        for(int i = 0; i < 300; i++){
            rows.add(new CensoredDisplacement.Row(Position.RB, 60, trueDraw(random), false));
        }
        CensoredDisplacement fitted = CensoredDisplacement.fitFromRows(rows);
        DisplacementModel doubled = fitted.scaled(2.0);

        double base = 0, wide = 0;
        Random sampler = new Random(11);
        double mu = fitted.mu(60, Position.RB);
        for(int i = 0; i < 4000; i++){
            base += Math.abs(fitted.sample(sampler, 60, Position.RB) - mu);
        }
        sampler = new Random(11);
        for(int i = 0; i < 4000; i++){
            wide += Math.abs(doubled.sample(sampler, 60, Position.RB) - mu);
        }
        Assertions.assertEquals(2.0, wide / base, 0.1);
    }
}
