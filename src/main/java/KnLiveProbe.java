import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The decision-relevant question for wiring Kim-Nelson into the live
 * committee is not tournament throughput - it is per-pick behaviour: how
 * many rollouts does it actually spend, does it PROVE a selection, and does
 * it finish inside the draft clock? Measured at every one of my picks along
 * a realistic draft.
 *
 *   ./gradlew run -Pmain=KnLiveProbe
 */
public class KnLiveProbe {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        PolicyTournament tournament = PolicyTournament.forCurrentGame(configuration, 300);

        System.out.printf("%-6s %-16s %10s %10s %12s%n", "pick", "KN picks",
                "rollouts", "proven?", "seconds");
        int budget = Integer.getInteger("budget", 64);
        String deltaList = System.getProperty("deltas", "1,3");
        for(String text : deltaList.split(",")){
            double delta = Double.parseDouble(text.trim());
            System.out.printf("%n--- delta = %.1f points, budget = %d rollouts ---%n",
                    delta, budget);
            PolicyTournament.RankingSelection policy =
                    tournament.new RankingSelection(delta, 0.05, 8, budget,
                            DraftSimulator.SEED);
            long[] timing = {0};
            tournament.simulateWithProbe(policy, timing);
        }
    }
}
