import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * How many quarterbacks should a man who keeps one actually draft?
 *
 * Justin, on being told the round-3-quarterback finding did not apply to him:
 * "Why ignore qb timing limit if we aren't modelling keepers properly where my
 * keeper includes a qb? I expect a plan where i draft 0 additional qb to score
 * quite well."
 *
 * He is right that dismissing the finding was the wrong move. The searches that
 * produced it draft fourteen picks and hold no keepers, so every shape in them
 * must buy its own starting quarterback and a nought-quarterback shape is not
 * merely unpopular - it is illegal, and never gets proposed. He keeps Purdy, so
 * for him it is legal, and nobody had scored it.
 *
 * EraPlans.floor() already subtracts the keepers from what the lineup needs, so
 * these plans exist in the enumeration. This groups them by how many
 * quarterbacks they draft and reports what each group is worth, on thirteen
 * seasons rather than five.
 *
 *   ./gradlew run -Pmain=QbCountTest [-PplanSample=20000] [-PnoKeepers]
 */
public class QbCountTest {

    public static void main(String[] args){
        int rounds = EraIngest.rounds();
        Map<String, EraBoards.Board> boards = EraBoards.usable(
                System.getProperty("format"), EraIngest.MIN_RATE, EraIngest.minDepth());
        EraScores.Table table = EraScores.compute(boards, rounds,
                Integer.getInteger("planSample", 20000));
        List<Integer> all = new ArrayList<>();
        for(int i = 0; i < table.seasons().size(); i++){
            all.add(i);
        }
        System.out.printf("%nHOW MANY QUARTERBACKS TO DRAFT WHEN YOU KEEP ONE%n%n");
        System.out.printf("%d seasons, %d rounds, %d plans, %s%n%n",
                table.seasons().size(), rounds, table.plans().size(),
                Boolean.getBoolean("noKeepers") ? "NO KEEPERS" : EraKeepers.describe());

        Map<Integer, List<Integer>> byCount = new TreeMap<>();
        for(int p = 0; p < table.plans().size(); p++){
            int qbs = 0;
            for(Position position : table.plans().get(p)){
                if(position == Position.QB){ qbs++; }
            }
            byCount.computeIfAbsent(qbs, k -> new ArrayList<>()).add(p);
        }

        System.out.printf("%-6s %8s %9s %9s %9s %9s%n",
                "QBs", "PLANS", "MEAN", "BEST", "WORST SEA", "TOP 1%");
        Map<Integer, Double> groupMean = new TreeMap<>();
        Map<Integer, Integer> groupBest = new TreeMap<>();
        for(Map.Entry<Integer, List<Integer>> entry : byCount.entrySet()){
            List<Integer> plans = entry.getValue();
            double sum = 0;
            double best = -1e9;
            int bestPlan = plans.get(0);
            List<Double> means = new ArrayList<>();
            for(int p : plans){
                double mean = table.mean(p, all);
                means.add(mean);
                sum += mean;
                if(mean > best){ best = mean; bestPlan = p; }
            }
            means.sort(Comparator.reverseOrder());
            double top1 = means.get(Math.max(0, means.size() / 100 - 1));
            double worstSeason = 1e9;
            for(int i = 0; i < table.seasons().size(); i++){
                worstSeason = Math.min(worstSeason, table.value()[bestPlan][i]);
            }
            groupMean.put(entry.getKey(), sum / plans.size());
            groupBest.put(entry.getKey(), bestPlan);
            System.out.printf("%-6d %8d %9.0f %9.0f %9.0f %9.0f%n",
                    entry.getKey(), plans.size(), sum / plans.size(), best,
                    worstSeason, top1);
        }

        System.out.printf("%nTHE BEST PLAN AT EACH QUARTERBACK COUNT%n");
        for(Map.Entry<Integer, Integer> entry : groupBest.entrySet()){
            System.out.printf("   %d QB  %7.0f  %s%n", entry.getKey(),
                    table.mean(entry.getValue(), all),
                    EraPlans.shape(table.plans().get(entry.getValue())));
        }

        // The bar. A difference smaller than this is not a difference.
        System.out.printf("%n%s%n", "-".repeat(72));
        double bar = bar(table, all);
        System.out.printf("the 95%% bar on %d seasons is %.0f points.%n",
                table.seasons().size(), bar);
        Double zero = groupMean.get(0);
        Double one = groupMean.get(1);
        if(zero != null && one != null){
            double gap = zero - one;
            System.out.printf("drafting NO extra quarterback is worth %+.0f a season"
                    + " against drafting one:%n   %s%n", gap,
                    Math.abs(gap) < bar
                            ? "inside the bar - the two are the same decision"
                            : gap > 0 ? "past the bar - keep the pick"
                                      : "past the bar - draft the second man");
        }
    }

    /** Paired spread between random plans, which is what sets the bar. */
    static double bar(EraScores.Table table, List<Integer> all){
        Random random = new Random(20260830);
        List<Double> spreads = new ArrayList<>();
        for(int trial = 0; trial < 4000; trial++){
            int a = random.nextInt(table.plans().size());
            int b = random.nextInt(table.plans().size());
            double sum = 0;
            double sumSquares = 0;
            for(int i : all){
                double difference = table.value()[a][i] - table.value()[b][i];
                sum += difference;
                sumSquares += difference * difference;
            }
            int n = all.size();
            double mean = sum / n;
            double variance = Math.max(0, sumSquares / n - mean * mean);
            spreads.add(Math.sqrt(variance));
        }
        spreads.sort(Comparator.naturalOrder());
        double median = spreads.get(spreads.size() / 2);
        return 2.18 * median / Math.sqrt(all.size());   // t(0.975, 12 df)
    }
}
