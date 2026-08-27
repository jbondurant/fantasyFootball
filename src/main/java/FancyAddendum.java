import PlayerImportAndSetup.Position;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The last three of the five (hindsight, distribution-DP, expert iteration),
 * evaluated on the SAME eval seed stream as the tournament tables so their
 * rows slot directly into tonight's v3 rankings:
 *
 *   hop        hindsight optimization - each sampled future solved exactly,
 *              actions scored by their per-future optima (max inside)
 *   saa-replan the unbiased cousin - sequences scored by scenario averages,
 *              re-planned each pick (max outside)
 *   saa-plan   the committed form: the distribution-aware DP, scenario style
 *   exit-*     expert iteration, two distill-lookahead cycles: the distilled
 *              policy alone, and the lookahead running on distilled tails
 *
 * greedy-vorp is re-evaluated as the anchor row tying this table to v3's.
 *
 *   ./gradlew run -Pmain=FancyAddendum [-Ptrials=800] [-PadaptiveTrials=150]
 *                 [-Pkeepers=Tuten,Flowers]
 */
public class FancyAddendum {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 800);
        int adaptiveTrials = Integer.getInteger("adaptiveTrials", 150);
        int inner = Integer.getInteger("inner", 12);
        int scenarios = Integer.getInteger("scenarios", 24);

        PolicyTournament tournament = PolicyTournament.forCurrentGame(configuration, 300);

        long start = System.currentTimeMillis();
        List<Position> saaPlan = tournament.saaPlan(200);
        System.out.printf("saa-committed plan %s (%.0fs)%n", saaPlan,
                (System.currentTimeMillis() - start) / 1000.0);
        start = System.currentTimeMillis();
        BoostedRegressor exit = tournament.trainExit(2, 120, inner);
        System.out.printf("expert iteration trained, 2 cycles (%.0fs)%n",
                (System.currentTimeMillis() - start) / 1000.0);

        Map<String, Object[]> rows = new LinkedHashMap<>();
        rows.put("hop (S=" + scenarios + ", exact futures)", new Object[]{adaptiveTrials,
                (PolicyTournament.Factory) named("hop",
                        seed -> tournament.new HindsightPolicy(scenarios, true, seed))});
        rows.put("saa-replan (S=" + scenarios + ")", new Object[]{adaptiveTrials,
                named("saa-replan",
                        seed -> tournament.new HindsightPolicy(scenarios, false, seed))});
        rows.put("saa-committed " + saaPlan, new Object[]{trials,
                named("saa-committed", seed -> tournament.new SequencePolicy(saaPlan))});
        rows.put("exit-policy (2 cycles)", new Object[]{trials,
                named("exit-policy", seed -> tournament.new ModelPolicy(exit))});
        rows.put("exit-agent (lookahead on distilled tails)", new Object[]{adaptiveTrials,
                named("exit-agent", seed -> tournament.new Lookahead(2, inner,
                        PolicyTournament.Tail.MODEL, seed, exit))});
        rows.put("greedy-vorp (anchor to v3 table)", new Object[]{trials,
                named("anchor", seed -> tournament.new GreedyVorp())});

        System.out.printf("%n%-44s %8s %10s %8s%n", "POLICY", "trials", "mean", "+/-SE");
        for(Map.Entry<String, Object[]> row : rows.entrySet()){
            int n = (Integer) row.getValue()[0];
            long rowStart = System.currentTimeMillis();
            double[] scores = tournament.evaluate(
                    (PolicyTournament.Factory) row.getValue()[1], n);
            System.out.printf("%-44s %8d %10.1f %8.1f   (%.0fs)%n", row.getKey(), n,
                    PolicyTournament.mean(scores), PolicyTournament.standardError(scores),
                    (System.currentTimeMillis() - rowStart) / 1000.0);
        }
        System.out.println("\nSame eval seeds as the tournament tables - rows are directly"
                + "\ncomparable to tonight's v3 rankings.");
    }

    private static PolicyTournament.Factory named(String name,
            java.util.function.LongFunction<PolicyTournament.TournamentPolicy> create){
        return new PolicyTournament.Factory() {
            @Override
            public String name(){
                return name;
            }

            @Override
            public PolicyTournament.TournamentPolicy create(long trialSeed){
                return create.apply(trialSeed);
            }
        };
    }
}
