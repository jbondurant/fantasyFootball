import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Show the decision, rather than reasoning about it.
 *
 * The sixteen-round model takes a defence at pick 79 and two attempts to explain
 * it - the rollout tail policy, then a false defence shortage - were both wrong,
 * because both were inferences about numbers nobody had printed. This prints
 * them: the roster as the objective sees it, every slot and what fills it, and
 * what each position's best available man would actually add.
 *
 *   ./gradlew run -Pmain=MarginalTrace [-Ppick=79] [-Pshape="RB RB RB WR WR WR"]
 */
public class MarginalTrace {

    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", "16");
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int target = Integer.getInteger("pick", 79);
        String shape = System.getProperty("shape", "RB RB RB WR WR WR");

        List<Keeper> myKeepers = DraftPlanner.keepersFromProperty(configuration);
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, myKeepers,
                model, earliness);
        DraftSimulator simulator = planner.simulator();
        RiskDiscountedValue value = new RiskDiscountedValue(planner.points(),
                RiskDiscountedValue.positionGamesMissed(),
                InsuranceTest.replacementRanks(configuration),
                PositionPredictability.reliability());

        // build the roster the plan would have, up to the pick in question
        List<Position> wanted = new ArrayList<>();
        for(String token : shape.trim().split("\\s+")){
            wanted.add(Position.valueOf(token));
        }
        DraftSimulator.SimState state = simulator.stateAfter(List.of());
        List<String> mine = new ArrayList<>(planner.myKeeperIDs());
        Random random = new Random(DraftSimulator.SEED);
        int taken = 0;
        while(simulator.slotOf(state) != null
                && simulator.slotOf(state).pickNumber() < target){
            DraftSimulator.Slot slot = simulator.slotOf(state);
            if(planner.me().equals(slot.manager()) && taken < wanted.size()){
                String pick = best(simulator, state, planner, wanted.get(taken));
                if(pick != null){
                    mine.add(pick);
                    state = simulator.branchWith(state, pick);
                    taken++;
                    continue;
                }
            }
            simulator.simulateOneFrom(state, random);
        }

        System.out.printf("%nAT PICK %d, after %s%n%n", target, shape);
        System.out.printf("MY ROSTER (%d)%n", mine.size());
        System.out.printf("   %-24s %-4s %10s %12s%n", "PLAYER", "POS", "projection",
                "discounted");
        List<String> sorted = new ArrayList<>(mine);
        sorted.sort(Comparator.comparingDouble(
                (String id) -> -planner.points().getOrDefault(id, 0.0)));
        for(String id : sorted){
            Player player = Player.getPlayerFromSIDV2(id);
            System.out.printf("   %-24s %-4s %10.1f %12.1f%n",
                    player.firstName + " " + player.lastName, player.position,
                    planner.points().getOrDefault(id, 0.0), value.valueOf(id));
        }

        System.out.printf("%nWHAT EACH SLOT CONTRIBUTES%n");
        System.out.printf("   %-8s %-26s %10s%n", "SLOT", "filled by", "points");
        value.explain(mine);

        System.out.printf("%nWHAT EACH POSITION WOULD ADD%n");
        System.out.printf("   %-4s %-24s %10s %11s %12s %10s%n", "POS", "BEST AVAILABLE",
                "projection", "after trust", "discounted", "ADDS");
        double base = value.of(mine);
        Map<Position, Double> adds = new EnumMap<>(Position.class);
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE,
                Position.QB, Position.DEF}){
            String candidate = best(simulator, state, planner, position);
            if(candidate == null){
                continue;
            }
            List<String> trial = new ArrayList<>(mine);
            trial.add(candidate);
            double marginal = value.of(trial) - base;
            adds.put(position, marginal);
            Player player = Player.getPlayerFromSIDV2(candidate);
            System.out.printf("   %-4s %-24s %10.1f %11.1f %12.1f %10.1f%n", position,
                    player.firstName + " " + player.lastName,
                    planner.points().getOrDefault(candidate, 0.0),
                    value.believedOf(candidate), value.valueOf(candidate), marginal);
        }
        Position winner = adds.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        System.out.printf("%n   the objective takes %s%n", winner);
        System.out.printf("%n   an unfilled slot is worth: %s%n", value.unfilledValues());
    }

    static String best(DraftSimulator simulator, DraftSimulator.SimState state,
                       DraftPlanner planner, Position position){
        String best = null;
        double top = -1;
        for(String id : simulator.players()){
            if(state.takenAtOf(id) != null){
                continue;
            }
            Player player = Player.getPlayerFromSIDV2(id);
            if(player == null || player.position != position){
                continue;
            }
            double points = planner.points().getOrDefault(id, 0.0);
            if(points > top){
                top = points;
                best = id;
            }
        }
        return best;
    }
}
