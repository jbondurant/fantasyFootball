import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every candidate feature, one gate each: fit through 2023 with the shipped
 * set plus the candidate, simulate 2024, score the same survival-calibration
 * metric the gaussian was beaten on. Candidates that beat the shipped
 * baseline by a clear margin get a joint refit and a final held-out 2025
 * verdict; everything else is reported and stays out.
 *
 * The multiple-testing debt is real and printed: 2024 has now judged many
 * variants, so the margin demanded here is deliberately larger than noise
 * (+/- ~0.08 points at 400 trials), and 2025 is only touched once, by the
 * joint winner set.
 *
 *     ./gradlew run -Pmain=FeatureLab [-Ptrials=400]
 */
public class FeatureLab {

    static final double SHIP_MARGIN = 0.0010;   // 0.10 calibration points

    record Candidate(String name, int[] indices){}

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 400);

        Map<String, Double> qbE = SelectionModel.qbEarliness(configuration, 2023);
        DraftSimulator.Extras extras24 = DraftSimulator.extrasFor(configuration, "2024", 2023);
        List<SelectionModel.Observation> train = SelectionModel.loadObservations(
                configuration, 2021, 2023, qbE,
                extras24.teEarliness(), extras24.rbEarliness());
        DraftBacktest.Season season24 = new DraftBacktest.Season(configuration, "2024");
        System.out.printf("train %d selections (2021-2023), judged on 2024, %d trials%n%n",
                train.size(), trials);
        System.out.printf("   FFC spread coverage 2024: %d players%n%n",
                extras24.adpSpreadCentered().size());

        boolean[] shipped = SelectionModel.shippedFeatures();
        double baseline = calibration(train, shipped, season24, qbE, extras24,
                configuration, trials);
        System.out.printf("   %-22s %11s %10s   %s%n", "CANDIDATE", "2024 calib", "delta", "coefficient(s)");
        System.out.printf("   %-22s %10.2f%% %10s%n", "shipped baseline", baseline * 100, "-");

        Map<String, int[]> candidates = new LinkedHashMap<>();
        candidates.put("value fall", new int[]{10});
        candidates.put("turn-pair swap", new int[]{11, 12});
        candidates.put("wait x adp", new int[]{13});
        candidates.put("flex need", new int[]{14});
        candidates.put("QB depletion", new int[]{15});
        candidates.put("TE timing", new int[]{16});
        candidates.put("RB timing", new int[]{17});
        candidates.put("QB stack", new int[]{18});
        candidates.put("rookie", new int[]{19});
        candidates.put("ADP spread", new int[]{20});
        candidates.put("loyalty (my guy)", new int[]{21});
        candidates.put("keeper stash", new int[]{22});

        List<Integer> winners = new ArrayList<>();
        for(Map.Entry<String, int[]> candidate : candidates.entrySet()){
            boolean[] active = shipped.clone();
            for(int index : candidate.getValue()){
                active[index] = true;
            }
            SelectionModel fitted = SelectionModel.fit(train, active);
            double error = calibrationFor(fitted, season24, qbE, extras24, configuration, trials);
            StringBuilder coefficients = new StringBuilder();
            for(int index : candidate.getValue()){
                coefficients.append(String.format("%+.3f ", fitted.beta()[index]));
            }
            boolean wins = error < baseline - SHIP_MARGIN;
            System.out.printf("   %-22s %10.2f%% %+9.2f%%   %s%s%n", candidate.getKey(),
                    error * 100, (error - baseline) * 100, coefficients, wins ? "  <- wins" : "");
            if(wins){
                for(int index : candidate.getValue()){
                    winners.add(index);
                }
            }
        }

        // ---- not a feature: the information-set correction. The managers'
        // draft room shows LEAGUE-scored projections (6-pt passing TDs); the
        // value features train on the raw 4-pt feed. Same shipped features,
        // value input swapped on both sides of the gate.
        List<SelectionModel.Observation> leagueTrain = SelectionModel.loadObservations(
                configuration, 2021, 2023, qbE,
                extras24.teEarliness(), extras24.rbEarliness(), true);
        DraftSimulator.Extras leagueExtras24 = new DraftSimulator.Extras(
                extras24.teEarliness(), extras24.rbEarliness(), extras24.teamOf(),
                extras24.rookies(), extras24.adpSpreadCentered(), extras24.keeperStackTeams(),
                extras24.formerPlayersByManager(), extras24.young(),
                HistoricalProjections.leaguePointsBySleeperID(configuration, "2024"));
        double leagueScored = calibration(leagueTrain, shipped, season24, qbE,
                leagueExtras24, configuration, trials);
        System.out.printf("%n   %-22s %10.2f%% %+9.2f%%   (input swap, not a feature)%n",
                "league-scored value", leagueScored * 100, (leagueScored - baseline) * 100);

        if(winners.isEmpty()){
            System.out.println("\nno candidate beat the shipped baseline by the margin;");
            System.out.println("shippedFeatures() stays as it is.");
            return;
        }

        // ---- joint winners on 2024, then the single 2025 verdict ----
        boolean[] joint = shipped.clone();
        for(int index : winners){
            joint[index] = true;
        }
        double jointError = calibration(train, joint, season24, qbE, extras24,
                configuration, trials);
        System.out.printf("%njoint winners on 2024: %.2f%% (baseline %.2f%%)%n",
                jointError * 100, baseline * 100);
        if(jointError >= baseline - SHIP_MARGIN){
            System.out.println("the joint set loses its edge together - nothing ships.");
            return;
        }

        Map<String, Double> qbE24 = SelectionModel.qbEarliness(configuration, 2024);
        DraftSimulator.Extras extras25 = DraftSimulator.extrasFor(configuration, "2025", 2024);
        List<SelectionModel.Observation> train24 = SelectionModel.loadObservations(
                configuration, 2021, 2024, qbE24,
                extras25.teEarliness(), extras25.rbEarliness());
        DraftBacktest.Season season25 = new DraftBacktest.Season(configuration, "2025");
        SelectionModel candidate25 = SelectionModel.fit(train24, joint);
        SelectionModel shipped25 = SelectionModel.fit(train24, SelectionModel.shippedFeatures());
        double jointOn25 = calibrationFor(candidate25, season25, qbE24, extras25,
                configuration, trials);
        double shippedOn25 = calibrationFor(shipped25, season25, qbE24, extras25,
                configuration, trials);
        System.out.printf("%nheld-out 2025 (fit through 2024): joint %.2f%%, shipped %.2f%%%n",
                jointOn25 * 100, shippedOn25 * 100);
        System.out.println(jointOn25 <= shippedOn25
                ? "-> the joint set confirms; update shippedFeatures() to include it"
                : "-> 2025 does not confirm; shippedFeatures() stays as it is");
    }

    private static double calibration(List<SelectionModel.Observation> train, boolean[] active,
                                      DraftBacktest.Season season, Map<String, Double> qbE,
                                      DraftSimulator.Extras extras,
                                      AAAConfiguration configuration, int trials){
        return calibrationFor(SelectionModel.fit(train, active), season, qbE, extras,
                configuration, trials);
    }

    private static double calibrationFor(SelectionModel model, DraftBacktest.Season season,
                                         Map<String, Double> qbE, DraftSimulator.Extras extras,
                                         AAAConfiguration configuration, int trials){
        DraftSimulator simulator = DraftSimulator.forSeason(season, model, qbE, extras);
        return DraftBacktest.calibrationOfMatrix(
                simulator.survivalMatrix(DraftSimulator.gameCheckpoints(), trials,
                        DraftSimulator.SEED),
                DraftSimulator.gameCheckpoints(), season, null);
    }

}
