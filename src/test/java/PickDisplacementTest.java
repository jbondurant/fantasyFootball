import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

/** The learned-displacement fit, on rows where the answer is known. */
class PickDisplacementTest {

    private static PickDisplacement.ResidualRow row(Position pos, int depth, double residual){
        return new PickDisplacement.ResidualRow("A", pos, depth, residual);
    }

    @Test
    void positionOffsetIsTheMeanResidual(){
        PickDisplacement fitted = PickDisplacement.fromRows(List.of(
                row(Position.QB, 10, 20), row(Position.QB, 40, 10),
                row(Position.WR, 10, -6), row(Position.WR, 90, -14)));

        Assertions.assertEquals(15.0, fitted.offset(Position.QB), 0.0001);
        Assertions.assertEquals(-10.0, fitted.offset(Position.WR), 0.0001);
        Assertions.assertEquals(0.0, fitted.offset(Position.TE), 0.0001, "never seen, no bias");
    }

    @Test
    void depthBinsSplitAtTheEdges(){
        Assertions.assertEquals(0, PickDisplacement.binOf(1));
        Assertions.assertEquals(0, PickDisplacement.binOf(35));
        Assertions.assertEquals(1, PickDisplacement.binOf(36));
        Assertions.assertEquals(1, PickDisplacement.binOf(83));
        Assertions.assertEquals(2, PickDisplacement.binOf(84));
        Assertions.assertEquals(2, PickDisplacement.binOf(300));
    }

    @Test
    void samplingBootstrapsTheRealResidualsAroundTheOffset(){
        // Two QB rows with residuals 20 and 10 (offset 15): centered values are
        // +5 and -5, so every draw is 15 +/- 5 exactly - the data, not a curve.
        PickDisplacement fitted = PickDisplacement.fromRows(List.of(
                row(Position.QB, 10, 20), row(Position.QB, 12, 10)));

        Random random = new Random(7);
        for(int i = 0; i < 50; i++){
            double sample = fitted.sample(random, 10, Position.QB);
            Assertions.assertTrue(sample == 10.0 || sample == 20.0,
                    "bootstrap must reproduce observed residuals, got " + sample);
        }
    }

    @Test
    void anEmptyBinFallsBackToTheOffsetAlone(){
        PickDisplacement fitted = PickDisplacement.fromRows(List.of(row(Position.RB, 10, 4)));
        Assertions.assertEquals(fitted.offset(Position.RB),
                fitted.sample(new Random(1), 200, Position.RB), 0.0001,
                "no deep-board rows: sample degrades to the offset");
    }
}
