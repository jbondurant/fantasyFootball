import PlayerImportAndSetup.Position;
import java.io.File;
import java.util.*;

/**
 * What each rule takes, round by round, across all sixteen.
 *
 * The plan is a fixed shape, so its answer is the same every time and the
 * question is trivial for it. The lookahead model drafts against whatever board
 * it meets, so its answer is a DISTRIBUTION, and the modal position is only as
 * meaningful as the spread around it - a round the model splits three ways is
 * not a recommendation, it is the model saying the round is free.
 *
 * Rounds 12 and 13 are Tuten and Purdy. They are not picks and neither rule
 * chooses them; they are printed because a sixteen-round list that hides them
 * is the arithmetic error this repo spent two days finding.
 *
 * The adaptive arm drafts against the five real boards on disk, so a mode here
 * is over FIVE observations. That is thin, and the agreement column says so.
 *
 *   ./gradlew run -Pmain=RoundTable -PholdKeepers=true -q
 */
public class RoundTable {

    /** The committed plan, in the fourteen rounds Justin actually picks. */
    static final String PLAN = "RB RB RB WR WR WR WR TE WR QB TE QB RB DEF";

    public static void main(String[] args) throws Exception {
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Map<Position, double[]> curve = RankDraft.pointsByRank(men);
        Map<Position, List<List<Double>>> pools = BoardValue.pools(men);

        List<PlanBacktest.Board> boards = new ArrayList<>();
        for(File file : new File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                boards.add(PlanBacktest.board(file, file.getName().split("-")[3]));
            }
        }
        boards.sort(Comparator.comparing(PlanBacktest.Board::season));

        // pick index -> counts, from the model drafting each real board
        List<Map<Position, Integer>> tally = new ArrayList<>();
        for(int i = 0; i < 14; i++){
            tally.add(new EnumMap<>(Position.class));
        }
        for(PlanBacktest.Board board : boards){
            List<String> roster = BoardValue.adaptiveDraft(board, curve, pools, order.size());
            int keepers = PlanBacktest.holdKeepers()
                    ? PlanBacktest.keeperIDs(board).size() : 0;
            int index = 0;
            for(int i = keepers; i < roster.size() && index < 14; i++, index++){
                Position position = board.positionOf().get(roster.get(i));
                if(position != null){
                    tally.get(index).merge(position, 1, Integer::sum);
                }
            }
        }

        String[] plan = PLAN.split("\\s+");
        System.out.printf("%nWHAT EACH RULE TAKES, ROUND BY ROUND%n%n");
        System.out.printf("%-7s %-10s %-10s %-9s   %s%n",
                "ROUND", "PICK", "THE PLAN", "MODEL", "how often the model agreed with itself");
        int index = 0;
        for(int round = 1; round <= 16; round++){
            if(round == 12 || round == 13){
                System.out.printf("%-7d %-10s %-10s %-9s   %s%n", round, "-",
                        round == 12 ? "TUTEN" : "PURDY", "same",
                        "keeper - not a pick for either rule");
                continue;
            }
            int pick = PlanBacktest.MY_PICKS[index];
            Map<Position, Integer> counts = tally.get(index);
            Position mode = counts.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            int seen = counts.values().stream().mapToInt(Integer::intValue).sum();
            int most = mode == null ? 0 : counts.get(mode);
            StringBuilder spread = new StringBuilder();
            counts.entrySet().stream()
                    .sorted(Map.Entry.<Position, Integer>comparingByValue().reversed())
                    .forEach(e -> spread.append(spread.isEmpty() ? "" : " ")
                            .append(e.getKey()).append("x").append(e.getValue()));
            System.out.printf("%-7d %-10d %-10s %-9s   %d of %d   %s%s%n", round, pick,
                    plan[index], mode == null ? "-" : mode.toString(), most, seen, spread,
                    mode != null && plan[index].equals(mode.toString()) ? "   <- agree" : "");
            index++;
        }

        System.out.printf("%nA round where the model splits three ways is the model saying the%n"
                + "round is free, not the model being confused. Read the spread, not the%n"
                + "mode - five boards cannot support a confident mode on their own.%n");
    }
}
