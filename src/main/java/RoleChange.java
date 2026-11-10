import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;

/**
 * WHEN A LEAD BACK'S CARRIES COLLAPSE, WHICH FORECAST OF THE REST OF HIS SEASON IS RIGHT?
 *
 * Justin, 2026-11-07, on Rhamondre Stevenson for Ashton Jeanty: "the narratives
 * surrounding jeanty reduced workload concern me", and then: "find tools that
 * would see the jeanty stevenson trade as neutral or bad for me while also
 * having decent predictive accuracy compared to sleeper". The explorer's
 * Check-a-trade tab answered with each source's accuracy over every player
 * (RosModel, ForwardAccuracy). Most players' roles are steady, so that average
 * says little about the one case he asked about, a back whose role has just
 * changed. There the sources split: Sleeper's next-game number had cut Jeanty
 * and the model, which leans on the season so far, had not. The average
 * favours the model.
 *
 * So this takes RosModel's cases for the running backs it happened to, 2018 on.
 *
 *   THE EVENT, fixed on 2026-11-09 before any outcome was read. A back on that
 *   season's draft board who averaged LEAD_CARRIES or more over at least
 *   MIN_PRIOR_GAMES games, and then played a game with COLLAPSE of that
 *   average or less: the first such game of his season, weeks FIRST_WEEK to
 *   LAST_WEEK. (Jeanty, week 8 of 2026: 2 carries against 15.1.) Beside it,
 *   the slower version, his last two games together at SUSTAINED of his
 *   earlier average or less; and the comparison, the same kind of back in a
 *   week that was not a collapse.
 *
 * It reports what the carries did next (how often the role came back), and
 * each forecast of his points per game from two weeks on - Sleeper's next-game
 * projection, the model's blend, its season-so-far half (the posterior), pace,
 * the last three games, ESPN's next game - against what he scored: its bias,
 * its RMSE, and its squared error against Sleeper's next game on the same
 * backs. Every figure is over backs, each counting once, with a standard error
 * that treats a season's backs as one cluster (pooled). A collapse that has no case (Sleeper did not
 * project him the next week, or he played fewer than three more games) is
 * counted apart: those are mostly injuries, and no forecast is scored on them.
 *
 * Played is a stat row with gp of one or more; carries are rush_att.
 *
 *     ./gradlew run -Pmain=RoleChange
 *
 * Report to data/role-change-&lt;date&gt;.txt.
 */
public class RoleChange {

    static final int MIN_PRIOR_GAMES = 3, FIRST_WEEK = 4, LAST_WEEK = 13;
    static final double LEAD_CARRIES = 12, COLLAPSE = 0.4, SUSTAINED = 0.5, BACK = 0.75;

    /** RosModel's estimators, and their names in the report. */
    static final Map<String, String> ESTIMATORS = new LinkedHashMap<>();
    static {
        ESTIMATORS.put("sleeper", "Sleeper next game");
        ESTIMATORS.put("blend", "model");
        ESTIMATORS.put("posterior", "season so far");
        ESTIMATORS.put("pace", "pace");
        ESTIMATORS.put("last3", "last 3 games");
        ESTIMATORS.put("espn", "ESPN next game");
    }

    /** carries[w - 1] is week w; NaN is a week he did not play. */
    static boolean played(double[] carries, int w){
        return w >= 1 && w <= carries.length && !Double.isNaN(carries[w - 1]);
    }

    /** His mean carries over the games he played before week {@code k}; null with fewer than MIN_PRIOR_GAMES. */
    static Double before(double[] carries, int k){
        double total = 0;
        int games = 0;
        for(int w = 1; w < k; w++){
            if(played(carries, w)){
                total += carries[w - 1];
                games++;
            }
        }
        return games < MIN_PRIOR_GAMES ? null : total / games;
    }

    /** A lead back playing week k: LEAD_CARRIES or more a game over the games before it. */
    static boolean lead(double[] carries, int k){
        Double b = before(carries, k);
        return played(carries, k) && b != null && b >= LEAD_CARRIES;
    }

    /** The one-game event: a lead back with COLLAPSE of his average or less in week k. */
    static boolean collapsed(double[] carries, int k){
        return lead(carries, k) && carries[k - 1] <= COLLAPSE * before(carries, k);
    }

    /** The week of his game before week k, or 0. */
    static int previous(double[] carries, int k){
        for(int w = k - 1; w >= 1; w--){
            if(played(carries, w)){
                return w;
            }
        }
        return 0;
    }

    /** The two-game event: week k and his game before it together at SUSTAINED or less of his lead-back average before the two. */
    static boolean sustained(double[] carries, int k){
        int j = previous(carries, k);
        if(!played(carries, k) || j == 0){
            return false;
        }
        Double b = before(carries, j);
        return b != null && b >= LEAD_CARRIES && (carries[k - 1] + carries[j - 1]) / 2 <= SUSTAINED * b;
    }

    /** The first week from FIRST_WEEK to {@code last} the event holds, or 0. */
    static int first(IntPredicate event, int last){
        for(int k = FIRST_WEEK; k <= last; k++){
            if(event.test(k)){
                return k;
            }
        }
        return 0;
    }

    /** His mean carries over the games he played after week k; NaN with none. */
    static double after(double[] carries, int k){
        double total = 0;
        int games = 0;
        for(int w = k + 1; w <= carries.length; w++){
            if(played(carries, w)){
                total += carries[w - 1];
                games++;
            }
        }
        return games == 0 ? Double.NaN : total / games;
    }

    /** Each man's carries by week for a season's first {@code weeks} weeks: NaN where no stat row has him playing. */
    static Map<String, double[]> carries(String season, Collection<String> ids, int weeks){
        Map<String, double[]> out = new HashMap<>();
        for(String id : ids){
            double[] c = new double[weeks];
            Arrays.fill(c, Double.NaN);
            out.put(id, c);
        }
        for(int w = 1; w <= weeks; w++){
            JsonObject week = EraActuals.week(season, w);
            for(String id : ids){
                JsonElement row = week.get(id);
                if(row != null && row.isJsonObject() && FaabDemand.stat(row.getAsJsonObject(), "gp") >= 1){
                    out.get(id)[w - 1] = FaabDemand.stat(row.getAsJsonObject(), "rush_att");
                }
            }
        }
        return out;
    }

    /**
     * One back at one week: his carries a game before the event, in it and
     * after it, his points a game before it, and RosModel's case for that week
     * (null where it has none).
     */
    record Row(String season, String id, int week, double before, double inGame, double after, double pointsBefore, RosModel.Case scored) {}

    /** {@code through} is the last week before the event: his points a game are taken to there. */
    static Row row(InSeasonLearning.Man man, double[] carries, int k, int through, double before, double inGame, Map<String, RosModel.Case> cases){
        int games = man.gamesThrough(through);
        return new Row(man.season(), man.id(), k, before, inGame, after(carries, k), games == 0 ? Double.NaN : man.pointsThrough(through) / games,
                cases.get(man.season() + "|" + man.id() + "|" + k));
    }

    private static double mean(List<Double> values){
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    /**
     * The mean over every value, each counting once, with a standard error that
     * lets one season's values move together (seasons as clusters): {mean, se,
     * seasons}. The first report averaged season means, which gave 2018's one
     * scored back an eighth of the weight of 36 and printed a bias of -1.08
     * where the backs' own mean was -0.28 (review, 2026-11-10).
     */
    static double[] pooled(Map<String, List<Double>> bySeason){
        double sum = 0;
        int n = 0;
        for(List<Double> values : bySeason.values()){
            for(double v : values){
                sum += v;
                n++;
            }
        }
        if(n == 0){
            return new double[]{Double.NaN, Double.NaN, 0};
        }
        double mean = sum / n, ss = 0;
        int seasons = 0;
        for(List<Double> values : bySeason.values()){
            if(values.isEmpty()){
                continue;
            }
            seasons++;
            double within = 0;
            for(double v : values){
                within += v - mean;
            }
            ss += within * within;
        }
        return new double[]{mean, seasons < 2 ? Double.NaN : Math.sqrt(seasons / (seasons - 1.0) * ss) / n, seasons};
    }

    static double median(List<Double> values){
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n == 0 ? Double.NaN : n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    /** One group's report: what the carries and points did, and every estimator against the outcome and against Sleeper's next game. */
    static String report(String title, List<Row> rows){
        StringBuilder out = new StringBuilder();
        List<Row> scored = rows.stream().filter(r -> r.scored() != null).toList();
        List<Row> again = rows.stream().filter(r -> !Double.isNaN(r.after())).toList();
        out.append(String.format("%n== %s ==%n", title));
        out.append(String.format("%d back-weeks in %d seasons; %d scored (Sleeper projected him the next week and he played 3+ games from the week after)%n",
                rows.size(), rows.stream().map(Row::season).distinct().count(), scored.size()));
        if(rows.isEmpty()){
            return out.toString();
        }
        out.append(String.format("carries a game: %.1f before, %.1f in the game%s; %.1f after for the %d who played again, %d of them (%.0f%%) back to %.0f%%+ of before%n",
                mean(rows.stream().map(Row::before).toList()), mean(rows.stream().map(Row::inGame).toList()), title.startsWith("two") ? "s" : "",
                mean(again.stream().map(Row::after).toList()), again.size(),
                again.stream().filter(r -> r.after() >= BACK * r.before()).count(),
                again.isEmpty() ? 0 : 100.0 * again.stream().filter(r -> r.after() >= BACK * r.before()).count() / again.size(), 100 * BACK));
        if(scored.isEmpty()){
            return out.toString();
        }
        List<Row> scoredAgain = scored.stream().filter(r -> !Double.isNaN(r.after())).toList();
        out.append(String.format("the scored backs' carries: %.1f before, %.1f after, %d of %d (%.0f%%) back to %.0f%%+%n",
                mean(scored.stream().map(Row::before).toList()), mean(scoredAgain.stream().map(Row::after).toList()),
                scoredAgain.stream().filter(r -> r.after() >= BACK * r.before()).count(), scoredAgain.size(),
                scoredAgain.isEmpty() ? 0 : 100.0 * scoredAgain.stream().filter(r -> r.after() >= BACK * r.before()).count() / scoredAgain.size(), 100 * BACK));
        double pointsBefore = mean(scored.stream().map(Row::pointsBefore).filter(v -> !Double.isNaN(v)).toList());
        double pointsAfter = mean(scored.stream().map(r -> r.scored().target()).toList());
        out.append(String.format("points a game, the scored backs: %.1f before the game, %.1f from two weeks on (%.0f%%)%n", pointsBefore, pointsAfter,
                100 * pointsAfter / pointsBefore));
        out.append(String.format("%-18s %5s %-16s %7s %7s %6s   %-24s %s%n", "forecast", "backs", "bias", "median", "RMSE", "ratio",
                "sq err - Sleeper next's", "reading"));
        for(Map.Entry<String, String> estimator : ESTIMATORS.entrySet()){
            Map<String, List<Double>> bias = new TreeMap<>(), sq = new TreeMap<>(), sqBase = new TreeMap<>(), diff = new TreeMap<>();
            List<Double> errors = new ArrayList<>();
            for(Row r : scored){
                Double e = RosModel.estimate(r.scored(), estimator.getKey());
                if(e == null){
                    continue;
                }
                double target = r.scored().target();
                double ee = (e - target) * (e - target), eb = (r.scored().sleeper() - target) * (r.scored().sleeper() - target);
                errors.add(e - target);
                bias.computeIfAbsent(r.season(), k -> new ArrayList<>()).add(e - target);
                sq.computeIfAbsent(r.season(), k -> new ArrayList<>()).add(ee);
                sqBase.computeIfAbsent(r.season(), k -> new ArrayList<>()).add(eb);
                diff.computeIfAbsent(r.season(), k -> new ArrayList<>()).add(ee - eb);
            }
            if(errors.size() < 10){
                continue;
            }
            double[] b = pooled(bias), d = pooled(diff);
            double rmse = Math.sqrt(pooled(sq)[0]), base = Math.sqrt(pooled(sqBase)[0]);
            String reading = estimator.getKey().equals("sleeper") ? "the baseline"
                    : !RosBands.separated(d) ? "= Sleeper next game" : d[0] < 0 ? "better than Sleeper next game" : "worse than Sleeper next game";
            out.append(String.format("%-18s %5d %+6.2f +- %-6.2f %+7.2f %7.2f %6.2f   %+7.2f +- %-12.2f %s%n", estimator.getValue(), errors.size(), b[0], b[1],
                    median(errors), rmse, rmse / base, d[0], d[1], reading));
        }
        return out.toString();
    }

    private static String name(String id){
        Player p = Player.getPlayerFromSIDV2(id);
        return p == null ? id : p.firstName + " " + p.lastName;
    }

    /** Every event, named, so the group can be read by eye. */
    static String listed(String title, List<Row> rows){
        StringBuilder out = new StringBuilder(String.format("%n-- %s, each one --%n%-6s %-4s %-24s %14s %8s %8s %9s %8s %8s%n", title, "season", "week", "back",
                "carries before", "in game", "after", "pts before", "Sleeper", "pts after"));
        rows.stream().sorted(Comparator.comparing(Row::season).thenComparing(Row::week)).forEach(r -> out.append(String.format(
                "%-6s %-4d %-24s %14.1f %8.1f %8s %9.1f %8s %8s%n", r.season(), r.week(), name(r.id()), r.before(), r.inGame(),
                Double.isNaN(r.after()) ? "-" : String.format("%.1f", r.after()), r.pointsBefore(),
                r.scored() == null ? "-" : String.format("%.1f", r.scored().sleeper()), r.scored() == null ? "-" : String.format("%.1f", r.scored().target()))));
        return out.toString();
    }

    public static void main(String[] args) throws IOException {
        Map<String, EraBoards.Board> boards = EraBoards.usable("ppr", EraIngest.MIN_RATE, EraIngest.minDepth());
        Map<String, List<InSeasonLearning.Man>> harvest = InSeasonLearning.harvest(boards);
        List<String> seasons = new ArrayList<>(harvest.keySet());
        Map<String, Map<Position, double[]>> priors = new TreeMap<>();
        Map<String, Map<Position, InSeasonLearning.Kappa>> kappas = new TreeMap<>();
        for(String season : seasons){
            priors.put(season, InSeasonLearning.priorTable(harvest, season, null, RosModel.DEPTH));
            kappas.put(season, InSeasonLearning.fitKappa(harvest, season, RosModel.DEPTH));
        }
        Map<String, EspnWeekly.Season> espn = new TreeMap<>();
        for(String season : seasons){
            try {
                EspnWeekly.Season s = EspnWeekly.season(season);
                if(!s.byWeek().isEmpty()){
                    espn.put(season, s);
                }
            }
            catch(RuntimeException unavailable){
                // ESPN does not serve this season: its forecast is simply not scored there
            }
        }
        List<String> withWeeks = new ArrayList<>();
        List<RosModel.Case> cases = RosModel.cases(harvest, seasons, priors, kappas, withWeeks, espn,
                IntStream.rangeClosed(FIRST_WEEK, LAST_WEEK).toArray());
        RosModel.fitBlend(cases, withWeeks);
        Map<String, RosModel.Case> at = new HashMap<>();
        for(RosModel.Case c : cases){
            if(c.position() == Position.RB){
                at.put(c.season() + "|" + c.id() + "|" + c.seen(), c);
            }
        }

        List<Row> one = new ArrayList<>(), two = new ArrayList<>(), steady = new ArrayList<>();
        for(String season : withWeeks){
            List<InSeasonLearning.Man> backs = harvest.get(season).stream().filter(m -> m.position() == Position.RB).toList();
            int weeks = EraActuals.weeks(season);
            Map<String, double[]> byMan = carries(season, backs.stream().map(InSeasonLearning.Man::id).toList(), weeks);
            for(InSeasonLearning.Man man : backs){
                double[] c = byMan.get(man.id());
                int k1 = first(k -> collapsed(c, k), LAST_WEEK), k2 = first(k -> sustained(c, k), LAST_WEEK);
                if(k1 > 0){
                    one.add(row(man, c, k1, k1 - 1, before(c, k1), c[k1 - 1], at));
                }
                if(k2 > 0){
                    int j = previous(c, k2);
                    two.add(row(man, c, k2, j - 1, before(c, j), (c[k2 - 1] + c[j - 1]) / 2, at));
                }
                for(int k = FIRST_WEEK; k <= LAST_WEEK; k++){
                    if(lead(c, k) && !collapsed(c, k)){
                        steady.add(row(man, c, k, k - 1, before(c, k), c[k - 1], at));
                    }
                }
            }
        }

        StringBuilder out = new StringBuilder();
        out.append(String.format("ROLE CHANGE  %s  (running backs on the draft board, %s-%s)%n", LocalDate.now(), withWeeks.get(0), withWeeks.get(withWeeks.size() - 1)));
        out.append(String.format("A lead back: %.0f+ carries a game over %d+ games. The event, fixed before any outcome was read: a game at %.0f%% of that%n",
                LEAD_CARRIES, MIN_PRIOR_GAMES, 100 * COLLAPSE));
        out.append(String.format("average or less, the first of his season, weeks %d-%d. Forecasts are of his points a game from two weeks on.%n", FIRST_WEEK, LAST_WEEK));
        out.append("bias = forecast minus what he scored, the mean over backs +- its standard error and the median; ratio = RMSE / Sleeper\n");
        out.append("next game's. Every figure counts each back once, and a standard error treats a season's backs as one cluster.\n");
        out.append(report("one game at 40% of his carries or less", one));
        out.append(report("two games running at half his earlier carries or less", two));
        out.append(report("the comparison: every lead back's weeks that were not a collapse", steady));
        out.append(listed("one game at 40% or less", one));
        out.append(listed("two games running at half or less", two));

        // this season's, from the finished weeks
        String now = LeagueWeek.season();
        int done = LeagueWeek.finished(now, LeagueWeek.week()) ? LeagueWeek.week() : LeagueWeek.week() - 1;
        List<String> ids = new ArrayList<>();
        for(int w = 1; w <= done; w++){
            for(Map.Entry<String, JsonElement> e : EraActuals.week(now, w).entrySet()){
                Player p = e.getKey().matches("\\d+") ? Player.getPlayerFromSIDV2(e.getKey()) : null;
                if(p != null && p.position == Position.RB && !ids.contains(e.getKey())){
                    ids.add(e.getKey());
                }
            }
        }
        Map<String, double[]> thisSeason = carries(now, ids, done);
        Map<String, String> ownerOf = LeagueOwners.today(AAAConfiguration.getInstance());
        out.append(String.format("%n== THIS SEASON, weeks 1-%d (and who holds him here) ==%n", done));
        for(String id : ids){
            double[] c = thisSeason.get(id);
            int k1 = first(k -> collapsed(c, k), Math.min(LAST_WEEK, done)), k2 = first(k -> sustained(c, k), Math.min(LAST_WEEK, done));
            if(k1 > 0){
                out.append(String.format("%-24s %-12s one game: week %d, %.0f carries against %.1f a game before%n", name(id), ownerOf.getOrDefault(id, "free"),
                        k1, c[k1 - 1], before(c, k1)));
            }
            if(k2 > 0){
                int j = previous(c, k2);
                out.append(String.format("%-24s %-12s two games running: weeks %d and %d, %.1f carries a game against %.1f before%n", name(id),
                        ownerOf.getOrDefault(id, "free"), j, k2, (c[k2 - 1] + c[j - 1]) / 2, before(c, j)));
            }
        }
        System.out.print(out);
        Path report = Path.of("data", "role-change-" + LocalDate.now() + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }
}
