import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

import com.google.gson.JsonArray;
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
 * Justin, 2026-10-29, on Nabers + Stevenson for Justin Jefferson: "fantasy pros
 * has nabers ahead of justin jefferson". The FP ros column is FantasyPros'
 * rest-of-season expert consensus rank at the position (half-PPR pages), the
 * view a man checking a trade outside Sleeper sees beside the two the trade
 * tools price on.
 *
 * Justin, 2026-11-07, on Stevenson for Ashton Jeanty: "the narratives
 * surrounding jeanty reduced workload concern me". The workload table puts
 * a number on a narrative before the projections have priced it: each finished
 * week's share of his club's offensive snaps, his carries and red-zone carries
 * against his club's running backs', and his targets against his club's, from
 * Sleeper's stat rows, with his club's last three games and its season pooled.
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

    /** One man's week against his club's: offensive snaps, carries and red-zone carries against its running backs', targets against all of its. */
    record Work(double snaps, double teamSnaps, double carries, double rbCarries, double targets, double teamTargets,
                double rzCarries, double rbRzCarries) {}

    /**
     * His work in one week's /stats rows. His club is the one his own row names,
     * so a man traded mid-season is measured against the team he played for; with
     * no row it is today's club and his counts are zero - a missed game is the
     * news. Null where that club has no rows: a bye. A running back is the
     * position his row names that week.
     */
    static Work work(String id, String team, JsonArray rows){
        JsonObject mine = null;
        for(JsonElement e : rows){
            if(e.isJsonObject() && id.equals(ScreenData.text(e.getAsJsonObject(), "player_id"))){
                mine = e.getAsJsonObject();
            }
        }
        String club = mine != null && ScreenData.text(mine, "team") != null ? ScreenData.text(mine, "team") : team;
        double teamSnaps = 0, rbCarries = 0, teamTargets = 0, rbRz = 0;
        boolean played = false;
        for(JsonElement e : rows){
            JsonObject row = e.isJsonObject() ? e.getAsJsonObject() : null;
            if(row == null || club == null || !club.equals(ScreenData.text(row, "team"))){
                continue;
            }
            played = true;
            JsonObject stats = ScreenData.object(row, "stats");
            teamSnaps = Math.max(teamSnaps, FaabDemand.stat(stats, "tm_off_snp"));
            teamTargets += FaabDemand.stat(stats, "rec_tgt");
            if("RB".equals(ScreenData.text(ScreenData.object(row, "player"), "position"))){
                rbCarries += FaabDemand.stat(stats, "rush_att");
                rbRz += FaabDemand.stat(stats, "rush_rz_att");
            }
        }
        if(!played){
            return null;
        }
        JsonObject stats = mine == null ? null : ScreenData.object(mine, "stats");
        return new Work(FaabDemand.stat(stats, "off_snp"), teamSnaps, FaabDemand.stat(stats, "rush_att"), rbCarries,
                FaabDemand.stat(stats, "rec_tgt"), teamTargets, FaabDemand.stat(stats, "rush_rz_att"), rbRz);
    }

    /** Weeks pooled: his counts summed over his club's, per game in the counts. Null with no games. */
    static Work pooled(List<Work> weeks){
        double[] s = new double[8];
        int games = 0;
        for(Work w : weeks){
            if(w == null){
                continue;
            }
            games++;
            double[] v = {w.snaps(), w.teamSnaps(), w.carries(), w.rbCarries(), w.targets(), w.teamTargets(), w.rzCarries(), w.rbRzCarries()};
            for(int i = 0; i < 8; i++){
                s[i] += v[i];
            }
        }
        if(games == 0){
            return null;
        }
        for(int i = 0; i < 8; i++){
            s[i] /= games;
        }
        return new Work(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7]);
    }

    /** "22 81%": his count and his share of his club's; a share alone for snaps. */
    static String cell(double mine, double theirs, boolean count){
        String share = theirs > 0 ? String.format("%.0f%%", 100 * mine / theirs) : "-";
        if(!count){
            return share;
        }
        return (mine == Math.rint(mine) ? String.format("%.0f", mine) : String.format("%.1f", mine)) + " " + share;
    }

    public static void main(String[] args) throws IOException {
        String season = LeagueWeek.season();
        int week = LeagueWeek.week();
        String[] names = System.getProperty("players", "").split(",");
        JsonObject db;
        db = PlayerRawData.database();   // through the in-season daily expiry
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
        Map<String, String> fpRos = new HashMap<>();
        for(Rank r : InSeasonProjectionsFP.getRosRanking()){
            fpRos.putIfAbsent(r.player.sleeperIDString, r.player.position.name() + r.rankNum);
        }
        List<String> ids = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        List<String> teams = new ArrayList<>();
        System.out.printf("TRADE CHECK  season %s, Sleeper on week %d (week %d is %s)%n%n", season, week, week,
                LeagueWeek.finished(season, week) ? "finished" : "in progress - its points are partial");
        System.out.printf("%-22s %-4s %-4s %-12s %-24s %7s %6s %6s %8s %7s %9s %7s  %s%n", "player", "pos", "team", "owner",
                "points by week", "ppg", "ADP", "pre", "season", "ros", "next wk", "FP ros", "injury");
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
                teams.add(ScreenData.text(p, "team"));
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
                System.out.printf("%-22s %-4s %-4s %-12s %-24s %7.1f %6s %6s %8.1f %7.1f %9.1f %7s  %s%n", name, ScreenData.text(p, "position"),
                        ScreenData.text(p, "team"), ownerOf.getOrDefault(id, "free"), byWeek, games == 0 ? 0 : total / games,
                        adp > 1000 ? "-" : String.format("%.1f", adp), pre == null ? "-" : String.format("%.1f", pre),
                        feed.getOrDefault(id, 0.0), ros.getOrDefault(id, 0.0), next.getOrDefault(id, 0.0), fpRos.getOrDefault(id, "-"),
                        SleeperProjections.injuryStatusOf(id) == null ? "" : SleeperProjections.injuryStatusOf(id));
            }
        }
        System.out.println("\nseason = Sleeper's season feed (what TradeMarket prices on by default); ros = the rest-of-season model;");
        System.out.println("next wk = Sleeper's projection for next week; FP ros = FantasyPros' rest-of-season consensus rank at the");
        System.out.println("position (half PPR), the median of the last three days' pulls. Points are this league's scoring.");

        int done = LeagueWeek.finished(season, week) ? week : week - 1;
        List<JsonArray> rows = new ArrayList<>();
        for(int w = 1; w <= done; w++){
            rows.add(JsonParser.parseString(LeagueWeek.teamStatsBody(season, w)).getAsJsonArray());
        }
        // the week in progress, for clubs whose game kicked off four hours ago or more (NewsCheck's rule): the day
        // after a role change is when the table is wanted (2026-11-09, the Monday after Jeanty's week 9)
        java.util.Set<String> playing = java.util.Set.of();
        if(done < week){
            List<NflverseGames.Game> games = NflverseGames.games();
            java.util.Set<String> over = NflverseGames.kickedOff(games, season, week,
                    java.time.LocalDateTime.now(java.time.ZoneId.of("America/New_York")).minusHours(4));
            playing = NflverseGames.playing(games, season, week);
            JsonArray live = new JsonArray();
            for(JsonElement e : JsonParser.parseString(LeagueWeek.teamStatsBody(season, week)).getAsJsonArray()){
                if(e.isJsonObject() && over.contains(ScreenData.text(e.getAsJsonObject(), "team"))){
                    live.add(e);
                }
            }
            rows.add(live);
        }
        System.out.printf("%nWORKLOAD BY WEEK, weeks 1-%d from Sleeper's stat rows%s%n", rows.size(),
                done < week ? " (week " + week + " is in progress: only games that kicked off four hours ago or more; tbd = not yet)" : "");
        System.out.println("snaps = his share of his club's offensive snaps; carries and red-zone carries = his, and his share of his");
        System.out.println("club's running backs'; targets = his, and his share of his club's. last 3 and season are per game, pooled");
        System.out.println("over his club's games: a game he missed counts as zero, a bye is left out.");
        String[] measures = {"snaps", "carries", "targets", "red-zone carries"};
        for(int i = 0; i < ids.size(); i++){
            List<Work> work = new ArrayList<>();
            for(JsonArray weekRows : rows){
                work.add(work(ids.get(i), teams.get(i), weekRows));
            }
            List<Work> played = work.stream().filter(Objects::nonNull).toList();
            Work last3 = pooled(played.subList(Math.max(0, played.size() - 3), played.size()));
            Work all = pooled(played);
            boolean pending = done < week && work.get(work.size() - 1) == null && playing.contains(teams.get(i));
            System.out.printf("%n%-22s", shown.get(i) + " (" + teams.get(i) + ")");
            for(int w = 1; w <= rows.size(); w++){
                System.out.printf(" %8s", "w" + w);
            }
            System.out.printf(" %11s %11s%n", "last 3", "season");
            for(int m = 0; m < measures.length; m++){
                System.out.printf("  %-20s", measures[m]);
                List<Work> cols = new ArrayList<>(work);
                cols.add(last3);
                cols.add(all);
                for(int c = 0; c < cols.size(); c++){
                    Work w = cols.get(c);
                    String text = w == null ? (c == work.size() - 1 && pending ? "tbd" : c < work.size() ? "bye" : "-") : switch(m){
                        case 0 -> cell(w.snaps(), w.teamSnaps(), false);
                        case 1 -> cell(w.carries(), w.rbCarries(), true);
                        case 2 -> cell(w.targets(), w.teamTargets(), true);
                        default -> cell(w.rzCarries(), w.rbRzCarries(), true);
                    };
                    System.out.printf(c < work.size() ? " %8s" : " %11s", text);
                }
                System.out.println();
            }
        }

        // today's read of each week left: a role change shows first in the near weeks, which Sleeper revisits
        // most, and the far weeks can still carry the old role (Jeanty, 2026-11-07: 26.8 rushing yards in week 9)
        System.out.printf("%nSLEEPER'S PROJECTION BY WEEK, today's read: league points, then projected rushing yards and targets%n");
        System.out.printf("%-22s", "player");
        for(int w = week; w <= EraActuals.weeks(season); w++){
            System.out.printf(" %13s", "w" + w);
        }
        System.out.println();
        List<Map<String, Double>> pointsByWeek = new ArrayList<>();
        List<Map<String, JsonObject>> linesByWeek = new ArrayList<>();
        for(int w = week; w <= EraActuals.weeks(season); w++){
            String body = LeagueWeek.projectionsBody(season, w);
            pointsByWeek.add(LeagueWeek.projectedFrom(body));
            linesByWeek.add(ScreenData.projectedLines(body));
        }
        for(int i = 0; i < ids.size(); i++){
            System.out.printf("%-22s", shown.get(i));
            for(int k = 0; k < pointsByWeek.size(); k++){
                Double pts = pointsByWeek.get(k).get(ids.get(i));
                JsonObject line = linesByWeek.get(k).get(ids.get(i));
                System.out.printf(" %13s", pts == null ? "bye/none" : String.format("%.1f %.0f/%.1f", pts,
                        FaabDemand.stat(line, "rush_yd"), FaabDemand.stat(line, "rec_tgt")));
            }
            System.out.println();
        }

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
