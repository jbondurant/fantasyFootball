import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DO THE SEASON PROJECTIONS MOVE, OR IS EVERY IN-SEASON NUMBER PRESEASON?
 *
 * Keeper surplus, every trade valuation, and every free agent's worth are
 * computed from the SEASON projection feed. If that feed is frozen at its
 * preseason values then by November a man who has missed six games still carries
 * his August number, and every one of those figures is quietly answering a
 * question about a season that did not happen.
 *
 * On 2026-09-07 - week one, before any game - not one of 586 projections had
 * moved in five days. That is not evidence of a frozen feed: nothing had
 * happened yet, so "has not moved" and "never moves" look identical. It is
 * exactly the shape of the player-metadata bug found the same day (#117), where
 * a file fetched once in August answered every question in September without
 * hesitating, so it is worth settling rather than assuming either way.
 *
 * This settles it. `AdpSnapshot` archives the feed daily; after real games have
 * been played, run this. If the drift is still zero the feed is static and the
 * in-season numbers need actuals blended in. If it moves, nothing to do.
 *
 * The tool deliberately reports the raw counts and the biggest movers rather
 * than a verdict, because the verdict depends on how many games have been played
 * and that is a fact about the calendar, not about the data.
 *
 *   ./gradlew run -Pmain=ProjectionDrift
 */
public class ProjectionDrift {

    /** playerID to season projection, out of one archived snapshot. */
    static Map<String, Double> snapshot(Path file) throws Exception {
        Map<String, Double> points = new HashMap<>();
        JsonElement parsed = JsonParser.parseString(Files.readString(file));
        JsonArray rows = parsed.isJsonArray() ? parsed.getAsJsonArray()
                : parsed.getAsJsonObject().getAsJsonArray("data");
        for(JsonElement element : rows){
            JsonObject row = element.getAsJsonObject();
            if(!row.has("player_id") || !row.has("stats") || row.get("stats").isJsonNull()){
                continue;
            }
            JsonObject stats = row.getAsJsonObject("stats");
            if(stats.has("pts_half_ppr") && !stats.get("pts_half_ppr").isJsonNull()){
                points.put(row.get("player_id").getAsString(), stats.get("pts_half_ppr").getAsDouble());
            }
        }
        return points;
    }

    /** Every archived season-projection snapshot, oldest first. */
    static List<Path> archive() throws Exception {
        try(var files = Files.list(Path.of("."))){
            return files.filter(p -> p.getFileName().toString()
                            .matches("sleeperProjections\\d{4}\\d{4}-\\d{2}-\\d{2}\\.txt"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    /** How many of the shared players moved, and by how much. */
    public record Drift(String from, String to, int shared, int moved, double biggest,
                        String biggestName) {

        public double movedShare(){
            return shared == 0 ? 0 : moved / (double) shared;
        }
    }

    static Drift between(Path older, Path newer, Map<String, String> nameOf) throws Exception {
        Map<String, Double> was = snapshot(older);
        Map<String, Double> now = snapshot(newer);
        int shared = 0, moved = 0;
        double biggest = 0;
        String who = "-";
        for(Map.Entry<String, Double> entry : was.entrySet()){
            Double after = now.get(entry.getKey());
            if(after == null){
                continue;
            }
            shared++;
            double delta = Math.abs(after - entry.getValue());
            if(delta > 0.01){
                moved++;
            }
            if(delta > biggest){
                biggest = delta;
                who = nameOf.getOrDefault(entry.getKey(), entry.getKey());
            }
        }
        return new Drift(stamp(older), stamp(newer), shared, moved, biggest, who);
    }

    static String stamp(Path file){
        String name = file.getFileName().toString();
        return name.substring(name.length() - 14, name.length() - 4);
    }

    public static void main(String[] args) throws Exception {
        List<Path> archive = archive();
        if(archive.size() < 2){
            System.out.println("need at least two archived snapshots; AdpSnapshot writes one a day.");
            return;
        }
        Map<String, String> nameOf = new HashMap<>();
        for(Player player : PlayerRawData.getPlayerMetaData()){
            if(player.sleeperIDString != null){
                nameOf.put(player.sleeperIDString, player.firstName + " " + player.lastName);
            }
        }

        Path oldest = archive.get(0), newest = archive.get(archive.size() - 1);
        Drift whole = between(oldest, newest, nameOf);
        List<Drift> steps = new ArrayList<>();
        for(int i = 1; i < archive.size(); i++){
            steps.add(between(archive.get(i - 1), archive.get(i), nameOf));
        }

        StringBuilder out = new StringBuilder();
        out.append(String.format("PROJECTION DRIFT  %s  (%d archived snapshots)%n%n",
                LocalDate.now(), archive.size()));
        out.append("Keeper surplus, every trade valuation and every free agent's worth are computed from\n");
        out.append("the SEASON projection feed. If it is frozen at preseason values then by November a man\n");
        out.append("who has missed six games still carries his August number.\n\n");
        out.append(String.format("%-12s %-12s %8s %8s %9s   %s%n",
                "FROM", "TO", "SHARED", "MOVED", "BIGGEST", "WHO"));
        for(Drift step : steps){
            out.append(String.format("%-12s %-12s %8d %8d %9.1f   %s%n", step.from(), step.to(),
                    step.shared(), step.moved(), step.biggest(), step.biggestName()));
        }
        out.append(String.format("%n%-12s %-12s %8d %8d %9.1f   %s%n", whole.from(), whole.to(),
                whole.shared(), whole.moved(), whole.biggest(), whole.biggestName()));
        out.append(String.format("%noverall: %.1f%% of shared players moved between the oldest and newest snapshot.%n",
                100 * whole.movedShare()));
        out.append("\nHOW TO READ IT. Before any game is played, zero drift proves nothing - nothing had\n");
        out.append("happened yet. AFTER real games, zero drift means the feed is static and every in-season\n");
        out.append("number here is answering a question about the preseason. That is the moment to blend\n");
        out.append("actuals in, and this tool exists so the moment is noticed rather than assumed away.\n");

        Path target = Path.of("data", "projection-drift-" + LocalDate.now() + ".txt");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.print(out);
        System.out.println("written to " + target);
    }
}
