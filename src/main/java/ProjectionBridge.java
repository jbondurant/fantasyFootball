import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Justin's scoring bridge: adapt ANY site's projected points to this
 * league's scoring using Sleeper's projected event counts. A site publishing
 * 4-pt-passing-TD points is off by exactly 2 x (passing TDs) for a 6-pt
 * league, and Sleeper's projected TD count fills that in - no stat-line
 * table needed from the site, just its points and its scoring rules.
 *
 *   league ~= site + (leaguePassTD - sitePassTD) x sleeperPassTDs
 *                  + (leagueRec    - siteRec)    x sleeperReceptions
 *
 * Drop a trusted site's numbers into data/external-projections/<name>.csv:
 *
 *   # passTD=4 rec=0.5
 *   name,position,points
 *   Josh Allen,QB,372.5
 *
 * or sportsbook season props - stat counts, no points conversion needed,
 * scored directly under league settings (headers are Sleeper stat keys):
 *
 *   name,position,pass_yd,pass_td,pass_int,rush_yd,rush_td
 *   Josh Allen,QB,3825.5,28.5,10.5,550.5,10.5
 *
 * and it becomes a planner source via -Pprojections=<name>. The main
 * validates the bridge against the one fetchable site that publishes both
 * points and stat lines, then diffs every configured source against Sleeper.
 *
 *     ./gradlew run -Pmain=ProjectionBridge
 */
public class ProjectionBridge {

    static final Path EXTERNAL = Path.of("data", "external-projections");

    /** The bridge itself, injectable counts for tests. */
    public static Map<String, Double> bridge(Map<String, Double> sitePoints,
                                             double sitePassTD, double siteReception,
                                             LeagueScoringSettings league,
                                             Map<String, Double> passTDs,
                                             Map<String, Double> receptions){
        Map<String, Double> out = new HashMap<>();
        for(Map.Entry<String, Double> entry : sitePoints.entrySet()){
            out.put(entry.getKey(), entry.getValue()
                    + (league.passTD - sitePassTD) * passTDs.getOrDefault(entry.getKey(), 0.0)
                    + (league.reception - siteReception)
                            * receptions.getOrDefault(entry.getKey(), 0.0));
        }
        return out;
    }

    /** Sleeper's projected count of one stat, by player. */
    public static Map<String, Double> sleeperStat(String key){
        Map<String, Double> out = new HashMap<>();
        for(JsonElement row : SleeperProjections.getTodaysProjections()){
            JsonObject object = row.getAsJsonObject();
            JsonElement stats = object.get("stats");
            if(stats == null || !stats.isJsonObject()){
                continue;
            }
            JsonElement value = stats.getAsJsonObject().get(key);
            if(value != null && !value.isJsonNull()){
                out.put(object.get("player_id").getAsString(), value.getAsDouble());
            }
        }
        return out;
    }

    /** A CSV source bridged to league scoring; null if the file is absent. */
    public static Map<String, Double> externalSource(String name){
        Path file = EXTERNAL.resolve(name + ".csv");
        if(!Files.exists(file)){
            return null;
        }
        try {
            return parseSource(Files.readAllLines(file),
                    SleeperLeague.getSeriousLeague().league.leagueScoringSettings,
                    sleeperStat("pass_td"), sleeperStat("rec"));
        } catch (Exception unusable){
            return null;
        }
    }

    /**
     * Two formats, told apart by the header row: "name,position,points" is a
     * points sheet needing the bridge; any longer header names Sleeper stat
     * keys (sportsbook props - counts) and scores directly, no bridge at all.
     */
    static Map<String, Double> parseSource(List<String> lines, LeagueScoringSettings league,
                                           Map<String, Double> passTDs,
                                           Map<String, Double> receptions){
        double sitePassTD = 4;
        double siteReception = 0.5;
        String[] header = null;
        Map<String, Double> sitePoints = new HashMap<>();
        Map<String, Double> scored = new HashMap<>();
        for(String line : lines){
            line = line.trim();
            if(line.isEmpty()){
                continue;
            }
            if(line.startsWith("#")){
                for(String token : line.substring(1).trim().split("\\s+")){
                    if(token.startsWith("passTD=")){
                        sitePassTD = Double.parseDouble(token.substring(7));
                    }
                    if(token.startsWith("rec=")){
                        siteReception = Double.parseDouble(token.substring(4));
                    }
                }
                continue;
            }
            String[] parts = line.split(",");
            if(parts[0].trim().equalsIgnoreCase("name")){
                header = parts;
                continue;
            }
            if(parts.length < 3){
                continue;
            }
            Player player = Player.getPlayerFromNameAndPos(parts[0].trim(),
                    Position.valueOf(parts[1].trim().toUpperCase()));
            if(player == null){
                continue;
            }
            boolean props = header != null && header.length > 3;
            if(props){
                JsonObject stats = new JsonObject();
                for(int column = 2; column < parts.length && column < header.length; column++){
                    stats.addProperty(header[column].trim(),
                            Double.parseDouble(parts[column].trim()));
                }
                scored.put(player.sleeperIDString,
                        SleeperProjections.scoreStatLine(stats, league));
            }
            else {
                sitePoints.put(player.sleeperIDString, Double.parseDouble(parts[2].trim()));
            }
        }
        if(!sitePoints.isEmpty()){
            scored.putAll(bridge(sitePoints, sitePassTD, siteReception, league,
                    passTDs, receptions));
        }
        return scored;
    }

    /**
     * The planner's projection feed: "sleeper" (default), or the name of a
     * bridged CSV in data/external-projections. Unknown names fall back to
     * Sleeper so a typo cannot silently change the model's world.
     */
    public static Map<String, Double> pointsForSource(String source){
        if(source == null || source.isEmpty() || source.equals("sleeper")){
            return SleeperProjections.parseTodaysWebPage();
        }
        Map<String, Double> external = externalSource(source);
        if(external == null){
            System.out.println("projection source '" + source + "' not found - using sleeper");
            return SleeperProjections.parseTodaysWebPage();
        }
        // Players the source does not cover keep Sleeper's number, so a
        // 150-player sheet does not zero out the rest of the board.
        Map<String, Double> merged = new HashMap<>(SleeperProjections.parseTodaysWebPage());
        merged.putAll(external);
        return merged;
    }

    public static void main(String[] args){
        LeagueScoringSettings league =
                SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        Map<String, Double> passTDs = sleeperStat("pass_td");
        Map<String, Double> receptions = sleeperStat("rec");
        Map<String, Double> sleeper = SleeperProjections.parseTodaysWebPage();

        // ---- validation: bridged points versus directly scored stat lines,
        // on the one site that publishes both. The residual is exactly the
        // cross-source disagreement in the bridged counts - the irreducible
        // error of using the bridge on a points-only site.
        List<FantasyProsProjections.FpRow> rows = FantasyProsProjections.rows();
        Map<String, Double> sitePoints = new HashMap<>();
        for(FantasyProsProjections.FpRow row : rows){
            sitePoints.put(row.player().sleeperIDString, row.sitePoints());
        }
        Map<String, Double> bridged = bridge(sitePoints, 4, 0.5, league, passTDs, receptions);
        System.out.printf("bridge validation on %d FantasyPros rows "
                + "(their points assumed 4-pt passTD, 0.5 PPR):%n%n", rows.size());
        double[] error = new double[2];
        double worst = 0;
        String worstName = "";
        int qbs = 0;
        for(FantasyProsProjections.FpRow row : rows){
            double direct = SleeperProjections.scoreStatLine(row.stats(), league);
            double viaBridge = bridged.get(row.player().sleeperIDString);
            double gap = Math.abs(direct - viaBridge);
            boolean isQB = row.player().position.equals(Position.QB);
            if(isQB){
                error[0] += gap;
                qbs++;
                if(gap > worst){
                    worst = gap;
                    worstName = row.player().firstName + " " + row.player().lastName;
                }
            }
            else {
                error[1] += gap;
            }
        }
        System.out.printf("   QB mean abs bridge error %.1f points (worst %s %.1f); "
                + "non-QB %.2f%n", error[0] / Math.max(qbs, 1), worstName, worst,
                error[1] / Math.max(rows.size() - qbs, 1));
        System.out.println("   (QB error = 2 x the TD-count disagreement between the site and");
        System.out.println("    Sleeper; everything else passes through nearly untouched)");

        // ---- context: how far the two sources sit apart under league scoring
        System.out.println("\nlargest league-scored disagreements, FantasyPros vs Sleeper");
        System.out.println("(context only - not a production source):\n");
        record Gap(String name, Position position, double fp, double sleeperPoints){}
        List<Gap> gaps = new ArrayList<>();
        for(FantasyProsProjections.FpRow row : rows){
            Double ours = sleeper.get(row.player().sleeperIDString);
            if(ours == null || SleeperProjections.adpOf(row.player().sleeperIDString) > 150){
                continue;
            }
            gaps.add(new Gap(row.player().firstName + " " + row.player().lastName,
                    row.player().position,
                    SleeperProjections.scoreStatLine(row.stats(), league), ours));
        }
        gaps.sort(Comparator.comparingDouble(
                (Gap gap) -> -Math.abs(gap.fp() - gap.sleeperPoints())));
        for(int i = 0; i < 12 && i < gaps.size(); i++){
            Gap gap = gaps.get(i);
            System.out.printf("   %-24s %-3s  fp %6.1f  sleeper %6.1f  %+7.1f%n",
                    gap.name(), gap.position(), gap.fp(), gap.sleeperPoints(),
                    gap.fp() - gap.sleeperPoints());
        }

        // ---- any configured external sources
        if(Files.isDirectory(EXTERNAL)){
            try {
                for(Path file : Files.list(EXTERNAL).sorted().toList()){
                    String name = file.getFileName().toString().replace(".csv", "");
                    Map<String, Double> points = externalSource(name);
                    if(points != null){
                        System.out.printf("%nexternal source '%s': %d players bridged; "
                                + "use -Pprojections=%s%n", name, points.size(), name);
                    }
                }
            } catch (java.io.IOException ignored){
            }
        }
        else {
            System.out.println("\nno external sources yet - drop a trusted site's numbers into");
            System.out.println("data/external-projections/<name>.csv (see class comment) and");
            System.out.println("run the planner with -Pprojections=<name>.");
        }
    }

}
