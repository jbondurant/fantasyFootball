import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Who has the market moved on in the last few days, and does Sleeper's own
 * data already say why.
 *
 * Every day's Sleeper projection response is cached in the project root as
 * {@code sleeperProjections<season><date>.txt}. Each one carries three
 * things per player: the projected stat line (scored here under the league's
 * own settings, exactly as the model scores it), Sleeper's half-PPR ADP, and
 * the player's metadata - injury status, the time of his last news item, and
 * when his team last changed. Read side by side they give two tables: the
 * players whose projection moved, and the players whose ADP moved, each with
 * the day the move happened and whatever the metadata offers as a cause. What
 * the metadata does not explain is for a human to look up.
 *
 * ADP moves are ranked RELATIVELY (log of to/from): four picks at ADP 10 is a
 * different world, four picks at ADP 150 is noise. Negative means rising -
 * the market is reaching for him. Projection moves are ranked by points.
 *
 * Every day-to-day series is inspected for a step: when one day accounts for
 * most of the whole move, that day is named, because an event (injury, trade,
 * suspension) lands on one day and hype drifts.
 *
 * Report-only: nothing here feeds the model. The report is also written to
 * {@code data/market-movers-<today>.txt}.
 *
 *     ./gradlew run -Pmain=MarketMovers            # last 7 days of caches
 *     ./gradlew run -Pmain=MarketMovers -Pdays=3 -Ptop=30 -PminMove=2
 */
public class MarketMovers {

    /** One player on one day. */
    public record Row(String id, String name, String position, String team,
                      double points, double adp, String injuryStatus, String injuryPart,
                      long newsUpdated) {}

    /** A player's move between the first and last snapshot in the window. */
    public record Move(Row from, Row to, List<Double> series, String stepDate, double stepShare) {
        double delta(){ return to.points() - from.points(); }
        double adpDelta(){ return to.adp() - from.adp(); }
        /** Relative ADP move; negative = rising. */
        double adpLogRatio(){ return Math.log(to.adp() / from.adp()); }
    }

    /** Sleeper reports players nobody drafts at 999; anything past this is "undrafted". */
    static final double UNDRAFTED = 500;
    /** The drafted universe: a player counts if he is inside this at either end. */
    static final double DRAFTABLE = 200;

    /* ---------------- pure pieces (tested) ---------------- */

    /**
     * The index of the largest one-day change and its share of the total move.
     * Share near 1 = a step on that day; share well below = a drift. Series
     * must be in date order; a flat series has no step (index -1).
     */
    static int[] stepIndex(List<Double> series){
        double total = series.get(series.size() - 1) - series.get(0);
        int best = -1;
        double bestJump = 0;
        for(int i = 1; i < series.size(); i++){
            double jump = series.get(i) - series.get(i - 1);
            if(Math.abs(jump) > Math.abs(bestJump)){
                bestJump = jump;
                best = i;
            }
        }
        int sharePercent = (best < 0 || total == 0) ? 0 : (int) Math.round(100 * bestJump / total);
        return new int[]{best, sharePercent};
    }

    /** Ranks by relative ADP move, biggest first; absolute changes under {@code minPicks} are dropped. */
    static List<Move> adpMovers(List<Move> moves, double minPicks){
        List<Move> kept = new ArrayList<>();
        for(Move m : moves){
            if(m.from().adp() < UNDRAFTED && m.to().adp() < UNDRAFTED
                    && Math.abs(m.adpDelta()) >= minPicks){
                kept.add(m);
            }
        }
        kept.sort(Comparator.comparingDouble((Move m) -> Math.abs(m.adpLogRatio())).reversed());
        return kept;
    }

    /** Ranks by projection change in league points, biggest first; changes under {@code minPoints} are dropped. */
    static List<Move> pointMovers(List<Move> moves, double minPoints){
        List<Move> kept = new ArrayList<>();
        for(Move m : moves){
            if(Math.abs(m.delta()) >= minPoints){
                kept.add(m);
            }
        }
        kept.sort(Comparator.comparingDouble((Move m) -> Math.abs(m.delta())).reversed());
        return kept;
    }

    /**
     * What Sleeper's own metadata says happened to this man inside the window:
     * a change of injury status, a change of team, or a news item dated in the
     * window. Empty when the data offers nothing - which is the cue to go read.
     */
    static String flags(Row from, Row to, LocalDate windowStart){
        List<String> out = new ArrayList<>();
        String before = from.injuryStatus() == null ? "healthy" : from.injuryStatus();
        String after = to.injuryStatus() == null ? "healthy" : to.injuryStatus();
        if(!before.equals(after)){
            out.add("injury " + before + " -> " + after
                    + (to.injuryPart() == null ? "" : " (" + to.injuryPart() + ")"));
        }
        else if(to.injuryStatus() != null){
            out.add(after.toLowerCase() + (to.injuryPart() == null ? "" : " " + to.injuryPart()));
        }
        if(from.team() != null && to.team() != null && !from.team().equals(to.team())){
            out.add("team " + from.team() + " -> " + to.team());
        }
        if(to.newsUpdated() > 0){
            LocalDate news = Instant.ofEpochMilli(to.newsUpdated()).atZone(ZoneId.systemDefault()).toLocalDate();
            if(!news.isBefore(windowStart)){
                out.add("news " + news.toString().substring(5));
            }
        }
        return String.join("; ", out);
    }

    /* ---------------- reading the caches ---------------- */

    /** Every cached day for the season, oldest first: date -> file. */
    static TreeMap<String, Path> cachedDays(String season) throws IOException {
        TreeMap<String, Path> days = new TreeMap<>();
        String prefix = "sleeperProjections" + season;
        try(var list = Files.list(Path.of("."))){
            for(Path p : list.toList()){
                String name = p.getFileName().toString();
                if(name.startsWith(prefix) && name.endsWith(".txt")
                        && name.length() == prefix.length() + 14){
                    days.put(name.substring(prefix.length(), prefix.length() + 10), p);
                }
            }
        }
        return days;
    }

    static Map<String, Row> read(Path file, LeagueScoringSettings scoring) throws IOException {
        Map<String, Row> rows = new HashMap<>();
        JsonArray array = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
        for(JsonElement element : array){
            JsonObject record = element.getAsJsonObject();
            JsonObject stats = record.getAsJsonObject("stats");
            JsonObject player = record.has("player") && record.get("player").isJsonObject()
                    ? record.getAsJsonObject("player") : null;
            if(stats == null || player == null){
                continue;
            }
            String id = record.get("player_id").getAsString();
            double adp = stats.has("adp_half_ppr") && !stats.get("adp_half_ppr").isJsonNull()
                    ? stats.get("adp_half_ppr").getAsDouble() : 999;
            double points = scoring == null
                    ? SleeperProjections.optionalStat(stats, "pts_half_ppr")
                            + 2 * SleeperProjections.optionalStat(stats, "pass_td")
                    : SleeperProjections.scoreStatLine(stats, scoring);
            rows.put(id, new Row(id,
                    text(player, "first_name") + " " + text(player, "last_name"),
                    text(player, "position"), text(player, "team"),
                    points, adp,
                    text(player, "injury_status"), text(player, "injury_body_part"),
                    player.has("news_updated") && !player.get("news_updated").isJsonNull()
                            ? player.get("news_updated").getAsLong() : 0));
        }
        return rows;
    }

    private static String text(JsonObject o, String key){
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    /* ---------------- the report ---------------- */

    public static void main(String[] args) throws IOException {
        int days = Integer.getInteger("days", 7);
        int top = Integer.getInteger("top", 25);
        double minMove = Double.parseDouble(System.getProperty("minMove", "3"));
        String season = AAAConfiguration.getInstance().getSeason();

        LeagueScoringSettings scoring = null;
        String scoringNote;
        try {
            scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
            scoringNote = "league scoring (as the model scores)";
        }
        catch(Exception e){
            scoringNote = "FALLBACK: Sleeper pts_half_ppr + 2 per passing TD (league settings unavailable: "
                    + e.getClass().getSimpleName() + ")";
        }

        TreeMap<String, Path> all = cachedDays(season);
        if(all.size() < 2){
            System.out.println("need at least two cached days of sleeperProjections" + season
                    + "<date>.txt in the project root; have " + all.size());
            return;
        }
        String last = all.lastKey();
        LocalDate windowStart = LocalDate.parse(last).minusDays(days);
        TreeMap<String, Path> window = new TreeMap<>(all.tailMap(windowStart.toString(), true));
        List<String> dates = new ArrayList<>(window.keySet());
        Map<String, Map<String, Row>> byDay = new LinkedHashMap<>();
        for(String d : dates){
            byDay.put(d, read(window.get(d), scoring));
        }
        Map<String, Row> first = byDay.get(dates.get(0));
        Map<String, Row> latest = byDay.get(dates.get(dates.size() - 1));

        List<Move> pointMoves = new ArrayList<>();
        List<Move> adpMoves = new ArrayList<>();
        List<Row> entered = new ArrayList<>();
        List<Row> left = new ArrayList<>();
        for(Row to : latest.values()){
            Row from = first.get(to.id());
            if(from == null){
                continue;
            }
            boolean draftable = from.adp() <= DRAFTABLE || to.adp() <= DRAFTABLE;
            if(!draftable){
                continue;
            }
            if(from.adp() >= UNDRAFTED && to.adp() < UNDRAFTED){ entered.add(to); }
            if(from.adp() < UNDRAFTED && to.adp() >= UNDRAFTED){ left.add(from); }
            List<Double> pts = new ArrayList<>();
            List<Double> adp = new ArrayList<>();
            for(String d : dates){
                Row r = byDay.get(d).get(to.id());
                pts.add(r == null ? null : r.points());
                adp.add(r == null ? null : r.adp());
            }
            if(pts.contains(null)){
                continue;
            }
            int[] ps = stepIndex(pts);
            pointMoves.add(new Move(from, to, pts, ps[0] < 0 ? null : dates.get(ps[0]), ps[1]));
            int[] as = stepIndex(adp);
            adpMoves.add(new Move(from, to, adp, as[0] < 0 ? null : dates.get(as[0]), as[1]));
        }

        StringBuilder out = new StringBuilder();
        out.append(String.format("MARKET MOVERS  %s -> %s  (%d cached days; %d drafted players compared; %s)%n",
                dates.get(0), last, dates.size(), pointMoves.size(), scoringNote));
        out.append("Sleeper half-PPR ADP: negative change = rising (drafted earlier). 'step' = the day carrying most of the move; a drift has no single day.\n");

        out.append(String.format("%n== PROJECTION MOVERS (league points, |change| >= %.0f) ==%n", minMove));
        out.append(String.format("%-24s %-3s %-4s %7s %7s %7s   %-14s %s%n",
                "player", "pos", "team", "from", "to", "change", "step", "Sleeper's own data says"));
        List<Move> byPoints = pointMovers(pointMoves, minMove);
        for(Move m : byPoints.subList(0, Math.min(top, byPoints.size()))){
            out.append(String.format("%-24s %-3s %-4s %7.1f %7.1f %+7.1f   %-14s %s%n",
                    cut(m.to().name(), 24), m.to().position(), m.to().team(),
                    m.from().points(), m.to().points(), m.delta(),
                    step(m), flags(m.from(), m.to(), windowStart)));
        }

        out.append(String.format("%n== ADP MOVERS (ranked relatively; |change| >= %.0f picks) ==%n", minMove));
        out.append(String.format("%-24s %-3s %-4s %7s %7s %7s %6s   %-14s %s%n",
                "player", "pos", "team", "from", "to", "change", "rel", "step", "Sleeper's own data says"));
        List<Move> ranked = adpMovers(adpMoves, minMove);
        for(Move m : ranked.subList(0, Math.min(top, ranked.size()))){
            out.append(String.format("%-24s %-3s %-4s %7.1f %7.1f %+7.1f %+5.0f%%   %-14s %s%n",
                    cut(m.to().name(), 24), m.to().position(), m.to().team(),
                    m.from().adp(), m.to().adp(), m.adpDelta(), 100 * (Math.exp(m.adpLogRatio()) - 1),
                    step(m), flags(m.from(), m.to(), windowStart)));
        }
        if(!entered.isEmpty() || !left.isEmpty()){
            out.append("\n== IN / OUT OF THE DRAFTED POOL ==\n");
            for(Row r : entered){ out.append(String.format("   now drafted:  %-24s %-3s %-4s adp %.1f%n", r.name(), r.position(), r.team(), r.adp())); }
            for(Row r : left){ out.append(String.format("   now undrafted: %-24s %-3s %-4s was adp %.1f%n", r.name(), r.position(), r.team(), r.adp())); }
        }

        out.append("\n== INJURY DESIGNATIONS TODAY (drafted pool, anything beyond Questionable) ==\n");
        List<Row> hurt = new ArrayList<>();
        for(Row r : latest.values()){
            if(r.adp() <= DRAFTABLE && r.injuryStatus() != null && !r.injuryStatus().equals("Questionable")){
                hurt.add(r);
            }
        }
        hurt.sort(Comparator.comparingDouble(Row::adp));
        for(Row r : hurt){
            out.append(String.format("   %-24s %-3s %-4s adp %6.1f  %s%s%n", r.name(), r.position(), r.team(), r.adp(),
                    r.injuryStatus(), r.injuryPart() == null ? "" : " (" + r.injuryPart() + ")"));
        }

        System.out.print(out);
        Path report = Path.of("data", "market-movers-" + last + ".txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }

    private static String step(Move m){
        if(m.stepDate() == null){ return "flat"; }
        // Over 100% means the day overshot and part of it was later given back.
        return m.stepShare() >= 60 ? "step " + m.stepDate().substring(5) + " " + Math.round(m.stepShare()) + "%" : "drift";
    }

    private static String cut(String s, int n){
        return s == null ? "?" : s.length() <= n ? s : s.substring(0, n);
    }
}
