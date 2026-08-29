import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * "He'll probably still be there next round" - measured instead of felt.
 *
 * The committee chooses a POSITION and then hands you the best player at it.
 * That is the right unit for the roster, but it is not the question a human
 * asks while looking at the board, which is about a PLAYER: if this receiver
 * survives eleven more picks, taking him now spends a pick I could have spent
 * on the tight end who will NOT survive.
 *
 * So this simulates forward from the live board to my next pick, many times,
 * and reports for every position's best-available player: how often he is
 * still there, and what the drop-off costs when he is not. Waiting is only
 * free when BOTH are true - he usually survives, AND the fallback behind him
 * is close. A 90% survival with a 40-point cliff behind it is not safe.
 *
 *     ./gradlew run -Pmain=WaitCheck -PdraftId=<id> [-Ptrials=400]
 */
public class WaitCheck {

    record Survival(Position position, String playerID, String name,
                    double survives, double dropWhenGone, double nextBestName) {}

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 400);
        String draftID = System.getProperty("draftId", configuration.getDraftID());

        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        TimingPlanner timing = new TimingPlanner(planner);
        timing.fillWaitingTable(200);
        DraftSimulator simulator = planner.simulator();
        Map<String, Double> points = planner.points();

        List<String> taken = LiveDraft.livePicks(draftID);
        DraftSimulator.SimState state = simulator.stateAfter(taken);
        if(simulator.slotOf(state) == null){
            System.out.println("The nine-round game is over.");
            return;
        }
        report(timing, planner, simulator, state, points, rollouts);
    }

    /**
     * The wait-or-take table, factored out of main so DraftNight can call it on
     * an engine that is already warm instead of paying the start-up again. The
     * mock measured that start-up at 17-20 seconds against a 60-second clock.
     */
    public static void report(TimingPlanner timing, DraftPlanner planner,
                              DraftSimulator simulator, DraftSimulator.SimState state,
                              Map<String, Double> points, int rollouts){
        DraftSimulator.Slot slot = simulator.slotOf(state);
        Map<Position, String> best = timing.bestAvailable(state.boardView());
        System.out.printf("%non the clock at pick %d (round %d); %d rollouts to my next pick%n",
                slot.pickNumber(), slot.round(), rollouts);

        // How often each position's best-available man is still there when I
        // pick again - conditioned on my having spent THIS pick elsewhere,
        // because that is the only world where the question arises.
        Map<Position, Integer> survived = new EnumMap<>(Position.class);
        Map<Position, Double> dropTotal = new EnumMap<>(Position.class);
        Map<Position, Integer> goneCount = new EnumMap<>(Position.class);
        for(Position position : best.keySet()){
            survived.put(position, 0);
            dropTotal.put(position, 0.0);
            goneCount.put(position, 0);
        }

        // My own pick must NOT be left to the generic manager model: simulated
        // me would sometimes take the very player whose survival is being
        // measured, counting him "gone" in a world where I waited. For each
        // position, spend this pick on the best man at a DIFFERENT position -
        // the only world in which the wait question is even asked.
        Map<Position, String> spendOn = new EnumMap<>(Position.class);
        for(Position position : best.keySet()){
            String choice = null;
            double bestPoints = -1;
            for(Map.Entry<Position, String> other : best.entrySet()){
                if(other.getKey() == position){
                    continue;
                }
                double value = points.getOrDefault(other.getValue(), 0.0);
                if(value > bestPoints){
                    bestPoints = value;
                    choice = other.getValue();
                }
            }
            spendOn.put(position, choice);
        }

        for(int trial = 0; trial < rollouts; trial++){
            for(Map.Entry<Position, String> entry : best.entrySet()){
                Position position = entry.getKey();
                String target = entry.getValue();
                Random random = new Random(4242L + 7919L * trial);
                DraftSimulator.SimState branch = simulator.branchWith(state,
                        spendOn.get(position));
                while(true){
                    DraftSimulator.Slot next = simulator.slotOf(branch);
                    if(next == null || next.manager().equals(planner.me())){
                        break;
                    }
                    simulator.simulateOneFrom(branch, random);
                }
                Map<Position, String> later = timing.bestAvailable(branch.boardView());
                if(branch.takenAtOf(target) == null){
                    survived.merge(position, 1, Integer::sum);
                }
                else {
                    goneCount.merge(position, 1, Integer::sum);
                    String fallback = later.get(position);
                    dropTotal.merge(position, points.getOrDefault(target, 0.0)
                            - (fallback == null ? 0.0 : points.getOrDefault(fallback, 0.0)),
                            Double::sum);
                }
            }
        }

        System.out.printf("%n%-5s %-24s %10s %14s   %s%n", "POS", "BEST AVAILABLE NOW",
                "SURVIVES", "COST IF GONE", "waiting is");
        List<Position> order = new ArrayList<>(best.keySet());
        for(Position position : order){
            String id = best.get(position);
            Player player = Player.getPlayerFromSIDV2(id);
            double survives = survived.get(position) / (double) rollouts;
            int gone = goneCount.get(position);
            double drop = gone == 0 ? 0 : dropTotal.get(position) / gone;
            // the honest number is the expected loss from waiting, not either
            // half of it on its own
            double expected = (1 - survives) * drop;
            String verdict = expected < 3 ? "FREE"
                    : expected < 10 ? "cheap (" + String.format("%.0f", expected) + " pts)"
                    : "COSTLY (" + String.format("%.0f", expected) + " pts)";
            System.out.printf("%-5s %-24s %9.0f%% %14.1f   %s%n", position,
                    player.firstName + " " + player.lastName, survives * 100, drop, verdict);
        }
        System.out.println("\nSURVIVES = still on the board at my next pick."
                + "  COST IF GONE = points between him and the next man at his"
                + " position.\nWaiting is only free when he usually survives AND"
                + " the fallback is close: the verdict multiplies the two.");
    }
}
