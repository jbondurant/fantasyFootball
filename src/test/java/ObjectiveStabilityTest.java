import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;

/** The stability report's spread is the range across seeds, and the worst line sets the floor. */
public class ObjectiveStabilityTest {

    @Test
    public void theSpreadIsTheRangeAcrossSeedsAndTheWorstLineIsTheFloor(){
        ObjectiveStability.Line purdy = new ObjectiveStability.Line("Purdy", new double[]{90.2, 96.3, 91.3});
        ObjectiveStability.Line johnston = new ObjectiveStability.Line("Johnston", new double[]{44.3, 60.1, 61.8});
        assertEquals(6.1, purdy.spread(), 1e-9);
        assertEquals(17.5, johnston.spread(), 1e-9);
        assertEquals(17.5, ObjectiveStability.worstSpread(List.of(purdy, johnston)), 1e-9);
    }
}
