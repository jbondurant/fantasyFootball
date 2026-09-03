import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The capacity claim, verified: trees can learn an interaction that a linear
 * utility cannot represent at all - which is the entire argument for the
 * boosted challenger.
 */
class BoostedSelectionModelTest {

    /** Utility = XOR-like (x0 and x1 same sign): zero linear signal. */
    private static List<SelectionModel.Observation> interactionData(int n, Random random){
        List<SelectionModel.Observation> observations = new ArrayList<>();
        for(int i = 0; i < n; i++){
            double[][] features = new double[12][SelectionModel.FEATURES];
            double[] utilities = new double[12];
            for(int a = 0; a < 12; a++){
                features[a][0] = random.nextBoolean() ? 1 : -1;
                features[a][1] = random.nextBoolean() ? 1 : -1;
                utilities[a] = features[a][0] * features[a][1] > 0 ? 2.0 : 0.0;
            }
            double max = 2.0;
            double sum = 0;
            double[] p = new double[12];
            for(int a = 0; a < 12; a++){
                p[a] = Math.exp(utilities[a] - max);
                sum += p[a];
            }
            double roll = random.nextDouble() * sum;
            int chosen = 0;
            double cumulative = 0;
            for(int a = 0; a < 12; a++){
                cumulative += p[a];
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
    void treesLearnAnInteractionTheLinearModelCannot(){
        Random random = new Random(20260825L);
        List<SelectionModel.Observation> train = interactionData(600, random);
        List<SelectionModel.Observation> test = interactionData(300, random);

        boolean[] linearActive = new boolean[SelectionModel.FEATURES];
        linearActive[0] = true;
        linearActive[1] = true;
        SelectionModel linear = SelectionModel.fit(train, linearActive);
        BoostedSelectionModel boosted = BoostedSelectionModel.fit(train, 120, 2, 0.2);

        double linearLoss = SelectionModel.meanLogLoss(linear, test);
        double boostedLoss = 0;
        for(SelectionModel.Observation observation : test){
            boostedLoss -= Math.log(Math.max(
                    boosted.choiceProbabilities(observation.features())[observation.chosen()], 1e-12));
        }
        boostedLoss /= test.size();

        Assertions.assertTrue(boostedLoss < linearLoss - 0.15,
                "trees should beat the linear model on a pure interaction: boosted "
                        + boostedLoss + " vs linear " + linearLoss);
    }

    @Test
    void probabilitiesAreADistribution(){
        Random random = new Random(7);
        BoostedSelectionModel model = BoostedSelectionModel.fit(
                interactionData(200, random), 30, 2, 0.2);
        double[][] features = new double[8][SelectionModel.FEATURES];
        for(double[] alternative : features){
            for(int f = 0; f < 2; f++){
                alternative[f] = random.nextBoolean() ? 1 : -1;
            }
        }
        double sum = 0;
        for(double probability : model.choiceProbabilities(features)){
            Assertions.assertTrue(probability >= 0);
            sum += probability;
        }
        Assertions.assertEquals(1.0, sum, 1e-9);
    }
}
