import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Class 1 and Class 2 in one build, because they need the same object.
 *
 * The certificate we have (22.1) is CLAIRVOYANT: it lets the decision maker
 * see the whole future. That bound cannot say how much of the gap a real
 * policy could capture. Getting that requires solving the adaptive problem
 * exactly, which requires branching scenarios - a tree - not the independent
 * sample paths hindsight uses.
 *
 * So: sample N futures, group them at each of my picks by what I could
 * actually OBSERVE there (a coarse signature of the board), and solve by
 * backward induction with the non-anticipativity constraint that scenarios
 * sharing a signature must share an action. That yields three numbers on the
 * same scenarios:
 *
 *   committed    best single sequence            (lower bound, no adaptation)
 *   ADAPTIVE     the exact optimum of the tree   (what any policy could reach)
 *   clairvoyant  best sequence per scenario      (upper bound, sees the future)
 *
 * adaptive - committed  = the value of adapting, exactly
 * clairvoyant - adaptive = pure clairvoyance, provably uncapturable
 *
 * The middle number is the one nothing in this project has measured, and it
 * is what decides whether any algorithm has points left to find.
 *
 *   ./gradlew run -Pmain=ScenarioTree [-Pscenarios=300] [-Pbuckets=3]
 */
public class ScenarioTree {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int scenarioCount = Integer.getInteger("scenarios", 300);
        int buckets = Integer.getInteger("buckets", 3);

        PolicyTournament tournament = PolicyTournament.forCurrentGame(configuration, 200);
        PolicyTournament.Needs start =
                PolicyTournament.Needs.afterKeepers(tournament.myKeeperIDs());
        int picks = tournament.myPickCount();
        List<PolicyTournament.Scenario> scenarios = new ArrayList<>();
        for(int s = 0; s < scenarioCount; s++){
            scenarios.add(tournament.sampleScenario(tournament.simulatorState(),
                    PolicyTournament.TRAIN_SEED + 61_000_000L + 7919L * s));
        }
        List<String> keepers = new ArrayList<>(tournament.myKeeperIDs());
        System.out.printf("%d scenarios, %d picks, %d-bucket observations%n",
                scenarioCount, picks, buckets);

        // ---- the three values, all on these same scenarios ----
        List<List<Position>> sequences = PolicyTournament.allSequences(start, picks);
        double bestCommitted = -Double.MAX_VALUE;
        List<Position> committedPlan = null;
        for(List<Position> sequence : sequences){
            double total = 0;
            for(PolicyTournament.Scenario scenario : scenarios){
                total += tournament.scenarioValue(scenario, keepers, 0, sequence);
            }
            double mean = total / scenarios.size();
            if(mean > bestCommitted){
                bestCommitted = mean;
                committedPlan = sequence;
            }
        }
        double clairvoyant = 0;
        for(PolicyTournament.Scenario scenario : scenarios){
            double best = -Double.MAX_VALUE;
            for(List<Position> sequence : sequences){
                best = Math.max(best,
                        tournament.scenarioValue(scenario, keepers, 0, sequence));
            }
            clairvoyant += best;
        }
        clairvoyant /= scenarios.size();

        // ---- backward induction with non-anticipativity ----
        List<Integer> all = new ArrayList<>();
        for(int s = 0; s < scenarios.size(); s++){
            all.add(s);
        }
        // Diagnostic: is the observation signature actually discriminating?
        // If every scenario looks alike at my picks, the tree collapses to the
        // committed problem and "adaptive" would be meaningless.
        System.out.print("   distinct observations per pick: ");
        for(int epoch = 0; epoch < picks; epoch++){
            java.util.Set<String> distinct = new java.util.HashSet<>();
            for(PolicyTournament.Scenario scenario : scenarios){
                distinct.add(signature(tournament, scenario, epoch, buckets));
            }
            System.out.print(distinct.size() + (epoch < picks - 1 ? ", " : ""));
        }
        System.out.println();

        Map<String, Double> memo = new HashMap<>();
        double adaptive = solve(tournament, scenarios, all, keepers, start,
                new ArrayList<>(), picks, buckets, memo);

        System.out.printf("%n   committed    %10.1f   (best single sequence)%n",
                bestCommitted);
        System.out.printf("   ADAPTIVE     %10.1f   (exact optimum of the tree)%n",
                adaptive);
        System.out.printf("   clairvoyant  %10.1f   (sees the future)%n%n", clairvoyant);
        System.out.printf("   value of adapting      %+8.1f   <- capturable, in principle%n",
                adaptive - bestCommitted);
        System.out.printf("   pure clairvoyance      %+8.1f   <- provably uncapturable%n",
                clairvoyant - adaptive);
        System.out.printf("%n   committed plan on these scenarios: %s%n", committedPlan);
        System.out.println("\nThe adaptive row is the ceiling for ANY non-anticipative"
                + "\npolicy on this scenario set. Compare it to the live engines'"
                + "\nmeasured values: what is left is what an algorithm could still win.");
    }

    /**
     * V(prefix, scenario group) = max over actions of the average continuation,
     * with scenarios re-grouped by what is observable after each action - the
     * non-anticipativity constraint, enforced by construction.
     */
    static double solve(PolicyTournament tournament,
                        List<PolicyTournament.Scenario> scenarios, List<Integer> group,
                        List<String> keepers, PolicyTournament.Needs needs,
                        List<Position> prefix, int picks, int buckets,
                        Map<String, Double> memo){
        if(prefix.size() == picks || group.isEmpty()){
            double total = 0;
            for(int s : group){
                total += tournament.scenarioValue(scenarios.get(s), keepers, 0, prefix);
            }
            return group.isEmpty() ? 0 : total / group.size();
        }
        String key = prefix + "|" + group.hashCode();
        Double cached = memo.get(key);
        if(cached != null){
            return cached;
        }
        double best = -Double.MAX_VALUE;
        for(Position action : needs.feasibleSkill()){
            PolicyTournament.Needs next = needs.copy();
            next.consume(action);
            List<Position> extended = new ArrayList<>(prefix);
            extended.add(action);
            // regroup: scenarios indistinguishable at the NEXT pick share an action
            Map<String, List<Integer>> byObservation = new LinkedHashMap<>();
            for(int s : group){
                byObservation.computeIfAbsent(
                        signature(tournament, scenarios.get(s), extended.size(), buckets),
                        u -> new ArrayList<>()).add(s);
            }
            double total = 0;
            for(List<Integer> subgroup : byObservation.values()){
                total += subgroup.size() * solve(tournament, scenarios, subgroup, keepers,
                        next, extended, picks, buckets, memo);
            }
            best = Math.max(best, total / group.size());
        }
        memo.put(key, best);
        return best;
    }

    /**
     * What I can see at pick t: how good the best available player is at each
     * position, bucketed. Coarse on purpose - the finer the signature, the
     * closer the tree drifts to clairvoyance, so this is the honest knob.
     */
    static String signature(PolicyTournament tournament,
                            PolicyTournament.Scenario scenario, int epoch, int buckets){
        if(epoch >= tournament.myPickCount()){
            return "end";
        }
        StringBuilder key = new StringBuilder();
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR,
                Position.TE}){
            double best = tournament.scenarioBest(scenario, epoch, position);
            key.append((int) (best / (300.0 / buckets))).append(',');
        }
        return key.toString();
    }
}
