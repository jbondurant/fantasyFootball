import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two pieces of PowerBacktest that are arithmetic rather than football: the
 * snake schedule with the keeper hole in it, and the clustered paired
 * statistics that decide what counts as a real difference.
 *
 * The drafting and scoring are not tested here because they are checked against
 * PlanBacktest on real boards every time the tool runs - see
 * PowerBacktest.reproductionCheck, which throws rather than prints.
 */
public class PowerBacktestTest {

    private static final Set<Integer> KEEPERS = Set.of(12, 13);

    @Test
    void slotSevenIsExactlyThePickListTheRestOfTheRepoUses(){
        assertArrayEquals(PlanBacktest.MY_PICKS,
                PowerBacktest.picksFor(7, 12, 16, KEEPERS),
                "the generalised schedule must agree with the hand-entered slot 7");
    }

    @Test
    void theKeeperHoleIsThirtyFivePicksWide(){
        int[] picks = PowerBacktest.picksFor(7, 12, 16, KEEPERS);
        // rounds 12 and 13 are gone, so the gap spans two whole rounds plus the
        // usual snake turn: 162 - 127 = 35
        assertEquals(127, picks[10]);
        assertEquals(162, picks[11]);
        assertEquals(35, picks[11] - picks[10]);
    }

    @Test
    void everySeatGetsFourteenPicksAndNoTwoSeatsShareOne(){
        Set<Integer> seen = new HashSet<>();
        for(int slot = 1; slot <= 12; slot++){
            int[] picks = PowerBacktest.picksFor(slot, 12, 16, KEEPERS);
            assertEquals(14, picks.length, "slot " + slot);
            for(int pick : picks){
                assertTrue(seen.add(pick), "pick " + pick + " claimed twice, slot " + slot);
                assertTrue(pick >= 1 && pick <= 192, "pick " + pick + " off the board");
            }
        }
        assertEquals(14 * 12, seen.size());
    }

    @Test
    void theSnakeTurnsEveryRound(){
        // slot 1 opens round 1 and closes round 2; slot 12 is the mirror
        assertEquals(1, PowerBacktest.picksFor(1, 12, 16, Set.of())[0]);
        assertEquals(24, PowerBacktest.picksFor(1, 12, 16, Set.of())[1]);
        assertEquals(12, PowerBacktest.picksFor(12, 12, 16, Set.of())[0]);
        assertEquals(13, PowerBacktest.picksFor(12, 12, 16, Set.of())[1]);
    }

    @Test
    void keeperRoundsAreTheOnlyOnesMissing(){
        assertEquals(16, PowerBacktest.picksFor(4, 12, 16, Set.of()).length);
        assertEquals(14, PowerBacktest.picksFor(4, 12, 16, KEEPERS).length);
    }

    @Test
    void aSeatOffTheBoardIsRefused(){
        assertThrows(IllegalArgumentException.class,
                () -> PowerBacktest.picksFor(13, 12, 16, KEEPERS));
        assertThrows(IllegalArgumentException.class,
                () -> PowerBacktest.picksFor(0, 12, 16, KEEPERS));
    }

    // ------------------------------------------------------------- opponents

    private static List<String> board(int size){
        List<String> ids = new ArrayList<>();
        for(int i = 0; i < size; i++){
            ids.add("p" + i);
        }
        return ids;
    }

    private static Map<String, Position> allReceivers(List<String> ids){
        Map<String, Position> positionOf = new HashMap<>();
        for(String id : ids){
            positionOf.put(id, Position.WR);
        }
        return positionOf;
    }

    @Test
    void jitterZeroLeavesTheAdpOrderExactlyAlone(){
        List<String> ids = board(60);
        assertEquals(ids, PowerBacktest.opponentOrder(ids, allReceivers(ids),
                (random, depth, position) -> 99.0, 0, 1L),
                "jitter 0 must be the old deterministic harness, or the"
                        + " reproduction check proves nothing");
    }

    @Test
    void defencesNeverEnterTheOpponentOrder(){
        List<String> ids = board(10);
        Map<String, Position> positionOf = allReceivers(ids);
        positionOf.put("p3", Position.DEF);
        positionOf.put("p8", Position.DEF);
        List<String> order = PowerBacktest.opponentOrder(ids, positionOf, null, 0, 0);
        assertEquals(8, order.size());
        assertTrue(!order.contains("p3") && !order.contains("p8"),
                "the other eleven do not draft defences");
    }

    @Test
    void oneWorldIsReproducibleAndTwoWorldsDiffer(){
        List<String> ids = board(120);
        Map<String, Position> positionOf = allReceivers(ids);
        DisplacementModel spread = (random, depth, position) -> random.nextGaussian() * 12;
        List<String> a = PowerBacktest.opponentOrder(ids, positionOf, spread, 1, 7L);
        List<String> b = PowerBacktest.opponentOrder(ids, positionOf, spread, 1, 7L);
        List<String> c = PowerBacktest.opponentOrder(ids, positionOf, spread, 1, 8L);
        assertEquals(a, b, "same world seed, same opponents - the common random numbers");
        assertNotEquals(a, c, "a different world must be a different board");
        assertEquals(new HashSet<>(ids), new HashSet<>(a), "a permutation, not a filter");
    }

    @Test
    void aConstantDisplacementCannotReorderTheBoard(){
        // sorting on depth + constant is sorting on depth; only spread and the
        // between-position offsets can move anyone
        List<String> ids = board(40);
        assertEquals(ids, PowerBacktest.opponentOrder(ids, allReceivers(ids),
                (random, depth, position) -> 25.0, 1, 3L));
    }

    // ------------------------------------------------------------ statistics

    /** Four draws in each of two seasons. */
    private static int[] clusters(int seasons, int each){
        int[] clusterOf = new int[seasons * each];
        for(int k = 0; k < clusterOf.length; k++){
            clusterOf[k] = k / each;
        }
        return clusterOf;
    }

    @Test
    void aStrategyThatAlwaysTiesHasNoUncertaintyAndNoBar(){
        double[] score = new double[20];
        double[] diff = new double[20];
        PowerBacktest.Paired row = PowerBacktest.paired("tie", score, diff,
                clusters(5, 4), 5);
        assertEquals(0, row.diff(), 1e-9);
        assertEquals(0, row.seNaive(), 1e-9);
        assertEquals(0, row.seSeason(), 1e-9);
        assertEquals(0, row.bar(), 1e-9);
        assertEquals(0, row.wins());
    }

    @Test
    void theClusteredErrorSeesSeasonsAndTheNaiveOneDoesNot(){
        // every draw inside a season is identical, so there are really only two
        // numbers here: +100 and -100. The naive error divides by 20 draws and
        // gets it badly wrong; the clustered one divides by 2 seasons.
        int seasons = 2;
        int each = 10;
        double[] diff = new double[seasons * each];
        double[] score = new double[diff.length];
        for(int k = 0; k < diff.length; k++){
            diff[k] = k < each ? 100 : -100;
        }
        PowerBacktest.Paired row = PowerBacktest.paired("split", score, diff,
                clusters(seasons, each), seasons);
        assertEquals(0, row.diff(), 1e-9);
        // sd of the 20 draws is 100*sqrt(20/19); over sqrt(20)
        assertEquals(100 * Math.sqrt(20.0 / 19.0) / Math.sqrt(20), row.seNaive(), 1e-6);
        // cluster means are +100 and -100: sd 100*sqrt(2), over sqrt(2)
        assertEquals(100.0, row.seSeason(), 1e-6);
        assertTrue(row.seSeason() > 4 * row.seNaive(),
                "pooling draws inside a season understates the error four-fold here");
    }

    @Test
    void withinSeasonNoiseIsStrippedOutOfTheBetweenSeasonComponent(){
        // all five seasons share the same true difference of zero; everything
        // observed is draw-level noise, so the irreducible between-season
        // component should come out at or near zero
        Random random = new Random(11);
        int seasons = 5;
        int each = 96;
        double[] diff = new double[seasons * each];
        double[] score = new double[diff.length];
        for(int k = 0; k < diff.length; k++){
            diff[k] = random.nextGaussian() * 150;
        }
        PowerBacktest.Paired row = PowerBacktest.paired("noise", score, diff,
                clusters(seasons, each), seasons);
        assertTrue(Math.sqrt(row.varWithin()) > 130 && Math.sqrt(row.varWithin()) < 170,
                "draw-level sd should recover the 150 it was drawn with, got "
                        + Math.sqrt(row.varWithin()));
        assertTrue(Math.sqrt(row.varBetween()) < 40,
                "no real season effect exists, so the floor should be small, got "
                        + Math.sqrt(row.varBetween()));
        assertTrue(row.seFloor() < row.seSeason() + 1e-9,
                "the floor cannot exceed the error we actually have");
    }

    @Test
    void aRealSeasonEffectSurvivesTheDecomposition(){
        // seasons differ by +-200 with only mild draw noise on top; the
        // between-season component must find that, because it is the part no
        // number of extra slots can average away
        Random random = new Random(3);
        int seasons = 5;
        int each = 96;
        double[] level = {200, -200, 200, -200, 0};
        double[] diff = new double[seasons * each];
        double[] score = new double[diff.length];
        for(int k = 0; k < diff.length; k++){
            diff[k] = level[k / each] + random.nextGaussian() * 20;
        }
        PowerBacktest.Paired row = PowerBacktest.paired("seasonal", score, diff,
                clusters(seasons, each), seasons);
        assertTrue(Math.sqrt(row.varBetween()) > 150,
                "a genuine season effect must show up, got " + Math.sqrt(row.varBetween()));
        assertTrue(row.seFloor() > 0.9 * row.seSeason(),
                "when the noise is season-level, more slots buy almost nothing");
    }

    @Test
    void moreSeasonsShrinkTheProjectedErrorAndMoreDrawsHitAFloor(){
        Random random = new Random(5);
        int each = 96;
        double[] diff = new double[5 * each];
        double[] score = new double[diff.length];
        double[] level = {120, -80, 60, -140, 40};
        for(int k = 0; k < diff.length; k++){
            diff[k] = level[k / each] + random.nextGaussian() * 100;
        }
        PowerBacktest.Paired row = PowerBacktest.paired("mixed", score, diff,
                clusters(5, each), 5);
        assertTrue(row.seAt(17, each) < row.seAt(5, each),
                "seasons are the axis that helps");
        assertTrue(row.seAt(5, 1_000_000) > 0.5 * row.seFloor(),
                "draws bottom out at the between-season floor");
        assertEquals(row.seFloor(), row.seAt(5, Integer.MAX_VALUE), 1e-6);
    }

    @Test
    void winsCountsDrawsBeatenNotSeasons(){
        double[] diff = {1, 1, 1, -1, -1, -1, -1, -1};
        PowerBacktest.Paired row = PowerBacktest.paired("some", new double[8], diff,
                clusters(2, 4), 2);
        assertEquals(3, row.wins());
        assertEquals(8, row.draws());
    }

    @Test
    void fiveSeasonsMeansTwoPointSevenEightNotOnePointNineSix(){
        // the published two-sided 95% row, df 1..10
        double[] published = {12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365,
                2.306, 2.262, 2.228};
        for(int df = 1; df <= published.length; df++){
            assertEquals(published[df - 1], PowerBacktest.t975(df), 1e-3,
                    "t(0.975) at df " + df);
        }
        // the published 80% row, df 1..6; df 1 is 1.376 because the Cauchy has
        // no light tail to speak of
        double[] eighty = {1.376, 1.061, 0.978, 0.941, 0.920, 0.906};
        for(int df = 1; df <= eighty.length; df++){
            assertEquals(eighty[df - 1], PowerBacktest.t80(df), 1e-3,
                    "t(0.80) at df " + df);
        }
        assertTrue(PowerBacktest.t975(2000) < 1.97 && PowerBacktest.t975(2000) > 1.95,
                "large samples converge on the normal 1.96, got "
                        + PowerBacktest.t975(2000));
        for(int df = 1; df < 40; df++){
            assertTrue(PowerBacktest.t975(df) >= PowerBacktest.t975(df + 1),
                    "t must fall as degrees of freedom rise, broke at " + df);
        }
    }

    @Test
    void theCdfAndTheQuantileAreEachOthersInverse(){
        for(int df : new int[]{1, 4, 9, 30}){
            for(double p : new double[]{0.6, 0.8, 0.95, 0.975, 0.999}){
                double t = PowerBacktest.tQuantile(p, df);
                assertEquals(p, PowerBacktest.tCdf(t, df), 1e-6,
                        "df " + df + " p " + p);
            }
        }
        assertEquals(0.5, PowerBacktest.tCdf(0, 4), 1e-9, "the t is symmetric about 0");
    }

    @Test
    void aBonferroniQuantileIsWellPastAnythingATablePrints(){
        // 36 pairs, two-sided 5% split across them, four degrees of freedom -
        // the reason the quantile is computed instead of looked up
        double strict = PowerBacktest.tQuantile(1 - 0.05 / (2.0 * 36), 4);
        assertTrue(strict > 2 * PowerBacktest.t975(4),
                "the all-pairs bar must be far stricter than a single pair's, got "
                        + strict);
        assertTrue(strict < 30, "and still finite, got " + strict);
    }

    @Test
    void theDetectableEffectIsWiderThanBareSignificance(){
        double se = 100;
        assertTrue(PowerBacktest.minimumDetectable(se, 5) > PowerBacktest.t975(4) * se,
                "a gap that only just clears significance is one this design"
                        + " usually misses");
        assertEquals((2.776 + 0.941) * se, PowerBacktest.minimumDetectable(se, 5), 0.5);
    }

    @Test
    void theLegalityFloorStopsThirdQuarterbacksAndSecondDefences(){
        List<Position> chosen = new ArrayList<>();
        assertTrue(PowerBacktest.worthTaking(Position.QB, chosen, 14));
        chosen.add(Position.QB);
        assertTrue(PowerBacktest.worthTaking(Position.QB, chosen, 14));
        chosen.add(Position.QB);
        assertTrue(!PowerBacktest.worthTaking(Position.QB, chosen, 14),
                "a third quarterback can never start");
        assertTrue(PowerBacktest.worthTaking(Position.DEF, chosen, 14));
        chosen.add(Position.DEF);
        assertTrue(!PowerBacktest.worthTaking(Position.DEF, chosen, 14),
                "a second defence can never start");
        assertTrue(PowerBacktest.worthTaking(Position.RB, chosen, 14));
        assertTrue(PowerBacktest.worthTaking(Position.WR, chosen, 14));
        assertTrue(PowerBacktest.worthTaking(Position.TE, chosen, 14));
    }
}
