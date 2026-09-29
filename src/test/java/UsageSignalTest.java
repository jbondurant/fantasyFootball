import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

/** The usage reader, the standardisation and the ridge solver. */
public class UsageSignalTest {

    @Test
    public void usageIsPerPlayedGameAndAMissingKeyIsZero(){
        JsonObject w1 = JsonParser.parseString("{\"1\":{\"pts_half_ppr\":10,\"rec_tgt\":8,\"rush_att\":2}}").getAsJsonObject();
        JsonObject w2 = JsonParser.parseString("{\"1\":{\"pts_half_ppr\":4,\"rec_tgt\":4}}").getAsJsonObject();
        JsonObject w3 = JsonParser.parseString("{\"1\":{\"gp\":0}}").getAsJsonObject();
        double[] u = UsageSignal.usageThrough(List.of(w1, w2, w3), "1");
        assertEquals(6.0, u[0], 1e-9, "targets: (8+4)/2 over the two played weeks");
        assertEquals(1.0, u[1], 1e-9, "carries: (2+0)/2, the missing key counting zero");
        assertNull(UsageSignal.usageThrough(List.of(w3), "1"), "no played week: no usage");
        assertNull(UsageSignal.usageThrough(List.of(w1), "2"), "an id the feed never saw");
    }

    @Test
    public void standardisationUsesTheMomentsGiven(){
        List<double[]> rows = List.of(new double[]{1, 10}, new double[]{3, 10}, new double[]{5, 10});
        double[][] m = UsageSignal.moments(rows, 2);
        assertEquals(3.0, m[0][0], 1e-9);
        assertEquals(2.0, m[1][0], 1e-9, "sample sd of 1,3,5");
        assertEquals(0.0, UsageSignal.standardise(new double[]{3, 10}, m)[0], 1e-9);
        assertEquals(1.0, UsageSignal.standardise(new double[]{5, 10}, m)[0], 1e-9);
        assertEquals(0.0, UsageSignal.standardise(new double[]{5, 10}, m)[1], 1e-9, "a constant column standardises to zero, never divides by zero");
    }

    @Test
    public void ridgeRecoversALineAndShrinksTowardZero(){
        List<double[]> x = List.of(new double[]{0}, new double[]{1}, new double[]{2}, new double[]{3}, new double[]{4});
        double[] y = {1, 3, 5, 7, 9};
        double[] exact = UsageSignal.ridge(x, y, 0);
        assertEquals(1.0, exact[0], 1e-9, "intercept");
        assertEquals(2.0, exact[1], 1e-9, "slope");
        double[] shrunk = UsageSignal.ridge(x, y, 5);
        assertTrue(shrunk[1] < 2.0 && shrunk[1] > 0, "the penalty pulls the slope toward zero, not past it");
        assertEquals(1 + 2 * 2.5, UsageSignal.predict(exact, new double[]{2.5}), 1e-9);
    }

    @Test
    public void twoFeaturesAreSeparated(){
        // y = 1 + 2a - 3b on a small grid
        List<double[]> x = new java.util.ArrayList<>();
        double[] y = new double[9];
        int i = 0;
        for(int a = 0; a < 3; a++){
            for(int b = 0; b < 3; b++){
                x.add(new double[]{a, b});
                y[i++] = 1 + 2 * a - 3 * b;
            }
        }
        double[] beta = UsageSignal.ridge(x, y, 0);
        assertEquals(1.0, beta[0], 1e-9);
        assertEquals(2.0, beta[1], 1e-9);
        assertEquals(-3.0, beta[2], 1e-9);
    }

    @Test
    public void eachPositionReadsItsOwnStats(){
        assertArrayEquals(new int[]{2, 1, 5}, UsageSignal.featuresFor(PlayerImportAndSetup.Position.QB), "pass attempts, carries, red-zone carries");
        assertArrayEquals(new int[]{1, 0, 5, 4}, UsageSignal.featuresFor(PlayerImportAndSetup.Position.RB));
        assertArrayEquals(new int[]{0, 3, 4, 1}, UsageSignal.featuresFor(PlayerImportAndSetup.Position.WR));
        assertArrayEquals(UsageSignal.featuresFor(PlayerImportAndSetup.Position.WR), UsageSignal.featuresFor(PlayerImportAndSetup.Position.TE));
    }
}
