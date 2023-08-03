import DateStuff.DateUtility;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
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

        String todaysWebpage = null;
        try {
            todaysWebpage = Files.readString(Path.of(thisMonthsFilePath));
        } catch (IOException e) {
            e.printStackTrace();
        }

        return todaysWebpage.strip();
    }

    public static void main(String[] args){
        String thisMonthsMyID = getThisMonthsMyID("justinb314");
        System.out.println(thisMonthsMyID);

    }

    private static void downloadThisMonthsMyID(String myUsername){
        String webURL = "https://api.sleeper.app/v1/user/" + myUsername;

        String webContent = WebUrlUtility.urlToString(webURL);

        JsonParser jp = new JsonParser();
        JsonElement jsonElement = jp.parse(webContent);
        JsonObject apiObject = jsonElement.getAsJsonObject();

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

        String todaysWebpage = null;
        try {
            todaysWebpage = Files.readString(Path.of(todaysFilePath));
        } catch (IOException e) {
            e.printStackTrace();
        }

        return todaysWebpage;
    }

    public static void downloadTodaysWebPage(String webURL, String filepathStart){

        String webContent = WebUrlUtility.urlToString(webURL);
        String todaysDate = DateUtility.getTodaysDate();

        String todaysFilePath = "./" + filepathStart + todaysDate + ".txt";

        writeContentToFile(webContent, todaysFilePath);
    }

    private static void writeContentToFile(String webContent, String thisMonthsFilePath) {
        PrintWriter out = null;
        try {
            out = new PrintWriter(thisMonthsFilePath);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        out.println(webContent);
        out.close();
    }

}
