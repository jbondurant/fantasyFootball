import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class WebUrlUtility {

    // FantasyPros serves an interstitial to clients that do not look like a
    // browser, and the default "Java/xx" agent is one of them.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    /**
     * Throws rather than returning null on failure. The old version swallowed
     * the exception and returned null, which got cached to disk as the string
     * "null" and poisoned every run for the rest of the day.
     */
    public static String urlToString(String webURL){
        try {
            URL url = URI.create(webURL).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "*/*");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);

            int responseCode = connection.getResponseCode();
            if(responseCode < 200 || responseCode >= 300){
                throw new RuntimeException("HTTP " + responseCode + " from " + webURL);
            }

            String content;
            try (InputStream in = connection.getInputStream()) {
                content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if(content.isBlank()){
                throw new RuntimeException("empty response from " + webURL);
            }
            return content;
        } catch (IOException e) {
            throw new RuntimeException("could not fetch " + webURL, e);
        }
    }

    public static String getLiveWebPage(String webURL){
        return urlToString(webURL);
    }
}
