import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A FIELD THE PAGE READS MUST BE A FIELD THE PAGE SHIPS.
 *
 * LeagueConsoleTest checks that every number in the JSON is the number the model
 * computes. Nothing checked that the JavaScript can find those numbers. Rename a
 * field in the Java emitter and forget the script, and every affected cell
 * renders "undefined" - or the script throws on the first row and EVERY TAB
 * COMES UP BLANK - while all of the data tests still pass, because the data is
 * still correct. The page would be broken and green.
 *
 * That is the same silent-failure shape as a two-week-old cache and a test that
 * skips: confident output from something that did not happen. This is the cheap
 * half of the guard - it cannot prove the page renders, but it catches the
 * mistake that would most plausibly stop it.
 */
public class ConsoleRendersTest {

    static Path newestConsole() throws Exception {
        Pattern named = Pattern.compile("console-(\\d{4})-w(\\d+)\\.html");
        try(var files = Files.list(Path.of("data"))){
            return files.filter(p -> named.matcher(p.getFileName().toString()).matches())
                    .max(Comparator.comparingLong(p -> {
                        Matcher m = named.matcher(p.getFileName().toString());
                        return m.matches() ? Long.parseLong(m.group(1)) * 100 + Long.parseLong(m.group(2))
                                : Long.MIN_VALUE;
                    })).orElse(null);
        }
    }

    /** Every key the emitter writes into the shipped JSON, at any nesting. */
    static Set<String> shippedKeys(String page){
        String data = page.substring(page.indexOf("const D = "));
        Set<String> keys = new TreeSet<>();
        Matcher key = Pattern.compile("\"([a-zA-Z][a-zA-Z0-9]*)\"\\s*:").matcher(
                data.substring(0, Math.min(data.indexOf("\n"), data.length())));
        while(key.find()){
            keys.add(key.group(1));
        }
        return keys;
    }

    @Test
    public void everyFieldTheScriptReadsIsOneTheDataShips() throws Exception {
        Path console = newestConsole();
        org.junit.jupiter.api.Assumptions.assumeTrue(console != null,
                "no console page built yet - run -Pmain=LeagueConsole");
        String page = Files.readString(console);
        Set<String> shipped = shippedKeys(page);
        assertTrue(shipped.size() > 20,
                "only " + shipped.size() + " keys parsed out of the shipped data, which means"
                        + " this test is reading the wrong thing and proves nothing");

        String script = page.substring(page.indexOf("const D = "));
        Set<String> missing = new TreeSet<>();
        // D.foo on the top-level object, and r.foo / m.foo / x.foo on its rows
        Matcher read = Pattern.compile("\\b(?:D|r|m|x|c)\\.([a-zA-Z][a-zA-Z0-9]*)\\b").matcher(script);
        while(read.find()){
            String field = read.group(1);
            if(JS_BUILTINS.contains(field) || shipped.contains(field)){
                continue;
            }
            missing.add(field);
        }
        assertEquals(Set.of(), missing,
                "the page reads these fields and the data does not carry them, so they render"
                        + " as undefined or throw: " + missing);
    }

    /** Things that are JavaScript, not data. */
    static final Set<String> JS_BUILTINS = Set.of(
            "length", "toFixed", "map", "filter", "forEach", "slice", "sort", "join",
            "toLocaleString", "textContent", "innerHTML", "value", "hidden", "dataset",
            "children", "className", "classList", "reduce", "includes", "toLowerCase",
            "querySelector", "querySelectorAll", "getAttribute", "setAttribute", "onclick",
            "addEventListener", "appendChild", "replace", "trim", "split", "push", "concat");

    /** And the page must not have shipped an empty data object. */
    @Test
    public void theDataObjectIsNotEmpty() throws Exception {
        Path console = newestConsole();
        org.junit.jupiter.api.Assumptions.assumeTrue(console != null, "no console page built yet");
        String page = Files.readString(console);
        for(String array : new String[]{"lineup", "trades", "wire", "supply", "keepers2027", "faab"}){
            Matcher empty = Pattern.compile("\"" + array + "\":\\[\\]").matcher(page);
            assertFalse(empty.find(),
                    "the page shipped an EMPTY " + array + " array; every tab that reads it is"
                            + " blank and no data test would notice");
        }
    }
}
