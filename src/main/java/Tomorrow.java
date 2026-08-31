import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * One screen for draft night. Everything that earned its place, nothing else.
 *
 * Six models were built over two days and the honest ranking never changed:
 *
 *     the committed plan       2050
 *     board value, fixed       1974   inside the bar, a tie
 *     adaptive, unconstrained  1924
 *     adaptive, constrained    1916
 *     matrix rule              1893
 *     best available by ADP    1426
 *
 * Adapting lost every time it was measured, and constraining the adaptive arm
 * did not rescue it - which is the finding, not a disappointment. So this does
 * not replace the plan with a model. It puts the plan's prescription, the
 * model's opinion, and the measured cost of waiting on one screen, and says
 * plainly which of the three has evidence behind it.
 *
 * WHAT EACH LINE IS WORTH:
 *   THE PLAN     a fixed shape, top of the table on real outcomes. Its lead
 *                over the best challenger is 76 points against a 125-point
 *                bar, so it is a tie and not a proof - but nothing has beaten
 *                it in two days of trying.
 *   THE MODEL    LiveBoard: this board, this roster, traps unreachable. Not
 *                backtested. Treat it as a second opinion, and take its
 *                REFUSALS seriously even when its rankings are only advisory -
 *                a refusal is arithmetic, a ranking is an estimate.
 *   WAITING      PairwiseOdds on sixteen seasons and 65,855 pairs, held out by
 *                season, 68-69% accurate. This is the best-evidenced number in
 *                the repo and it answers the question actually faced at a pick.
 *
 *   ./gradlew run -Pmain=Tomorrow -Pkeepers=Tuten,Purdy -q
 */
public class Tomorrow {

    /** RUNBOOK.md's round table, with its conditionals left as conditionals. */
    static final Map<Integer, String> PLAN = new LinkedHashMap<>(Map.ofEntries(
            Map.entry(1, "RB"), Map.entry(2, "RB"), Map.entry(3, "RB"),
            Map.entry(4, "WR"), Map.entry(5, "WR"), Map.entry(6, "WR"),
            Map.entry(7, "RB or WR - NOT TE"),
            Map.entry(8, "TE"),
            Map.entry(9, "RB or WR, highest upside - NOT a backup QB"),
            Map.entry(10, "young QB stash if he is there - else RB/WR"),
            Map.entry(11, "last call before the 35-pick gap - anything you want"),
            Map.entry(14, "RB/WR, or the QB stash if you skipped it at 10"),
            Map.entry(15, "RB/WR"),
            Map.entry(16, "DEFENCE")));

    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", System.getProperty("scheduleRounds", "16"));
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String draftID = System.getProperty("draftId", configuration.getDraftID());
        List<Keeper> myKeepers = DraftPlanner.keepersFromProperty(configuration);
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, myKeepers,
                choice, earliness);
        DraftSimulator simulator = planner.simulator();

        List<String> taken = LiveDraft.livePicks(draftID);
        DraftSimulator.SimState state = simulator.stateAfter(taken);
        DraftSimulator.Slot on = simulator.slotOf(state);
        int at = on == null ? 200 : on.pickNumber();
        int pick = at;
        for(int p = at; p <= 200; p++){
            DraftSimulator.Slot mine = simulator.slotAt(p);
            if(mine != null && planner.me().equals(mine.manager())){
                pick = p;
                break;
            }
        }
        DraftSimulator.Slot slot = simulator.slotAt(pick);
        int round = slot == null ? 16 : slot.round();
        int next = -1;
        for(int p = pick + 1; p <= 200; p++){
            DraftSimulator.Slot mine = simulator.slotAt(p);
            if(mine != null && planner.me().equals(mine.manager())){
                next = p;
                break;
            }
        }

        System.out.printf("%n========================================================%n");
        System.out.printf("  MY PICK %d, ROUND %d%s%n", pick, round,
                at == pick ? "  (on the clock)" : "  (draft is on " + at + ")");
        System.out.printf("========================================================%n");

        System.out.printf("%n  THE PLAN says: %s%n", PLAN.getOrDefault(round, "-"));
        if(round >= 12 && round <= 13){
            System.out.printf("  (rounds 12 and 13 are Tuten and Purdy - no pick here)%n");
        }

        System.out.printf("%n  WAITING until pick %s costs you:%n",
                next < 0 ? "(none)" : String.valueOf(next));
        if(next > 0){
            Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
            List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
            List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
            int[] ties = new int[1];
            PairwiseOdds.Model odds = PairwiseOdds.latent(
                    PairwiseOdds.pairs(men, -1, false, ties), 0, 0.25);
            Map<Position, List<Double>> board = RankDraft.board(
                    new Position[]{Position.RB, Position.WR, Position.TE, Position.QB});
            for(Position position : new Position[]{Position.RB, Position.WR,
                    Position.TE, Position.QB}){
                int early = RankDraft.depth(board.get(position), pick);
                int late = RankDraft.depth(board.get(position), next);
                Integer cap = PairwiseOdds.CAP.get(position);
                if(early >= late || cap == null || late > cap){
                    System.out.printf("     %-4s -%n", position);
                    continue;
                }
                double p = odds.probability(position, early, late);
                System.out.printf("     %-4s the man at %d beats the man at %d only %.0f%%"
                        + " of the time%s%n", position, next, pick, 100 * p,
                        p < 0.42 ? "   <- expensive to wait" : "");
            }
        }

        System.out.printf("%n  THE MODEL (second opinion - not backtested):%n");
        System.out.printf("  run  ./gradlew run -Pmain=LiveBoard -Pkeepers=Tuten,Purdy -q%n");
        System.out.printf("  take its REFUSALS as binding and its rankings as advisory.%n");

        System.out.printf("%n  THE TWO RULES WITH EVIDENCE BEHIND THEM:%n");
        System.out.printf("     round 1 is RB or WR - 90%% of every plan that ties the"
                + " committed one%n");
        System.out.printf("     no defence before round 10 - and it is worth ~0 whenever"
                + " you take it,%n     so take it last%n");
        System.out.printf("%n  AND THE ONE TO IGNORE: you keep Purdy. You do not need a%n"
                + "  quarterback. LiveLateRounds has no cap and will price one anyway.%n%n");
    }
}
