import PlayerImportAndSetup.Position;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Justin's intel: JFMarino (slot 8, adjacent to Justin's slot 7) is roughly
 * 50/50 to autodraft the first five rounds. Serpentine order makes that
 * matter more than it sounds - in even rounds he picks IMMEDIATELY BEFORE
 * Justin, so in rounds 2 and 4 he is the last thing standing between Justin
 * and the player Justin wants.
 *
 * An autodrafter is deterministic: he takes the best remaining ADP, always.
 * That is strictly more predictable than a human, so the question is whether
 * the extra predictability changes anything Justin should do.
 *
 * Measured: survival of the players Justin cares about at each of his picks,
 * human JFMarino versus autodrafting JFMarino, same seeds.
 *
 *   ./gradlew run -Pmain=AutodraftImpact [-Ptrials=600]
 */
public class AutodraftImpact {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 600);
        String marino = "604377190016016384";   // slot 8

        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        DraftSimulator human = planner.simulator();
        DraftSimulator drone = human.withManagerModels(
                Map.of(marino, OpponentVariants.autodraft()));
        int[] myPicks = human.pickNumbersOf(planner.me());
        Map<String, Double> points = planner.points();

        Map<String, double[]> survival = new HashMap<>();
        for(int arm = 0; arm < 2; arm++){
            DraftSimulator simulator = arm == 0 ? human : drone;
            Random random = new Random(DraftSimulator.SEED + 31337);
            Map<String, int[]> counts = new HashMap<>();
            for(int t = 0; t < trials; t++){
                Map<String, Integer> takenAt = simulator.simulateOnce(random);
                for(String id : simulator.players()){
                    int at = takenAt.getOrDefault(id, 9999);
                    int[] row = counts.computeIfAbsent(id, u -> new int[myPicks.length]);
                    for(int i = 0; i < myPicks.length; i++){
                        if(at >= myPicks[i]){
                            row[i]++;
                        }
                    }
                }
            }
            for(Map.Entry<String, int[]> entry : counts.entrySet()){
                double[] row = survival.computeIfAbsent(entry.getKey(),
                        u -> new double[myPicks.length * 2]);
                for(int i = 0; i < myPicks.length; i++){
                    row[arm * myPicks.length + i] = entry.getValue()[i] / (double) trials;
                }
            }
        }

        // the players whose availability moves most, weighted by their value
        List<Map.Entry<String, double[]>> rows = new java.util.ArrayList<>(
                survival.entrySet());
        rows.sort((a, b) -> {
            double da = impact(a.getValue(), myPicks.length, points.getOrDefault(a.getKey(), 0.0));
            double db = impact(b.getValue(), myPicks.length, points.getOrDefault(b.getKey(), 0.0));
            return Double.compare(db, da);
        });

        System.out.printf("JFMarino autodrafting vs human, %d trials each.%n"
                + "Survival to my picks %s:%n%n", trials,
                java.util.Arrays.toString(myPicks));
        System.out.printf("   %-24s %-4s %7s %8s %8s %9s%n", "PLAYER", "POS", "proj",
                "human", "drone", "change");
        int shown = 0;
        for(Map.Entry<String, double[]> entry : rows){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            double projected = points.getOrDefault(entry.getKey(), 0.0);
            if(player == null || projected < 120 || shown >= 15){
                continue;
            }
            // report at my second pick, the one JFMarino sits in front of
            double h = entry.getValue()[1];
            double d = entry.getValue()[myPicks.length + 1];
            if(Math.abs(h - d) < 0.03){
                continue;
            }
            System.out.printf("   %-24s %-4s %7.0f %7.0f%% %7.0f%% %+8.0f pts%n",
                    player.firstName + " " + player.lastName, player.position, projected,
                    h * 100, d * 100, (d - h) * projected * 100 / 100);
            shown++;
        }
        if(shown == 0){
            System.out.println("   (no player's availability moves by more than 3 points)");
        }
        System.out.println("\nA drone is deterministic, so if he is autodrafting the"
                + "\nplayers he would take become certainties rather than risks - which"
                + "\nis information the live tool can use once two or three of his picks"
                + "\nmatch ADP exactly.");
    }

    static double impact(double[] row, int picks, double projected){
        double worst = 0;
        for(int i = 0; i < picks; i++){
            worst = Math.max(worst, Math.abs(row[i] - row[picks + i]));
        }
        return worst * projected;
    }
}
