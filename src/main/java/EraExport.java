import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Write the harvested boards to disk so the rest of the repo can use them.
 *
 * The other backtests find their seasons by globbing data/ for
 * fp-adp-halfppr-YYYY-YYYYMMDD.csv, so the obvious move is to write these
 * files under that name and let eight tools pick up eight new seasons for
 * free. That would be a mistake twice over, and both halves are worth saying
 * out loud:
 *
 *   PROVENANCE. These boards are Fantasy Football Calculator PPR, not
 *   FantasyPros half-PPR. This repo already runs an AdpProvenanceAudit because
 *   a feed wearing another feed's name is exactly the sort of thing that
 *   quietly invalidates a season of work. A filename is a claim.
 *
 *   DEPTH. The FantasyPros files are 294-484 rows deep and support the real
 *   sixteen-round game. FFC's boards run 15 rounds - 145-205 joined players -
 *   and PlanBacktest drafts to pick 186. Dropped into that glob, these files
 *   would silently hand every strategy a short roster for the older seasons,
 *   in eight tools at once, and the numbers would still look like numbers.
 *
 * So they are written under their own name. A tool that wants them takes two
 * deliberate steps: widen its glob to include ffc-adp-ppr-*, and cap its draft
 * at the eleven rounds these boards can actually supply.
 *
 *   ./gradlew run -Pmain=EraExport
 */
public class EraExport {

    public static void main(String[] args) throws Exception {
        String format = System.getProperty("format");
        Map<String, EraBoards.Board> boards = EraBoards.usable(format,
                EraIngest.MIN_RATE, EraIngest.minDepth());

        System.out.printf("%nEXPORTING %d HARVESTED SEASONS%n%n", boards.size());
        System.out.printf("%-42s %7s %7s %9s%n", "FILE", "ROWS", "DEPTH", "ROUNDS");
        for(EraBoards.Board board : boards.values()){
            JsonObject json = JsonParser.parseString(
                    EraBoards.adpJson(board.season(), board.format())).getAsJsonObject();
            String drafted = json.getAsJsonObject("meta").get("start_date").getAsString()
                    .replace("-", "");
            Path path = Path.of("data", String.format("ffc-adp-%s-%s-%s.csv",
                    board.format().replace("-", ""), board.season(), drafted));
            write(path, board, json);
            System.out.printf("%-42s %7d %7d %9d%n", path, board.ids().size(),
                    board.match().skill(), board.match().skill() / EraBoards.TEAMS);
        }

        System.out.printf("%nTO USE THESE FROM ANOTHER BACKTEST%n");
        System.out.printf("   1. widen the glob, e.g.%n"
                + "      fp-adp-halfppr-\\d{4}-\\d{8}\\.csv%n"
                + "      -> (fp-adp-halfppr|ffc-adp-ppr)-\\d{4}-\\d{8}\\.csv%n");
        System.out.printf("   2. cap the draft at %d rounds. These boards are 15 rounds"
                + " deep as published%n      and %d-%d players deep once joined;"
                + " a 16-round replay drafting to pick 186%n      runs off the end of"
                + " them and hands out short rosters.%n", EraIngest.rounds(),
                minDepth(boards), maxDepth(boards));
        System.out.printf("   3. grade through LeagueActuals, as PlanBacktest already"
                + " does. These seasons%n      carry no scoring of their own - the CSV"
                + " is a board, nothing more.%n");
    }

    static void write(Path path, EraBoards.Board board, JsonObject json) throws Exception {
        Files.createDirectories(path.getParent());
        try (PrintWriter out = new PrintWriter(path.toFile(), StandardCharsets.UTF_8)) {
            // name/position/AVG are the column names PlanBacktest.board() reads;
            // the rest is provenance, and costs nothing to carry.
            out.println("name,position,team,AVG,source,format,drafts,season");
            String drafts = json.getAsJsonObject("meta").get("total_drafts").getAsString();
            for(JsonElement element : json.getAsJsonArray("players")){
                JsonObject entry = element.getAsJsonObject();
                String position = EraBoards.text(entry, "position");
                if(!PlayerImportAndSetup.Position.isStandardPosition(position)){
                    continue;
                }
                String name = EraBoards.text(entry, "name").replace(",", " ");
                out.printf("%s,%s,%s,%s,fantasyfootballcalculator,%s,%s,%s%n",
                        name, position.equals("DEF") ? "DST" : position,
                        EraBoards.text(entry, "team"), entry.get("adp").getAsString(),
                        board.format(), drafts, board.season());
            }
        }
    }

    static int minDepth(Map<String, EraBoards.Board> boards){
        return boards.values().stream().mapToInt(b -> b.match().skill()).min().orElse(0);
    }

    static int maxDepth(Map<String, EraBoards.Board> boards){
        return boards.values().stream().mapToInt(b -> b.match().skill()).max().orElse(0);
    }
}
