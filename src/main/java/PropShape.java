import PlayerImportAndSetup.Position;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * THE SHAPE OF A PLAYER-PROP OUTCOME AROUND ITS PROJECTION.
 *
 * A prop is a line on one stat - receiving yards, receptions, rushing yards,
 * passing yards, touchdowns - and the bet is over or under. Where the book puts
 * the line decides everything, and yardage is skewed to the right: a receiver
 * projected for 60 yards has more 20-yard games than 100-yard ones, so his
 * median sits below his mean. A line set at the projected MEAN is therefore
 * an under that wins more often than it loses, before any model.
 *
 * This measures that shape on Sleeper's (Rotowire's) weekly projected stat
 * lines against the stat rows, every played player-week 2018-2025: how often
 * the outcome fell below the projection, stat by stat and position by
 * position and projection size, and the ratio of the median outcome to the
 * projection. Break-even on a -110 under is 52.38%.
 *
 * What it does not measure is where books actually put their lines - no prop
 * history is on disk. It measures the one thing a line has to beat: the
 * distribution around the number a book could have copied.
 *
 *     ./gradlew run -Pmain=PropShape
 */
public class PropShape {

    record Stat(String name, Position position, String[] projectedKeys, String[] actualKeys, double minProjection) {}

    static final List<Stat> STATS = List.of(
        new Stat("passing yards", Position.QB, new String[]{"pass_yd"}, new String[]{"pass_yd"}, 150),
        new Stat("passing TDs", Position.QB, new String[]{"pass_td"}, new String[]{"pass_td"}, 0.8),
        new Stat("QB rushing yards", Position.QB, new String[]{"rush_yd"}, new String[]{"rush_yd"}, 10),
        new Stat("rushing yards", Position.RB, new String[]{"rush_yd"}, new String[]{"rush_yd"}, 25),
        new Stat("RB receiving yards", Position.RB, new String[]{"rec_yd"}, new String[]{"rec_yd"}, 10),
        new Stat("RB rush + rec yards", Position.RB, new String[]{"rush_yd", "rec_yd"}, new String[]{"rush_yd", "rec_yd"}, 40),
        new Stat("WR receiving yards", Position.WR, new String[]{"rec_yd"}, new String[]{"rec_yd"}, 25),
        new Stat("WR receptions", Position.WR, new String[]{"rec"}, new String[]{"rec"}, 2.5),
        new Stat("TE receiving yards", Position.TE, new String[]{"rec_yd"}, new String[]{"rec_yd"}, 20),
        new Stat("TE receptions", Position.TE, new String[]{"rec"}, new String[]{"rec"}, 2)
    );

    static double sum(JsonObject stats, String[] keys){
        double s = 0;
        for(String k : keys){
            s += FaabDemand.stat(stats, k);
        }
        return s;
    }

    /** One played player-week: {projected, actual}. */
    static List<double[]> pairs(Map<String, ScreenData.Season> seasons, Map<String, Map<Integer, Map<String, JsonObject>>> actualStats, Stat stat){
        List<double[]> out = new ArrayList<>();
        for(Map.Entry<String, ScreenData.Season> e : seasons.entrySet()){
            ScreenData.Season s = e.getValue();
            for(Map.Entry<Integer, Map<String, JsonObject>> week : s.projected.entrySet()){
                Map<String, ScreenData.Line> lines = s.lines.getOrDefault(week.getKey(), Map.of());
                Map<String, JsonObject> actual = actualStats.get(e.getKey()).getOrDefault(week.getKey(), Map.of());
                for(Map.Entry<String, JsonObject> p : week.getValue().entrySet()){
                    ScreenData.Line line = lines.get(p.getKey());
                    if(line == null || !line.played() || line.position() != stat.position()){
                        continue;
                    }
                    double projected = sum(p.getValue(), stat.projectedKeys());
                    if(projected < stat.minProjection()){
                        continue;
                    }
                    JsonObject a = actual.get(p.getKey());
                    out.add(new double[]{projected, a == null ? 0 : sum(a, stat.actualKeys())});
                }
            }
        }
        return out;
    }

    /** {share below the projection (ties half), share above, median of actual / projected, n, mean actual / mean projected}. */
    static double[] shape(List<double[]> pairs){
        double below = 0;
        double above = 0;
        List<Double> ratio = new ArrayList<>();
        double sumP = 0;
        double sumA = 0;
        for(double[] p : pairs){
            sumP += p[0];
            sumA += p[1];
            if(p[1] < p[0]){
                below++;
            }
            else if(p[1] > p[0]){
                above++;
            }
            else{
                below += 0.5;
                above += 0.5;
            }
            ratio.add(p[1] / p[0]);
        }
        ratio.sort(Double::compare);
        double n = pairs.size();
        return new double[]{below / n, above / n, ratio.isEmpty() ? 0 : ratio.get(ratio.size() / 2), n, sumP > 0 ? sumA / sumP : 0};
    }

    public static void main(String[] args) throws IOException {
        LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        Map<String, ScreenData.Season> seasons = new TreeMap<>();
        Map<String, Map<Integer, Map<String, JsonObject>>> actualStats = new TreeMap<>();
        for(int y = 2018; y <= 2025; y++){
            String season = String.valueOf(y);
            ScreenData.Season s = ScreenData.load(season, true, true, scoring);
            seasons.put(season, s);
            Map<Integer, Map<String, JsonObject>> byWeek = new TreeMap<>();
            for(int w = 1; w <= 17; w++){
                byWeek.put(w, statsById(LeagueWeek.teamStatsBody(season, w)));
            }
            actualStats.put(season, byWeek);
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format("PROP SHAPE  %s  (Sleeper/Rotowire weekly projected stat lines against the stat rows, played player-weeks 2018-2025)%n",
                LocalDate.now()));
        out.append("below = share of games the outcome fell below the projection (a line set at the projection: the under's hit rate);\n");
        out.append("median ratio = the median outcome over the projection; mean ratio = mean outcome over mean projection (1.00 = the\n");
        out.append("projection is an unbiased mean, and 'below' above 50% is then skew alone). Break-even on a -110 under: 52.38%.\n\n");
        out.append(String.format("%-22s %7s %8s %8s %13s %11s   %s%n", "stat", "games", "below", "above", "median ratio", "mean ratio",
                "below, by projection tercile"));
        for(Stat stat : STATS){
            List<double[]> pairs = pairs(seasons, actualStats, stat);
            double[] sh = shape(pairs);
            List<double[]> sorted = new ArrayList<>(pairs);
            sorted.sort((a, b) -> Double.compare(a[0], b[0]));
            StringBuilder terciles = new StringBuilder();
            for(int t = 0; t < 3; t++){
                List<double[]> part = sorted.subList(t * sorted.size() / 3, (t + 1) * sorted.size() / 3);
                double[] ps = shape(part);
                terciles.append(String.format("%5.1f%% (%.0f-%.0f)  ", 100 * ps[0], part.get(0)[0], part.get(part.size() - 1)[0]));
            }
            out.append(String.format("%-22s %7.0f %7.1f%% %7.1f%% %13.2f %11.2f   %s%n", stat.name(), sh[3], 100 * sh[0], 100 * sh[1], sh[2], sh[4],
                    terciles));
        }
        out.append("\nBy season (receiving yards, WR), to see whether the shape is stable:\n");
        Stat wr = STATS.get(6);
        for(String season : seasons.keySet()){
            List<double[]> pairs = pairs(Map.of(season, seasons.get(season)), actualStats, wr);
            double[] sh = shape(pairs);
            out.append(String.format("   %s  %5.0f games  below %5.1f%%  median ratio %.2f%n", season, sh[3], 100 * sh[0], sh[2]));
        }
        out.append("\nThe reading: where 'below' is well above 52.4%, an under at -110 against a line placed AT the projection wins money;\n");
        out.append("whether any book places its line there is the question the prop-line archive has to answer (BETTING.md).\n");
        System.out.print(out);
        Path report = Path.of("data", "prop-shape-" + LocalDate.now() + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }

    /** id -> the stats object of each row of a /stats array body. */
    static Map<String, JsonObject> statsById(String body){
        Map<String, JsonObject> out = new java.util.HashMap<>();
        for(com.google.gson.JsonElement e : com.google.gson.JsonParser.parseString(body).getAsJsonArray()){
            JsonObject row = e.getAsJsonObject();
            String id = ScreenData.text(row, "player_id");
            JsonObject stats = ScreenData.object(row, "stats");
            if(id != null && stats != null){
                out.put(id, stats);
            }
        }
        return out;
    }
}
