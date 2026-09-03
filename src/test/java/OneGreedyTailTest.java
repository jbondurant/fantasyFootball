import PlayerImportAndSetup.Position;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * THE NINTH PROSE-DRIFT FAULT: the loop that must not be copied, copied.
 *
 * LiveBoard.rulesRoster's javadoc states the rule this repo learned the hard
 * way:
 *
 *     "Extracted so that nothing else has to rebuild it. ... a second copy of
 *      this loop is exactly how the two would drift apart - which is the
 *      failure this repo has hit six times."
 *
 * A hundred lines above that sentence, LiveBoard.rollout and
 * LiveBoard.rolloutRoster are eighty-seven lines that differ only in their last
 * statement - `return BoardValue.empirical(roster, ...)` against
 * `return roster`. The twenty-five line comment block explaining why the tail
 * must reserve seats for unfilled starting slots appears twice, word for word.
 *
 * rollout() has NO callers anywhere in src/. So the copy that would rot is the
 * one nothing runs and nothing tests: the next person to fix the tail fixes one
 * of them, every test still passes, and the repo carries a method whose
 * comments promise a legality guarantee its body may no longer provide. That is
 * the same shape as the eight faults before it, caught before it cost anything.
 *
 * The fix is not to delete the method - it is a legitimate thing to want - but
 * to make it delegate, so there is exactly one greedy tail in the file.
 */
public class OneGreedyTailTest {

    private static String liveBoard() throws Exception {
        return Files.readString(Path.of("src/main/java/LiveBoard.java"));
    }

    /**
     * The tail's own explanatory comment must appear once.
     *
     * Chosen as the marker because it is the sentence that states the property
     * the loop is responsible for. Two copies of it is precisely the condition
     * where one loop can stop honouring it while the prose still says it does.
     */
    @Test
    public void theTailIsWrittenOnce() throws Exception {
        String source = liveBoard();
        String marker = "THE TAIL MUST END WITH A LEGAL LINEUP.";
        int copies = source.split(quoted(marker), -1).length - 1;
        assertEquals(1, copies,
                "the greedy tail, and the comment promising it finishes legal,"
                        + " must exist once - found " + copies + " copies");
    }

    /** And the loop body itself, not just its comment. */
    @Test
    public void theSeatReservationIsWrittenOnce() throws Exception {
        String source = liveBoard();
        int copies = source.split(
                quoted("boolean mustFill = owed > seatsLeft;"), -1).length - 1;
        assertEquals(1, copies,
                "the seat-reservation rule must have one home - found " + copies);
    }

    private static String quoted(String literal){
        return java.util.regex.Pattern.quote(literal);
    }

    /**
     * Whatever the shape, the two entry points must agree on the same roster.
     *
     * This is the behavioural half: it holds today because the bodies are
     * identical, and it is what keeps holding after one delegates to the other.
     */
    @Test
    public void bothEntryPointsScoreTheSameTail() throws Exception {
        System.setProperty("scheduleRounds", "16");
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        Set<String> kept = LiveBoard.kept(configuration);
        Map<Position, double[]> curve = LiveBoard.thisYear(planner, kept);
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Map<Position, List<List<Double>>> pools =
                new EnumMap<>(BoardValue.pools(men, curve));
        List<List<Double>> defence = LiveBoard.defenceScatter();
        if(!defence.isEmpty()){
            pools.put(Position.DEF, defence);
        }
        List<BoardValue.Slot> held = List.of(new BoardValue.Slot(Position.RB, 24),
                new BoardValue.Slot(Position.QB, 6));
        for(int from : new int[]{6, 30, 78, 126, 185}){
            for(Position first : new Position[]{Position.RB, Position.WR,
                    Position.TE, Position.DEF}){
                double direct = LiveBoard.rollout(planner, List.of(), curve, pools,
                        order.size(), held, first, 1, from);
                double viaRoster = BoardValue.empirical(
                        LiveBoard.rolloutRoster(planner, List.of(), curve, pools,
                                order.size(), held, first, 1, from),
                        pools, curve, order.size(), true);
                assertEquals(viaRoster, direct, 1e-9,
                        "the two tails disagree at pick " + from + " on " + first
                                + " - which is the drift this file's own comment"
                                + " says has cost this repo six times");
            }
        }
    }
}
