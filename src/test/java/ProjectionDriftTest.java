import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The drift monitor exists because "it has not changed" and "it never changes"
 * look identical before anything has happened. Its arithmetic has to be right or
 * it will answer that question wrongly in either direction.
 */
public class ProjectionDriftTest {

    /** A player present in one snapshot and absent from the other is not drift. */
    @Test
    void onlyPlayersInBothSnapshotsAreCompared() throws Exception {
        List<Path> archive = ProjectionDrift.archive();
        org.junit.jupiter.api.Assumptions.assumeTrue(archive.size() >= 2,
                "need two archived snapshots");
        Map<String, Double> first = ProjectionDrift.snapshot(archive.get(0));
        Map<String, Double> last = ProjectionDrift.snapshot(archive.get(archive.size() - 1));
        ProjectionDrift.Drift drift = ProjectionDrift.between(
                archive.get(0), archive.get(archive.size() - 1), Map.of());

        assertTrue(drift.shared() <= Math.min(first.size(), last.size()),
                "the shared count cannot exceed the smaller snapshot");
        assertTrue(drift.moved() <= drift.shared(),
                "more players moved than were compared, which is impossible");
        assertTrue(drift.movedShare() >= 0 && drift.movedShare() <= 1,
                "a share outside 0-1: " + drift.movedShare());
    }

    /**
     * AND THE FEED MUST BE SHOWN TO MOVE AT LEAST ONCE.
     *
     * If drift were zero across the whole archive, every conclusion this repo
     * draws in-season rests on preseason numbers - which is a finding, not a
     * pass. This test asserts what was measured on 2026-09-07: thirty of 585
     * players moved between August 24th and September 7th, so the feed is not
     * architecturally frozen. If it ever reads zero across the archive, that is
     * the discovery the monitor was built for and this test should fail loudly
     * rather than shrug.
     */
    @Test
    void theFeedIsShownToUpdateAtLeastSometimes() throws Exception {
        List<Path> archive = ProjectionDrift.archive();
        org.junit.jupiter.api.Assumptions.assumeTrue(archive.size() >= 2,
                "need two archived snapshots");
        ProjectionDrift.Drift whole = ProjectionDrift.between(
                archive.get(0), archive.get(archive.size() - 1), Map.of());
        assertTrue(whole.shared() > 100,
                "only " + whole.shared() + " players shared across the archive, so this"
                        + " comparison is not measuring the feed");
        assertTrue(whole.moved() > 0,
                "NOT ONE of " + whole.shared() + " season projections has changed across the"
                        + " whole archive. The feed is static, and every keeper surplus, trade"
                        + " valuation and free-agent worth in this repo is a preseason number"
                        + " wearing an in-season label. Blend actuals in.");
    }

    /** The committed report's percentage must be its own counts. */
    @Test
    void theReportsShareIsItsOwnArithmetic() throws Exception {
        Path report;
        try(var files = Files.list(Path.of("data"))){
            report = files.filter(p -> p.getFileName().toString()
                            .matches("projection-drift-\\d{4}-\\d{2}-\\d{2}\\.txt"))
                    .max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(report != null,
                "no drift report built yet - run -Pmain=ProjectionDrift");
        String text = Files.readString(report);
        Matcher overall = Pattern.compile("overall: ([\\d.]+)% of shared").matcher(text);
        assertTrue(overall.find(), "the report must state the overall share");
        Matcher totals = Pattern.compile("(?m)^\\d{4}-\\d{2}-\\d{2}\\s+\\d{4}-\\d{2}-\\d{2}\\s+"
                + "(\\d+)\\s+(\\d+)\\s+[\\d.]+\\s+\\S").matcher(text);
        int shared = 0, moved = 0;
        while(totals.find()){
            shared = Integer.parseInt(totals.group(1));   // the last line is the whole-archive row
            moved = Integer.parseInt(totals.group(2));
        }
        assertTrue(shared > 0, "no rows parsed out of the report");
        assertEquals(100.0 * moved / shared, Double.parseDouble(overall.group(1)), 0.15,
                "the printed share is not its own moved-over-shared");
    }
}
