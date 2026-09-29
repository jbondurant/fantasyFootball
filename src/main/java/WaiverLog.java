import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * THE SEASON'S WAIVER CLAIMS, EVERY ONE - WHO BID WHAT ON WHOM, AND WHO WON.
 *
 * Justin, week 2: "can you see the data for the bids and bid attempts?" Yes.
 * Sleeper's transactions feed keeps every claim placed, failed ones included,
 * with the bid, the man, the roster and the note that says WHY it failed - a
 * higher bid, or a roster that would have been too full. {@link FaabBid}
 * harvests five finished seasons of it into the win ladder the page bids on;
 * this is the same feed for THIS season, read live and printed as a log:
 *
 *   - each contest: the man, the moment it cleared, every bid by manager in
 *     order, the winner and the clearing price; a claim that died for a full
 *     roster is shown as such, because that is not a revealed price
 *   - each manager's FAAB spent, from the roster feed's own counter, beside the
 *     sum of his winning bids here - the two must agree, and the row says so
 *   - my own claims, won and lost, with what the winner paid
 *
 * The league runs DAILY waivers (settings.daily_waivers, at daily_waivers_hour)
 * with a one-day clear, so a claim can settle any morning, not only Wednesday;
 * the moments in the log are Sleeper's status_updated, which is when it did.
 *
 * A finished week's transactions are frozen forever; the live week's are read
 * through the day's cache ({@link LeagueWeek#transactions}), so a claim placed
 * this morning is in the log this afternoon.
 *
 *   ./gradlew run -Pmain=WaiverLog
 */
public class WaiverLog {

    /** One claim as Sleeper recorded it. */
    public record Claim(int week, String playerID, int rosterID, int bid, boolean won,
                        long created, long cleared, String note, List<String> dropped) {}

    /** Every waiver claim in a week's transactions. Free-agent adds and trades are not claims. */
    static List<Claim> claims(String body, int week){
        List<Claim> out = new ArrayList<>();
        for(JsonElement element : JsonParser.parseString(body).getAsJsonArray()){
            JsonObject row = element.getAsJsonObject();
            if(!"waiver".equals(text(row, "type")) || !row.has("adds") || row.get("adds").isJsonNull()
                    || !row.has("settings") || row.get("settings").isJsonNull()){
                continue;
            }
            JsonObject settings = row.getAsJsonObject("settings");
            int bid = settings.has("waiver_bid") && !settings.get("waiver_bid").isJsonNull()
                    ? settings.get("waiver_bid").getAsInt() : 0;
            boolean won = "complete".equals(text(row, "status"));
            long created = row.has("created") && !row.get("created").isJsonNull() ? row.get("created").getAsLong() : 0;
            long cleared = row.has("status_updated") && !row.get("status_updated").isJsonNull()
                    ? row.get("status_updated").getAsLong() : created;
            String note = row.has("metadata") && row.get("metadata").isJsonObject()
                    ? text(row.getAsJsonObject("metadata"), "notes") : null;
            int rosterID = row.has("roster_ids") && row.get("roster_ids").isJsonArray()
                    && row.getAsJsonArray("roster_ids").size() > 0
                    ? row.getAsJsonArray("roster_ids").get(0).getAsInt() : -1;
            List<String> dropped = new ArrayList<>();
            if(row.has("drops") && row.get("drops").isJsonObject()){
                dropped.addAll(row.getAsJsonObject("drops").keySet());
            }
            for(String playerID : row.getAsJsonObject("adds").keySet()){
                out.add(new Claim(week, playerID, rosterID, bid, won, created, cleared, note, dropped));
            }
        }
        return out;
    }

    /** Sleeper's own words for a claim that died for a reason other than price. */
    static boolean diedForRoom(Claim claim){
        return !claim.won() && claim.note() != null && claim.note().contains("too many players");
    }

    /** Claims grouped into contests: the same man, the same clearing moment. Insertion order is by clearing time. */
    static Map<String, List<Claim>> contests(List<Claim> claims){
        List<Claim> sorted = new ArrayList<>(claims);
        sorted.sort(Comparator.comparingLong(Claim::cleared).thenComparing(Claim::playerID));
        Map<String, List<Claim>> out = new LinkedHashMap<>();
        for(Claim claim : sorted){
            out.computeIfAbsent(claim.playerID() + "@" + claim.cleared(), k -> new ArrayList<>()).add(claim);
        }
        for(List<Claim> contest : out.values()){
            contest.sort(Comparator.comparingInt(Claim::bid).reversed().thenComparing(c -> !c.won()));
        }
        return out;
    }

    private static String text(JsonObject o, String key){
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    static String name(String playerID){
        Player player = Player.getPlayerFromSIDV2(playerID);
        if(player == null){
            return playerID;
        }
        return player.position != null && player.position.name().equals("DEF")
                ? player.team + " D/ST" : player.firstName + " " + player.lastName;
    }

    static String when(long millis){
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
                .toLocalDateTime().toString().replace('T', ' ').substring(5, 16);
    }

    public static void main(String[] args) throws IOException {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String leagueID = configuration.getLeagueID();
        String season = LeagueWeek.season();
        int current = LeagueWeek.week();
        Map<Integer, String> managerOf = SeasonLedger.managerByRoster(configuration);
        String me = configuration.getUserIDToDisplayName()
                .getOrDefault(configuration.getMyID(), configuration.getMyID());
        JsonObject settings = configuration.getLeagueJson().getAsJsonObject("settings");
        int budget = settings.has("waiver_budget") ? settings.get("waiver_budget").getAsInt() : 100;

        List<Claim> all = new ArrayList<>();
        for(int week = 1; week <= current; week++){
            all.addAll(claims(LeagueWeek.transactions(leagueID, week), week));
        }
        Map<String, List<Claim>> contests = contests(all);

        StringBuilder out = new StringBuilder();
        out.append(String.format("WAIVER LOG  season %s through week %d  (%d claims, %d contests; the live week read today)%n",
                season, current, all.size(), contests.size()));
        out.append(String.format("daily waivers at %s:00, %d-day clear, FAAB budget %d. Bids in order, winner marked; 'no room' = the roster would have been over-full, not outbid.%n",
                settings.has("daily_waivers_hour") ? settings.get("daily_waivers_hour").getAsString() : "?",
                settings.has("waiver_clear_days") ? settings.get("waiver_clear_days").getAsInt() : 1, budget));

        // Grouped by the DAY the claims cleared, not by Sleeper's leg: a claim placed
        // in week 1 and one placed in week 2 settle in the same daily run.
        String currentDay = "";
        Map<String, Integer> spent = new TreeMap<>();
        Map<String, int[]> record = new TreeMap<>();     // manager -> {won, lost to price, no room}
        for(Map.Entry<String, List<Claim>> e : contests.entrySet()){
            List<Claim> contest = e.getValue();
            Claim first = contest.get(0);
            String day = when(first.cleared()).substring(0, 5);
            if(!day.equals(currentDay)){
                currentDay = day;
                out.append(String.format("%n== cleared %s ==%n", day));
            }
            Claim winner = null;
            for(Claim c : contest){
                if(c.won()){
                    winner = c;
                }
            }
            StringBuilder bids = new StringBuilder();
            for(Claim c : contest){
                String manager = managerOf.getOrDefault(c.rosterID(), "roster " + c.rosterID());
                int[] r = record.computeIfAbsent(manager, k -> new int[3]);
                if(c.won()){
                    r[0]++;
                    spent.merge(manager, c.bid(), Integer::sum);
                }
                else if(diedForRoom(c)){
                    r[2]++;
                }
                else{
                    r[1]++;
                }
                bids.append(String.format("  %s $%d%s", manager, c.bid(),
                        c.won() ? " WON" : diedForRoom(c) ? " (no room)" : ""));
            }
            String drop = winner == null || winner.dropped().isEmpty() ? ""
                    : "  dropping " + String.join(", ", winner.dropped().stream().map(WaiverLog::name).toList());
            out.append(String.format("%s  %-24s %d bid%s:%s%s%n", when(first.cleared()).substring(6), name(first.playerID()),
                    contest.size(), contest.size() == 1 ? "" : "s", bids, drop));
        }

        // FAAB spent: the roster feed's counter against the log's winning bids
        out.append(String.format("%n== FAAB, %d each ==%n", budget));
        out.append(String.format("%-13s %6s %8s %7s %6s %6s %8s%n", "manager", "used", "log says", "left", "won", "outbid", "no room"));
        Map<String, Integer> used = new TreeMap<>();
        for(JsonElement element : JsonParser.parseString(configuration.getTodaysRosterWebPageSerious()).getAsJsonArray()){
            JsonObject roster = element.getAsJsonObject();
            if(!roster.has("roster_id") || !roster.has("settings")){
                continue;
            }
            JsonObject s = roster.getAsJsonObject("settings");
            String manager = managerOf.get(roster.get("roster_id").getAsInt());
            if(manager != null && s.has("waiver_budget_used")){
                used.put(manager, s.get("waiver_budget_used").getAsInt());
            }
        }
        List<String> managers = new ArrayList<>(managerOf.values());
        managers.sort(Comparator.comparingInt((String m) -> -used.getOrDefault(m, 0)).thenComparing(m -> m));
        for(String manager : managers){
            int u = used.getOrDefault(manager, 0);
            int logged = spent.getOrDefault(manager, 0);
            int[] r = record.getOrDefault(manager, new int[3]);
            out.append(String.format("%-13s %6d %8d %7d %6d %6d %8d%s%s%n", manager, u, logged, budget - u,
                    r[0], r[1], r[2], u == logged ? "" : "   <- the counter and the log disagree",
                    manager.equals(me) ? "   <- you" : ""));
        }

        // my claims
        out.append(String.format("%n== %s's claims ==%n", me));
        boolean any = false;
        for(List<Claim> contest : contests.values()){
            for(Claim c : contest){
                if(!me.equals(managerOf.get(c.rosterID()))){
                    continue;
                }
                any = true;
                Claim winner = null;
                for(Claim other : contest){
                    if(other.won()){
                        winner = other;
                    }
                }
                out.append(String.format("%s  %-24s bid $%d  %s%n", when(c.cleared()), name(c.playerID()), c.bid(),
                        c.won() ? "WON" : diedForRoom(c) ? "no room on the roster"
                                : winner == null ? "lost (nobody won it)"
                                : "lost to " + managerOf.getOrDefault(winner.rosterID(), "?") + " at $" + winner.bid()));
            }
        }
        if(!any){
            out.append("none this season\n");
        }
        out.append("\nA contest with one bid is uncontested: the winner paid what he chose, and a $0 there is the usual price.\n");
        out.append("The page's bid comes from FaabBid's ladder over five finished seasons; this log is the sixth as it happens.\n");

        System.out.print(out);
        Path report = Path.of("data", "waiver-log-" + season + "-w" + current + ".txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }
}
