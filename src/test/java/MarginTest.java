import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * The margin between the top two rows must be tested PAIRED.
 *
 * The table prints END TEAM to a decimal, and twenty-five points looks
 * decisive. Whether it is depends on the noise in the DIFFERENCE, not on the
 * noise in either total - and because both candidates are scored on the same
 * worlds, those are wildly different quantities.
 */
public class MarginTest {

    @Test
    public void aConstantLeadHasNoNoiseAtAll(){
        double[] leader = new double[600];
        double[] runnerUp = new double[600];
        for(int i = 0; i < leader.length; i++){
            // Both totals swing enormously; the GAP never moves.
            double world = 1500 + 400 * Math.sin(i);
            leader[i] = world + 25;
            runnerUp[i] = world;
        }
        double[] gap = LiveBoard.margin(leader, runnerUp);
        assertEquals(25.0, gap[0], 1e-9, "the paired margin is exactly 25");
        assertEquals(0.0, gap[1], 1e-9,
                "a lead that is identical in every world has zero standard"
                        + " error, however violently the totals themselves move -"
                        + " which is the whole reason to pair them");
    }

    @Test
    public void anUnpairedComparisonWouldHaveCalledThatNoise(){
        double[] leader = new double[600];
        double[] runnerUp = new double[600];
        for(int i = 0; i < leader.length; i++){
            double world = 1500 + 400 * Math.sin(i);
            leader[i] = world + 25;
            runnerUp[i] = world;
        }
        // The SD of either series alone is huge next to a 25-point gap.
        double mean = 0;
        for(double value : leader){
            mean += value;
        }
        mean /= leader.length;
        double sumSquares = 0;
        for(double value : leader){
            sumSquares += (value - mean) * (value - mean);
        }
        double sd = Math.sqrt(sumSquares / (leader.length - 1));
        assertTrue(sd > 100,
                "the totals must be genuinely noisy for this test to mean"
                        + " anything, got sd " + sd);
        assertTrue(LiveBoard.margin(leader, runnerUp)[1] < sd / 100,
                "pairing must destroy nearly all of that noise");
    }

    @Test
    public void aRealTieReportsRealUncertainty(){
        double[] leader = new double[600];
        double[] runnerUp = new double[600];
        java.util.Random random = new java.util.Random(4242);
        for(int i = 0; i < leader.length; i++){
            leader[i] = 1800 + random.nextGaussian() * 200;
            runnerUp[i] = 1800 + random.nextGaussian() * 200;
        }
        double[] gap = LiveBoard.margin(leader, runnerUp);
        assertTrue(gap[1] > 5,
                "two independently drawn candidates must show real standard"
                        + " error, got " + gap[1]);
        assertTrue(Math.abs(gap[0]) < 2 * gap[1] * 3,
                "and their margin must not be wildly outside its own noise");
    }
}
