import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TradeSearchTest {

    /**
     * A one-week league. Index = man. Justin (0-4): a QB, three RBs, one thin WR. The other team (5-9): a QB,
     * a star WR at 5th on his roster, an RB, and two nobodies. Lineups need QB, 2 RB, 3 WR, TE, DEF, two FLEX,
     * with no free agents, so every point is held.
     */
    static final List<String> MEN = List.of("myQb", "myRb1", "myRb2", "myRb3", "myWr", "hisQb", "hisRb", "hisBench", "hisWorst", "hisStar");
    static final Position[] AT = {Position.QB, Position.RB, Position.RB, Position.RB, Position.WR, Position.QB, Position.RB, Position.WR, Position.WR, Position.WR};
    static final double[] POINTS = {20, 18, 16, 14, 5, 15, 6, 2, 1, 25};

    static WeeklyLineupValue.Table table(){
        Map<String, Double> week = new HashMap<>();
        Map<String, Position> at = new HashMap<>();
        for(int i = 0; i < MEN.size(); i++){
            week.put(MEN.get(i), POINTS[i]);
            at.put(MEN.get(i), AT[i]);
        }
        TreeMap<Integer, Map<String, Double>> projected = new TreeMap<>(Map.of(9, week));
        TreeMap<Integer, Map<Position, List<Map.Entry<String, Double>>>> free = new TreeMap<>(Map.of(9, Map.of()));
        return new WeeklyLineupValue(projected, free, at::get, "test").table(MEN);
    }

    static final int[] MINE = {0, 1, 2, 3, 4}, HIS = {5, 6, 7, 8, 9};
    static final double[] NOTHING = new double[MEN.size()];
    static final boolean[] NONE = new boolean[MEN.size()];

    static TradeSearch.Found search(int maxSide, int limit, boolean[] onIr, boolean[] locked, double minGain){
        WeeklyLineupValue.Table t = table();
        double[] season = POINTS.clone();
        TradeSearch.Screen screen = new TradeSearch.Screen(NOTHING, season, season, season, 0);
        return TradeSearch.search(MINE, HIS, onIr, locked, POINTS, new TradeSearch.Prices(t, t, t), screen,
                new TradeSearch.Rules(maxSide, minGain, 0, false, false, limit, 400));
    }

    static List<TradeSearch.Deal> all(TradeSearch.Found found){
        return found.lanes().get(TradeSearch.ANY_LANE);
    }

    @Test
    public void everyShapeOfEveryRosterIsTried(){
        // subsets of 5 men up to 3: 5 + 10 + 10 = 25 a side
        assertEquals(25 * 25, search(3, 99, NONE, NONE, -999).searched());
        assertEquals(25, TradeSearch.combos(MINE, 3).length);
        assertEquals(31, TradeSearch.combos(MINE, 5).length);
    }

    /** The star is the last man of his list and the last in no top-anything: a package can still reach his bench. */
    @Test
    public void aManAnywhereOnTheRosterCanBeInAPackage(){
        boolean found = false;
        for(TradeSearch.Deal d : all(search(3, 99, NONE, NONE, 3))){
            found |= Arrays.equals(d.get, new int[]{7, 9}) && d.give.length == 1;   // his bench man and his star for one of mine
        }
        assertTrue(found, "the 8th-or-lower man is not out of the search");
    }

    /**
     * Over the limit, the side receiving more cuts its lowest-projected man outside the deal: Justin's three-for-two,
     * "1 of the 3 is just their worst bench player, given they would need to drop a player regardless".
     */
    @Test
    public void aSideOverTheLimitCutsItsWorstManOutsideTheDeal(){
        WeeklyLineupValue.Table t = table();
        TradeSearch.Deal threeForOne = null;
        for(TradeSearch.Deal d : all(search(3, 5, NONE, NONE, -999))){
            if(d.give.length == 3 && d.get.length == 2 && has(d.get, 9) && has(d.get, 6)){
                threeForOne = d;
                break;
            }
        }
        assertTrue(threeForOne != null);
        assertEquals(1, threeForOne.hisCut.length);
        assertEquals(8, threeForOne.hisCut[0], "his worst man who is not going to Justin");
        assertEquals(0, threeForOne.myCut.length, "Justin ends a man short, which is legal");
    }

    @Test
    public void nobodyOnIrIsCutAndALockedManNeverMoves(){
        boolean[] ir = new boolean[MEN.size()];
        ir[8] = true;      // his worst man is on IR: cutting him makes no room
        for(TradeSearch.Deal d : all(search(3, 4, ir, NONE, -999))){
            assertFalse(has(d.hisCut, 8), "a man on IR holds no active spot");
        }
        boolean[] locked = new boolean[MEN.size()];
        locked[9] = true;
        for(TradeSearch.Deal d : all(search(3, 5, NONE, locked, -999))){
            assertFalse(has(d.get, 9));
            assertFalse(has(d.hisCut, 9));
        }
    }

    @Test
    public void aGainBelowTheMinimumGoesNoFurther(){
        for(TradeSearch.Deal d : all(search(3, 99, NONE, NONE, 3))){
            assertTrue(d.you >= 3);
        }
    }

    /** A cost-free man thrown in changes nothing on either side, so the trade is one trade, the smaller. */
    @Test
    public void aManThrownInForNothingIsTheSameTrade(){
        Map<String, Integer> seen = new HashMap<>();
        for(TradeSearch.Deal d : all(search(3, 99, NONE, NONE, -999))){
            String key = Math.round(d.you * 10) + "/" + Math.round(d.him * 10);
            int band = Math.max(0, Math.min(TradeSearch.BANDS - 1, (int) (d.you / TradeSearch.BAND)));
            assertEquals(null, seen.put(key + "/" + band, d.men()), key);
        }
    }

    /** The walk stops a bucket at its limit per lane, keeps a trade met twice once, and respects the cap. */
    @Test
    public void theWalkFillsBucketsAndStopsAtTheCap(){
        List<TradeSearch.Deal> lane = new ArrayList<>();
        for(int i = 0; i < 20; i++){
            lane.add(new TradeSearch.Deal(new int[]{i}, new int[]{i}, new int[0], new int[0], 50, -i, 1, 1, 1));
        }
        List<List<TradeSearch.Deal>> lanes = new ArrayList<>();
        for(int l = 0; l < TradeSearch.LANES; l++){
            lanes.add(l < 2 ? lane : List.of());
        }
        TradeSearch.Found found = new TradeSearch.Found(20, 0, 20, lanes);
        TradeSearch.Walked walked = TradeSearch.walk(found, d -> new double[]{0.7, 0.1, -0.2}, 0.5, 12, 3, 999);
        assertEquals(3, walked.kept().size(), "three in the +0.5 bucket, the same three in the second lane");
        assertFalse(walked.capReached());
        TradeSearch.Walked capped = TradeSearch.walk(found, d -> new double[]{0.1, 0.1, 0}, 0.5, 12, 3, 5);
        assertEquals(5, capped.simulated());
        assertTrue(capped.capReached());
        assertEquals(0, capped.kept().size(), "a rise under a half point is in no bucket");
    }

    private static boolean has(int[] men, int m){
        return Arrays.stream(men).anyMatch(x -> x == m);
    }
}
