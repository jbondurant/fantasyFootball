import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The error bar on a trade is the thing that decides whether its number means
 * anything, so the arithmetic behind it has to be right.
 */
public class TradeStabilityTest {

    @Test
    void spreadIsTheRangeAcrossSeeds(){
        TradeStability.Line line = new TradeStability.Line("give", "get", "them",
                new double[]{7.4, 5.4, 6.8}, new double[]{1, 1, 1});
        assertEquals(2.0, line.mySpread(), 1e-9);
        assertEquals(0.0, new TradeStability.Line("a", "b", "c",
                new double[]{5, 5, 5}, new double[]{0, 0, 0}).mySpread(), 1e-9,
                "a gain identical on every seed has no spread");
    }

    /**
     * SURVIVING MEANS THE WORST SEED CLEARS THE FLOOR, not the best or the mean.
     *
     * The recommendation this was built to check reads +7.4 on the seed the page
     * happens to ship and +5.4 on another. Judging it by its best seed would
     * call that a clear pass; judging it by its worst says what is true, which is
     * that it sits at the edge of what the objective can resolve.
     */
    @Test
    void aTradeSurvivesOnlyIfItsWorstSeedClears(){
        TradeStability.Line marginal = new TradeStability.Line("Josh Downs", "Travis Kelce",
                "BHier", new double[]{7.4, 5.4, 6.8}, new double[]{1, 1, 1});
        assertFalse(marginal.survives(6.8),
                "5.4 is below the floor, so the trade does not clear it on every seed");
        assertTrue(marginal.survives(5.0), "it does clear a floor of 5.0 on every seed");

        TradeStability.Line solid = new TradeStability.Line("a", "b", "c",
                new double[]{23.0, 23.4, 23.3}, new double[]{1, 1, 1});
        assertTrue(solid.survives(6.8), "a gain reproducible to 0.4 clears comfortably");
    }

    /** And the committed report's spreads must be its own columns' range. */
    @Test
    void theReportsSpreadsAreItsOwnNumbers() throws Exception {
        Path report;
        try(var files = Files.list(Path.of("data"))){
            report = files.filter(p -> p.getFileName().toString()
                            .matches("trade-stability-\\d{4}-\\d{2}-\\d{2}\\.txt"))
                    .max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(report != null,
                "no stability report built yet - run -Pmain=TradeStability");
        Matcher row = Pattern.compile("^.{46}((?:\\s+[+-][\\d.]+)+)\\s+([\\d.]+)\\s+\\S",
                Pattern.MULTILINE).matcher(Files.readString(report));
        int seen = 0;
        while(row.find()){
            double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
            for(String value : row.group(1).trim().split("\\s+")){
                double gain = Double.parseDouble(value);
                low = Math.min(low, gain);
                high = Math.max(high, gain);
            }
            assertEquals(high - low, Double.parseDouble(row.group(2)), 0.15,
                    "the printed spread is not the range of the seeds beside it");
            seen++;
        }
        assertTrue(seen > 0, "the report listed no trades");
    }
}
