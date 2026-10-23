import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * DOES A QUESTIONABLE MAN PLAY? From eight seasons of the NFL's own injury
 * reports, joined to who actually took the field.
 *
 * The lineup tab gives every doubtful starter a break-even - the chance of
 * playing he needs before he is the right start over the healthy man behind him
 * - and has always left the chance itself to Justin. This is the chance, as a
 * base rate: every skill player on a regular-season final injury report,
 * 2018-2025 (nflverse's injuries files, approved and downloaded 2026-10-03 into
 * data/nflverse/), by his game status and his last practice of the week, and
 * whether Sleeper's stat rows say he played that week. The join is nflverse's
 * gsis id to Sleeper's player record (1,616 of 1,617 skill rows in 2019).
 *
 * Sleeper's live feed carries the game status and never the practice line
 * (every man's practice_participation was empty on 2026-10-03), so the live
 * number is the status row; the practice rows show how much a Friday report
 * would move it.
 *
 * The table is written to data/play-probability.tsv: eight finished seasons do
 * not change, and NewsCheck reads the file rather than rebuilding it every run.
 *
 *   ./gradlew run -Pmain=PlayProbability
 */
public class PlayProbability {

    static final Path TABLE = Path.of("data", "play-probability.tsv");
    static final List<String> STATUSES = List.of("Questionable", "Doubtful", "Out", "not listed");
    static final List<String> PRACTICE = List.of("Full", "Limited", "DNP", "none");

    /** How many men were listed so, and how many of them played. */
    record Cell(int listed, int played) {
        double rate(){
            return listed == 0 ? Double.NaN : played / (double) listed;
        }
        /** The 95% Wilson interval. */
        double[] wilson(){
            if(listed == 0){
                return new double[]{Double.NaN, Double.NaN};
            }
            double z = 1.96, n = listed, p = rate();
            double centre = (p + z * z / (2 * n)) / (1 + z * z / n);
            double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / (1 + z * z / n);
            return new double[]{centre - half, centre + half};
        }
    }

    static String status(String report){
        return report == null || report.isBlank() ? "not listed" : report.trim();
    }

    static String practice(String line){
        if(line == null || line.isBlank()){
            return "none";
        }
        String l = line.toLowerCase();
        return l.startsWith("full") ? "Full" : l.startsWith("limited") ? "Limited" : l.startsWith("did not") ? "DNP" : "none";
    }

    /** status|practice and status|all -> cell, from {report status, practice status, played} rows. */
    static Map<String, Cell> table(List<String[]> rows){
        Map<String, int[]> counts = new TreeMap<>();
        for(String[] r : rows){
            String s = status(r[0]);
            boolean played = Boolean.parseBoolean(r[2]);
            for(String key : List.of(s + "|" + practice(r[1]), s + "|all")){
                int[] c = counts.computeIfAbsent(key, k -> new int[2]);
                c[0]++;
                c[1] += played ? 1 : 0;
            }
        }
        Map<String, Cell> out = new TreeMap<>();
        counts.forEach((k, c) -> out.put(k, new Cell(c[0], c[1])));
        return out;
    }

    /** Every regular-season skill row of the injury reports, 2018-2025, with whether he played. */
    static List<String[]> history(LeagueScoringSettings scoring) throws IOException {
        Map<String, String> sleeperOf = new HashMap<>();
        try(FileReader reader = new FileReader("sleeperDataPlayerAPI.json")){
            for(Map.Entry<String, JsonElement> e : JsonParser.parseReader(reader).getAsJsonObject().entrySet()){
                JsonObject p = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : null;
                String gsis = p == null ? null : ScreenData.text(p, "gsis_id");
                if(gsis != null && !gsis.isBlank()){
                    sleeperOf.put(gsis.trim(), e.getKey());
                }
            }
        }
        List<String[]> rows = new ArrayList<>();
        for(int y = 2018; y <= 2025; y++){
            String season = String.valueOf(y);
            ScreenData.Season s = ScreenData.load(season, true, false, scoring);
            List<String> lines = Files.readAllLines(Path.of("data", "nflverse", "injuries_" + season + ".csv"), StandardCharsets.UTF_8);
            List<String> header = NflverseWeekly.split(lines.get(0));
            int cWeek = header.indexOf("week"), cType = header.indexOf("game_type"), cGsis = header.indexOf("gsis_id"),
                    cPos = header.indexOf("position"), cReport = header.indexOf("report_status"), cPractice = header.indexOf("practice_status");
            for(String line : lines.subList(1, lines.size())){
                List<String> c = NflverseWeekly.split(line);
                if(c.size() <= cPractice || !"REG".equals(c.get(cType)) || !List.of("QB", "RB", "WR", "TE").contains(c.get(cPos))){
                    continue;
                }
                String id = sleeperOf.get(c.get(cGsis));
                int week = Integer.parseInt(c.get(cWeek));
                if(id == null || !relevant(s, week, id)){
                    continue;
                }
                ScreenData.Line played = s.line(week, id);
                rows.add(new String[]{c.get(cReport), c.get(cPractice), String.valueOf(played != null && played.played())});
            }
        }
        return rows;
    }

    /**
     * A man a lineup decision is about: Sleeper projected him for 5 or more
     * that week or the week before. Without it the table was full of backups -
     * Mitchell Trubisky on a Full practice line, active and never on the field -
     * and "played" (Sleeper's gp, which needs a snap) read 93.6% for men with no
     * game status at all, dragging every row down.
     */
    static boolean relevant(ScreenData.Season s, int week, String id){
        Double now = s.projectedPoints.getOrDefault(week, Map.of()).get(id);
        Double before = s.projectedPoints.getOrDefault(week - 1, Map.of()).get(id);
        return now != null && now >= 5 || before != null && before >= 5;
    }

    /** The status rows from the committed table, for the live tools: status -> share who played. */
    static Map<String, Double> rates(){
        Map<String, Double> out = new HashMap<>();
        try {
            for(String line : Files.readAllLines(TABLE, StandardCharsets.UTF_8)){
                String[] c = line.split("\t");
                if(c.length >= 5 && c[1].equals("all")){
                    out.put(c[0], Double.parseDouble(c[4]));
                }
            }
        }
        catch(IOException | NumberFormatException missing){
            // no table yet: the live tools print "-" rather than a made-up rate
        }
        return out;
    }

    /** "81%" for a listed status, "-" for none or no table. */
    static String label(String status, Map<String, Double> rates){
        Double r = status == null ? null : rates.get(status);
        return r == null ? "-" : String.format("%.0f%%", 100 * r);
    }

    public static void main(String[] args) throws IOException {
        LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        List<String[]> rows = history(scoring);
        Map<String, Cell> table = table(rows);
        StringBuilder out = new StringBuilder();
        StringBuilder tsv = new StringBuilder("status\tpractice\tlisted\tplayed\trate\tlow\thigh\n");
        out.append(String.format("PLAY PROBABILITY  %s  (nflverse final injury reports 2018-2025, regular season, QB/RB/WR/TE,%n"
                + "joined to Sleeper's stat rows: did he take a snap that week; men Sleeper projected for 5+ that week or the week%n"
                + "before - the men a start/sit call is about; %d player-weeks)%n%n", LocalDate.now(), rows.size()));
        out.append(String.format("%-13s %-9s %7s %7s %8s   %s%n", "STATUS", "PRACTICE", "LISTED", "PLAYED", "RATE", "95% interval"));
        for(String s : STATUSES){
            for(String p : new ArrayList<>(List.of("all", "Full", "Limited", "DNP", "none"))){
                Cell c = table.get(s + "|" + p);
                if(c == null){
                    continue;
                }
                double[] w = c.wilson();
                out.append(String.format("%-13s %-9s %7d %7d %7.1f%%   %.1f-%.1f%%%n", p.equals("all") ? s : "", p.equals("all") ? "(any)" : p,
                        c.listed(), c.played(), 100 * c.rate(), 100 * w[0], 100 * w[1]));
                tsv.append(String.format("%s\t%s\t%d\t%d\t%.4f\t%.4f\t%.4f%n", s, p, c.listed(), c.played(), c.rate(), w[0], w[1]));
            }
        }
        out.append("\nPRACTICE is his last practice line of the week. Sleeper's live feed has the status only, so NewsCheck shows\n");
        out.append("the (any) row; a Friday 'Full' or 'DNP' moves it as the rows under it show.\n");
        System.out.print(out);
        Files.writeString(TABLE, tsv.toString(), StandardCharsets.UTF_8);
        Path report = Path.of("data", "play-probability-" + LocalDate.now() + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + TABLE + " and " + report);
    }
}
