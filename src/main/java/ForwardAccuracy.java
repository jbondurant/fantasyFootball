import PlayerImportAndSetup.Position;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * THIS SEASON'S REST-OF-SEASON FORECASTS, SCORED ON THE WEEKS SINCE.
 *
 * RosModel replays 2018-2025, but only for forecasts whose past values are on
 * disk as they stood at the time: each man's points so far, and Sleeper's and
 * ESPN's projection for his next game. Their week-by-week projections for later
 * weeks were read after those seasons, so they cannot be replayed, and CBS keeps
 * no past numbers. The trade explorer prices on exactly those forms, and on
 * 2026-11-07 CBS was the one source that read Stevenson for Ashton Jeanty as a
 * loss for Justin. The review of 2026-11-08 found the page called it untestable,
 * although the repo had saved CBS's rest-of-season pages, ESPN's projections and
 * Sleeper's week-by-week projections as they stood on 2026-09-25.
 *
 * So for every day all three were saved, and with three or more finished weeks
 * after it, this scores each source's forecast of a man's points per played game
 * over those weeks:
 *  - sleeper-remaining: the mean of Sleeper's projections for those weeks as read
 *    that day, over the weeks it projected him;
 *  - sleeper-next: Sleeper's projection that day for his first of those weeks;
 *  - espn and espn-next: the same from ESPN's projections that day, by the
 *    page's own rules (WeeklyLineupValue.nextGame): over the weeks ESPN had him
 *    above zero. A week ESPN left empty is ESPN saying he does not play it,
 *    which a rate per played game cannot score, and a man ESPN had nothing
 *    above zero for is not scored as ESPN's;
 *  - cbs: CBS's rest-of-season points over its games left, that day.
 * Each is set against Sleeper's next-game forecast on the same men, as RosModel's
 * replay is. The men are RosModel's: the preseason board who have played (its
 * blend's keys), with three or more played games in the weeks scored. It is one
 * season of a few weeks, so the standard error is over men, not seasons. It is a
 * first look, not a verdict. Who plays is not scored, here or in the replay: the
 * target is points per played game. Writes data/forward-accuracy.tsv, which the explorer
 * reads, and a report.
 *
 *     ./gradlew run -Pmain=ForwardAccuracy
 */
public class ForwardAccuracy {

    static final Path ACCURACY = Path.of("data", "forward-accuracy.tsv");
    static final List<String> SOURCES = List.of("sleeper-remaining", "espn", "espn-next", "cbs");

    /** One man: his forecasts by source, and what he went on to score a game. */
    record Case(String id, Position position, Map<String, Double> forecast, double target) {}

    /** Mean of the values, or null when there are none. */
    static Double mean(List<Double> values){
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).sum() / values.size();
    }

    /**
     * Every source scored against the baseline on the men each covers: n, both
     * RMSEs, their ratio, and the mean paired difference in squared error with
     * its standard error over men. Rows: source, pos, n, rmse, rmseBase, ratio, diff, se.
     */
    static List<Object[]> score(List<Case> cases, List<String> sources, String baseline){
        List<Object[]> out = new ArrayList<>();
        for(String source : sources){
            for(Position position : InSeasonLearning.POSITIONS){
                List<Double> diffs = new ArrayList<>();
                double se = 0, sb = 0;
                for(Case c : cases){
                    Double f = c.forecast().get(source), b = c.forecast().get(baseline);
                    if(c.position() != position || f == null || b == null){
                        continue;
                    }
                    double ef = (f - c.target()) * (f - c.target()), eb = (b - c.target()) * (b - c.target());
                    se += ef;
                    sb += eb;
                    diffs.add(ef - eb);
                }
                int n = diffs.size();
                if(n < 10){
                    continue;
                }
                double mean = diffs.stream().mapToDouble(Double::doubleValue).sum() / n;
                double var = diffs.stream().mapToDouble(d -> (d - mean) * (d - mean)).sum() / (n - 1);
                out.add(new Object[]{source, position, n, Math.sqrt(se / n), Math.sqrt(sb / n), Math.sqrt(se / sb), mean, Math.sqrt(var / n)});
            }
        }
        return out;
    }

    /** Past the 95% bar for this many men, either way; or not separated. */
    static String reading(double diff, double se, int n){
        double bar = RosBands.tCritical95(n - 1) * se;
        return Math.abs(diff) <= bar ? "= Sleeper's next game" : diff < 0 ? "better than Sleeper's next game" : "worse than Sleeper's next game";
    }

    public static void main(String[] args) throws IOException {
        String season = LeagueWeek.season();
        int done = LeagueWeek.finished(season, LeagueWeek.week()) ? LeagueWeek.week() : LeagueWeek.week() - 1;
        LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        Set<String> board = RosModel.blend().keySet();
        List<NflverseGames.Game> games = NflverseGames.games();

        // the days all three were saved
        TreeSet<String> days = new TreeSet<>();
        String[] files = new java.io.File(".").list();
        for(String name : files == null ? new String[0] : files){
            String prefix = "espnProjections" + season;
            if(name.startsWith(prefix) && name.length() == prefix.length() + 14){
                String day = name.substring(prefix.length(), prefix.length() + 10);
                boolean cbs = true;
                for(String p : CbsProjections.POSITIONS){
                    cbs &= Files.exists(Path.of("cbsRosProjections_" + p + "_" + season + day + ".txt"));
                }
                if(cbs){
                    days.add(day);
                }
            }
        }

        StringBuilder out = new StringBuilder();
        StringBuilder tsv = new StringBuilder();
        out.append(String.format("FORWARD ACCURACY  %s  (season %s; finished weeks through %d)%n", LocalDate.now(), season, done));
        out.append("Each source's forecast, as saved that day, of a man's points per played game over the finished weeks after it,\n");
        out.append("against Sleeper's next-game forecast on the same men. ratio = RMSE / Sleeper next game's; diff = squared error minus\n");
        out.append("Sleeper next game's, mean over men +- se. One season, a few weeks: a first look.\n");
        tsv.append("source\tvintage\tweeks\tpos\tmen\trmse\trmse_base\tratio\tdiff\tse\treading\n");
        String header = null;
        for(String day : days){
            // the first week none of whose games had started that day
            LocalDateTime start = LocalDate.parse(day).atStartOfDay();
            int first = 1;
            while(first <= done && !NflverseGames.kickedOff(games, season, first, start).isEmpty()){
                first++;
            }
            if(done - first + 1 < 3){
                out.append(String.format("%n%s: %d finished week%s after it - too few, skipped%n", day, Math.max(0, done - first + 1),
                        done - first + 1 == 1 ? "" : "s"));
                continue;
            }
            TreeMap<Integer, Map<String, Double>> sleeper = new TreeMap<>();
            boolean complete = true;
            for(int w = first; w <= done; w++){
                Path f = Path.of("sleeperLiveProjection" + season + "w" + w + day + ".txt");
                if(!Files.exists(f)){
                    complete = false;
                    break;
                }
                sleeper.put(w, LeagueWeek.projectedFrom(Files.readString(f, StandardCharsets.UTF_8)));
            }
            if(!complete){
                out.append(String.format("%n%s: Sleeper's week projections were not all saved that day - skipped%n", day));
                continue;
            }
            Map<Integer, Map<String, Double>> espn = EspnWeekly.weeklyFrom(
                    Files.readString(Path.of("espnProjections" + season + day + ".txt"), StandardCharsets.UTF_8), season, scoring,
                    (name, position) -> {
                        Player matched = Player.getPlayerFromNameAndPos(name, position);
                        return matched == null ? null : matched.sleeperIDString;
                    }).byWeek();
            Map<String, String> pages = new LinkedHashMap<>();
            for(String p : CbsProjections.POSITIONS){
                pages.put(p, Files.readString(Path.of("cbsRosProjections_" + p + "_" + season + day + ".txt"), StandardCharsets.UTF_8));
            }
            Map<String, double[]> cbs = CbsProjections.linesFrom(pages, "restofseason");
            List<Map<String, Double>> actual = new ArrayList<>();
            for(int w = first; w <= done; w++){
                actual.add(LeagueWeek.actual(season, w));
            }

            Map<String, Double> slpNext = WeeklyLineupValue.nextGame(sleeper, null);
            Map<String, Double> espNext = WeeklyLineupValue.nextGame(sleeper, espn);
            List<Case> cases = new ArrayList<>();
            for(String id : board){
                Player p = Player.getPlayerFromSIDV2(id);
                List<Double> played = new ArrayList<>();
                actual.forEach(week -> { if(week.containsKey(id)) played.add(week.get(id)); });
                if(p == null || played.size() < 3){
                    continue;
                }
                List<Double> slp = new ArrayList<>(), esp = new ArrayList<>();
                for(int w = first; w <= done; w++){
                    Double s = sleeper.get(w).get(id);
                    if(s == null){
                        continue;
                    }
                    slp.add(s);
                    double e = espn.getOrDefault(w, Map.of()).getOrDefault(id, 0.0);
                    if(e > 0){
                        esp.add(e);
                    }
                }
                if(!slpNext.containsKey(id)){
                    continue;
                }
                Map<String, Double> forecast = new LinkedHashMap<>();
                forecast.put("sleeper-next", slpNext.get(id));
                forecast.put("sleeper-remaining", mean(slp));
                forecast.put("espn", mean(esp));
                forecast.put("espn-next", espNext.get(id));
                double[] c = cbs.get(id);
                if(c != null && c[1] > 0){
                    forecast.put("cbs", c[0] / c[1]);
                }
                cases.add(new Case(id, p.position, forecast, mean(played)));
            }
            String weeks = first + "-" + done;
            header = String.format("# ForwardAccuracy %s: saved %s, weeks %s, %d men", LocalDate.now(), day, weeks, cases.size());
            out.append(String.format("%n== saved %s, scored on weeks %s: %d men ==%n%-18s %-4s %5s %8s %8s %6s   %-22s %s%n", day, weeks, cases.size(),
                    "source", "pos", "men", "RMSE", "baseline", "ratio", "diff (sq err)", "reading"));
            for(Object[] r : score(cases, SOURCES, "sleeper-next")){
                String reading = reading((double) r[6], (double) r[7], (int) r[2]);
                out.append(String.format("%-18s %-4s %5d %8.2f %8.2f %6.2f   %+7.2f +- %-10.2f %s%n", r[0], r[1], (int) r[2], (double) r[3],
                        (double) r[4], (double) r[5], (double) r[6], (double) r[7], reading));
                tsv.append(String.format("%s\t%s\t%s\t%s\t%d\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%s%n", r[0], day, weeks, r[1], (int) r[2],
                        (double) r[3], (double) r[4], (double) r[5], (double) r[6], (double) r[7], reading));
            }
        }
        // the latest scored day only: the explorer shows one check per source
        StringBuilder latest = new StringBuilder(tsv.substring(0, tsv.indexOf("\n") + 1));
        String lastDay = null;
        for(String line : tsv.toString().split("\n")){
            String[] f = line.split("\t");
            if(f.length > 1 && !f[0].equals("source")){
                lastDay = f[1];
            }
        }
        for(String line : tsv.toString().split("\n")){
            String[] f = line.split("\t");
            if(lastDay != null && f.length > 1 && f[1].equals(lastDay)){
                latest.append(line).append('\n');
            }
        }
        Files.writeString(ACCURACY, (header == null ? "# ForwardAccuracy: no day to score yet\n" : header + "\n") + latest, StandardCharsets.UTF_8);
        out.append(String.format("%nwritten to %s, which the trade explorer reads%n", ACCURACY));
        System.out.print(out);
        Path report = Path.of("data", "forward-accuracy-" + LocalDate.now() + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + report);
    }
}
