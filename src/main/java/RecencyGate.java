import PlayerImportAndSetup.Position;

import java.util.Map;

/**
 * M3, finally gated. The appetite audit showed the league's QB timing was an
 * ERA (first-QB round 6.1 -> 4.1 -> 3.7 -> 5.4 -> 5.6), not a trend, so
 * pooling five seasons equally overstates today's appetite. Recency weighting
 * should fix that - IF it survives the same protocol everything else did:
 * choose the half-life on 2024 (brain fitted through 2023), then ONE
 * confirmation on held-out 2025 (brain fitted through 2024).
 *
 * Ships only if it beats pooled earliness on survival calibration.
 *
 *   ./gradlew run -Pmain=RecencyGate [-Ptrials=400]
 */
public class RecencyGate {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 400);
        int[] checkpoints = DraftSimulator.gameCheckpoints();

        System.out.println("choosing the half-life on 2024 (brain fitted through 2023):");
        DraftBacktest.Season tune = new DraftBacktest.Season(configuration, "2024");
        double bestHalfLife = 0;
        double bestError = Double.MAX_VALUE;
        for(double halfLife : new double[]{0, 1.0, 2.0, 3.0, 5.0}){
            Map<String, Double> earliness = halfLife == 0
                    ? SelectionModel.qbEarliness(configuration, 2023)
                    : SelectionModel.positionEarlinessWeighted(configuration, 2023,
                            Position.QB, halfLife);
            ChoiceModel brain = BoostedSelectionModel.fitShipped(configuration, 2023,
                    earliness);
            DraftSimulator simulator = DraftSimulator.forSeason(tune, brain, earliness,
                    DraftSimulator.extrasFor(configuration, "2024", 2023));
            double error = DraftBacktest.calibrationOfMatrix(
                    simulator.survivalMatrix(checkpoints, trials, DraftSimulator.SEED),
                    checkpoints, tune, null);
            System.out.printf("   half-life %-5s calibration %6.2f%%%s%n",
                    halfLife == 0 ? "pooled" : String.valueOf(halfLife), error * 100,
                    error < bestError ? "   <- best so far" : "");
            if(error < bestError){
                bestError = error;
                bestHalfLife = halfLife;
            }
        }

        System.out.printf("%nchosen: %s. Confirming once on held-out 2025 "
                        + "(brain fitted through 2024):%n",
                bestHalfLife == 0 ? "pooled" : "half-life " + bestHalfLife);
        DraftBacktest.Season confirm = new DraftBacktest.Season(configuration, "2025");
        double[] results = new double[2];
        String[] labels = {"pooled (incumbent)", "recency-weighted"};
        for(int arm = 0; arm < 2; arm++){
            Map<String, Double> earliness = arm == 0 || bestHalfLife == 0
                    ? SelectionModel.qbEarliness(configuration, 2024)
                    : SelectionModel.positionEarlinessWeighted(configuration, 2024,
                            Position.QB, bestHalfLife);
            ChoiceModel brain = BoostedSelectionModel.fitShipped(configuration, 2024,
                    earliness);
            DraftSimulator simulator = DraftSimulator.forSeason(confirm, brain, earliness,
                    DraftSimulator.extrasFor(configuration, "2025", 2024));
            results[arm] = DraftBacktest.calibrationOfMatrix(
                    simulator.survivalMatrix(checkpoints, trials, DraftSimulator.SEED),
                    checkpoints, confirm, null);
            System.out.printf("   %-22s %6.2f%%%n", labels[arm], results[arm] * 100);
        }
        boolean ships = bestHalfLife > 0 && results[1] < results[0] - 0.0005;
        System.out.printf("%nVERDICT: %s%n", ships
                ? "SHIP recency weighting (half-life " + bestHalfLife + ")"
                : "REJECT - pooled earliness stands");
        if(ships){
            System.out.println("   -> triggers the re-derivation cascade: plan, premium,"
                    + " snipes and robustness must be recomputed under the new brain.");
        }
    }
}
