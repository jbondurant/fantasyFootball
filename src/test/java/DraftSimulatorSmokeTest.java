import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Random;

/**
 * Gates 2 and 3 on the real held-out season, kept green: simulated drafts from
 * the fitted selection model must stay at least as calibrated as the gaussian
 * incumbent, and must keep predicting per-manager QB timing better than a
 * league-mean constant - the thing the gaussian cannot predict at all.
 */
@Tag("smoke")
class DraftSimulatorSmokeTest {

    private static final int TRIALS = 200;

    private static DraftSimulator fittedSimulator(AAAConfiguration configuration,
                                                  DraftBacktest.Season season){
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, 2024);
        boolean[] full = new boolean[SelectionModel.FEATURES];
        Arrays.fill(full, true);
        SelectionModel model = SelectionModel.fit(
                SelectionModel.loadObservations(configuration, 2021, 2024, earliness), full);
        return DraftSimulator.forSeason(season, model, earliness);
    }

    @Test
    void aSimulatedDraftFillsEveryLiveSlotFromTheBoard(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        DraftBacktest.Season season = new DraftBacktest.Season(configuration, "2025");
        DraftSimulator simulator = fittedSimulator(configuration, season);

        Map<String, Integer> takenAt = simulator.simulateOnce(new Random(1));

        Assertions.assertEquals(12, simulator.managers().size());
        long liveSlots = 12L * SelectionModel.GAME_ROUNDS - season.keeperPickNumbers.stream()
                .filter(pick -> pick <= 12 * SelectionModel.GAME_ROUNDS).count();
        Assertions.assertEquals(liveSlots, takenAt.size(),
                "every non-keeper slot in the nine-round game takes exactly one player");
        for(String sleeperID : takenAt.keySet()){
            Assertions.assertTrue(simulator.players().contains(sleeperID),
                    sleeperID + " was drafted but never on the board");
        }
    }

    @Test
    void simulatedSurvivalStaysAtLeastAsCalibratedAsTheGaussian(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        DraftBacktest.Season season = new DraftBacktest.Season(configuration, "2025");
        DraftSimulator simulator = fittedSimulator(configuration, season);
        ManagerProfiles profiles = ManagerProfiles.fitThroughSeason(configuration, 2024);
        AvailabilityModel incumbent = season.model(profiles,
                AvailabilityModel.PICK_STANDARD_DEVIATION, AvailabilityModel.VALUE_WEIGHT);

        int[] checkpoints = DraftSimulator.gameCheckpoints();
        Map<String, double[]> simulated =
                simulator.survivalMatrix(checkpoints, TRIALS, DraftSimulator.SEED);
        Map<String, double[]> gaussian =
                incumbent.survivalMatrix(checkpoints, TRIALS, DraftSimulator.SEED);
        gaussian.keySet().retainAll(simulated.keySet());
        simulated.keySet().retainAll(gaussian.keySet());

        double simulatedError = DraftBacktest.calibrationOfMatrix(
                simulated, checkpoints, season, null);
        double gaussianError = DraftBacktest.calibrationOfMatrix(
                gaussian, checkpoints, season, null);

        System.out.printf("weighted calibration: simulated %.2f%%, gaussian %.2f%%%n",
                simulatedError * 100, gaussianError * 100);
        Assertions.assertTrue(simulatedError <= gaussianError + 0.005,
                "gate 2 regressed: simulated " + simulatedError + " vs gaussian " + gaussianError
                        + " (0.5 points of draw noise allowed)");
    }

    @Test
    void simulatedManagersKeepTheirRealQuarterbackTiming(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        DraftBacktest.Season season = new DraftBacktest.Season(configuration, "2025");
        DraftSimulator simulator = fittedSimulator(configuration, season);

        Map<String, Integer> real = DraftSimulator.realFirstRound(season.picks, Position.QB);
        Map<String, Double> simulated =
                simulator.meanFirstRound(Position.QB, TRIALS, DraftSimulator.SEED);
        double leagueMean = DraftSimulator.trainingMeanFirstRound(configuration, Position.QB, 2024);

        double modelError = 0;
        double baselineError = 0;
        for(String manager : simulator.managers()){
            double actual = Math.min(real.getOrDefault(manager, DraftSimulator.NEVER_ROUND),
                    DraftSimulator.NEVER_ROUND);
            modelError += Math.abs(actual
                    - simulated.getOrDefault(manager, (double) DraftSimulator.NEVER_ROUND));
            baselineError += Math.abs(actual - leagueMean);
        }
        System.out.printf("QB timing MAE: simulated %.2f, league-mean %.2f%n",
                modelError / 12, baselineError / 12);
        Assertions.assertTrue(modelError < baselineError,
                "gate 3 regressed: the simulator no longer beats a constant at QB timing");
    }
}
