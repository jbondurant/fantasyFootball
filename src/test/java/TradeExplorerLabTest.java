import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The trade explorer's Check-a-trade tab prices trades in the page, in
 * JavaScript, so its arithmetic is a second copy of WeeklyLineupValue's. This
 * runs the page's own code (between its LAB markers) in node: on a small league
 * worked by hand, and on the latest explorer data, where every trades-tab row's
 * six season numbers and cut lists, computed in Java, must come back. Skipped
 * where node is not installed.
 */
public class TradeExplorerLabTest {

    static String labCode() throws IOException {
        String page = Files.readString(Path.of("src/main/resources/trade-explorer.html"), StandardCharsets.UTF_8);
        int from = page.indexOf("/*LAB-BEGIN*/"), to = page.indexOf("/*LAB-END*/");
        assertTrue(from >= 0 && to > from, "the page marks its pricing code");
        return page.substring(from, to);
    }

    static Optional<String> node(){
        for(String candidate : List.of("/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node")){
            if(Files.isExecutable(Path.of(candidate))){
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    static JsonObject run(String script) throws IOException, InterruptedException {
        Path file = Files.createTempFile("lab", ".js");
        Files.writeString(file, script, StandardCharsets.UTF_8);
        Process p = new ProcessBuilder(node().orElseThrow(), file.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "node finished");
        assertEquals(0, p.exitValue(), out);
        Files.delete(file);
        return JsonParser.parseString(out.trim()).getAsJsonObject();
    }

    /**
     * Two managers, active limit 3, one week. Justin's QB 20, RB 10 and RB 4; the
     * rival's RB 12 (on IR in a slot), WR 8 and DEF 6. The wire carries QB 15, RB
     * 5 and 3, WR 6, TE 2, DEF 7. Justin gives his RB 4 for the rival's RB 12 and
     * WR 8: three active men less one plus two is four, so he cuts his
     * lowest-projected man not in the deal, the RB 10. The rival had three active
     * and an IR man; he sends one IR and one active and takes one, so he stays
     * at three and cuts nobody.
     */
    @Test
    public void aSmallLeagueComesOutAsWorkedByHand() throws Exception {
        assumeTrue(node().isPresent(), "node is not installed");
        String lab = """
            {"weeks":[9],"activeLimit":3,"sources":[{"key":"s","label":"S"}],
             "men":[{"id":"a","n":"QB A","p":"QB","o":"me","ir":false,"locked":false,"cut":200},
                    {"id":"b","n":"RB B","p":"RB","o":"me","ir":false,"locked":false,"cut":100},
                    {"id":"c","n":"RB C","p":"RB","o":"me","ir":false,"locked":false,"cut":150},
                    {"id":"d","n":"RB D","p":"RB","o":"him","ir":true,"locked":false,"cut":120},
                    {"id":"e","n":"WR E","p":"WR","o":"him","ir":false,"locked":false,"cut":80},
                    {"id":"f","n":"DEF F","p":"DEF","o":"him","ir":false,"locked":false,"cut":60},
                    {"id":"g","n":"WR G","p":"WR","o":"him","ir":false,"locked":false,"cut":10}],
             "pts":{"s":[[20],[10],[4],[12],[8],[6],[1]]},
             "wire":{"s":[{"QB":[15],"RB":[5,3],"WR":[6],"TE":[2],"DEF":[7]}]}}
            """;
        JsonObject r = run(labCode() + "\nconst lab = " + lab + ";\n"
                + "const r = labPrice(lab, 'me', 'him', [2], [3, 4]);\n"
                + "console.log(JSON.stringify({feasible: r.feasible, myCut: r.myCut, hisCut: r.hisCut, you: r.by.s.you, him: r.by.s.him,"
                + " before: labSeason(lab, 's', [0, 1, 2])}));\n");
        assertTrue(r.get("feasible").getAsBoolean());
        assertEquals("[1]", r.get("myCut").toString(), "his lowest-projected active man not in the deal");
        assertEquals("[]", r.get("hisCut").toString());
        // before: QB 20, RB 10 + 5 (wire), WR 6 (wire) + 0 + 0, TE 2, DEF 7, flex: RB 4 and 3 = 57
        assertEquals(20 + 10 + 5 + 6 + 2 + 7 + 4 + 3, r.get("before").getAsDouble(), 1e-9);
        // after (QB 20, RB 12, WR 8): QB 20, RB 12 + 5, WR 8 + 6, TE 2, DEF 7, flex RB 3 = 63 -> +6
        assertEquals(6, r.get("you").getAsDouble(), 1e-9);
        // he had WR 8, DEF 6, WR 1 active (+RB 12 on IR, projected and so starting): QB 15, RB 12 + 5, WR 8 + 6 + 1,
        // TE 2, DEF 7, flex RB 3 = 59; after (DEF 6, WR 1 and RB 4): QB 15, RB 5 + 4, WR 6 + 1, TE 2, DEF 7, flex 3 = 43
        assertEquals(43 - 59, r.get("him").getAsDouble(), 1e-9);
    }

    @Test
    public void everyTradesTabRowComesBackFromTheLatestData() throws Exception {
        assumeTrue(node().isPresent(), "node is not installed");
        Optional<Path> latest;
        try(Stream<Path> files = Files.list(Path.of("data"))){
            latest = files.filter(f -> f.getFileName().toString().matches("trade-explorer-\\d{4}-\\d{2}-\\d{2}\\.json"))
                    .max(Comparator.comparing(Path::toString));
        }
        assumeTrue(latest.isPresent(), "no explorer data on disk");
        String json = Files.readString(latest.get(), StandardCharsets.UTF_8);
        assumeTrue(json.contains("\"lab\":"), latest.get() + " predates the Check-a-trade tab");
        JsonObject r = run(labCode() + "\nconst DATA = JSON.parse(require('fs').readFileSync(" + JsonParser.parseString("\""
                + latest.get().toAbsolutePath().toString().replace("\\", "\\\\") + "\"") + ", 'utf8'));\n"
                + "console.log(JSON.stringify(checkLab(DATA)));\n");
        assertTrue(r.get("rows").getAsInt() > 0, r.toString());
        assertEquals(0, r.get("unmatched").getAsInt(), "every trades-tab name is a rostered man in the lab: " + r);
        assertEquals(0, r.get("off").getAsInt(), "the page's numbers are the Java ones: " + r);
        assertEquals(0, r.get("cutsOff").getAsInt(), "and the same men are cut: " + r);
    }
}
