import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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

    /**
     * The seed floor DIAGNOSTIC quotes is the one the newest committed report
     * prints. It was published as 7.7 in DIAGNOSTIC, 7.7 in the agent file and
     * 5.6 in the life repo while the report said 6.8 - three numbers for one
     * measurement, none of them checked against the file they cited.
     */
    @Test
    public void theQuotedSeedFloorIsTheOneTheNewestReportPrints() throws Exception {
        Path report;
        try(var files = Files.list(Path.of("data"))){
            report = files.filter(p -> p.getFileName().toString().matches("objective-stability-\\d{4}-\\d{2}-\\d{2}\\.txt"))
                    .max(Comparator.comparing(p -> p.getFileName().toString())).orElseThrow();
        }
        String floor = null;
        for(String line : Files.readAllLines(report)){
            if(line.startsWith("worst seed-to-seed spread of a marginal:")){
                floor = line.split(":")[1].trim().split(" ")[0];
            }
        }
        assertNotNull(floor, "no worst-spread line in " + report);
        String diagnostic = Files.readString(Path.of("DIAGNOSTIC.md"));
        assertTrue(diagnostic.contains(floor + " points at 480 scenarios"),
                "DIAGNOSTIC.md does not quote the " + floor + " that " + report + " prints");
    }
}
