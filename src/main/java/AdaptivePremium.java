import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Overnight phase 2: the adaptive premium, measured in the FULL-RULES game.
 * The lab said re-deciding from the live board is worth +6..+10 over any
 * committed plan; this prices it where it pays - my actual seat, real rules -
 * by racing four policies on the same fresh seed stream, paired:
 *
 *   shipped   the locked committed plan
 *   timing    best (QB round, TE round) head, RB/WR live      [phase 1's win]
 *   vorp      fully reactive four-position VORP, no lookahead
 *   adaptive  depth-2 lookahead over the live rollout state, VORP-completed
 *             inner rollouts (the draft-night engine, priced honestly)
 *
 *   ./gradlew run -Pmain=AdaptivePremium [-Ptrials=10000]
 *                 [-PadaptiveTrials=300] [-Psearch=300] [-Pinner=16]
 */
public class AdaptivePremium {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 10000);
        int adaptiveTrials = Integer.getInteger("adaptiveTrials", 300);
        int search = Integer.getInteger("search", 300);
        int inner = Integer.getInteger("inner", 16);

        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, lastCompleted);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, lastCompleted,
                earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        TimingPlanner timing = new TimingPlanner(planner);
        timing.fillWaitingTable(Math.max(search, 200));
        int picks = planner.simulator().pickNumbersOf(planner.me()).length;

        // Re-find the best timing head on the search seeds (numbers from code).
        List<int[]> heads = new ArrayList<>();
        for(int qbAt = -1; qbAt < picks; qbAt++){
            for(int teAt = 0; teAt < picks; teAt++){
                if(teAt != qbAt){
                    heads.add(new int[]{qbAt, teAt});
                }
            }
        }
        double[] headMeans = IntStream.range(0, heads.size()).parallel().mapToDouble(h -> {
            double total = 0;
            for(int r = 0; r < search; r++){
                TimingPlanner.TimingPolicy policy = timing.new TimingPolicy(
                        heads.get(h)[0], heads.get(h)[1]);
                planner.simulator().simulateOnce(
                        new Random(TimingPlanner.SEARCH_SEED + 7919L * r),
                        planner.me(), policy);
                total += StartingLineup.bestNine(policy.mine, planner.points());
            }
            return total / search;
        }).toArray();
        int argmax = 0;
        for(int h = 1; h < headMeans.length; h++){
            if(headMeans[h] > headMeans[argmax]){
                argmax = h;
            }
        }
        int[] bestHead = heads.get(argmax);
        System.out.printf("best timing head QB@%s TE@r%d (search mean %.1f)%n",
                bestHead[0] < 0 ? "none" : "r" + (bestHead[0] + 1), bestHead[1] + 1,
                headMeans[argmax]);

        List<Position> shipped = List.of(Position.RB, Position.WR, Position.RB,
                Position.WR, Position.WR, Position.WR, Position.TE, Position.QB,
                Position.RB);

        double[] shippedScores = timing.evaluate(
                r -> timing.new CommittedPolicy(shipped), trials, TimingPlanner.EVAL_SEED);
        double[] timingScores = timing.evaluate(
                r -> timing.new TimingPolicy(bestHead[0], bestHead[1]), trials,
                TimingPlanner.EVAL_SEED);
        double[] vorpScores = timing.evaluate(
                r -> timing.new VorpPolicy(), trials, TimingPlanner.EVAL_SEED);
        long adaptiveStart = System.currentTimeMillis();
        double[] adaptiveScores = timing.evaluate(
                r -> timing.new AdaptivePolicy(inner,
                        TimingPlanner.EVAL_SEED + 13_000_000L + 7919L * r),
                adaptiveTrials, TimingPlanner.EVAL_SEED);
        System.out.printf("adaptive evaluated in %.0fs%n",
                (System.currentTimeMillis() - adaptiveStart) / 1000.0);

        System.out.printf("%n%-40s %8s %10s %8s %14s%n", "POLICY", "trials", "mean",
                "+/-SE", "vs shipped");
        Object[][] rows = {
                {"adaptive d2 (inner " + inner + ", vorp tails)", adaptiveScores},
                {"timing QB@" + (bestHead[0] < 0 ? "none" : "r" + (bestHead[0] + 1))
                        + " TE@r" + (bestHead[1] + 1), timingScores},
                {"vorp reactive", vorpScores},
                {"shipped " + shipped, shippedScores}};
        for(Object[] row : rows){
            double[] scores = (double[]) row[1];
            int paired = Math.min(scores.length, shippedScores.length);
            double delta = 0;
            for(int r = 0; r < paired; r++){
                delta += scores[r] - shippedScores[r];
            }
            System.out.printf("%-40s %8d %10.1f %8.1f %+14.1f%n", row[0], scores.length,
                    TimingPlanner.mean(scores), TimingPlanner.standardError(scores),
                    delta / paired);
        }
        System.out.println("\nAll rows share the eval seed stream (vs-shipped is paired"
                + "\nover the common prefix); search seeds disjoint.");
    }
}
