import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What actually happened, recorded week by week against a bar frozen before a
 * single game was played.
 *
 * DIAGNOSTIC's fix list has carried one item since the draft with no tick beside
 * it: Justin finished 1st of twelve by BENCH and 9th by STARTERS, and the
 * objective the model drafts on scores a bench at exactly zero. Only results can
 * say whether that was a good trade, and results are only evidence if the test
 * was written down first. Written down afterwards, any season can be made to
 * agree with any story.
 *
 *   ./gradlew run -Pmain=SeasonLedger -Panchor   once, BEFORE week 1
 *   ./gradlew run -Pmain=SeasonLedger            each week: appends finished weeks,
 *                                                then prints the standing verdict
 *
 * THE PRE-REGISTRATION, in `data/season-anchor-<season>.txt` and never rewritten:
 *
 *   Justin's preseason rank by projected best-ten STARTERS was 9th of 12.
 *   If a deep bench is worth nothing, his rank by POINTS ACTUALLY SCORED should
 *   land about there. The bench is worth something if he finishes at least three
 *   places better; it is worth nothing if he finishes 9th or worse. Between the
 *   two is a null result, and it will be reported as one.
 *
 *   The MECHANISM, recorded alongside so the headline cannot be luck wearing a
 *   theory: the share of his scored points that came from men who were NOT in
 *   his frozen preseason best ten. A bench that pays does it by being promoted -
 *   into a bye, an injury, a collapse - and that share is what promotion looks
 *   like. A good rank with a low share is a hot roster, not a vindicated bench.
 *
 * Append-only, and safe to run twice: a week already in the file is skipped, and
 * only FINISHED weeks are ever written, so a week cannot be recorded before it
 * has happened (TRAPS #85).
 */
public class SeasonLedger {

    /** One roster's week. `fromBench` is points scored by men outside the frozen preseason ten. */
    public record Row(int week, String manager, double scored, double bestPossible,
                      double fromBench, int promoted) {}

    static final String HEADER = "week,manager,scored,best_possible,from_bench,promoted";

    /** Projected-starter totals this close are one rank, not two. */
    static final double TIE_POINTS = 5.0;

    /** A row per finished week per manager, in a stable order, ready to append. */
    static List<String> rowsFor(List<Row> rows){
        List<String> lines = new ArrayList<>();
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingInt(Row::week).thenComparing(Row::manager));
        for(Row row : sorted){
            lines.add(String.format("%d,%s,%.2f,%.2f,%.2f,%d", row.week(), row.manager(),
                    row.scored(), row.bestPossible(), row.fromBench(), row.promoted()));
        }
        return lines;
    }

    /** Weeks already in the ledger, so a rerun adds nothing twice. */
    static Set<Integer> weeksRecorded(List<String> lines){
        Set<Integer> weeks = new HashSet<>();
        for(String line : lines){
            if(line.isBlank() || line.startsWith("week,")){
                continue;
            }
            try {
                weeks.add(Integer.parseInt(line.split(",")[0].trim()));
            }
            catch(NumberFormatException notARow){
                // a comment or a blank; the ledger is meant to be readable by eye too
            }
        }
        return weeks;
    }

    /** 1-based rank of `manager` by a total, biggest first. */
    static int rankOf(Map<String, Double> totals, String manager){
        List<Map.Entry<String, Double>> order = new ArrayList<>(totals.entrySet());
        order.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        for(int i = 0; i < order.size(); i++){
            if(order.get(i).getKey().equals(manager)){
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * The pre-registered reading - but only once there is a season to read.
     *
     * The test is about where he FINISHES. Rendered after week 1 it would
     * announce "THE BENCH PAID" off one Sunday, which is the reverse of what
     * pre-registering was for: the point is that the answer cannot be shopped
     * for, and a verdict that changes every Tuesday is exactly a shopped one.
     */
    static String verdict(int preseasonRank, int actualRank, double benchShare,
                          int weeksRecorded, int regularSeasonWeeks){
        if(weeksRecorded < regularSeasonWeeks){
            return String.format("STANDING after %d of %d weeks (NOT the verdict): %d%s by points scored"
                    + " against %d%s projected, %.0f%% of his points from outside the frozen ten."
                    + " The pre-registered test is about where he FINISHES and will not be read until"
                    + " week %d is in.", weeksRecorded, regularSeasonWeeks, actualRank, suffix(actualRank),
                    preseasonRank, suffix(preseasonRank), 100 * benchShare, regularSeasonWeeks);
        }
        return verdict(preseasonRank, actualRank, benchShare);
    }

    /** The pre-registered reading. Better by 3+ places is the bench paying; 9th or worse is not. */
    static String verdict(int preseasonRank, int actualRank, double benchShare){
        if(actualRank <= preseasonRank - 3){
            return String.format("THE BENCH PAID: %d%s by points against %d%s projected. Mechanism check:"
                    + " %.0f%% of his points came from outside the frozen ten%s.",
                    actualRank, suffix(actualRank), preseasonRank, suffix(preseasonRank), 100 * benchShare,
                    benchShare < 0.15 ? " - LOW, so read the rank as a hot roster rather than a vindicated bench"
                            : ", which is what promotion looks like");
        }
        if(actualRank >= preseasonRank){
            return String.format("THE BENCH DID NOT PAY: %d%s by points against %d%s projected, and the draft's"
                    + " bench-first shape bought nothing measurable.", actualRank, suffix(actualRank),
                    preseasonRank, suffix(preseasonRank));
        }
        return String.format("NULL RESULT: %d%s by points against %d%s projected - better, but inside the"
                + " three places the test called noise before the season started.",
                actualRank, suffix(actualRank), preseasonRank, suffix(preseasonRank));
    }

    private static String suffix(int n){
        return n == 1 ? "st" : n == 2 ? "nd" : n == 3 ? "rd" : "th";
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String season = LeagueWeek.season();
        Path anchorPath = Path.of("data", "season-anchor-" + season + ".txt");
        Path ledgerPath = Path.of("data", "season-ledger-" + season + ".csv");

        if(System.getProperty("anchor") != null){
            if(Files.exists(anchorPath)){
                System.out.println(anchorPath + " already exists and is never rewritten - that is the point."
                        + " Delete it by hand if the pre-registration itself was wrong.");
                return;
            }
            Files.writeString(anchorPath, anchor(configuration, season), StandardCharsets.UTF_8);
            System.out.println("frozen: " + anchorPath);
            return;
        }
        if(!Files.exists(anchorPath)){
            System.out.println("no anchor yet - run with -Panchor BEFORE week 1, or the test is written"
                    + " after the results it judges and settles nothing.");
            return;
        }

        Map<String, List<String>> preseasonTen = frozenTen(anchorPath);
        Map<Integer, String> managerOf = managerByRoster(configuration);
        List<String> existing = Files.exists(ledgerPath) ? Files.readAllLines(ledgerPath) : new ArrayList<>();
        Set<Integer> already = weeksRecorded(existing);
        List<Row> fresh = new ArrayList<>();
        int current = LeagueWeek.week();
        for(int week = 1; week < current; week++){
            if(already.contains(week) || !LeagueWeek.finished(week)){
                continue;
            }
            Map<String, Double> actual = LeagueWeek.actual(season, week);
            // the WEEK's own rosters and lineups, not today's: the matchups feed
            // carries both, so best_possible is a fact about week w rather than
            // about the day this tool happened to be run
            for(LineupPromotion.RosterWeek row : LineupPromotion.week(configuration.getLeagueID(), week)){
                String manager = managerOf.get(row.rosterID());
                if(manager == null){
                    continue;
                }
                double scored = 0, fromBench = 0;
                int promoted = 0;
                List<String> frozen = preseasonTen.getOrDefault(manager, List.of());
                for(String id : row.started()){
                    if(id == null || id.isBlank() || id.equals("0")){
                        continue;   // an empty lineup slot, not a man
                    }
                    double points = actual.getOrDefault(id, 0.0);
                    scored += points;
                    if(!frozen.contains(id)){
                        fromBench += points;
                        promoted++;
                    }
                }
                List<TeamRankings.Man> all = new ArrayList<>();
                for(String id : row.roster()){
                    all.add(DraftExpectation.man(id, actual.getOrDefault(id, 0.0), false, 0));
                }
                fresh.add(new Row(week, manager, scored, TeamRankings.bestLineup(all).starters(),
                        fromBench, promoted));
            }
        }
        if(!fresh.isEmpty()){
            List<String> out = new ArrayList<>();
            if(existing.isEmpty()){
                out.add("# " + ledgerPath.getFileName() + " - append-only. Bar frozen in "
                        + anchorPath.getFileName() + " before week 1.");
                out.add(HEADER);
            }
            out.addAll(rowsFor(fresh));
            Files.write(ledgerPath, out, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        System.out.printf("%d new week-rows appended (%s)%n", fresh.size(), ledgerPath);
        judge(configuration, anchorPath, ledgerPath);
    }

    /** The frozen preseason ten per manager, parsed back out of the anchor. */
    static Map<String, List<String>> frozenTen(Path anchor) throws Exception {
        Map<String, List<String>> ten = new TreeMap<>();
        for(String line : Files.readAllLines(anchor)){
            if(!line.startsWith("TEN,")){
                continue;
            }
            String[] parts = line.split(",");
            List<String> ids = new ArrayList<>();
            for(int i = 2; i < parts.length; i++){
                ids.add(parts[i].trim());
            }
            ten.put(parts[1].trim(), ids);
        }
        return ten;
    }

    /**
     * Manager display name for each roster_id.
     *
     * THE JOIN IS roster_id, not "whoever owns the first starter today". A
     * matchup row carries its roster_id and the rosters array carries
     * roster_id with owner_id, so the exact join is sitting in the feed. The
     * first version looked up the current owner of the first man in the lineup,
     * which is wrong the moment anybody drops him: a week-3 lineup would be
     * attributed by who holds that player in December, or dropped entirely if
     * nobody does. A roster_id never changes hands.
     */
    static Map<Integer, String> managerByRoster(AAAConfiguration configuration){
        Map<String, String> nameByUser = configuration.getUserIDToDisplayName();
        Map<Integer, String> byRoster = new TreeMap<>();
        for(JsonElement element : JsonParser.parseString(
                configuration.getTodaysRosterWebPageSerious()).getAsJsonArray()){
            JsonObject roster = element.getAsJsonObject();
            if(!roster.has("roster_id") || roster.get("roster_id").isJsonNull()){
                continue;
            }
            String owner = roster.has("owner_id") && !roster.get("owner_id").isJsonNull()
                    ? roster.get("owner_id").getAsString() : null;
            if(owner != null){
                byRoster.put(roster.get("roster_id").getAsInt(), nameByUser.getOrDefault(owner, owner));
            }
        }
        return byRoster;
    }

    private static String anchor(AAAConfiguration configuration, String season) throws Exception {
        Map<String, Double> projected = ProjectionSources.resolve("sleeper");
        Map<String, String> ownerOf = LeagueOwners.today(configuration);
        Map<String, List<String>> rosters = new TreeMap<>();
        for(Map.Entry<String, String> entry : ownerOf.entrySet()){
            rosters.computeIfAbsent(entry.getValue(), u -> new ArrayList<>()).add(entry.getKey());
        }
        String me = configuration.getUserIDToDisplayName()
                .getOrDefault(configuration.getMyID(), configuration.getMyID());
        Map<String, Double> starterTotal = new TreeMap<>();
        Map<String, List<String>> tens = new TreeMap<>();
        for(Map.Entry<String, List<String>> entry : rosters.entrySet()){
            List<TeamRankings.Man> men = new ArrayList<>();
            for(String id : entry.getValue()){
                men.add(DraftExpectation.man(id, projected.getOrDefault(id, 0.0), false, 0));
            }
            TeamRankings.Lineup lineup = TeamRankings.bestLineup(men);
            starterTotal.put(entry.getKey(), lineup.starters());
            List<String> ten = new ArrayList<>();
            for(TeamRankings.Man man : lineup.starting()){
                ten.add(man.id());
            }
            tens.put(entry.getKey(), ten);
        }
        int myRank = rankOf(starterTotal, me);
        // HOW SOLID IS THAT RANK? The test is "three places better than his
        // projected rank", so it is worth exactly as much as the rank is. On
        // this board 8th and 9th are the same number to a decimal and 10th is
        // two points below, which the first anchor recorded as a bare "9th of
        // 12" and would have carried all season as if it were a fact.
        List<Map.Entry<String, Double>> ladder = new ArrayList<>(starterTotal.entrySet());
        ladder.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        double mine = starterTotal.get(me);
        int tiedFrom = myRank, tiedTo = myRank;
        for(int i = 0; i < ladder.size(); i++){
            if(Math.abs(ladder.get(i).getValue() - mine) <= TIE_POINTS){
                tiedFrom = Math.min(tiedFrom, i + 1);
                tiedTo = Math.max(tiedTo, i + 1);
            }
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format("SEASON ANCHOR %s - frozen %s, before a single game%n", season, LocalDate.now()));
        out.append("This file is never rewritten. It exists so the bench question is settled by a test\n");
        out.append("written BEFORE the results, not by a story told after them.\n\n");
        out.append("THE QUESTION. The objective this model drafts on scores a bench at exactly zero.\n");
        out.append(String.format("%s drafted the deepest bench in the league and the %d%s-best starting ten.%n",
                me, myRank, suffix(myRank)));
        out.append("Was that a good trade?\n\n");
        out.append("THE TEST, pre-registered:\n");
        out.append(String.format("  preseason rank by projected best-ten STARTERS: %d of %d%n", myRank, rosters.size()));
        if(tiedFrom != tiedTo){
            out.append(String.format("  BUT THAT RANK IS A TIE: ranks %d-%d are within %.0f points of each other%n",
                    tiedFrom, tiedTo, TIE_POINTS));
            out.append(String.format("  (%s), so which of them he is called is arbitrary and a one-place%n",
                    ladder.subList(tiedFrom - 1, tiedTo).stream()
                            .map(e -> String.format("%s %.1f", e.getKey(), e.getValue()))
                            .collect(java.util.stream.Collectors.joining(", "))));
            out.append(String.format("  error moves the threshold one place. Read a finish of %d%s as inside the%n",
                    myRank - 3 + (tiedTo - myRank), suffix(myRank - 3 + (tiedTo - myRank))));
            out.append("  anchor's own noise rather than as a clean pass.\n");
        }
        out.append(String.format("  the bench PAID      if he finishes %d%s or better by points actually scored%n",
                myRank - 3, suffix(myRank - 3)));
        out.append(String.format("  the bench PAID NOTHING if he finishes %d%s or worse%n", myRank, suffix(myRank)));
        out.append("  anything between is a NULL RESULT and will be reported as one\n\n");
        out.append("THE MECHANISM, recorded alongside so the headline cannot be luck wearing a theory:\n");
        out.append("  the share of his scored points from men OUTSIDE the frozen ten below. A bench that\n");
        out.append("  pays does it by being promoted - into a bye, an injury, a collapse. A good rank with\n");
        out.append("  a low share is a hot roster, not a vindicated bench.\n\n");
        out.append("PRESEASON PROJECTED STARTERS, every manager:\n");
        List<Map.Entry<String, Double>> order = new ArrayList<>(starterTotal.entrySet());
        order.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        for(int i = 0; i < order.size(); i++){
            out.append(String.format("  %2d. %-14s %8.1f%s%n", i + 1, order.get(i).getKey(),
                    order.get(i).getValue(), order.get(i).getKey().equals(me) ? "   <- him" : ""));
        }
        out.append("\nTHE FROZEN TEN per manager (ids), the men a bench is measured against:\n");
        for(Map.Entry<String, List<String>> entry : tens.entrySet()){
            out.append("TEN,").append(entry.getKey()).append(",")
               .append(String.join(",", entry.getValue())).append("\n");
        }
        return out.toString();
    }

    private static void judge(AAAConfiguration configuration, Path anchorPath, Path ledgerPath) throws Exception {
        if(!Files.exists(ledgerPath)){
            System.out.println("nothing recorded yet - the verdict waits for week 1.");
            return;
        }
        int preseasonRank = -1;
        for(String line : Files.readAllLines(anchorPath)){
            if(line.contains("preseason rank by projected best-ten STARTERS:")){
                preseasonRank = Integer.parseInt(line.split(":")[1].trim().split(" ")[0]);
            }
        }
        Map<String, Double> scored = new TreeMap<>();
        Map<String, Double> bench = new TreeMap<>();
        Set<Integer> weeks = new HashSet<>();
        for(String line : Files.readAllLines(ledgerPath)){
            if(line.isBlank() || line.startsWith("#") || line.startsWith("week,")){
                continue;
            }
            String[] parts = line.split(",");
            weeks.add(Integer.parseInt(parts[0].trim()));
            scored.merge(parts[1], Double.parseDouble(parts[2]), Double::sum);
            bench.merge(parts[1], Double.parseDouble(parts[4]), Double::sum);
        }
        String me = configuration.getUserIDToDisplayName()
                .getOrDefault(configuration.getMyID(), configuration.getMyID());
        if(!scored.containsKey(me) || preseasonRank < 0){
            System.out.println("not enough recorded to judge yet.");
            return;
        }
        int actualRank = rankOf(scored, me);
        double share = scored.get(me) == 0 ? 0 : bench.get(me) / scored.get(me);
        // the regular season is what the test was written about: this league's
        // playoffs start week 15, so weeks 1-14 are the population
        int regularSeason = configuration.getLeagueJson().getAsJsonObject("settings")
                .get("playoff_week_start").getAsInt() - 1;
        System.out.printf("%n%s%n", verdict(preseasonRank, actualRank, share, weeks.size(), regularSeason));
        System.out.printf("(%.0f points scored, %.0f of them from outside the frozen ten)%n",
                scored.get(me), bench.get(me));
    }
}
