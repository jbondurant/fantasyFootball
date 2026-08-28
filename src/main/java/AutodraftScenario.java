import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Justin's intel: JFMarino is ~50/50 to autodraft the first five rounds.
 * That matters far more than a coin flip on one manager usually would,
 * because of where he sits. Justin is slot 7, JFMarino slot 8, and the snake
 * puts JFMarino at pick 17 - IMMEDIATELY BEFORE Justin's pick 18. Whatever
 * he does is the last thing that happens before Justin's second turn.
 *
 * An autodrafter takes the best remaining player by ADP, deterministically.
 * That is strictly more predictable than a human, so the question is not
 * only "does the plan change" but "is the wait-or-take decision at pick 7
 * sharper when I know what happens at 17".
 *
 * Runs Justin's seat in both worlds and compares: plan, value, and the
 * survival of his round-2 targets.
 *
 *   ./gradlew run -Pmain=AutodraftScenario [-Ptrials=400]
 */
public class AutodraftScenario {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 400);

        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        Map<String, Double> points = planner.points();

        String marino = null;
        for(String manager : planner.simulator().managers()){
            if(HumanOfInterest.getHumanFromID(manager).contains("Marino")){
                marino = manager;
            }
        }
        if(marino == null){
            System.out.println("JFMarino not found among managers");
            return;
        }
        int[] myPicks = planner.simulator().pickNumbersOf(planner.me());
        int[] hisPicks = planner.simulator().pickNumbersOf(marino);
        System.out.printf("my picks   %s%nJFMarino   %s  <- pick %d lands immediately "
                        + "before my %d%n%n", java.util.Arrays.toString(myPicks),
                java.util.Arrays.toString(hisPicks), hisPicks[1], myPicks[1]);

        DraftSimulator human = planner.simulator();
        DraftSimulator robot = human.withManagerModels(
                Map.of(marino, OpponentVariants.autodraft()));

        // what does he take, and what survives to my pick 18, in each world?
        for(String world : new String[]{"human", "autodraft"}){
            DraftSimulator simulator = world.equals("human") ? human : robot;
            Map<String, Integer> takesAt17 = new HashMap<>();
            Map<String, Integer> aliveAt18 = new HashMap<>();
            Random random = new Random(DraftSimulator.SEED + 31337);
            for(int t = 0; t < trials; t++){
                Map<String, Integer> takenAt = simulator.simulateOnce(random);
                for(Map.Entry<String, Integer> entry : takenAt.entrySet()){
                    if(entry.getValue() == hisPicks[1]){
                        takesAt17.merge(entry.getKey(), 1, Integer::sum);
                    }
                }
                for(String id : simulator.players()){
                    if(takenAt.getOrDefault(id, 999) >= myPicks[1]){
                        aliveAt18.merge(id, 1, Integer::sum);
                    }
                }
            }
            System.out.printf("=== %s world ===%n", world);
            System.out.println("   his pick 17, most likely:");
            takesAt17.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue()).limit(4)
                    .forEach(e -> {
                        Player p = Player.getPlayerFromSIDV2(e.getKey());
                        System.out.printf("      %-24s %-4s %5.0f%%%n",
                                p.firstName + " " + p.lastName, p.position,
                                100.0 * e.getValue() / trials);
                    });
            System.out.println("   best players still alive at my pick 18:");
            aliveAt18.entrySet().stream()
                    .filter(e -> e.getValue() > trials / 4)
                    .sorted((a, b) -> Double.compare(points.getOrDefault(b.getKey(), 0.0),
                            points.getOrDefault(a.getKey(), 0.0))).limit(5)
                    .forEach(e -> {
                        Player p = Player.getPlayerFromSIDV2(e.getKey());
                        System.out.printf("      %-24s %-4s %5.0f pts  %4.0f%% alive%n",
                                p.firstName + " " + p.lastName, p.position,
                                points.getOrDefault(e.getKey(), 0.0),
                                100.0 * e.getValue() / trials);
                    });
            System.out.println();
        }

        // does my best plan or its value change?
        TimingPlanner timingHuman = new TimingPlanner(planner);
        timingHuman.fillWaitingTable(200);
        System.out.printf("value of my shipped plan in each world (%d rollouts):%n", trials);
        List<Position> shipped = List.of(Position.RB, Position.RB, Position.RB,
                Position.WR, Position.WR, Position.WR, Position.TE, Position.QB,
                Position.RB);
        for(String world : new String[]{"human", "autodraft"}){
            DraftSimulator simulator = world.equals("human") ? human : robot;
            double mean = IntStream.range(0, trials).parallel().mapToDouble(r -> {
                TimingPlanner.CommittedPolicy policy = timingHuman.new CommittedPolicy(
                        shipped);
                simulator.simulateOnce(new Random(TimingPlanner.EVAL_SEED + 7919L * r),
                        planner.me(), policy);
                return StartingLineup.bestNine(policy.mine, points);
            }).sum() / trials;
            System.out.printf("   %-12s %8.1f%n", world, mean);
        }
    }
}
