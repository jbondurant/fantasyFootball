import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * The suite is pinned to the league as it stood before the 2026 draft.
 *
 * The morning after the draft Sleeper had emptied every roster's keepers
 * field, the planner saw a league with no keepers, and four tests failed with
 * no code change behind them. This is the pin, and this test is what says the
 * pin is in: run without -DfixtureDir it fails on purpose.
 */
public class PreDraftFixtureTest {

    @Test
    public void theSuiteRunsAgainstThePreDraftLeague(){
        String dir = System.getProperty("fixtureDir");
        assertNotNull(dir, "build.gradle must set fixtureDir for the unit tests");
        assertTrue(new java.io.File(dir, "seriousRostersForKeepers1390416723210952704.txt").isFile(),
                "the pre-draft rosters snapshot must be committed under " + dir);
    }

    @Test
    public void everyRosterStillHoldsItsTwoKeepers(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        assertEquals(24, configuration.getTodaysKeepers().size(),
                "12 rosters x 2 keepers, as declared on 2026-09-01 - after the draft Sleeper reports 0");
    }
}
