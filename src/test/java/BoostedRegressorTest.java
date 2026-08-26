import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The regression sibling must actually learn interactions, not just means. */
public class BoostedRegressorTest {

    @Test
    void learnsAnInteractionWellBelowTheMeanPredictorsError(){
        Random random = new Random(7);
        int n = 4000;
        double[][] rows = new double[n][3];
        double[] targets = new double[n];
        double mean = 0;
        for(int i = 0; i < n; i++){
            for(int f = 0; f < 3; f++){
                rows[i][f] = random.nextDouble() * 2 - 1;
            }
            // XOR-flavored: sign(x0) agreeing with sign(x1) pays, plus a
            // linear x2 term and noise - depth 1 cannot represent this.
            targets[i] = (rows[i][0] > 0) == (rows[i][1] > 0) ? 5 : -5;
            targets[i] += 2 * rows[i][2] + random.nextGaussian() * 0.5;
            mean += targets[i];
        }
        mean /= n;

        BoostedRegressor model = BoostedRegressor.fit(rows, targets, 120, 3, 0.15);

        double modelError = 0;
        double meanError = 0;
        for(int i = 0; i < n; i++){
            double predicted = model.predict(rows[i]);
            modelError += (targets[i] - predicted) * (targets[i] - predicted);
            meanError += (targets[i] - mean) * (targets[i] - mean);
        }
        assertTrue(modelError < meanError / 10,
                "boosting should cut squared error by 10x on a learnable target, got "
                        + modelError / meanError);
    }
}
