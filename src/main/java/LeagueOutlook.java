import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The whole league through the same lens: every manager's seat optimized by
 * the expectimax from THEIR slot with THEIR keepers, everyone else playing
 * the fitted model. Three answers per seat:
 *
 *   - max expected best-nine (the ranking),
 *   - the slot-group breakdown (where each roster's points come from),
 *   - two decompositions: keeperless seat value (what the draft SLOT alone
 *     is worth) and per-keeper deltas (drop one keeper, re-optimize, diff -
 *     the same V(K)-V(K minus k) yardstick as KeeperPlan, from their seat).
 *
 * Managers who have not declared are valued keeperless and marked. Justin's
 * locked pair rides in via -Pkeepers until the commissioner enters it.
 *
 *     ./gradlew run -Pmain=LeagueOutlook [-Ptrials=150] [-Pkeepers=Tuten,Purdy]
 */
public class LeagueOutlook {

    /** One seat's three answers, so the arithmetic can be tested apart from
     *  the printing. */
    public record Seat(DraftPlanner.Plan plan, DraftPlanner.Profile profile,
                       double keeperless, Map<String, Double> keeperDeltas){}

    /**
     * The counterfactual seat: the same manager with droppedIDs taken out of
     * the world entirely.
     *
     * The filter is the whole point. forCurrentSeasonAs applies its exclude
     * set to DECLARED keepers only - an explicit extra always lands, by
     * design, so KeeperLedger can float a kept player back in as his own
     * hypothetical. That makes naming the same keeper in BOTH lists a silent
     * no-op: the exclusion strips nothing, because an undeclared keeper was
     * never in the declared list, and the extra puts him straight back. The
     * baseline then IS the with-keepers world, bit for bit, and every keeper
     * on that seat prices at exactly +0.0. Only the -Pkeepers seat can hit
     * this, which is why Justin's was the one seat reading zero.
     */
    static DraftPlanner baseline(AAAConfiguration configuration, String manager,
                                 List<Keeper> extras, Set<String> droppedIDs,
                                 ChoiceModel model, Map<String, Double> earliness){
        List<Keeper> world = extras.stream()
                .filter(extra -> !droppedIDs.contains(extra.player.sleeperIDString))
                .toList();
        return DraftPlanner.forCurrentSeasonAs(configuration, manager, world, droppedIDs,
                model, earliness);
    }

    /** Optimize one seat, then price its keepers against the seat without them. */
    public static Seat evaluate(AAAConfiguration configuration, String manager,
                                List<Keeper> theirs, List<Keeper> extras,
                                ChoiceModel model, Map<String, Double> earliness,
                                int rollouts, double q){
        DraftPlanner planner = DraftPlanner.forCurrentSeasonAs(configuration, manager,
                extras, Set.of(), model, earliness);
        DraftPlanner.Plan plan = planner.plan(rollouts, 0, q, DraftSimulator.SEED);
        DraftPlanner.Profile profile = planner.profile(plan.positions(), rollouts,
                DraftSimulator.SEED);

        double keeperless = plan.mean();
        Map<String, Double> deltas = new HashMap<>();
        if(!theirs.isEmpty()){
            Set<String> allTheirs = new HashSet<>();
            for(Keeper keeper : theirs){
                allTheirs.add(keeper.player.sleeperIDString);
            }
            keeperless = baseline(configuration, manager, extras, allTheirs, model, earliness)
                    .plan(rollouts, 0, q, DraftSimulator.SEED).mean();
            for(Keeper keeper : theirs){
                double without = theirs.size() == 1 ? keeperless
                        : baseline(configuration, manager, extras,
                                        Set.of(keeper.player.sleeperIDString), model, earliness)
                                .plan(rollouts, 0, q, DraftSimulator.SEED).mean();
                deltas.put(keeper.player.lastName, plan.mean() - without);
            }
        }
        return new Seat(plan, profile, keeperless, deltas);
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 150);
        double q = 0.10;

        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, lastCompleted);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, lastCompleted,
                earliness);
        List<Keeper> extras = DraftPlanner.keepersFromProperty(configuration);

        List<Keeper> declared = new ArrayList<>(configuration.getTodaysKeepers());
        for(Keeper extra : extras){
            if(declared.stream().noneMatch(keeper ->
                    keeper.player.sleeperIDString.equals(extra.player.sleeperIDString))){
                declared.add(extra);
            }
        }

        JsonObject draftOrder = configuration.getDraftJson().getAsJsonObject("draft_order");
        Map<Integer, String> bySlot = new TreeMap<>();
        for(Map.Entry<String, JsonElement> entry : draftOrder.entrySet()){
            bySlot.put(entry.getValue().getAsInt(), entry.getKey());
        }

        record Row(String manager, int slot, Seat seat, List<Keeper> keepers){}
        List<Row> rows = new ArrayList<>();
        for(Map.Entry<Integer, String> seat : bySlot.entrySet()){
            String manager = seat.getValue();
            List<Keeper> theirs = declared.stream()
                    .filter(keeper -> manager.equals(keeper.humanWhoCanKeep)).toList();

            Seat evaluated = evaluate(configuration, manager, theirs, extras, model, earliness,
                    rollouts, q);
            rows.add(new Row(manager, seat.getKey(), evaluated, theirs));
            System.out.printf("   evaluated %-22s (slot %2d, %d keepers)%n",
                    HumanOfInterest.getHumanFromID(manager), seat.getKey(), theirs.size());
        }

        rows.sort(Comparator.comparingDouble((Row row) -> row.seat().plan().mean()).reversed());
        System.out.printf("%nthe league, every seat optimized (%d rollouts; managers without a%n"
                + "declaration are valued keeperless and marked *):%n%n", rollouts);
        System.out.printf("   %-4s %-16s %4s %9s %9s %8s   %s%n",
                "RANK", "MANAGER", "SLOT", "best-9", "slotless*", "keepers", "");
        System.out.printf("   %-4s %-16s %4s %9s %9s %8s   %s%n",
                "", "", "", "", "(no kprs)", "worth", "keeper deltas");
        int rank = 1;
        for(Row row : rows){
            StringBuilder keeperText = new StringBuilder();
            for(Keeper keeper : row.keepers()){
                if(keeperText.length() > 0){
                    keeperText.append(", ");
                }
                keeperText.append(String.format("%s r%d %+.0f", keeper.player.lastName,
                        keeper.roundCanBeKept,
                        row.seat().keeperDeltas().getOrDefault(keeper.player.lastName, 0.0)));
            }
            System.out.printf("   %-4d %-16s %4d %9.1f %9.1f %+8.1f   %s%s%n",
                    rank++, HumanOfInterest.getHumanFromID(row.manager()), row.slot(),
                    row.seat().plan().mean(), row.seat().keeperless(),
                    row.seat().plan().mean() - row.seat().keeperless(),
                    keeperText.length() == 0 ? "(none declared) *" : keeperText,
                    "");
        }

        System.out.println("\nwhere each optimized roster's points come from:\n");
        System.out.printf("   %-16s %7s %7s %7s %7s %7s%n",
                "MANAGER", "QB", "RBx2", "WRx3", "TE", "FLEXx2");
        for(Row row : rows){
            StartingLineup.NineBreakdown slots = row.seat().profile().slots();
            System.out.printf("   %-16s %7.1f %7.1f %7.1f %7.1f %7.1f%n",
                    HumanOfInterest.getHumanFromID(row.manager()),
                    slots.qb(), slots.rb(), slots.wr(), slots.te(), slots.flex());
        }
        System.out.println("\nslot value alone: compare the keeperless column across seats -");
        System.out.println("that spread is what serpentine position is worth this year.");
    }

}
