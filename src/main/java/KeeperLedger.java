import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every team's full keeper ledger: for each ELIGIBLE player on each roster,
 * the standalone value of having kept him - seat value with just that keeper
 * minus the keeperless seat - sorted per team, the actually-kept players
 * marked with **. Every value comes from the full expectimax at reduced
 * rollouts (a greedy shortcut was tried and removed: it drafts QB round one,
 * a blunder the branching exists to prevent, which inflated every QB-keeper
 * scenario). Standalone values ignore pair interaction: a kept pair's joint
 * worth is less than the sum, since freed-pick cascades overlap.
 *
 *     ./gradlew run -Pmain=KeeperLedger [-Ptrials=60] [-Pkeepers=Tuten,Purdy]
 */
public class KeeperLedger {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 60);
        String me = configuration.getMyID();

        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, lastCompleted);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, lastCompleted,
                earliness);
        Map<String, Double> points = SleeperProjections.parseTodaysWebPage();
        List<Keeper> justinPair = DraftPlanner.keepersFromProperty(configuration);

        JsonObject draftOrder = configuration.getDraftJson().getAsJsonObject("draft_order");
        Map<Integer, String> bySlot = new TreeMap<>();
        for(Map.Entry<String, JsonElement> entry : draftOrder.entrySet()){
            bySlot.put(entry.getValue().getAsInt(), entry.getKey());
        }

        List<Keeper> declared = configuration.getTodaysKeepers();
        System.out.printf("standalone keeper deltas, every eligible player, %d rollouts%n"
                + "(** = actually kept; standalone values ignore pair overlap; noise ~+/-4):%n", rollouts);

        for(Map.Entry<Integer, String> seat : bySlot.entrySet()){
            String manager = seat.getValue();
            Set<String> keptIDs = new java.util.HashSet<>();
            for(Keeper keeper : declared){
                if(manager.equals(keeper.humanWhoCanKeep)){
                    keptIDs.add(keeper.player.sleeperIDString);
                }
            }
            if(manager.equals(me)){
                for(Keeper keeper : justinPair){
                    keptIDs.add(keeper.player.sleeperIDString);
                }
            }
            List<Keeper> worldExtras = manager.equals(me) ? List.of() : justinPair;

            double base = DraftPlanner.forCurrentSeasonAs(configuration, manager,
                            worldExtras, keptIDs, model, earliness)
                    .plan(rollouts, 0, 0.10, DraftSimulator.SEED).mean();

            record Line(Keeper candidate, double delta){}
            List<Line> lines = new ArrayList<>();
            for(Keeper candidate : KeeperChooser.eligibleCandidates(configuration, manager)){
                if(!StartingLineup.isSkillPosition(candidate.player.position)
                        || points.getOrDefault(candidate.player.sleeperIDString, 0.0) <= 0.0){
                    continue;
                }
                List<Keeper> extras = new ArrayList<>(worldExtras);
                extras.add(candidate);
                double value = DraftPlanner.forCurrentSeasonAs(configuration, manager,
                                extras, keptIDs, model, earliness)
                        .plan(rollouts, 0, 0.10, DraftSimulator.SEED).mean();
                lines.add(new Line(candidate, value - base));
            }
            lines.sort(Comparator.comparingDouble((Line line) -> line.delta()).reversed());

            System.out.printf("%n%s (slot %d, keeperless seat %.1f):%n",
                    HumanOfInterest.getHumanFromID(manager), seat.getKey(), base);
            for(Line line : lines){
                boolean kept = keptIDs.contains(line.candidate().player.sleeperIDString);
                String name = line.candidate().player.firstName + " "
                        + line.candidate().player.lastName;
                System.out.printf("   %-34s %-3s r%-3d %+7.1f%n",
                        kept ? "**" + name + "**" : name,
                        line.candidate().player.position,
                        line.candidate().roundCanBeKept, line.delta());
            }
        }
    }

}
