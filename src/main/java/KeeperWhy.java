import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Explains a keeper's value instead of just asserting it: the same two
 * optimized branches KeeperPlan compares - keep him versus don't - decomposed
 * by lineup slot group, plus who actually ends up as my lineup quarterback in
 * each branch. This answers "why is a QB keeper worth anything when the
 * league drafts QBs late and Allen is reachable": the delta shows whether the
 * value comes from the QB slot itself (it should not, if Allen is reachable
 * either way) or from the extra skill pick the keeper frees.
 *
 *     ./gradlew run -Pmain=KeeperWhy -Pwhy=Purdy [-Ptrials=300]
 */
public class KeeperWhy {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 300);
        double lambda = Double.parseDouble(System.getProperty("risk", "0"));
        double q = Double.parseDouble(System.getProperty("quantile", "0.10"));
        String who = System.getProperty("why", "");

        List<Keeper> candidates =
                KeeperChooser.eligibleCandidates(configuration, configuration.getMyID());
        Keeper keeper = null;
        for(Keeper candidate : candidates){
            String full = candidate.player.firstName + " " + candidate.player.lastName;
            if(!who.isEmpty() && (candidate.player.lastName.equalsIgnoreCase(who)
                    || full.equalsIgnoreCase(who))){
                keeper = candidate;
            }
        }
        if(keeper == null){
            System.out.println("pass -Pwhy=<last name>; eligible:");
            for(Keeper candidate : candidates){
                System.out.printf("   %-24s %-4s r%d%n",
                        candidate.player.firstName + " " + candidate.player.lastName,
                        candidate.player.position, candidate.roundCanBeKept);
            }
            return;
        }

        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, 2025);
        SelectionModel model = SelectionModel.fitShipped(configuration, 2025, earliness);

        DraftPlanner without = DraftPlanner.forCurrentSeason(configuration, null,
                model, earliness);
        DraftPlanner with = DraftPlanner.forCurrentSeason(configuration, keeper,
                model, earliness);
        DraftPlanner.Plan planWithout = without.plan(rollouts, lambda, q, DraftSimulator.SEED);
        DraftPlanner.Plan planWith = with.plan(rollouts, lambda, q, DraftSimulator.SEED);
        DraftPlanner.Profile profileWithout =
                without.profile(planWithout.positions(), rollouts, DraftSimulator.SEED);
        DraftPlanner.Profile profileWith =
                with.profile(planWith.positions(), rollouts, DraftSimulator.SEED);

        String name = keeper.player.firstName + " " + keeper.player.lastName;
        System.out.printf("keeping %s (r%d) versus not, %d rollouts each, both branches optimized:%n%n",
                name, keeper.roundCanBeKept, rollouts);
        System.out.printf("   %-12s %12s %12s %10s%n", "SLOTS", "no keeper", "keep", "delta");
        row("QB", profileWithout.slots().qb(), profileWith.slots().qb());
        row("RB x2", profileWithout.slots().rb(), profileWith.slots().rb());
        row("WR x3", profileWithout.slots().wr(), profileWith.slots().wr());
        row("TE", profileWithout.slots().te(), profileWith.slots().te());
        row("FLEX x2", profileWithout.slots().flex(), profileWith.slots().flex());
        row("total", profileWithout.slots().total(), profileWith.slots().total());
        System.out.printf("%n   plans: %s -> %s%n", planWithout.positions(), planWith.positions());
        System.out.printf("   (Monte Carlo +/- %.1f on each total)%n", planWithout.standardError());

        System.out.println("\nmy lineup QB across rollouts:\n");
        System.out.printf("   %-14s %-40s%n", "no keeper:", qbShares(profileWithout));
        System.out.printf("   %-14s %-40s%n", "keep " + keeper.player.lastName + ":",
                qbShares(profileWith));
    }

    private static void row(String label, double without, double with){
        System.out.printf("   %-12s %12.1f %12.1f %+10.1f%n", label, without, with, with - without);
    }

    private static String qbShares(DraftPlanner.Profile profile){
        List<Map.Entry<String, Integer>> shares =
                new ArrayList<>(profile.lineupQBs().entrySet());
        shares.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        StringBuilder text = new StringBuilder();
        for(int i = 0; i < shares.size() && i < 4; i++){
            if(i > 0){
                text.append(", ");
            }
            text.append(String.format("%s %.0f%%", shares.get(i).getKey(),
                    100.0 * shares.get(i).getValue() / profile.rollouts()));
        }
        return text.length() == 0 ? "(no QB drafted)" : text.toString();
    }

}
