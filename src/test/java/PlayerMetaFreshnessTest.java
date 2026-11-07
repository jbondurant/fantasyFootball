import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * The player metadata is the repo's map of who exists, what position he plays
 * and which team he is on. Every name in every report comes through it.
 *
 * It was fetched once and never again - the condition was "if the file does not
 * exist" - so on the morning of week 1 it was two weeks old and would have
 * stayed that way to January. This pins that it expires.
 */
public class PlayerMetaFreshnessTest {

    @Test
    void theMetadataExpiresRatherThanBeingFetchedOnce(){
        assertTrue(PlayerRawData.STALE_AFTER_DAYS > 0,
                "a staleness bound of zero or less would refetch fifteen megabytes every call");
        assertTrue(PlayerRawData.STALE_AFTER_DAYS <= 14,
                "a bound longer than a fortnight is 'never' with extra steps; the whole point"
                        + " is that rosters and positions move during a season");
    }

    /**
     * And if the file is on disk, it must not be older than the bound the code
     * promises - otherwise the promise is in a comment and not in the data.
     */
    @Test
    void whateverIsOnDiskIsWithinItsOwnBound() throws Exception {
        File file = new File("sleeperDataPlayerAPI.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(file.exists(),
                "no metadata cached here yet; the next run fetches it");
        long ageDays = TimeUnit.MILLISECONDS.toDays(
                System.currentTimeMillis() - file.lastModified());
        assertTrue(ageDays <= PlayerRawData.STALE_AFTER_DAYS + 1,
                "sleeperDataPlayerAPI.json is " + ageDays + " days old against a bound of "
                        + PlayerRawData.STALE_AFTER_DAYS + "; the expiry is not working");
    }
}
