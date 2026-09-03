import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Did the valley trap fire inside the keeper ledger? PolicyTournament showed
 * the staged one-round frontier, with its raw-greedy tails, commits QB in
 * round 2 in a QB-open game (-10.3 vs the exhaustive optimum's QB-last).
 * Every ledger delta is V(candidate) - V(base) with the owner's declared
 * keepers stripped from the base - so almost every BASE branch is exactly
 * such a QB-open seat, and a mistimed base plan understates V(base) and
 * inflates every QB candidate's delta.
 *
 * The trap needs a valley; if the real game's QB timing value is monotone,
 * the staged search slides QB back one round per stage all the way to the
 * shelf despite the bad tails, and the ledger stands. This prints, for every
 * manager's keeperless baseline, the staged plan and where QB landed - the
 * direct evidence either way.
 *
 *   ./gradlew run -Pmain=BaselinePlanAudit [-Ptrials=150]
 */
public class BaselinePlanAudit {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 150);

        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, lastCompleted);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, lastCompleted,
                earliness);

        JsonObject draftOrder = configuration.getDraftJson().getAsJsonObject("draft_order");
        List<Keeper> declared = configuration.getTodaysKeepers();

        System.out.printf("keeperless baseline plans (the ledger's V(base) branch), staged "
                + "search at %d rollouts:%n%n", rollouts);
        System.out.printf("   %-22s %-32s %8s %8s%n", "MANAGER", "PLAN", "QB rd", "mean");
        for(Map.Entry<String, JsonElement> entry : draftOrder.entrySet()){
            String manager = entry.getKey();
            Set<String> stripped = new HashSet<>();
            for(Keeper keeper : declared){
                if(manager.equals(keeper.humanWhoCanKeep)){
                    stripped.add(keeper.player.sleeperIDString);
                }
            }
            DraftPlanner planner = DraftPlanner.forCurrentSeasonAs(configuration, manager,
                    List.of(), stripped, model, earliness);
            DraftPlanner.Plan plan = planner.plan(rollouts, 0, 0.10, DraftSimulator.SEED);
            List<Position> positions = plan.positions();
            List<Integer> qbRounds = new ArrayList<>();
            for(int i = 0; i < positions.size(); i++){
                if(positions.get(i) == Position.QB){
                    qbRounds.add(i + 1);
                }
            }
            StringBuilder label = new StringBuilder();
            for(Position position : positions){
                label.append(position.name().charAt(0));
            }
            System.out.printf("   %-22s %-32s %8s %8.1f%n",
                    HumanOfInterest.getHumanFromID(manager), label,
                    qbRounds.isEmpty() ? "none" : qbRounds.toString(), plan.mean());
        }
        System.out.println("\nQB in rounds 7-9 everywhere = the slide reached the shelf and"
                + "\nthe ledger's baselines were planned sanely; an early QB round is the"
                + "\nvalley trap, live inside the keeper numbers.");
    }
}
