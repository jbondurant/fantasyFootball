import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** The conditional logit, verified by recovering known coefficients. Offline. */
class SelectionModelTest {

    /** Pads a leading-coefficient vector out to however many features exist. */
    private static double[] beta(double... leading){
        return java.util.Arrays.copyOf(leading, SelectionModel.FEATURES);
    }

    private static boolean[] active(int howMany){
        boolean[] active = new boolean[SelectionModel.FEATURES];
        for(int f = 0; f < howMany; f++){
            active[f] = true;
        }
        return active;
    }

    private static List<SelectionModel.Observation> synthetic(double[] trueBeta, int n, Random random){
        SelectionModel truth = new SelectionModel(trueBeta);
        List<SelectionModel.Observation> observations = new ArrayList<>();
        for(int i = 0; i < n; i++){
            double[][] features = new double[25][SelectionModel.FEATURES];
            for(double[] alternative : features){
                for(int f = 0; f < SelectionModel.FEATURES; f++){
                    alternative[f] = random.nextGaussian();
                }
            }
            double[] probabilities = truth.choiceProbabilities(features);
            double roll = random.nextDouble();
            int chosen = 0;
            double cumulative = 0;
            for(int a = 0; a < probabilities.length; a++){
                cumulative += probabilities[a];
                if(roll <= cumulative){
                    chosen = a;
                    break;
                }
            }
            observations.add(new SelectionModel.Observation(features, chosen));
        }
        return observations;
    }

    @Test
    void theFitRecoversKnownCoefficients(){
        double[] trueBeta = beta(1.5, 0.8, -0.5);
        List<SelectionModel.Observation> data =
                synthetic(trueBeta, 800, new Random(20260825L));

        SelectionModel fitted = SelectionModel.fit(data, active(3));

        Assertions.assertEquals(1.5, fitted.beta()[0], 0.25);
        Assertions.assertEquals(0.8, fitted.beta()[1], 0.25);
        Assertions.assertEquals(-0.5, fitted.beta()[2], 0.25);
        Assertions.assertEquals(0.0, fitted.beta()[3], 0.0001, "inactive features stay at zero");
    }

    @Test
    void probabilitiesAreADistribution(){
        SelectionModel model = new SelectionModel(beta(1, -1, 0.5));
        double[][] features = new double[10][SelectionModel.FEATURES];
        Random random = new Random(3);
        for(double[] alternative : features){
            for(int f = 0; f < SelectionModel.FEATURES; f++){
                alternative[f] = random.nextGaussian();
            }
        }
        double sum = 0;
        for(double probability : model.choiceProbabilities(features)){
            Assertions.assertTrue(probability >= 0);
            sum += probability;
        }
        Assertions.assertEquals(1.0, sum, 1e-9);
    }

    @Test
    void aBetterFitHasLowerLogLoss(){
        double[] trueBeta = beta(2.0);
        List<SelectionModel.Observation> data = synthetic(trueBeta, 400, new Random(9));

        double fittedLoss = SelectionModel.meanLogLoss(
                SelectionModel.fit(data, active(1)), data);
        double uniformLoss = SelectionModel.meanLogLoss(
                new SelectionModel(new double[SelectionModel.FEATURES]), data);

        Assertions.assertTrue(fittedLoss < uniformLoss - 0.3,
                "fitted " + fittedLoss + " vs uniform " + uniformLoss);
    }
}
