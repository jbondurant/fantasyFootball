import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 2026-10-03 tools: title odds (TitleOdds, and the rival sides TradeMarket
 * builds from them), the waiver market's need (FaabRivals), the injury base
 * rates (PlayProbability) and the lineup that wins the week (WinProbability).
 */
public class TitleOddsTest {

    /** Six teams, one regular week, then the bracket in weeks 2-4. */
    static TitleOdds.League league(double spread){
        List<String> managers = List.of("A", "B", "C", "D", "E", "F");
        Map<Integer, String> managerOf = new TreeMap<>();
        Map<String, Integer> wins = new TreeMap<>();
        for(int i = 0; i < 6; i++){
            managerOf.put(i + 1, managers.get(i));
            wins.put(managers.get(i), 0);
        }
        Map<Integer, List<int[]>> schedule = Map.of(1, List.of(new int[]{1, 2}, new int[]{3, 4}, new int[]{5, 6}));
        return new TitleOdds.League("2026", 1, 1, 2, 6, true, spread, managers, managerOf, wins, new TreeMap<>(), schedule, Map.of(), 0.0);
    }

    static Map<String, double[]> flat(double a){
        Map<String, double[]> means = new HashMap<>();
        for(String m : List.of("A", "B", "C", "D", "E", "F")){
            means.put(m, new double[]{100, 100, 100, 100});
        }
        means.put("A", new double[]{a, a, a, a});
        return means;
    }

    @Test
    public void aTeamFarBetterThanTheRestWinsTheTitle(){
        TitleOdds.Outcomes o = TitleOdds.play(league(10), flat(1_000), 4_000, 7L);
        assertTrue(o.title("A") > 0.99, "A scores ten times the rest: " + o.title("A"));
        assertEquals(1.0, o.playoffs("A"), 1e-9, "all six make a six-team bracket");
        assertEquals(1.0, o.bye("A"), 1e-9, "and the best record takes a bye");
        double total = 0;
        for(String m : List.of("A", "B", "C", "D", "E", "F")){
            total += o.title(m);
        }
        assertEquals(1.0, total, 1e-9, "one champion a season");
    }

    @Test
    public void theSameDrawsGiveTheSameSeasonSoANothingMoveIsWorthNothing(){
        TitleOdds.Outcomes a = TitleOdds.play(league(25), flat(110), 4_000, 42L);
        TitleOdds.Outcomes b = TitleOdds.play(league(25), flat(110), 4_000, 42L);
        TitleOdds.Delta d = TitleOdds.title(a, b, "A");
        assertEquals(0.0, d.change(), 1e-12);
        assertEquals(0.0, d.se(), 1e-12);
        TitleOdds.Delta up = TitleOdds.title(a, TitleOdds.play(league(25), flat(130), 4_000, 42L), "A");
        assertTrue(up.change() > 0 && up.change() > 3 * up.se(), "twenty points a week more is more titles: " + up);
    }

    /** A week under way: what has been scored is settled, and the spread is only the share still to play. */
    @Test
    public void aFinishedLiveWeekIsSettledExactly(){
        TitleOdds.League base = league(25);
        Map<String, double[]> live = new HashMap<>();
        for(String m : List.of("A", "B", "C", "D", "E", "F")){
            live.put(m, new double[]{100, 0});
        }
        live.put("B", new double[]{140, 0});         // B beat A, finished
        TitleOdds.League done = new TitleOdds.League("2026", 1, 1, 2, 6, true, 25, base.managers(), base.managerOf(),
                base.wins(), base.banked(), base.schedule(), live, 0.0);
        assertEquals(0.0, done.liveSpread("A"), 1e-12, "nothing left to play, nothing left to draw");
        live.put("C", new double[]{50, 50});
        assertEquals(25 * Math.sqrt(0.5), done.liveSpread("C"), 1e-12, "half the lineup still to play: the spread of half a week");
        assertEquals(25, base.liveSpread("A"), 1e-12, "no live read: the whole week is drawn");
        Map<String, double[]> means = flat(100);
        for(String m : means.keySet()){
            TitleOdds.liveWeek(done, m, means.get(m));
        }
        assertEquals(140, means.get("B")[0], 1e-12, "this week's mean is what was scored plus what is still to play");
        assertEquals(100, means.get("B")[1], 1e-12, "and next week is still the projection");
    }

    /**
     * A team error drawn once a season pulls a projected favourite back toward
     * the field: a 20-point edge a week is a near-certain title when the
     * projection is exactly right and much less so when it can be off by 15.
     */
    @Test
    public void aTeamErrorPullsAFavouriteTowardTheField(){
        TitleOdds.League sure = league(10);
        TitleOdds.League unsure = new TitleOdds.League("2026", 1, 1, 2, 6, true, 10, sure.managers(), sure.managerOf(),
                sure.wins(), sure.banked(), sure.schedule(), Map.of(), 15.0);
        double certain = TitleOdds.play(sure, flat(120), 8_000, 3L).title("A");
        double doubted = TitleOdds.play(unsure, flat(120), 8_000, 3L).title("A");
        assertTrue(doubted < certain - 0.05, "a 15-point team error must cost the favourite: " + certain + " -> " + doubted);
        assertTrue(doubted > 1.0 / 6, "and still leave him the favourite: " + doubted);
    }

    @Test
    public void theTeamErrorTableIsReadFromTheNearestMeasuredWeek(){
        java.util.TreeMap<Integer, Double> table = new java.util.TreeMap<>(Map.of(2, 7.9, 5, 8.3, 8, 10.6));
        assertEquals(8.3, TeamError.sigmaFrom(5, table), 1e-12);
        assertEquals(8.3, TeamError.sigmaFrom(6, table), 1e-12, "between measured weeks: the last one measured");
        assertEquals(7.9, TeamError.sigmaFrom(1, table), 1e-12, "before the first: the first");
        assertEquals(0.0, TeamError.sigmaFrom(5, new java.util.TreeMap<>()), 1e-12, "no table: no team error");
        // twelve teams, misses of +-10 over 10 weeks with a 23.7 spread: (12/11)*100 - 56.2 = 52.9
        assertEquals(Math.sqrt(12.0 / 11 * 100 - 23.7 * 23.7 / 10), TeamError.sigma(List.of(10.0, -10.0), 12, 10, 23.7), 1e-9);
        assertEquals(0.0, TeamError.sigma(List.of(1.0, -1.0), 12, 10, 23.7), 1e-12, "noise alone explains it: floored at zero");
    }

    @Test
    public void aTieGoesToTheHigherSeedAndTheMedianIsTheMiddle(){
        assertEquals(0, TitleOdds.winner(0, 1, new double[]{90, 90}));
        assertEquals(1, TitleOdds.winner(0, 1, new double[]{90, 91}));
        assertEquals(2.5, TitleOdds.median(new double[]{4, 1, 3, 2}), 1e-12);
        assertEquals(3.0, TitleOdds.median(new double[]{5, 1, 3}), 1e-12);
    }

    /** A rival out of it weighs season points at nothing and feels a keeper he receives; a keeper he gives up costs him all of it. */
    @Test
    public void aRivalSideWeighsHisSeasonAndHisKeepers(){
        Map<String, Double> worth = Map.of("starter", 50.0, "bench", 10.0, "keeperA", 20.0, "keeperB", 15.0, "mine", 30.0);
        java.util.function.ToDoubleFunction<List<String>> season = ids -> ids.stream().mapToDouble(id -> worth.getOrDefault(id, 0.0)).sum();
        Map<String, Double> surplus = Map.of("keeperA", 40.0, "mine", 25.0);
        Map<String, Position> positionOf = Map.of("starter", Position.RB, "bench", Position.WR, "keeperA", Position.RB,
                "keeperB", Position.WR, "mine", Position.WR);
        List<String> his = List.of("starter", "bench", "keeperA");
        // he gives "bench" (10) for "mine" (30, a 25-point keeper): +20 season, +25 keeper received
        TradeMarket.Side contender = TradeMarket.rivalSide(season, surplus, positionOf, 1.5, 0.0);
        TradeMarket.Side out = TradeMarket.rivalSide(season, surplus, positionOf, 0.0, 1.0);
        double keeperGain = TradeMarket.keeperValue(List.of("starter", "keeperA", "mine"), surplus, positionOf)
                - TradeMarket.keeperValue(List.of("starter", "keeperA"), surplus, positionOf);
        assertEquals(1.5 * 20, contender.gain(his, List.of("bench"), List.of("mine")), 1e-9, "a contender: his season, weighted; no keeper credit");
        assertEquals(keeperGain, out.gain(his, List.of("bench"), List.of("mine")), 1e-9, "out of it: the keeper he receives, his season ignored");
        // giving up his keeper costs both of them the full drop in his best two
        double loss = TradeMarket.keeperValue(his, surplus, positionOf) - TradeMarket.keeperValue(List.of("starter", "bench"), surplus, positionOf);
        assertEquals(1.5 * (15 - 20) - loss, contender.gain(his, List.of("keeperA"), List.of("keeperB")), 1e-9);
    }

    // ------------------------------------------------------------------ FaabRivals

    @Test
    public void eachManagersNeedIsHisRosterWithTheTargetInForHisWorstMan(){
        Map<String, Double> points = Map.of("a1", 100.0, "a2", 10.0, "b1", 100.0, "b2", 90.0, "x", 50.0);
        java.util.function.ToDoubleFunction<List<String>> value = ids -> ids.stream().mapToDouble(points::get).sum();
        Map<String, List<String>> active = new TreeMap<>(Map.of("A", List.of("a1", "a2"), "B", List.of("b1", "b2")));
        Map<String, Double> base = Map.of("A", 110.0, "B", 190.0);
        List<FaabRivals.Need> needs = FaabRivals.needs("x", active, points, value, base);
        assertEquals("A", needs.get(0).manager(), "A cuts a 10 for a 50");
        assertEquals(40.0, needs.get(0).gain(), 1e-9);
        assertEquals("a2", needs.get(0).cut());
        assertEquals(-40.0, needs.get(1).gain(), 1e-9, "B would cut a 90 for him");
        FaabRivals.Market market = new FaabRivals.Market("x", 5, needs);
        assertEquals(1, market.bidders(11.3));
    }

    // ------------------------------------------------------------------ PlayProbability

    @Test
    public void thePlayTableCountsEachStatusAndPracticeLine(){
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Questionable", "Full Participation in Practice", "true"});
        rows.add(new String[]{"Questionable", "Did Not Participate In Practice", "false"});
        rows.add(new String[]{"Questionable", "Limited Participation in Practice", "true"});
        rows.add(new String[]{"", "Full Participation in Practice", "true"});
        Map<String, PlayProbability.Cell> t = PlayProbability.table(rows);
        assertEquals(3, t.get("Questionable|all").listed());
        assertEquals(2.0 / 3, t.get("Questionable|all").rate(), 1e-12);
        assertEquals(0, t.get("Questionable|DNP").played());
        assertEquals(1, t.get("not listed|Full").played());
        double[] w = t.get("Questionable|all").wilson();
        assertTrue(w[0] < 2.0 / 3 && 2.0 / 3 < w[1], "the interval brackets the rate");
        assertEquals("82%", PlayProbability.label("Questionable", Map.of("Questionable", 0.822)));
        assertEquals("-", PlayProbability.label(null, Map.of("Questionable", 0.822)));
    }

    // ------------------------------------------------------------------ WinProbability

    static Map<String, Position> positions(){
        Map<String, Position> p = new HashMap<>();
        p.put("qb", Position.QB);
        p.put("qb2", Position.QB);
        p.put("def", Position.DEF);
        for(String id : List.of("rb1", "rb2", "rb3", "rb4")) p.put(id, Position.RB);
        for(String id : List.of("wr1", "wr2", "wr3", "wr4")) p.put(id, Position.WR);
        for(String id : List.of("te1", "te2")) p.put(id, Position.TE);
        return p;
    }

    @Test
    public void aLineupIsLegalOnlyIfItFillsEverySlot(){
        Map<String, Position> p = positions();
        List<String> ok = List.of("qb", "rb1", "rb2", "wr1", "wr2", "wr3", "te1", "rb3", "wr4", "def");
        assertTrue(WinProbability.legal(ok, p));
        assertFalse(WinProbability.legal(List.of("qb", "qb2", "rb2", "wr1", "wr2", "wr3", "te1", "rb3", "wr4", "def"), p), "two quarterbacks");
        assertTrue(WinProbability.legal(List.of("qb", "rb1", "rb3", "wr1", "wr2", "wr3", "te1", "te2", "wr4", "def"), p),
                "two backs, four receivers and two ends fill the flexes");
        assertFalse(WinProbability.legal(List.of("qb", "rb1", "wr1", "wr2", "wr3", "wr4", "te1", "te2", "def"), p), "nine men");
        List<List<String>> c = WinProbability.candidates(ok, List.of("rb4", "te2", "qb2"), Set.of("rb1"), p);
        assertEquals(ok, c.get(0));
        for(List<String> lineup : c){
            assertTrue(WinProbability.legal(lineup, p), "every candidate is legal: " + lineup);
            assertTrue(lineup.contains("rb1"), "a man whose game has started cannot be moved");
        }
        assertTrue(c.stream().anyMatch(l -> l.contains("qb2") && !l.contains("qb")), "the backup quarterback for the starter");
    }

    @Test
    public void expectedWinsCountTheGameAndTheMedian(){
        // two candidates, two drawn weeks; opponent 100 both weeks; the league's other ten at 90 and 120
        double[][] mine = {{110, 95}, {130, 80}};
        double[] opponent = {100, 100};
        double[][] others = new double[10][2];
        for(int o = 0; o < 10; o++){
            others[o][0] = o < 5 ? 90 : 120;
            others[o][1] = o < 5 ? 90 : 120;
        }
        double[][] wins = WinProbability.expectedWins(mine, opponent, others);
        // week 1: 110 beats 100 and the median of {90x5, 100, 110, 120x5}, 105: two wins; week 2: 95 loses to 100 and to 97.5: none
        assertEquals(1.0, wins[0][0], 1e-9);
        // 130 beats 100 and the median 110: two; 80 loses to 100 and to 95: none
        assertEquals(1.0, wins[1][0], 1e-9);
    }

    @Test
    public void aDrawIsTheProjectionPlusANearbyResidual(){
        List<double[]> pairs = new ArrayList<>();
        for(int i = 0; i < 100; i++){
            pairs.add(new double[]{i, i + (i < 50 ? -5 : 5)});      // under-shoot below 50, over-shoot above
        }
        WinProbability.Residuals r = WinProbability.residuals(pairs);
        Random random = new Random(1);
        for(int k = 0; k < 50; k++){
            assertEquals(85.0, r.draw(80, random, 20), 1e-9, "a man projected 80 draws from men projected 70-89, all +5");
            assertEquals(5.0, r.draw(10, random, 20), 1e-9, "and one projected 10 from men all -5");
        }
    }
}
