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
        return TradeSearch.search(MINE, HIS, onIr, locked, POINTS, new TradeSearch.Prices(t, t, t, t), screen,
                new TradeSearch.Rules(maxSide, minGain, 0, false, false, limit, 400, 0, 0));
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
            int band = TradeSearch.band(d.you);
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
        TradeSearch.Found found = new TradeSearch.Found(20, 0, 20, 20, List.of(), lanes);
        TradeSearch.Walked walked = TradeSearch.walk(found, TradeSearch.Deal::key, k -> new double[]{0.7, 0.1, -0.2}, 0.5, 12, 3, 999);
        assertEquals(3, walked.kept().size(), "three in the +0.5 bucket, the same three in the second lane");
        assertFalse(walked.capReached());
        TradeSearch.Walked capped = TradeSearch.walk(found, TradeSearch.Deal::key, k -> new double[]{0.1, 0.1, 0}, 0.5, 12, 3, 5);
        assertEquals(5, capped.simulated());
        assertTrue(capped.capReached());
        assertEquals(15, capped.skipped(), "the rest were never simulated, and say so");
        assertEquals(0, capped.kept().size(), "a rise under a half point is in no bucket");
    }

    /** With the cap far short, every band of gain still gets simulated, and each bucket keeps its least costly from what ran. */
    @Test
    public void theCapReachesTheBigGains(){
        List<TradeSearch.Deal> lane = new ArrayList<>();
        for(int i = 0; i < 100; i++){       // a hundred cheap small gains, all less costly than the one big gain
            lane.add(new TradeSearch.Deal(new int[]{i}, new int[]{i}, new int[0], new int[0], 4, -i, 1, 1, 1));
        }
        lane.add(new TradeSearch.Deal(new int[]{500}, new int[]{500}, new int[0], new int[0], 60, -500, 1, 1, 1));
        List<List<TradeSearch.Deal>> lanes = new ArrayList<>();
        for(int l = 0; l < TradeSearch.LANES; l++){
            lanes.add(l == TradeSearch.ANY_LANE ? lane : List.of());
        }
        TradeSearch.Walked walked = TradeSearch.walk(new TradeSearch.Found(101, 0, 101, 101, List.of(), lanes), TradeSearch.Deal::key,
                k -> new double[]{k.startsWith("[500]") ? 6.2 : 0.7, 0.1, 0}, 0.5, 12, 8, 10);
        assertEquals(10, walked.simulated());
        assertTrue(walked.kept().stream().anyMatch(k -> k.deal().give[0] == 500), "the big gain was simulated although a hundred cost less");
        assertTrue(walked.kept().stream().filter(k -> k.deal().give[0] != 500).allMatch(k -> k.deal().give[0] < 9), "and the small ones are the least costly");
    }

    /** Only the first trade of each outcome is simulated; the rest take its result and do not count against the cap. */
    @Test
    public void anOutcomeAlreadySimulatedIsNotSimulatedAgain(){
        List<TradeSearch.Deal> lane = new ArrayList<>();
        for(int i = 0; i < 20; i++){
            lane.add(new TradeSearch.Deal(new int[]{i}, new int[]{i}, new int[0], new int[0], 50, -i, 1, 1, 1));
        }
        List<List<TradeSearch.Deal>> lanes = new ArrayList<>();
        for(int l = 0; l < TradeSearch.LANES; l++){
            lanes.add(l < 2 ? lane : List.of());
        }
        java.util.concurrent.atomic.AtomicInteger counted = new java.util.concurrent.atomic.AtomicInteger();
        TradeSearch.Walked walked = TradeSearch.walk(new TradeSearch.Found(20, 0, 20, 4, List.of(), lanes), d -> d.give[0] % 4, group -> {
            counted.incrementAndGet();
            return new double[]{0.5 + group, 0.1, 0};
        }, 0.5, 12, 99, 4);
        assertEquals(4, counted.get());
        assertEquals(4, walked.simulated());
        assertEquals(16, walked.reused(), "sixteen trades took another's result; meeting one again in the second lane is not a reuse");
        assertFalse(walked.capReached(), "the cap counts simulations run, and all twenty trades were answered with four");
        assertEquals(20, walked.kept().size(), "each is still its own row");
        for(TradeSearch.Kept kept : walked.kept()){
            assertEquals(0.5 + kept.deal().give[0] % 4, kept.rise());
        }
    }

    /**
     * A second quarterback on each side never starts, so throwing him in leaves both teams' weeks exactly as they
     * were: one outcome, and the smaller trade stands for it. The count is checked against every trade, by hand.
     */
    @Test
    public void aThrowInNobodyStartsIsTheSameTrade(){
        List<String> men = List.of("myQb", "myBackup", "myRb", "hisQb", "hisBackup", "hisRb");
        Position[] at = {Position.QB, Position.QB, Position.RB, Position.QB, Position.QB, Position.RB};
        double[] nine = {20, 1, 5, 21, 2, 15}, ten = {18, 0.5, 7, 19, 1.5, 11};
        Map<String, Position> positions = new HashMap<>();
        Map<String, Double> weekNine = new HashMap<>(), weekTen = new HashMap<>();
        for(int i = 0; i < men.size(); i++){
            positions.put(men.get(i), at[i]);
            weekNine.put(men.get(i), nine[i]);
            weekTen.put(men.get(i), ten[i]);
        }
        WeeklyLineupValue.Table t = new WeeklyLineupValue(new TreeMap<>(Map.of(9, weekNine, 10, weekTen)),
                new TreeMap<>(Map.of(9, Map.of(), 10, Map.of())), positions::get, "test").table(men);
        int[] mine = {0, 1, 2}, his = {3, 4, 5};
        double[] flat = new double[men.size()];
        TradeSearch.Found found = TradeSearch.search(mine, his, new boolean[men.size()], new boolean[men.size()], flat,
                new TradeSearch.Prices(t, t, t, t), new TradeSearch.Screen(flat, flat, flat, flat, 0),
                new TradeSearch.Rules(3, -999, 0, false, false, 99, 400, 0, 50));

        double[][] scratch = t.scratch();
        double[] myWeeks = new double[2], hisWeeks = new double[2];
        java.util.Set<String> outcomes = new java.util.HashSet<>();
        int trades = 0;
        for(int[] give : TradeSearch.combos(mine, 3)){
            for(int[] get : TradeSearch.combos(his, 3)){
                int[] myAfter = java.util.stream.IntStream.concat(Arrays.stream(mine).filter(m -> !has(give, m)), Arrays.stream(get)).toArray();
                int[] hisAfter = java.util.stream.IntStream.concat(Arrays.stream(his).filter(m -> !has(get, m)), Arrays.stream(give)).toArray();
                t.of(myAfter, myAfter.length, scratch, myWeeks);
                t.of(hisAfter, hisAfter.length, scratch, hisWeeks);
                outcomes.add(Arrays.toString(myWeeks) + Arrays.toString(hisWeeks));
                trades++;
            }
        }
        assertEquals(trades, found.passed());
        assertEquals(outcomes.size(), found.outcomes());
        assertTrue(found.outcomes() < trades, "the throw-ins leave something to skip");

        List<TradeSearch.Deal> any = all(found);
        assertEquals(outcomes.size(), any.size(), "one row an outcome");
        // my RB for his RB, with or without the backups changing hands, reads the same: the one-for-one stands for them all
        assertTrue(any.stream().anyMatch(d -> Arrays.equals(d.give, new int[]{2}) && Arrays.equals(d.get, new int[]{5})));
        assertFalse(any.stream().anyMatch(d -> Arrays.equals(d.give, new int[]{1, 2}) && Arrays.equals(d.get, new int[]{5})));

        assertFalse(found.merged().isEmpty());
        for(TradeSearch.Deal[] pair : found.merged()){
            assertEquals(pair[0].you, pair[1].you, "a pair handed back for testing is a pair the search treated as one");
            assertEquals(pair[0].him, pair[1].him);
        }
    }

    /** Past the cap, a trade can still take a result already due; only those needing a new simulation go unanswered. */
    @Test
    public void pastTheCapATradeStillTakesAKnownResult(){
        List<TradeSearch.Deal> lane = new ArrayList<>();
        for(int i = 0; i < 4; i++){
            lane.add(new TradeSearch.Deal(new int[]{i}, new int[]{i}, new int[0], new int[0], 50, -i, 1, 1, 1));
        }
        List<List<TradeSearch.Deal>> lanes = new ArrayList<>();
        for(int l = 0; l < TradeSearch.LANES; l++){
            lanes.add(l == TradeSearch.ANY_LANE ? lane : List.of());
        }
        String[] outcome = {"a", "b", "c", "a"};
        TradeSearch.Walked walked = TradeSearch.walk(new TradeSearch.Found(4, 0, 4, 3, List.of(), lanes), d -> outcome[d.give[0]],
                same -> new double[]{1.2, 0.1, 0}, 0.5, 12, 99, 2);
        assertEquals(2, walked.simulated());
        assertTrue(walked.capReached());
        assertEquals(1, walked.reused());
        assertEquals(1, walked.skipped(), "c alone never ran");
        assertEquals(3, walked.kept().size());
    }

    /** Three non-round weeks, a man whose only week is the first, and every trade's fingerprints compared with the exact vectors. */
    private static WeeklyLineupValue.Table threeWeeks(List<String> men, Position[] at, double[][] byWeek){
        Map<String, Position> positions = new HashMap<>();
        TreeMap<Integer, Map<String, Double>> projected = new TreeMap<>();
        TreeMap<Integer, Map<Position, List<Map.Entry<String, Double>>>> free = new TreeMap<>();
        for(int i = 0; i < men.size(); i++){
            positions.put(men.get(i), at[i]);
        }
        for(int w = 0; w < byWeek.length; w++){
            Map<String, Double> week = new HashMap<>();
            for(int i = 0; i < men.size(); i++){
                if(byWeek[w][i] == byWeek[w][i]){
                    week.put(men.get(i), byWeek[w][i]);
                }
            }
            projected.put(9 + w, week);
            free.put(9 + w, Map.of());
        }
        return new WeeklyLineupValue(projected, free, positions::get, "test").table(men);
    }

    /**
     * Once this week is under way a roster can't change it in the simulation, so trades differing only this week need
     * one simulation between them, while each keeps its own row. Non-round points, because a week's points read off a
     * running total would carry rounding from the weeks before it and count the same season twice.
     */
    @Test
    public void aTradeDifferingOnlyThisWeekIsOneSimulationAndItsOwnRow(){
        List<String> men = List.of("myQb", "myRb", "myNow", "myNow2", "hisQb", "hisRb", "hisNow", "hisNow2");
        Position[] at = {Position.QB, Position.RB, Position.RB, Position.RB, Position.QB, Position.RB, Position.RB, Position.RB};
        double no = Double.NaN;
        double[][] byWeek = {
                {20.37, 7.19, 3.11, 2.73, 21.93, 15.71, 4.57, 1.91},
                {18.43, 6.61, no, no, 19.17, 11.83, no, no},
                {17.29, 8.03, no, no, 16.47, 12.59, no, no}};
        WeeklyLineupValue.Table t = threeWeeks(men, at, byWeek);
        int[] mine = {0, 1, 2, 3}, his = {4, 5, 6, 7};
        double[] flat = new double[men.size()];
        TradeSearch.Found found = TradeSearch.search(mine, his, new boolean[men.size()], new boolean[men.size()], flat,
                new TradeSearch.Prices(t, t, t, t), new TradeSearch.Screen(flat, flat, flat, flat, 0),
                new TradeSearch.Rules(3, -999, 0, false, false, 99, 400, 1, 0));
        double[][] scratch = t.scratch();
        double[] myWeeks = new double[3], hisWeeks = new double[3];
        java.util.Set<String> later = new java.util.HashSet<>(), allWeeks = new java.util.HashSet<>();
        for(int[] give : TradeSearch.combos(mine, 3)){
            for(int[] get : TradeSearch.combos(his, 3)){
                int[] myAfter = java.util.stream.IntStream.concat(Arrays.stream(mine).filter(m -> !has(give, m)), Arrays.stream(get)).toArray();
                int[] hisAfter = java.util.stream.IntStream.concat(Arrays.stream(his).filter(m -> !has(get, m)), Arrays.stream(give)).toArray();
                t.of(myAfter, myAfter.length, scratch, myWeeks);
                t.of(hisAfter, hisAfter.length, scratch, hisWeeks);
                // to the millionth, so the expected count can't share any rounding with the count it checks
                later.add(rounded(myWeeks, 1) + rounded(hisWeeks, 1));
                allWeeks.add(rounded(myWeeks, 0) + rounded(hisWeeks, 0));
            }
        }
        assertTrue(later.size() < allWeeks.size(), "this week's men leave something for the simulated count to collapse");
        assertEquals(later.size(), found.outcomes());
        assertEquals(allWeeks.size(), all(found).size(), "one row for each different set of weeks, this one included");
    }

    private static String rounded(double[] weeks, int from){
        return Arrays.toString(Arrays.stream(weeks).skip(from).mapToLong(p -> Math.round(p * 1e6)).toArray());
    }

    /** With -Pprojections on another pricing, what a trade looks like there can't merge what the simulation tells apart. */
    @Test
    public void aThrowInWhoStartsInTheSimulationIsNotADuplicate(){
        List<String> men = List.of("myQb", "myBackup", "myRb", "hisQb", "hisBackup", "hisRb");
        Position[] at = {Position.QB, Position.QB, Position.RB, Position.QB, Position.QB, Position.RB};
        // on the pricing that finds trades, my backup never starts; in the simulation he is my starter
        WeeklyLineupValue.Table finds = threeWeeks(men, at, new double[][]{{20, 1, 5, 21, 2, 15}});
        WeeklyLineupValue.Table simulated = threeWeeks(men, at, new double[][]{{20, 22, 5, 21, 2, 15}});
        int[] mine = {0, 1, 2}, his = {3, 4, 5};
        double[] flat = new double[men.size()];
        TradeSearch.Found same = TradeSearch.search(mine, his, new boolean[men.size()], new boolean[men.size()], flat,
                new TradeSearch.Prices(finds, finds, finds, finds), new TradeSearch.Screen(flat, flat, flat, flat, 0),
                new TradeSearch.Rules(3, -999, 0, false, false, 99, 400, 0, 0));
        TradeSearch.Found apart = TradeSearch.search(mine, his, new boolean[men.size()], new boolean[men.size()], flat,
                new TradeSearch.Prices(finds, finds, finds, simulated), new TradeSearch.Screen(flat, flat, flat, flat, 0),
                new TradeSearch.Rules(3, -999, 0, false, false, 99, 400, 0, 0));
        java.util.function.Predicate<TradeSearch.Deal> rbAndBackup = d -> Arrays.equals(d.give, new int[]{1, 2}) && Arrays.equals(d.get, new int[]{5});
        assertFalse(all(same).stream().anyMatch(rbAndBackup), "on one pricing the backup changes nothing");
        assertTrue(all(apart).stream().anyMatch(rbAndBackup), "the simulation can tell these apart, so the row stays");
    }

    private static boolean has(int[] men, int m){
        return Arrays.stream(men).anyMatch(x -> x == m);
    }
}
