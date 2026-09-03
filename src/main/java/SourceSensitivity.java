import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The plan rests on ONE projection feed (Rotowire via Sleeper). The keeper
 * decision was stress-tested across three independent shops back in August;
 * the PLAN never was. If the best sequence flips when the numbers come from
 * ESPN or CBS instead, that is a fragility worth knowing before Tuesday - and
 * if it holds, that is real reassurance, since the three shops disagree by
 * 40-65 points on elite players.
 *
 * For each source: find the best committed sequence under THAT source's
 * numbers, then score every candidate under it.
 *
 *   ./gradlew run -Pmain=SourceSensitivity [-Ptrials=800]
 */
public class SourceSensitivity {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 800);
        String[] sources = System.getProperty("sources",
                "sleeper;espn;cbs;blend:sleeper,espn,cbs").split(";");

        System.out.printf("%-14s %-26s %10s   %s%n", "SOURCE", "its best sequence",
                "value", "how the OTHER sources' plans score under it");
        Map<String, List<Position>> plans = new LinkedHashMap<>();
        Map<String, PolicyTournament> worlds = new LinkedHashMap<>();

        for(String source : sources){
            System.setProperty("projections", source);
            PolicyTournament tournament = PolicyTournament.forCurrentGame(configuration,
                    250);
            PolicyTournament.Needs start =
                    PolicyTournament.Needs.afterKeepers(tournament.myKeeperIDs());
            List<List<Position>> sequences = PolicyTournament.allSequences(start,
                    tournament.myPickCount());
            List<Position> best = null;
            double bestValue = -Double.MAX_VALUE;
            for(List<Position> sequence : sequences){
                double value = tournament.searchMean(sequence, 60);
                if(value > bestValue){
                    bestValue = value;
                    best = sequence;
                }
            }
            plans.put(source, best);
            worlds.put(source, tournament);
            System.out.printf("%-14s %-26s %10.1f%n", source.split(":")[0],
                    label(best), bestValue);
        }

        System.out.printf("%n%-14s", "under ->");
        for(String source : plans.keySet()){
            System.out.printf(" %12s", source.split(":")[0]);
        }
        System.out.println("     (rows = plan, cols = the numbers scoring it)");
        for(Map.Entry<String, List<Position>> plan : plans.entrySet()){
            System.out.printf("%-14s", label(plan.getValue()));
            for(Map.Entry<String, PolicyTournament> world : worlds.entrySet()){
                System.setProperty("projections", world.getKey());
                double value = PolicyTournament.mean(world.getValue().evaluate(
                        FluxDraft.named(seed -> world.getValue().new SequencePolicy(
                                plan.getValue())), trials));
                System.out.printf(" %12.1f", value);
            }
            System.out.println();
        }
        System.out.println("\nIf every row picks the same shape, the plan is source-proof:"
                + "\nthe three shops disagree by 40-65 points on elite players, so"
                + "\nagreement here means the SEQUENCE does not depend on whose numbers"
                + "\nI trust.");
    }

    static String label(List<Position> sequence){
        StringBuilder text = new StringBuilder();
        for(Position position : sequence){
            text.append(position.name().charAt(0));
        }
        return text.toString();
    }
}
