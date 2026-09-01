import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * IS IT THE RIGHT MANAGERS TAKING TIGHT ENDS EARLY?
 *
 * The room model is off by about eleven points at tight end on both held-out
 * seasons and the reason is not known. DIAGNOSTIC.md step 6: this is a
 * POPULATION question, not a feature question. The model has a per-manager
 * TE-earliness feature (f16). If it works, the managers who really take a tight
 * end early should be the same managers whose simulated selves do. If the
 * simulation spreads early tight ends across the wrong managers, the aggregate
 * band can look close while every individual habit is wrong - and that is the
 * kind of error a band table cannot see.
 *
 * For each held-out season, per manager: the round he really took his FIRST
 * non-kept tight end, against the round his simulated self takes one, averaged
 * over trials. Then the rank correlation across managers: 1.0 means the model
 * knows exactly who reaches, 0 means it is guessing which manager.
 *
 *   ./gradlew run -Pmain=TightEndHabit -PfullRounds=true -q
 */
public class TightEndHabit {

    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", "16");
        System.setProperty("fullRounds", "true");
        AAAConfiguration configuration = AAAConfiguration.getInstance();

        for(String target : new String[]{"2024", "2025"}){
            int trainTo = Integer.parseInt(target) - 1;
            Map<String, Double> qbEarliness =
                    SelectionModel.qbEarliness(configuration, trainTo);
            DraftSimulator.Extras extras =
                    DraftSimulator.extrasFor(configuration, target, trainTo);
            List<SelectionModel.Observation> train = SelectionModel.loadObservations(
                    configuration, 2021, trainTo, qbEarliness,
                    extras.teEarliness(), extras.rbEarliness(),
                    false, SelectionModel.trainRounds());
            BoostedSelectionModel model = BoostedSelectionModel.fit(train, 300, 2, 0.1);
            DraftBacktest.Season season = new DraftBacktest.Season(configuration, target);
            DraftSimulator simulator =
                    DraftSimulator.forSeason(season, model, qbEarliness, extras);

            // REAL: each manager's first non-kept tight end.
            Map<String, Integer> realFirst = new HashMap<>();
            List<JsonObject> ordered = new ArrayList<>();
            for(JsonElement element : season.picks){
                ordered.add(element.getAsJsonObject());
            }
            ordered.sort(Comparator.comparingInt(o -> o.get("pick_no").getAsInt()));
            for(JsonObject pick : ordered){
                JsonElement keeper = pick.get("is_keeper");
                if(keeper != null && !keeper.isJsonNull() && keeper.getAsBoolean()){
                    continue;
                }
                if(!pick.has("player_id") || pick.get("player_id").isJsonNull()
                        || !pick.has("picked_by") || pick.get("picked_by").isJsonNull()
                        || !pick.has("round") || pick.get("round").isJsonNull()){
                    continue;
                }
                Player player = Player.getPlayerFromSIDV2(pick.get("player_id").getAsString());
                if(player != null && player.position == Position.TE){
                    realFirst.putIfAbsent(pick.get("picked_by").getAsString(),
                            pick.get("round").getAsInt());
                }
            }

            // SIMULATED: the same, averaged over trials.
            Map<String, List<Integer>> simFirst = new HashMap<>();
            int trials = 30;
            for(int trial = 0; trial < trials; trial++){
                Map<String, Integer> takenAt =
                        simulator.simulateOnce(new Random(77_000L + 7919L * trial));
                Map<String, Integer> firstThisTrial = new HashMap<>();
                for(Map.Entry<String, Integer> entry : takenAt.entrySet()){
                    Player player = Player.getPlayerFromSIDV2(entry.getKey());
                    DraftSimulator.Slot slot = simulator.slotAt(entry.getValue());
                    if(player != null && player.position == Position.TE && slot != null){
                        firstThisTrial.merge(slot.manager(), slot.round(), Math::min);
                    }
                }
                for(Map.Entry<String, Integer> entry : firstThisTrial.entrySet()){
                    simFirst.computeIfAbsent(entry.getKey(), u -> new ArrayList<>())
                            .add(entry.getValue());
                }
            }

            System.out.printf("%n=== %s, held out: first tight end, per manager ===%n", target);
            System.out.printf("%-14s %10s %10s%n", "MANAGER", "REAL rd", "SIM rd");
            List<double[]> pairs = new ArrayList<>();
            for(String manager : new TreeSet<>(realFirst.keySet())){
                List<Integer> sim = simFirst.get(manager);
                if(sim == null || sim.isEmpty()){
                    continue;
                }
                double simMean = sim.stream().mapToInt(Integer::intValue).average().orElse(0);
                System.out.printf("%-14s %10d %10.1f%n",
                        HumanOfInterest.getHumanFromID(manager), realFirst.get(manager), simMean);
                pairs.add(new double[]{realFirst.get(manager), simMean});
            }
            System.out.printf("%n   rank correlation, real vs simulated, across managers: %.2f%n",
                    spearman(pairs));
            System.out.printf("   (1.0 = the model knows WHO reaches; 0 = it is guessing"
                    + " which manager)%n");
        }
    }

    private static double spearman(List<double[]> pairs){
        int n = pairs.size();
        if(n < 3){
            return Double.NaN;
        }
        double[] rankA = ranks(pairs, 0);
        double[] rankB = ranks(pairs, 1);
        double sum = 0;
        for(int i = 0; i < n; i++){
            sum += (rankA[i] - rankB[i]) * (rankA[i] - rankB[i]);
        }
        return 1 - 6 * sum / ((double) n * (n * n - 1));
    }

    private static double[] ranks(List<double[]> pairs, int column){
        Integer[] order = new Integer[pairs.size()];
        for(int i = 0; i < order.length; i++){
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> pairs.get(i)[column]));
        double[] out = new double[pairs.size()];
        for(int r = 0; r < order.length; r++){
            out[order[r]] = r + 1;
        }
        return out;
    }
}
