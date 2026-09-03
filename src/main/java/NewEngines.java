import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Four engines aimed at the last 1-2 points, designed from what the scenario
 * tree just showed: the adaptive ceiling is ~+7 to +9 over committed and our
 * best live engine measures +7.2, so incremental search depth is spent - the
 * remaining value has to come from using the OBSERVATION better, which is
 * exactly what the tree's non-anticipativity structure rewards.
 *
 *   depth3        depth-3 heads with VORP tails - the brute-force control,
 *                 to confirm depth really is exhausted
 *   two-stage     at each pick, solve the two-stage stochastic program
 *                 exactly over sampled futures (here-and-now action, then
 *                 recourse solved per scenario) - the tree's logic applied
 *                 one step at a time, which is cheap and non-anticipative
 *   regret-match  play the action minimising worst-case regret across
 *                 futures rather than maximising the mean - different
 *                 objective, protects the downside the mean hides
 *   blend         mean and p25 combined (mean - 0.35 x downside), the risk
 *                 knob the planner always had but never had data to set
 *
 * All are evaluated by the tournament's own protocol so the numbers land in
 * the same table as the other 28 policies.
 *
 *   ./gradlew run -Pmain=NewEngines [-Ptrials=400] [-Pscenarios=40]
 */
public class NewEngines {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 400);
        int scenarios = Integer.getInteger("scenarios", 40);
        int inner = Integer.getInteger("inner", 16);

        PolicyTournament tournament = PolicyTournament.forCurrentGame(configuration, 300);
        Map<String, double[]> results = new LinkedHashMap<>();

        results.put("oldschool-2-vorp (incumbent)", tournament.evaluate(
                factory(seed -> tournament.new Lookahead(2, inner,
                        PolicyTournament.Tail.VORP, seed)), trials));
        results.put("depth3-vorp", tournament.evaluate(
                factory(seed -> tournament.new Lookahead(3, inner,
                        PolicyTournament.Tail.VORP, seed)), trials));
        results.put("two-stage-recourse", tournament.evaluate(
                factory(seed -> tournament.new TwoStage(scenarios, seed, 0.0)), trials));
        results.put("regret-match", tournament.evaluate(
                factory(seed -> tournament.new TwoStage(scenarios, seed, -1.0)), trials));
        results.put("blend mean-0.35xdownside", tournament.evaluate(
                factory(seed -> tournament.new TwoStage(scenarios, seed, 0.35)), trials));
        results.put("greedy-vorp (floor)", tournament.evaluate(
                factory(seed -> tournament.new GreedyVorp()), trials));

        double base = PolicyTournament.mean(results.get("greedy-vorp (floor)"));
        System.out.printf("%n%-32s %10s %8s %12s%n", "ENGINE", "mean", "+/-SE",
                "vs floor");
        results.entrySet().stream()
                .sorted((a, b) -> Double.compare(PolicyTournament.mean(b.getValue()),
                        PolicyTournament.mean(a.getValue())))
                .forEach(e -> System.out.printf("%-32s %10.1f %8.1f %+12.1f%n",
                        e.getKey(), PolicyTournament.mean(e.getValue()),
                        PolicyTournament.standardError(e.getValue()),
                        PolicyTournament.mean(e.getValue()) - base));
        System.out.printf("%n%d trials, shared eval seeds; scenarios=%d for the "
                + "two-stage family.%n", trials, scenarios);
    }

    static PolicyTournament.Factory factory(
            java.util.function.LongFunction<PolicyTournament.TournamentPolicy> make){
        return new PolicyTournament.Factory() {
            @Override
            public String name(){
                return "new";
            }

            @Override
            public PolicyTournament.TournamentPolicy create(long trialSeed){
                return make.apply(trialSeed);
            }
        };
    }
}
