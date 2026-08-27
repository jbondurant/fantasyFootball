import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Night 2, step 1: the fog, parameterized. Five seasons of projection
 * residuals (actual / projected, players projected 50+ points) grouped by
 * position x projection tier. Outputs the constants DecisionSensitivity
 * perturbs with: mean ratio, sd, bust rate (actual under half of projection
 * - the injury/collapse mass) and boom rate (over 1.3x).
 *
 *   ./gradlew run -Pmain=FogFit
 */
public class FogFit {

    /** tier boundaries by position rank: 1-12, 13-36, 37+ */
    static int tier(int positionRank){
        return positionRank <= 12 ? 0 : positionRank <= 36 ? 1 : 2;
    }

    /** position -> tier -> {mean ratio, sd, bust rate} - the fog constants. */
    public static Map<Position, double[][]> fit(AAAConfiguration configuration){
        Map<Position, List<Double>[]> ratios = collect(configuration);
        Map<Position, double[][]> constants = new EnumMap<>(Position.class);
        for(Map.Entry<Position, List<Double>[]> entry : ratios.entrySet()){
            double[][] tiers = new double[3][3];
            for(int t = 0; t < 3; t++){
                List<Double> values = entry.getValue()[t];
                if(values.isEmpty()){
                    tiers[t] = new double[]{0.85, 0.35, 0.2};
                    continue;
                }
                double mean = values.stream().mapToDouble(Double::doubleValue)
                        .average().orElse(0.85);
                double var = values.stream()
                        .mapToDouble(v -> (v - mean) * (v - mean)).sum()
                        / Math.max(values.size() - 1, 1);
                double bust = values.stream().filter(v -> v < 0.5).count()
                        / (double) values.size();
                tiers[t] = new double[]{mean, Math.sqrt(var), bust};
            }
            constants.put(entry.getKey(), tiers);
        }
        return constants;
    }

    static Map<Position, List<Double>[]> collect(AAAConfiguration configuration){
        Map<Position, List<Double>[]> ratios = new EnumMap<>(Position.class);
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR,
                Position.TE}){
            @SuppressWarnings("unchecked")
            List<Double>[] tiers = new List[]{new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>()};
            ratios.put(position, tiers);
        }

        for(String season : new String[]{"2021", "2022", "2023", "2024", "2025"}){
            Map<String, Double> projected;
            try {
                projected = HistoricalProjections.rawPointsBySleeperID(configuration,
                        season);
            }
            catch(Exception missing){
                continue;
            }
            Map<String, Double> actual = HistoricalActuals.pointsBySleeperID(season);
            Map<Position, Integer> rankCounter = new EnumMap<>(Position.class);
            List<String> ordered = new ArrayList<>(projected.keySet());
            ordered.sort(Comparator.comparingDouble(id -> -projected.get(id)));
            for(String sleeperID : ordered){
                Player player = Player.getPlayerFromSIDV2(sleeperID);
                if(player == null || !StartingLineup.isSkillPosition(player.position)
                        || projected.get(sleeperID) < 50){
                    continue;
                }
                int rank = rankCounter.merge(player.position, 1, Integer::sum);
                if(rank > 60){
                    continue;
                }
                double ratio = actual.getOrDefault(sleeperID, 0.0)
                        / projected.get(sleeperID);
                ratios.get(player.position)[tier(rank)].add(Math.min(ratio, 2.5));
            }
        }

        return ratios;
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Map<Position, List<Double>[]> ratios = collect(configuration);
        System.out.printf("%-4s %-6s %5s %7s %6s %6s %6s%n", "POS", "tier", "n",
                "mean", "sd", "bust", "boom");
        for(Map.Entry<Position, List<Double>[]> entry : ratios.entrySet()){
            for(int t = 0; t < 3; t++){
                List<Double> values = entry.getValue()[t];
                if(values.size() < 15){
                    continue;
                }
                double mean = values.stream().mapToDouble(Double::doubleValue)
                        .average().orElse(0);
                double var = values.stream()
                        .mapToDouble(v -> (v - mean) * (v - mean)).sum()
                        / (values.size() - 1);
                double bust = values.stream().filter(v -> v < 0.5).count()
                        / (double) values.size();
                double boom = values.stream().filter(v -> v > 1.3).count()
                        / (double) values.size();
                System.out.printf("%-4s %-6s %5d %7.2f %6.2f %5.0f%% %5.0f%%%n",
                        entry.getKey(), t == 0 ? "1-12" : t == 1 ? "13-36" : "37+",
                        values.size(), mean, Math.sqrt(var), bust * 100, boom * 100);
            }
        }
        System.out.println("\nratio = actual / projected season points (players projected"
                + "\n50+, top 60 per position per season, capped at 2.5).");
    }
}
