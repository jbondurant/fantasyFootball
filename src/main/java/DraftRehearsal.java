import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The rehearsal component F needs and no mock draft can give: the real
 * draft is empty until Tuesday, so the simulator GENERATES mid-draft boards
 * instead. It plays a full draft with the fitted opponents, stops at each of
 * my picks, and runs the live decision path exactly as draft night will -
 * same state replay, same engine, same output - while timing every call.
 *
 * This exercises what a Sleeper mock cannot: my league's keepers, my slot,
 * and every round including the late ones where the board is thin.
 *
 *   ./gradlew run -Pmain=DraftRehearsal [-Ptrials=150] [-Pseed=1]
 */
public class DraftRehearsal {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 150);
        long seed = Long.getLong("seed", 1L);

        long warm = System.currentTimeMillis();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        TimingPlanner timing = new TimingPlanner(planner);
        timing.fillWaitingTable(200);
        DraftSimulator simulator = planner.simulator();
        System.out.printf("engine warm in %.1fs (paid once, before the draft)%n",
                (System.currentTimeMillis() - warm) / 1000.0);

        int[] myPicks = simulator.pickNumbersOf(planner.me());
        List<String> roster = new ArrayList<>(planner.myKeeperIDs());
        List<String> taken = new ArrayList<>();
        DraftSimulator.SimState state = simulator.stateAfter(taken);
        Random world = new Random(DraftSimulator.SEED + 400_000L * seed);
        double slowest = 0;

        for(int pick : myPicks){
            // let the room draft up to my turn, exactly as it will on the night
            while(true){
                DraftSimulator.Slot slot = simulator.slotOf(state);
                if(slot == null || slot.pickNumber() >= pick){
                    break;
                }
                DraftSimulator.SimState step = state.copy();
                simulator.simulateOneFrom(step, world);
                taken.add(step.lastTaken());
                state = simulator.stateAfter(taken);
            }
            DraftSimulator.Slot slot = simulator.slotOf(state);
            if(slot == null){
                break;
            }
            System.out.printf("%n================ pick %d (round %d), %d players gone "
                    + "================", slot.pickNumber(), slot.round(), taken.size());
            long start = System.currentTimeMillis();
            Position choice = LiveDraft.recommend(timing, planner, simulator, state,
                    roster, rollouts);
            slowest = Math.max(slowest, (System.currentTimeMillis() - start) / 1000.0);

            String chosen = timing.bestAvailable(state.boardView()).get(choice);
            roster.add(chosen);
            taken.add(chosen);
            state = simulator.stateAfter(taken);
            Player player = Player.getPlayerFromSIDV2(chosen);
            System.out.printf("   -> took %s (%s)%n",
                    player.firstName + " " + player.lastName, player.position);
        }

        System.out.printf("%n%nrehearsal complete. Final roster:%n");
        double total = 0;
        for(String id : roster){
            Player player = Player.getPlayerFromSIDV2(id);
            double points = planner.points().getOrDefault(id, 0.0);
            total += points;
            System.out.printf("   %-4s %-26s %7.1f%n", player.position,
                    player.firstName + " " + player.lastName, points);
        }
        System.out.printf("%n   best nine: %.1f   (roster total %.1f over %d players)%n",
                StartingLineup.bestNine(roster, planner.points()), total, roster.size());
        System.out.printf("   slowest decision: %.1fs  (budget 15s preferred, 30s cap)%n",
                slowest);
    }
}
