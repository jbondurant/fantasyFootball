import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * The suite runs on the Sleeper feed as fetched on draft night, served from the
 * fixture directory - projections, ADP and injury tags for every man, defences
 * and the undrafted included. Run without -DfixtureDir the first test fails on
 * purpose.
 */
public class FeedPinTest {

    @Test
    public void theSuiteReadsTheDraftNightFeed(){
        String dir = System.getProperty("fixtureDir");
        assertNotNull(dir, "build.gradle must set fixtureDir for the unit tests");
        assertTrue(new java.io.File(dir, "sleeperProjections2026.txt").isFile(),
                "the draft-night Sleeper feed must be committed under " + dir);
        // Jacobs projected 80.2 on draft night (186.1 the day before): the pin is that feed
        assertEquals(34.8, SleeperProjections.adpOf("5850"), 0.05, "Jacobs' ADP in the 20:10 draft-night fetch (35.3 was the next morning - TRAPS #57)");
        assertEquals("NA", SleeperProjections.injuryStatusOf("5850"), "the exempt-list tag as of draft night");
    }

    @Test
    public void aSnapshotReadsOnlyItsDateAndFeed(){
        List<String> lines = List.of("date,source,sleeper_id,league_points",
                "2026-09-01,sleeper,5850,80.2", "2026-09-01,espn,5850,150.0",
                "2026-08-30,sleeper,5850,186.1", "2026-09-01,sleeper,bad,notanumber");
        Map<String, Double> points = ProjectionSources.snapshot(lines, "2026-09-01", "sleeper");
        assertEquals(Map.of("5850", 80.2), points);
    }

    @Test
    public void anAdpSnapshotReadsOnlyItsDate(){
        List<String> lines = List.of("date,sleeper_id,name,position,adp",
                "2026-09-01,5850,Josh Jacobs,RB,35.3", "2026-09-02,5850,Josh Jacobs,RB,35.3", "2026-09-01,4984,Josh Allen,QB,20.7");
        Map<String, Double> adp = SleeperProjections.adpSnapshot(lines, "2026-09-01");
        assertEquals(2, adp.size());
        assertEquals(35.3, adp.get("5850"), 1e-9);
    }

    @Test
    public void theDraftNightSnapshotExistsInTheArchive(){
        assertTrue(ProjectionSources.snapshotPoints("2026-09-01").containsKey("5850"), "Jacobs, 80.2 on draft night");
    }
}
