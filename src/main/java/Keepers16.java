import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

/**
 * KEEPERS ON THE SIXTEEN-ROUND GAME, WITH A DEFENCE AND A BENCH.
 *
 * The keeper tools of August (KeeperPlan, KeeperLedger) valued a keeper on
 * Model A's nine-round game: best nine skill starters by season total, no
 * defence, and a man kept at round 12 or 13 burned no pick at all - he cost a
 * round-nine starter only by benching the weakest of nine live picks
 * (KeeperValuation's stated rule). Season totals also make every bench man
 * worth exactly zero (TRAPS #28).
 *
 * This tool keeps the ladder's world - the pre-draft league, sixteen rounds,
 * every keeper at his REAL round, defences on the board, the fitted room
 * drafting every seat, a roster that ends legal - and changes only the
 * yardstick: WeeklyStarterValue, the rounds 1-16 objective. It scores a roster
 * as seventeen weeks of the best legal ten (QB RB2 WR3 TE DEF FLEX2) set on
 * PRESEASON expectation and scored on a drawn outcome, where each man's
 * outcome is a whole observed player-season from his position-and-tier cell of
 * 1,466 seasons: availability (injury) and scoring drawn together, the wire
 * competing for every slot. A bench man is worth what he starts in the weeks a
 * starter is down; a keeper's value includes the pick he really costs.
 *
 * ONE objective instance is shared across every world so the comparison is
 * paired (sample-average approximation): the same drawn seasons face every
 * roster. Candidates are every man the rules let the owner keep, valued ALONE
 * against the phantom seat; the best PAIR is searched among the top few, each
 * pair priced as a pair (the same-round bump, TRAPS #74/#75).
 *
 * What it does not do: promote a booming bench man mid-season (the objective
 * sets lineups on preseason expectation; BustBoomValue's promotion channel is
 * a bound, not a model), or know byes. Report-only.
 *
 *     ./gradlew run -Pmain=Keepers16 [-Ptrials=200] [-Pscenarios=480] [-PpairPool=6] [-Powners=justinb314,BHier]
 */
public class Keepers16 {

    public record Alone(String id, Keeper keeper, double value, double se) {}

    /** The top `pool` candidates by their stand-alone value. */
    static List<Alone> topByAlone(List<Alone> alone, int pool){
        List<Alone> sorted = new ArrayList<>(alone);
        sorted.sort(Comparator.comparingDouble(Alone::value).reversed());
        return sorted.subList(0, Math.min(pool, sorted.size()));
    }

    /**
     * The pair-search pool: the top men by stand-alone value PLUS every declared
     * keeper, so the pair the owner actually kept is always searched. Standalone
     * values do not add (TRAPS #75) and a cheap man low on this list can be half
     * of the best pair.
     */
    static List<Alone> pool(List<Alone> alone, int top, Set<String> declaredIDs){
        List<Alone> pool = new ArrayList<>(topByAlone(alone, top));
        for(Alone a : alone){
            if(declaredIDs.contains(a.id()) && pool.stream().noneMatch(p -> p.id().equals(a.id()))){
                pool.add(a);
            }
        }
        return pool;
    }

    /** Mean and standard error of the trial-by-trial difference world - base (the two share seeds). */
    static double[] paired(double[] world, double[] base){
        if(world.length != base.length){
            throw new IllegalArgumentException("unpaired: " + world.length + " vs " + base.length + " trials");
        }
        List<Double> diff = new ArrayList<>();
        for(int t = 0; t < world.length; t++){ diff.add(world[t] - base[t]); }
        return DraftExpectation.meanAndError(diff);
    }

    /** One man's row of the text report, parsed back for the HTML. */
    public record Row(String name, String position, int round, double delta, double se, boolean kept) {}
    /** One owner's block of the text report. */
    public record Block(String owner, double seat, String keptLabel, double keptValue, double keptDelta,
                        String pairLabel, double pairValue, double pairDelta, List<Row> rows, List<String> notes) {}

    private static final java.util.regex.Pattern HEADLINE = java.util.regex.Pattern.compile(
            "^(\\S+)\\s+seat\\s+([\\d.]+)\\s+kept\\s+(.+?)\\s+([\\d.]+) \\(\\s*([+-][\\d.]+)(?: \\+/-\\s*[\\d.]+)?\\)\\s+best pair\\s+(.+?)\\s+([\\d.]+) \\(\\s*([+-][\\d.]+)(?: \\+/-\\s*[\\d.]+)?\\)");
    private static final java.util.regex.Pattern ROW = java.util.regex.Pattern.compile(
            "^\\s+(.+?)\\s+(QB|RB|WR|TE|DEF|K)\\s+r(\\d+)\\s+([+-][\\d.]+)\\s+\\+/-\\s+([\\d.]+)(\\s+kept)?\\s*$");

    /** The text report back into blocks; lines before the first owner are the header. */
    static List<Block> parseReport(List<String> lines){
        List<Block> blocks = new ArrayList<>();
        Block current = null;
        for(String line : lines){
            java.util.regex.Matcher h = HEADLINE.matcher(line);
            if(h.find()){
                current = new Block(h.group(1), Double.parseDouble(h.group(2)), h.group(3).trim(), Double.parseDouble(h.group(4)),
                        Double.parseDouble(h.group(5)), h.group(6).trim(), Double.parseDouble(h.group(7)), Double.parseDouble(h.group(8)),
                        new ArrayList<>(), new ArrayList<>());
                blocks.add(current);
                continue;
            }
            if(current != null && (line.trim().startsWith("not valued") || line.trim().matches("^\\d+ pair\\(s\\).*"))){
                current.notes().add(line.trim());
                continue;
            }
            java.util.regex.Matcher r = ROW.matcher(line);
            if(current != null && r.find()){
                current.rows().add(new Row(r.group(1).trim(), r.group(2), Integer.parseInt(r.group(3)),
                        Double.parseDouble(r.group(4)), Double.parseDouble(r.group(5)), r.group(6) != null));
            }
        }
        return blocks;
    }

    private static String signed(double v){ return String.format("%+.1f", v); }
    private static String tone(double v){ return v > 0 ? "pos" : v < 0 ? "neg" : ""; }

    /** The report as one page: a league table, then a card per owner with every man's stand-alone value. */
    static String html(List<String> reportLines, String today){
        List<Block> blocks = parseReport(reportLines);
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html><head><meta charset='utf-8'><title>Keepers on the sixteen-round game ").append(today).append("</title><style>")
         .append(":root{--muted:#777;--pos:#1a7f37;--neg:#b42318;--line:#ddd;--me:#fff7d6}")
         .append("body{font:14px/1.4 -apple-system,Helvetica,Arial,sans-serif;margin:24px;max-width:1100px}")
         .append("h1{font-size:20px;margin:0 0 4px}h2{font-size:16px;margin:28px 0 6px}p.note{color:var(--muted);margin:0 0 16px}")
         .append("table{border-collapse:collapse;width:100%}th,td{padding:4px 8px;border-bottom:1px solid var(--line);text-align:right;white-space:nowrap}")
         .append("th{color:var(--muted);font-weight:normal}td.l,th.l{text-align:left}tr.me{background:var(--me)}tr.kept td{font-weight:600}")
         .append(".pos{color:var(--pos)}.neg{color:var(--neg)}.se{color:var(--muted);font-size:12px}")
         .append("</style></head><body>");
        h.append("<h1>Keepers on the sixteen-round game &middot; ").append(today).append("</h1>");
        for(String line : reportLines){
            if(HEADLINE.matcher(line).find()) break;
            if(!line.isBlank()) h.append("<p class='note'>").append(TeamRankings.esc(line)).append("</p>");
        }
        h.append("<table><tr><th class='l'>Owner</th><th>Seat</th><th class='l'>Kept</th><th>Value</th><th>vs seat</th>")
         .append("<th class='l'>Best pair on his roster</th><th>Value</th><th>vs kept</th></tr>");
        for(Block b : blocks){
            boolean same = b.pairDelta() == 0.0;
            h.append("<tr").append(b.owner().equals("justinb314") ? " class='me'" : "").append("><td class='l'>").append(TeamRankings.esc(b.owner()))
             .append("</td><td>").append(String.format("%.0f", b.seat()))
             .append("</td><td class='l'>").append(TeamRankings.esc(b.keptLabel()))
             .append("</td><td>").append(String.format("%.0f", b.keptValue()))
             .append("</td><td class='").append(tone(b.keptDelta())).append("'>").append(signed(b.keptDelta()))
             .append("</td><td class='l'").append(same ? " style='color:var(--muted)'" : "").append(">").append(same ? "the pair he kept" : TeamRankings.esc(b.pairLabel()))
             .append("</td><td>").append(String.format("%.0f", b.pairValue()))
             .append("</td><td class='").append(tone(b.pairDelta())).append("'>").append(signed(b.pairDelta())).append("</td></tr>");
        }
        h.append("</table>");
        for(Block b : blocks){
            h.append("<h2>").append(TeamRankings.esc(b.owner())).append(" &middot; seat ").append(String.format("%.0f", b.seat()))
             .append(" &middot; kept ").append(TeamRankings.esc(b.keptLabel())).append(" ").append(signed(b.keptDelta())).append("</h2>");
            for(String note : b.notes()){ h.append("<p class='note'>").append(TeamRankings.esc(note)).append("</p>"); }
            h.append("<table><tr><th class='l'>Man kept alone</th><th class='l'>Pos</th><th>Round</th><th>vs seat</th><th></th></tr>");
            for(Row r : b.rows()){
                h.append("<tr").append(r.kept() ? " class='kept'" : "").append("><td class='l'>").append(TeamRankings.esc(r.name()))
                 .append("</td><td class='l'>").append(r.position()).append("</td><td>r").append(r.round())
                 .append("</td><td class='").append(tone(r.delta())).append("'>").append(signed(r.delta()))
                 .append(" <span class='se'>&plusmn; ").append(String.format("%.1f", r.se())).append("</span></td><td class='l'>")
                 .append(r.kept() ? "kept" : "").append("</td></tr>");
            }
            h.append("</table>");
        }
        h.append("</body></html>");
        return h.toString();
    }

    private static Path htmlBeside(Path txt){ return Path.of(txt.toString().replaceAll("\\.txt$", "") + ".html"); }

    public static void main(String[] args) throws Exception {
        if(System.getProperty("render") != null){
            // -Drender=data/keepers16-<date>.txt: the HTML from an existing text report, no simulation
            Path txt = Path.of(System.getProperty("render"));
            List<String> lines = Files.readAllLines(txt, StandardCharsets.UTF_8);
            java.util.regex.Matcher d = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(lines.isEmpty() ? "" : lines.get(0));
            String date = d.find() ? d.group() : "";
            Files.writeString(htmlBeside(txt), html(lines, date), StandardCharsets.UTF_8);
            System.out.println("html: " + htmlBeside(txt) + " (" + parseReport(lines).size() + " owners)");
            return;
        }
        if(System.getProperty("fixtureDir") == null){
            System.setProperty("fixtureDir", Path.of("data", "fixtures", "2026-pre-draft").toString());
        }
        System.setProperty("scheduleRounds", "16");
        System.setProperty("fullRounds", "true");
        LiveDraft.freezeWith(List.of());
        int trials = Integer.getInteger("trials", 200);
        int scenarios = Integer.getInteger("scenarios", 480);   // multiples of 60 walk every sixty-season cell evenly
        int pairPool = Integer.getInteger("pairPool", 6);
        Set<String> only = new HashSet<>();
        if(System.getProperty("owners") != null){
            for(String o : System.getProperty("owners").split(",")){ only.add(o.trim()); }
        }
        // -Dshard=i/N: this JVM takes the owners whose index modulo N is i (parallel runs
        // write separate files via -Dout; concatenate them in owner order afterwards)
        int shardIndex = 0, shardCount = 1;
        if(System.getProperty("shard") != null){
            String[] parts = System.getProperty("shard").split("/");
            shardIndex = Integer.parseInt(parts[0].trim()); shardCount = Integer.parseInt(parts[1].trim());
        }

        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Map<String, String> nameByUser = configuration.getUserIDToDisplayName();
        Map<String, String> userByName = new HashMap<>();
        for(Map.Entry<String, String> e : nameByUser.entrySet()){ userByName.put(e.getValue(), e.getKey()); }
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        Map<String, Double> points = ProjectionSources.resolve(System.getProperty("projections", "sleeper"));

        // THE YARDSTICK, built once and shared by every world.
        long t0 = System.currentTimeMillis();
        WeeklyStarterValue value = WeeklyStarterValue.forCurrentBoard(configuration, points, scenarios, 424_242L);
        ToDoubleFunction<List<String>> scorer = ids -> value.of(ids);
        System.out.printf("weekly objective: %d scenarios over the projected board, built in %.1fs%n",
                scenarios, (System.currentTimeMillis() - t0) / 1000.0);

        List<Keeper> declared = configuration.getTodaysKeepers();
        Map<String, List<String>> declaredOf = new TreeMap<>();
        Map<String, List<Keeper>> declaredKeepers = new HashMap<>();
        for(Keeper k : declared){
            String manager = nameByUser.getOrDefault(k.humanWhoCanKeep, k.humanWhoCanKeep);
            declaredOf.computeIfAbsent(manager, m -> new ArrayList<>()).add(k.player.sleeperIDString);
            declaredKeepers.computeIfAbsent(manager, m -> new ArrayList<>()).add(k);
        }
        // every seat is an owner, keepers declared or not; a declared man with no
        // projection is said out loud - the kept world counts him as never available
        for(String name : nameByUser.values()){ declaredOf.putIfAbsent(name, new ArrayList<>()); }
        for(Keeper k : declared){
            if(points.getOrDefault(k.player.sleeperIDString, 0.0) <= 0){
                System.out.printf("*** declared keeper %s %s (r%d) has no projection - kept value counts him as never available%n",
                        k.player.firstName, k.player.lastName, k.roundCanBeKept);
            }
        }
        List<String> managers = new ArrayList<>(declaredOf.keySet());
        if(!only.isEmpty()){ managers.removeIf(m -> !only.contains(m)); }
        if(shardCount > 1){
            List<String> mine = new ArrayList<>();
            for(int i = 0; i < managers.size(); i++){ if(i % shardCount == shardIndex){ mine.add(managers.get(i)); } }
            managers = mine;
        }

        // rung 2 once: the league as declared
        DraftPlanner actual = DraftPlanner.forCurrentSeasonAs(configuration, configuration.getMyID(),
                List.of(), Set.of(), model, earliness);
        Map<String, double[]> withKeepers = OwnerLadder.rungTrials(actual, declaredOf, nameByUser, trials, scorer);

        StringBuilder out = new StringBuilder();
        String today = LocalDate.now().toString();
        out.append(String.format("KEEPERS ON THE SIXTEEN-ROUND GAME  %s  (%d simulated drafts per world, %d outcome scenarios, weekly starter objective with a defence and a bench)%n", today, trials, scenarios));
        out.append("Values are seventeen-week starter points. SEAT = the owner's keepers phantomed (off the board, no credit, no slot burned), others as declared.\n");
        out.append(String.format("ALONE = that one man kept by himself against the seat (his real round); the top %d by ALONE plus the declared men form the pair pool. BEST PAIR = the best legal pair in that pool, each priced as a pair.%n", pairPool));
        out.append("Every +/- is the standard error of the trial-by-trial difference (the worlds share seeds; the shared draws diverge after the first round a keeper changes, so pairing helps late keepers most). ALONE values do not add: two men kept together free two picks that compete for the same bench.\n");
        out.append("BEST PAIR is the largest of the searched pairs' means, so its edge over the kept pair carries selection bias on top of its +/-; two pairs inside one +/- of each other are a tie.\n");
        out.append("The defence wire is the streamed level (WireRateStress: 7.73 a week streamed over 6.98 held). Two keepers priced at one round: the ruleset moves the LOWER-ADP man a round dearer (KeeperPricing); the one case on record, 2025 Jeudy over Daniels, went the other way.\n\n");
        Map<String, Map<String, Object>> report = new LinkedHashMap<>();
        for(String manager : managers){
            long m0 = System.currentTimeMillis();
            String user = userByName.getOrDefault(manager, manager);
            Set<String> declaredIDs = new HashSet<>(declaredOf.get(manager));
            // SEAT: phantom
            DraftPlanner phantomed = DraftPlanner.forCurrentSeasonAs(configuration, user, List.of(), Set.of(), true, model, earliness);
            Map<String, List<String>> without = new TreeMap<>(declaredOf);
            without.remove(manager);
            double[] seat = OwnerLadder.rungTrials(phantomed, without, nameByUser, trials, scorer).get(manager);
            // ALONE: every man the rules let him keep, one at a time (others phantomed)
            List<Alone> alone = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for(Keeper k : KeeperChooser.eligibleCandidates(configuration, user)){
                String id = k.player.sleeperIDString;
                if(points.getOrDefault(id, 0.0) <= 0){
                    // an unprojected man would score as never available - not valued, but said
                    skipped.add(k.player.firstName + " " + k.player.lastName + " r" + k.roundCanBeKept);
                    continue;
                }
                Set<String> others = new HashSet<>(declaredIDs);
                others.remove(id);
                DraftPlanner one = DraftPlanner.forCurrentSeasonAs(configuration, configuration.getMyID(),
                        List.of(k), Set.of(), false, false, others, model, earliness);
                Map<String, List<String>> keepersOf = new TreeMap<>(without);
                keepersOf.put(manager, List.of(id));
                double[] v = OwnerLadder.rungTrials(one, keepersOf, nameByUser, trials, scorer).get(manager);
                double[] d = paired(v, seat);
                alone.add(new Alone(id, k, d[0], d[1]));
            }
            // BEST PAIR among the top candidates by alone value plus the declared men, each pair priced as a pair
            List<Alone> pool = pool(alone, pairPool, declaredIDs);
            double best = -Double.MAX_VALUE;
            List<Keeper> bestPair = null;
            double[] bestValue = null;
            int searched = 0, unpriceable = 0;
            for(int i = 0; i < pool.size(); i++){
                for(int j = i + 1; j < pool.size(); j++){
                    List<String> ids = List.of(pool.get(i).id(), pool.get(j).id());
                    List<Keeper> priced = KeeperChooser.priceHypothetical(configuration, user, ids);
                    if(priced == null || priced.size() != 2){ unpriceable++; continue; }
                    double[] v;
                    if(new HashSet<>(ids).equals(declaredIDs)){
                        v = withKeepers.get(manager);
                    }
                    else {
                        Set<String> phantom = new HashSet<>(declaredIDs);
                        phantom.removeAll(ids);
                        DraftPlanner two = DraftPlanner.forCurrentSeasonAs(configuration, configuration.getMyID(),
                                priced, Set.of(), false, false, phantom, model, earliness);
                        Map<String, List<String>> keepersOf = new TreeMap<>(declaredOf);
                        keepersOf.put(manager, ids);
                        v = OwnerLadder.rungTrials(two, keepersOf, nameByUser, trials, scorer).get(manager);
                    }
                    searched++;
                    double mean = Arrays.stream(v).average().orElse(0);
                    if(mean > best){ best = mean; bestPair = priced; bestValue = v; }
                }
            }
            double[] keptVector = withKeepers.get(manager);
            double[] keptDelta = paired(keptVector, seat);
            double[] pairValue = bestValue == null ? keptVector : bestValue;
            double[] pairDelta = paired(pairValue, keptVector);
            double kept = Arrays.stream(keptVector).average().orElse(0);
            double seatMean = Arrays.stream(seat).average().orElse(0);
            report.put(manager, Map.of("seat", seatMean, "alone", alone, "kept", kept,
                    "bestPair", bestPair == null ? List.of() : bestPair, "bestValue", Arrays.stream(pairValue).average().orElse(0),
                    "searched", searched));
            StringBuilder keptLabel = new StringBuilder();
            for(Keeper k : declaredKeepers.getOrDefault(manager, List.of())){
                if(keptLabel.length() > 0){ keptLabel.append(" · "); }
                keptLabel.append(k.player.lastName).append(" r").append(k.roundCanBeKept);
            }
            StringBuilder pairLabel = new StringBuilder();
            if(bestPair != null){
                for(Keeper k : bestPair){
                    if(pairLabel.length() > 0){ pairLabel.append(" · "); }
                    pairLabel.append(k.player.lastName).append(" r").append(k.roundCanBeKept);
                }
            }
            out.append(String.format("%-12s seat %7.1f   kept %-30s %7.1f (%+6.1f +/- %4.1f)   best pair %-30s %7.1f (%+6.1f +/- %4.1f)   %d pairs searched, %.0fs%n",
                    manager, seatMean, keptLabel, kept, keptDelta[0], keptDelta[1], pairLabel, Arrays.stream(pairValue).average().orElse(0),
                    pairDelta[0], pairDelta[1], searched, (System.currentTimeMillis() - m0) / 1000.0));
            if(unpriceable > 0){
                out.append("      ").append(unpriceable).append(" pair(s) the rules refuse to price were not searched\n");
            }
            if(!skipped.isEmpty()){
                out.append("      not valued (no projection): ").append(String.join(", ", skipped)).append("\n");
            }
            List<Alone> shown = new ArrayList<>(alone);
            shown.sort(Comparator.comparingDouble(Alone::value).reversed());
            for(Alone a : shown){
                out.append(String.format("      %-24s %-3s r%-3d %+8.1f  +/- %4.1f%s%n", a.keeper().player.firstName + " " + a.keeper().player.lastName,
                        a.keeper().player.position, a.keeper().roundCanBeKept, a.value(), a.se(), declaredIDs.contains(a.keeper().player.sleeperIDString) ? "  kept" : ""));
            }
            System.out.print(out.substring(out.lastIndexOf(manager + " ")));
            System.out.flush();
        }
        Files.createDirectories(Path.of("data"));
        Path target = Path.of(System.getProperty("out", "data/keepers16-" + today + ".txt"));
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + target);
        Files.writeString(htmlBeside(target), html(Files.readAllLines(target, StandardCharsets.UTF_8), today), StandardCharsets.UTF_8);
        System.out.println("html: " + htmlBeside(target));
    }
}
