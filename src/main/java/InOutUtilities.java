import DateStuff.DateUtility;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

public class InOutUtilities {
    
    public static String getThisMonthsMyID(String username){
        String thisMonthsFilePath = "./mySleeperIDOfTheMonth" + DateUtility.getThisMonth() + ".txt";
        File f = new File(thisMonthsFilePath);
        if(!f.exists() || f.isDirectory()) {
            downloadThisMonthsMyID(username);
        }

        try {
            return Files.readString(Path.of(thisMonthsFilePath)).strip();
        } catch (IOException e) {
            throw new RuntimeException("could not read cached " + thisMonthsFilePath, e);
        }
    }

    public static void main(String[] args){
        String thisMonthsMyID = getThisMonthsMyID("justinb314");
        System.out.println(thisMonthsMyID);

    }

    private static void downloadThisMonthsMyID(String myUsername){
        String webURL = "https://api.sleeper.app/v1/user/" + myUsername;

        String webContent = WebUrlUtility.urlToString(webURL);

        JsonObject apiObject = JsonParser.parseString(webContent).getAsJsonObject();

        String myID = "";
        if(!apiObject.get("user_id").isJsonNull()) {
            myID = apiObject.get("user_id").getAsString();
        }

        String thisMonthsFilePath = "./mySleeperIDOfTheMonth" + DateUtility.getThisMonth() + ".txt";


        writeContentToFile(myID, thisMonthsFilePath);
    }

    /**
     * Always refetch, and keep the day-cache only as a lifeboat.
     *
     * A live draft board changes every few seconds, so the day-cache that is
     * right for a projections feed is exactly wrong here: the 2026-08-28 mock
     * rehearsal showed the second run of LiveCommittee reading the board that
     * the first run had written, and on draft night that would have frozen
     * every recommendation after the first pick at a board nobody was looking
     * at any more. Fetch fresh; if the network drops mid-draft, fall back to
     * the last good copy rather than dying on the clock.
     */
    public static String getLiveWebPage(String webURL, String filepathStart){
        String todaysFilePath = "./" + filepathStart + DateUtility.getTodaysDate() + ".txt";
        try {
            String content = WebUrlUtility.urlToStringUncached(webURL);
            writeContentToFile(content, todaysFilePath);
            return content;
        }
        catch(RuntimeException unreachable){
            File cached = new File(todaysFilePath);
            if(cached.exists() && !cached.isDirectory()){
                System.out.println("WARNING: " + webURL + " unreachable - using the "
                        + "last cached copy, which may be several picks stale.");
                try {
                    return Files.readString(Path.of(todaysFilePath));
                }
                catch(IOException unreadable){
                    throw new RuntimeException("could not read cached " + todaysFilePath,
                            unreadable);
                }
            }
            throw unreachable;
        }
    }

    /**
     * The test suite is pinned to a snapshot of the LEAGUE'S STATE. With
     * -DfixtureDir set (build.gradle sets it for the unit tests, never for
     * smokeTest or run), a file named {@code <fixtureDir>/<filepathStart>.txt}
     * is served instead of today's cache, and nothing is fetched for it.
     * Files not in the directory fall through to the normal path, so the
     * projection and ADP feeds still float with the day.
     *
     * Why: on 2026-09-02, the morning after the draft, Sleeper had emptied every
     * roster's keepers field (two per roster the day before, zero after), the
     * planner derived "kept" from it, and four tests written against the
     * pre-draft league failed for a reason no code change had caused. The
     * suite must see the league the tests describe - data/fixtures/2026-pre-draft
     * is the league as it stood on 2026-09-01, keepers declared, no pick made.
     */
    public static String getTodaysWebPage(String webURL, String filepathStart){
        String fixtureDir = System.getProperty("fixtureDir");
        if(fixtureDir != null && !fixtureDir.isBlank()){
            File fixture = new File(fixtureDir, filepathStart + ".txt");
            if(fixture.isFile()){
                try {
                    return java.nio.file.Files.readString(fixture.toPath());
                }
                catch(java.io.IOException unreadable){
                    throw new RuntimeException("fixture exists but cannot be read: " + fixture, unreadable);
                }
            }
        }
        String todaysFilePath = "./" + filepathStart + DateUtility.getTodaysDate() + ".txt";
        File f = new File(todaysFilePath);
        if(!f.exists() || f.isDirectory()) {
            try {
                downloadTodaysWebPage(webURL, filepathStart);
            }
            catch(RuntimeException unreachable){
                // A feed being down must not kill the tool on the clock. The
                // 2026-08-29 lockdown lost the Boris Chen tiers to an SSL
                // handshake failure, and DraftPlanner reads the same source
                // through ProjectionSources - so a flaky S3 bucket could have
                // taken the whole engine down at 20:45 on draft night. These
                // inputs move slowly; yesterday's copy is worth far more than
                // an exception. Live draft picks do NOT come through here -
                // they use getLiveWebPage, which never serves a stale board
                // silently.
                String stale = mostRecentCached(filepathStart);
                if(stale == null){
                    throw unreachable;
                }
                System.out.println("   WARNING: could not fetch " + webURL
                        + "\n   falling back to " + stale + " - this data is STALE");
                try {
                    return Files.readString(Path.of(stale));
                }
                catch(IOException unreadable){
                    throw unreachable;
                }
            }
        }

        try {
            return Files.readString(Path.of(todaysFilePath));
        } catch (IOException e) {
            throw new RuntimeException("could not read cached " + todaysFilePath, e);
        }
    }

    /**
     * The newest dated cache file for this prefix, or null if there is none.
     * Dates are ISO yyyy-MM-dd, so lexicographic order is chronological order.
     */
    static String mostRecentCached(String filepathStart){
        Path prefix = Path.of("./" + filepathStart);
        Path directory = prefix.getParent() == null ? Path.of(".") : prefix.getParent();
        String base = prefix.getFileName().toString();
        String best = null;
        File[] candidates = directory.toFile().listFiles();
        if(candidates == null){
            return null;
        }
        for(File candidate : candidates){
            String name = candidate.getName();
            if(candidate.isFile() && name.startsWith(base) && name.endsWith(".txt")
                    && name.length() > base.length() + 4
                    && (best == null || name.compareTo(best) > 0)){
                best = name;
            }
        }
        return best == null ? null : directory.resolve(best).toString();
    }

    /**
     * Cache with no date suffix, for data that never changes - a finished
     * season's projections, a completed draft. Fetched once, kept forever.
     */
    public static String getCachedForever(String webURL, String filepathStart){
        String filePath = "./" + filepathStart + ".txt";
        File f = new File(filePath);
        if(!f.exists() || f.isDirectory()) {
            String fetched = WebUrlUtility.urlToString(webURL);
            // NEVER FREEZE AN EMPTY PAYLOAD. "Kept forever" and "asked too early"
            // are a bad pair: on 2026-09-04, five days before the season,
            // /v1/stats/nfl/regular/2026/1 returned "{}" and the 2026 DEF stats
            // endpoint returned "[]". Cached, those would have been the answer
            // for the rest of the season - every defence scoring zero, every
            // week-1 actual missing - with nothing to notice it but the numbers
            // being wrong. A week that has not happened is not data; it is a
            // question asked too early, and it must fail loudly.
            if(emptyPayload(fetched)){
                throw new IllegalStateException(webURL + " returned an empty payload ("
                        + fetched.trim() + ") and getCachedForever would keep it forever."
                        + " Nothing was written. If this is a week or a season that has not"
                        + " happened yet, ask again when it has; if it is genuinely empty"
                        + " forever, cache it under a name that says so.");
            }
            writeContentToFile(fetched, filePath);
        }
        try {
            return Files.readString(Path.of(filePath));
        } catch (IOException e) {
            throw new RuntimeException("could not read cached " + filePath, e);
        }
    }

    /** "", "{}", "[]" or "null" - a response carrying no rows. */
    static boolean emptyPayload(String body){
        if(body == null){
            return true;
        }
        String trimmed = body.trim();
        return trimmed.isEmpty() || trimmed.equals("{}") || trimmed.equals("[]")
                || trimmed.equals("null");
    }

    public static void downloadTodaysWebPage(String webURL, String filepathStart){

        // Fetch first, write second: a failed fetch now throws instead of
        // caching the string "null" for the rest of the day.
        String webContent = WebUrlUtility.urlToString(webURL);
        String todaysDate = DateUtility.getTodaysDate();

        String todaysFilePath = "./" + filepathStart + todaysDate + ".txt";

        writeContentToFile(webContent, todaysFilePath);
    }

    private static void writeContentToFile(String content, String filePath) {
        try (PrintWriter out = new PrintWriter(filePath, StandardCharsets.UTF_8)) {
            out.println(content);
        } catch (IOException e) {
            throw new RuntimeException("could not write " + filePath, e);
        }
    }

}
