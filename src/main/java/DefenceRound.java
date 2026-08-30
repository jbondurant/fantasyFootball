import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where should the defence pick actually go?
 *
 * Every model consulted so far has given an opinion rather than an answer. The
 * starter-sum model declines to draft one at all; the RUNBOOK says last; the
 * folk rule says last two rounds. None of that has been tested.
 *
 * So force it. Take one plan, hold its thirteen other picks fixed, and slide
 * the defence through every slot from the first pick to the last - then score
 * all fourteen variants on what really happened, five seasons, the league's
 * real ten-man lineup. Only the position of one pick differs between rows, so
 * the difference between them IS the cost of drafting a defence that early.
 *
 *   ./gradlew run -Pmain=DefenceRound
 */
public class DefenceRound {

    /** The committed plan with its defence removed - thirteen fixed picks. */
    static final String[] WITHOUT_DEFENCE =
            "RB RB RB WR WR WR WR TE WR QB TE QB RB".split("\\s+");

    public static void main(String[] args) throws Exception {
        List<PlanBacktest.Board> boards = new ArrayList<>();
        for(File file : new File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                PlanBacktest.Board board = PlanBacktest.board(file,
                        file.getName().split("-")[3]);
                if(board != null && board.ids().size() > 150){
                    boards.add(board);
                }
            }
        }
        boards.sort(Comparator.comparing(PlanBacktest.Board::season));

        System.out.printf("%nWHERE SHOULD THE DEFENCE PICK GO?%n");
        System.out.printf("one plan, thirteen picks held fixed, the defence slid through"
                + " every slot%nscored on real outcomes, %d seasons%n%n", boards.size());
        System.out.printf("%-6s %-8s", "AT", "ROUND");
        for(PlanBacktest.Board board : boards){
            System.out.printf(" %8s", board.season());
        }
        System.out.printf(" %9s%n", "mean");

        Map<Integer, Double> means = new LinkedHashMap<>();
        for(int at = 0; at <= WITHOUT_DEFENCE.length; at++){
            List<String> sequence = new ArrayList<>(List.of(WITHOUT_DEFENCE));
            sequence.add(at, "DEF");
            String plan = String.join(" ", sequence);
            double total = 0;
            System.out.printf("%-6d %-8d", at + 1, roundOf(at + 1));
            for(PlanBacktest.Board board : boards){
                double scored = PlanBacktest.score(board, plan);
                total += scored;
                System.out.printf(" %8.0f", scored);
            }
            double mean = total / boards.size();
            means.put(at + 1, mean);
            System.out.printf(" %9.0f%n", mean);
        }

        int best = means.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
        int worst = means.entrySet().stream()
                .min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
        System.out.printf("%nbest at pick %d of 14 (round %d), worst at pick %d"
                + " (round %d).%nthe spread across all fourteen placements is %.0f"
                + " points a season.%n", best, roundOf(best), worst, roundOf(worst),
                means.get(best) - means.get(worst));
        System.out.println("\nThe spread is what the decision is worth. If it is small,"
                + " then where the\ndefence goes barely matters and any rule that puts"
                + " it late is good enough.");
    }

    /** My picks are 7, 18, 31 ... so the nth pick falls in this round. */
    static int roundOf(int nth){
        return nth <= 11 ? nth : nth + 2;   // rounds 12 and 13 are keeper slots
    }
}
