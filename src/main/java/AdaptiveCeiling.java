import java.util.Map;

/**
 * How high is the ceiling? Every policy trained on the simulator - trees,
 * nets, anything - approximates the same object: the simulated game's optimal
 * policy. The practical question before buying bigger ML tools is how much
 * headroom is left above the current best entry, and lookahead answers it
 * empirically: oldschool-2 with VORP tails converges toward the optimum as
 * its inner rollouts grow, so the value curve over inner counts shows where
 * the plateau - the effective ceiling - sits.
 *
 * Same eval-seed stream as the tournament, so means here are directly
 * comparable to the tournament tables, and the vs-16 column is paired.
 *
 *   ./gradlew run -Pmain=AdaptiveCeiling [-Ptrials=60] [-Pkeepers=Tuten,Flowers]
 */
public class AdaptiveCeiling {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 60);
        int[] inners = {16, 48, 144};

        PolicyTournament tournament = PolicyTournament.forCurrentGame(configuration, 150);

        System.out.printf("%noldschool-2 (vorp tails) as inner rollouts grow, %d trials "
                + "(paired, shared eval seeds):%n%n", trials);
        System.out.printf("   %-8s %10s %8s %12s%n", "inner", "mean", "+/-SE", "vs inner16");
        double[] reference = null;
        for(int inner : inners){
            long start = System.currentTimeMillis();
            double[] scores = tournament.evaluate(new PolicyTournament.Factory() {
                @Override
                public String name(){
                    return "oldschool-2-vorp-" + inner;
                }

                @Override
                public PolicyTournament.TournamentPolicy create(long trialSeed){
                    return tournament.new Lookahead(2, inner, PolicyTournament.Tail.VORP,
                            trialSeed);
                }
            }, trials);
            if(reference == null){
                reference = scores;
            }
            double delta = 0;
            for(int r = 0; r < trials; r++){
                delta += scores[r] - reference[r];
            }
            delta /= trials;
            System.out.printf("   %-8d %10.1f %8.1f %+12.1f   (%.0fs)%n", inner,
                    PolicyTournament.mean(scores), PolicyTournament.standardError(scores),
                    delta, (System.currentTimeMillis() - start) / 1000.0);
        }
        System.out.println("\nIf the curve is flat, the current entries already sit at the"
                + "\nsimulated game's effective ceiling and bigger models can only tie;"
                + "\na rising curve is unclaimed value that more compute - or a better"
                + "\nfunction approximator - can still collect.");
    }
}
