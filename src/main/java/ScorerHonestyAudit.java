import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Does PlanBacktest.seasonPoints fill its own lineup by EXPECTATION or by
 * HINDSIGHT?
 *
 * WHY THIS EXISTS. seasonPoints is the scorer every model comparison in this
 * repo rests on - the strategy table, the shape sweeps, the six-model ranking,
 * PowerBacktest. DRAFT-READY.md item 4 said nobody had ever audited it, and the
 * suspicion was specific: if the SCORER reads the box score before setting its
 * lineup, then it pays for something no manager can do, rosters built to
 * exploit hindsight are rewarded, and an honest model is marked down for
 * refusing to cheat. Every number on record would then be measured against the
 * wrong thing.
 *
 * The comment on seasonPoints says preseason rank. This repo has been bitten
 * three times by comments describing a mechanism the code does not implement
 * (TRAPS.md F27), once by a comment that specifically denied the hindsight
 * sitting twelve lines below it, so the comment is not evidence.
 *
 * THE ANSWER IS EXPECTATION, and this tool is the measurement rather than the
 * assertion. It scores the identical roster twice - once with the shipped fill
 * and once with a fill that sorts each week on what the players actually
 * scored - and prints the gap. Read it as follows:
 *
 *   premium ~ 0     the scorer is ALREADY filling by hindsight. It cheats.
 *   premium > 0     the scorer left that many points on the table by being
 *                   honest, which it could only do if it were honest.
 *
 * The premium is not a defect and it is not to be closed. It is the price of
 * the scorer's honesty, and printing it is the only way the honesty stays
 * checkable when the next person reads the comment and wonders.
 *
 * THE BAR. Five seasons, one draw each, so the season is the unit of
 * independent randomness (TRAPS.md D15) and there are five of them. The
 * clustered 95% bar comes from PowerBacktest.paired rather than from anything
 * typed here, which is also what stops this tool reporting a sub-bar gap as a
 * finding (D16).
 *
 *   ./gradlew run -Pmain=ScorerHonestyAudit -q
 *   ./gradlew run -Pmain=ScorerHonestyAudit -PholdKeepers=true -q
 */
public class ScorerHonestyAudit {

    public static void main(String[] args) throws Exception {
        List<PlanBacktest.Board> boards = new ArrayList<>();
        for(File file : new File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                PlanBacktest.Board board =
                        PlanBacktest.board(file, file.getName().split("-")[3]);
                if(board != null && board.ids().size() > 150){
                    boards.add(board);
                }
            }
        }
        boards.sort(Comparator.comparing(PlanBacktest.Board::season));
        if(boards.isEmpty()){
            System.out.println("no seasons to audit");
            return;
        }

        System.out.printf("%nIS THE SCORER HONEST?%n%n");
        System.out.printf("Each roster scored twice on the same %d seasons: the shipped fill,%n"
                + "which sorts each week's candidates by PRESEASON ADP rank, and a fill that%n"
                + "sorts them by what they REALLY scored that week. Everything else - who is%n"
                + "available, the dropped man, the streamed defence - is identical.%n%n"
                + "A premium of zero would mean the shipped fill is already the hindsight one.%n%n",
                boards.size());

        System.out.printf("%-26s %8s %8s %9s   %s%n",
                "STRATEGY", "SHIPPED", "HINDSIGHT", "PREMIUM", "per season");

        List<double[]> everyPremium = new ArrayList<>();
        for(Map.Entry<String, String> entry : PlanBacktest.STRATEGIES.entrySet()){
            double[] honest = new double[boards.size()];
            double[] cheating = new double[boards.size()];
            double[] premium = new double[boards.size()];
            for(int i = 0; i < boards.size(); i++){
                PlanBacktest.Board board = boards.get(i);
                // ONE roster, scored two ways. Drafting once is the whole point:
                // if the rosters differed, the gap would be a draft difference
                // wearing a scoring difference's clothes.
                List<String> roster = PlanBacktest.draft(board, entry.getValue());
                honest[i] = PlanBacktest.seasonPoints(board, roster, false);
                cheating[i] = PlanBacktest.seasonPoints(board, roster, true);
                premium[i] = cheating[i] - honest[i];
            }
            everyPremium.add(premium);
            StringBuilder detail = new StringBuilder();
            for(int i = 0; i < boards.size(); i++){
                detail.append(detail.isEmpty() ? "" : " ")
                        .append(String.format("%s %+.0f", boards.get(i).season(), premium[i]));
            }
            System.out.printf("%-26s %8.0f %8.0f %+9.0f   %s%n", entry.getKey(),
                    mean(honest), mean(cheating), mean(premium), detail);
        }

        // The premium pooled over every strategy on every season, clustered on
        // season because that is the only thing here that is independent.
        int strategies = everyPremium.size();
        int n = strategies * boards.size();
        double[] flat = new double[n];
        int[] clusterOf = new int[n];
        int k = 0;
        for(double[] premium : everyPremium){
            for(int season = 0; season < boards.size(); season++){
                flat[k] = premium[season];
                clusterOf[k] = season;
                k++;
            }
        }
        PowerBacktest.Paired pooled = PowerBacktest.paired(
                "hindsight premium", flat, flat, clusterOf, boards.size());

        System.out.printf("%npooled over %d strategies x %d seasons: the hindsight fill is worth"
                + " %+.0f points%n", strategies, boards.size(), pooled.diff());
        System.out.printf("clustered on season, %d clusters, 95%% bar %.0f - %s%n",
                pooled.clusters(), pooled.bar(),
                pooled.real() ? "the gap is REAL" : "inside the bar, so this is a tie");

        System.out.printf("%n%s%n", "=".repeat(72));
        if(pooled.real() && pooled.diff() > 0){
            System.out.printf("VERDICT: the scorer fills by EXPECTATION.%n%n"
                    + "Filling by hindsight instead is worth %+.0f points a season, and the%n"
                    + "shipped scorer does not collect it. It cannot be doing the thing it%n"
                    + "would be paid %.0f points to do. Every comparison in this repo is%n"
                    + "measured against a lineup a manager could actually have set.%n",
                    pooled.diff(), pooled.diff());
        }
        else {
            System.out.printf("VERDICT: THE SCORER CHEATS, or the audit is broken.%n%n"
                    + "The hindsight fill scores %+.0f against the shipped one, which is%n"
                    + "inside the bar. A scorer choosing on preseason rank should be beaten%n"
                    + "clearly by one choosing on the result. Check this before trusting a%n"
                    + "single ranking in this repo.%n", pooled.diff());
        }
        System.out.printf("%nTWO THINGS THIS DOES NOT CLEAR, both already catalogued:%n"
                + "  - availability. A man with no line that week cannot start. That is a%n"
                + "    real manager's knowledge (inactives, byes) and is applied to every%n"
                + "    strategy identically, but it is information about the week.%n"
                + "  - the streamed defence rate, TRAPS.md C13, which IS hindsight and is%n"
                + "    charged only to a roster holding no defence. -PwireDef overrides it.%n");
    }

    static double mean(double[] values){
        double total = 0;
        for(double value : values){
            total += value;
        }
        return values.length == 0 ? 0 : total / values.length;
    }
}
