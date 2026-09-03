import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Will he still be there at my NEXT pick - asked over the whole draft, not
 * just the nine-round game.
 *
 * LateRoundTargets reported survival "after nine rounds", because the
 * simulated board stopped at round 9. For a rounds 10-16 stash that is the
 * wrong finish line: it said Bo Nix survives 87%, meaning to pick 108, when
 * the pick in question was 162. Everything survives a race that stops early.
 *
 * This runs the board out to round 16 and records, at every one of my picks,
 * which targets are still on it. The gap that matters here is structural: with
 * keepers at r12 and r13 my picks jump 127 -> 162, thirty-five picks with no
 * selection of my own. Anyone I want after 127 has to be taken AT 127.
 *
 *   ./gradlew run -Pmain=LateSurvival [-Ptrials=400] [-PdraftId=<id>]
 *   ./gradlew run -Pmain=LateSurvival -Ptargets="Bo Nix,Jaxson Dart"
 */
public class LateSurvival {

    /** The RUNBOOK's stash targets, as a default. */
    static final String DEFAULT_TARGETS = "Bo Nix,Jaxson Dart,Tyler Shough,Cam Ward";

    public static void main(String[] args) throws Exception {
        // The whole point of this tool: run the board past round 9.
        System.setProperty("scheduleRounds", System.getProperty("scheduleRounds", "16"));

        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 400);
        String draftID = System.getProperty("draftId");

        List<Keeper> myKeepers = DraftPlanner.keepersFromProperty(configuration);
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, myKeepers,
                model, earliness);
        DraftSimulator simulator = planner.simulator();
        Map<String, Double> points = planner.points();

        List<String> taken = draftID == null ? List.of() : LiveDraft.livePicks(draftID);

        // name -> id, for the targets asked about
        Map<String, String> targets = new LinkedHashMap<>();
        for(String name : System.getProperty("targets", DEFAULT_TARGETS).split(",")){
            String wanted = name.trim();
            for(String id : points.keySet()){
                Player player = Player.getPlayerFromSIDV2(id);
                if(player != null
                        && (player.firstName + " " + player.lastName).equalsIgnoreCase(wanted)){
                    targets.put(wanted, id);
                    break;
                }
            }
            if(!targets.containsKey(wanted)){
                System.out.println("not on the board: " + wanted);
            }
        }
        if(targets.isEmpty()){
            System.out.println("no targets found - nothing to measure");
            return;
        }

        // pick number -> target name -> times still available
        Map<Integer, Map<String, Integer>> available = new TreeMap<>();
        for(int trial = 0; trial < trials; trial++){
            Random random = new Random(778_000L + 7919L * trial);
            DraftSimulator.SimState state = simulator.stateAfter(taken);
            DraftSimulator.Slot slot;
            while((slot = simulator.slotOf(state)) != null){
                if(planner.me().equals(slot.manager())){
                    Map<String, Integer> row = available.computeIfAbsent(
                            slot.pickNumber(), u -> new LinkedHashMap<>());
                    for(Map.Entry<String, String> target : targets.entrySet()){
                        if(state.takenAtOf(target.getValue()) == null){
                            row.merge(target.getKey(), 1, Integer::sum);
                        }
                    }
                }
                simulator.simulateOneFrom(state, random);
            }
        }

        System.out.printf("%nboard run to round %d, %d trials, %d picks already gone%n",
                DraftPlanner.scheduleRounds(), trials, taken.size());
        System.out.printf("my remaining picks: %s%n%n", available.keySet());
        System.out.printf("%-8s %-7s", "PICK", "ROUND");
        for(String name : targets.keySet()){
            System.out.printf(" %18s", name);
        }
        System.out.println();
        Integer previous = null;
        for(Map.Entry<Integer, Map<String, Integer>> entry : available.entrySet()){
            int pickNumber = entry.getKey();
            int round = 1 + (pickNumber - 1) / 12;
            String gap = previous == null ? "" : "   (" + (pickNumber - previous)
                    + " picks since my last)";
            System.out.printf("%-8d %-7d", pickNumber, round);
            for(String name : targets.keySet()){
                System.out.printf(" %17.0f%%",
                        100.0 * entry.getValue().getOrDefault(name, 0) / trials);
            }
            System.out.println(gap);
            previous = pickNumber;
        }

        System.out.println("\nRead this as the wait-or-take answer for the back half."
                + " A target whose\nsurvival collapses between two of my picks has to be"
                + " taken at the earlier\none - and the 127 -> 162 gap is where that"
                + " bites hardest, because nothing\nof mine happens in between.");
        System.out.printf("%nNOTE: the choice model is fitted on rounds 1-%d, so rounds"
                + " %d-16 extrapolate.%n", SelectionModel.TRAIN_ROUNDS,
                SelectionModel.TRAIN_ROUNDS + 1);
    }
}
