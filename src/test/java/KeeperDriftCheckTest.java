import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeper surplus rests on "the best man still available at your pick", and that
 * availability comes from Sleeper's NATIONAL ADP. QbMarketGap measured this
 * league letting quarterbacks fall in all five seasons, so the curve is wrong
 * about QBs specifically - and every QB keeper number is overstated by it.
 */
public class KeeperDriftCheckTest {

    /** The drift is QB-specific; moving it must not touch anybody else. */
    @Test
    void onlyQuarterbacksMove() throws Exception {
        Path report;
        try(var files = Files.list(Path.of("data"))){
            report = files.filter(p -> p.getFileName().toString()
                            .matches("keeper-drift-\\d{4}-\\d{2}-\\d{2}\\.txt"))
                    .max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(report != null,
                "no drift check built yet - run -Pmain=KeeperDriftCheck");
        Matcher row = Pattern.compile("(?m)^(.{22})\\s+(\\S+)\\s+r(\\d+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+([+-][\\d.]+)")
                .matcher(Files.readString(report));
        int moved = 0, seen = 0;
        while(row.find()){
            String position = row.group(2);
            double change = Double.parseDouble(row.group(6));
            if(Math.abs(change) > 0.05){
                assertEquals("QB", position,
                        row.group(1).trim() + " is a " + position + " and moved by " + change
                                + "; the measured gap is quarterback-specific, with the"
                                + " board-wide keeper shift already removed");
                moved++;
            }
            seen++;
        }
        assertTrue(seen > 0, "no rows parsed, so this proves nothing");
        assertTrue(moved > 0,
                "no keeper moved at all. Either this roster holds no quarterback worth"
                        + " keeping, or the drift stopped being applied - and the second"
                        + " would silently restore the overstatement.");
    }

    /**
     * AND THE DRIFT MUST ONLY EVER LOWER A QB'S SURPLUS.
     *
     * Quarterbacks last LONGER here than the market says, so the replacement
     * available at a late pick is BETTER than the curve believes. A drift that
     * raised a keeper's value would mean the sign had been flipped, which would
     * turn a correction into an endorsement.
     */
    @Test
    void theCorrectionNeverFlattersAQuarterback() throws Exception {
        Path report;
        try(var files = Files.list(Path.of("data"))){
            report = files.filter(p -> p.getFileName().toString()
                            .matches("keeper-drift-\\d{4}-\\d{2}-\\d{2}\\.txt"))
                    .max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(report != null, "no drift check built yet");
        Matcher row = Pattern.compile("(?m)^(.{22})\\s+QB\\s+r(\\d+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+([+-][\\d.]+)")
                .matcher(Files.readString(report));
        int seen = 0;
        while(row.find()){
            double asPriced = Double.parseDouble(row.group(3));
            double shifted = Double.parseDouble(row.group(4));
            assertTrue(shifted <= asPriced + 1e-9,
                    row.group(1).trim() + " is worth MORE once the drift is applied ("
                            + shifted + " against " + asPriced + "). This league lets QBs"
                            + " fall, so the replacement can only get better and the surplus"
                            + " can only shrink - a rise means the sign is inverted");
            assertTrue(shifted >= 0, "a floored surplus cannot be negative: " + shifted);
            seen++;
        }
        assertTrue(seen > 0, "no quarterback rows found, so this proves nothing");
    }
}
