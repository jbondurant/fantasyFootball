import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

public class InOutUtilities {

    public static String getTodaysWebPage(String webURL, String filepathStart){
        String fullPathName = "./" + filepathStart + DateUtility.getTodaysDate() + ".txt";
        File f = new File(fullPathName);
        if(!f.exists() || f.isDirectory()) {
            downloadTodaysWebPage(webURL, filepathStart);
        }

        String todaysDate = DateUtility.getTodaysDate();
        String todaysFilePath = "./" + filepathStart + todaysDate + ".txt";
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

        PrintWriter out = null;
        try {
            out = new PrintWriter(todaysFilePath);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        out.println(webContent);
        out.close();
    }



}
