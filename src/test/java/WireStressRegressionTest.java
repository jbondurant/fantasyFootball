import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The defence wire's streaming-over-holding ratio is the one the data file records, not a remembered number. */
public class WireStressRegressionTest {

    static double rate(List<String> lines, String rowLabel){
        for(String line : lines){
            if(line.startsWith(rowLabel)){
                String[] parts = line.substring(rowLabel.length()).trim().split("\\s+");
                return Double.parseDouble(parts[0]);
            }
        }
        throw new IllegalStateException("no row starting " + rowLabel);
    }

    @Test
    public void streamOverHoldIsReadBackFromWireRateStress() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("data", "wire-rate-stress-2026-09-04.txt"));
        double stream = rate(lines, "stream on form, react after week 2");
        double hold = rate(lines, "hold best undrafted by ADP, all season");
        assertEquals(7.73, stream, 1e-9);
        assertEquals(6.98, hold, 1e-9);
        assertEquals(stream / hold, WeeklyStarterValue.DEF_STREAM_OVER_HOLD, 1e-12,
                "forCurrentBoard scales the held-13th defence projection by this ratio");
    }
}
