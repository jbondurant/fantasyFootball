import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * THE FACTS OF A TRADE, AS THE MAN ACROSS THE TABLE SEES THEM AND AS THE MODEL
 * DOES.
 *
 * Justin, 2026-09-27, on the short list's top offer (Nabers + Stevenson for
 * Chase): Nabers' quarterback Jaxson Dart is out for the season, Nabers and
 * Stevenson had mediocre week 3s, Chase just had a great one - "no way that
 * trade goes through; something is wrong if you thought it had the slightest
 * chance." This prints, for named players, what the other manager sees on his
 * screen (points each week, points per game, draft position, injury) beside
 * what each projection the trade tools price on says (Sleeper's season feed,
 * the rest-of-season model, next week's weekly projection), so a trade the
 * model likes can be checked against the one he will judge it by.
 *
 * Justin's follow-up the same day: "did sleeper weekly projections update,
 * and can we sum those instead?" The second table answers the first half. It
 * sums each man's weekly projections over the same future weeks as read on
 * every day the repo saved all of those week files. Reads from before and
 * after an injury show whether Sleeper moved on it.
 *
 *     ./gradlew run -Pmain=TradeCheck -Pplayers="Malik Nabers,Ja'Marr Chase"
 */
public class TradeCheck {

    /**
     * Sleeper's weekly projections for weeks from..to, summed per man, once for
     * every day on which the repo saved a read of every one of those weeks
     * (the dated sleeperLiveProjection files LeagueWeek writes). date -> id ->
     * {league points summed, weeks in which he carries a projection}.
     */
    static Map<String, Map<String, double[]>> weeklySumsByDate(String season, int from, int to) throws IOException {
        String stem = "sleeperLiveProjection" + season + "w";
        TreeSet<String> dates = new TreeSet<>();
        File[] files = new File(".").listFiles();
        for(File f : files == null ? new File[0] : files){
            String name = f.getName();
            String prefix = stem + from;
            if(name.startsWith(prefix) && name.endsWith(".txt") && name.length() == prefix.length() + 14){
                dates.add(name.substring(prefix.length(), prefix.length() + 10));
            }
        }
        Map<String, Map<String, double[]>> out = new TreeMap<>();
        for(String date : dates){
            Map<String, double[]> sums = new HashMap<>();
            boolean complete = true;
            for(int w = from; w <= to && complete; w++){
                File f = new File(stem + w + date + ".txt");
                if(!f.isFile()){
                    complete = false;
                    break;
                }
                LeagueWeek.projectedFrom(Files.readString(f.toPath(), StandardCharsets.UTF_8)).forEach((id, pts) -> {
                    double[] s = sums.computeIfAbsent(id, k -> new double[2]);
                    s[0] += pts;
                    s[1]++;
                });
            }
            if(complete){
                out.put(date, sums);
            }
        }
        return out;
    }

    public static void main(String[] args) throws IOException {
        String season = LeagueWeek.season();
        int week = LeagueWeek.week();
        String[] names = System.getProperty("players", "").split(",");
        JsonObject db;
        try(FileReader reader = new FileReader("sleeperDataPlayerAPI.json")){
            db = JsonParser.parseReader(reader).getAsJsonObject();
        }
        Map<String, Double> feed = SleeperProjections.parseTodaysWebPage();
        Map<String, Double> ros = ProjectionSources.resolve("ros");
        Map<String, Double> next = LeagueWeek.projected(season, week + 1);
        Map<String, Double> preseasonAdp = SleeperProjections.adpSnapshot(RosModel.preseasonLines(), RosModel.preseasonAdpDay());
        List<Map<String, Double>> weeks = new ArrayList<>();
        for(int w = 1; w <= week; w++){
            weeks.add(LeagueWeek.actualSoFar(season, w));
        }
        Map<String, String> ownerOf = LeagueOwners.today(AAAConfiguration.getInstance());
        for(int w = week + 1; w <= EraActuals.weeks(season); w++){
            LeagueWeek.projected(season, w);   // today's read of every future week, if the day has none yet
        }
        Map<String, Map<String, double[]>> byDate = weeklySumsByDate(season, week + 1, EraActuals.weeks(season));
        List<String> ids = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        System.out.printf("TRADE CHECK  season %s, Sleeper on week %d (week %d is %s)%n%n", season, week, week,
                LeagueWeek.finished(season, week) ? "finished" : "in progress - its points are partial");
        System.out.printf("%-22s %-4s %-4s %-12s %-24s %7s %6s %6s %8s %7s %9s  %s%n", "player", "pos", "team", "owner",
                "points by week", "ppg", "ADP", "pre", "season", "ros", "next wk", "injury");
        for(String raw : names){
            String name = raw.trim();
            if(name.isEmpty()){
                continue;
            }
            for(Map.Entry<String, JsonElement> e : db.entrySet()){
                JsonObject p = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : null;
                if(p == null || !KalshiFair.normal(name).equals(KalshiFair.normal(ScreenData.text(p, "full_name")))
                        || ScreenData.text(p, "team") == null){
                    continue;
                }
                String id = e.getKey();
                ids.add(id);
                shown.add(name);
                StringBuilder byWeek = new StringBuilder();
                double total = 0;
                int games = 0;
                for(Map<String, Double> w : weeks){
                    Double pts = w.get(id);
                    byWeek.append(pts == null ? "  - " : String.format("%4.1f ", pts));
                    if(pts != null){
                        total += pts;
                        games++;
                    }
                }
                double adp = SleeperProjections.adpOf(id);
                Double pre = preseasonAdp.get(id);
                System.out.printf("%-22s %-4s %-4s %-12s %-24s %7.1f %6s %6s %8.1f %7.1f %9.1f  %s%n", name, ScreenData.text(p, "position"),
                        ScreenData.text(p, "team"), ownerOf.getOrDefault(id, "free"), byWeek, games == 0 ? 0 : total / games,
                        adp > 1000 ? "-" : String.format("%.1f", adp), pre == null ? "-" : String.format("%.1f", pre),
                        feed.getOrDefault(id, 0.0), ros.getOrDefault(id, 0.0), next.getOrDefault(id, 0.0),
                        SleeperProjections.injuryStatusOf(id) == null ? "" : SleeperProjections.injuryStatusOf(id));
            }
        }
        System.out.println("\nseason = Sleeper's season feed (what TradeMarket prices on by default); ros = the rest-of-season model;");
        System.out.println("next wk = Sleeper's projection for next week. Points are this league's scoring.");

        System.out.printf("%nSLEEPER'S WEEKLY PROJECTIONS, weeks %d-%d summed, as read on each day the repo saved them all%n",
                week + 1, EraActuals.weeks(season));
        System.out.println("(points / weeks in which he carries a projection; a bye is a week without one)");
        System.out.printf("%-22s", "player");
        for(String date : byDate.keySet()){
            System.out.printf(" %13s", date.substring(5));
        }
        System.out.println();
        for(int i = 0; i < ids.size(); i++){
            System.out.printf("%-22s", shown.get(i));
            for(Map<String, double[]> sums : byDate.values()){
                double[] s = sums.get(ids.get(i));
                System.out.printf(" %13s", s == null ? "-" : String.format("%.1f/%d", s[0], (int) s[1]));
            }
            System.out.println();
        }
    }
}
