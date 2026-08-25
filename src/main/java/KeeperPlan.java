import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The keeper decision, made by the model that plays the whole game: for every
 * player I am allowed to keep, V(keeper) - V(no keeper), where each V is the
 * expected best-nine of a full nine-round plan optimized by DraftPlanner
 * under that scenario. Both branches are optimized, so this is the value of
 * the keeper DECISION, not of the player - a keeper is only worth what he
 * beats the redrafted alternative by.
 *
 * An in-game keeper (cost round 1-9) spends that pick; an out-of-game keeper
 * spends nothing and benches my weakest pick, per the best-nine scoring rule.
 * Risk knobs match the planner: -Prisk / -Pquantile rank scenarios by
 * mean - lambda * (mean - p_q).
 *
 *     ./gradlew run -Pmain=KeeperPlan [-Ptrials=200] [-Prisk=0] [-Pquantile=0.10]
 */
public class KeeperPlan {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 200);
        double lambda = Double.parseDouble(System.getProperty("risk", "0"));
        double q = Double.parseDouble(System.getProperty("quantile", "0.10"));

        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, 2025);
        SelectionModel model = SelectionModel.fit(
                SelectionModel.loadObservations(configuration, 2021, 2025, earliness),
                SelectionModel.shippedFeatures());

        DraftPlanner noKeeper = DraftPlanner.forCurrentSeason(configuration, null,
                model, earliness);
        DraftPlanner.Plan base = noKeeper.plan(rollouts, lambda, q, DraftSimulator.SEED);
        System.out.printf("no keeper: best-nine %.1f (p%.0f %.1f), plan %s%n%n",
                base.mean(), q * 100, base.p10(), base.positions());

        Map<String, Double> points = SleeperProjections.parseTodaysWebPage();
        List<Keeper> mine = KeeperChooser.eligibleCandidates(configuration, configuration.getMyID());

        record Row(String name, Position position, int cost, DraftPlanner.Plan plan){}
        List<Row> rows = new ArrayList<>();
        for(Keeper keeper : mine){
            if(!StartingLineup.isSkillPosition(keeper.player.position)
                    || points.getOrDefault(keeper.player.sleeperIDString, 0.0) <= 0.0){
                continue;
            }
            DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, keeper,
                    model, earliness);
            DraftPlanner.Plan plan = planner.plan(rollouts, lambda, q, DraftSimulator.SEED);
            rows.add(new Row(keeper.player.firstName + " " + keeper.player.lastName,
                    keeper.player.position, keeper.roundCanBeKept, plan));
            System.out.printf("evaluated %s (round %d)%n",
                    keeper.player.lastName, keeper.roundCanBeKept);
        }

        rows.sort(Comparator.comparingDouble((Row row) -> row.plan().riskAdjusted()).reversed());
        System.out.printf("%nkeeper decision, %d rollouts per branch, lambda %.2f:%n%n", rollouts, lambda);
        System.out.printf("   %-24s %-4s %5s %10s %10s %10s   %s%n",
                "KEEPER", "POS", "COST", "best-nine", "p" + Math.round(q * 100), "vs none", "plan");
        for(Row row : rows){
            System.out.printf("   %-24s %-4s   r%-3d %10.1f %10.1f %+10.1f   %s%n",
                    row.name(), row.position(), row.cost(), row.plan().mean(), row.plan().p10(),
                    row.plan().riskAdjusted() - base.riskAdjusted(), row.plan().positions());
        }
        System.out.printf("   %-24s %-4s %5s %10.1f %10.1f %10s   %s%n",
                "(no keeper)", "-", "-", base.mean(), base.p10(), "-", base.positions());
        System.out.printf("%n   Monte Carlo noise about +/- %.1f on each mean - gaps inside"
                        + "%n   roughly twice that are ties; raise -Ptrials to separate them.%n",
                base.standardError());
    }

}
