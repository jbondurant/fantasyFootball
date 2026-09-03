import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** A collapse is a drop in time, not a disagreement with the market. */
public class RecentCollapseTest {

    private static RecentCollapse.Snapshot row(String d, String id, double pts){
        return new RecentCollapse.Snapshot(LocalDate.parse(d), id, pts);
    }

    @Test
    public void jacobsCollapsesTheDayBeforeTheDraft(){
        List<RecentCollapse.Snapshot> rows = List.of(
                row("2026-08-25", "5850", 186.1), row("2026-08-30", "5850", 186.1), row("2026-09-01", "5850", 80.2),
                row("2026-08-25", "steady", 150.0), row("2026-09-01", "steady", 141.0),
                row("2026-08-25", "backup", 40.0), row("2026-09-01", "backup", 20.0),
                row("2026-08-25", "riser", 48.0), row("2026-09-01", "riser", 137.0));
        Set<String> out = RecentCollapse.collapsed(rows, LocalDate.parse("2026-09-01"), 14, 0.30, 50.0);
        assertEquals(Set.of("5850"), out,
                "a 6% dip is not news, a 40-to-20 backup is under the floor, a riser is not a collapse");
    }

    @Test
    public void onlyTheWindowCounts(){
        List<RecentCollapse.Snapshot> rows = List.of(
                row("2026-07-01", "old", 200.0), row("2026-09-01", "old", 100.0));
        assertTrue(RecentCollapse.collapsed(rows, LocalDate.parse("2026-09-01"), 14, 0.30, 50.0).isEmpty(),
                "the 200 was two months ago; inside the window he has always been 100");
    }

    @Test
    public void noArchiveMeansNoRule(){
        assertTrue(RecentCollapse.collapsed(List.of(), LocalDate.parse("2024-09-01"), 14, 0.30, 50.0).isEmpty(),
                "historical boards have no archive, so the rule is inert there");
    }
}
