import java.net.URL;
import java.net.URLConnection;
import java.util.Scanner;

public class WebUrlUtility {

    public static String urlToString(String webURL){
        String content = null;
        URLConnection connection = null;
        try {
            connection =  new URL(webURL).openConnection();
            Scanner scanner = new Scanner(connection.getInputStream());
            scanner.useDelimiter("\\Z");
            content = scanner.next();
            scanner.close();
        }catch ( Exception ex ) {
            ex.printStackTrace();
        }
        //System.out.println(content);

        return content;
    }

    public static String getLiveWebPage(String webURL){
        return urlToString(webURL);
    }
}
