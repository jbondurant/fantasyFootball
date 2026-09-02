import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.IntFunction;

/**
 * What each manager should have EXPECTED from his seat and keepers, against
 * what he actually drafted.
 *
 * BEFORE: the league as it stood on draft morning - 24 keepers declared, no
 * pick made (served from data/fixtures/2026-pre-draft). The draft is simulated
 * many times with the fitted room model choosing for EVERY seat, each seat's
 * simulated roster (keepers plus its picks) is scored as the best legal lineup
 * under league scoring, and the mean over simulations is that seat's
 * expectation. It answers: "drafting from slot S with these keepers, what does
 * a typical room hand you?"
 *
 * AFTER: the roster the manager actually holds, scored the same way with the
 * same projections. The difference is what his own picks were worth against
 * his seat - skill or luck, this cannot tell, but it is the honest baseline.
 *
 * Both sides use today's projections, so the comparison is fair even though
 * the feed has moved since draft night. Report-only.
 *
 *     ./gradlew run -Pmain=DraftExpectation [-Ptrials=200] [-Pprojections=sleeper]
 *
 * Writes data/draft-expectation-[date].txt and .html.
 */
public class DraftExpectation {

    public record Row(String manager, int slot, List<String> keepers,
                      double expected, double standardError, int expectedRank,
                      double actual, int actualRank) {
        double diff(){ return actual - expected; }
    }

    /* ---------------- pure pieces (tested) ---------------- */

    /** Player ids per manager from a finished simulation's takenAt (id -> pick number). */
    static Map<String, List<String>> rostersFrom(Map<String, Integer> takenAt, IntFunction<String> managerAt){
        Map<String, List<String>> rosters = new TreeMap<>();
        List<Map.Entry<String, Integer>> ordered = new ArrayList<>(takenAt.entrySet());
        ordered.sort(Map.Entry.comparingByValue());
        for(Map.Entry<String, Integer> taken : ordered){
            String manager = managerAt.apply(taken.getValue());
            if(manager != null){
                rosters.computeIfAbsent(manager, k -> new ArrayList<>()).add(taken.getKey());
            }
        }
        return rosters;
    }

    /** Mean and standard error of the mean. */
    static double[] meanAndError(List<Double> values){
        if(values.isEmpty()){
            return new double[]{0, 0};
        }
        double mean = 0;
        for(double v : values){ mean += v; }
        mean /= values.size();
        double ss = 0;
        for(double v : values){ ss += (v - mean) * (v - mean); }
        double sd = values.size() > 1 ? Math.sqrt(ss / (values.size() - 1)) : 0;
        return new double[]{mean, sd / Math.sqrt(values.size())};
    }

    /** 1 = best. Ties share the better rank. */
    static Map<String, Integer> ranks(Map<String, Double> byManager){
        List<Map.Entry<String, Double>> ordered = new ArrayList<>(byManager.entrySet());
        ordered.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        Map<String, Integer> rank = new HashMap<>();
        for(int i = 0; i < ordered.size(); i++){
            int r = i + 1;
            if(i > 0 && ordered.get(i).getValue().equals(ordered.get(i - 1).getValue())){
                r = rank.get(ordered.get(i - 1).getKey());
            }
            rank.put(ordered.get(i).getKey(), r);
        }
        return rank;
    }

    /* ---------------- the analysis ---------------- */

    static TeamRankings.Man man(String id, double points, boolean keeper, int pickNo){
        Player player = Player.getPlayerFromSIDV2(id);
        String name = player == null ? id : player.firstName + " " + player.lastName;
        String position = player == null || player.position == null ? "?" : player.position.name();
        String team = player == null ? "" : player.team;
        return new TeamRankings.Man(id, name, position, team, points, keeper, pickNo, null);
    }

    public static void main(String[] args) throws Exception {
        // BEFORE means the pre-draft league: served from the committed snapshot
        // unless the caller pointed elsewhere. No live pick exists in that world.
        if(System.getProperty("fixtureDir") == null){
            System.setProperty("fixtureDir", Path.of("data", "fixtures", "2026-pre-draft").toString());
        }
        LiveDraft.freezeWith(List.of());
        int trials = Integer.getInteger("trials", 200);

        LiveSetup setup = LiveSetup.forTonight();
        AAAConfiguration configuration = setup.configuration;
        DraftPlanner planner = setup.planner;
        DraftSimulator simulator = setup.simulator;
        Map<String, Double> points = planner.points();

        // Keepers per manager, from the pre-draft picks (is_keeper) - the
        // schedule holds the keeper SLOTS but not the men in them.
        Map<String, String> nameByUser = new HashMap<>();
        for(JsonElement e : JsonParser.parseString(InOutUtilities.getTodaysWebPage(
                configuration.getUsersWebURL(), AAAConfiguration.filepathStartUsers + configuration.getLeagueID()))
                .getAsJsonArray()){
            JsonObject u = e.getAsJsonObject();
            nameByUser.put(u.get("user_id").getAsString(), u.get("display_name").getAsString());
        }
        Map<String, List<String>> keepersOf = new TreeMap<>();
        Map<String, Integer> slotOf = new HashMap<>();
        for(JsonElement e : JsonParser.parseString(configuration.getTodaysDraftPicks()).getAsJsonArray()){
            JsonObject pick = e.getAsJsonObject();
            boolean keeper = pick.has("is_keeper") && !pick.get("is_keeper").isJsonNull() && pick.get("is_keeper").getAsBoolean();
            String by = pick.has("picked_by") && !pick.get("picked_by").isJsonNull() ? pick.get("picked_by").getAsString() : null;
            if(!keeper || by == null){ continue; }
            String manager = nameByUser.getOrDefault(by, by);
            keepersOf.computeIfAbsent(manager, k -> new ArrayList<>()).add(pick.get("player_id").getAsString());
            slotOf.put(manager, pick.get("draft_slot").getAsInt());
        }
        JsonObject draft = configuration.getDraftJson();
        if(draft.has("draft_order") && draft.get("draft_order").isJsonObject()){
            for(Map.Entry<String, JsonElement> entry : draft.getAsJsonObject("draft_order").entrySet()){
                slotOf.put(nameByUser.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue().getAsInt());
            }
        }

        // The schedule keys seats by whatever Slot.manager holds; normalise to display names.
        IntFunction<String> managerAt = pickNo -> {
            DraftSimulator.Slot slot = simulator.slotAt(pickNo);
            return slot == null ? null : nameByUser.getOrDefault(slot.manager(), slot.manager());
        };

        // BEFORE: simulate the whole draft, room model at every seat.
        Map<String, List<Double>> simulated = new TreeMap<>();
        long t0 = System.currentTimeMillis();
        for(int trial = 0; trial < trials; trial++){
            DraftSimulator.SimState state = simulator.initialState();
            simulator.simulateFrom(state, new Random(DraftSimulator.SEED + 104729L * trial), "", null);
            Map<String, List<String>> rosters = rostersFrom(state.takenAt, managerAt);
            for(String manager : slotOf.keySet()){
                List<TeamRankings.Man> men = new ArrayList<>();
                for(String id : keepersOf.getOrDefault(manager, List.of())){
                    men.add(man(id, points.getOrDefault(id, 0.0), true, 0));
                }
                for(String id : rosters.getOrDefault(manager, List.of())){
                    men.add(man(id, points.getOrDefault(id, 0.0), false, state.takenAt.get(id)));
                }
                simulated.computeIfAbsent(manager, k -> new ArrayList<>())
                        .add(TeamRankings.bestLineup(men).starters());
            }
        }
        double simSeconds = (System.currentTimeMillis() - t0) / 1000.0;

        // AFTER: the real rosters, from the live picks (never the fixture).
        Map<String, Double> actual = new TreeMap<>();
        Map<Integer, List<TeamRankings.Man>> real = new TreeMap<>();
        for(JsonElement e : JsonParser.parseString(InOutUtilities.getLiveWebPage(
                AAAConfiguration.draftPicksWebURL(configuration.getDraftID()),
                "livePicks" + configuration.getDraftID())).getAsJsonArray()){
            JsonObject pick = e.getAsJsonObject();
            String id = pick.get("player_id").getAsString();
            boolean keeper = pick.has("is_keeper") && !pick.get("is_keeper").isJsonNull() && pick.get("is_keeper").getAsBoolean();
            real.computeIfAbsent(pick.get("draft_slot").getAsInt(), k -> new ArrayList<>())
                    .add(man(id, points.getOrDefault(id, 0.0), keeper, pick.get("pick_no").getAsInt()));
        }
        Map<Integer, String> managerBySlot = new HashMap<>();
        for(Map.Entry<String, Integer> e : slotOf.entrySet()){ managerBySlot.put(e.getValue(), e.getKey()); }
        for(Map.Entry<Integer, List<TeamRankings.Man>> e : real.entrySet()){
            actual.put(managerBySlot.getOrDefault(e.getKey(), "slot " + e.getKey()),
                    TeamRankings.bestLineup(e.getValue()).starters());
        }

        Map<String, Double> expected = new TreeMap<>();
        Map<String, Double> errors = new HashMap<>();
        for(Map.Entry<String, List<Double>> e : simulated.entrySet()){
            double[] me = meanAndError(e.getValue());
            expected.put(e.getKey(), me[0]);
            errors.put(e.getKey(), me[1]);
        }
        Map<String, Integer> expectedRank = ranks(expected);
        Map<String, Integer> actualRank = ranks(actual);

        List<Row> rows = new ArrayList<>();
        for(String manager : expected.keySet()){
            List<String> keeperNames = new ArrayList<>();
            for(String id : keepersOf.getOrDefault(manager, List.of())){
                Player p = Player.getPlayerFromSIDV2(id);
                keeperNames.add(p == null ? id : p.lastName);
            }
            rows.add(new Row(manager, slotOf.getOrDefault(manager, 0), keeperNames,
                    expected.get(manager), errors.get(manager), expectedRank.get(manager),
                    actual.getOrDefault(manager, 0.0), actualRank.getOrDefault(manager, 0)));
        }
        rows.sort(Comparator.comparingDouble(Row::diff).reversed());

        String today = LocalDate.now().toString();
        StringBuilder out = new StringBuilder();
        out.append(String.format("DRAFT EXPECTATION vs RESULT  %s  (%d simulated drafts, room model at every seat, %.0fs; projections: %s)%n",
                today, trials, simSeconds, System.getProperty("projections", "sleeper")));
        out.append("EXPECTED = mean best-lineup starters a seat with these keepers gets when the fitted room drafts every pick.\n");
        out.append("ACTUAL = the roster the manager holds today, same lineup rule, same projections. DIFF = his own picks against his seat.\n\n");
        out.append(String.format("%-14s %4s  %-22s %9s %6s %4s   %8s %4s   %7s%n", "manager", "slot", "keepers", "expected", "+/-", "rk", "actual", "rk", "diff"));
        for(Row r : rows){
            out.append(String.format("%-14s %4d  %-22s %9.1f %6.1f %4d   %8.1f %4d   %+7.1f%n",
                    r.manager(), r.slot(), String.join(", ", r.keepers()), r.expected(), r.standardError(), r.expectedRank(),
                    r.actual(), r.actualRank(), r.diff()));
        }
        double meanDiff = 0;
        for(Row r : rows){ meanDiff += r.diff(); }
        meanDiff /= Math.max(1, rows.size());
        out.append(String.format("%nleague mean DIFF %+.1f: real managers beat the fitted room by this much on average"
                + " (they draft on projections the room model only approximates), so read each DIFF against it.%n", meanDiff));
        System.out.print(out);
        Files.createDirectories(Path.of("data"));
        Files.writeString(Path.of("data", "draft-expectation-" + today + ".txt"), out.toString(), StandardCharsets.UTF_8);
        Files.writeString(Path.of("data", "draft-expectation-" + today + ".html"), html(rows, today, trials), StandardCharsets.UTF_8);
        System.out.println("\nwritten to data/draft-expectation-" + today + ".txt and .html");
    }

    static String html(List<Row> rows, String today, int trials){
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
         .append("<title>Draft Expectation ").append(today).append("</title><style>")
         .append(":root{--bg:#fafaf7;--fg:#1c1c1a;--muted:#6b6b66;--line:#e3e3dd;--up:#4a7c59;--down:#b3261e;--me:#fff3c4}")
         .append("@media (prefers-color-scheme: dark){:root{--bg:#161614;--fg:#ecece7;--muted:#9a9a93;--line:#2c2c29;--up:#6fa37f;--down:#e0776d;--me:#3a3416}}")
         .append("body{margin:0 auto;padding:24px;max-width:1000px;background:var(--bg);color:var(--fg);font:14px/1.45 -apple-system,Segoe UI,Helvetica,Arial,sans-serif}")
         .append("h1{font-size:22px;margin:0 0 4px}.sub{color:var(--muted);margin-bottom:18px}table{border-collapse:collapse;width:100%}")
         .append("th,td{padding:7px 8px;border-bottom:1px solid var(--line);text-align:right;white-space:nowrap}th:nth-child(1),td:nth-child(1),th:nth-child(3),td:nth-child(3){text-align:left}")
         .append("th{color:var(--muted);font-weight:600;font-size:12px;text-transform:uppercase}.up{color:var(--up)}.down{color:var(--down)}tr.me td{background:var(--me)}")
         .append(".bar{display:inline-block;height:8px;border-radius:4px;vertical-align:middle}")
         .append("</style></head><body><h1>Draft expectation vs result</h1><div class='sub'>")
         .append(trials).append(" simulated drafts from the pre-draft league (24 keepers, no pick made), the fitted room model choosing every seat. ")
         .append("EXPECTED is the mean best-lineup starters that seat and those keepers yield; ACTUAL is the roster held today, same lineup rule, same projections. DIFF is the manager's own drafting against his seat. Sorted by DIFF.</div>");
        h.append("<table><tr><th>Manager</th><th>Slot</th><th>Keepers</th><th>Expected</th><th>&plusmn;</th><th>Rank</th><th>Actual</th><th>Rank</th><th>Diff</th><th></th></tr>");
        double maxAbs = 1;
        for(Row r : rows){ maxAbs = Math.max(maxAbs, Math.abs(r.diff())); }
        String me = System.getProperty("me", "justinb314");
        for(Row r : rows){
            double d = r.diff();
            h.append("<tr").append(r.manager().equals(me) ? " class='me'" : "").append("><td>").append(TeamRankings.esc(r.manager())).append("</td><td>").append(r.slot())
             .append("</td><td>").append(TeamRankings.esc(String.join(", ", r.keepers()))).append("</td><td>").append(String.format("%.0f", r.expected()))
             .append("</td><td>").append(String.format("%.0f", r.standardError())).append("</td><td>").append(r.expectedRank())
             .append("</td><td><b>").append(String.format("%.0f", r.actual())).append("</b></td><td>").append(r.actualRank())
             .append("</td><td class='").append(d >= 0 ? "up" : "down").append("'>").append(String.format("%+.0f", d)).append("</td>")
             .append("<td style='text-align:left;min-width:160px'><span class='bar' style='width:").append(String.format("%.0f", 150 * Math.abs(d) / maxAbs))
             .append("px;background:var(--").append(d >= 0 ? "up" : "down").append(")'></span></td></tr>");
        }
        double meanDiff = 0;
        for(Row r : rows){ meanDiff += r.diff(); }
        meanDiff /= Math.max(1, rows.size());
        h.append("</table><div class='sub' style='margin-top:18px'>League mean DIFF ").append(String.format("%+.0f", meanDiff))
         .append(": real managers beat the fitted room by this much on average, so read each DIFF against it. Expected ranks are what the seats and keepers alone would produce; actual ranks are the rosters as drafted. A manager above his expectation out-drafted his seat by that many projected starter points, on this feed, before a game is played.</div></body></html>");
        return h.toString();
    }
}
