import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The defence bands LateRoundValue prices with are the ones DefenceVersusDepth
 * measured, read out of its committed output rather than retyped. A constant
 * copied out of a tool's output becomes a lie the moment the tool is rerun -
 * which is how DEF_WORST_BAND sat at 129.5 for a week after the bands were
 * re-ranked by the source's ADP order (TRAPS #80).
 */
public class BandRegressionTest {

    /** "mean         135.8     124.4     125.3     127.2" -> the four band means. */
    static double[] bandMeans(List<String> lines){
        for(String line : lines){
            if(line.startsWith("mean ")){
                String[] parts = line.trim().split("\\s+");
                if(parts.length >= 5){
                    return new double[]{ Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                            Double.parseDouble(parts[3]), Double.parseDouble(parts[4]) };
                }
            }
        }
        throw new IllegalStateException("no mean row in the defence-band report");
    }

    @Test
    public void theDefenceBandsAreTheOnesTheToolMeasured() throws Exception {
        double[] means = bandMeans(Files.readAllLines(Path.of("data", LateRoundValue.DEF_BANDS)));
        assertEquals(means[0], LateRoundValue.DEF_BEST_BAND, 0.05,
                "LateRoundValue.DEF_BEST_BAND disagrees with data/" + LateRoundValue.DEF_BANDS);
        assertEquals(means[3], LateRoundValue.DEF_WORST_BAND, 0.05,
                "LateRoundValue.DEF_WORST_BAND disagrees with data/" + LateRoundValue.DEF_BANDS);
    }
}
