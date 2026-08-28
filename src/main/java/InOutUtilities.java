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
            String content = WebUrlUtility.urlToString(webURL);
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
            downloadTodaysWebPage(webURL, filepathStart);
        }

        try {
            return Files.readString(Path.of(todaysFilePath));
        } catch (IOException e) {
            throw new RuntimeException("could not read cached " + todaysFilePath, e);
        }
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
