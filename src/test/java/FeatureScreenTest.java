import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The screen's machinery on data whose answer is known: the Clark-West test
 * finds a planted effect and is centred on noise, the clustered variance, the
 * centering, the two multiple-testing rules, the placebos, the shrinkage, and
 * the registry's own consistency.
 */
public class FeatureScreenTest {

    /** A deterministic standard-normal-ish draw: twelve seeded uniforms. */
    static double normal(int seed, int i){
        double s = 0;
        for(int k = 0; k < 12; k++){
            s += FeatureScreen.placebo(seed * 131 + k, "row" + i);
        }
        return s / 2;
    }

    /** Five seasons x 16 weeks x 60 rows; y = 1 + 0.9 p + 6 e, plus {@code effect} * x0 * p. */
    static FeatureScreen.Pos synthetic(double effect, int candidates){
        int n = 5 * 16 * 60;
        double[] p = new double[n];
        double[] y = new double[n];
        String[] season = new String[n];
        int[] seasonWeek = new int[n];
        int[] teamSeason = new int[n];
        int[] player = new int[n];
        double[][] raw = new double[candidates][n];
        for(int i = 0; i < n; i++){
            int s = i / (16 * 60);
            int w = (i / 60) % 16;
            season[i] = String.valueOf(2018 + s);
            seasonWeek[i] = s * 16 + w;
            teamSeason[i] = s * 32 + i % 32;
            player[i] = s * 200 + i % 200;
            p[i] = 15 + 10 * FeatureScreen.placebo(999, "p" + i);
            for(int j = 0; j < candidates; j++){
                raw[j][i] = FeatureScreen.placebo(1000 + j, "x" + i);
            }
            y[i] = 1 + 0.9 * p[i] + 6 * normal(7, i) + effect * raw[0][i] * p[i];
        }
        double[][] x = new double[candidates][];
        for(int j = 0; j < candidates; j++){
            x[j] = FeatureScreen.centered(raw[j], seasonWeek);
        }
        return new FeatureScreen.Pos(Position.WR, p, y, season, seasonWeek, teamSeason, player, x);
    }

    @Test
    public void aPlantedProportionalEffectIsFoundHeldOutAndItsSizeRecovered(){
        FeatureScreen.Pos d = synthetic(0.15, 1);
        FeatureScreen.Outcome o = FeatureScreen.test(d, new double[0][], d.x[0], d.teamSeason, FeatureScreen.DISCOVERY, null);
        assertTrue(o.z() > 5, "a strong effect is found: z " + o.z());
        assertEquals(0.15, o.c(), 0.03, "and its coefficient recovered");
        assertTrue(o.rmseX() < o.rmseB());
        assertEquals(5, o.seasonsBetter(), "it helps every held-out season");
    }

    @Test
    public void onNoiseTheClarkWestZIsCentredWithUnitSpread(){
        // forty candidates that are pure noise: raw squared-error gain would sit
        // below zero for every one of them; the adjusted statistic must not
        FeatureScreen.Pos d = synthetic(0, 41);
        List<Double> zs = new ArrayList<>();
        for(int j = 1; j < 41; j++){
            zs.add(FeatureScreen.test(d, new double[0][], d.x[j], d.teamSeason, FeatureScreen.DISCOVERY, null).z());
        }
        double mean = zs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sd = Math.sqrt(zs.stream().mapToDouble(z -> (z - mean) * (z - mean)).sum() / (zs.size() - 1));
        // pooled leave-one-season-out z's sit a little below zero with sd a little above one even with right
        // errors - which is why each level's null is measured from placebos rather than assumed
        assertTrue(mean > -1 && mean < 0.5, "near-centred: mean z " + mean);
        assertTrue(sd > 0.6 && sd < 1.8, "near-unit spread: sd " + sd);
        FeatureScreen.Null measured = FeatureScreen.calibrate(zs);
        assertEquals(mean, measured.mean(), 1e-9);
        assertEquals(sd, measured.sd(), 1e-9);
        long beyond = zs.stream().filter(z -> measured.adjust(z) > 1.645).count();
        assertTrue(beyond <= 6, "the standardised upper tail is about 5%: " + beyond + " of 40");
    }

    @Test
    public void theConfirmationPathFitsOnDiscoveryAndScoresOnlyTheUnseenSeasons(){
        FeatureScreen.Pos d = synthetic(0.15, 1);
        FeatureScreen.Outcome o = FeatureScreen.test(d, new double[0][], d.x[0], d.teamSeason,
                List.of("2018", "2019", "2020"), List.of("2021", "2022"));
        assertEquals(2 * 16 * 60, o.n(), "only the two held-back seasons are scored");
        assertTrue(o.z() > 4);
    }

    @Test
    public void theModelRecoversItsCoefficients(){
        int n = 400;
        double[] p = new double[n];
        double[] y = new double[n];
        double[] x = new double[n];
        boolean[] train = new boolean[n];
        double pbar = 0;
        for(int i = 0; i < n; i++){
            p[i] = 5 + (i % 40);
            x[i] = (i % 7) - 3;
            pbar += p[i];
        }
        pbar /= n;
        for(int i = 0; i < n; i++){
            double q = p[i] - pbar;
            y[i] = 1.5 + 0.8 * p[i] + 0.01 * q * q + 0.04 * x[i] * p[i];
            train[i] = true;
        }
        FeatureScreen.Model m = FeatureScreen.fit(p, y, train, new double[][]{x});
        assertArrayEquals(new double[]{1.5, 0.8, 0.01, 0.04}, m.beta(), 1e-6);
        assertEquals(y[17], m.predict(p[17], new double[][]{x}, 17), 1e-6);
    }

    @Test
    public void twoWayClusteringReducesToOneWayWhenTheTwoAgree(){
        double[] f = {1, 2, 3, 4, 5, 6};
        int[] a = {0, 0, 1, 1, 2, 2};
        double mean = 3.5;
        double one = FeatureScreen.oneWay(f, a, null, mean);
        assertEquals(one, FeatureScreen.twoWayVariance(f, a, a)[0], 1e-12, "A = B: V_A + V_A - V_A");
        // cluster sums of residuals: -4, 0, 4 -> G/(G-1) * 32 / 36
        assertEquals(1.5 * 32 / 36, one, 1e-12);
        int[] singles = {0, 1, 2, 3, 4, 5};
        double hc = FeatureScreen.oneWay(f, singles, null, mean);
        assertEquals(6.0 / 5 * 17.5 / 36, hc, 1e-12, "singleton clusters are the robust variance");
        assertEquals(2, FeatureScreen.twoWayVariance(f, a, singles)[1], 1e-12, "df = fewest clusters - 1");
    }

    @Test
    public void centeringRemovesEachGroupsObservedMeanAndMissingBecomesZero(){
        double[] raw = {1, 3, Double.NaN, 10, 20};
        int[] group = {0, 0, 0, 1, 1};
        assertArrayEquals(new double[]{-1, 1, 0, -5, 5}, FeatureScreen.centered(raw, group), 1e-12);
    }

    @Test
    public void benjaminiHochbergAndHolmAgreeWithTheirHandWorkedCases(){
        // BH at 0.10 over four: thresholds .025 .05 .075 .10; sorted .01 .03 .04 .20 -> first three
        assertArrayEquals(new boolean[]{true, true, true, false},
                FeatureScreen.benjaminiHochberg(new double[]{0.01, 0.04, 0.03, 0.20}, 0.10));
        // step-up: .06 at rank 3 of 3 passes (<= .10), so all ranks below it pass too
        assertArrayEquals(new boolean[]{true, true, true},
                FeatureScreen.benjaminiHochberg(new double[]{0.06, 0.05, 0.04}, 0.10));
        assertArrayEquals(new boolean[]{true, true, true}, FeatureScreen.holm(new double[]{0.04, 0.01, 0.02}, 0.05));
        assertArrayEquals(new boolean[]{false, false}, FeatureScreen.holm(new double[]{0.03, 0.04}, 0.05),
                "the smallest must beat alpha/2");
        assertArrayEquals(new boolean[]{true, false}, FeatureScreen.holm(new double[]{0.01, 0.06}, 0.05), "step-down stops");
    }

    @Test
    public void placebosAreSeededUniformAndDistinctBySeed(){
        assertEquals(FeatureScreen.placebo(3, "2019_05_KC_DEN"), FeatureScreen.placebo(3, "2019_05_KC_DEN"));
        assertNotEquals(FeatureScreen.placebo(3, "2019_05_KC_DEN"), FeatureScreen.placebo(4, "2019_05_KC_DEN"));
        double sum = 0;
        for(int i = 0; i < 20000; i++){
            double u = FeatureScreen.placebo(5, "unit" + i);
            assertTrue(u >= -1 && u <= 1);
            sum += u;
        }
        assertEquals(0, sum / 20000, 0.02, "centred on zero");
    }

    @Test
    public void derSimonianLairdFindsSpreadOnlyWhereThereIsSome(){
        List<double[]> same = List.of(new double[]{0, 0.10, 0.01}, new double[]{1, 0.10, 0.01}, new double[]{2, 0.10, 0.01});
        assertEquals(0, FeatureScreen.derSimonianLaird(same)[0], 1e-12, "identical slopes: no between-player variance");
        List<double[]> apart = List.of(new double[]{0, -0.5, 0.01}, new double[]{1, 0.0, 0.01}, new double[]{2, 0.5, 0.01});
        double[] dl = FeatureScreen.derSimonianLaird(apart);
        // Q = (0.25 + 0 + 0.25) / 0.01 = 50; S1 = 300, S2 = 30000 -> tau2 = (50 - 2) / (300 - 100)
        assertEquals(50, dl[1], 1e-9);
        assertEquals(48.0 / 200, dl[0], 1e-9);
        assertEquals(0.5, FeatureScreen.chiSquareCdf(9.342, 10), 0.01, "the chi-square(10) median");
    }

    @Test
    public void theRegistryIsConsistentAndItsFingerprintStable(){
        Set<String> keys = new HashSet<>();
        for(FeatureScreen.Feature f : FeatureScreen.REGISTRY){
            assertTrue(keys.add(f.key()), "one key once: " + f.key());
            assertFalse(f.positions().isEmpty(), f.key());
            for(String also : f.alsoInBaseline()){
                FeatureScreen.index(also);
            }
        }
        assertTrue(FeatureScreen.REGISTRY.get(FeatureScreen.index("V1")).market());
        assertTrue(FeatureScreen.REGISTRY.get(FeatureScreen.index("V2")).market());
        assertFalse(FeatureScreen.REGISTRY.get(FeatureScreen.index("G16")).market(), "party city is tested net of the line");
        assertEquals(FeatureScreen.fingerprint(), FeatureScreen.fingerprint());
        assertEquals(16, FeatureScreen.fingerprint().length());
        assertEquals(List.of("2018", "2019", "2020", "2021", "2022"), FeatureScreen.DISCOVERY);
        assertTrue(FeatureScreen.CONFIRMATION.stream().allMatch(s -> s.compareTo("2022") > 0), "confirmation seasons come after");
    }

    static NflverseGames.Side side(String team, String home, boolean isHome, String roof, Double wind, Double temp, String time){
        return new NflverseGames.Side("g", "2024", 6, team, isHome ? "BUF" : home, isHome, false, "2024-10-13", "Sunday", time,
                3.0, 45.0, 7, 7, false, roof, "grass", temp, wind, null, "coach", "other", "stad", isHome ? team : home, 7.0);
    }

    static ScreenData.Row row(NflverseGames.Side side){
        ScreenData.Line line = new ScreenData.Line("1", side.team(), side.opponent(), side.gameday(), Position.WR, "Doe",
                true, true, 0, 0, 5, 3, 0, 0, 0, 0, 40, 9.0);
        return new ScreenData.Row("2024", 6, line, 10, side);
    }

    static double value(String key, NflverseGames.Side side){
        return FeatureScreen.REGISTRY.get(FeatureScreen.index(key)).value().of(row(side), null);
    }

    @Test
    public void gameConditionFeaturesReadTheScheduleAsRegistered(){
        assertEquals(0, value("G1", side("SEA", "NO", false, "dome", null, null, "13:00")), "a roof is calm");
        assertEquals(8, value("G1", side("SEA", "CHI", false, "outdoors", 20.0, 40.0, "13:00")));
        assertTrue(Double.isNaN(value("G1", side("SEA", "CHI", false, "outdoors", null, null, "13:00"))), "unrecorded is unknown, not calm");
        assertEquals(15, value("G2", side("SEA", "CHI", false, "outdoors", 5.0, 35.0, "13:00")));
        assertEquals(1, value("G3", side("SEA", "ATL", false, "closed", null, null, "13:00")));
        assertEquals(0, value("G3", side("SEA", "ARI", false, "open", null, 80.0, "13:00")), "an open roof is outdoors");
        assertEquals(1, value("G16", side("SEA", "MIA", false, "outdoors", 5.0, 80.0, "13:00")), "road game at Miami");
        assertEquals(0, value("G16", side("MIA", "MIA", true, "outdoors", 5.0, 80.0, "13:00")), "Miami at home is not a road trip");
        assertEquals(1, value("G5", side("KC", "DEN", false, "outdoors", 5.0, 60.0, "16:25")));
        assertEquals(3, value("A9", side("SEA", "NE", false, "outdoors", 5.0, 60.0, "13:00")), "Seattle at 1pm in Foxborough");
        assertEquals(0, value("A9", side("SEA", "NE", false, "outdoors", 5.0, 60.0, "16:25")));
        assertEquals(0, value("A9", side("SEA", "KC", false, "outdoors", 5.0, 60.0, "13:00")), "Kansas City is not Eastern");
        assertEquals(3, value("A10", side("SEA", "NE", false, "outdoors", 5.0, 60.0, "20:20")));
        assertEquals(1, value("G11", side("SEA", "NE", false, "outdoors", 5.0, 60.0, "20:20")));
        assertEquals(3.0, value("V2", side("SEA", "NE", false, "outdoors", 5.0, 60.0, "13:00")), "the side's own spread");
    }

    // ------------------------------------------------------------ fixes from the pre-run code review

    static ScreenData.Line wr(String id, String team, int touches, boolean played){
        return new ScreenData.Line(id, team, "MIA", "2022-10-02", Position.WR, id, played, false, 0, 0, touches, touches / 2.0,
                0, 0, 0, 0, 0, 5);
    }

    static ScreenData.Context context(Map<String, ScreenData.Season> seasons, List<ScreenData.Row> rows){
        return new ScreenData.Context(seasons, new HashMap<>(), rows, new HashMap<>());
    }

    @Test
    public void theLeaderIsChosenWithTheManHimselfIncluded(){
        // weeks 1-3: wr1 10 touches a game, wr2 6, me 3; week 4: wr2 carries no projection (inactive)
        ScreenData.Season s = new ScreenData.Season("2022");
        for(int w = 1; w <= 4; w++){
            Map<String, ScreenData.Line> week = new HashMap<>();
            week.put("wr1", wr("wr1", "BUF", 10, true));
            week.put("wr2", wr("wr2", "BUF", 6, w < 4));
            week.put("me", wr("me", "BUF", 3, true));
            s.lines.put(w, week);
        }
        s.projectedPoints.put(4, Map.of("wr1", 14.0, "me", 7.0));
        NflverseGames.Side side = side("BUF", "MIA", true, "outdoors", 5.0, 60.0, "13:00");
        ScreenData.Row wr1 = new ScreenData.Row("2022", 4, s.line(4, "wr1"), 14, side);
        ScreenData.Row me = new ScreenData.Row("2022", 4, s.line(4, "me"), 7, side);
        ScreenData.Context c = context(Map.of("2022", s), List.of(wr1, me));
        assertEquals(0, FeatureScreen.leaderOut(wr1, c), "the WR1 is not promoted when the WR2 sits - he is the leader");
        assertEquals(0, FeatureScreen.leaderOut(me, c), "the leader, wr1, is projected: nobody is out");
        s.projectedPoints.put(4, Map.of("wr2", 9.0, "me", 7.0));
        ScreenData.Row wr2 = new ScreenData.Row("2022", 4, s.line(4, "wr2"), 9, side);
        assertEquals(1, FeatureScreen.leaderOut(wr2, context(Map.of("2022", s), List.of(wr2))),
                "wr1 carries no projection: the man who led the rest steps up");
    }

    @Test
    public void thePassRatePriorIsLastSeasonsShareNotItsPlaysCount(){
        ScreenData.Season last = new ScreenData.Season("2021");
        Map<String, ScreenData.Line> week = new HashMap<>();
        week.put("qb", new ScreenData.Line("qb", "BUF", "MIA", "2021-09-12", Position.QB, "qb", true, true, 30, 3, 0, 0, 2, 0, 0, 2, 0, 20));
        week.put("rb", new ScreenData.Line("rb", "BUF", "MIA", "2021-09-12", Position.RB, "rb", true, false, 0, 22, 4, 3, 0, 1, 0, 0, 0, 15));
        last.lines.put(1, week);
        ScreenData.Season now = new ScreenData.Season("2022");
        ScreenData.Context c = context(new java.util.TreeMap<>(Map.of("2021", last, "2022", now)), List.of());
        double[] pp = c.playsPerGame("2022", "BUF", 1);
        assertEquals(57, pp[0], 1e-9, "30 attempts + 2 sacks + 25 carries");
        assertEquals(32.0 / 57, pp[1], 1e-9, "with no game played yet, exactly last season's share");
    }

    static NflverseGames.Side home(String stadium, String season, String surface){
        return new NflverseGames.Side(stadium + season, season, 1, "NE", "BUF", true, false, season + "-09-10", "Sunday", "13:00",
                1.0, 44.0, 7, 7, false, "outdoors", surface, 60.0, 5.0, null, "c", "d", stadium, "NE", 3.0);
    }

    @Test
    public void theSurfaceIsTheStadiumsSevenSeasonMajority(){
        Map<String, NflverseGames.Side> sides = new HashMap<>();
        String[] bos = {"fieldturf", "fieldturf", "fieldturf", "grass", "grass", "fieldturf", "fieldturf"};
        for(int i = 0; i < bos.length; i++){
            sides.put("b" + i, home("BOS00", String.valueOf(2016 + i), bos[i]));
        }
        for(int s = 2014; s <= 2024; s++){
            sides.put("x" + s, home("NEW01", String.valueOf(s), s <= 2020 ? "grass " : "matrixturf"));
        }
        ScreenData.Context c = new ScreenData.Context(new java.util.TreeMap<>(), sides, List.of(), new HashMap<>());
        assertEquals("fieldturf", c.surface(home("BOS00", "2019", "grass")), "a two-season coding error is outvoted");
        assertEquals("grass", c.surface(home("NEW01", "2020", "grass ")), "a real change is followed: grass through 2020");
        assertEquals("matrixturf", c.surface(home("NEW01", "2021", "matrixturf")), "and turf from 2021");
    }

    @Test
    public void aPlayersSlopeIsReadWithinHim(){
        List<double[]> offsetOnly = new ArrayList<>();
        List<double[]> sloped = new ArrayList<>();
        for(int i = 0; i < 30; i++){
            double z = i - 15;
            double noise = 0.5 * FeatureScreen.placebo(11, "e" + i);
            offsetOnly.add(new double[]{z + 40, 3 + noise});            // Sleeper under-projects him by 3; z does nothing
            sloped.add(new double[]{z, 0.1 * z + noise});
        }
        assertEquals(0, FeatureScreen.withinPlayerSlope(offsetOnly)[0], 0.03, "a persistent bias is his intercept, not a slope");
        assertEquals(0.1, FeatureScreen.withinPlayerSlope(sloped)[0], 0.03);
        assertNull(FeatureScreen.withinPlayerSlope(offsetOnly.subList(0, 19)), "fewer than twenty rows: not estimated");
    }

    @Test
    public void projectionMomentumIsBoundedForAPromotedBackup(){
        ScreenData.Season s = new ScreenData.Season("2022");
        s.projectedPoints.put(1, Map.of("b", 0.4));
        s.projectedPoints.put(2, Map.of("b", 0.6));
        s.projectedPoints.put(3, Map.of("b", 0.5));
        s.lines.put(4, Map.of("b", wr("b", "BUF", 8, true)));
        ScreenData.Row row = new ScreenData.Row("2022", 4, s.line(4, "b"), 12, side("BUF", "MIA", true, "outdoors", 5.0, 60.0, "13:00"));
        double a1 = FeatureScreen.projectionMomentum(row, context(Map.of("2022", s), List.of(row)));
        assertEquals(Math.log(12 / 5.0), a1, 1e-9, "log of 12 over the floor of 5, not 12 / 0.5 - 1 = 23");
    }

    @Test
    public void arizonaKeepsNoDaylightTime(){
        assertEquals(3, ScreenData.hoursBehindEt("ARI", "2024-10-27"));
        assertEquals(2, ScreenData.hoursBehindEt("ARI", "2024-11-03"), "the first Sunday of November 2024");
        assertEquals(2, ScreenData.hoursBehindEt("ARI", "2025-01-05"));
        assertEquals(3, ScreenData.hoursBehindEt("SEA", "2024-11-03"));
        assertEquals(0, ScreenData.hoursBehindEt("NE", "2024-11-03"));
    }
}
