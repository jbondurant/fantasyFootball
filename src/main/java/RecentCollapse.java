import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Men whose projection COLLAPSED recently - news the national ADP has not yet
 * priced but a draft room prices the same night.
 *
 * Read from the daily projection archive AdpSnapshot keeps
 * (data/projection-snapshots.csv): a man is collapsed when his latest
 * league-scored projection is at least {@code drop} below his best projection
 * inside the last {@code days} days, and that best was at least {@code floor}
 * points (so a 40-to-20 backup is not "news"). Jacobs on 2026-09-01: 186.1 on
 * every snapshot to 08-30, 80.2 on 09-01 - a 57% collapse the day before the
 * draft, while Sleeper's ADP still said 35.
 *
 * This is deliberately NOT "projection disagrees with ADP": that disagreement
 * is systematic (the feed underrates rookies, the league pays 6 for passing
 * touchdowns) and the room model's positional terms already carry it. Applying
 * a disagreement rule to the held-out seasons made QB timing 2.5 points worse,
 * beyond the noise floor. A collapse is a change in time, and the archive only
 * exists from 2026-08-25, so on historical boards this set is empty and the
 * rule is inert - which is the honest state of what can be known about them.
 */
public class RecentCollapse {

    public record Snapshot(LocalDate date, String id, double points) {}

    /** Parses the archive for one source. Missing file: no rows. */
    static List<Snapshot> read(Path csv, String source) throws IOException {
        List<Snapshot> rows = new java.util.ArrayList<>();
        if(!Files.isRegularFile(csv)){
            return rows;
        }
        for(String line : Files.readAllLines(csv, StandardCharsets.UTF_8)){
            String[] cells = line.split(",");
            if(cells.length < 4 || cells[0].equals("date") || !cells[1].equals(source)){
                continue;
            }
            try {
                rows.add(new Snapshot(LocalDate.parse(cells[0]), cells[2], Double.parseDouble(cells[3])));
            }
            catch(RuntimeException malformed){
                // one bad line must not blank the whole rule
            }
        }
        return rows;
    }

    /**
     * Ids collapsed as of {@code asOf}: latest projection within the window at
     * most (1 - drop) of the window's best, best at least floor.
     */
    static Set<String> collapsed(List<Snapshot> rows, LocalDate asOf, int days, double drop, double floor){
        LocalDate from = asOf.minusDays(days);
        Map<String, Double> best = new HashMap<>();
        Map<String, LocalDate> latestDate = new HashMap<>();
        Map<String, Double> latest = new HashMap<>();
        for(Snapshot row : rows){
            if(row.date().isBefore(from) || row.date().isAfter(asOf)){
                continue;
            }
            best.merge(row.id(), row.points(), Math::max);
            LocalDate seen = latestDate.get(row.id());
            if(seen == null || row.date().isAfter(seen)){
                latestDate.put(row.id(), row.date());
                latest.put(row.id(), row.points());
            }
        }
        Set<String> out = new HashSet<>();
        for(Map.Entry<String, Double> e : best.entrySet()){
            double top = e.getValue();
            if(top >= floor && latest.get(e.getKey()) <= (1 - drop) * top){
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** The production call: Sleeper's archive, last 14 days, a 30% drop from at least 50 points. */
    public static Set<String> today(){
        try {
            return collapsed(read(Path.of("data", "projection-snapshots.csv"), "sleeper"),
                    LocalDate.now(), 14, 0.30, 50.0);
        }
        catch(IOException unreadable){
            return Set.of();
        }
    }
}
