import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

/** The rank statistics and the calendar the shootout leans on. */
public class ProjectionShootoutTest {

    @Test
    public void tiesShareTheirMeanRank(){
        assertArrayEquals(new double[]{1, 2.5, 2.5, 4}, ProjectionShootout.ranks(new double[]{1, 2, 2, 3}), 1e-9);
        assertArrayEquals(new double[]{3, 1, 2}, ProjectionShootout.ranks(new double[]{30, 10, 20}), 1e-9);
        assertArrayEquals(new double[]{2, 2, 2}, ProjectionShootout.ranks(new double[]{5, 5, 5}), 1e-9);
    }

    @Test
    public void spearmanIsOneForAnyMonotoneMapAndMinusOneReversed(){
        assertEquals(1.0, ProjectionShootout.spearman(List.of(
                new double[]{1, 10}, new double[]{2, 100}, new double[]{3, 1000}, new double[]{4, 1001})), 1e-9);
        assertEquals(-1.0, ProjectionShootout.spearman(List.of(
                new double[]{1, 4}, new double[]{2, 3}, new double[]{3, 2}, new double[]{4, 1})), 1e-9);
        assertTrue(Double.isNaN(ProjectionShootout.spearman(List.of(new double[]{1, 2}, new double[]{2, 3}))),
                "two pairs cannot carry a correlation");
    }

    @Test
    public void aPairedDifferenceCarriesItsOwnStandardError(){
        ProjectionShootout.Paired p = ProjectionShootout.paired(List.of(2.0, 4.0, 6.0, 8.0));
        assertEquals(5.0, p.mean(), 1e-9);
        // sd of {2,4,6,8} is sqrt(20/3); se = sd / 2
        assertEquals(Math.sqrt(20.0 / 3) / 2, p.se(), 1e-9);
        assertEquals(4, p.n());
        assertTrue(Double.isNaN(ProjectionShootout.paired(List.of(1.0)).se()), "one difference has no spread");
    }

    @Test
    public void theWeeksFirstGameIsSevenDaysOnFromTheSeasonStart(){
        assertEquals(LocalDate.parse("2026-09-09"), ProjectionShootout.firstGame("2026-09-09", 1));
        assertEquals(LocalDate.parse("2026-09-16"), ProjectionShootout.firstGame("2026-09-09", 2));
        assertEquals(LocalDate.parse("2026-12-30"), ProjectionShootout.firstGame("2026-09-09", 17));
    }

    @Test
    public void archiveDaysComeFromDatedRowsOnly(){
        List<String> lines = List.of("date,source,sleeper_id,league_points",
                "2026-08-25,sleeper,SEA,103.0", "2026-09-02,cbs,4034,210.5", "2026-08-25,espn,4034,300.1", "garbage");
        assertEquals(List.of("2026-08-25", "2026-09-02"), List.copyOf(ProjectionShootout.archiveDays(lines)));
    }
}
