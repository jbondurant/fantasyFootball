import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import PlayerImportAndSetup.Position;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;

/** The demand model's pieces: features, the logistic fit, the ladder arithmetic, the weekday. */
public class FaabDemandTest {

    @Test
    public void snapShareIsSnapsOverTheTeamsAndMissingIsNaN(){
        JsonObject line = JsonParser.parseString("{\"off_snp\":45,\"tm_off_snp\":60,\"rec_tgt\":7,\"rush_att\":2}").getAsJsonObject();
        assertEquals(0.75, FaabDemand.snapShare(line), 1e-9);
        assertTrue(Double.isNaN(FaabDemand.snapShare(JsonParser.parseString("{\"rec_tgt\":7}").getAsJsonObject())));
        assertTrue(Double.isNaN(FaabDemand.snapShare(null)));
        double[] f = FaabDemand.features(line, null, 12.5, 8.0, 150, true, Position.WR, true);
        assertEquals(0.75, f[0], 1e-9, "share");
        assertEquals(0.75, f[1], 1e-9, "jump from a missing week reads as from zero");
        assertEquals(9.0, f[2], 1e-9, "touches = targets + carries");
        assertEquals(12.5, f[3], 1e-9);
        assertEquals(8.0, f[4], 1e-9, "points per game so far");
        assertEquals(Math.log(150), f[5], 1e-9, "log ADP");
        assertEquals(1.0, f[6], 1e-9, "dropped");
        assertArrayEquals(new double[]{0, 1, 0, 0}, new double[]{f[7], f[8], f[9], f[10]}, 1e-9, "WR indicator");
        assertEquals(1.0, f[11], 1e-9, "next man up");
        assertEquals(FaabDemand.FEATURES.length, f.length, "one name per column");
        double[] rb = FaabDemand.features(line, null, 0, 0, 0, false, Position.RB, false);
        assertEquals(0.0, rb[5], 1e-9, "an ADP under one logs to zero, never below");
        assertEquals(0.0, rb[11], 1e-9);
        assertArrayEquals(java.util.Arrays.copyOf(f, 11), FaabDemand.pick(f, FaabDemand.WITHOUT_NEXT_UP), 1e-12,
                "the model without the feature is the same row less its last column");
    }

    @Test
    public void theLogisticFitSeparatesAnEasyCaseAndTheInterceptCarriesTheBaseRate(){
        List<double[]> x = new ArrayList<>();
        boolean[] y = new boolean[200];
        for(int i = 0; i < 200; i++){
            double v = (i % 20) / 10.0 - 1;          // -1 .. 0.9
            x.add(new double[]{v});
            y[i] = v > 0.2 ? true : v < -0.2 ? false : (i % 2 == 0);
        }
        double[] beta = FaabDemand.logistic(x, y, 1.0);
        assertTrue(beta[1] > 0, "a positive slope: the feature raises the odds");
        assertTrue(FaabDemand.predict(beta, new double[]{0.9}) > 0.85);
        assertTrue(FaabDemand.predict(beta, new double[]{-0.9}) < 0.15);
        // all-false outcomes: the intercept goes strongly negative, the slope is shrunk
        boolean[] none = new boolean[200];
        double[] flat = FaabDemand.logistic(x, none, 1.0);
        assertTrue(FaabDemand.predict(flat, new double[]{0}) < 0.05);
    }

    @Test
    public void logLossRewardsCalibrationAndTheLadderReadsTheSameWayAsFaabBid(){
        assertTrue(FaabDemand.logLoss(List.of(0.9, 0.1), List.of(true, false)) < FaabDemand.logLoss(List.of(0.5, 0.5), List.of(true, false)));
        List<Integer> prices = List.of(0, 0, 2, 5, 9);
        assertEquals(0.2, FaabDemand.winChance(prices, 0), 1e-9, "two ties of five, at half each");
        assertEquals(0.5, FaabDemand.winChance(prices, 2), 1e-9, "beats two, ties one");
        assertEquals(1.0, FaabDemand.winChance(prices, 10), 1e-9);
        assertEquals(3, FaabDemand.bidFor(prices, 0.6), "the smallest ladder rung that reaches the target");
        assertEquals(13, FaabDemand.bidFor(prices, 0.95));
        assertEquals(50, FaabDemand.bidFor(List.of(80, 90), 0.5), "beyond the ladder: its top, and the caller knows it");
        assertEquals(2, FaabDemand.quantile(prices, 0.5));
    }

    @Test
    public void theWeekdayIsTheLeaguesNotTheMachines(){
        // 2026-09-16 12:08 New York = 16:08 UTC = 1789574880000 ms
        assertEquals(DayOfWeek.WEDNESDAY, FaabDemand.weekday(1789574880000L));
    }

    @Test
    public void everyManOnAnyRosterIsRostered(){
        String matchups = "[{\"roster_id\":1,\"players\":[\"1\",\"2\",null]},{\"roster_id\":2,\"players\":[\"3\"]},{\"roster_id\":3}]";
        java.util.Set<String> rostered = FaabDemand.rosteredIn(matchups);
        assertEquals(java.util.Set.of("1", "2", "3"), rostered);
    }

    @Test
    public void theSolverInvertsASmallSystem(){
        double[] x = FaabDemand.solve(new double[][]{{2, 1}, {1, 3}}, new double[]{5, 10});
        assertEquals(1.0, x[0], 1e-9);
        assertEquals(3.0, x[1], 1e-9);
    }
}
