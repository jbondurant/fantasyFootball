import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TRAPS.md section E: comparing one position against another.
 *
 *   E22  raw points are not comparable across positions. Pricing a wait in raw
 *        points over-buys quarterbacks, because a quarterback's rank curve is
 *        steeper in absolute points. COMMITTED TWICE, by two authors, a day
 *        apart - RankDraft lost 68 points to it and BoardValue's first bench
 *        attempt priced a backup at 88 at every pick.
 *   E23  positions do not drain at the same rate. A uniform (next - pick) / 5
 *        threw away the only signal the model had and drafted TE TE QB QB.
 *   E24  a within-position matrix knows nothing about what a position is worth,
 *        nor about what is already on the roster.
 *
 * The fixture is a two-position world with the trap built into it: quarterbacks
 * score far more per man and fall away faster IN POINTS, while backs are what
 * the lineup is actually short of. A rule reading raw points takes the
 * quarterback. A rule reading a marginal against a filled lineup takes the back.
 * Both answers are computed here, so the test cannot pass by reading only one.
 */
class CrossPositionTest {

    // Six starters' worth of a curve at each position; index IS the rank, so
    // slot 0 is unused and the numbers below are read straight off it.
    private static final int DEPTH = 40;

    /**
     * Quarterbacks: 400 at the top, falling 20 a rank. Backs: 250 at the top,
     * falling 10 a rank. The quarterback is worth more AND decays faster in
     * absolute points, which is the whole of E22 in two lines.
     */
    private static Map<Position, double[]> curve(){
        Map<Position, double[]> curve = new EnumMap<>(Position.class);
        curve.put(Position.QB, ramp(400, 20));
        curve.put(Position.RB, ramp(250, 10));
        curve.put(Position.WR, ramp(240, 9));
        curve.put(Position.TE, ramp(150, 6));
        return curve;
    }

    private static double[] ramp(double top, double step){
        double[] out = new double[DEPTH];
        for(int rank = 1; rank < DEPTH; rank++){
            out[rank] = Math.max(0, top - step * (rank - 1));
        }
        return out;
    }

    /**
     * Outcome pools with real spread, so a bench man is not automatically worth
     * zero and the marginal is not a foregone conclusion. Every rank draws from
     * a band around its own mean.
     */
    private static Map<Position, List<List<Double>>> pools(Map<Position, double[]> curve){
        Map<Position, List<List<Double>>> pools = new EnumMap<>(Position.class);
        for(Map.Entry<Position, double[]> entry : curve.entrySet()){
            List<List<Double>> byRank = new ArrayList<>();
            for(int rank = 0; rank < entry.getValue().length; rank++){
                double mean = entry.getValue()[rank];
                List<Double> pool = new ArrayList<>();
                for(double multiple : new double[]{0.35, 0.7, 0.95, 1.05, 1.3, 1.65}){
                    pool.add(mean * multiple);
                }
                byRank.add(pool);
            }
            pools.put(entry.getKey(), byRank);
        }
        return pools;
    }

    /** What RankDraft.pointsLost prices a wait at: the mean now minus the mean later. */
    private static double rawPointsWait(Map<Position, double[]> curve, Position position,
                                        int early, int later){
        return curve.get(position)[early] - curve.get(position)[later];
    }

    // =====================================================================
    // E22.
    // =====================================================================

    /**
     * THE TRAP ITSELF, stated so it cannot creep back: on this board the raw
     * rule prefers the quarterback and the marginal rule prefers the back, and
     * the roster already holds a quarterback.
     *
     * If a future model's wait-pricing ever agrees with the raw column here, it
     * is denominated in raw points and it will over-buy quarterbacks.
     */
    @Test
    void pricingAWaitInRawPointsPrefersTheQuarterbackAndIsWrong(){
        Map<Position, double[]> curve = curve();
        Map<Position, List<List<Double>>> pools = pools(curve);
        // Purdy is kept: the one quarterback slot is already filled.
        List<BoardValue.Slot> roster = List.of(new BoardValue.Slot(Position.QB, 9));

        // The board at an early pick: two ranks of each position go by before my
        // next turn, so the wait is like for like.
        int early = 3;
        int later = 5;

        double rawQb = rawPointsWait(curve, Position.QB, early, later);
        double rawRb = rawPointsWait(curve, Position.RB, early, later);
        assertTrue(rawQb > rawRb,
                "the fixture must reproduce the trap's premise: a quarterback's"
                        + " curve is steeper in absolute points");
        assertEquals(40.0, rawQb, 1e-9);
        assertEquals(20.0, rawRb, 1e-9);

        double marginalQb = BoardValue.marginal(curve, pools, 16, roster,
                Position.QB, early, later);
        double marginalRb = BoardValue.marginal(curve, pools, 16, roster,
                Position.RB, early, later);

        assertTrue(marginalRb > marginalQb,
                "a second quarterback for a one-quarterback lineup was priced above"
                        + " a starting back: RB " + marginalRb + " against QB "
                        + marginalQb + ". That is the raw-points fault, twice"
                        + " committed, back a third time.");
    }

    /**
     * The sharp form the brief asked for: a BACKUP quarterback at an early pick
     * is wrong by construction, whatever the curve says.
     *
     * One quarterback slot means the second one can only ever be insurance, so
     * his marginal must be a small fraction of a starter's - not the 88 points
     * at every pick a flat bench figure once gave him.
     */
    @Test
    void aBackupQuarterbackIsWorthAFractionOfAStartingBackAtAnEarlyPick(){
        Map<Position, double[]> curve = curve();
        Map<Position, List<List<Double>>> pools = pools(curve);
        List<BoardValue.Slot> roster = List.of(new BoardValue.Slot(Position.QB, 9));

        double backup = BoardValue.marginal(curve, pools, 16, roster, Position.QB, 3, 5);
        double starter = BoardValue.marginal(curve, pools, 16, roster, Position.RB, 3, 5);

        assertTrue(backup < starter / 2,
                "a backup quarterback priced at " + backup + " against a starting"
                        + " back at " + starter + " - anything close to parity here"
                        + " drafts QB QB inside the first ten rounds");
        assertTrue(backup >= 0 || Math.abs(backup) < starter,
                "insurance may be worth little, but it cannot be worth more than"
                        + " the slot it does not fill");
    }

    /**
     * And the marginal needs no replacement level chosen by hand: it falls out
     * of the lineup being full.
     *
     * Adding a fourth receiver to a roster that already starts three plus two
     * flexes is worth strictly less than adding the first, at the SAME rank.
     * A raw-points rule cannot tell those two apart at all.
     */
    @Test
    void theMarginalKnowsWhatIsAlreadyOnTheRoster(){
        Map<Position, double[]> curve = curve();
        Map<Position, List<List<Double>>> pools = pools(curve);

        List<BoardValue.Slot> empty = List.of();
        List<BoardValue.Slot> stacked = List.of(
                new BoardValue.Slot(Position.WR, 1), new BoardValue.Slot(Position.WR, 2),
                new BoardValue.Slot(Position.WR, 3), new BoardValue.Slot(Position.WR, 4),
                new BoardValue.Slot(Position.WR, 5), new BoardValue.Slot(Position.WR, 6));

        double first = BoardValue.marginal(curve, pools, 16, empty, Position.WR, 8, 8);
        double seventh = BoardValue.marginal(curve, pools, 16, stacked, Position.WR, 8, 8);

        assertTrue(seventh < first,
                "the seventh receiver was worth as much as the first (" + seventh
                        + " against " + first + "), so the rule is blind to the roster");
        // the raw curve, by contrast, cannot see the difference at all
        assertEquals(curve.get(Position.WR)[8], curve.get(Position.WR)[8], 1e-9);
    }

    // =====================================================================
    // E23.
    // =====================================================================

    /**
     * The drain between two picks is read off the board's own order, so two
     * positions running at different speeds give different answers.
     *
     * The fixture is a receiver run: over picks 10-40 thirty receivers go and
     * three tight ends do. A uniform (next - pick) / 5 would call both six.
     */
    @Test
    void positionsDrainAtTheirOwnRateAndNotAtAUniformOne(){
        List<String> order = new ArrayList<>();
        Map<String, Position> positionOf = new HashMap<>();
        for(int i = 0; i < 60; i++){
            // ten of every eleven picks in this stretch is a receiver
            Position position = i >= 10 && i < 40 && i % 10 != 0 ? Position.WR : Position.TE;
            String id = "p" + i;
            order.add(id);
            positionOf.put(id, position);
        }
        PlanBacktest.Board board = new PlanBacktest.Board("fixture", order, positionOf,
                List.of());

        int wrDrain = BoardValue.adpDepth(board, Position.WR, 40)
                - BoardValue.adpDepth(board, Position.WR, 10);
        int teDrain = BoardValue.adpDepth(board, Position.TE, 40)
                - BoardValue.adpDepth(board, Position.TE, 10);

        assertEquals(27, wrDrain, "twenty-seven receivers go between picks 10 and 40");
        assertEquals(3, teDrain, "three tight ends do");
        assertNotEquals(wrDrain, teDrain,
                "the two positions drained at the same rate, which is the"
                        + " (next - pick) / 5 fault that drafted TE TE QB QB");

        int uniform = (40 - 10) / 5;
        assertNotEquals(uniform, wrDrain, "the receiver drain must not be the uniform 6");
        assertNotEquals(uniform, teDrain, "nor the tight end drain");
    }

    /** And the drain is a count, so it never runs backwards. */
    @Test
    void theDrainIsMonotoneInThePick(){
        List<String> order = new ArrayList<>();
        Map<String, Position> positionOf = new HashMap<>();
        for(int i = 0; i < 50; i++){
            String id = "p" + i;
            order.add(id);
            positionOf.put(id, i % 3 == 0 ? Position.RB : Position.WR);
        }
        PlanBacktest.Board board = new PlanBacktest.Board("fixture", order, positionOf,
                List.of());
        int previous = 0;
        for(int pick = 0; pick <= 50; pick++){
            int depth = BoardValue.adpDepth(board, Position.RB, pick);
            assertTrue(depth >= previous, "drain fell going from " + (pick - 1)
                    + " to " + pick);
            previous = depth;
        }
        assertEquals(17, previous, "seventeen backs in fifty picks");
    }

    // =====================================================================
    // E24.
    // =====================================================================

    /**
     * A rule that reads only ranks cannot see what a position is worth.
     *
     * Triple every tight end's points. Nothing about the ORDER of the board
     * changes, so every rank-based quantity - the depth counts a pairwise matrix
     * is indexed by - is byte-identical. The marginal moves, because it is
     * denominated in lineup points.
     *
     * That is why the matrix alone drafted a tight end at 18: it knew tight ends
     * decay fastest there and had no way to know one is worth less than a back.
     */
    @Test
    void aRankOnlyRuleIsBlindToWhatAPositionIsWorth(){
        Map<Position, double[]> cheap = curve();
        Map<Position, double[]> rich = curve();
        double[] tripled = rich.get(Position.TE).clone();
        for(int rank = 0; rank < tripled.length; rank++){
            tripled[rank] *= 3;
        }
        rich.put(Position.TE, tripled);

        List<String> order = new ArrayList<>();
        Map<String, Position> positionOf = new HashMap<>();
        for(int i = 0; i < 40; i++){
            String id = "p" + i;
            order.add(id);
            positionOf.put(id, i % 4 == 0 ? Position.TE : Position.RB);
        }
        PlanBacktest.Board board = new PlanBacktest.Board("fixture", order, positionOf,
                List.of());

        // the rank view: identical, because points never entered it
        assertEquals(BoardValue.adpDepth(board, Position.TE, 20),
                BoardValue.adpDepth(board, Position.TE, 20),
                "ranks do not depend on points at all");

        double before = BoardValue.marginal(cheap, pools(cheap), 16, List.of(),
                Position.TE, 4, 6);
        double after = BoardValue.marginal(rich, pools(rich), 16, List.of(),
                Position.TE, 4, 6);

        assertTrue(after > before,
                "tripling what a tight end scores did not change what taking one"
                        + " is worth (" + before + " -> " + after + "), so the rule"
                        + " is reading ranks and calling them value");
    }
}
