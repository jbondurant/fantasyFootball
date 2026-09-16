import PlayerImportAndSetup.Position;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * DO THE WEEKLY PROJECTIONS ADD UP TO THE SEASON, AND DID WEEK ONE MOVE THEM?
 *
 * Justin, the night week one ended. Sleeper publishes a projection for every
 * man for every remaining week (918 rows carried points for week 2 on
 * 2026-09-14, 947 for week 18) and a season number beside them. The season
 * number was measured static on box scores the same day (TRAPS #136); this
 * asks the same of the weekly feed, three ways, each a count rather than an
 * opinion:
 *
 *  1. SUM. Whether the eighteen weekly numbers add up to the season number. If
 *     they do, the weekly feed is the season feed cut into games - one source,
 *     one staleness - and a season number that does not move means the weeks
 *     do not either.
 *  2. LEAN. Whether a man's week-2 projection leans on his week-1 result: the
 *     slope of his week-2 projection on (week-1 actual minus his PRE-GAME
 *     week-1 projection), by position. Zero means the box score is not in the
 *     weekly feed; the honest rule ({@link InSeasonLearning}) would put it near
 *     g/(kappa+g), about a tenth; a slope near one is a feed that chases.
 *     The week-2 number is measured against his season prior per game from the
 *     same pre-kickoff day, NOT against his week-1 projection: x already holds
 *     that number, and any noise in it would lean a shared-term slope positive
 *     by construction. That biased slope is printed too, labelled, so the size
 *     of the trap is visible. Matchups differ between the two weeks, which is
 *     noise in the slope and not a bias - a man's week-1 surprise does not
 *     know who he plays in week 2.
 *  3. DAY TO DAY. Every read of a live week is cached under its date, so once
 *     a week has two days on disk the per-man change between them is printed.
 *     No read of week 2 predates week 1's games, so week 2 has no before; from
 *     week 3 on, a week's reads straddle the previous week's games - and the
 *     days of news between them, which nothing on disk separates (TRAPS #137).
 *     The lean stays the box-score-specific measurement.
 *
 * Everything is league-scored through {@link LeagueWeek} - the same
 * scoreStatLine the lineup and the page use. A man absent from a week has no
 * projection that week (bye, out) and counts as zero in the sum, which is what
 * a season number means.
 *
 *   ./gradlew run -Pmain=WeeklyFeedAudit
 */
public class WeeklyFeedAudit {

    /** Ordinary least squares of y on x. */
    record Fit(double slope, double se, double r, int n){}

    /** Slope of y on x with its standard error and the correlation; NaN under three points or a flat x. */
    static Fit fit(List<double[]> xy){
        int n = xy.size();
        if(n < 3){
            return new Fit(Double.NaN, Double.NaN, Double.NaN, n);
        }
        double mx = 0;
        double my = 0;
        for(double[] p : xy){
            mx += p[0];
            my += p[1];
        }
        mx /= n;
        my /= n;
        double sxx = 0;
        double sxy = 0;
        double syy = 0;
        for(double[] p : xy){
            double dx = p[0] - mx;
            double dy = p[1] - my;
            sxx += dx * dx;
            sxy += dx * dy;
            syy += dy * dy;
        }
        if(sxx == 0){
            return new Fit(Double.NaN, Double.NaN, Double.NaN, n);
        }
        double slope = sxy / sxx;
        double residual = 0;
        for(double[] p : xy){
            double e = (p[1] - my) - slope * (p[0] - mx);
            residual += e * e;
        }
        double se = Math.sqrt(residual / (n - 2) / sxx);
        double r = syy == 0 ? Double.NaN : sxy / Math.sqrt(sxx * syy);
        return new Fit(slope, se, r, n);
    }

    /** The p-th percentile of a sorted list by nearest rank; NaN when empty. */
    static double percentile(List<Double> sorted, double p){
        if(sorted.isEmpty()){
            return Double.NaN;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /**
     * Is this file one day's read of that week's live projection? Anchored on
     * the date so that week 1's name is never a prefix match for week 10-18
     * (the mostRecentCached bug, TRAPS batch 1).
     */
    static boolean isDayFile(String name, String season, int week){
        return Pattern.compile("^sleeperLiveProjection" + season + "w" + week
                + "\\d{4}-\\d{2}-\\d{2}\\.txt$").matcher(name).matches();
    }

    /** The dated reads of one live week on disk, oldest first: date -> file. */
    static TreeMap<String, Path> liveDays(String season, int week) throws IOException {
        TreeMap<String, Path> days = new TreeMap<>();
        try(var list = Files.list(Path.of("."))){
            for(Path p : list.toList()){
                String name = p.getFileName().toString();
                if(isDayFile(name, season, week)){
                    days.put(name.substring(name.length() - 14, name.length() - 4), p);
                }
            }
        }
        return days;
    }

    public static void main(String[] args) throws IOException {
        String season = LeagueWeek.season();
        int current = LeagueWeek.week();
        int lastWeek = WeeklyActuals.WEEKS;
        int gamesInSeason = WeeklyStarterValue.WEEKS;
        LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;

        Map<String, Double> seasonPoints = ProjectionSources.resolve("sleeper");
        TreeMap<String, Path> seasonDays = MarketMovers.cachedDays(season);
        Map<String, MarketMovers.Row> meta = MarketMovers.read(seasonDays.get(seasonDays.lastKey()), scoring);

        List<Map<String, Double>> weekly = new ArrayList<>();
        for(int w = 1; w <= lastWeek; w++){
            weekly.add(LeagueWeek.projected(season, w));
        }

        StringBuilder out = new StringBuilder();
        out.append(String.format("WEEKLY FEED AUDIT  season %s, Sleeper on week %d  (%s)%n", season, current, DataStamp.line()));

        // ------------------------------------------------------------ 1. coverage and the sum
        out.append("\n== 1. THE SUM: eighteen weekly projections against the season number, league-scored ==\n");
        out.append(String.format("%-6s", "week"));
        for(int w = 1; w <= lastWeek; w++){
            out.append(String.format("%6d", w));
        }
        out.append(String.format("%n%-6s", "rows"));
        for(int w = 1; w <= lastWeek; w++){
            out.append(String.format("%6d", weekly.get(w - 1).size()));
        }
        out.append('\n');

        Map<String, Double> sum = new HashMap<>();
        Map<String, Integer> weeksWith = new HashMap<>();
        for(Map<String, Double> week : weekly){
            for(Map.Entry<String, Double> e : week.entrySet()){
                sum.merge(e.getKey(), e.getValue(), Double::sum);
                weeksWith.merge(e.getKey(), 1, Integer::sum);
            }
        }
        Map<String, List<Double>> ratios = new LinkedHashMap<>();
        Map<String, List<double[]>> pairs = new LinkedHashMap<>();
        Map<Integer, Integer> weeksHistogram = new TreeMap<>();
        List<String[]> gaps = new ArrayList<>();
        for(String position : new String[]{"QB", "RB", "WR", "TE", "DEF"}){
            ratios.put(position, new ArrayList<>());
            pairs.put(position, new ArrayList<>());
        }
        for(Map.Entry<String, Double> e : seasonPoints.entrySet()){
            String id = e.getKey();
            MarketMovers.Row row = meta.get(id);
            if(row == null || row.team() == null || e.getValue() <= 0 || !ratios.containsKey(row.position())){
                continue;
            }
            double total = sum.getOrDefault(id, 0.0);
            ratios.get(row.position()).add(total / e.getValue());
            pairs.get(row.position()).add(new double[]{e.getValue(), total});
            weeksHistogram.merge(weeksWith.getOrDefault(id, 0), 1, Integer::sum);
            gaps.add(new String[]{id, row.position(), String.valueOf(total - e.getValue())});
        }
        out.append(String.format("%n%-4s %6s %10s %10s %10s %8s   the season number is the x, the sum the y%n",
                "pos", "men", "median", "p10", "p90", "corr"));
        for(Map.Entry<String, List<Double>> e : ratios.entrySet()){
            List<Double> sorted = new ArrayList<>(e.getValue());
            sorted.sort(Comparator.naturalOrder());
            Fit f = fit(pairs.get(e.getKey()));
            out.append(String.format("%-4s %6d %10.3f %10.3f %10.3f %8.3f%n", e.getKey(), sorted.size(),
                    percentile(sorted, 50), percentile(sorted, 10), percentile(sorted, 90), f.r()));
        }
        out.append("ratio = sum of his weekly projections / his season projection. weeks carrying a projection per man:");
        for(Map.Entry<Integer, Integer> e : weeksHistogram.entrySet()){
            out.append(String.format(" %d:%d", e.getKey(), e.getValue()));
        }
        out.append("\n(17 weeks is a full season with one bye; fewer is a man Sleeper has out for some of it; 0 is a man the season\n");
        out.append("feed gives points to and the weekly feed never does)\n");
        Map<String, Integer> zeroWeeks = new LinkedHashMap<>();
        int defAboveOne = 0;
        for(String[] g : gaps){
            if(weeksWith.getOrDefault(g[0], 0) == 0){
                zeroWeeks.merge(g[1], 1, Integer::sum);
            }
        }
        for(double ratio : ratios.get("DEF")){
            if(ratio > 1){
                defAboveOne++;
            }
        }
        out.append("men with no weekly row at all, by position:");
        for(Map.Entry<String, Integer> e : zeroWeeks.entrySet()){
            out.append(String.format(" %s %d", e.getKey(), e.getValue()));
        }
        out.append(String.format("%nDEF: %d of %d sum above their season number. The two feeds' defence lines carry different categories -%n",
                defAboveOne, ratios.get("DEF").size()));
        TreeSet<String> seasonKeys = defenceKeys(Files.readString(seasonDays.get(seasonDays.lastKey()), StandardCharsets.UTF_8), true);
        TreeSet<String> weekKeys = new TreeSet<>();
        TreeMap<String, Path> anyWeek = liveDays(season, Math.min(lastWeek, current + 1));
        if(!anyWeek.isEmpty()){
            weekKeys = defenceKeys(Files.readString(anyWeek.lastEntry().getValue(), StandardCharsets.UTF_8), false);
        }
        TreeSet<String> onlyWeekly = new TreeSet<>(weekKeys);
        onlyWeekly.removeAll(seasonKeys);
        out.append("season DEF line: " + String.join(" ", seasonKeys) + "\n");
        out.append("weekly DEF line adds: " + String.join(" ", onlyWeekly) + "\n");
        out.append("(both are scored by Sleeper's own pts_half_ppr, a defence having no offensive line; a season line missing\n");
        out.append("categories the league pays for is that much low everywhere the season feed prices a defence)\n");
        gaps.sort(Comparator.comparingDouble((String[] g) -> -Math.abs(Double.parseDouble(g[2]))));
        out.append("\nbiggest gaps, sum minus season:\n");
        for(String[] g : gaps.subList(0, Math.min(10, gaps.size()))){
            MarketMovers.Row row = meta.get(g[0]);
            out.append(String.format("   %-24s %-3s %-4s season %6.1f  sum %6.1f  %+7.1f  weeks %2d  %s%n",
                    row.name(), row.position(), row.team(), seasonPoints.get(g[0]), sum.getOrDefault(g[0], 0.0),
                    Double.parseDouble(g[2]), weeksWith.getOrDefault(g[0], 0),
                    row.injuryStatus() == null ? "" : row.injuryStatus()
                            + (row.injuryPart() == null ? "" : " (" + row.injuryPart() + ")")));
        }

        // ------------------------------------------------------------ 2. the lean of week 2 on week 1
        out.append("\n== 2. THE LEAN: does the week-2 projection carry the week-1 result? ==\n");
        String seasonStart = LeagueWeek.state().get("season_start_date").getAsString();
        String priorDay = WeekReaction.priorDay(seasonDays.keySet(), seasonStart);
        Map<String, MarketMovers.Row> seasonPrior = priorDay == null ? Map.of()
                : MarketMovers.read(seasonDays.get(priorDay), scoring);
        TreeMap<String, Path> week1Days = liveDays(season, 1);
        String preGameDay = WeekReaction.priorDay(week1Days.keySet(), seasonStart);
        Map<String, Double> preGame;
        String preGameSource;
        if(preGameDay != null){
            preGame = LeagueWeek.projectedFrom(Files.readString(week1Days.get(preGameDay), StandardCharsets.UTF_8));
            preGameSource = "the last live read before kickoff, " + preGameDay;
        }
        else{
            String url1 = "https://api.sleeper.app/v1/projections/nfl/regular/" + season + "/1";
            preGame = LeagueWeek.projectedFrom(
                    InOutUtilities.getCachedForever(url1, "sleeperWeekProjection" + season + "w1"));
            preGameSource = "the frozen pre-season file";
        }
        Map<String, Double> actual1 = LeagueWeek.actualSoFar(season, 1);
        Map<String, Double> week2 = weekly.get(1);
        // Two y's. Against his own pre-game week-1 number, which x also contains -
        // so any noise in that number leans the slope positive by construction.
        // And against his season prior per game from the same pre-kickoff day,
        // which x does not contain: the slope that can be believed.
        Map<String, List<double[]>> leanOwn = new LinkedHashMap<>();
        Map<String, List<double[]>> leanPrior = new LinkedHashMap<>();
        for(String position : new String[]{"QB", "RB", "WR", "TE"}){
            leanOwn.put(position, new ArrayList<>());
            leanPrior.put(position, new ArrayList<>());
        }
        List<double[]> pooledOwn = new ArrayList<>();
        List<double[]> pooledPrior = new ArrayList<>();
        for(String id : preGame.keySet()){
            MarketMovers.Row row = meta.get(id);
            Double a = actual1.get(id);
            Double p2 = week2.get(id);
            if(row == null || a == null || p2 == null || !leanOwn.containsKey(row.position()) || preGame.get(id) <= 0){
                continue;
            }
            double x = a - preGame.get(id);
            leanOwn.get(row.position()).add(new double[]{x, p2 - preGame.get(id)});
            pooledOwn.add(new double[]{x, p2 - preGame.get(id)});
            MarketMovers.Row prior = seasonPrior.get(id);
            if(prior != null && prior.points() > 0){
                double y = p2 - prior.points() / gamesInSeason;
                leanPrior.get(row.position()).add(new double[]{x, y});
                pooledPrior.add(new double[]{x, y});
            }
        }
        // The honest rule's share after one game, per position, from the same fit WeekReaction prints.
        Map<String, EraBoards.Board> boards = EraBoards.usable("ppr", EraIngest.MIN_RATE, EraIngest.minDepth());
        Map<Position, InSeasonLearning.Kappa> kappa = InSeasonLearning.fitKappa(InSeasonLearning.harvest(boards), null);
        Map<String, Double> honest = new LinkedHashMap<>();
        for(String position : new String[]{"QB", "RB", "WR", "TE"}){
            honest.put(position, WeekReaction.keptShare(1, kappa.get(Position.valueOf(position)).kappa()));
        }
        // Men who played week 1 and have no week-2 row at all - removed by injury or
        // demotion. Conditioning on a week-2 row drops them, and they are the
        // largest reactions there are, so the slope is also shown with them at zero.
        Map<String, List<double[]>> leanPriorAll = new LinkedHashMap<>();
        for(String position : new String[]{"QB", "RB", "WR", "TE"}){
            leanPriorAll.put(position, new ArrayList<>(leanPrior.get(position)));
        }
        List<double[]> pooledPriorAll = new ArrayList<>(pooledPrior);
        int removed = 0;
        for(String id : preGame.keySet()){
            MarketMovers.Row row = meta.get(id);
            Double a = actual1.get(id);
            MarketMovers.Row prior = seasonPrior.get(id);
            if(row == null || a == null || week2.containsKey(id) || !leanPriorAll.containsKey(row.position())
                    || preGame.get(id) <= 0 || prior == null || prior.points() <= 0){
                continue;
            }
            removed++;
            double[] pair = {a - preGame.get(id), 0 - prior.points() / gamesInSeason};
            leanPriorAll.get(row.position()).add(pair);
            pooledPriorAll.add(pair);
        }
        out.append("x = week-1 actual minus his pre-game week-1 projection (" + preGameSource + ").\n");
        out.append("\ny = week-2 projection minus that SAME pre-game number. x holds it too, so its noise leans this slope\n");
        out.append("positive by construction - printed so the bias is visible, not to be read:\n");
        out.append(String.format("%-6s %6s %8s %8s %8s %7s   read%n", "pos", "men", "slope", "se", "corr", "honest"));
        for(Map.Entry<String, List<double[]>> e : leanOwn.entrySet()){
            out.append(leanRow(e.getKey(), fit(e.getValue()), honest.get(e.getKey())));
        }
        out.append(leanRow("all", fit(pooledOwn), pooledHonest(honest, leanOwn)));
        out.append(String.format("%ny = week-2 projection minus his season prior per game (season snapshot %s over %d games), which x%n",
                priorDay, gamesInSeason));
        out.append("does not contain - the slope to believe:\n");
        out.append(String.format("%-6s %6s %8s %8s %8s %7s   read%n", "pos", "men", "slope", "se", "corr", "honest"));
        for(Map.Entry<String, List<double[]>> e : leanPrior.entrySet()){
            out.append(leanRow(e.getKey(), fit(e.getValue()), honest.get(e.getKey())));
        }
        out.append(leanRow("all", fit(pooledPrior), pooledHonest(honest, leanPrior)));
        out.append(String.format("%nthe same, with the %d men who played and have NO week-2 row restored at zero (removed by injury or demotion;%n", removed));
        out.append("conditioning on a week-2 row drops the largest reactions there are, so this is the other bound):\n");
        out.append(String.format("%-6s %6s %8s %8s %8s %7s   read%n", "pos", "men", "slope", "se", "corr", "honest"));
        for(Map.Entry<String, List<double[]>> e : leanPriorAll.entrySet()){
            out.append(leanRow(e.getKey(), fit(e.getValue()), honest.get(e.getKey())));
        }
        out.append(leanRow("all", fit(pooledPriorAll), pooledHonest(honest, leanPriorAll)));
        out.append("'honest' is the share of a one-game surprise InSeasonLearning's rule keeps, 1/(kappa+1), refit here; the read compares\n");
        out.append("the slope's two-standard-error bar to zero and to it. A slope near one chases the week.\n");
        out.append(String.format("actuals: %d men with a scored line%s; a man who played without a scoring line is absent, not zero.%n",
                WeekReaction.men(actual1), LeagueWeek.finished(1) ? "" : " (week 1 not finished - a partial read; a game may be in progress)"));

        // ------------------------------------------------------------ 3. day to day, per week
        out.append("\n== 3. DAY TO DAY: each live week's projection, every cached read against the next ==\n");
        for(int w = 1; w <= lastWeek; w++){
            TreeMap<String, Path> days = liveDays(season, w);
            if(days.size() < 2){
                out.append(String.format("w%-2d  %s%n", w, days.isEmpty() ? "no live read cached"
                        : "one read cached (" + days.firstKey() + "); run again tomorrow for the before/after"));
                continue;
            }
            List<String> dates = new ArrayList<>(days.keySet());
            Map<String, Double> was = LeagueWeek.projectedFrom(Files.readString(days.get(dates.get(0)), StandardCharsets.UTF_8));
            for(int i = 1; i < dates.size(); i++){
                Map<String, Double> now = LeagueWeek.projectedFrom(Files.readString(days.get(dates.get(i)), StandardCharsets.UTF_8));
                List<String[]> moves = new ArrayList<>();
                int shared = 0;
                int moved = 0;
                for(String id : was.keySet()){
                    Double after = now.get(id);
                    if(after == null){
                        continue;
                    }
                    shared++;
                    double delta = after - was.get(id);
                    if(Math.abs(delta) >= 1.0){
                        moved++;
                        moves.add(new String[]{id, String.valueOf(delta)});
                    }
                }
                moves.sort(Comparator.comparingDouble((String[] m) -> -Math.abs(Double.parseDouble(m[1]))));
                StringBuilder top = new StringBuilder();
                for(String[] m : moves.subList(0, Math.min(4, moves.size()))){
                    MarketMovers.Row row = meta.get(m[0]);
                    top.append(String.format("  %s %+.1f", row == null ? m[0] : row.name(), Double.parseDouble(m[1])));
                }
                out.append(String.format("w%-2d  %s -> %s: %4d shared, %3d moved 1+ pts, rows +%d/-%d%s%n",
                        w, dates.get(i - 1), dates.get(i), shared, moved,
                        now.size() - shared, was.size() - shared, top));
                was = now;
            }
        }
        out.append("a live week is read through the day's cache, so each day on disk is one read; a finished week is frozen and has no days.\n");
        out.append("the pair that straddles kickoff is the post-game read against the pre-game one.\n");

        System.out.print(out);
        Path report = Path.of("data", "weekly-feed-audit-" + LocalDate.now() + ".txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }

    /** The read of a slope against zero and against the honest rule's share, on a two-standard-error bar. */
    static String read(Fit f, double honest){
        if(Double.isNaN(f.slope())){
            return "too few men";
        }
        double lo = f.slope() - 2 * f.se();
        double hi = f.slope() + 2 * f.se();
        if(hi < 0){
            return "leans AGAINST the week";
        }
        if(lo <= 0 && hi >= honest){
            return "cannot tell zero from the honest rule";
        }
        if(hi < honest){
            return lo > 0 ? "a lean, below the honest rule" : "no lean the data can see; below the honest rule";
        }
        if(lo > honest){
            return hi < 1 ? "above the honest rule" : "chases the week";
        }
        return "consistent with the honest rule";
    }

    private static String leanRow(String label, Fit f, double honest){
        return String.format("%-6s %6d %8.3f %8.3f %8.3f %7.3f   %s%n", label, f.n(), f.slope(), f.se(), f.r(),
                honest, read(f, honest));
    }

    /** The honest share of a pooled fit: each position's share weighted by its men. */
    static double pooledHonest(Map<String, Double> honest, Map<String, List<double[]>> byPosition){
        double sum = 0;
        int n = 0;
        for(Map.Entry<String, List<double[]>> e : byPosition.entrySet()){
            sum += honest.getOrDefault(e.getKey(), 0.0) * e.getValue().size();
            n += e.getValue().size();
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** The stat categories a feed's defence rows carry, minus draft ranks, games and the feed's own totals. */
    static TreeSet<String> defenceKeys(String body, boolean seasonShape){
        TreeSet<String> keys = new TreeSet<>();
        JsonElement parsed = JsonParser.parseString(body);
        if(seasonShape){
            for(JsonElement e : parsed.getAsJsonArray()){
                JsonObject record = e.getAsJsonObject();
                if(record.has("player_id") && LeagueActuals.isDefence(record.get("player_id").getAsString())
                        && record.has("stats") && record.get("stats").isJsonObject()){
                    keys.addAll(record.getAsJsonObject("stats").keySet());
                }
            }
        }
        else{
            for(Map.Entry<String, JsonElement> e : parsed.getAsJsonObject().entrySet()){
                if(LeagueActuals.isDefence(e.getKey()) && e.getValue().isJsonObject()){
                    keys.addAll(e.getValue().getAsJsonObject().keySet());
                }
            }
        }
        keys.removeIf(k -> k.startsWith("adp_") || k.startsWith("pos_adp") || k.startsWith("pts_") || k.equals("gp"));
        return keys;
    }
}
