import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;

/** The slope, the percentile and the anchored day-file name the audit relies on. */
public class WeeklyFeedAuditTest {

    @Test
    public void aPerfectLineFitsExactlyAndAFlatLineHasNoSlope(){
        WeeklyFeedAudit.Fit line = WeeklyFeedAudit.fit(List.of(
                new double[]{1, 3}, new double[]{2, 5}, new double[]{3, 7}, new double[]{4, 9}));
        assertEquals(2.0, line.slope(), 1e-9);
        assertEquals(0.0, line.se(), 1e-9, "no residual, no standard error");
        assertEquals(1.0, line.r(), 1e-9);
        assertEquals(4, line.n());

        WeeklyFeedAudit.Fit flat = WeeklyFeedAudit.fit(List.of(
                new double[]{1, 5}, new double[]{2, 5}, new double[]{3, 5}));
        assertEquals(0.0, flat.slope(), 1e-9, "y never moves with x");
        assertTrue(Double.isNaN(flat.r()), "a flat y has no correlation to report");
    }

    @Test
    public void tooFewPointsOrAFlatXRefuseRatherThanGuess(){
        assertTrue(Double.isNaN(WeeklyFeedAudit.fit(List.of(new double[]{1, 2}, new double[]{2, 4})).slope()));
        assertTrue(Double.isNaN(WeeklyFeedAudit.fit(List.of(
                new double[]{2, 1}, new double[]{2, 5}, new double[]{2, 9})).slope()), "every x the same: no slope exists");
    }

    @Test
    public void theStandardErrorGrowsWithScatter(){
        // the same slope through noisier points must carry a bigger bar
        WeeklyFeedAudit.Fit tight = WeeklyFeedAudit.fit(List.of(
                new double[]{0, 0.1}, new double[]{1, 0.9}, new double[]{2, 2.1}, new double[]{3, 2.9}));
        // y = x + (1, -1, -1, 1): the noise is orthogonal to x, so the slope is exactly one
        WeeklyFeedAudit.Fit loose = WeeklyFeedAudit.fit(List.of(
                new double[]{0, 1}, new double[]{1, 0}, new double[]{2, 1}, new double[]{3, 4}));
        assertTrue(loose.se() > tight.se());
        assertEquals(1.0, loose.slope(), 1e-9);
    }

    @Test
    public void percentilesReadTheSortedListByRank(){
        List<Double> sorted = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);
        assertEquals(5.0, WeeklyFeedAudit.percentile(sorted, 50), 1e-9);
        assertEquals(1.0, WeeklyFeedAudit.percentile(sorted, 10), 1e-9);
        assertEquals(9.0, WeeklyFeedAudit.percentile(sorted, 90), 1e-9);
        assertTrue(Double.isNaN(WeeklyFeedAudit.percentile(List.of(), 50)));
    }

    @Test
    public void theReadComparesTheBarToZeroAndToTheHonestShare(){
        assertEquals("cannot tell zero from the honest rule", WeeklyFeedAudit.read(new WeeklyFeedAudit.Fit(0.05, 0.04, 0.2, 60), 0.10));
        assertEquals("a lean, below the honest rule", WeeklyFeedAudit.read(new WeeklyFeedAudit.Fit(0.05, 0.01, 0.2, 60), 0.10));
        assertEquals("no lean the data can see; below the honest rule", WeeklyFeedAudit.read(new WeeklyFeedAudit.Fit(0.01, 0.01, 0.2, 60), 0.10));
        assertEquals("consistent with the honest rule", WeeklyFeedAudit.read(new WeeklyFeedAudit.Fit(0.09, 0.01, 0.2, 60), 0.10));
        assertEquals("above the honest rule", WeeklyFeedAudit.read(new WeeklyFeedAudit.Fit(0.30, 0.02, 0.2, 60), 0.10));
        assertEquals("chases the week", WeeklyFeedAudit.read(new WeeklyFeedAudit.Fit(1.10, 0.02, 0.2, 60), 0.10));
        assertEquals("leans AGAINST the week", WeeklyFeedAudit.read(new WeeklyFeedAudit.Fit(-0.10, 0.02, 0.2, 60), 0.10),
                "a significant negative slope is not 'a small lean'");
        assertEquals("too few men", WeeklyFeedAudit.read(new WeeklyFeedAudit.Fit(Double.NaN, Double.NaN, Double.NaN, 2), 0.10));
    }

    @Test
    public void weekOneNeverMatchesWeekTenThroughEighteen(){
        assertTrue(WeeklyFeedAudit.isDayFile("sleeperLiveProjection2026w12026-09-14.txt", "2026", 1));
        assertFalse(WeeklyFeedAudit.isDayFile("sleeperLiveProjection2026w102026-09-14.txt", "2026", 1), "w1 is a prefix of w10");
        assertFalse(WeeklyFeedAudit.isDayFile("sleeperLiveProjection2026w142026-09-14.txt", "2026", 1));
        assertTrue(WeeklyFeedAudit.isDayFile("sleeperLiveProjection2026w102026-09-14.txt", "2026", 10));
        assertFalse(WeeklyFeedAudit.isDayFile("sleeperWeekProjection2026w1.txt", "2026", 1), "the frozen file is not a day");
        assertFalse(WeeklyFeedAudit.isDayFile("sleeperLiveProjection2025w32026-09-05.txt", "2026", 3), "another season's week");
    }
}
