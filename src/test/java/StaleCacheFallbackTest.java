import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A feed going down must not kill the draft tool on the clock.
 *
 * The 2026-08-29 lockdown lost the Boris Chen tiers to an SSL handshake
 * failure, and DraftPlanner reads the same source through ProjectionSources -
 * so a flaky bucket could have taken the engine down at 20:45 on draft night.
 * getTodaysWebPage now falls back to the newest dated copy it already has.
 * This proves it, rather than trusting that it does.
 */
public class StaleCacheFallbackTest {

    @Test
    void anUnreachableFeedFallsBackToYesterdaysCopy() throws Exception {
        String prefix = "staleCacheFallbackTest";
        Path older = Path.of("./" + prefix + "2020-01-01.txt");
        Path newer = Path.of("./" + prefix + "2020-06-15.txt");
        try {
            Files.writeString(older, "the older copy");
            Files.writeString(newer, "the newer copy");

            // no file for today, and a host that cannot resolve
            String content = InOutUtilities.getTodaysWebPage(
                    "https://this-host-does-not-exist.invalid/feed.txt", prefix);

            assertEquals("the newer copy", content,
                    "must serve the NEWEST cached copy, not just any of them");
        }
        finally {
            Files.deleteIfExists(older);
            Files.deleteIfExists(newer);
            Files.deleteIfExists(Path.of("./" + prefix
                    + DateStuff.DateUtility.getTodaysDate() + ".txt"));
        }
    }

    /** A copy past its age is replaced only once a fresh one is in hand: a failed fetch must keep it, not fall back past it to an older day's. */
    @Test
    void anExpiredCopyFromTodaySurvivesAFailedFetch() throws Exception {
        String prefix = "staleCacheExpiryTest";
        Path yesterday = Path.of("./" + prefix + "2020-01-01.txt");
        Path today = Path.of("./" + prefix + DateStuff.DateUtility.getTodaysDate() + ".txt");
        try {
            Files.writeString(yesterday, "an older day's copy");
            Files.writeString(today, "this morning's copy");
            assertTrue(today.toFile().setLastModified(System.currentTimeMillis() - 3_600_000L));
            assertEquals("this morning's copy", InOutUtilities.getRecentWebPage("https://this-host-does-not-exist.invalid/feed.txt", prefix, 5));
            assertTrue(Files.exists(today));
            assertTrue(InOutUtilities.STALE.contains(today.getFileName().toString()), "and the run knows it was not refreshed");
        }
        finally {
            InOutUtilities.STALE.remove(today.getFileName().toString());
            Files.deleteIfExists(yesterday);
            Files.deleteIfExists(today);
        }
    }

    @Test
    void withNothingCachedItStillThrows() {
        boolean threw = false;
        try {
            InOutUtilities.getTodaysWebPage(
                    "https://this-host-does-not-exist.invalid/feed.txt",
                    "noSuchCacheEverExisted");
        }
        catch(RuntimeException expected){
            threw = true;
        }
        assertTrue(threw, "with no cache at all there is nothing to fall back to,"
                + " and silence would be worse than the exception");
    }

    @Test
    void anUnknownPrefixHasNoNewestCopy() {
        assertNull(InOutUtilities.mostRecentCached("definitelyNotAPrefixInThisRepo"));
    }
}
