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

    public static String getTodaysWebPage(String webURL, String filepathStart){
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
            writeContentToFile(WebUrlUtility.urlToString(webURL), filePath);
        }
        try {
            return Files.readString(Path.of(filePath));
        } catch (IOException e) {
            throw new RuntimeException("could not read cached " + filePath, e);
        }
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
