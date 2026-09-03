import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * The scoreboard where model-humble algorithms can finally win (Justin's
 * framing): a matrix of TRUE opponent worlds the planner does not know -
 * different brains, different rationality, different QB appetites - crossed
 * with policies that either trust the base model, hedge across an assumed
 * ensemble (Bayes / robust committed plans), adapt to the board (lookahead),
 * or infer the world online from the live board's depletion pace (PaceVorp).
 *
 * Every policy is built from the BASE tournament's beliefs and then dropped
 * into each true world via evaluateIn - plan with your model, live in the
 * truth. The MEAN column is the Bayes score, the MIN column the robustness
 * score; the prediction under test is that fixed-model policies top the base
 * column while the hedgers and the online corrector top MIN and the
 * mismatch columns.
 *
 *   ./gradlew run -Pmain=MismatchArena [-Ptrials=1200] [-Pkeepers=...]
 */
public class MismatchArena {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 1200);
        int lookaheadTrials = Integer.getInteger("adaptiveTrials", 150);
        int search = Integer.getInteger("search", 40);

        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, lastCompleted);
        ChoiceModel boosted = BoostedSelectionModel.fitShipped(configuration, lastCompleted,
                earliness);
        SelectionModel linear = SelectionModel.fitShipped(configuration, lastCompleted,
                earliness);
        Map<String, Double> pressure = new HashMap<>(earliness);
        Map<String, Double> lazy = new HashMap<>(earliness);
        for(String manager : earliness.keySet()){
            if(!manager.equals(configuration.getMyID())){
                pressure.merge(manager, 0.7, Double::sum);
                lazy.merge(manager, -0.7, Double::sum);
            }
        }

        PolicyTournament base = PolicyTournament.forCurrentGame(configuration, 300);
        Map<String, PolicyTournament> worlds = new LinkedHashMap<>();
        worlds.put("base", base);
        worlds.put("linear", PolicyTournament.forCurrentGame(configuration, 150,
                linear, earliness));
        worlds.put("drones", PolicyTournament.forCurrentGame(configuration, 150,
                OpponentVariants.sharpen(boosted, 6.0), earliness));
        worlds.put("chaos", PolicyTournament.forCurrentGame(configuration, 150,
                OpponentVariants.chaos(boosted, 0.35), earliness));
        worlds.put("qb-rush", PolicyTournament.forCurrentGame(configuration, 150,
                boosted, pressure));
        worlds.put("qb-lazy", PolicyTournament.forCurrentGame(configuration, 150,
                boosted, lazy));

        // Committed plans: base-optimal, Bayes (mean over the assumed
        // ensemble) and robust (min over it). The assumed ensemble is
        // deliberately SMALLER than the true world set - the planner may
        // imagine three brains; reality has six.
        PolicyTournament.Needs start =
                PolicyTournament.Needs.afterKeepers(base.myKeeperIDs());
        List<List<Position>> sequences = PolicyTournament.allSequences(start,
                base.myPickCount());
        List<PolicyTournament> assumed = List.of(base, worlds.get("linear"),
                worlds.get("chaos"));
        System.out.printf("scoring %d sequences across %d assumed worlds at %d CRN "
                + "rollouts...%n", sequences.size(), assumed.size(), search);
        double[][] scores = new double[assumed.size()][];
        for(int w = 0; w < assumed.size(); w++){
            PolicyTournament world = assumed.get(w);
            scores[w] = IntStream.range(0, sequences.size()).parallel()
                    .mapToDouble(s -> base.searchMeanIn(world, sequences.get(s), search))
                    .toArray();
        }
        int baseArg = 0;
        int bayesArg = 0;
        int robustArg = 0;
        double bestBase = -Double.MAX_VALUE;
        double bestBayes = -Double.MAX_VALUE;
        double bestRobust = -Double.MAX_VALUE;
        for(int s = 0; s < sequences.size(); s++){
            double mean = 0;
            double min = Double.MAX_VALUE;
            for(int w = 0; w < assumed.size(); w++){
                mean += scores[w][s];
                min = Math.min(min, scores[w][s]);
            }
            mean /= assumed.size();
            if(scores[0][s] > bestBase){
                bestBase = scores[0][s];
                baseArg = s;
            }
            if(mean > bestBayes){
                bestBayes = mean;
                bayesArg = s;
            }
            if(min > bestRobust){
                bestRobust = min;
                robustArg = s;
            }
        }
        List<Position> basePlan = sequences.get(baseArg);
        List<Position> bayesPlan = sequences.get(bayesArg);
        List<Position> robustPlan = sequences.get(robustArg);
        System.out.printf("base plan %s, bayes plan %s, robust plan %s%n",
                basePlan, bayesPlan, robustPlan);

        Map<String, PolicyTournament.Factory> policies = new LinkedHashMap<>();
        policies.put("committed-base", named(base, seed -> base.new SequencePolicy(basePlan)));
        policies.put("committed-bayes", named(base, seed -> base.new SequencePolicy(bayesPlan)));
        policies.put("committed-robust", named(base,
                seed -> base.new SequencePolicy(robustPlan)));
        policies.put("vorp (base beliefs)", named(base, seed -> base.new GreedyVorp()));
        policies.put("pace-vorp (online)", named(base, seed -> base.new PaceVorp()));
        policies.put("lookahead-1v (base model)", named(base,
                seed -> base.new Lookahead(1, 12, PolicyTournament.Tail.VORP, seed)));

        System.out.printf("%n%-26s", "POLICY");
        for(String world : worlds.keySet()){
            System.out.printf(" %9s", world);
        }
        System.out.printf(" %9s %9s%n", "MEAN", "MIN");
        for(Map.Entry<String, PolicyTournament.Factory> policy : policies.entrySet()){
            boolean lookahead = policy.getKey().startsWith("lookahead");
            int n = lookahead ? lookaheadTrials : trials;
            System.out.printf("%-26s", policy.getKey());
            double mean = 0;
            double min = Double.MAX_VALUE;
            for(PolicyTournament world : worlds.values()){
                double value = PolicyTournament.mean(
                        base.evaluateIn(world, policy.getValue(), n));
                System.out.printf(" %9.1f", value);
                mean += value;
                min = Math.min(min, value);
            }
            System.out.printf(" %9.1f %9.1f%n", mean / worlds.size(), min);
        }
        System.out.printf("%nassumed ensemble = {base, linear, chaos}; true worlds add "
                + "drones and the QB shifts the planner never imagined. %d trials per "
                + "cell (%d for the lookahead), shared eval seeds per column.%n",
                trials, lookaheadTrials);
    }

    private static PolicyTournament.Factory named(PolicyTournament tournament,
            java.util.function.LongFunction<PolicyTournament.TournamentPolicy> create){
        return new PolicyTournament.Factory() {
            @Override
            public String name(){
                return "arena";
            }

            @Override
            public PolicyTournament.TournamentPolicy create(long trialSeed){
                return create.apply(trialSeed);
            }
        };
    }
}
