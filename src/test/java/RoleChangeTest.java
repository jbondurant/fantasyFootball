import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** The role-change events: what counts as a lead back's collapse, on carries by week (NaN a week not played). */
public class RoleChangeTest {

    private static final double X = Double.NaN;
    /** Ashton Jeanty's carries, weeks 1-8 of 2026. */
    private static final double[] JEANTY = {23, 21, 19, 15, 10, 10, 8, 2};

    @Test
    public void theAverageBeforeNeedsThreeGamesAndSkipsWeeksNotPlayed(){
        assertNull(RoleChange.before(JEANTY, 3), "two games before week 3");
        assertEquals(21.0, RoleChange.before(JEANTY, 4), 1e-12);
        assertEquals(106 / 7.0, RoleChange.before(JEANTY, 8), 1e-12);
        assertEquals(15.0, RoleChange.before(new double[]{20, X, 15, X, 10, 5}, 6), 1e-12, "the two byes are not games");
        assertNull(RoleChange.before(new double[]{20, X, 15, X, 10}, 5));
    }

    @Test
    public void jeantysWeekEightIsTheFirstCollapseOfHisSeason(){
        for(int k = 4; k <= 7; k++){
            assertTrue(RoleChange.lead(JEANTY, k));
            assertFalse(RoleChange.collapsed(JEANTY, k), "week " + k + " is a decline, not 40% of his average");
        }
        assertTrue(RoleChange.collapsed(JEANTY, 8), "2 carries against 15.1");
        assertEquals(8, RoleChange.first(k -> RoleChange.collapsed(JEANTY, k), 13));
        assertEquals(0, RoleChange.first(k -> RoleChange.collapsed(JEANTY, k), 7));
    }

    @Test
    public void aBackWhoWasNeverTheLeadCannotCollapse(){
        double[] backup = {6, 5, 7, 6, 1};
        assertFalse(RoleChange.lead(backup, 5));
        assertFalse(RoleChange.collapsed(backup, 5));
        assertFalse(RoleChange.collapsed(new double[]{20, 20, 20, X}, 4), "he did not play the week");
    }

    @Test
    public void twoGamesRunningAreReadAgainstTheAverageBeforeBoth(){
        // weeks 6 and 7: 9 a game against 17.6 before them, just over half; weeks 7 and 8: 5 against 16.3
        assertFalse(RoleChange.sustained(JEANTY, 7));
        assertTrue(RoleChange.sustained(JEANTY, 8));
        assertEquals(7, RoleChange.previous(JEANTY, 8));
        double[] withBye = {20, 20, 20, 8, X, 6};
        assertEquals(4, RoleChange.previous(withBye, 6), "the game before week 6 is week 4");
        assertTrue(RoleChange.sustained(withBye, 6), "7 a game against 20");
        assertFalse(RoleChange.sustained(new double[]{20, 20, 8, 6}, 4), "two games before the pair are not enough");
    }

    @Test
    public void afterIsHisCarriesInTheGamesHePlayedLater(){
        assertEquals(6.0, RoleChange.after(new double[]{20, 20, 20, 2, X, 4, 8}, 4), 1e-12);
        assertTrue(Double.isNaN(RoleChange.after(JEANTY, 8)));
    }

    /** One season with a single back at -9 and one with three at +1: the backs' mean is -1.5, not the -4 a mean of season means gives. */
    @Test
    public void everyBackCountsOnceAndASeasonIsOneCluster(){
        double[] m = RoleChange.pooled(java.util.Map.of("2018", java.util.List.of(-9.0), "2019", java.util.List.of(1.0, 1.0, 1.0)));
        assertEquals(-1.5, m[0], 1e-12);
        assertEquals(3.75, m[1], 1e-12, "sqrt(2/1 x (7.5^2 + 7.5^2)) / 4");
        assertEquals(2, m[2], 1e-12);
        assertTrue(Double.isNaN(RoleChange.pooled(java.util.Map.of("2018", java.util.List.of(1.0, 2.0)))[1]), "one season is no spread of seasons");
        assertEquals(1.5, RoleChange.median(java.util.List.of(3.0, 1.0, 2.0, 0.0)), 1e-12);
        assertEquals(2.0, RoleChange.median(java.util.List.of(3.0, 1.0, 2.0)), 1e-12);
    }
}
