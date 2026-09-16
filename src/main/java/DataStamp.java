import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * WHICH SNAPSHOT OF THE WORLD A REPORT WAS BUILT FROM.
 *
 * Two artifacts written on the same afternoon can disagree completely and both
 * be correct, because the feeds underneath them move: the projection file is
 * refetched daily and was rewritten at 13:23 one day mid-session, and the player
 * metadata expires weekly and refreshed at 04:29 on another. A date stamp of
 * `LocalDate.now()` records when a file was written, which is not the question -
 * the question is what it was written FROM.
 *
 * `LeagueConsoleTest` compares the drop ladder the console ships against the one
 * TuesdaySwap prints, on the grounds that they are the same search and must
 * agree. It has now failed three times, every one of them because the two
 * artifacts were built hours apart across a feed refresh, and not once because
 * the search disagreed with itself. A test that cries staleness is a test that
 * gets ignored, and the fix is not to keep regenerating - it is to let the
 * artifacts say what they were built from, so the comparison can be skipped
 * honestly when they were built from different things.
 */
public class DataStamp {

    /** The newest projections snapshot on disk, by filename date. */
    static String projections(){
        try(var files = Files.list(Path.of("."))){
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.matches("sleeperProjections\\d{4}\\d{4}-\\d{2}-\\d{2}\\.txt"))
                    .max(Comparator.naturalOrder())
                    .map(name -> name.substring(name.length() - 14, name.length() - 4))
                    .orElse("none");
        }
        catch(Exception unreadable){
            return "none";
        }
    }

    /** The day the player metadata was last fetched, which expires weekly. */
    static String metadata(){
        File file = new File("sleeperDataPlayerAPI.json");
        return file.exists()
                ? java.time.Instant.ofEpochMilli(file.lastModified())
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
                : "none";
    }

    /**
     * The stamp itself, with NO label on the front.
     *
     * It carried a "data: " prefix, and the report printed it whole while the
     * console shipped it whole and the test stripped the prefix from one side
     * only - so the two never matched and the comparison skipped every single
     * time. A guard that always fires is not a guard, it is a deleted test that
     * still shows up in the count. The label belongs to whoever is printing,
     * which is why it now lives at the call site.
     */
    public static String stamp(){
        return "projections " + projections() + ", player metadata " + metadata();
    }

    /** The labelled form, for a text report's header. */
    public static String line(){
        return "data: " + stamp();
    }
}
