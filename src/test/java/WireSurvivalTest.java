import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The survival study answers a conditional question - does sitting unclaimed
 * predict staying unclaimed - and its first version answered it BACKWARDS,
 * because it divided claims by men-at-risk across bands of wildly different
 * widths and called a cumulative share a rate.
 */
public class WireSurvivalTest {

    /**
     * A RATE IS PER UNIT TIME. One day and eight weeks are not comparable
     * numerators, and the version that treated them as such reported a hazard
     * RISING with time sat - the exact opposite of what the data says.
     */
    @Test
    void theRateDividesByTheWidthOfItsBand(){
        WireSurvival.Band oneDay = new WireSurvival.Band("a day", 0, 1, 1000, 50);
        WireSurvival.Band eightWeeks = new WireSurvival.Band("8 weeks", 4, 60, 1000, 300);

        assertEquals(0.05, oneDay.hazardPerDay(), 1e-9, "50 of 1000 in one day is 5% a day");
        assertEquals(300.0 / (1000 * 56), eightWeeks.hazardPerDay(), 1e-9);
        assertTrue(oneDay.hazardPerDay() > eightWeeks.hazardPerDay(),
                "fifty claims in a day is a hotter band than three hundred over eight weeks;"
                        + " a measure that says otherwise is counting, not rating");
        assertEquals(0, new WireSurvival.Band("empty", 0, 1, 0, 0).hazardPerDay(),
                "no men at risk is a rate of zero, not a divide by zero");
    }

    /** A spell that never ends is a man nobody wanted, not a claim. */
    @Test
    void anUnclaimedSpellIsNotAClaim(){
        long day = 1000L * 60 * 60 * 24;
        WireSurvival.Spell taken = new WireSurvival.Spell("x", 0, 3 * day, "2025");
        WireSurvival.Spell never = new WireSurvival.Spell("y", 0, null, "2025");
        assertTrue(taken.claimed());
        assertFalse(never.claimed());
        assertEquals(3.0, taken.days(90 * day), 1e-6, "claimed after three days");
        assertEquals(90.0, never.days(90 * day), 1e-6,
                "an unclaimed man sat until the record ended, and that is what he tells us");
    }

    /**
     * And the finding itself, pinned: the claim rate must FALL from its peak.
     * If it ever stops falling, the conditioning that justified bidding zero on
     * a long-sitting man has stopped being true and should fail loudly.
     */
    @Test
    void theClaimRateFallsAfterItsPeak() throws Exception {
        Path report;
        try(var files = Files.list(Path.of("data"))){
            report = files.filter(p -> p.getFileName().toString()
                            .matches("wire-survival-\\d{4}-\\d{2}-\\d{2}\\.txt"))
                    .max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(report != null,
                "no survival report built yet - run -Pmain=WireSurvival");
        String text = Files.readString(report);
        // the band labels contain digits ("1-2 days"), so anchoring on a
        // non-digit run parsed exactly one row and the test proved nothing
        Matcher row = Pattern.compile("(?m)^(\\S.*?)\\s{2,}(\\d+)\\s+(\\d+)\\s+([\\d.]+)%")
                .matcher(text);
        List<Double> rates = new java.util.ArrayList<>();
        while(row.find()){
            rates.add(Double.parseDouble(row.group(4)));
        }
        assertTrue(rates.size() >= 4, "expected the full band table, parsed " + rates.size());
        double peak = rates.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double last = rates.get(rates.size() - 1);
        assertTrue(last < peak / 2,
                "the claim rate ends at " + last + "%/day against a peak of " + peak
                        + "%/day. It is supposed to fall by a wide margin - that decline is what"
                        + " justifies bidding nothing on a man who has sat unclaimed. If it has"
                        + " flattened, demand is uniform and that reasoning is gone.");
    }
}
