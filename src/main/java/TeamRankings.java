import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every roster in the league's draft, scored and ranked, as an HTML page.
 *
 * Reads the live draft (keepers and every pick made so far), prices each
 * man with the same league-scored projections the draft tool uses, fills
 * each team's best legal lineup - QB, 2 RB, 3 WR, TE, DEF and two FLEX from
 * the remaining RB/WR/TE, exactly as {@code BoardValue.lineup} does - and
 * ranks the twelve teams by what those starters project to. Bench is shown
 * separately and does not rank; an empty slot scores zero and is counted as
 * a hole, so a half-drafted roster ranks honestly low.
 *
 * Injury designations come from Sleeper's player metadata in the same
 * projection response, so a man projected at full strength while on IR is
 * visible for what he is. Report-only; nothing here feeds a model.
 *
 *     ./gradlew run -Pmain=TeamRankings [-Pprojections=sleeper|blend:sleeper,espn,cbs]
 *
 * Writes {@code data/team-rankings-<date>.html} and prints the ranking.
 */
public class TeamRankings {

    public record Man(String id, String name, String position, String team, double points,
                      boolean keeper, int pickNo, String injury) {}

    public record Lineup(double starters, Map<String, Double> byPosition, List<Man> starting,
                         List<Man> bench, int holes) {}

    public record Team(int slot, String manager, List<Man> roster, Lineup lineup) {}

    /** Fixed slots in display order; FLEX (2) is filled afterwards from RB/WR/TE. */
    static final LinkedHashMap<String, Integer> FIXED = new LinkedHashMap<>();
    static {
        FIXED.put("QB", 1);
        FIXED.put("RB", 2);
        FIXED.put("WR", 3);
        FIXED.put("TE", 1);
        FIXED.put("DEF", 1);
    }
    static final int FLEX = 2;
    static final Set<String> FLEXES = Set.of("RB", "WR", "TE");

    /** The best legal lineup from a roster: fixed slots first, then two FLEX from what is left. */
    static Lineup bestLineup(List<Man> roster){
        Map<String, List<Man>> pool = new HashMap<>();
        for(Man man : roster){
            pool.computeIfAbsent(man.position(), k -> new ArrayList<>()).add(man);
        }
        for(List<Man> men : pool.values()){
            men.sort(Comparator.comparingDouble(Man::points).reversed());
        }
        List<Man> starting = new ArrayList<>();
        Map<String, Double> byPosition = new LinkedHashMap<>();
        List<Man> flexPool = new ArrayList<>();
        int holes = 0;
        for(Map.Entry<String, Integer> slot : FIXED.entrySet()){
            List<Man> have = pool.getOrDefault(slot.getKey(), List.of());
            double total = 0;
            for(int i = 0; i < slot.getValue(); i++){
                if(i < have.size()){
                    starting.add(have.get(i));
                    total += have.get(i).points();
                }
                else {
                    holes++;
                }
            }
            byPosition.put(slot.getKey(), total);
            if(FLEXES.contains(slot.getKey())){
                for(int i = slot.getValue(); i < have.size(); i++){
                    flexPool.add(have.get(i));
                }
            }
        }
        flexPool.sort(Comparator.comparingDouble(Man::points).reversed());
        double flexTotal = 0;
        for(int i = 0; i < FLEX; i++){
            if(i < flexPool.size()){
                starting.add(flexPool.get(i));
                flexTotal += flexPool.get(i).points();
            }
            else {
                holes++;
            }
        }
        byPosition.put("FLEX", flexTotal);
        List<Man> bench = new ArrayList<>(roster);
        bench.removeAll(starting);
        bench.sort(Comparator.comparingDouble(Man::points).reversed());
        double starters = 0;
        for(Man man : starting){
            starters += man.points();
        }
        return new Lineup(starters, byPosition, starting, bench, holes);
    }

    /** Ranked best starters first; bench total breaks ties. */
    static List<Team> rank(List<Team> teams){
        List<Team> ranked = new ArrayList<>(teams);
        ranked.sort(Comparator.comparingDouble((Team t) -> t.lineup().starters()).reversed()
                .thenComparingDouble(t -> -benchTotal(t.lineup())));
        return ranked;
    }

    static double benchTotal(Lineup lineup){
        double total = 0;
        for(Man man : lineup.bench()){
            total += man.points();
        }
        return total;
    }

    /* ---------------- live data ---------------- */

    private static String text(JsonObject o, String key){
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    public static void main(String[] args) throws IOException {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String draftID = System.getProperty("draftId", configuration.getDraftID());
        String source = System.getProperty("projections", "sleeper");
        Map<String, Double> points = ProjectionSources.resolve(source);

        // injury designations from the same projection response the model reads
        Map<String, String> injury = new HashMap<>();
        for(JsonElement element : SleeperProjections.getTodaysProjections()){
            JsonObject record = element.getAsJsonObject();
            JsonObject player = record.has("player") && record.get("player").isJsonObject()
                    ? record.getAsJsonObject("player") : null;
            String status = text(player, "injury_status");
            if(status != null){
                injury.put(record.get("player_id").getAsString(), status);
            }
        }

        JsonObject draft = JsonParser.parseString(InOutUtilities.getLiveWebPage(
                AAAConfiguration.draftWebURL(draftID), "draftObject" + draftID)).getAsJsonObject();
        int teams = draft.getAsJsonObject("settings").get("teams").getAsInt();
        int rounds = draft.getAsJsonObject("settings").get("rounds").getAsInt();
        Map<Integer, String> managerBySlot = new HashMap<>();
        Map<String, String> nameByUser = new HashMap<>();
        for(JsonElement element : JsonParser.parseString(InOutUtilities.getTodaysWebPage(
                "https://api.sleeper.app/v1/league/" + configuration.getLeagueID() + "/users",
                "leagueUsers" + configuration.getLeagueID())).getAsJsonArray()){
            JsonObject user = element.getAsJsonObject();
            nameByUser.put(text(user, "user_id"), text(user, "display_name"));
        }
        if(draft.has("draft_order") && draft.get("draft_order").isJsonObject()){
            for(Map.Entry<String, JsonElement> entry : draft.getAsJsonObject("draft_order").entrySet()){
                managerBySlot.put(entry.getValue().getAsInt(),
                        nameByUser.getOrDefault(entry.getKey(), entry.getKey()));
            }
        }

        JsonArray picks = JsonParser.parseString(InOutUtilities.getLiveWebPage(
                AAAConfiguration.draftPicksWebURL(draftID), "livePicks" + draftID)).getAsJsonArray();
        Map<Integer, List<Man>> rosters = new TreeMap<>();
        for(int slot = 1; slot <= teams; slot++){
            rosters.put(slot, new ArrayList<>());
        }
        int livePicks = 0;
        for(JsonElement element : picks){
            JsonObject pick = element.getAsJsonObject();
            JsonObject meta = pick.has("metadata") && pick.get("metadata").isJsonObject()
                    ? pick.getAsJsonObject("metadata") : null;
            String id = text(pick, "player_id");
            boolean keeper = pick.has("is_keeper") && !pick.get("is_keeper").isJsonNull()
                    && pick.get("is_keeper").getAsBoolean();
            if(!keeper){
                livePicks++;
            }
            int slot = pick.get("draft_slot").getAsInt();
            String name = (text(meta, "first_name") + " " + text(meta, "last_name")).trim();
            rosters.computeIfAbsent(slot, k -> new ArrayList<>()).add(new Man(id, name,
                    text(meta, "position"), text(meta, "team"), points.getOrDefault(id, 0.0),
                    keeper, pick.get("pick_no").getAsInt(), injury.get(id)));
        }

        List<Team> all = new ArrayList<>();
        for(Map.Entry<Integer, List<Man>> entry : rosters.entrySet()){
            all.add(new Team(entry.getKey(),
                    managerBySlot.getOrDefault(entry.getKey(), "slot " + entry.getKey()),
                    entry.getValue(), bestLineup(entry.getValue())));
        }
        List<Team> ranked = rank(all);

        String me = System.getProperty("me", "justinb314");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        int totalPicks = teams * rounds;
        int made = picks.size();
        String state = made >= totalPicks ? "draft complete"
                : String.format("draft in progress: %d of %d picks in (%d keepers, %d live)",
                        made, totalPicks, made - livePicks, livePicks);

        System.out.printf("TEAM RANKINGS  %s  (%s; projections: %s)%n", stamp, state, source);
        System.out.printf("%-4s %-16s %9s %7s %6s  %s%n", "rank", "manager", "starters", "bench", "holes", "QB / RB / WR / TE / DEF / FLEX");
        int r = 1;
        for(Team team : ranked){
            Lineup l = team.lineup();
            System.out.printf("%-4d %-16s %9.1f %7.1f %6d  %s%s%n", r++, team.manager(), l.starters(),
                    benchTotal(l), l.holes(), positions(l), team.manager().equals(me) ? "   <- me" : "");
        }

        Path out = Path.of("data", "team-rankings-" + stamp.substring(0, 10) + ".html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html(ranked, me, stamp, state, source), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + out);
    }

    private static String positions(Lineup l){
        StringBuilder sb = new StringBuilder();
        for(Map.Entry<String, Double> e : l.byPosition().entrySet()){
            if(sb.length() > 0){ sb.append(" / "); }
            sb.append(String.format("%.0f", e.getValue()));
        }
        return sb.toString();
    }

    /* ---------------- the page ---------------- */

    static String esc(String s){
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String html(List<Team> ranked, String me, String stamp, String state, String source){
        double best = ranked.isEmpty() ? 1 : Math.max(1, ranked.get(0).lineup().starters());
        double mean = 0;
        for(Team t : ranked){ mean += t.lineup().starters(); }
        mean = ranked.isEmpty() ? 0 : mean / ranked.size();
        Map<String, Double> meanByPos = new LinkedHashMap<>();
        for(Team t : ranked){
            for(Map.Entry<String, Double> e : t.lineup().byPosition().entrySet()){
                meanByPos.merge(e.getKey(), e.getValue() / ranked.size(), Double::sum);
            }
        }
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
         .append("<title>Team Rankings ").append(esc(stamp.substring(0, 10))).append("</title><style>")
         .append(":root{--bg:#fafaf7;--fg:#1c1c1a;--muted:#6b6b66;--line:#e3e3dd;--bar:#4a7c59;--me:#fff3c4;--flag:#b3261e;--card:#ffffff;}")
         .append("@media (prefers-color-scheme: dark){:root{--bg:#161614;--fg:#ecece7;--muted:#9a9a93;--line:#2c2c29;--bar:#6fa37f;--me:#3a3416;--card:#1f1f1c;}}")
         .append("body{margin:0;padding:24px;background:var(--bg);color:var(--fg);font:14px/1.45 -apple-system,Segoe UI,Helvetica,Arial,sans-serif;max-width:1100px;margin:auto}")
         .append("h1{font-size:22px;margin:0 0 4px}.sub{color:var(--muted);margin-bottom:18px}")
         .append("table{border-collapse:collapse;width:100%}th,td{padding:7px 8px;border-bottom:1px solid var(--line);text-align:right;white-space:nowrap}th:nth-child(2),td:nth-child(2){text-align:left}th{color:var(--muted);font-weight:600;font-size:12px;text-transform:uppercase;letter-spacing:.03em}")
         .append("tr.me td{background:var(--me)}.bar{height:8px;background:var(--bar);border-radius:4px;display:inline-block;vertical-align:middle}.barcell{text-align:left;min-width:180px}")
         .append(".pos{color:var(--muted)}.up{color:var(--bar)}.down{color:var(--flag)}")
         .append("details{margin:10px 0;background:var(--card);border:1px solid var(--line);border-radius:8px;padding:8px 12px}summary{cursor:pointer;font-weight:600}")
         .append(".roster{display:grid;grid-template-columns:1fr 1fr;gap:0 24px}.roster table{width:100%}.roster td,.roster th{padding:3px 6px;font-size:13px}")
         .append(".flag{color:var(--flag);font-weight:600}.keep{color:var(--muted);font-size:11px}")
         .append("@media (max-width:700px){.roster{grid-template-columns:1fr}}")
         .append("</style></head><body>");
        h.append("<h1>Team rankings</h1><div class='sub'>").append(esc(state)).append(" &middot; projections: ")
         .append(esc(source)).append(" (league scoring) &middot; ").append(esc(stamp))
         .append("<br>Ranked by the projected points of each team's best legal lineup: QB, 2 RB, 3 WR, TE, DEF, 2 FLEX. Bench does not rank. An empty slot scores 0 and counts as a hole.</div>");
        h.append("<table><tr><th>#</th><th>Manager</th><th class='barcell'></th><th>Starters</th><th>vs avg</th><th>Bench</th><th>Holes</th>");
        for(String pos : meanByPos.keySet()){ h.append("<th>").append(pos).append("</th>"); }
        h.append("</tr>");
        int r = 1;
        for(Team t : ranked){
            Lineup l = t.lineup();
            double diff = l.starters() - mean;
            h.append("<tr").append(t.manager().equals(me) ? " class='me'" : "").append("><td>").append(r++).append("</td><td>")
             .append(esc(t.manager())).append(t.manager().equals(me) ? " <span class='keep'>(me)</span>" : "").append("</td>")
             .append("<td class='barcell'><span class='bar' style='width:").append(String.format("%.0f", 180 * l.starters() / best)).append("px'></span></td>")
             .append("<td><b>").append(String.format("%.0f", l.starters())).append("</b></td>")
             .append("<td class='").append(diff >= 0 ? "up" : "down").append("'>").append(String.format("%+.0f", diff)).append("</td>")
             .append("<td>").append(String.format("%.0f", benchTotal(l))).append("</td><td>").append(l.holes()).append("</td>");
            for(Map.Entry<String, Double> e : l.byPosition().entrySet()){
                double d = e.getValue() - meanByPos.getOrDefault(e.getKey(), 0.0);
                h.append("<td>").append(String.format("%.0f", e.getValue())).append(" <span class='pos ").append(d >= 0 ? "up" : "down").append("'>")
                 .append(String.format("%+.0f", d)).append("</span></td>");
            }
            h.append("</tr>");
        }
        h.append("</table>");
        h.append("<h2 style='font-size:16px;margin-top:26px'>Rosters</h2>");
        r = 1;
        for(Team t : ranked){
            Lineup l = t.lineup();
            h.append("<details").append(t.manager().equals(me) ? " open" : "").append("><summary>").append(r++).append(". ")
             .append(esc(t.manager())).append(" &mdash; starters ").append(String.format("%.0f", l.starters()))
             .append(", bench ").append(String.format("%.0f", benchTotal(l))).append(l.holes() > 0 ? ", " + l.holes() + " empty slot" + (l.holes() > 1 ? "s" : "") : "")
             .append("</summary><div class='roster'>");
            h.append("<div><table><tr><th>Starter</th><th>Pos</th><th>Team</th><th>Proj</th><th>Pick</th></tr>");
            for(Man m : l.starting()){ h.append(row(m)); }
            h.append("</table></div><div><table><tr><th>Bench</th><th>Pos</th><th>Team</th><th>Proj</th><th>Pick</th></tr>");
            for(Man m : l.bench()){ h.append(row(m)); }
            h.append("</table></div></div></details>");
        }
        h.append("<div class='sub' style='margin-top:20px'>Injury designations are Sleeper's own tags at the time of the run. A designation next to a full projection means the feed has not priced it; read the man, not the number.</div>");
        h.append("</body></html>");
        return h.toString();
    }

    private static String row(Man m){
        boolean hurt = m.injury() != null && !m.injury().equals("Questionable");
        return "<tr><td style='text-align:left'>" + esc(m.name())
                + (m.injury() != null ? " <span class='" + (hurt ? "flag" : "pos") + "'>" + esc(m.injury()) + "</span>" : "")
                + (m.keeper() ? " <span class='keep'>keeper</span>" : "") + "</td><td>" + esc(m.position()) + "</td><td>"
                + esc(m.team()) + "</td><td>" + String.format("%.0f", m.points()) + "</td><td>" + m.pickNo() + "</td></tr>";
    }
}
