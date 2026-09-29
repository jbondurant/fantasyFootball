import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * THE NEWS, BEFORE THE PROJECTIONS HAVE HEARD IT.
 *
 * Every pricing in this repo reads a projection, and a projection moves a day
 * or more after the news. On 2026-09-28 Sleeper's trending adds had Ollie
 * Gordon II - free here, Miami's lead back after De'Von Achane's ACL - a day
 * before its projections moved: that morning's weekly projections still summed
 * Gordon to 28.3 over weeks 4-18 and Achane to 227.1. The managers adding him
 * were reading the news; the wire tools could not.
 *
 * So this prints the facts a projection is built from, for Justin's roster,
 * for named men, and for Sleeper's public trending adds (48 hours, marked
 * free or held here): team, injury tag and body part, practice report, depth
 * chart, the day Sleeper last had news on him, bye week and next opponent,
 * his snap share and target share over his team's last three games, whether
 * his tag lets him sit in an IR slot under this league's rules, and Sleeper's
 * weekly projections summed over the games he has left - the number that
 * moves last, beside the facts that move first.
 *
 *     ./gradlew run -Pmain=NewsCheck [-Pplayers="Ollie Gordon II,De'Von Achane"] [-Pme=<name>]
 *
 * Nothing here is a recommendation; it is what to read before one. Report to
 * data/news-&lt;date&gt;.txt.
 */
public class NewsCheck {

    static final String TRENDING = "https://api.sleeper.app/v1/players/nfl/trending/add?lookback_hours=48&limit=50";

    /** The positions this league starts; a trending kicker or linebacker is not news for this roster. */
    static final Set<String> STARTED = Set.of("QB", "RB", "WR", "TE", "DEF");

    /**
     * THE LEAGUE'S IR RULE: which injury tags may sit in a reserve slot.
     *
     * An IR or PUP tag always may; the rest are the league's reserve_allow_*
     * switches. PUP counts with IR because Sleeper treats it so - on
     * 2026-09-29 a PUP man (Zach Charbonnet) sat in a reserve slot here, in a
     * league whose switches allow Out, Doubtful and COV and not NA. With no
     * slots, nobody is eligible whatever his tag.
     */
    record ReserveRule(int slots, Set<String> statuses) {

        static ReserveRule of(JsonObject settings){
            int slots = intOf(settings, "reserve_slots");
            Set<String> ok = new TreeSet<>(Set.of("IR", "PUP"));
            if(intOf(settings, "reserve_allow_out") > 0){ ok.add("Out"); }
            if(intOf(settings, "reserve_allow_doubtful") > 0){ ok.add("Doubtful"); }
            if(intOf(settings, "reserve_allow_sus") > 0){ ok.add("Sus"); }
            if(intOf(settings, "reserve_allow_na") > 0){ ok.add("NA"); }
            if(intOf(settings, "reserve_allow_cov") > 0){ ok.add("COV"); }
            if(intOf(settings, "reserve_allow_dnr") > 0){ ok.add("DNR"); }
            return new ReserveRule(slots, slots > 0 ? ok : Set.of());
        }

        boolean eligible(String status){
            return status != null && statuses.contains(status);
        }
    }

    static int intOf(JsonObject o, String key){
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsInt();
    }

    /** Sleeper's trending adds, id -> adds, in the feed's order (most added first). */
    static LinkedHashMap<String, Integer> trending(String body){
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for(JsonElement e : JsonParser.parseString(body).getAsJsonArray()){
            JsonObject row = e.getAsJsonObject();
            String id = ScreenData.text(row, "player_id");
            if(id != null){
                out.put(id, intOf(row, "count"));
            }
        }
        return out;
    }

    /** One man's week from the /stats rows: his club that week, his offensive snaps, his team's, his targets. */
    record WeekLine(String team, double snaps, double teamSnaps, double targets) {}

    static Map<String, WeekLine> weekLines(String body){
        Map<String, WeekLine> out = new LinkedHashMap<>();
        for(JsonElement e : JsonParser.parseString(body).getAsJsonArray()){
            if(!e.isJsonObject()){
                continue;
            }
            JsonObject row = e.getAsJsonObject();
            String id = ScreenData.text(row, "player_id");
            String team = ScreenData.text(row, "team");
            if(id == null || team == null){
                continue;
            }
            JsonObject stats = ScreenData.object(row, "stats");
            if(stats == null){
                stats = new JsonObject();
            }
            out.put(id, new WeekLine(team, FaabDemand.stat(stats, "off_snp"), FaabDemand.stat(stats, "tm_off_snp"),
                    FaabDemand.stat(stats, "rec_tgt")));
        }
        return out;
    }

    /** Snap share and target share, pooled over the games counted, and how many games that was. NaN where the team had none. */
    record Usage(double snapShare, double targetShare, int games) {}

    /**
     * His share of his team's snaps and targets over its last {@code k} games,
     * newest first. A week his team has no rows is a bye or a game not yet
     * over and is skipped; a week it played and he has no row counts as zero -
     * the missed game is the news. His club is the one his own row names that
     * week, so a man traded mid-season is measured against the team he played
     * for (TRAPS #148); with no row, today's club.
     */
    static Usage usage(String id, String team, List<Map<String, WeekLine>> newestFirst, int k){
        double snaps = 0, teamSnaps = 0, targets = 0, teamTargets = 0;
        int games = 0;
        for(Map<String, WeekLine> week : newestFirst){
            if(games == k){
                break;
            }
            WeekLine mine = week.get(id);
            String club = mine != null ? mine.team() : team;
            if(club == null){
                continue;
            }
            double weekSnaps = 0, weekTargets = 0;
            boolean played = false;
            for(WeekLine line : week.values()){
                if(club.equals(line.team())){
                    played = true;
                    weekSnaps = Math.max(weekSnaps, line.teamSnaps());
                    weekTargets += line.targets();
                }
            }
            if(!played){
                continue;
            }
            games++;
            teamSnaps += weekSnaps;
            teamTargets += weekTargets;
            if(mine != null){
                snaps += mine.snaps();
                targets += mine.targets();
            }
        }
        return new Usage(teamSnaps > 0 ? snaps / teamSnaps : Double.NaN,
                teamTargets > 0 ? targets / teamTargets : Double.NaN, games);
    }

    /** The club's bye: the first week of 1..lastWeek it has no game. Null for a season the schedule does not carry. */
    static Integer bye(List<NflverseGames.Game> games, String season, String team, int lastWeek){
        Set<Integer> weeks = new HashSet<>();
        for(NflverseGames.Game g : games){
            if(g.season().equals(season) && (team.equals(NflverseGames.sleeperTeam(g.home()))
                    || team.equals(NflverseGames.sleeperTeam(g.away())))){
                weeks.add(g.week());
            }
        }
        if(weeks.isEmpty()){
            return null;
        }
        for(int w = 1; w <= lastWeek; w++){
            if(!weeks.contains(w)){
                return w;
            }
        }
        return null;
    }

    /** The club's next game not yet kicked off, as "w4 @BUF" or "w4 v BUF"; null if none is scheduled. */
    static String next(List<NflverseGames.Game> games, String season, String team, int fromWeek, LocalDateTime nowEastern){
        NflverseGames.Game best = null;
        for(NflverseGames.Game g : games){
            if(!g.season().equals(season) || g.week() < fromWeek || g.gameday() == null || g.gameday().isBlank()){
                continue;
            }
            boolean home = team.equals(NflverseGames.sleeperTeam(g.home()));
            if(!home && !team.equals(NflverseGames.sleeperTeam(g.away()))){
                continue;
            }
            LocalDateTime kickoff = LocalDateTime.parse(g.gameday() + "T"
                    + (g.gametime() == null || g.gametime().isBlank() ? "13:00" : g.gametime()));
            if(nowEastern.isBefore(kickoff) && (best == null || g.week() < best.week())){
                best = g;
            }
        }
        if(best == null){
            return null;
        }
        boolean home = team.equals(NflverseGames.sleeperTeam(best.home()));
        return "w" + best.week() + (home ? " v " + NflverseGames.sleeperTeam(best.away())
                : " @" + NflverseGames.sleeperTeam(best.home()));
    }

    /** The ids a -Pplayers name means: a Sleeper id (a defence's is its club) as given, else every man with a club whose full name matches. */
    static List<String> named(String name, JsonObject db){
        List<String> out = new ArrayList<>();
        if(db.has(name)){
            out.add(name);
            return out;
        }
        String wanted = KalshiFair.normal(name);
        for(Map.Entry<String, JsonElement> e : db.entrySet()){
            JsonObject p = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : null;
            if(p != null && ScreenData.text(p, "team") != null && wanted.equals(KalshiFair.normal(fullName(p)))){
                out.add(e.getKey());
            }
        }
        return out;
    }

    static String fullName(JsonObject p){
        String full = ScreenData.text(p, "full_name");
        if(full != null){
            return full;
        }
        String first = ScreenData.text(p, "first_name");
        String last = ScreenData.text(p, "last_name");
        return ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
    }

    static String pct(double share){
        return Double.isNaN(share) ? "-" : String.format("%.0f%%", 100 * share);
    }

    static String or(String s){
        return s == null || s.isBlank() ? "-" : s;
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String season = LeagueWeek.season();
        int week = LeagueWeek.week();
        String me = System.getProperty("me", configuration.getUserIDToDisplayName()
                .getOrDefault(configuration.getMyID(), configuration.getMyID()));
        JsonObject db = PlayerRawData.database();
        Map<String, String> ownerOf = LeagueOwners.today(configuration);
        Set<String> reserve = LeagueOwners.reserve(configuration);
        ReserveRule rule = ReserveRule.of(configuration.getLeagueJson().getAsJsonObject("settings"));
        Map<String, Double> left = ProjectionSources.resolve("sleeper-remaining");
        LocalDateTime now = LocalDateTime.now(ZoneId.of("America/New_York"));

        List<NflverseGames.Game> games;
        try {
            games = NflverseGames.games();
        }
        catch(IllegalStateException notOnDisk){
            System.out.println(notOnDisk.getMessage() + " - no bye or next opponent");
            games = List.of();
        }

        // the team-by-week rows, newest first: this week's only for clubs whose
        // game kicked off four hours ago or more, so a game in progress is not
        // read as one in which a man played half the snaps
        List<Map<String, WeekLine>> weeks = new ArrayList<>();
        Set<String> over = NflverseGames.kickedOff(games, season, week, now.minusHours(4));
        for(int w = week; w >= Math.max(1, week - 5); w--){
            Map<String, WeekLine> lines = weekLines(LeagueWeek.teamStatsBody(season, w));
            if(w == week && !LeagueWeek.finished(season, w)){
                lines.values().removeIf(line -> !over.contains(line.team()));
            }
            weeks.add(lines);
        }

        LinkedHashMap<String, Integer> trending = trending(
                InOutUtilities.getRecentWebPage(TRENDING, "sleeperTrendingAdd", 60));

        List<String> mine = new ArrayList<>();
        for(Map.Entry<String, String> e : ownerOf.entrySet()){
            if(e.getValue().equals(me)){
                mine.add(e.getKey());
            }
        }
        mine.sort(Comparator.comparing((String id) -> positionOrder(position(db, id)))
                .thenComparing(id -> -left.getOrDefault(id, 0.0)));
        List<String> asked = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for(String raw : System.getProperty("players", "").split(",")){
            String name = raw.trim();
            if(name.isEmpty()){
                continue;
            }
            List<String> ids = named(name, db);
            if(ids.isEmpty()){
                unknown.add(name);
            }
            asked.addAll(ids);
        }
        List<String> hot = new ArrayList<>();
        for(String id : trending.keySet()){
            if(STARTED.contains(position(db, id))){
                hot.add(id);
            }
        }

        StringBuilder out = new StringBuilder();
        out.append(DataStamp.line()).append("\n");
        out.append(String.format("NEWS CHECK  %s  season %s, week %d  (%s)%n", LocalDate.now(), season, week, me));
        out.append(String.format("IR here: %d slot%s; a tag that may sit in one: %s (league settings)%n",
                rule.slots(), rule.slots() == 1 ? "" : "s",
                rule.statuses().isEmpty() ? "none" : String.join(", ", rule.statuses())));
        out.append("SNAP and TGT are his share of his team's offensive snaps and targets over its last three games,\n"
                + "a game he missed counting as zero. LEFT is Sleeper's weekly projections summed over the games he\n"
                + "has left (sleeper-remaining) - the number that moves last; '-' is a man projected for none.\n"
                + "NEWS is the day Sleeper last had news on him.\n");
        String header = String.format(ROW, "", "POS", "TEAM", "HELD BY", "STATUS", "PRACTICE", "DEPTH", "NEWS", "BYE",
                "NEXT", "SNAP", "TGT", "IR", "LEFT", "ADDS");
        Context context = new Context(db, ownerOf, reserve, me, rule, left, games, season, week, now, weeks, trending);
        section(out, "YOUR ROSTER", header, mine, context);
        if(!asked.isEmpty() || !unknown.isEmpty()){
            section(out, "NAMED", header, asked, context);
            for(String name : unknown){
                out.append(String.format("   no player with a club is named %s%n", name));
            }
        }
        section(out, "TRENDING ADDS ACROSS SLEEPER, LAST 48 HOURS (FREE = on this league's wire)", header, hot, context);

        System.out.print(out);
        Path target = Path.of("data", "news-" + LocalDate.now() + ".txt");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + target);
    }

    static final String ROW = "%-24s %-3s %-4s %-16s %-18s %-8s %-5s %-5s %4s %-9s %5s %5s %-3s %6s %5s%n";

    /** Everything a row reads, gathered once. */
    record Context(JsonObject db, Map<String, String> ownerOf, Set<String> reserve, String me, ReserveRule rule,
                   Map<String, Double> left, List<NflverseGames.Game> games, String season, int week,
                   LocalDateTime now, List<Map<String, WeekLine>> weeks, Map<String, Integer> trending) {}

    static void section(StringBuilder out, String title, String header, List<String> ids, Context c){
        out.append("\n").append(title).append("\n").append(header);
        for(String id : ids){
            JsonObject p = c.db().has(id) && c.db().get(id).isJsonObject() ? c.db().getAsJsonObject(id) : new JsonObject();
            String team = ScreenData.text(p, "team");
            String holder = c.ownerOf().get(id);
            String held = holder == null ? "FREE"
                    : (holder.equals(c.me()) ? "you" : holder) + (c.reserve().contains(id) ? " (IR)" : "");
            String status = ScreenData.text(p, "injury_status");
            String part = ScreenData.text(p, "injury_body_part");
            String depthPosition = ScreenData.text(p, "depth_chart_position");
            String depthOrder = ScreenData.text(p, "depth_chart_order");
            JsonElement news = p.get("news_updated");
            String newsDay = news == null || news.isJsonNull() ? "-"
                    : java.time.Instant.ofEpochMilli(news.getAsLong()).atZone(ZoneId.systemDefault())
                            .toLocalDate().toString().substring(5);
            Integer bye = team == null ? null : bye(c.games(), c.season(), team, WeeklyActuals.WEEKS);
            String next = team == null ? null : next(c.games(), c.season(), team, c.week(), c.now());
            // the /stats rows are skill men only, so a defence has no share - not a zero one
            Usage usage = "DEF".equals(ScreenData.text(p, "position")) ? new Usage(Double.NaN, Double.NaN, 0)
                    : usage(id, team, c.weeks(), 3);
            Double points = c.left().get(id);
            Integer adds = c.trending().get(id);
            out.append(String.format(ROW,
                    clip(fullName(p).isEmpty() ? id : fullName(p), 24), or(ScreenData.text(p, "position")),
                    team == null ? "FA" : team, clip(held, 16),
                    clip(status == null ? "-" : status + (part == null ? "" : " " + part), 18),
                    clip(or(ScreenData.text(p, "practice_participation")), 8),
                    depthPosition == null ? "-" : depthPosition + (depthOrder == null ? "" : depthOrder),
                    newsDay, bye == null ? "-" : bye.toString(), or(next),
                    pct(usage.snapShare()), pct(usage.targetShare()),
                    c.rule().eligible(status) ? "yes" : "-",
                    points == null ? "-" : String.format("%.1f", points),
                    adds == null ? "" : adds.toString()));
        }
        if(ids.isEmpty()){
            out.append("   nobody\n");
        }
    }

    static String clip(String s, int width){
        return s.length() <= width ? s : s.substring(0, width - 1) + "~";
    }

    static String position(JsonObject db, String id){
        JsonElement e = db.get(id);
        return e != null && e.isJsonObject() ? ScreenData.text(e.getAsJsonObject(), "position") : null;
    }

    static int positionOrder(String position){
        int i = List.of("QB", "RB", "WR", "TE", "DEF").indexOf(position);
        return i < 0 ? 9 : i;
    }
}
