import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Overnight phase 3 (Justin's reframe): different SIMULATIONS x different
 * MODELS, so tomorrow's precompute runs on a validated way to compute. Six
 * worlds - the shipped boosted brain, the interpretable linear brain at three
 * temperatures, and QB-pressure / QB-lazy behavioral shifts (every opponent's
 * fitted earliness moved +/-0.7 rounds) - each world racing the same
 * candidates: the shipped committed plan, the world's own best timing head,
 * the base world's best head, and reactive VORP.
 *
 * The question is not which world is true (the boosted brain won the gates;
 * it IS the best estimate) - it is whether MY decisions survive being wrong
 * about the world. A plan within a point of the best in every world is
 * draft-proof; a plan that collapses in the QB-pressure world is a risk with
 * a name.
 *
 *   ./gradlew run -Pmain=WorldsRace [-Ptrials=2000] [-Psearch=120]
 */
public class WorldsRace {

    record World(String name, ChoiceModel model, Map<String, Double> earliness){}

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 2000);
        int search = Integer.getInteger("search", 120);

        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, lastCompleted);
        ChoiceModel boosted = BoostedSelectionModel.fitShipped(configuration, lastCompleted,
                earliness);
        SelectionModel linear = SelectionModel.fitShipped(configuration, lastCompleted,
                earliness);
        String me = configuration.getMyID();

        Map<String, Double> pressure = new HashMap<>(earliness);
        Map<String, Double> lazy = new HashMap<>(earliness);
        for(String manager : earliness.keySet()){
            if(!manager.equals(me)){
                pressure.merge(manager, 0.7, Double::sum);
                lazy.merge(manager, -0.7, Double::sum);
            }
        }

        List<World> worlds = List.of(
                new World("boosted (base)", boosted, earliness),
                new World("linear", linear, earliness),
                new World("linear soft t1.5", linear.scaled(1.5), earliness),
                new World("linear sharp t0.7", linear.scaled(0.7), earliness),
                new World("QB-pressure +0.7", boosted, pressure),
                new World("QB-lazy -0.7", boosted, lazy));

        List<Position> shipped = List.of(Position.RB, Position.WR, Position.RB,
                Position.WR, Position.WR, Position.WR, Position.TE, Position.QB,
                Position.RB);

        int[] baseHead = null;
        System.out.printf("%-20s %-16s %10s %10s %10s %10s%n", "WORLD", "best head",
                "own-head", "base-head", "shipped", "vorp");
        for(World world : worlds){
            DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                    List.of(), world.model(), world.earliness());
            TimingPlanner timing = new TimingPlanner(planner);
            timing.fillWaitingTable(200);
            int picks = planner.simulator().pickNumbersOf(planner.me()).length;

            List<int[]> heads = new ArrayList<>();
            for(int qbAt = -1; qbAt < picks; qbAt++){
                for(int teAt = 0; teAt < picks; teAt++){
                    if(teAt != qbAt){
                        heads.add(new int[]{qbAt, teAt});
                    }
                }
            }
            double[] means = IntStream.range(0, heads.size()).parallel().mapToDouble(h -> {
                double total = 0;
                for(int r = 0; r < search; r++){
                    TimingPlanner.TimingPolicy policy = timing.new TimingPolicy(
                            heads.get(h)[0], heads.get(h)[1]);
                    planner.simulator().simulateOnce(
                            new Random(TimingPlanner.SEARCH_SEED + 7919L * r),
                            planner.me(), policy);
                    total += StartingLineup.bestNine(policy.mine, planner.points());
                }
                return total / search;
            }).toArray();
            int argmax = 0;
            for(int h = 1; h < means.length; h++){
                if(means[h] > means[argmax]){
                    argmax = h;
                }
            }
            int[] ownHead = heads.get(argmax);
            if(baseHead == null){
                baseHead = ownHead;
            }
            int[] base = baseHead;

            double ownMean = TimingPlanner.mean(timing.evaluate(
                    r -> timing.new TimingPolicy(ownHead[0], ownHead[1]), trials,
                    TimingPlanner.EVAL_SEED));
            double baseMean = TimingPlanner.mean(timing.evaluate(
                    r -> timing.new TimingPolicy(base[0], base[1]), trials,
                    TimingPlanner.EVAL_SEED));
            double shippedMean = TimingPlanner.mean(timing.evaluate(
                    r -> timing.new CommittedPolicy(shipped), trials,
                    TimingPlanner.EVAL_SEED));
            double vorpMean = TimingPlanner.mean(timing.evaluate(
                    r -> timing.new VorpPolicy(), trials, TimingPlanner.EVAL_SEED));

            System.out.printf("%-20s QB@%-5s TE@r%-3d %10.1f %10.1f %10.1f %10.1f%n",
                    world.name(),
                    ownHead[0] < 0 ? "none" : "r" + (ownHead[0] + 1), ownHead[1] + 1,
                    ownMean, baseMean, shippedMean, vorpMean);
        }
        System.out.printf("%neach row: that world's own searched head vs the base world's "
                + "head vs the shipped plan vs reactive VORP, all at %d fresh rollouts. "
                + "Shipped within ~1 of own-head everywhere = draft-proof.%n", trials);
    }
}
