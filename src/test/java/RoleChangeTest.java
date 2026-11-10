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
}
