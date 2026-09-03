import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import PlayerImportAndSetup.Position;

/** The learned floor as a prior: below-floor candidates keep a share, never none, never all. */
public class SoftFloorTest {

    private static Position pos(String id){ return id.startsWith("d") ? Position.DEF : Position.RB; }

    @Test
    public void belowFloorKeepsTheWeightAndTheRestRenormalises(){
        double[] p = {0.5, 0.5};   // rb, def
        double[] out = DraftSimulator.softenFloor(p, List.of("rb", "d1"), 6, Map.of(Position.DEF, 10), SoftFloorTest::pos, 0.1);
        assertEquals(0.5 / 0.55, out[0], 1e-9);
        assertEquals(0.05 / 0.55, out[1], 1e-9, "the defence keeps a tenth of its probability in round 6");
        assertArrayEquals(new double[]{0.5, 0.5}, p, "the input is not modified");
    }

    @Test
    public void atOrPastTheFloorNothingChanges(){
        double[] p = {0.5, 0.5};
        assertArrayEquals(p, DraftSimulator.softenFloor(p, List.of("rb", "d1"), 10, Map.of(Position.DEF, 10), SoftFloorTest::pos, 0.1), 1e-12);
    }

    @Test
    public void noFloorsOrFullWeightIsIdentity(){
        double[] p = {0.7, 0.3};
        assertSame(p, DraftSimulator.softenFloor(p, List.of("rb", "d1"), 1, Map.of(), SoftFloorTest::pos, 0.1));
        assertSame(p, DraftSimulator.softenFloor(p, List.of("rb", "d1"), 1, Map.of(Position.DEF, 10), SoftFloorTest::pos, 1.0));
    }
}
