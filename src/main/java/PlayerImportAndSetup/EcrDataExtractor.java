package PlayerImportAndSetup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Pulls the `var ecrData = {...}` object out of a FantasyPros rankings page.
 *
 * Callers used to slice it up with split("\"players\":") and
 * split("var sosData"), which quietly returned a truncated array whenever
 * FantasyPros reordered their JSON keys. Matching braces is not fragile that
 * way.
 */
public class EcrDataExtractor {

    public static JsonObject extract(String entireHTML){
        int marker = entireHTML.indexOf("var ecrData");
        if(marker < 0){
            throw new RuntimeException("no ecrData on page - FantasyPros changed their markup");
        }
        int start = entireHTML.indexOf('{', marker);
        if(start < 0){
            throw new RuntimeException("malformed ecrData on page");
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for(int i = start; i < entireHTML.length(); i++){
            char c = entireHTML.charAt(i);
            if(escaped){
                escaped = false;
                continue;
            }
            if(c == '\\'){
                escaped = true;
                continue;
            }
            if(c == '"'){
                inString = !inString;
                continue;
            }
            if(inString){
                continue;
            }
            if(c == '{'){
                depth++;
            }
            else if(c == '}'){
                depth--;
                if(depth == 0){
                    return JsonParser.parseString(entireHTML.substring(start, i + 1)).getAsJsonObject();
                }
            }
        }
        throw new RuntimeException("unterminated ecrData on page");
    }

}
