import java.util.List;
import java.util.Map;

/**
 * The model-class question, settled the same way as every feature: does a
 * gradient-boosted scorer over ALL features (including every one the linear
 * lab rejected) beat the shipped conditional logit on the survival gate?
 * Hyperparameters chosen on 2024, one look at 2025 for the best cell only.
 *
 *     ./gradlew run -Pmain=BoostLab [-Ptrials=400]
 */
public class BoostLab {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 400);

        Map<String, Double> qbE = SelectionModel.qbEarliness(configuration, 2023);
        DraftSimulator.Extras extras24 = DraftSimulator.extrasFor(configuration, "2024", 2023);
        List<SelectionModel.Observation> train = SelectionModel.loadObservations(
                configuration, 2021, 2023, qbE, extras24.teEarliness(), extras24.rbEarliness(),
                false, SelectionModel.TRAIN_ROUNDS);
        DraftBacktest.Season season24 = new DraftBacktest.Season(configuration, "2024");
        List<SelectionModel.Observation> test24 = SelectionModel.loadObservations(
                configuration, 2024, 2024, qbE, extras24.teEarliness(), extras24.rbEarliness());
        System.out.printf("train %d selections (2021-2023, rounds 1-%d), judged on 2024, %d trials%n%n",
                train.size(), SelectionModel.TRAIN_ROUNDS, trials);

        SelectionModel linear = SelectionModel.fit(train, SelectionModel.shippedFeatures());
        double baseline = calibrationFor(linear, season24, qbE, extras24, trials);
        System.out.printf("   %-26s %10s %12s%n", "MODEL", "2024 calib", "2024 logloss");
        System.out.printf("   %-26s %9.2f%% %12.3f%n", "shipped linear logit",
                baseline * 100, SelectionModel.meanLogLoss(linear, test24));

        double bestError = baseline;
        int bestTrees = 0;
        int bestDepth = 0;
        for(int depth : new int[]{2, 3}){
            for(int treeCount : new int[]{50, 150, 300}){
                BoostedSelectionModel boosted = BoostedSelectionModel.fit(
                        train, treeCount, depth, 0.1);
                double error = calibrationFor(boosted, season24, qbE, extras24, trials);
                System.out.printf("   %-26s %9.2f%% %12.3f%n",
                        String.format("boosted %d trees depth %d", treeCount, depth),
                        error * 100, boostLogLoss(boosted, test24));
                if(error < bestError - FeatureLab.SHIP_MARGIN){
                    bestError = error;
                    bestTrees = treeCount;
                    bestDepth = depth;
                }
            }
        }

        if(bestTrees == 0){
            System.out.println("\nno boosted cell beat the shipped logit by the margin on 2024;");
            System.out.println("the linear model keeps the gate and 2025 stays untouched.");
            return;
        }

        // ---- the single 2025 look, best cell only ----
        Map<String, Double> qbE24 = SelectionModel.qbEarliness(configuration, 2024);
        DraftSimulator.Extras extras25 = DraftSimulator.extrasFor(configuration, "2025", 2024);
        List<SelectionModel.Observation> train24 = SelectionModel.loadObservations(
                configuration, 2021, 2024, qbE24, extras25.teEarliness(), extras25.rbEarliness(),
                false, SelectionModel.TRAIN_ROUNDS);
        DraftBacktest.Season season25 = new DraftBacktest.Season(configuration, "2025");
        BoostedSelectionModel boosted25 = BoostedSelectionModel.fit(train24, bestTrees, bestDepth, 0.1);
        SelectionModel linear25 = SelectionModel.fitShipped(configuration, 2024, qbE24);
        double boostedError = calibrationFor(boosted25, season25, qbE24, extras25, trials);
        double linearError = calibrationFor(linear25, season25, qbE24, extras25, trials);
        System.out.printf("%nheld-out 2025: boosted (%d trees depth %d) %.2f%%, linear %.2f%%%n",
                bestTrees, bestDepth, boostedError * 100, linearError * 100);

        // The other gates, same season: my actual slots, and QB timing.
        DraftSimulator boostedSimulator = DraftSimulator.forSeason(season25, boosted25,
                qbE24, extras25);
        DraftSimulator linearSimulator = DraftSimulator.forSeason(season25, linear25,
                qbE24, extras25);
        int[] myPicks = boostedSimulator.pickNumbersOf(configuration.getMyID());
        double boostedMine = DraftBacktest.calibrationOfMatrix(
                boostedSimulator.survivalMatrix(myPicks, trials, DraftSimulator.SEED),
                myPicks, season25, null);
        double linearMine = DraftBacktest.calibrationOfMatrix(
                linearSimulator.survivalMatrix(myPicks, trials, DraftSimulator.SEED),
                myPicks, season25, null);
        System.out.printf("   at my 2025 slots: boosted %.2f%%, linear %.2f%%%n",
                boostedMine * 100, linearMine * 100);

        Map<String, Integer> real = DraftSimulator.realFirstRound(season25.picks,
                PlayerImportAndSetup.Position.QB);
        double constant = DraftSimulator.trainingMeanFirstRound(configuration,
                PlayerImportAndSetup.Position.QB, 2024);
        double boostedTiming = 0;
        double linearTiming = 0;
        double constantTiming = 0;
        Map<String, Double> boostedFirst = boostedSimulator.meanFirstRound(
                PlayerImportAndSetup.Position.QB, trials / 2, DraftSimulator.SEED);
        Map<String, Double> linearFirst = linearSimulator.meanFirstRound(
                PlayerImportAndSetup.Position.QB, trials / 2, DraftSimulator.SEED);
        List<String> managers = boostedSimulator.managers();
        for(String manager : managers){
            double actual = Math.min(real.getOrDefault(manager, DraftSimulator.NEVER_ROUND),
                    DraftSimulator.NEVER_ROUND);
            boostedTiming += Math.abs(actual
                    - boostedFirst.getOrDefault(manager, (double) DraftSimulator.NEVER_ROUND));
            linearTiming += Math.abs(actual
                    - linearFirst.getOrDefault(manager, (double) DraftSimulator.NEVER_ROUND));
            constantTiming += Math.abs(actual - constant);
        }
        System.out.printf("   QB-timing MAE: boosted %.2f, linear %.2f, constant %.2f%n",
                boostedTiming / managers.size(), linearTiming / managers.size(),
                constantTiming / managers.size());
        System.out.println(boostedError <= linearError
                ? "-> the boosted model confirms on the main gate"
                : "-> 2025 does not confirm; the linear logit keeps the gate");
    }

    private static double calibrationFor(ChoiceModel model, DraftBacktest.Season season,
                                         Map<String, Double> qbE, DraftSimulator.Extras extras,
                                         int trials){
        DraftSimulator simulator = DraftSimulator.forSeason(season, model, qbE, extras);
        return DraftBacktest.calibrationOfMatrix(
                simulator.survivalMatrix(DraftSimulator.gameCheckpoints(), trials,
                        DraftSimulator.SEED),
                DraftSimulator.gameCheckpoints(), season, null);
    }

    private static double boostLogLoss(BoostedSelectionModel model,
                                       List<SelectionModel.Observation> observations){
        double total = 0;
        for(SelectionModel.Observation observation : observations){
            total -= Math.log(Math.max(
                    model.choiceProbabilities(observation.features())[observation.chosen()], 1e-12));
        }
        return total / observations.size();
    }

}
