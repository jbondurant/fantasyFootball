import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * D1: the number that bounds every future "could anything beat this?" -
 * information-relaxation duality in its unpenalized form. The clairvoyant
 * value (each sampled future solved EXACTLY over all committed sequences,
 * then averaged) is a provable upper bound on ANY policy's value; the best
 * measured policy is the lower bound; the difference certifies the maximum
 * unclaimed points in this game.
 *
 *   ./gradlew run -Pmain=GapCertificate [-Pscenarios=400] [-Ptrials=300]
 *                 [-Pkeepers=Tuten,Flowers]
 */
public class GapCertificate {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int scenarios = Integer.getInteger("scenarios", 400);
        int trials = Integer.getInteger("trials", 300);
        int inner = Integer.getInteger("inner", 16);

        PolicyTournament tournament = PolicyTournament.forCurrentGame(configuration, 300);
        PolicyTournament.Needs start =
                PolicyTournament.Needs.afterKeepers(tournament.myKeeperIDs());
        List<List<Position>> sequences = PolicyTournament.allSequences(start,
                tournament.myPickCount());
        List<String> keepers = new ArrayList<>(tournament.myKeeperIDs());

        // upper bound: mean over futures of the per-future exact optimum
        long startTime = System.currentTimeMillis();
        double[] bounds = IntStream.range(0, scenarios).parallel().mapToDouble(s -> {
            PolicyTournament.Scenario scenario = tournament.sampleScenario(
                    tournament.simulatorState(), PolicyTournament.TRAIN_SEED
                            + 37_000_000L + 7919L * s);
            double best = -Double.MAX_VALUE;
            for(List<Position> sequence : sequences){
                best = Math.max(best,
                        tournament.scenarioValue(scenario, keepers, 0, sequence));
            }
            return best;
        }).toArray();
        double upper = PolicyTournament.mean(bounds);
        double upperSE = PolicyTournament.standardError(bounds);
        System.out.printf("clairvoyant upper bound %.1f +/- %.1f  (%d futures solved "
                        + "exactly over %d sequences, %.0fs)%n", upper, upperSE, scenarios,
                sequences.size(), (System.currentTimeMillis() - startTime) / 1000.0);

        // lower bound: the best practical policy, measured on fresh seeds
        double[] scores = tournament.evaluate(new PolicyTournament.Factory() {
            @Override
            public String name(){
                return "oldschool-2-vorp";
            }

            @Override
            public PolicyTournament.TournamentPolicy create(long trialSeed){
                return tournament.new Lookahead(2, inner, PolicyTournament.Tail.VORP,
                        trialSeed);
            }
        }, trials);
        double lower = PolicyTournament.mean(scores);
        double lowerSE = PolicyTournament.standardError(scores);
        System.out.printf("best policy lower bound %.1f +/- %.1f  (oldschool-2-vorp, "
                + "%d trials)%n", lower, lowerSE, trials);
        System.out.printf("%nCERTIFIED GAP: %.1f points (+/- %.1f)%n", upper - lower,
                Math.sqrt(upperSE * upperSE + lowerSE * lowerSE));
        System.out.println("The clairvoyant bound includes the value of knowing the "
                + "future; the true optimal policy sits somewhere inside the gap, so "
                + "the gap OVERSTATES what any algorithm can still collect.");
    }
}
