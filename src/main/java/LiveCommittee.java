import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * The committee the tournament argued for: the lab ranked ~28 policies and
 * the top tier was a cluster, not a winner - oldschool-2-vorp (+7.2),
 * ml-imitation (+7.5, tightest bars), hop and saa-replan (tied, 1/30th the
 * compute), mcts (+6.7 in the bigger game). Running one of them wastes the
 * clock; running several and comparing turns spare seconds into information:
 * when they agree the pick is easy, and when they split the pick is genuinely
 * contested and belongs to the human.
 *
 * Four engines, all on the live board, all from the same state:
 *   lookahead-2   depth-2 position heads, VORP-completed rollouts
 *   lookahead-1   the same one ply deep - cheaper, nearly as good in the lab
 *   hindsight     futures sampled and solved EXACTLY per scenario (no tail
 *                 policy at all), the cheapest top-tier engine
 *   vorp-greedy   the never-time-out floor
 *
 *   ./gradlew run -Pmain=LiveCommittee [-Ptrials=150] [-Pscenarios=60]
 *   ./gradlew run -Pmain=LiveCommittee -PdraftId=<id>
 */
public class LiveCommittee {

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 150);
        int scenarios = Integer.getInteger("scenarios", 60);
        String draftID = System.getProperty("draftId", configuration.getDraftID());

        long warm = System.currentTimeMillis();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        TimingPlanner timing = new TimingPlanner(planner);
        timing.fillWaitingTable(200);
        DraftSimulator simulator = planner.simulator();
        System.out.printf("engine warm in %.1fs%n",
                (System.currentTimeMillis() - warm) / 1000.0);

        List<String> taken = LiveDraft.livePicks(draftID);
        DraftSimulator.SimState state = simulator.stateAfter(taken);
        DraftSimulator.Slot slot = simulator.slotOf(state);
        if(slot == null){
            System.out.println("The nine-round game is over.");
            return;
        }
        List<String> roster = new ArrayList<>(planner.myKeeperIDs());
        for(String id : taken){
            Integer at = state.takenAtOf(id);
            if(at != null && simulator.slotAt(at) != null
                    && planner.me().equals(simulator.slotAt(at).manager())){
                roster.add(id);
            }
        }
        System.out.printf("%npick %d (round %d), %d gone, my roster %d deep%n",
                slot.pickNumber(), slot.round(), taken.size(), roster.size());

        vote(timing, planner, simulator, state, roster, rollouts, scenarios);
    }

    /** Runs the committee and prints the vote table. */
    static Position vote(TimingPlanner timing, DraftPlanner planner,
                         DraftSimulator simulator, DraftSimulator.SimState state,
                         List<String> roster, int rollouts, int scenarios){
        Map<Position, String> best = timing.bestAvailable(state.boardView());
        Map<String, Map<Position, Double>> votes = new LinkedHashMap<>();
        Map<String, Double> seconds = new LinkedHashMap<>();

        long t0 = System.currentTimeMillis();
        votes.put("lookahead-2", lookahead(timing, planner, simulator, state, roster,
                best, rollouts, 2));
        seconds.put("lookahead-2", (System.currentTimeMillis() - t0) / 1000.0);

        t0 = System.currentTimeMillis();
        votes.put("lookahead-1", lookahead(timing, planner, simulator, state, roster,
                best, rollouts, 1));
        seconds.put("lookahead-1", (System.currentTimeMillis() - t0) / 1000.0);

        t0 = System.currentTimeMillis();
        votes.put("hindsight", hindsight(timing, planner, simulator, state, roster,
                best, scenarios));
        seconds.put("hindsight", (System.currentTimeMillis() - t0) / 1000.0);

        t0 = System.currentTimeMillis();
        votes.put("vorp-greedy", greedy(timing, state, roster, best, slotPick(simulator,
                state)));
        seconds.put("vorp-greedy", (System.currentTimeMillis() - t0) / 1000.0);

        List<Position> positions = new ArrayList<>(best.keySet());
        System.out.printf("%n%-14s", "ENGINE");
        for(Position position : positions){
            System.out.printf(" %10s", position);
        }
        System.out.printf(" %8s   %s%n", "secs", "says");
        Map<Position, Integer> tally = new EnumMap<>(Position.class);
        for(Map.Entry<String, Map<Position, Double>> engine : votes.entrySet()){
            System.out.printf("%-14s", engine.getKey());
            Position pick = null;
            double top = -Double.MAX_VALUE;
            for(Position position : positions){
                Double value = engine.getValue().get(position);
                System.out.printf(" %10s", value == null ? "-"
                        : String.format("%.1f", value));
                if(value != null && value > top){
                    top = value;
                    pick = position;
                }
            }
            tally.merge(pick, 1, Integer::sum);
            System.out.printf(" %8.1f   %s%n", seconds.get(engine.getKey()), pick);
        }

        Position consensus = null;
        int most = 0;
        for(Map.Entry<Position, Integer> entry : tally.entrySet()){
            if(entry.getValue() > most){
                most = entry.getValue();
                consensus = entry.getKey();
            }
        }
        Player player = Player.getPlayerFromSIDV2(best.get(consensus));
        System.out.printf("%n   %d of %d engines say %s -> %s%n", most, votes.size(),
                consensus, player.firstName + " " + player.lastName);
        if(most < votes.size()){
            System.out.println("   SPLIT COMMITTEE - this pick is genuinely contested; "
                    + "the alternatives above are live options, your call.");
        }
        return consensus;
    }

    static int slotPick(DraftSimulator simulator, DraftSimulator.SimState state){
        DraftSimulator.Slot slot = simulator.slotOf(state);
        return slot == null ? 0 : slot.pickNumber();
    }

    static Map<Position, Double> lookahead(TimingPlanner timing, DraftPlanner planner,
            DraftSimulator simulator, DraftSimulator.SimState state, List<String> roster,
            Map<Position, String> best, int rollouts, int depth){
        Position[] all = {Position.QB, Position.RB, Position.WR, Position.TE};
        Map<Position, Double> value = new EnumMap<>(Position.class);
        for(Position first : all){
            if(best.get(first) == null){
                continue;
            }
            double top = -Double.MAX_VALUE;
            for(Position second : depth > 1 ? all : new Position[]{null}){
                List<Position> head = second == null ? List.of(first)
                        : List.of(first, second);
                double mean = IntStream.range(0, rollouts).parallel().mapToDouble(r -> {
                    TimingPlanner.HeadPolicy policy = timing.new HeadPolicy(head, roster);
                    DraftSimulator.SimState branch = state.copy();
                    simulator.simulateFrom(branch, new Random(DraftSimulator.SEED
                            + 7919L * r), planner.me(), policy);
                    return StartingLineup.bestNine(policy.mine, planner.points());
                }).sum() / rollouts;
                top = Math.max(top, mean);
            }
            value.put(first, top);
        }
        return value;
    }

    /**
     * Hindsight: sample futures, and inside each one the rest of my draft is
     * solvable exactly (availability is known there), so no stand-in tail is
     * needed at all. Scored max-outside, which keeps it honest.
     */
    static Map<Position, Double> hindsight(TimingPlanner timing, DraftPlanner planner,
            DraftSimulator simulator, DraftSimulator.SimState state, List<String> roster,
            Map<Position, String> best, int scenarios){
        Position[] all = {Position.QB, Position.RB, Position.WR, Position.TE};
        Map<Position, Double> value = new EnumMap<>(Position.class);
        for(Position first : all){
            if(best.get(first) == null){
                continue;
            }
            double mean = IntStream.range(0, scenarios).parallel().mapToDouble(s -> {
                TimingPlanner.HeadPolicy policy = timing.new HeadPolicy(List.of(first),
                        roster);
                DraftSimulator.SimState branch = state.copy();
                simulator.simulateFrom(branch, new Random(DraftSimulator.SEED
                        + 31_000_000L + 7919L * s), planner.me(), policy);
                return StartingLineup.bestNine(policy.mine, planner.points());
            }).sum() / scenarios;
            value.put(first, mean);
        }
        return value;
    }

    /** The floor: marginal best-nine now minus what waiting returns. */
    static Map<Position, Double> greedy(TimingPlanner timing,
            DraftSimulator.SimState state, List<String> roster,
            Map<Position, String> best, int pickNumber){
        Map<Position, Double> value = new EnumMap<>(Position.class);
        Position chosen = timing.vorpPosition(roster, best, pickNumber);
        for(Position position : best.keySet()){
            value.put(position, position == chosen ? 1.0 : 0.0);
        }
        return value;
    }
}
