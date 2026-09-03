import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import PlayerImportAndSetup.Position;

/**
 * EVERY OWNER'S LADDER: what his seat is worth, what his keepers added, what
 * the best pair he could have kept would have added, and what he drafted.
 *
 * Four rungs, one yardstick - the mean best-legal-lineup projected starters
 * when the fitted room drafts every seat of the pre-draft league (as
 * DraftExpectation), 200 simulated drafts per rung:
 *
 *   1. SLOT      this owner's keepers phantomed (off the board, no credit, no
 *                slot burned), everyone else as declared - the seat alone
 *   2. KEEPERS   the 24 keepers as declared
 *   3. BEST PAIR this owner keeps the two men the 10k ledger valued highest
 *                (data/keeper-ledger-10k-2026-08-26.txt, standalone deltas),
 *                everyone else as declared
 *   4. DRAFTED   the roster he actually holds, same lineup rule, same feed
 *
 * Deltas: KEEPERS - SLOT is what his keepers were worth; BEST PAIR - KEEPERS is
 * what a better pair would have been worth (zero for the eight who kept their
 * top two); DRAFTED - KEEPERS is his own drafting against his seat and keepers.
 * Report-only.
 *
 *     ./gradlew run -Pmain=OwnerLadder [-Ptrials=200] [-Ptop=3] [-Pledger=data/keeper-ledger-10k-2026-08-26.txt]
 *
 * Also values each keeper ALONE: the top `top` ledger candidates per owner plus
 * any kept man outside them, kept by himself against the keeperless seat, on
 * the same yardstick as the rungs, with the ledger's Model A delta alongside.
 */
public class OwnerLadder {

    public record Candidate(String name, String position, int round, double delta, boolean kept) {}

    /** One keeper valued alone against the keeperless seat, on the ladder's yardstick and the ledger's. */
    public record Valued(Candidate candidate, double ladderDelta, double standardError) {}

    /** The top `top` ledger candidates plus any kept man outside them, ledger order. */
    static List<Candidate> toValue(List<Candidate> candidates, int top){
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(Candidate::delta).reversed());
        List<Candidate> out = new ArrayList<>(sorted.subList(0, Math.min(top, sorted.size())));
        for(Candidate c : sorted){
            if(c.kept() && !out.contains(c)){
                out.add(c);
            }
        }
        return out;
    }

    /** Per manager display name, the ledger's candidates in file order. */
    static Map<String, List<Candidate>> parseLedger(List<String> lines){
        Map<String, List<Candidate>> out = new LinkedHashMap<>();
        Pattern header = Pattern.compile("^(\\S+) \\(slot (\\d+), keeperless seat ([\\d.]+)\\):");
        Pattern row = Pattern.compile("^\\s+(\\*\\*)?([A-Za-z.' -]+?)(\\*\\*)?\\s+(QB|RB|WR|TE|DEF)\\s+r(\\d+)\\s+([+-][\\d.]+)");
        String current = null;
        for(String line : lines){
            Matcher h = header.matcher(line);
            if(h.find()){
                current = h.group(1);
                out.put(current, new ArrayList<>());
                continue;
            }
            Matcher r = row.matcher(line);
            if(current != null && r.find()){
                out.get(current).add(new Candidate(r.group(2).trim(), r.group(4), Integer.parseInt(r.group(5)),
                        Double.parseDouble(r.group(6)), r.group(1) != null));
            }
        }
        return out;
    }

    /** The two highest standalone deltas. */
    static List<Candidate> bestPair(List<Candidate> candidates){
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(Candidate::delta).reversed());
        return sorted.subList(0, Math.min(2, sorted.size()));
    }

    /* ---------------- one rung ---------------- */

    /** Mean starters per manager (display name) over `trials` simulated drafts of this planner's world. */
    static Map<String, double[]> rung(DraftPlanner planner, Map<String, List<String>> keepersOf,
                                      Map<String, String> nameByUser, int trials){
        DraftSimulator simulator = planner.simulator();
        Map<String, Double> points = planner.points();
        IntFunction<String> managerAt = pick -> {
            DraftSimulator.Slot slot = simulator.slotAt(pick);
            return slot == null ? null : nameByUser.getOrDefault(slot.manager(), slot.manager());
        };
        Map<String, List<Double>> totals = new TreeMap<>();
        for(int trial = 0; trial < trials; trial++){
            DraftSimulator.SimState state = simulator.initialState();
            simulator.simulateFrom(state, new Random(DraftSimulator.SEED + 104729L * trial), "", null);
            Map<String, List<String>> rosters = DraftExpectation.rostersFrom(state.takenAt, managerAt);
            Set<String> managers = new HashSet<>(rosters.keySet());
            managers.addAll(keepersOf.keySet());
            for(String manager : managers){
                List<TeamRankings.Man> men = new ArrayList<>();
                for(String id : keepersOf.getOrDefault(manager, List.of())){
                    men.add(DraftExpectation.man(id, points.getOrDefault(id, 0.0), true, 0));
                }
                for(String id : rosters.getOrDefault(manager, List.of())){
                    men.add(DraftExpectation.man(id, points.getOrDefault(id, 0.0), false, state.takenAt.get(id)));
                }
                totals.computeIfAbsent(manager, k -> new ArrayList<>()).add(TeamRankings.bestLineup(men).starters());
            }
        }
        Map<String, double[]> out = new TreeMap<>();
        for(Map.Entry<String, List<Double>> e : totals.entrySet()){
            out.put(e.getKey(), DraftExpectation.meanAndError(e.getValue()));
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        if(System.getProperty("fixtureDir") == null){
            System.setProperty("fixtureDir", Path.of("data", "fixtures", "2026-pre-draft").toString());
        }
        // THE SIXTEEN-ROUND SCHEDULE. The planner defaults to the nine-round
        // game, which drops every keeper kept at round 10 or later - twelve of
        // the twenty-four - and the first run of this tool priced five owners'
        // keepers as worth less than nothing because half of them were missing.
        System.setProperty("scheduleRounds", "16");
        System.setProperty("fullRounds", "true");
        LiveDraft.freezeWith(List.of());
        int trials = Integer.getInteger("trials", 200);
        Path ledgerPath = Path.of(System.getProperty("ledger", "data/keeper-ledger-10k-2026-08-26.txt"));

        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Map<String, String> nameByUser = configuration.getUserIDToDisplayName();
        Map<String, String> userByName = new HashMap<>();
        for(Map.Entry<String, String> e : nameByUser.entrySet()){ userByName.put(e.getValue(), e.getKey()); }
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);

        List<Keeper> declared = configuration.getTodaysKeepers();
        Map<String, List<String>> declaredOf = new TreeMap<>();
        Map<String, List<Keeper>> declaredKeepers = new HashMap<>();
        for(Keeper k : declared){
            String manager = nameByUser.getOrDefault(k.humanWhoCanKeep, k.humanWhoCanKeep);
            declaredOf.computeIfAbsent(manager, m -> new ArrayList<>()).add(k.player.sleeperIDString);
            declaredKeepers.computeIfAbsent(manager, m -> new ArrayList<>()).add(k);
        }

        long t0 = System.currentTimeMillis();
        // 2. KEEPERS as declared - built first because rung 1 borrows its world
        DraftPlanner actual = DraftPlanner.forCurrentSeasonAs(configuration, configuration.getMyID(),
                List.of(), Set.of(), model, earliness);
        Map<String, double[]> withKeepers = rung(actual, declaredOf, nameByUser, trials);

        // 1. SLOT: this owner's keepers are PHANTOMED - off the board, no lineup
        // credit, no slot burned - and everyone else keeps as declared. That is
        // the seat given the league's keeper structure and nothing else.
        // Two earlier readings were wrong: a league where NOBODY keeps (the 24
        // kept men return and every seat drafts from a richer pool), and "this
        // owner keeps nobody" with his own two men back on the board - which let
        // an owner who kept stars redraft them from his seat, so the column
        // peaked at jerem9604's slot 9 (Taylor, Bowers back) and Hamrliks' 10
        // (Chase Brown back) and fell 24 points at tommyrads' 11, whose returned
        // men were worth little. Those bumps were the counterfactual, not noise.
        // -PneutralSeat=true drafts the seat with the LEAGUE-AVERAGE quarterback
        // habit instead of the owner's learned one (the room model carries a
        // per-manager QB-earliness term: tommyrads -2.9 rounds, Justin +3.5).
        // With the owner's habit in, the column measures seat AND owner; with it
        // out, the seat. Tight-end and running-back habits stay as learned.
        boolean neutralSeat = Boolean.getBoolean("neutralSeat");
        Map<String, double[]> slotOnly = new TreeMap<>();
        for(String manager : declaredOf.keySet()){
            String user = userByName.getOrDefault(manager, manager);
            Map<String, Double> seatEarliness = new HashMap<>(earliness);
            if(neutralSeat){ seatEarliness.remove(user); }
            DraftPlanner phantomed = DraftPlanner.forCurrentSeasonAs(configuration, user,
                    List.of(), Set.of(), true, model, seatEarliness);
            Map<String, List<String>> keepersOf = new TreeMap<>(declaredOf);
            keepersOf.remove(manager);
            slotOnly.put(manager, rung(phantomed, keepersOf, nameByUser, trials).get(manager));
        }

        // 3. BEST PAIR per owner, from the ledger
        Map<String, List<Candidate>> ledger = parseLedger(Files.readAllLines(ledgerPath, StandardCharsets.UTF_8));
        Map<String, double[]> bestPairRung = new TreeMap<>();
        Map<String, String> bestPairNames = new TreeMap<>();
        Map<String, Boolean> keptTheirBest = new TreeMap<>();
        for(String manager : declaredOf.keySet()){
            List<Candidate> candidates = ledger.getOrDefault(manager, List.of());
            if(candidates.isEmpty()){ continue; }
            List<Candidate> best = bestPair(candidates);
            boolean same = best.stream().allMatch(Candidate::kept);
            keptTheirBest.put(manager, same);
            bestPairNames.put(manager, best.get(0).name() + " r" + best.get(0).round() + " + " + best.get(1).name() + " r" + best.get(1).round());
            if(same){
                bestPairRung.put(manager, withKeepers.get(manager));
                continue;
            }
            String user = userByName.getOrDefault(manager, manager);
            List<Keeper> replacement = new ArrayList<>();
            for(Candidate c : best){
                Keeper match = null;
                for(Keeper eligible : KeeperChooser.eligibleCandidates(configuration, user)){
                    String full = (eligible.player.firstName + " " + eligible.player.lastName).trim();
                    if(full.equalsIgnoreCase(c.name())){ match = eligible; break; }
                }
                if(match == null){
                    Player p = Player.getPlayerFromNameAndPos(c.name(), Position.valueOf(c.position()));
                    if(p != null){ match = new Keeper(user, p, c.round()); }
                }
                if(match != null){ replacement.add(match); }
            }
            if(replacement.size() < 2){
                System.out.printf("   (%s: could not resolve the ledger's best pair %s - rung 3 left as declared)%n",
                        manager, bestPairNames.get(manager));
                bestPairRung.put(manager, withKeepers.get(manager));
                continue;
            }
            Set<String> exclude = new HashSet<>(declaredOf.get(manager));
            DraftPlanner counterfactual = DraftPlanner.forCurrentSeasonAs(configuration, configuration.getMyID(),
                    replacement, exclude, model, earliness);
            Map<String, List<String>> keepersOf = new TreeMap<>(declaredOf);
            List<String> mine = new ArrayList<>();
            for(Keeper k : replacement){ mine.add(k.player.sleeperIDString); }
            keepersOf.put(manager, mine);
            bestPairRung.put(manager, rung(counterfactual, keepersOf, nameByUser, trials).get(manager));
        }

        // 2b. EACH KEEPER ALONE: the top three ledger candidates per owner, plus a
        // kept man outside that three, each kept by himself against the
        // keeperless seat (everyone else as declared) - the same yardstick as the
        // rungs, with the ledger's Model A number beside it.
        int top = Integer.getInteger("top", 3);
        Map<String, List<Valued>> valued = new TreeMap<>();
        for(String manager : declaredOf.keySet()){
            List<Candidate> candidates = ledger.getOrDefault(manager, List.of());
            if(candidates.isEmpty()){ continue; }
            String user = userByName.getOrDefault(manager, manager);
            List<Keeper> eligible = KeeperChooser.eligibleCandidates(configuration, user);
            for(Candidate c : toValue(candidates, top)){
                Keeper match = null;
                for(Keeper k : eligible){
                    if((k.player.firstName + " " + k.player.lastName).trim().equalsIgnoreCase(c.name())){ match = k; break; }
                }
                if(match == null){
                    Player p = Player.getPlayerFromNameAndPos(c.name(), Position.valueOf(c.position()));
                    if(p != null){ match = new Keeper(user, p, c.round()); }
                }
                if(match == null){ continue; }
                // keep this one man; the owner's OTHER declared keepers stay phantomed, not
                // returned to the board, so the delta is against the same seat as rung 1
                Set<String> others = new HashSet<>(declaredOf.get(manager));
                others.remove(match.player.sleeperIDString);
                DraftPlanner alone = DraftPlanner.forCurrentSeasonAs(configuration, user,
                        List.of(match), others, true, model, earliness);
                Map<String, List<String>> keepersOf = new TreeMap<>(declaredOf);
                keepersOf.put(manager, List.of(match.player.sleeperIDString));
                double[] v = rung(alone, keepersOf, nameByUser, trials).get(manager);
                valued.computeIfAbsent(manager, m -> new ArrayList<>())
                        .add(new Valued(c, v[0] - slotOnly.get(manager)[0], Math.hypot(v[1], slotOnly.get(manager)[1])));
            }
        }

        // 4. DRAFTED: the real rosters, from the live picks (never the fixture)
        Map<String, Double> points = actual.points();
        Map<Integer, String> managerBySlot = new HashMap<>();
        com.google.gson.JsonObject draft = configuration.getDraftJson();
        for(Map.Entry<String, com.google.gson.JsonElement> e : draft.getAsJsonObject("draft_order").entrySet()){
            managerBySlot.put(e.getValue().getAsInt(), nameByUser.getOrDefault(e.getKey(), e.getKey()));
        }
        Map<String, List<TeamRankings.Man>> real = new TreeMap<>();
        for(com.google.gson.JsonElement e : com.google.gson.JsonParser.parseString(InOutUtilities.getLiveWebPage(
                AAAConfiguration.draftPicksWebURL(configuration.getDraftID()),
                "livePicks" + configuration.getDraftID())).getAsJsonArray()){
            com.google.gson.JsonObject pick = e.getAsJsonObject();
            String id = pick.get("player_id").getAsString();
            boolean keeper = pick.has("is_keeper") && !pick.get("is_keeper").isJsonNull() && pick.get("is_keeper").getAsBoolean();
            String manager = managerBySlot.getOrDefault(pick.get("draft_slot").getAsInt(), "slot " + pick.get("draft_slot").getAsInt());
            real.computeIfAbsent(manager, m -> new ArrayList<>())
                    .add(DraftExpectation.man(id, points.getOrDefault(id, 0.0), keeper, pick.get("pick_no").getAsInt()));
        }
        Map<String, Double> drafted = new TreeMap<>();
        // A man on the commissioner exempt list or suspended carries a projection
        // the feed has cut (Jacobs: 186 to 80 the day before the draft). His
        // owner's DRAFTED rung is honest for the season the feed expects, but
        // the reader should see the man behind the number.
        Map<String, String> butHas = new TreeMap<>();
        for(Map.Entry<String, List<TeamRankings.Man>> e : real.entrySet()){
            drafted.put(e.getKey(), TeamRankings.bestLineup(e.getValue()).starters());
            List<String> suppressed = new ArrayList<>();
            for(TeamRankings.Man man : e.getValue()){
                String status = SleeperProjections.injuryStatusOf(man.id());
                if("NA".equals(status) || "Sus".equals(status)){
                    suppressed.add(man.name() + (status.equals("NA") ? ", exempt list" : ", suspended"));
                }
            }
            if(!suppressed.isEmpty()){
                butHas.put(e.getKey(), "but has " + String.join("; ", suppressed));
            }
        }
        double seconds = (System.currentTimeMillis() - t0) / 1000.0;

        // the table
        String today = LocalDate.now().toString();
        Map<String, Integer> slotOf = new HashMap<>();
        for(Map.Entry<Integer, String> e : managerBySlot.entrySet()){ slotOf.put(e.getValue(), e.getKey()); }
        List<String> managers = new ArrayList<>(declaredOf.keySet());
        managers.sort(Comparator.comparingInt(m -> slotOf.getOrDefault(m, 99)));
        StringBuilder out = new StringBuilder();
        out.append(String.format("OWNER LADDER  %s  (%d simulated drafts per rung, room model at every seat, %.0fs)%n", today, trials, seconds));
        out.append("SLOT = this owner's keepers phantomed, others as declared; KEEPERS = as declared; BEST PAIR = the ledger's two highest-valued keepers for this owner; DRAFTED = the roster held today.\n\n");
        out.append(String.format("%-12s %4s %8s %8s %7s %9s %7s %8s %7s   %s%n", "owner", "slot", "SLOT", "KEEPERS", "worth", "BEST PAIR", "gain", "DRAFTED", "drafting", "best pair (ledger)"));
        for(String m : managers){
            double s = slotOnly.getOrDefault(m, new double[]{0, 0})[0];
            double k = withKeepers.getOrDefault(m, new double[]{0, 0})[0];
            double b = bestPairRung.getOrDefault(m, new double[]{k, 0})[0];
            double d = drafted.getOrDefault(m, 0.0);
            out.append(String.format("%-12s %4d %8.1f %8.1f %+7.1f %9.1f %+7.1f %8.1f %+8.1f%s   %s%s%n",
                    m, slotOf.getOrDefault(m, 0), s, k, k - s, b, b - k, d, d - k,
                    butHas.containsKey(m) ? " (" + butHas.get(m) + ")" : "",
                    bestPairNames.getOrDefault(m, "?"), keptTheirBest.getOrDefault(m, false) ? " (kept)" : ""));
        }
        out.append(String.format("%nEACH KEEPER ALONE (top %d by the ledger, plus any kept man outside them): points over the keeperless seat%n", top));
        out.append(String.format("   %-12s %-24s %-3s %4s %8s %6s %8s%n", "owner", "keeper", "pos", "rnd", "ladder", "+/-", "ledger"));
        for(String m : managers){
            for(Valued v : valued.getOrDefault(m, List.of())){
                out.append(String.format("   %-12s %-24s %-3s r%-3d %+8.1f %6.1f %+8.1f%s%n", m, v.candidate().name(), v.candidate().position(),
                        v.candidate().round(), v.ladderDelta(), v.standardError(), v.candidate().delta(), v.candidate().kept() ? "  kept" : ""));
            }
        }
        System.out.print(out);
        Files.createDirectories(Path.of("data"));
        Files.writeString(Path.of("data", "owner-ladder-" + today + ".txt"), out.toString(), StandardCharsets.UTF_8);
        Files.writeString(Path.of("data", "owner-ladder-" + today + ".html"),
                html(managers, slotOf, slotOnly, withKeepers, bestPairRung, drafted, bestPairNames, keptTheirBest, declaredOf, points, today, trials, valued, butHas),
                StandardCharsets.UTF_8);
        System.out.println("\nwritten to data/owner-ladder-" + today + ".txt and .html");
    }

    static String html(List<String> managers, Map<String, Integer> slotOf, Map<String, double[]> slotOnly,
                       Map<String, double[]> withKeepers, Map<String, double[]> bestPairRung, Map<String, Double> drafted,
                       Map<String, String> bestPairNames, Map<String, Boolean> keptTheirBest,
                       Map<String, List<String>> declaredOf, Map<String, Double> points, String today, int trials,
                       Map<String, List<Valued>> valued, Map<String, String> butHas){
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for(String m : managers){
            for(double v : new double[]{slotOnly.get(m)[0], withKeepers.get(m)[0], bestPairRung.getOrDefault(m, withKeepers.get(m))[0], drafted.getOrDefault(m, 0.0)}){
                lo = Math.min(lo, v); hi = Math.max(hi, v);
            }
        }
        double floor = Math.floor((lo - 20) / 50) * 50, ceil = Math.ceil((hi + 20) / 50) * 50;
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
         .append("<title>Owner Ladder ").append(today).append("</title><style>")
         .append(":root{--bg:#fafaf7;--fg:#1c1c1a;--muted:#6b6b66;--line:#e3e3dd;--r1:#9a9a93;--r2:#4a7c59;--r3:#8a6d1f;--r4:#2f5f9e;--me:#fff3c4;--card:#fff}")
         .append("@media (prefers-color-scheme: dark){:root{--bg:#161614;--fg:#ecece7;--muted:#9a9a93;--line:#2c2c29;--r1:#6b6b66;--r2:#6fa37f;--r3:#c9a24a;--r4:#6f9bd6;--me:#3a3416;--card:#1f1f1c}}")
         .append("body{margin:0 auto;padding:24px;max-width:1100px;background:var(--bg);color:var(--fg);font:14px/1.45 -apple-system,Segoe UI,Helvetica,Arial,sans-serif}")
         .append("h1{font-size:22px;margin:0 0 4px}.sub{color:var(--muted);margin-bottom:16px}table{border-collapse:collapse;width:100%}")
         .append("th,td{padding:6px 8px;border-bottom:1px solid var(--line);text-align:right;white-space:nowrap}th:first-child,td:first-child{text-align:left}th{color:var(--muted);font-size:12px;text-transform:uppercase}")
         .append("tr.me td{background:var(--me)}.up{color:var(--r2)}.down{color:#b3261e}.card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px 14px;margin:10px 0}")
         .append(".ladder{display:grid;grid-template-columns:110px 1fr 70px;gap:4px 10px;align-items:center;font-size:13px}.bar{height:10px;border-radius:5px}")
         .append(".lg{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:6px;vertical-align:middle}.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}@media(max-width:760px){.grid{grid-template-columns:1fr}}")
         .append("</style></head><body><h1>Owner ladder</h1><div class='sub'>").append(trials).append(" simulated drafts per rung, the fitted room drafting every seat of the pre-draft league; each rung is the mean projected points of the best legal lineup. ")
         .append("<span class='lg' style='background:var(--r1)'></span>SLOT: this owner's keepers phantomed (off the board, no credit), everyone else as declared &nbsp; <span class='lg' style='background:var(--r2)'></span>KEEPERS: as declared &nbsp; <span class='lg' style='background:var(--r3)'></span>BEST PAIR: the 10k ledger's two highest-valued keepers for this owner &nbsp; <span class='lg' style='background:var(--r4)'></span>DRAFTED: the roster held today</div>");
        String me = System.getProperty("me", "justinb314");
        h.append("<table><tr><th>Owner</th><th>Slot</th><th>Slot only</th><th>+ keepers</th><th>worth</th><th>+ best pair</th><th>gain</th><th>Drafted</th><th>drafting</th></tr>");
        for(String m : managers){
            double s = slotOnly.get(m)[0], k = withKeepers.get(m)[0], b = bestPairRung.getOrDefault(m, withKeepers.get(m))[0], d = drafted.getOrDefault(m, 0.0);
            h.append("<tr").append(m.equals(me) ? " class='me'" : "").append("><td>").append(TeamRankings.esc(m)).append("</td><td>").append(slotOf.getOrDefault(m, 0))
             .append("</td><td>").append(String.format("%.0f", s)).append("</td><td>").append(String.format("%.0f", k)).append("</td><td class='").append(k - s >= 0 ? "up" : "down").append("'>").append(String.format("%+.0f", k - s))
             .append("</td><td>").append(String.format("%.0f", b)).append("</td><td class='").append(b - k > 0.5 ? "up" : "").append("'>").append(String.format("%+.0f", b - k))
             .append("</td><td><b>").append(String.format("%.0f", d)).append("</b></td><td class='").append(d - k >= 0 ? "up" : "down").append("'>").append(String.format("%+.0f", d - k))
             .append(butHas.containsKey(m) ? " <span style='color:var(--muted);font-weight:normal'>(" + TeamRankings.esc(butHas.get(m)) + ")</span>" : "").append("</td></tr>");
        }
        h.append("</table><div class='grid'>");
        String[] colors = {"var(--r1)", "var(--r2)", "var(--r3)", "var(--r4)"};
        String[] labels = {"Slot only", "+ keepers", "+ best pair", "Drafted"};
        for(String m : managers){
            double s = slotOnly.get(m)[0], k = withKeepers.get(m)[0], b = bestPairRung.getOrDefault(m, withKeepers.get(m))[0], d = drafted.getOrDefault(m, 0.0);
            double[] vals = {s, k, b, d};
            List<String> keeperNames = new ArrayList<>();
            for(String id : declaredOf.getOrDefault(m, List.of())){
                Player p = Player.getPlayerFromSIDV2(id);
                keeperNames.add(p == null ? id : p.lastName + " " + String.format("%.0f", points.getOrDefault(id, 0.0)));
            }
            h.append("<div class='card'").append(m.equals(me) ? " style='background:var(--me)'" : "").append("><b>").append(TeamRankings.esc(m)).append("</b> &middot; slot ").append(slotOf.getOrDefault(m, 0))
             .append(" &middot; kept ").append(TeamRankings.esc(String.join(", ", keeperNames)))
             .append(keptTheirBest.getOrDefault(m, false) ? " (the ledger's best pair)" : " &middot; ledger's best pair: " + TeamRankings.esc(bestPairNames.getOrDefault(m, "?")))
             .append("<div class='ladder' style='margin-top:8px'>");
            for(int i = 0; i < 4; i++){
                double w = Math.max(2, 100 * (vals[i] - floor) / (ceil - floor));
                h.append("<div>").append(labels[i]).append("</div><div><div class='bar' style='width:").append(String.format("%.1f", w)).append("%;background:").append(colors[i]).append("'></div></div><div>").append(String.format("%.0f", vals[i])).append("</div>");
            }
            h.append("</div>");
            List<Valued> vs = valued.getOrDefault(m, List.of());
            if(!vs.isEmpty()){
                double maxDelta = 1;
                for(Valued v : vs){ maxDelta = Math.max(maxDelta, Math.abs(v.ladderDelta())); }
                h.append("<div style='margin-top:8px;font-size:12px;color:var(--muted)'>each keeper alone, over the keeperless seat (ladder / ledger)</div><div class='ladder' style='grid-template-columns:150px 1fr 110px'>");
                for(Valued v : vs){
                    double w = Math.max(2, 100 * Math.abs(v.ladderDelta()) / maxDelta);
                    h.append("<div>").append(TeamRankings.esc(v.candidate().name())).append(" <span style='color:var(--muted)'>").append(v.candidate().position()).append(" r").append(v.candidate().round()).append(v.candidate().kept() ? " kept" : "").append("</span></div>")
                     .append("<div><div class='bar' style='width:").append(String.format("%.1f", w)).append("%;background:").append(v.ladderDelta() >= 0 ? "var(--r2)" : "#b3261e").append("'></div></div>")
                     .append("<div>").append(String.format("%+.0f / %+.0f", v.ladderDelta(), v.candidate().delta())).append("</div>");
                }
                h.append("</div>");
            }
            h.append("</div>");
        }
        h.append("</div><div class='sub' style='margin-top:16px'>Rung 3 uses the standalone keeper deltas the 10k ledger measured on 2026-08-26 (best two by delta); for the eight owners who kept exactly that pair it equals rung 2. Rung 4 is the same lineup rule on the real roster, same feed, so the drafting column is his own picks against his seat and keepers.</div></body></html>");
        return h.toString();
    }
}
