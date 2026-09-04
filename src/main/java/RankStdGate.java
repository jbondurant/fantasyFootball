import PlayerImportAndSetup.Position;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 1's gate: does expert disagreement earn its place?
 *
 * FantasyPros publishes `rank_std` - how much its contributors disagree about a
 * player - and it is available at draft time, which makes it the only
 * per-player uncertainty signal in the repo that is not invented. The scalar
 * dials in StarterContribution faked exactly this. So it is worth having, IF it
 * predicts anything.
 *
 * The test is whether it predicts how far a player lands from what his draft
 * slot promised, out of sample, better than simply knowing his tier. Fit on
 * 2022-2023, judged once on 2024-2025. It has to beat the tier baseline or it
 * does not go in the model - a feature that only looks good in-sample is worse
 * than none, because it gets trusted.
 *
 *   ./gradlew run -Pmain=RankStdGate
 */
public class RankStdGate {

    record Seen(String season, Position position, int rank, double rankStd, double actual){}

    static final String[] TRAIN = {"2022", "2023"};
    static final String[] TEST = {"2024", "2025"};
    static final int TIER = 12;

    public static void main(String[] args) throws Exception {
        List<Seen> all = new ArrayList<>();
        for(String season : new String[]{"2022", "2023", "2024", "2025"}){
            all.addAll(load(season));
        }
        if(all.isEmpty()){
            System.out.println("no ECR seasons joined");
            return;
        }
        System.out.printf("%d player-seasons joined from dated ECR%n", all.size());

        // expectation curve fitted on TRAINING seasons only
        Map<Position, double[]> curve = expectation(filter(all, TRAIN));

        System.out.println("\n\nDIAGNOSTIC: does rank_std move with the miss at all?");
        System.out.printf("%-8s %8s %14s   %s%n", "SEASON", "n", "correlation",
                "with |actual - expected|");
        for(String season : new String[]{"2022", "2023", "2024", "2025"}){
            List<Seen> group = filter(all, new String[]{season});
            List<double[]> pairs = new ArrayList<>();
            for(Seen s : group){
                Double expected = expected(curve, s);
                if(expected != null){
                    pairs.add(new double[]{s.rankStd(), Math.abs(s.actual() - expected)});
                }
            }
            if(pairs.size() < 30){
                continue;
            }
            System.out.printf("%-8s %8d %14.3f%n", season, pairs.size(),
                    correlation(pairs));
        }

        System.out.println("\n\nTHE GATE: out of sample, does it beat knowing the tier?");
        System.out.println("fit on " + String.join(", ", TRAIN) + "; judged once on "
                + String.join(", ", TEST));

        // baseline: each tier's mean miss, learned on training
        Map<String, Double> tierMiss = new HashMap<>();
        Map<String, Integer> tierCount = new HashMap<>();
        List<double[]> trainPairs = new ArrayList<>();
        for(Seen s : filter(all, TRAIN)){
            Double expected = expected(curve, s);
            if(expected == null){
                continue;
            }
            double miss = Math.abs(s.actual() - expected);
            String key = s.position() + ":" + (s.rank() / TIER);
            tierMiss.merge(key, miss, Double::sum);
            tierCount.merge(key, 1, Integer::sum);
            trainPairs.add(new double[]{s.rankStd(), miss});
        }
        for(String key : tierMiss.keySet()){
            tierMiss.put(key, tierMiss.get(key) / tierCount.get(key));
        }
        // rank_std ALONE - kept only to show it is not the interesting question
        double[] fit = leastSquares(trainPairs);

        // The question that matters: does rank_std add anything ON TOP of the
        // tier? Comparing it against the tier on its own is a straw man - it
        // would lose on rank information it was never carrying. So regress the
        // training RESIDUALS (miss minus tier average) on how far a player's
        // disagreement sits from his own tier's, and see if that helps.
        Map<String, Double> tierStd = new HashMap<>();
        Map<String, Integer> tierStdCount = new HashMap<>();
        for(Seen s : filter(all, TRAIN)){
            String key = s.position() + ":" + (s.rank() / TIER);
            tierStd.merge(key, s.rankStd(), Double::sum);
            tierStdCount.merge(key, 1, Integer::sum);
        }
        for(String key : tierStd.keySet()){
            tierStd.put(key, tierStd.get(key) / tierStdCount.get(key));
        }
        List<double[]> residualPairs = new ArrayList<>();
        for(Seen s : filter(all, TRAIN)){
            Double expected = expected(curve, s);
            String key = s.position() + ":" + (s.rank() / TIER);
            Double base = tierMiss.get(key);
            Double std = tierStd.get(key);
            if(expected == null || base == null || std == null){
                continue;
            }
            residualPairs.add(new double[]{s.rankStd() - std,
                    Math.abs(s.actual() - expected) - base});
        }
        double[] incremental = leastSquares(residualPairs);
        System.out.printf("%nfitted on training:%n   alone        miss = %.1f + %.2f"
                + " x rank_std%n   on top of tier  residual = %.1f + %.2f x (rank_std"
                + " - tier mean)%n", fit[0], fit[1], incremental[0], incremental[1]);

        double baselineError = 0;
        double modelError = 0;
        double combinedError = 0;
        int tested = 0;
        for(Seen s : filter(all, TEST)){
            Double expected = expected(curve, s);
            String key = s.position() + ":" + (s.rank() / TIER);
            Double base = tierMiss.get(key);
            if(expected == null || base == null){
                continue;
            }
            double miss = Math.abs(s.actual() - expected);
            baselineError += Math.abs(base - miss);
            modelError += Math.abs(fit[0] + fit[1] * s.rankStd() - miss);
            Double std = tierStd.get(key);
            double adjusted = base + (std == null ? 0
                    : incremental[0] + incremental[1] * (s.rankStd() - std));
            combinedError += Math.abs(adjusted - miss);
            tested++;
        }
        if(tested == 0){
            System.out.println("nothing testable");
            return;
        }
        baselineError /= tested;
        modelError /= tested;
        combinedError /= tested;
        System.out.printf("%n%-28s %12s%n", "PREDICTOR OF THE MISS", "mean error");
        System.out.printf("%-28s %12.1f%n", "tier average (baseline)", baselineError);
        System.out.printf("%-28s %12.1f%n", "rank_std alone", modelError);
        System.out.printf("%-28s %12.1f%n", "tier PLUS rank_std", combinedError);
        System.out.printf("%nheld-out players: %d%n", tested);
        double gain = baselineError - combinedError;
        System.out.printf("%nadded to the tier, rank_std %s it by %.2f points of"
                + " predicted miss.%n", gain > 0 ? "improves" : "WORSENS",
                Math.abs(gain));
        System.out.println(gain > 1.0
                ? "It earns its place: keep it as the per-player spread modifier."
                : "DROP IT. Knowing the tier is as good, and a feature that adds"
                  + " nothing out\nof sample is worse than none, because it would be"
                  + " trusted anyway.");
    }

    static List<Seen> filter(List<Seen> all, String[] seasons){
        List<String> want = List.of(seasons);
        return all.stream().filter(s -> want.contains(s.season())).toList();
    }

    static Double expected(Map<Position, double[]> curve, Seen s){
        double[] byRank = curve.get(s.position());
        return byRank == null || s.rank() >= byRank.length || byRank[s.rank()] <= 0
                ? null : byRank[s.rank()];
    }

    /** Mean actual points by positional rank, smoothed over a band of five. */
    static Map<Position, double[]> expectation(List<Seen> seasons){
        int depth = 60;
        Map<Position, double[]> totals = new EnumMap<>(Position.class);
        Map<Position, int[]> counts = new EnumMap<>(Position.class);
        for(Seen s : seasons){
            if(s.rank() >= depth){
                continue;
            }
            totals.computeIfAbsent(s.position(), u -> new double[depth])[s.rank()] += s.actual();
            counts.computeIfAbsent(s.position(), u -> new int[depth])[s.rank()]++;
        }
        Map<Position, double[]> curve = new EnumMap<>(Position.class);
        for(Position position : totals.keySet()){
            double[] sum = totals.get(position);
            int[] n = counts.get(position);
            double[] out = new double[depth];
            for(int rank = 0; rank < depth; rank++){
                double s = 0;
                int c = 0;
                for(int near = Math.max(0, rank - 2);
                        near <= Math.min(depth - 1, rank + 2); near++){
                    s += sum[near];
                    c += n[near];
                }
                out[rank] = c == 0 ? 0 : s / c;
            }
            curve.put(position, out);
        }
        return curve;
    }

    static double correlation(List<double[]> pairs){
        double mx = pairs.stream().mapToDouble(p -> p[0]).average().orElse(0);
        double my = pairs.stream().mapToDouble(p -> p[1]).average().orElse(0);
        double sxy = 0;
        double sx = 0;
        double sy = 0;
        for(double[] p : pairs){
            sxy += (p[0] - mx) * (p[1] - my);
            sx += (p[0] - mx) * (p[0] - mx);
            sy += (p[1] - my) * (p[1] - my);
        }
        return sx == 0 || sy == 0 ? 0 : sxy / Math.sqrt(sx * sy);
    }

    static double[] leastSquares(List<double[]> pairs){
        double mx = pairs.stream().mapToDouble(p -> p[0]).average().orElse(0);
        double my = pairs.stream().mapToDouble(p -> p[1]).average().orElse(0);
        double sxy = 0;
        double sxx = 0;
        for(double[] p : pairs){
            sxy += (p[0] - mx) * (p[1] - my);
            sxx += (p[0] - mx) * (p[0] - mx);
        }
        double slope = sxx == 0 ? 0 : sxy / sxx;
        return new double[]{my - slope * mx, slope};
    }

    /** Dated ECR joined to actual season points, by normalised name. */
    static List<Seen> load(String season) throws Exception {
        File chosen = null;
        for(File file : new File("data").listFiles()){
            if(file.getName().matches("fp-ecr-dated-" + season + "-\\d{8}\\.json")){
                // the LATEST dated file for that season - nearest the draft
                if(chosen == null || file.getName().compareTo(chosen.getName()) > 0){
                    chosen = file;
                }
            }
        }
        if(chosen == null){
            return List.of();
        }
        Map<String, Double> actual = HistoricalActuals.pointsBySleeperID(season);
        Map<String, String> idByName = new HashMap<>();
        for(String id : actual.keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null){
                idByName.putIfAbsent(TightEndTiming.normalise(
                        player.firstName + " " + player.lastName), id);
            }
        }
        JsonObject root = JsonParser.parseString(
                Files.readString(chosen.toPath())).getAsJsonObject();
        List<JsonObject> rows = new ArrayList<>();
        for(JsonElement element : root.getAsJsonArray("players")){
            rows.add(element.getAsJsonObject());
        }
        rows.sort(Comparator.comparingDouble(r -> r.get("rank_ecr").getAsDouble()));

        Map<Position, Integer> nextRank = new EnumMap<>(Position.class);
        List<Seen> out = new ArrayList<>();
        for(JsonObject row : rows){
            JsonElement positions = row.get("player_positions");
            JsonElement std = row.get("rank_std");
            JsonElement name = row.get("player_name");
            if(positions == null || std == null || name == null){
                continue;
            }
            Position position;
            try {
                position = Position.valueOf(positions.getAsString().trim());
            }
            catch(IllegalArgumentException notSkill){
                continue;
            }
            int rank = nextRank.merge(position, 1, Integer::sum) - 1;   // source rank (TRAPS #80)
            String id = idByName.get(TightEndTiming.normalise(name.getAsString()));
            if(id == null){
                continue;
            }
            try {
                out.add(new Seen(season, position, rank,
                        Double.parseDouble(std.getAsString()),
                        actual.getOrDefault(id, 0.0)));
            }
            catch(NumberFormatException unparseable){ /* skip */ }
        }
        return out;
    }
}
