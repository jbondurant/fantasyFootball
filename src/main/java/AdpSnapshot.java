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
                    archiveProjections(today);
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
        archiveProjections(today);
    }

    static final Path PROJECTIONS_CSV = Path.of("data", "projection-snapshots.csv");

    /**
     * The projection archive that makes a future accuracy shootout possible:
     * every available feed's league-scored numbers, one row per player per
     * source per day. Sources only become comparable against actual results
     * once seasons of this exist - which is why it rides along with the ADP
     * snapshot Justin already runs.
     */
    static void archiveProjections(String today) throws IOException {
        if(Files.exists(PROJECTIONS_CSV)){
            for(String line : Files.readAllLines(PROJECTIONS_CSV, StandardCharsets.UTF_8)){
                if(line.startsWith(today + ",")){
                    System.out.println("today's projection archive is already recorded");
                    return;
                }
            }
        }
        List<String> sources = new ArrayList<>(ProjectionSources.automaticSources());
        if(Files.isDirectory(ProjectionBridge.EXTERNAL)){
            for(Path file : Files.list(ProjectionBridge.EXTERNAL).sorted().toList()){
                String name = file.getFileName().toString();
                if(name.endsWith(".csv")){
                    sources.add(name.substring(0, name.length() - 4));
                }
            }
        }
        StringBuilder out = new StringBuilder();
        if(!Files.exists(PROJECTIONS_CSV)){
            out.append("date,source,sleeper_id,league_points\n");
        }
        int rows = 0;
        for(String source : sources){
            for(Map.Entry<String, Double> entry : ProjectionSources.resolve(source).entrySet()){
                if(SleeperProjections.adpOf(entry.getKey()) > 250){
                    continue;
                }
                out.append(String.join(",", today, source, entry.getKey(),
                        String.format("%.1f", entry.getValue()))).append("\n");
                rows++;
            }
        }
        Files.createDirectories(PROJECTIONS_CSV.getParent());
        Files.writeString(PROJECTIONS_CSV, out.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("archived " + rows + " projection rows ("
                + String.join(", ", sources) + ") for " + today);
    }

}
