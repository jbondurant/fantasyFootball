import PlayerImportAndSetup.Position;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The open question flagged on N2: Model A's search says RB,RB,RB,WR,WR,WR,TE
 * while the older shipped plan says RB,WR,RB,WR,WR,WR,TE - four running backs
 * feeding the flexes versus four receivers. Seven independent searches picked
 * the RB-heavy version, but they ran at 150 rollouts; this prices both at high
 * precision on fresh paired seeds, alongside the live engine that will
 * actually be driving on Tuesday.
 *
 *   ./gradlew run -Pmain=PlanShowdown [-Ptrials=4000]
 */
public class PlanShowdown {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 4000);
        PolicyTournament tournament = PolicyTournament.forCurrentGame(configuration, 300);

        List<Position> rbHeavy = List.of(Position.RB, Position.RB, Position.RB,
                Position.WR, Position.WR, Position.WR, Position.TE);
        List<Position> wrHeavy = List.of(Position.RB, Position.WR, Position.RB,
                Position.WR, Position.WR, Position.WR, Position.TE);

        Map<String, double[]> results = new LinkedHashMap<>();
        results.put("RB-heavy RRRWWWT (Model A)", tournament.evaluate(FluxDraft.named(
                seed -> tournament.new SequencePolicy(rbHeavy)), trials));
        results.put("WR-heavy RWRWWWT (shipped)", tournament.evaluate(FluxDraft.named(
                seed -> tournament.new SequencePolicy(wrHeavy)), trials));
        results.put("live engine (lookahead-2-vorp)", tournament.evaluate(FluxDraft.named(
                seed -> tournament.new Lookahead(2, 16, PolicyTournament.Tail.VORP, seed)),
                Math.min(trials, 600)));

        double[] base = results.get("WR-heavy RWRWWWT (shipped)");
        System.out.printf("%n%-32s %8s %10s %8s %14s%n", "PLAN", "trials", "mean",
                "+/-SE", "vs shipped");
        results.forEach((label, scores) -> {
            int paired = Math.min(scores.length, base.length);
            double delta = 0;
            for(int r = 0; r < paired; r++){
                delta += scores[r] - base[r];
            }
            System.out.printf("%-32s %8d %10.1f %8.1f %+14.1f%n", label, scores.length,
                    PolicyTournament.mean(scores),
                    PolicyTournament.standardError(scores), delta / paired);
        });
        System.out.println("\nThe live engine follows neither sequence - it decides each"
                + "\npick from the board. These two are the fallback if the tool dies.");
    }
}
