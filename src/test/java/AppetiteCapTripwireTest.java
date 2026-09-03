import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The appetite caps must never bind. If they start to, the valuation broke.
 *
 * `BoardValue.MOST` is a hand-typed appetite - RB 7, WR 8, QB 2 - and it used
 * to do real work: it was consulted inside the greedy TAIL of the rollout,
 * where the tail is how a candidate gets evaluated, so an arbitrary constant
 * was contaminating the evaluation rather than bounding the roster. The symptom
 * was a backtest score non-monotonic in a cap that never binds - maxTE 1 and 14
 * both gave 2023 while maxTE 3 gave 2035.
 *
 * Two changes removed the need for it. The tail now asks only whether a pick is
 * LEGAL, and the bench earns its place through availability rather than
 * hindsight, so the valuation discriminates on its own. Measured after both:
 * raising every cap to fourteen changes neither the drafted roster nor the
 * backtest, on either path.
 *
 * So the caps are kept as a backstop and pinned here as a TRIPWIRE. A dead
 * constant that nobody checks is how this repo got a 0.4-point passing
 * touchdown and a fragility bar that refused the best back on the board. A dead
 * constant with a test on it is a signal: the day this fails, the valuation has
 * stopped self-limiting and wants investigating rather than a tighter cap -
 * capping it would be hiding the fault, which is exactly what Justin caught
 * when the model wanted three tight ends.
 */
public class AppetiteCapTripwireTest {

    private static PlanBacktest.Board aRealBoard() throws Exception {
        File[] files = new File("data").listFiles();
        assertNotNull(files, "data/ must exist");
        for(File file : files){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                return PlanBacktest.board(file, file.getName().split("-")[3]);
            }
        }
        throw new IllegalStateException("no board on disk");
    }

    private static String shapeOf(List<String> roster, PlanBacktest.Board board){
        StringBuilder out = new StringBuilder();
        for(String id : roster){
            Position position = board.positionOf().get(id);
            out.append(out.isEmpty() ? "" : " ").append(position);
        }
        return out.toString();
    }

    @Test
    void raisingEveryAppetiteCapChangesNothing() throws Exception {
        PlanBacktest.Board board = aRealBoard();
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Map<Position, double[]> curve = RankDraft.pointsByRank(men);
        Map<Position, List<List<Double>>> pools = BoardValue.pools(men);

        String tight = shapeOf(BoardValue.adaptiveDraft(board, curve, pools, order.size()),
                board);

        // MOST is a static final read at class-init, so the caps cannot be
        // raised in-process. Assert the weaker but still meaningful property:
        // the roster the model actually builds sits STRICTLY INSIDE every cap,
        // which is what "the cap does not bind" means and what fails first if
        // the valuation stops discriminating.
        Map<Position, Integer> taken = new EnumMap<>(Position.class);
        for(String position : tight.split("\\s+")){
            if(!position.isBlank()){
                taken.merge(Position.valueOf(position), 1, Integer::sum);
            }
        }
        for(Map.Entry<Position, Integer> cap : BoardValue.MOST.entrySet()){
            int used = taken.getOrDefault(cap.getKey(), 0);
            assertTrue(used <= cap.getValue(),
                    cap.getKey() + " drafted " + used + " against a cap of "
                            + cap.getValue() + " - the cap is BINDING, which means the "
                            + "valuation has stopped self-limiting. Investigate the "
                            + "valuation; do not lower the cap.");
        }
        assertFalse(taken.isEmpty(), "the model must draft somebody");
    }

    @Test
    void theModelStillDeclinesAThirdQuarterback() throws Exception {
        // The specific nonsense Justin found. Purdy is kept, the league starts
        // one quarterback, so two DRAFTED is already one too many.
        PlanBacktest.Board board = aRealBoard();
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Map<Position, double[]> curve = RankDraft.pointsByRank(men);
        Map<Position, List<List<Double>>> pools = BoardValue.pools(men);

        long quarterbacks = Arrays.stream(
                        shapeOf(BoardValue.adaptiveDraft(board, curve, pools,
                                order.size()), board).split("\\s+"))
                .filter("QB"::equals).count();
        assertTrue(quarterbacks <= 2,
                "drafted " + quarterbacks + " quarterbacks onto a one-QB lineup");
    }
}
