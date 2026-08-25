import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The gate every model layer has to pass: fit on the seasons before a held-out
 * one, replay the held-out draft, measure.
 *
 * Two measurements:
 *  - Pick prediction: rank of the actual pick on each model's board. Tells us
 *    whether a layer explains WHO gets taken.
 *  - Availability calibration: of the players the model scored 70-80% likely
 *    to survive to a pick, how many did? Tells us whether the survival numbers
 *    the keeper and wait/take decisions rest on can be believed.
 *
 * PICK_STANDARD_DEVIATION and VALUE_WEIGHT are tuned here - grid-searched on
 * 2024 with profiles fitted through 2023 - and the final report on 2025 uses
 * profiles fitted through 2024, so the reported season never leaks into
 * anything it is scored on.
 *
 *     ./gradlew run -Pmain=DraftBacktest
 */
public class DraftBacktest {

    public static final int TRIALS = 1500;
    public static final long SEED = 20260825L;
    static final double ADP_LIMIT = 250.0;

    /** One held-out season, loaded once. */
    public static class Season {
        final String label;
        final JsonArray picks;
        final Map<String, Double> adp;
        final Map<String, Double> rawPoints;
        final Set<String> keptIDs = new HashSet<>();
        final List<Integer> keeperPickNumbers = new ArrayList<>();

        Season(AAAConfiguration configuration, String label){
            this.label = label;
            List<String> seasons = configuration.getPreviousSeasons();
            this.picks = configuration.getPreviousDraftPicks().get(seasons.indexOf(label));
            this.adp = HistoricalProjections.adpBySleeperID(configuration, label);
            this.rawPoints = HistoricalProjections.rawPointsBySleeperID(configuration, label);
            for(JsonElement pickElement : picks){
                JsonObject pick = pickElement.getAsJsonObject();
                JsonElement isKeeper = pick.get("is_keeper");
                if(isKeeper != null && !isKeeper.isJsonNull() && isKeeper.getAsBoolean()){
                    keptIDs.add(pick.get("player_id").getAsString());
                    keeperPickNumbers.add(pick.get("pick_no").getAsInt());
                }
            }
        }

        AvailabilityModel model(ManagerProfiles profiles, double sd, double valueWeight){
            Map<String, Double>[] pools = pools();
            return AvailabilityModel.build(pools[0], profiles.leagueBiasMap(), pools[1], sd, valueWeight)
                    .withOccupiedPicks(keeperPickNumbers);
        }

        AvailabilityModel learnedModel(DisplacementModel displacement){
            Map<String, Double>[] pools = pools();
            return AvailabilityModel.buildLearned(pools[0], pools[1], displacement)
                    .withOccupiedPicks(keeperPickNumbers);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Double>[] pools(){
            Map<String, Double> pool = new HashMap<>();
            Map<String, Double> poolAdp = new HashMap<>();
            for(Map.Entry<String, Double> entry : rawPoints.entrySet()){
                if(!keptIDs.contains(entry.getKey())){
                    pool.put(entry.getKey(), entry.getValue());
                    Double a = adp.get(entry.getKey());
                    if(a != null){
                        poolAdp.put(entry.getKey(), a);
                    }
                }
            }
            return new Map[]{pool, poolAdp};
        }
    }

    /** Weighted mean absolute calibration error over predicted-decile buckets. */
    public static double calibrationError(AAAConfiguration configuration, Season season,
                                          ManagerProfiles profiles, double sd, double valueWeight,
                                          int trials, double[][] bucketReportOut){
        return calibrationErrorFor(season.model(profiles, sd, valueWeight), season, trials, bucketReportOut);
    }

    /** The same measurement for any availability model - Gaussian or learned. */
    public static double calibrationErrorFor(AvailabilityModel model, Season season,
                                             int trials, double[][] bucketReportOut){

        int[] checkpoints = new int[15];
        for(int c = 0; c < 15; c++){
            checkpoints[c] = 13 + 12 * c;
        }
        Map<String, double[]> predicted = model.survivalMatrix(checkpoints, trials, SEED);

        Map<String, Integer> actualPick = new HashMap<>();
        for(JsonElement pickElement : season.picks){
            JsonObject pick = pickElement.getAsJsonObject();
            JsonElement isKeeper = pick.get("is_keeper");
            if(isKeeper != null && !isKeeper.isJsonNull() && isKeeper.getAsBoolean()){
                continue;
            }
            actualPick.put(pick.get("player_id").getAsString(), pick.get("pick_no").getAsInt());
        }

        double[][] buckets = new double[10][3];   // sum predicted, sum actual, count
        for(Map.Entry<String, double[]> entry : predicted.entrySet()){
            int actual = actualPick.getOrDefault(entry.getKey(), Integer.MAX_VALUE);
            for(int c = 0; c < checkpoints.length; c++){
                double p = entry.getValue()[c];
                int bucket = Math.min((int) (p * 10), 9);
                buckets[bucket][0] += p;
                buckets[bucket][1] += actual >= checkpoints[c] ? 1 : 0;
                buckets[bucket][2] += 1;
            }
        }
        double weightedError = 0.0;
        double total = 0.0;
        for(double[] bucket : buckets){
            if(bucket[2] < 20){
                continue;   // too thin to score
            }
            weightedError += Math.abs(bucket[0] / bucket[2] - bucket[1] / bucket[2]) * bucket[2];
            total += bucket[2];
        }
        if(bucketReportOut != null){
            for(int b = 0; b < 10; b++){
                bucketReportOut[b] = buckets[b].clone();
            }
        }
        return total == 0 ? 1.0 : weightedError / total;
    }

    /** Rank of each actual pick on a model's board. mode 0=ADP, 1=+league, 2=+manager. */
    public static List<Integer> pickRanks(Season season, ManagerProfiles profiles, int mode){
        Map<String, Double> baseScore = new HashMap<>();
        Map<String, Position> positionOf = new HashMap<>();
        for(Map.Entry<String, Double> entry : season.adp.entrySet()){
            if(entry.getValue() > ADP_LIMIT || season.keptIDs.contains(entry.getKey())){
                continue;
            }
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null || !StartingLineup.isSkillPosition(player.position)){
                continue;
            }
            positionOf.put(entry.getKey(), player.position);
            baseScore.put(entry.getKey(), entry.getValue());
        }

        List<Integer> ranks = new ArrayList<>();
        Set<String> gone = new HashSet<>();
        for(JsonElement pickElement : season.picks){
            JsonObject pick = pickElement.getAsJsonObject();
            JsonElement isKeeper = pick.get("is_keeper");
            if(isKeeper != null && !isKeeper.isJsonNull() && isKeeper.getAsBoolean()){
                continue;
            }
            String pid = pick.get("player_id").getAsString();
            String picker = pick.get("picked_by").isJsonNull() ? "" : pick.get("picked_by").getAsString();
            if(baseScore.containsKey(pid)){
                List<String> board = new ArrayList<>();
                for(String candidate : baseScore.keySet()){
                    if(!gone.contains(candidate)){
                        board.add(candidate);
                    }
                }
                board.sort((a, b) -> Double.compare(
                        score(a, picker, baseScore, positionOf, profiles, mode),
                        score(b, picker, baseScore, positionOf, profiles, mode)));
                ranks.add(board.indexOf(pid));
            }
            gone.add(pid);
        }
        return ranks;
    }

    private static double score(String pid, String picker, Map<String, Double> adp,
                                Map<String, Position> positionOf, ManagerProfiles profiles, int mode){
        double base = adp.get(pid);
        if(mode == 0){
            return base;
        }
        Position position = positionOf.get(pid);
        if(mode == 1){
            return base + profiles.leagueBias(position);
        }
        return base + profiles.adjustmentFor(picker, position);
    }

    private static void printPickMetrics(String label, List<Integer> ranks){
        long top1 = ranks.stream().filter(r -> r == 0).count();
        long top5 = ranks.stream().filter(r -> r < 5).count();
        List<Integer> sorted = new ArrayList<>(ranks);
        sorted.sort(Integer::compareTo);
        System.out.printf("   %-34s top-1 %5.1f%%   top-5 %5.1f%%   median rank %d%n",
                label, 100.0 * top1 / ranks.size(), 100.0 * top5 / ranks.size(),
                sorted.get(sorted.size() / 2));
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();

        // ---- tune on 2024, profiles through 2023 ----
        Season tuneSeason = new Season(configuration, "2024");
        ManagerProfiles tuneProfiles = ManagerProfiles.fitThroughSeason(configuration, 2023);
        System.out.println("Tuning on 2024 (profiles fitted 2021-2023):\n");
        System.out.printf("   %6s %6s %12s%n", "SD", "VW", "calib error");
        double bestError = 1.0;
        double bestSd = AvailabilityModel.PICK_STANDARD_DEVIATION;
        double bestVw = AvailabilityModel.VALUE_WEIGHT;
        for(double sd : new double[]{12, 16, 20, 24, 28}){
            for(double vw : new double[]{0.0, 0.25, 0.5}){
                double error = calibrationError(configuration, tuneSeason, tuneProfiles,
                        sd, vw, TRIALS / 3, null);
                System.out.printf("   %6.0f %6.2f %11.1f%%%n", sd, vw, error * 100);
                if(error < bestError){
                    bestError = error;
                    bestSd = sd;
                    bestVw = vw;
                }
            }
        }
        System.out.printf("%n   chosen: SD=%.0f VW=%.2f (error %.1f%%)%n", bestSd, bestVw, bestError * 100);

        // ---- report on 2025, profiles through 2024, chosen constants ----
        Season testSeason = new Season(configuration, "2025");
        ManagerProfiles testProfiles = ManagerProfiles.fitThroughSeason(configuration, 2024);

        System.out.println("\nPick prediction, 2025 (fit 2021-2024):\n");
        printPickMetrics("national ADP only", pickRanks(testSeason, testProfiles, 0));
        printPickMetrics("+ league positional bias", pickRanks(testSeason, testProfiles, 1));
        printPickMetrics("+ per-manager offsets", pickRanks(testSeason, testProfiles, 2));

        System.out.println("\nAvailability calibration, 2025, tuned constants:\n");
        double[][] buckets = new double[10][3];
        double error = calibrationError(configuration, testSeason, testProfiles,
                bestSd, bestVw, TRIALS, buckets);
        System.out.printf("   %-12s %10s %10s %8s%n", "PREDICTED", "OBSERVED", "GAP", "N");
        for(int b = 0; b < 10; b++){
            if(buckets[b][2] < 20){
                continue;
            }
            double predicted = buckets[b][0] / buckets[b][2];
            double observed = buckets[b][1] / buckets[b][2];
            System.out.printf("   %3d-%3d%%     %8.1f%% %+9.1f%% %8.0f%n",
                    b * 10, b * 10 + 10, observed * 100, (observed - predicted) * 100, buckets[b][2]);
        }
        System.out.printf("%n   weighted mean abs calibration error: %.1f%%%n", error * 100);

        double defaultsError = calibrationError(configuration, testSeason, testProfiles,
                AvailabilityModel.PICK_STANDARD_DEVIATION, AvailabilityModel.VALUE_WEIGHT,
                TRIALS, null);
        System.out.printf("   (shipped defaults SD=%.0f VW=%.2f would score %.1f%%)%n",
                AvailabilityModel.PICK_STANDARD_DEVIATION, AvailabilityModel.VALUE_WEIGHT,
                defaultsError * 100);

        // ---- Gaussian vs learned displacement, same gate ----
        PickDisplacement displacement = PickDisplacement.fitThroughSeason(configuration, 2024);
        double[][] learnedBuckets = new double[10][3];
        double learnedError = calibrationErrorFor(testSeason.learnedModel(displacement),
                testSeason, TRIALS, learnedBuckets);
        System.out.println("\nLearned displacement (fit 2021-2024) on 2025:\n");
        System.out.printf("   %-12s %10s %10s %8s%n", "PREDICTED", "OBSERVED", "GAP", "N");
        for(int b = 0; b < 10; b++){
            if(learnedBuckets[b][2] < 20){
                continue;
            }
            double predicted = learnedBuckets[b][0] / learnedBuckets[b][2];
            double observed = learnedBuckets[b][1] / learnedBuckets[b][2];
            System.out.printf("   %3d-%3d%%     %8.1f%% %+9.1f%% %8.0f%n",
                    b * 10, b * 10 + 10, observed * 100, (observed - predicted) * 100, learnedBuckets[b][2]);
        }
        // ---- the censored challenger, with its dispersion scale tuned the
        // same way the gaussian's sigma was: on 2024, through the simulator ----
        CensoredDisplacement censoredForTuning = CensoredDisplacement.fitThroughSeason(configuration, 2023);
        double bestScale = 1.0;
        double bestScaleError = 1.0;
        System.out.println("\nTuning the censored model's dispersion scale on 2024:\n");
        for(double scale : new double[]{1.0, 1.25, 1.5, 1.75, 2.0, 2.5}){
            double scaleError = calibrationErrorFor(
                    tuneSeason.learnedModel(censoredForTuning.scaled(scale)),
                    tuneSeason, TRIALS / 3, null);
            System.out.printf("   scale %.2f  calib error %5.1f%%%n", scale, scaleError * 100);
            if(scaleError < bestScaleError){
                bestScaleError = scaleError;
                bestScale = scale;
            }
        }
        System.out.printf("   chosen scale %.2f%n", bestScale);

        CensoredDisplacement censored = CensoredDisplacement.fitThroughSeason(configuration, 2024);
        double[][] censoredBuckets = new double[10][3];
        double censoredError = calibrationErrorFor(
                testSeason.learnedModel(censored.scaled(bestScale)),
                testSeason, TRIALS, censoredBuckets);
        System.out.println("\nCensored MLE displacement (fit 2021-2024) on 2025:\n");
        printBuckets(censoredBuckets);

        // ---- hybrid: gaussian location, learned asymmetry ----
        // The MLE says falls outrun reaches about 1.8 to 1 at every depth; a
        // zero-mean split normal with that ratio, total scale tuned on 2024.
        double asymmetry = 1.8;
        double bestHybridScale = 18;
        double bestHybridError = 1.0;
        System.out.println("\nTuning the hybrid's scale on 2024:\n");
        for(double scale : new double[]{12, 15, 18, 21, 24}){
            double hybridTuneError = calibrationErrorFor(
                    tuneSeason.model(tuneProfiles, AvailabilityModel.PICK_STANDARD_DEVIATION,
                            AvailabilityModel.VALUE_WEIGHT)
                        .withDisplacement(splitNoise(scale, asymmetry)),
                    tuneSeason, TRIALS / 3, null);
            System.out.printf("   scale %4.0f  calib error %5.1f%%%n", scale, hybridTuneError * 100);
            if(hybridTuneError < bestHybridError){
                bestHybridError = hybridTuneError;
                bestHybridScale = scale;
            }
        }
        double[][] hybridBuckets = new double[10][3];
        double hybridError = calibrationErrorFor(
                testSeason.model(testProfiles, AvailabilityModel.PICK_STANDARD_DEVIATION,
                        AvailabilityModel.VALUE_WEIGHT)
                    .withDisplacement(splitNoise(bestHybridScale, asymmetry)),
                testSeason, TRIALS, hybridBuckets);

        System.out.println("\nHead-to-head on 2025:\n");
        System.out.printf("   %-28s %10s %14s%n", "MODEL", "WEIGHTED", "MID-BUCKETS");
        System.out.printf("   %-28s %9.2f%% %13.1f%%%n", "gaussian (shipped)",
                defaultsError * 100, midBucketGap(gaussianBuckets(configuration, testSeason, testProfiles)) * 100);
        System.out.printf("   %-28s %9.2f%% %13.1f%%%n", "empirical bootstrap",
                learnedError * 100, midBucketGap(learnedBuckets) * 100);
        System.out.printf("   %-28s %9.2f%% %13.1f%%%n",
                String.format("censored MLE x%.2f", bestScale),
                censoredError * 100, midBucketGap(censoredBuckets) * 100);
        System.out.printf("   %-28s %9.2f%% %13.1f%%%n",
                String.format("hybrid split x%.0f", bestHybridScale),
                hybridError * 100, midBucketGap(hybridBuckets) * 100);
        System.out.println("\n   gate metric is WEIGHTED error; mid-buckets (10-90% predictions,");
        System.out.println("   the hard region) shown so an easy-bucket flood cannot hide anything.");
        double bestChallenger = Math.min(hybridError, Math.min(learnedError, censoredError));
        System.out.println(bestChallenger < defaultsError
                ? "   -> a learned model wins the gate"
                : "   -> the gaussian keeps the gate");
    }

    /** Zero-mean split normal: falls outrun reaches by the given ratio. */
    static DisplacementModel splitNoise(double scale, double asymmetry){
        double left = scale;
        double right = scale * asymmetry;
        double meanShift = Math.sqrt(2.0 / Math.PI) * (right - left) / 2.0;
        return (random, depth, position) -> {
            double raw = random.nextDouble() < left / (left + right)
                    ? -Math.abs(random.nextGaussian()) * left
                    : Math.abs(random.nextGaussian()) * right;
            return raw - meanShift;
        };
    }

    private static double[][] gaussianBuckets(AAAConfiguration configuration, Season season,
                                              ManagerProfiles profiles){
        double[][] buckets = new double[10][3];
        calibrationError(configuration, season, profiles,
                AvailabilityModel.PICK_STANDARD_DEVIATION, AvailabilityModel.VALUE_WEIGHT,
                TRIALS, buckets);
        return buckets;
    }

    /** Mean absolute gap over the 10-90% predicted buckets with enough data. */
    static double midBucketGap(double[][] buckets){
        double total = 0.0;
        int counted = 0;
        for(int b = 1; b <= 8; b++){
            if(buckets[b][2] < 20){
                continue;
            }
            total += Math.abs(buckets[b][0] / buckets[b][2] - buckets[b][1] / buckets[b][2]);
            counted++;
        }
        return counted == 0 ? 0.0 : total / counted;
    }

    private static void printBuckets(double[][] buckets){
        System.out.printf("   %-12s %10s %10s %8s%n", "PREDICTED", "OBSERVED", "GAP", "N");
        for(int b = 0; b < 10; b++){
            if(buckets[b][2] < 20){
                continue;
            }
            double predicted = buckets[b][0] / buckets[b][2];
            double observed = buckets[b][1] / buckets[b][2];
            System.out.printf("   %3d-%3d%%     %8.1f%% %+9.1f%% %8.0f%n",
                    b * 10, b * 10 + 10, observed * 100, (observed - predicted) * 100, buckets[b][2]);
        }
    }

}
