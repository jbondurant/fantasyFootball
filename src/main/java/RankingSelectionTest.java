import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Does statistically rigorous budget allocation beat equal allocation?
 * The committee currently gives every position the same fixed rollouts.
 * Kim-Nelson runs them sequentially on common random numbers, eliminates
 * candidates the moment they are statistically dominated, and either proves
 * a selection at the stated confidence or reports an honest tie.
 *
 *   ./gradlew run -Pmain=RankingSelectionTest [-Ptrials=400]
 */
public class RankingSelectionTest {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 400);
        PolicyTournament tournament = PolicyTournament.forCurrentGame(configuration, 300);

        Map<String, double[]> results = new LinkedHashMap<>();
        results.put("KN delta=1 alpha=.05", tournament.evaluate(FluxDraft.named(
                seed -> tournament.new RankingSelection(1.0, 0.05, 8, 64, seed)), trials));
        results.put("KN delta=3 alpha=.10", tournament.evaluate(FluxDraft.named(
                seed -> tournament.new RankingSelection(3.0, 0.10, 8, 64, seed)), trials));
        results.put("equal allocation (16)", tournament.evaluate(FluxDraft.named(
                seed -> tournament.new Lookahead(1, 16, PolicyTournament.Tail.VORP, seed)),
                trials));
        results.put("equal allocation (64)", tournament.evaluate(FluxDraft.named(
                seed -> tournament.new Lookahead(1, 64, PolicyTournament.Tail.VORP, seed)),
                trials));

        System.out.printf("%n%-28s %10s %8s%n", "PROCEDURE", "mean", "+/-SE");
        results.entrySet().stream()
                .sorted((a, b) -> Double.compare(PolicyTournament.mean(b.getValue()),
                        PolicyTournament.mean(a.getValue())))
                .forEach(e -> System.out.printf("%-28s %10.1f %8.1f%n", e.getKey(),
                        PolicyTournament.mean(e.getValue()),
                        PolicyTournament.standardError(e.getValue())));
        System.out.println("\nKN's value is not a higher mean - it is the same decision"
                + "\nwith a stated confidence and fewer wasted rollouts, plus an honest"
                + "\n'this is a tie' signal when the candidates are within delta.");
    }
}
