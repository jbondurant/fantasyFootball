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

    /**
     * The plan AS WRITTEN, which is not the string the backtest calls
     * "RUNBOOK committed".
     *
     * Justin, on seeing the old column: how can the plan, selecting 2 QBs in
     * addition to Purdy, make sense? It cannot, and it was my error to keep
     * printing it after finding it. RUNBOOK.md:77 makes the round-10
     * quarterback conditional - "if he is there, else RB/WR" - and :79 offers
     * round 14 as the ALTERNATIVE to that stash, not an addition. The encoded
     * string takes both conditionals as certain and adds a tight end at 11, so
     * it drafts two quarterbacks and a second tight end, neither of which the
     * document asks for.
     *
     * Resolved the way the document resolves them: one stash at 10, round 11
     * free, round 14 skill.
     */
    static final String PLAN = "RB RB RB WR WR WR WR TE WR QB WR RB RB DEF";

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
                // Printed in BOTH columns, because both rules hold these men
                // from the first pick. A sixteen-round list that shows them
                // under the plan and blank under the model reads as though the
                // model does not have them, and it does - PlanBacktest.keeperIDs
                // puts them on its roster and off its board before it drafts.
                String who = round == 12 ? "TUTEN RB" : "PURDY QB";
                System.out.printf("%-7d %-10s %-10s %-9s   %s%n", round, "kept",
                        who, who, "held by both - costs this round, not a pick");
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

        // WHO, not just what. A position is an abstraction; the question at the
        // table is whether to take this man, and a round-2 tight end means
        // something different if it is Kelce than if it is the fourth one left.
        System.out.printf("%n%nWHO THE MODEL ACTUALLY TOOK, BY SEASON%n%n");
        System.out.printf("%-7s %-6s %s%n", "ROUND", "PICK", "the men, one per board");
        for(int r = 0; r < 14; r++){
            int pick = PlanBacktest.MY_PICKS[r];
            StringBuilder line = new StringBuilder();
            for(PlanBacktest.Board board : boards){
                List<String> roster = BoardValue.adaptiveDraft(board, curve, pools,
                        order.size());
                int keepers = PlanBacktest.holdKeepers()
                        ? PlanBacktest.keeperIDs(board).size() : 0;
                int at = keepers + r;
                if(at >= roster.size()){
                    continue;
                }
                Player player = Player.getPlayerFromSIDV2(roster.get(at));
                Position position = board.positionOf().get(roster.get(at));
                line.append(line.isEmpty() ? "" : ", ")
                        .append(board.season()).append(" ")
                        .append(position).append(" ")
                        .append(player == null ? "?" : player.lastName);
            }
            System.out.printf("%-7d %-6d %s%n", r < 11 ? r + 1 : r + 3, pick, line);
        }

        // WHO, not just what. Justin asked which tight end the model takes at
        // round 2, and the position alone cannot answer it: taking the best
        // tight end on the board at 18 is a different decision from taking the
        // fourth, and only one of them is defensible.
        int want = Integer.getInteger("round", 2);
        int wantIndex = want <= 11 ? want - 1 : want - 3;
        if(wantIndex >= 0 && wantIndex < 14){
            System.out.printf("%n%s%nWHO THE MODEL ACTUALLY TOOK IN ROUND %d%n%s%n",
                    "=".repeat(64), want, "=".repeat(64));
            System.out.printf("%n%-8s %-24s %-5s %s%n",
                    "SEASON", "THE MAN", "POS", "his rank at that position");
            for(PlanBacktest.Board board : boards){
                List<String> roster = BoardValue.adaptiveDraft(board, curve, pools,
                        order.size());
                int keepers = PlanBacktest.holdKeepers()
                        ? PlanBacktest.keeperIDs(board).size() : 0;
                int at = keepers + wantIndex;
                if(at >= roster.size()){
                    continue;
                }
                String id = roster.get(at);
                Position position = board.positionOf().get(id);
                int rank = 0;
                for(String other : board.ids()){
                    if(board.positionOf().get(other) == position){
                        rank++;
                        if(other.equals(id)){
                            break;
                        }
                    }
                }
                Player player = Player.getPlayerFromSIDV2(id);
                System.out.printf("%-8s %-24s %-5s %s%d%n", board.season(),
                        player == null ? id : player.firstName + " " + player.lastName,
                        position, position, rank);
            }
        }

        System.out.printf("%nA round where the model splits three ways is the model saying the%n"
                + "round is free, not the model being confused. Read the spread, not the%n"
                + "mode - five boards cannot support a confident mode on their own.%n");
    }
}
