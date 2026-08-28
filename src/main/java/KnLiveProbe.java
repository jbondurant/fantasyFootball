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
        for(double delta : new double[]{1.0, 3.0}){
            System.out.printf("%n--- indifference zone delta = %.0f point%s ---%n",
                    delta, delta == 1 ? "" : "s");
            PolicyTournament.RankingSelection policy =
                    tournament.new RankingSelection(delta, 0.05, 8, 64,
                            DraftSimulator.SEED);
            long[] timing = {0};
            tournament.simulateWithProbe(policy, timing);
        }
    }
}
