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
