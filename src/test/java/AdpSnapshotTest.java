import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** The mover arithmetic, offline. */
class AdpSnapshotTest {

    @Test
    void moversAreOrderedByAbsoluteShift(){
        Map<String, double[]> byPlayer = Map.of(
                "1", new double[]{50, 38},     // rising hard, -12
                "2", new double[]{80, 84},     // drifting down, +4
                "3", new double[]{120, 120});  // unchanged - excluded
        Map<String, String> labels = Map.of("1", "Riser Guy|RB", "2", "Fader Guy|WR", "3", "Same Guy|TE");

        List<AdpSnapshot.Mover> movers = AdpSnapshot.movers(byPlayer, labels, 10);

        Assertions.assertEquals(2, movers.size());
        Assertions.assertEquals("Riser Guy", movers.get(0).name());
        Assertions.assertEquals(-12, movers.get(0).to() - movers.get(0).from(), 0.0001);
    }

    @Test
    void topLimitIsRespected(){
        Map<String, double[]> byPlayer = Map.of(
                "1", new double[]{50, 40}, "2", new double[]{60, 55}, "3", new double[]{70, 68});
        Map<String, String> labels = Map.of("1", "A|RB", "2", "B|RB", "3", "C|RB");

        Assertions.assertEquals(2, AdpSnapshot.movers(byPlayer, labels, 2).size());
    }
}
