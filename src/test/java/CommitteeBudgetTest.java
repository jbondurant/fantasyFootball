import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Model A's committee must land inside its wall-clock budget. Measured at pick
 * 7 of the 2026 draft: 42 seconds against a 60-second clock, with 25 documented.
 */
public class CommitteeBudgetTest {

    @Test
    public void whenItFitsNothingChanges(){
        assertEquals(150, LiveCommittee.fitRollouts(150, 2.0, 20.0), "8s predicted, 20s left");
        assertEquals(150, LiveCommittee.fitRollouts(150, 0.0, 1.0), "no measurement, no scaling");
    }

    @Test
    public void whenItDoesNotFitRolloutsScaleToTheTimeLeft(){
        // lookahead-1 took 6.2s, so lookahead-2 is predicted at 24.8s; 12.4s remain -> half
        assertEquals(75, LiveCommittee.fitRollouts(150, 6.2, 12.4));
    }

    @Test
    public void neverBelowFortyAndNeverAboveWhatWasAsked(){
        assertEquals(40, LiveCommittee.fitRollouts(150, 6.2, 1.0), "the floor");
        assertEquals(40, LiveCommittee.fitRollouts(150, 6.2, -3.0), "already over budget: the floor, not zero");
        assertEquals(60, LiveCommittee.fitRollouts(60, 1.0, 4.0), "fits exactly: the ceiling is what was asked for");
        assertEquals(58, LiveCommittee.fitRollouts(60, 1.0, 3.9), "just short: scaled, not clamped to the floor");
    }
}
