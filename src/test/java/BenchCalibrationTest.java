import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bench constant, and the machinery that fits it.
 *
 * TRAPS.md D21: a parameter that cannot be identified should not be tuned. That
 * is not a slogan a test can check, but every piece of arithmetic underneath it
 * is, and each one below is a claim {@link BenchCalibration} makes in its own
 * output:
 *
 *   - the two ends of the family really are the two ends. lambda 0 and
 *     lostBelow 0 must make a bench man worth EXACTLY zero, because that is the
 *     failure the availability channel exists to fix, and lambda 1 must be best
 *     ball - the same lineup -PbestBall produces.
 *   - between them the bench value must be MONOTONE, or "fit the parameter" is
 *     not a well-posed request.
 *   - the shipped default must still be the shipped default. A refactor that
 *     quietly moved 0.55 would invalidate every recorded number.
 *   - a rejected fit must be reported as rejected, and a flat one as flat.
 */
class BenchCalibrationTest {

    private final BoardValue.Selection shipped = BoardValue.SELECTION;

    @AfterEach
    void restore(){
        BoardValue.SELECTION = shipped;
    }

    // =====================================================================
    // A fixture with real spread, so a bench man is not zero by construction.
    // =====================================================================

    private static final int DEPTH = 40;

    private static Map<Position, double[]> curve(){
        Map<Position, double[]> curve = new EnumMap<>(Position.class);
        curve.put(Position.QB, ramp(400, 8));
        curve.put(Position.RB, ramp(250, 4));
        curve.put(Position.WR, ramp(240, 4));
        curve.put(Position.TE, ramp(150, 3));
        curve.put(Position.DEF, ramp(120, 2));
        return curve;
    }

    private static double[] ramp(double top, double step){
        double[] out = new double[DEPTH];
        for(int rank = 1; rank < DEPTH; rank++){
            out[rank] = Math.max(1, top - step * (rank - 1));
        }
        return out;
    }

    /**
     * Every rank draws from the same set of multiples of its own mean, so a
     * deep man's good season really can beat a top man's bad one - which is the
     * only thing that ever pays for a bench pick.
     */
    private static Map<Position, List<List<Double>>> pools(Map<Position, double[]> curve){
        Map<Position, List<List<Double>>> pools = new EnumMap<>(Position.class);
        for(Map.Entry<Position, double[]> entry : curve.entrySet()){
            List<List<Double>> byRank = new ArrayList<>();
            for(int rank = 0; rank < entry.getValue().length; rank++){
                List<Double> pool = new ArrayList<>();
                for(double multiple : new double[]{0.2, 0.45, 0.7, 0.95, 1.15, 1.4, 1.8}){
                    pool.add(entry.getValue()[rank] * multiple);
                }
                byRank.add(pool);
            }
            pools.put(entry.getKey(), byRank);
        }
        return pools;
    }

    /** A full starting nine plus a defence: every slot covered, nothing spare. */
    private static List<BoardValue.Slot> full(){
        return new ArrayList<>(List.of(
                new BoardValue.Slot(Position.QB, 6),
                new BoardValue.Slot(Position.RB, 4),
                new BoardValue.Slot(Position.RB, 12),
                new BoardValue.Slot(Position.WR, 5),
                new BoardValue.Slot(Position.WR, 11),
                new BoardValue.Slot(Position.WR, 18),
                new BoardValue.Slot(Position.WR, 24),
                new BoardValue.Slot(Position.TE, 7),
                new BoardValue.Slot(Position.RB, 20),
                new BoardValue.Slot(Position.DEF, 9)));
    }

    /** What one more receiver is worth to that roster, under the rule now set. */
    private static double benchMarginal(){
        Map<Position, double[]> curve = curve();
        Map<Position, List<List<Double>>> pools = pools(curve);
        List<BoardValue.Slot> roster = full();
        double base = BoardValue.empirical(roster, pools, curve, 0);
        List<BoardValue.Slot> more = new ArrayList<>(roster);
        more.add(new BoardValue.Slot(Position.WR, 30));
        return BoardValue.empirical(more, pools, curve, 0) - base;
    }

    // =====================================================================
    // The two ends of the family.
    // =====================================================================

    /**
     * THE FAILURE THE WHOLE MECHANISM EXISTS TO FIX, pinned as a test.
     *
     * With nobody ever lost, a lineup chosen on preseason expectation can never
     * promote a bench man, so his marginal is EXACTLY zero - not small, zero.
     * That is what happened the first time BY_EXPECTED went on, the search
     * stopped discriminating, and the model drafted sixteen receivers.
     */
    @Test
    void atTheUselessEndABenchManIsWorthExactlyZero(){
        BoardValue.SELECTION = BoardValue.Selection.threshold(0.0);
        assertEquals(0.0, benchMarginal(), 1e-9,
                "lostBelow 0 must make a bench man worth nothing at all; anything"
                        + " else means some other channel is quietly paying him");

        BoardValue.SELECTION = BoardValue.Selection.blend(0.0);
        assertEquals(0.0, benchMarginal(), 1e-9,
                "lambda 0 is the same useless bench by construction");
    }

    /**
     * And lambda 1 is best ball, not a stronger version of this league.
     *
     * The blend at lambda 1 orders the lineup purely on what each man DREW,
     * which is exactly what -PbestBall does. If those two ever diverge, one of
     * them has stopped being what its comment says it is.
     */
    @Test
    void lambdaOneIsTheSameLineupBestBallSets(){
        Map<Position, double[]> curve = curve();
        Map<Position, List<List<Double>>> pools = pools(curve);
        List<BoardValue.Slot> roster = full();
        roster.add(new BoardValue.Slot(Position.WR, 30));

        BoardValue.SELECTION = BoardValue.Selection.blend(1.0);
        for(int world = 0; world < 50; world++){
            double blended = BoardValue.oneSeason(roster, pools, curve, world);
            double byDraw = bestBall(roster, pools, curve, world);
            assertEquals(byDraw, blended, 1e-9,
                    "lambda 1 and a drawn-points fill disagreed in world " + world);
        }
    }

    /** The drawn-points fill, computed independently of the code under test. */
    private static double bestBall(List<BoardValue.Slot> roster,
                                   Map<Position, List<List<Double>>> pools,
                                   Map<Position, double[]> curve, int world){
        Map<Position, List<Double>> drew = new EnumMap<>(Position.class);
        for(BoardValue.Slot slot : roster){
            drew.computeIfAbsent(slot.position(), u -> new ArrayList<>())
                    .add(BoardValue.drawn(pools, slot.position(), slot.rank(), world, curve));
        }
        for(List<Double> values : drew.values()){
            values.sort(java.util.Comparator.reverseOrder());
        }
        double total = 0;
        List<Double> flex = new ArrayList<>();
        int[] slots = {1, 2, 3, 1, 1};
        Position[] order = {Position.QB, Position.RB, Position.WR, Position.TE,
                Position.DEF};
        boolean[] flexes = {false, true, true, true, false};
        for(int p = 0; p < order.length; p++){
            List<Double> have = drew.getOrDefault(order[p], List.of());
            for(int slot = 0; slot < slots[p]; slot++){
                total += slot < have.size() ? have.get(slot)
                        : BoardValue.replacement(curve, order[p]);
            }
            if(flexes[p]){
                for(int extra = slots[p]; extra < have.size(); extra++){
                    flex.add(have.get(extra));
                }
            }
        }
        flex.sort(java.util.Comparator.reverseOrder());
        for(int slot = 0; slot < 2; slot++){
            total += slot < flex.size() ? flex.get(slot)
                    : BoardValue.replacement(curve, Position.RB);
        }
        return total;
    }

    // =====================================================================
    // Between the ends.
    // =====================================================================

    /**
     * A fit needs a monotone response or it is not a fit, it is a search over a
     * landscape. Both forms must buy MORE bench value as the parameter rises.
     */
    @Test
    void benchValueRisesWithBothParameters(){
        double previous = -1;
        for(double lostBelow : new double[]{0.0, 0.2, 0.4, 0.6, 0.8}){
            BoardValue.SELECTION = BoardValue.Selection.threshold(lostBelow);
            double now = benchMarginal();
            assertTrue(now >= previous - 1e-9,
                    "bench value fell going from the setting below to lostBelow "
                            + lostBelow + " (" + previous + " -> " + now + ")");
            previous = now;
        }
        previous = -1;
        for(double lambda : new double[]{0.0, 0.2, 0.4, 0.6, 0.8, 1.0}){
            BoardValue.SELECTION = BoardValue.Selection.blend(lambda);
            double now = benchMarginal();
            assertTrue(now >= previous - 1e-9,
                    "bench value fell going to lambda " + lambda
                            + " (" + previous + " -> " + now + ")");
            previous = now;
        }
    }

    /** The lineup is set on the rule; the points counted are always the draw. */
    @Test
    void theScoreIsAlwaysTheDrawWhateverSetsTheLineup(){
        Map<Position, double[]> curve = curve();
        Map<Position, List<List<Double>>> pools = pools(curve);
        // Two receivers, one flex-eligible slot beyond the three starters plus
        // two flexes, so exactly one of them is left out of the lineup.
        List<BoardValue.Slot> roster = full();
        BoardValue.SELECTION = BoardValue.Selection.blend(0.5);
        double blended = BoardValue.oneSeason(roster, pools, curve, 3);
        BoardValue.SELECTION = BoardValue.Selection.blend(1.0);
        double hindsight = BoardValue.oneSeason(roster, pools, curve, 3);
        assertTrue(hindsight >= blended - 1e-9,
                "perfect hindsight scored BELOW a partial blend, which cannot"
                        + " happen if both are scored on the same draws");
    }

    // =====================================================================
    // The guard that was not a guard.
    // =====================================================================

    /**
     * The shipped behaviour, stated so it cannot drift again: when every man at
     * a position is lost, they ALL go and the slot falls to the wire.
     *
     * The comment that used to sit here promised the opposite and the code did
     * this, because the size() it consulted was read inside its own removeIf
     * and never went down. The behaviour is kept - it is arguably the better
     * model - and it is now a named choice with the other one reachable.
     */
    @Test
    void whenEveryManAtAPositionIsLostTheWireTakesTheSlot(){
        List<double[]> both = new ArrayList<>(List.of(
                new double[]{200, 40}, new double[]{150, 20}));
        BoardValue.bench(both, BoardValue.Selection.threshold(0.55).fielding(true));
        assertEquals(0, both.size(),
                "the shipped rule drops both and streams; a survivor here would"
                        + " silently change every recorded backtest number");

        List<double[]> again = new ArrayList<>(List.of(
                new double[]{200, 40}, new double[]{150, 20}));
        BoardValue.bench(again, BoardValue.Selection.threshold(0.55).fielding(false));
        assertEquals(1, again.size(), "the other choice fields somebody");
        assertEquals(200, again.get(0)[0], 1e-9,
                "and it is the best man by EXPECTATION, not whoever happened to be"
                        + " added to the roster last");
    }

    /** A one-man position is never emptied under either choice. */
    @Test
    void aPositionWithOneManAlwaysStartsHim(){
        for(boolean wire : new boolean[]{true, false}){
            List<double[]> alone = new ArrayList<>(List.of(new double[]{200, 10}));
            BoardValue.bench(alone, BoardValue.Selection.threshold(0.55).fielding(wire));
            assertEquals(1, alone.size(),
                    "somebody starts at every position, wire=" + wire);
        }
    }

    /** And the men who are NOT lost are never touched. */
    @Test
    void onlyTheLostAreBenched(){
        List<double[]> men = new ArrayList<>(List.of(
                new double[]{200, 190}, new double[]{150, 30}, new double[]{100, 95}));
        BoardValue.bench(men, BoardValue.Selection.threshold(0.55).fielding(true));
        assertEquals(2, men.size(), "one man was lost, so two remain");
        assertEquals(200, men.get(0)[0], 1e-9);
        assertEquals(100, men.get(1)[0], 1e-9);
    }

    /** The blend benches nobody at all: its work is done in the ordering. */
    @Test
    void theBlendBenchesNobody(){
        List<double[]> men = new ArrayList<>(List.of(
                new double[]{200, 1}, new double[]{150, 2}));
        BoardValue.bench(men, BoardValue.Selection.blend(0.5));
        assertEquals(2, men.size(),
                "the blend has no threshold, so nothing may be removed by one");
    }

    // =====================================================================
    // The shipped default.
    // =====================================================================

    /**
     * 0.55, threshold, wire - the setting every published number was measured
     * with. A refactor that moved any of the three would silently invalidate
     * 1935 mean / 1792 worst and everything argued from them.
     */
    @Test
    void theShippedRuleIsStillTheShippedRule(){
        BoardValue.Selection rule = BoardValue.shipped();
        assertFalse(rule.blend(), "the shipped rule is the threshold, not the blend");
        assertEquals(0.55, rule.lostBelow(), 1e-9);
        assertTrue(rule.wireWhenAllLost(),
                "wireWhenAllLost=true is what the tagged backtest was measured with");
    }

    /** And the two forms are not silently interchangeable. */
    @Test
    void thresholdAndBlendAreDifferentRules(){
        assertNotEquals(BoardValue.Selection.threshold(0.55),
                BoardValue.Selection.blend(0.55));
        assertEquals(0.55, BoardValue.Selection.blend(0.55).order(0, 1.0), 1e-9,
                "the blend's order is expected + lambda * (drawn - expected)");
        assertEquals(0.0, BoardValue.Selection.threshold(0.55).order(0, 1.0), 1e-9,
                "the threshold orders on expectation alone");
        assertFalse(BoardValue.Selection.blend(0.9).lost(100, 10),
                "the blend has no lost men, however far a season fell");
        assertTrue(BoardValue.Selection.threshold(0.55).lost(100, 10));
        assertFalse(BoardValue.Selection.threshold(0.55).lost(100, 60));
    }

    // =====================================================================
    // The fit's own arithmetic.
    // =====================================================================

    private static List<BenchCalibration.Band> bands(){
        return List.of(new BenchCalibration.Band("rounds 8-9", 44.0, 9.2, 111),
                new BenchCalibration.Band("rounds 10-12", 32.8, 6.9, 154),
                new BenchCalibration.Band("rounds 13-16", 31.2, 6.6, 169));
    }

    /** A perfect match is a chi-square of zero, and a miss is measured in bars. */
    @Test
    void chiSquareIsInUnitsOfTheTargetsOwnBar(){
        assertEquals(0.0,
                BenchCalibration.chiSquare(new double[]{44.0, 32.8, 31.2}, bands()), 1e-9);
        // exactly one standard error high in the first band and nowhere else
        assertEquals(1.0,
                BenchCalibration.chiSquare(new double[]{44.0 + 4.6, 32.8, 31.2}, bands()),
                1e-9);
    }

    /** A band with no bar cannot constrain anything, and must not divide by zero. */
    @Test
    void aBandWithNoErrorBarIsSkippedRatherThanInfinite(){
        List<BenchCalibration.Band> soft = List.of(
                new BenchCalibration.Band("rounds 8-9", 44.0, 0.0, 1));
        assertEquals(0.0, BenchCalibration.chiSquare(new double[]{0.0}, soft), 1e-9);
    }

    /** A fit that misses everywhere must report itself as a miss. */
    @Test
    void aRejectedFitSaysSo(){
        List<BenchCalibration.Point> miss = new ArrayList<>();
        for(double p = 0; p <= 1.0; p += 0.1){
            double[] implied = {0, 0, 0};
            miss.add(new BenchCalibration.Point(p, implied,
                    BenchCalibration.chiSquare(implied, bands())));
        }
        assertFalse(BenchCalibration.reproducible(miss),
                "three bands missed by their whole value is not a fit");
    }

    /** A flat landscape must come back as an interval covering the whole sweep. */
    @Test
    void aFlatLandscapeIsReportedAsUnidentified(){
        List<BenchCalibration.Point> flat = new ArrayList<>();
        for(double p = 0.3; p <= 0.8001; p += 0.1){
            double[] implied = {44.0, 32.8, 31.2};
            flat.add(new BenchCalibration.Point(p, implied,
                    BenchCalibration.chiSquare(implied, bands())));
        }
        assertTrue(BenchCalibration.reproducible(flat));
        double[] range = BenchCalibration.interval(flat);
        assertEquals(0.3, range[0], 1e-9);
        assertEquals(0.8, range[1], 1e-9);
    }

    /** And a sharp one to a narrow interval around the minimum. */
    @Test
    void aSharpLandscapeNarrowsToItsMinimum(){
        List<BenchCalibration.Point> sharp = new ArrayList<>();
        for(double p = 0.0; p <= 1.0001; p += 0.1){
            // implied first band walks past the target at p = 0.5
            double[] implied = {44.0 + 40.0 * (p - 0.5), 32.8, 31.2};
            sharp.add(new BenchCalibration.Point(p, implied,
                    BenchCalibration.chiSquare(implied, bands())));
        }
        double[] range = BenchCalibration.interval(sharp);
        assertTrue(range[1] - range[0] <= 0.4,
                "a landscape this steep must not report a wide interval: got "
                        + Arrays.toString(range));
        assertEquals(0.5, BenchCalibration.best(sharp).parameter(), 1e-9);
    }

    // =====================================================================
    // The bands themselves.
    // =====================================================================

    /** The round split is the one BenchValue prints, and Justin's picks land in it. */
    @Test
    void theRoundBandsMatchTheOnesTheTargetWasMeasuredIn(){
        assertEquals(BenchValue.ROUNDS_8_9, BenchValue.band(8));
        assertEquals(BenchValue.ROUNDS_8_9, BenchValue.band(9));
        assertEquals(BenchValue.ROUNDS_10_12, BenchValue.band(10));
        assertEquals(BenchValue.ROUNDS_10_12, BenchValue.band(12));
        assertEquals(BenchValue.ROUNDS_13_16, BenchValue.band(13));
        assertEquals(BenchValue.ROUNDS_13_16, BenchValue.band(16));

        assertEquals(BenchCalibration.BENCH_PICKS.length,
                BenchCalibration.BENCH_ROUNDS.length,
                "every bench pick priced must know which round it buys");
        for(int i = 0; i < BenchCalibration.BENCH_ROUNDS.length; i++){
            assertEquals(BenchCalibration.BENCH_ROUNDS[i],
                    BoardValue.round(indexOf(BenchCalibration.BENCH_PICKS[i])),
                    "pick " + BenchCalibration.BENCH_PICKS[i] + " does not buy round "
                            + BenchCalibration.BENCH_ROUNDS[i] + " on the real schedule");
        }
    }

    private static int indexOf(int pick){
        for(int i = 0; i < PlanBacktest.MY_PICKS.length; i++){
            if(PlanBacktest.MY_PICKS[i] == pick){
                return i;
            }
        }
        throw new AssertionError(pick + " is not one of Justin's picks");
    }

    /**
     * The starting nine really is full before the first bench pick.
     *
     * If it were not, the bands would be measuring a starter's value and would
     * be far too high for reasons that have nothing to do with the parameter.
     */
    @Test
    void theCalibrationRosterFieldsAFullLineupBeforeAnyBenchPick(){
        List<String> ids = new ArrayList<>();
        Map<String, Position> positionOf = new java.util.HashMap<>();
        for(int i = 0; i < 250; i++){
            String id = "p" + i;
            ids.add(id);
            positionOf.put(id, switch(i % 5){
                case 0 -> Position.RB; case 1 -> Position.WR; case 2 -> Position.WR;
                case 3 -> Position.TE; default -> i % 25 == 4 ? Position.DEF
                        : Position.QB; });
        }
        List<BoardValue.Slot> roster = BenchCalibration.starters(
                new PlanBacktest.Board("fixture", ids, positionOf, List.of()));

        Map<Position, Integer> have = new EnumMap<>(Position.class);
        for(BoardValue.Slot slot : roster){
            have.merge(slot.position(), 1, Integer::sum);
        }
        assertEquals(1, have.getOrDefault(Position.QB, 0));
        assertEquals(3, have.getOrDefault(Position.RB, 0));
        assertEquals(4, have.getOrDefault(Position.WR, 0));
        assertEquals(1, have.getOrDefault(Position.TE, 0));
        assertEquals(1, have.getOrDefault(Position.DEF, 0));
        assertEquals(10, roster.size(),
                "ten men - the league's ten starting slots - and not one spare");
        assertEquals(RosterRules.live().size() - roster.size(),
                BenchCalibration.BENCH_PICKS.length,
                "the bench picks priced must exactly fill the roster, or the last"
                        + " one is being charged for a man there is no room for");
    }

    /** Defences are never priced as bench picks; the target counts skill men only. */
    @Test
    void theDefenceIsNotOneOfThePricedBenchPicks(){
        assertFalse(List.of(BenchCalibration.CANDIDATES).contains(Position.DEF),
                "BenchValue's join filters to skill positions, so pricing a defence"
                        + " against it would compare two different populations");
    }
}
