import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The quantification behind BaselinePlanAudit's alarm: all twelve keeperless
 * baseline plans commit QB in rounds 1-3. If a QB-late commitment beats the
 * staged plan at high precision, V(base) is understated by that gap - and
 * every QB KEEPER's ledger delta (Purdy +10.4, Daniels -5.2, Watson +28.5,
 * Herbert +0.4) is inflated by it, because the kept-QB branch is immune (a
 * kept QB fills the slot and tames the tails) while its baseline is not.
 * Non-QB deltas are common-mode and largely cancel.
 *
 * Method: take MY keeperless baseline's staged plan, extract its QB, re-insert
 * it at each later round, and price every variant with the ledger's own
 * search/evaluate convention - evaluate() at -Ptrials rollouts on the
 * EVAL seed stream (SEED + 1,000,000), fresh relative to the search.
 *
 *   ./gradlew run -Pmain=BaselineQbTimingCheck [-Ptrials=10000] [-Psearch=150]
 */
public class BaselineQbTimingCheck {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 10000);
        int search = Integer.getInteger("search", 150);
        long evalSeed = DraftSimulator.SEED + 1_000_000L;

        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, lastCompleted);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, lastCompleted,
                earliness);

        String me = configuration.getMyID();
        Set<String> stripped = new HashSet<>();
        for(Keeper keeper : configuration.getTodaysKeepers()){
            if(me.equals(keeper.humanWhoCanKeep)){
                stripped.add(keeper.player.sleeperIDString);
            }
        }
        DraftPlanner planner = DraftPlanner.forCurrentSeasonAs(configuration, me,
                List.of(), stripped, model, earliness);
        List<Position> staged = planner.plan(search, 0, 0.10, DraftSimulator.SEED).positions();

        List<Position> skill = new ArrayList<>(staged);
        int stagedQbRound = skill.indexOf(Position.QB) + 1;
        skill.remove(Position.QB);

        System.out.printf("my keeperless baseline: staged plan %s (QB round %d), "
                        + "%d-rollout evaluation on fresh seeds:%n%n",
                staged, stagedQbRound, trials);
        System.out.printf("   %-6s %-24s %10s %12s%n", "QB rd", "PLAN", "mean",
                "vs staged");
        double stagedMean = planner.evaluate(staged, trials, evalSeed);
        for(int insert = 0; insert <= skill.size(); insert++){
            List<Position> variant = new ArrayList<>(skill);
            variant.add(insert, Position.QB);
            double mean = variant.equals(staged) ? stagedMean
                    : planner.evaluate(variant, trials, evalSeed);
            System.out.printf("   %-6d %-24s %10.1f %+12.1f%s%n",
                    insert + 1, variant, mean, mean - stagedMean,
                    variant.equals(staged) ? "   <- staged search chose this" : "");
        }
        System.out.println("\nA positive vs-staged number at a late QB round is the amount"
                + "\nthe ledger's V(base) is understated for this seat - and the amount"
                + "\nevery QB keeper's delta is inflated.");
    }
}
