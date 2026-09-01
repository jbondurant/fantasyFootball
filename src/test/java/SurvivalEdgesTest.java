import PlayerImportAndSetup.Position;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * THE EDGES OF THE SURVIVAL TABLE, which is new tonight and indexes arrays.
 *
 * Survival keeps a double[202] per position and fills 1..201, and
 * probabilityGone binary-searches a sorted int[]. expectedRank then adds a
 * correction per man already taken. Every one of those is an index, and the
 * live path feeds them pick numbers from a schedule that runs 1..192 while
 * LiveBoard's own scans run to 200 and fall back to 200 past the end of the
 * game.
 *
 * None of this can be allowed to throw at a table. The searched-for failures
 * and what was actually measured:
 *
 *   pick 0, -5           clamped to 1, rank 1          (no man is gone yet)
 *   pick 1               rank 1
 *   pick 192, 193, 200   inside the array
 *   pick 201, 202, 1000  clamped to 201, no throw
 *   a man in zero draws  probabilityGone 0, and taking him still counts him
 *   every man taken      rank runs past the curve, and the callers guard it
 *
 * SurvivalRankTest covers whether the table is RIGHT. This covers whether it
 * can be made to throw or to return nonsense at a boundary.
 */
public class SurvivalEdgesTest {

    private static DraftPlanner planner;
    private static DraftSimulator simulator;

    @AfterEach
    public void clearTheTable(){
        // SURVIVAL is a mutable static and the whole suite shares one JVM.
        LiveBoard.SURVIVAL = null;
    }

    private static synchronized void warm() throws Exception {
        if(planner != null){
            return;
        }
        System.setProperty("scheduleRounds", "16");
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        simulator = planner.simulator();
    }

    private static final Position[] PRICED = {Position.RB, Position.WR, Position.TE,
            Position.QB, Position.DEF};

    @Test
    public void noPickNumberCanThrowOrGoBackwards() throws Exception {
        warm();
        LiveBoard.SURVIVAL = new LiveBoard.Survival(planner, simulator, 30, 31_337L);
        int[] picks = {-5, 0, 1, 2, 7, 186, 191, 192, 193, 200, 201, 202, 1000};
        for(Position position : PRICED){
            int previous = 0;
            for(int pick : picks){
                int rank = LiveBoard.expectedRank(planner, List.of(), position, pick);
                assertTrue(rank >= 1,
                        position + " at pick " + pick + " gave rank " + rank
                                + "; a rank is one-based and indexes the curve");
                if(pick >= 1){
                    assertTrue(rank >= previous,
                            position + " went BACKWARDS between picks: rank "
                                    + previous + " then " + rank + " at pick " + pick);
                    previous = rank;
                }
            }
        }
    }

    /** Past the end of the array the answer must settle, not wrap or throw. */
    @Test
    public void beyondTheLastPickTheTableStopsMoving() throws Exception {
        warm();
        LiveBoard.SURVIVAL = new LiveBoard.Survival(planner, simulator, 30, 31_337L);
        for(Position position : PRICED){
            int at201 = LiveBoard.expectedRank(planner, List.of(), position, 201);
            assertEquals(at201, LiveBoard.expectedRank(planner, List.of(), position, 202));
            assertEquals(at201, LiveBoard.expectedRank(planner, List.of(), position, 100_000));
        }
    }

    /**
     * A man no simulated draft ever took is still gone once he is really taken.
     *
     * He has no entry in `wentAt` at all, so probabilityGone returns 0 for him -
     * the same value it returns for a man who merely survives - and the
     * correction in expectedRank is `1 - probabilityGone`. The pool is mostly
     * such men: the simulator's board stops at ADP 250 while the projection pool
     * does not.
     */
    @Test
    public void aManNoSimulationEverTookStillCountsWhenHeIsTaken() throws Exception {
        warm();
        LiveBoard.SURVIVAL = new LiveBoard.Survival(planner, simulator, 30, 31_337L);
        String never = null;
        for(String id : planner.points().keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null && player.position == Position.RB
                    && LiveBoard.SURVIVAL.probabilityGone(id, 201) == 0.0){
                never = id;
                break;
            }
        }
        assertNotNull(never, "the projection pool must reach past the simulator's board");
        int without = LiveBoard.expectedRank(planner, List.of(), Position.RB, 90);
        int with = LiveBoard.expectedRank(planner, List.of(never), Position.RB, 90);
        assertEquals(without + 1, with,
                "a man really taken counts exactly one, whatever the prior thought");
    }

    /**
     * With every man of a position gone the rank runs past the curve, and the
     * two callers that index the curve with it must both survive that.
     */
    @Test
    public void aDrainedPositionIsSkippedRatherThanIndexedPastTheEnd() throws Exception {
        warm();
        LiveBoard.SURVIVAL = new LiveBoard.Survival(planner, simulator, 30, 31_337L);
        List<String> everyTightEnd = new ArrayList<>();
        for(String id : planner.points().keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null && player.position == Position.TE){
                everyTightEnd.add(id);
            }
        }
        Map<Position, double[]> curve = LiveBoard.thisYear(planner, Set.of());
        int rank = LiveBoard.expectedRank(planner, everyTightEnd, Position.TE, 90);
        assertTrue(rank > curve.get(Position.TE).length,
                "the fixture must actually push the rank past the curve");
        // BoardValue.drawn is the hot path that indexes it and must not throw.
        assertDoesNotThrow(() -> BoardValue.drawn(
                new EnumMap<>(Position.class), Position.TE, rank, 0, curve, true));
    }
}
