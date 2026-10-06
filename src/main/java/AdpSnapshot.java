import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Appends today's Sleeper ADP to a committed CSV, one row per player per day.
 *
 * This exists because the signal Justin wanted from mock drafts - which
 * players the market is warming to late in the preseason - is not recoverable
 * from Sleeper (mocks are not enumerable per user, and historical mocks 404
 * even by id). Late ADP drift is the collectible cousin: run this every day
 * or two before the draft and the movers fall out.
 *
 * Report-only by design: there is no historical time series to backtest drift
 * against, so it cannot pass the gate and does NOT feed the fitted model. It
 * informs the human, not the simulator.
 *
 *     ./gradlew run -Pmain=AdpSnapshot
 */
public class AdpSnapshot {

    static final Path CSV = Path.of("data", "adp-snapshots.csv");

    public record Mover(String name, String position, double from, double to) {}

    /** Biggest ADP changes between two snapshots (maps of id -> adp). */
    static List<Mover> movers(Map<String, double[]> byPlayer, Map<String, String> labels, int top){
        List<Mover> all = new ArrayList<>();
        for(Map.Entry<String, double[]> entry : byPlayer.entrySet()){
            double from = entry.getValue()[0];
            double to = entry.getValue()[1];
            if(from > 0 && to > 0 && Math.abs(from - to) >= 0.05){
                String[] label = labels.getOrDefault(entry.getKey(), "?|?").split("\\|");
                all.add(new Mover(label[0], label[1], from, to));
            }
        }
        all.sort(Comparator.comparingDouble((Mover m) -> Math.abs(m.to() - m.from())).reversed());
        return all.subList(0, Math.min(top, all.size()));
    }

    public static void main(String[] args) throws IOException {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String today = DateStuff.DateUtility.getTodaysDate();

        Map<String, Double> previous = new HashMap<>();
        String previousDate = null;
        if(Files.exists(CSV)){
            for(String line : Files.readAllLines(CSV, StandardCharsets.UTF_8)){
                String[] cells = line.split(",");
                if(cells.length < 5 || cells[0].equals("date")){
                    continue;
                }
                if(cells[0].equals(today)){
                    System.out.println("today's ADP snapshot is already recorded");
                    exitOnFailure(archiveProjections(today));
                    return;
                }
                if(previousDate == null || cells[0].compareTo(previousDate) >= 0){
                    if(!cells[0].equals(previousDate)){
                        previous.clear();
                        previousDate = cells[0];
                    }
                    previous.put(cells[1], Double.parseDouble(cells[4]));
                }
            }
        }

        StringBuilder out = new StringBuilder();
        if(!Files.exists(CSV)){
            out.append("date,sleeper_id,name,position,adp\n");
        }
        Map<String, double[]> forMovers = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        int rows = 0;
        for(JsonElement element : SleeperProjections.getTodaysProjections()){
            JsonObject row = element.getAsJsonObject();
            JsonObject stats = row.getAsJsonObject("stats");
            if(stats == null || stats.get("adp_half_ppr") == null || stats.get("adp_half_ppr").isJsonNull()){
                continue;
            }
            String sleeperID = row.get("player_id").getAsString();
            Player player = Player.getPlayerFromSIDV2(sleeperID);
            if(player == null || !StartingLineup.isSkillPosition(player.position)){
                continue;
            }
            double adp = stats.get("adp_half_ppr").getAsDouble();
            if(adp > 250){
                continue;
            }
            out.append(String.join(",", today, sleeperID,
                    (player.firstName + " " + player.lastName).replace(",", " "),
                    player.position.name(), String.valueOf(adp))).append("\n");
            rows++;
            if(previous.containsKey(sleeperID)){
                forMovers.put(sleeperID, new double[]{previous.get(sleeperID), adp});
                labels.put(sleeperID, player.firstName + " " + player.lastName + "|" + player.position);
            }
        }
        Files.createDirectories(CSV.getParent());
        Files.writeString(CSV, out.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("recorded " + rows + " players for " + today);

        if(previousDate != null){
            System.out.println("\nbiggest moves since " + previousDate + " (negative = rising, being reached for):");
            for(Mover mover : movers(forMovers, labels, 15)){
                System.out.printf("   %-24s %-3s %7.1f -> %-7.1f (%+.1f)%n",
                        mover.name(), mover.position(), mover.from(), mover.to(),
                        mover.to() - mover.from());
            }
        }
        exitOnFailure(archiveProjections(today));
    }

    /** A feed that failed ends the run non-zero, after everything else has been written. */
    static void exitOnFailure(List<String> failed){
        if(!failed.isEmpty()){
            System.err.println(failed.size() + " projection feed(s) failed to archive; the rest were written");
            System.exit(1);
        }
    }

    static final Path PROJECTIONS_CSV = Path.of("data", "projection-snapshots.csv");

    /**
     * The projection archive that makes a future accuracy shootout possible:
     * every available feed's league-scored numbers, one row per player per
     * feed per day. Sources only become comparable against actual results
     * once seasons of this exist - which is why it rides along with the ADP
     * snapshot Justin already runs.
     *
     * Each feed is read, and written, on its own. The first version resolved
     * all four and wrote once at the end, and checked "already recorded" by
     * the date alone - so when CBS began serving its week page in season and
     * its guard threw, nothing was written for anyone, on 11 and 25 September
     * alike, while the ADP half of the same run succeeded and was committed
     * (TRAPS #147). Now a feed that fails is named and the others land; a feed
     * already recorded today is skipped, so a rerun after a fix fills in only
     * what is missing; and the run exits non-zero when any feed failed, so the
     * failure is seen the day it happens rather than three weeks later.
     *
     * Rows are each feed's OWN men (ProjectionSources.own), never the planner's
     * map merged over Sleeper (TRAPS #139); the rows of espn, cbs and borischen
     * archived before 2026-09-25 are that merged map, and the "sleeper" rows'
     * defences before then are the season feed's four-category stub
     * (TRAPS #138). In season the feeds are the ones ProjectionSources
     * .archiveFeeds names for what they now serve.
     */
    static List<String> archiveProjections(String today) throws IOException {
        List<String> lines = Files.exists(PROJECTIONS_CSV)
                ? Files.readAllLines(PROJECTIONS_CSV, StandardCharsets.UTF_8) : List.of();
        boolean inSeason = LeagueWeek.inSeason();
        List<String> feeds = new ArrayList<>(ProjectionSources.archiveFeeds(inSeason));
        if(Files.isDirectory(ProjectionBridge.EXTERNAL)){
            for(Path file : Files.list(ProjectionBridge.EXTERNAL).sorted().toList()){
                String name = file.getFileName().toString();
                if(name.endsWith(".csv")){
                    feeds.add(name.substring(0, name.length() - 4));
                }
            }
        }
        // this week's projections at the positions the league starts - the
        // weekly feed also projects kickers, which nobody here rosters
        Map<String, Double> thisWeek = new HashMap<>();
        if(inSeason){
            LeagueWeek.projected(LeagueWeek.season(), LeagueWeek.week()).forEach((id, points) -> {
                Player player = Player.getPlayerFromSIDV2(id);
                if(player != null && player.position != null && (StartingLineup.isSkillPosition(player.position)
                        || player.position == PlayerImportAndSetup.Position.DEF)){
                    thisWeek.put(id, points);
                }
            });
        }
        if(!Files.exists(PROJECTIONS_CSV)){
            Files.createDirectories(PROJECTIONS_CSV.getParent());
            Files.writeString(PROJECTIONS_CSV, "date,source,sleeper_id,league_points\n", StandardCharsets.UTF_8);
        }
        List<String> failed = archive(today, feeds, recordedOn(lines, today), ProjectionSources::own,
                id -> kept(inSeason, SleeperProjections.adpOf(id), thisWeek.get(id)),
                rows -> {
                    try {
                        Files.writeString(PROJECTIONS_CSV, rows, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                    }
                    catch(IOException unwritable){
                        throw new java.io.UncheckedIOException(unwritable);
                    }
                });
        for(String failure : failed){
            System.out.println("FAILED " + failure);
        }
        return failed;
    }

    /** The feeds already archived on {@code day}. */
    static java.util.Set<String> recordedOn(List<String> lines, String day){
        java.util.Set<String> out = new java.util.TreeSet<>();
        for(String line : lines){
            String[] cells = line.split(",", 3);
            if(cells.length >= 2 && cells[0].equals(day)){
                out.add(cells[1]);
            }
        }
        return out;
    }

    /**
     * Who is archived: before the season, the preseason board (ADP 250 or
     * better). In season also any man Sleeper projects for 3+ points this
     * week - the men a waiver or a trade is decided over, most of whom have no
     * preseason ADP (TRAPS #141).
     */
    static boolean kept(boolean inSeason, double adp, Double thisWeek){
        return adp <= 250 || (inSeason && thisWeek != null && thisWeek >= 3);
    }

    /**
     * The loop, with its effects passed in: each feed not yet recorded is read
     * and appended on its own, and one that throws, or answers with nobody, is
     * reported by name while the rest go on. Returns the failures.
     */
    static List<String> archive(String today, List<String> feeds, java.util.Set<String> recorded,
                                java.util.function.Function<String, Map<String, Double>> own,
                                java.util.function.Predicate<String> keep,
                                java.util.function.Consumer<String> append){
        List<String> failed = new ArrayList<>();
        List<String> done = new ArrayList<>();
        for(String feed : feeds){
            if(recorded.contains(feed)){
                done.add(feed + " (already)");
                continue;
            }
            try {
                StringBuilder rows = new StringBuilder();
                int n = 0;
                for(Map.Entry<String, Double> entry : new java.util.TreeMap<>(own.apply(feed)).entrySet()){
                    if(keep.test(entry.getKey())){
                        rows.append(String.join(",", today, feed, entry.getKey(),
                                String.format("%.1f", entry.getValue()))).append("\n");
                        n++;
                    }
                }
                if(n == 0){
                    throw new IllegalStateException("answered with nobody on the board");
                }
                append.accept(rows.toString());
                done.add(feed + " " + n);
            }
            catch(RuntimeException problem){
                failed.add(feed + ": " + problem.getMessage());
            }
        }
        System.out.println("projection archive " + today + ": " + String.join(", ", done)
                + (failed.isEmpty() ? "" : "; " + failed.size() + " failed"));
        return failed;
    }

}
