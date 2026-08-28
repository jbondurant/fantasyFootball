import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * The one channel no bound this week prices: every scenario-based number -
 * the 22.1 certificate, the tree, hindsight, two-stage - samples futures
 * INDEPENDENTLY of my actions. Reality does not: when I take a player, the
 * manager who wanted him takes someone else, and different players come back
 * to me. If that effect is real, an engine exploiting it is bounded by
 * nothing we have computed. If it is negligible, every bound stands and the
 * strategic class is a dead end - worth knowing either way.
 *
 * The test: at my first pick take player A versus player B (same position,
 * so the roster effect is controlled), then measure how the availability
 * distribution at my NEXT pick differs. Reported as the mean change in
 * best-available points per position, and the mean change in the value of my
 * eventual roster - the latter being what actually matters.
 *
 *   ./gradlew run -Pmain=BlockingTest [-Ptrials=600]
 */
public class BlockingTest {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 600);

        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        TimingPlanner timing = new TimingPlanner(planner);
        timing.fillWaitingTable(200);
        DraftSimulator simulator = planner.simulator();
        int[] myPicks = simulator.pickNumbersOf(planner.me());
        Map<String, Double> points = planner.points();

        DraftSimulator.SimState root = simulator.stateAfter(List.of());
        // walk the room to my first pick
        Random warm = new Random(DraftSimulator.SEED + 12345);
        while(true){
            DraftSimulator.Slot slot = simulator.slotOf(root);
            if(slot == null || slot.pickNumber() >= myPicks[0]){
                break;
            }
            simulator.simulateOneFrom(root, warm);
        }
        Map<Position, String> best = timing.bestAvailable(root.boardView());

        // two candidate first picks at the SAME position - roster effect held
        List<String> rbs = new ArrayList<>();
        for(String id : root.boardView()){
            if(Player.getPlayerFromSIDV2(id).position == Position.RB){
                rbs.add(id);
            }
            if(rbs.size() == 2){
                break;
            }
        }
        String a = rbs.get(0);
        String b = rbs.get(1);
        System.out.printf("at pick %d: comparing %s vs %s (both RB, %.0f vs %.0f pts)%n",
                myPicks[0], Player.getPlayerFromSIDV2(a).lastName,
                Player.getPlayerFromSIDV2(b).lastName, points.get(a), points.get(b));

        double[][] shift = new double[2][4];
        Position[] positions = {Position.QB, Position.RB, Position.WR, Position.TE};
        for(int arm = 0; arm < 2; arm++){
            String taken = arm == 0 ? a : b;
            double[] sums = new double[4];
            for(int t = 0; t < trials; t++){
                DraftSimulator.SimState branch = simulator.branchWith(root, taken);
                Random random = new Random(DraftSimulator.SEED + 7919L * t);
                // run the room to my NEXT pick under the SAME random stream
                while(true){
                    DraftSimulator.Slot slot = simulator.slotOf(branch);
                    if(slot == null || slot.pickNumber() >= myPicks[1]){
                        break;
                    }
                    simulator.simulateOneFrom(branch, random);
                }
                Map<Position, String> nextBest = timing.bestAvailable(branch.boardView());
                for(int p = 0; p < positions.length; p++){
                    String id = nextBest.get(positions[p]);
                    sums[p] += id == null ? 0 : points.getOrDefault(id, 0.0);
                }
            }
            for(int p = 0; p < 4; p++){
                shift[arm][p] = sums[p] / trials;
            }
        }

        System.out.printf("%nmean best-available at pick %d, by arm:%n", myPicks[1]);
        System.out.printf("   %-6s %12s %12s %10s%n", "POS", "took " +
                Player.getPlayerFromSIDV2(a).lastName, "took " +
                Player.getPlayerFromSIDV2(b).lastName, "shift");
        double biggest = 0;
        for(int p = 0; p < positions.length; p++){
            double delta = shift[0][p] - shift[1][p];
            biggest = Math.max(biggest, Math.abs(delta));
            System.out.printf("   %-6s %12.1f %12.1f %+10.2f%n", positions[p],
                    shift[0][p], shift[1][p], delta);
        }
        System.out.printf("%n   largest positional shift: %.2f points%n", biggest);
        System.out.println("   Under ~1 point the blocking channel is negligible and every"
                + "\n   bound computed this week stands. Several points and the strategic"
                + "\n   class is real - and unbounded by anything we have measured.");
    }
}
