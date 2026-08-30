import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * How far from each draft the Abusing Draft Rankings feeds actually sit.
 *
 * The ADR sheets are the platforms' DEFAULT draft-room rankings - the literal
 * on-screen order the room drafts from - so a stale one is not a small error:
 * a sheet exported months after a draft describes a board nobody ever saw. The
 * first pull of these sheets took them LIVE at their Google ids, which stamps
 * them with whenever the author last touched them, not with draft day. This
 * tool puts the two dates side by side and says, per season, whether the feed
 * is admissible as a pre-draft signal.
 *
 * Draft dates come from the league chain itself (the same walk DraftDates
 * makes), never from a copy kept in a file, so the comparison cannot drift.
 *
 *     ./gradlew run -Pmain=AdrProvenance
 */
public class AdrProvenance {

    static final Path DATA = Path.of("data");
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** captured is null when nothing establishes when the feed was taken. */
    record Feed(String season, LocalDate captured, int players, String file,
                boolean verified, String source) {}

    static final Path PROVENANCE = Path.of("data", "adr", "provenance.csv");

    /**
     * Every dated defaults CSV, with its capture date read from the provenance
     * manifest rather than from its own filename.
     *
     * Filenames were the bug. Commit 2bd97be extracted every 2021-2024 defaults
     * file from the same mid-July ADR workbooks and named them after each
     * season's DRAFT date, so this tool read 20220901 off a file holding the
     * 16 July board and certified it as three days pre-draft. A name is not
     * provenance; it is a label anyone can type. The manifest records what
     * actually produced each file, and every entry marked verified was
     * reproduced rank-for-rank by re-running the extractor on the named
     * workbook. Anything the manifest does not cover is reported as unverified
     * rather than believed.
     */
    static List<Feed> feeds() throws IOException {
        Map<String, String[]> manifest = new HashMap<>();
        if(Files.exists(PROVENANCE)){
            List<String> lines = Files.readAllLines(PROVENANCE, StandardCharsets.UTF_8);
            for(String line : lines.subList(1, lines.size())){
                String[] cells = line.split(",", 6);
                if(cells.length >= 5){
                    manifest.put(cells[0].trim(), cells);
                }
            }
        }
        List<Feed> found = new ArrayList<>();
        try(var files = Files.list(DATA)){
            for(Path file : files.toList()){
                String name = file.getFileName().toString();
                if(!name.matches("sleeper-defaults-\\d{4}-\\d{8}\\.csv")){
                    continue;
                }
                String[] parts = name.replace(".csv", "").split("-");
                int rows = (int) Files.lines(file, StandardCharsets.UTF_8).skip(1)
                        .filter(line -> !line.isBlank()).count();
                String[] row = manifest.get(name);
                LocalDate captured = null;
                boolean verified = false;
                String source = "not in the provenance manifest";
                if(row != null){
                    verified = row[4].trim().equalsIgnoreCase("yes");
                    source = row[3].trim();
                    if(!row[2].trim().equalsIgnoreCase("UNKNOWN")){
                        captured = LocalDate.parse(row[2].trim(), STAMP);
                    }
                }
                found.add(new Feed(parts[2], captured, rows, name, verified, source));
            }
        }
        found.sort(Comparator.comparing(Feed::season)
                .thenComparing(feed -> feed.captured() == null ? LocalDate.MIN
                        : feed.captured()));
        return found;
    }

    /** name -> sleeper default rank, from one dated defaults CSV. */
    static Map<String, Double> ranks(String file) throws IOException {
        Map<String, Double> byName = new HashMap<>();
        List<String> lines = Files.readAllLines(DATA.resolve(file), StandardCharsets.UTF_8);
        if(lines.isEmpty()){
            return byName;
        }
        String[] header = lines.get(0).split(",");
        int nameColumn = -1;
        int rankColumn = -1;
        for(int column = 0; column < header.length; column++){
            if(header[column].equals("name")){
                nameColumn = column;
            }
            if(header[column].equals("sleeper_rank")){
                rankColumn = column;
            }
        }
        if(nameColumn < 0 || rankColumn < 0){
            return byName;
        }
        for(String line : lines.subList(1, lines.size())){
            String[] cells = line.split(",");
            if(cells.length <= Math.max(nameColumn, rankColumn)){
                continue;
            }
            try {
                byName.put(cells[nameColumn], Double.parseDouble(cells[rankColumn]));
            }
            catch(NumberFormatException unparseable){ /* blank row */ }
        }
        return byName;
    }

    public static void main(String[] args) throws Exception {
        Map<String, LocalDate> draftDates = DraftDates.byLeagueSeason();
        List<Feed> feeds = feeds();

        System.out.println("ADR / Sleeper-defaults feeds against the real draft dates");
        System.out.printf("%-8s %-12s %-12s %8s %7s  %s%n",
                "SEASON", "CAPTURED", "DRAFT", "DAYS", "PLAYERS", "admissible?");
        for(Feed feed : feeds){
            LocalDate draft = draftDates.get(feed.season());
            if(feed.captured() == null || !feed.verified()){
                System.out.printf("%-8s %-12s %-12s %8s %7d  %s%n", feed.season(),
                        feed.captured() == null ? "UNKNOWN" : feed.captured(),
                        draft == null ? "unknown" : draft.toString(), "-",
                        feed.players(),
                        "NO - provenance unverified (" + feed.source() + ")");
                continue;
            }
            if(draft == null){
                System.out.printf("%-8s %-12s %-12s %8s %7d  %s%n", feed.season(),
                        feed.captured(), "unknown", "-", feed.players(),
                        "season not in this league's chain");
                continue;
            }
            long days = ChronoUnit.DAYS.between(feed.captured(), draft);
            String verdict = days < 0
                    ? "NO - captured " + (-days) + "d AFTER the draft"
                    : days > 45 ? "stale - " + days + "d early" : "yes";
            System.out.printf("%-8s %-12s %-12s %8d %7d  %s%n", feed.season(),
                    feed.captured(), draft, days, feed.players(), verdict);
        }

        // Consecutive captures within a season are the only place drift in the
        // default board can be seen at all; for 2026 that drift is live news.
        Map<String, List<Feed>> bySeason = new TreeMap<>();
        for(Feed feed : feeds){
            bySeason.computeIfAbsent(feed.season(), season -> new ArrayList<>()).add(feed);
        }
        for(Map.Entry<String, List<Feed>> entry : bySeason.entrySet()){
            List<Feed> season = entry.getValue();
            for(int index = 1; index < season.size(); index++){
                Feed before = season.get(index - 1);
                Feed after = season.get(index);
                if(before.captured() == null || after.captured() == null){
                    continue;   // cannot call it drift without two real dates
                }
                Map<String, Double> from = ranks(before.file());
                Map<String, Double> to = ranks(after.file());
                List<String> movers = new ArrayList<>(from.keySet());
                movers.retainAll(to.keySet());
                movers.sort(Comparator.comparingDouble(
                        name -> -Math.abs(to.get(name) - from.get(name))));
                System.out.printf("%n%s default-board drift %s -> %s (%d shared players)%n",
                        entry.getKey(), before.captured(), after.captured(), movers.size());
                int shown = 0;
                for(String name : movers){
                    double change = to.get(name) - from.get(name);
                    if(Math.abs(change) < 1 || shown++ >= 15){
                        break;
                    }
                    System.out.printf("   %-26s %6.1f -> %6.1f  %+.1f%n",
                            name, from.get(name), to.get(name), change);
                }
                if(shown == 0){
                    System.out.println("   no player moved a full rank");
                }
            }
        }
    }
}
