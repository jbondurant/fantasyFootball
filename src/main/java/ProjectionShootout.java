import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * WHICH PROJECTIONS MATCHED THE WEEK, AND WOULD ANY HAVE RANKED THE ROSTERS
 * BETTER THAN THE ONE THE MODEL USES?
 *
 * Justin, after week 1: the manager the model ranked last outscored the
 * league. Variance covers that, and he said so; the question worth a tool is
 * whether another source's projections would have called it. The repo has
 * archived four automatic feeds daily since August (sleeper = Rotowire stat
 * lines, espn, cbs, borischen = FantasyPros-consensus tiers on the points
 * curve) for exactly this shootout, and it now has the week's actuals.
 *
 * Two levels, both against the same week:
 *
 *  PLAYERS. Every man who played, projected per game by each source (season
 *     number over the seventeen games the objective counts) against what he
 *     scored: Pearson r, Spearman rho, mean absolute error, and - the only
 *     honest comparison between sources - the PAIRED difference in absolute
 *     error against sleeper on the same men, with its standard error. Sleeper's
 *     own WEEK projection, read before kickoff, is the fifth column: it knows
 *     the matchup and the injury report, the season numbers do not.
 *  ROSTERS. Each manager's best legal lineup under each source (the ranking
 *     the model makes) and his actual started ten under each source, against
 *     the points the league recorded for him. Twelve managers, so a rank
 *     correlation's standard error is about 0.3 and one week separates nothing;
 *     the rows are printed so that the season can accumulate them.
 *
 * The source snapshot is the last archived day before Sleeper's season start,
 * the same day for every source, so no feed gets credit for knowing more of
 * the calendar. Until the archive resumes per source (Batch 2, B2-1) that one
 * preseason snapshot stands for every week. The week feed is the newest live
 * read dated before the week's first game (season start plus seven days a
 * week), never a read taken after the games (TRAPS #137).
 *
 *   ./gradlew run -Pmain=ProjectionShootout [-Pweek=n]
 */
public class ProjectionShootout {

    /** Average ranks, ties sharing their mean rank; 1 is the smallest value. */
    static double[] ranks(double[] values){
        int n = values.length;
        Integer[] order = new Integer[n];
        for(int i = 0; i < n; i++){
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> values[i]));
        double[] rank = new double[n];
        int i = 0;
        while(i < n){
            int j = i;
            while(j + 1 < n && values[order[j + 1]] == values[order[i]]){
                j++;
            }
            double shared = (i + j) / 2.0 + 1;
            for(int k = i; k <= j; k++){
                rank[order[k]] = shared;
            }
            i = j + 1;
        }
        return rank;
    }

    /** Spearman's rho: Pearson on the ranks. NaN under three pairs. */
    static double spearman(List<double[]> xy){
        double[] x = new double[xy.size()];
        double[] y = new double[xy.size()];
        for(int i = 0; i < xy.size(); i++){
            x[i] = xy.get(i)[0];
            y[i] = xy.get(i)[1];
        }
        double[] rx = ranks(x);
        double[] ry = ranks(y);
        List<double[]> pairs = new ArrayList<>();
        for(int i = 0; i < x.length; i++){
            pairs.add(new double[]{rx[i], ry[i]});
        }
        return WeeklyFeedAudit.fit(pairs).r();
    }

    /** A paired mean with its standard error. */
    record Paired(double mean, double se, int n){}

    static Paired paired(List<Double> differences){
        int n = differences.size();
        if(n < 2){
            return new Paired(Double.NaN, Double.NaN, n);
        }
        double mean = 0;
        for(double d : differences){
            mean += d;
        }
        mean /= n;
        double ss = 0;
        for(double d : differences){
            ss += (d - mean) * (d - mean);
        }
        return new Paired(mean, Math.sqrt(ss / (n - 1) / n), n);
    }

    /** The day a week's games begin: Sleeper's season start, plus seven days for each week after the first. */
    static LocalDate firstGame(String seasonStart, int week){
        return LocalDate.parse(seasonStart).plusDays(7L * (week - 1));
    }

    /** The archived dates in the projection archive, oldest first. */
    static TreeSet<String> archiveDays(List<String> lines){
        TreeSet<String> days = new TreeSet<>();
        for(String line : lines){
            int comma = line.indexOf(',');
            if(comma == 10 && line.substring(0, 10).matches("\\d{4}-\\d{2}-\\d{2}")){
                days.add(line.substring(0, 10));
            }
        }
        return days;
    }

    /** Which men count: the four positions every source projects, on a per-game basis. */
    static final List<String> POSITIONS = List.of("QB", "RB", "WR", "TE");

    public static void main(String[] args) throws IOException {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String season = LeagueWeek.season();
        int games = WeeklyStarterValue.WEEKS;
        LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        String seasonStart = LeagueWeek.state().get("season_start_date").getAsString();
        String me = configuration.getUserIDToDisplayName()
                .getOrDefault(configuration.getMyID(), configuration.getMyID());

        int week = LeagueWeek.week();
        if(Integer.getInteger("week") == null && week > 1
                && LeagueWeek.actualSoFar(season, week).isEmpty()){
            week--;
        }
        boolean finished = LeagueWeek.finished(week);

        // The sources, as they stood on the last archived day before the season.
        List<String> lines = Files.readAllLines(AdpSnapshot.PROJECTIONS_CSV, StandardCharsets.UTF_8);
        String archiveDay = WeekReaction.priorDay(archiveDays(lines), seasonStart);
        if(archiveDay == null){
            throw new IllegalStateException("no projection archive day before the season start "
                    + seasonStart + " in " + AdpSnapshot.PROJECTIONS_CSV);
        }
        LinkedHashMap<String, Map<String, Double>> sources = new LinkedHashMap<>();
        for(String feed : ProjectionSources.automaticSources()){
            Map<String, Double> points = ProjectionSources.snapshot(lines, archiveDay, feed);
            if(!points.isEmpty()){
                sources.put(feed, points);
            }
        }
        // The week feed: the newest live read dated before the week's first game.
        LocalDate cutoff = firstGame(seasonStart, week);
        TreeMap<String, Path> reads = WeeklyFeedAudit.liveDays(season, week);
        String weekReadDay = WeekReaction.priorDay(reads.keySet(), cutoff.toString());
        Map<String, Double> weekFeed = weekReadDay == null ? Map.of()
                : LeagueWeek.projectedFrom(Files.readString(reads.get(weekReadDay), StandardCharsets.UTF_8));

        Map<String, Double> actual = LeagueWeek.actualSoFar(season, week);
        java.util.Set<String> inProgress = finished ? java.util.Set.of()
                : inProgressTeams(com.google.gson.JsonParser.parseString(LeagueWeek.actualsBody(season, week)).getAsJsonObject());
        Map<Integer, String> managerOf = SeasonLedger.managerByRoster(configuration);
        List<LineupPromotion.RosterWeek> rosters = LineupPromotion.weekFrom(
                LeagueWeek.matchups(configuration.getLeagueID(), week), week);
        TreeMap<String, Path> seasonDays = MarketMovers.cachedDays(season);
        Map<String, MarketMovers.Row> meta = MarketMovers.read(seasonDays.get(seasonDays.lastKey()), scoring);

        StringBuilder out = new StringBuilder();
        out.append(String.format("PROJECTION SHOOTOUT  season %s week %d  %s%n", season, week,
                finished ? "(the week is finished)" : "PARTIAL: week " + week + " is not over; " + WeekReaction.men(actual)
                        + " men carry a line and every roster's points are so far, not final"
                        + (inProgress.isEmpty() ? "" : "; a game was IN PROGRESS at the read for " + inProgress
                                + " - their men carry part of a game against a full-game projection")));
        out.append(String.format("sources: %s, each as archived on %s (the last day before Sleeper's season start %s); "
                + "week feed: %s%n", String.join(", ", sources.keySet()), archiveDay, seasonStart,
                weekReadDay == null ? "no pre-game read on disk" : "sleeper's week-" + week + " projection read " + weekReadDay
                        + " (before the first game " + cutoff + ")"));
        out.append("per game = the season number over " + games + " games. " + DataStamp.line() + "\n");
        out.append("the week column covers more men than the archived pool, so its r, rho and MAE are over a different population; only the paired columns compare like with like.\n");

        // ------------------------------------------------------------ players
        out.append("\n== PLAYERS: projected per game against what he scored, men who played ==\n");
        Map<String, Double> sleeper = sources.get("sleeper");
        LinkedHashMap<String, Map<String, Double>> columns = new LinkedHashMap<>(sources);
        if(!weekFeed.isEmpty()){
            columns.put("week", weekFeed);
        }
        // Every source but sleeper at every position is one paired test; with
        // that many, a lone two-standard-error flag is what chance gives about
        // half the time, so the bar is three and the count is printed.
        int tests = POSITIONS.size() * (columns.size() - 1);
        int flagged = 0;
        out.append(String.format("%-4s %-10s %5s %7s %7s %7s %6s | %-24s | %s%n", "pos", "source", "men", "r", "rho", "MAE", "bias",
                "vs sleeper, same men", "vs sleeper, each source's level removed"));
        for(String position : POSITIONS){
            for(Map.Entry<String, Map<String, Double>> source : columns.entrySet()){
                boolean isWeek = source.getKey().equals("week");
                List<double[]> pairs = new ArrayList<>();
                List<double[]> common = new ArrayList<>();      // {source per game, sleeper per game, actual}
                int fillIns = 0;
                for(Map.Entry<String, Double> a : actual.entrySet()){
                    String id = a.getKey();
                    MarketMovers.Row row = meta.get(id);
                    Double p = source.getValue().get(id);
                    if(row == null || !position.equals(row.position()) || p == null){
                        continue;
                    }
                    // The archive backfills a source with Sleeper's number for any man
                    // the source did not cover (ProjectionSources.resolve merges over
                    // Sleeper's map). Such a row is Sleeper's opinion twice, and paired
                    // against itself it is a tie that never happened. Dropped and counted.
                    if(!isWeek && !source.getKey().equals("sleeper") && sleeper != null
                            && p.equals(sleeper.get(id))){
                        fillIns++;
                        continue;
                    }
                    double perGame = isWeek ? p : p / games;
                    pairs.add(new double[]{perGame, a.getValue()});
                    Double s = sleeper == null ? null : sleeper.get(id);
                    if(s != null){
                        common.add(new double[]{perGame, s / games, a.getValue()});
                    }
                }
                if(pairs.size() < 3){
                    continue;
                }
                double mae = 0;
                double bias = 0;
                for(double[] p : pairs){
                    mae += Math.abs(p[0] - p[1]);
                    bias += p[0] - p[1];
                }
                mae /= pairs.size();
                bias /= pairs.size();
                // the paired difference, raw and with each source's own level over the common men removed
                double biasSource = 0;
                double biasSleeper = 0;
                for(double[] c : common){
                    biasSource += c[0] - c[2];
                    biasSleeper += c[1] - c[2];
                }
                biasSource /= Math.max(1, common.size());
                biasSleeper /= Math.max(1, common.size());
                List<Double> raw = new ArrayList<>();
                List<Double> levelled = new ArrayList<>();
                for(double[] c : common){
                    raw.add(Math.abs(c[0] - c[2]) - Math.abs(c[1] - c[2]));
                    levelled.add(Math.abs(c[0] - biasSource - c[2]) - Math.abs(c[1] - biasSleeper - c[2]));
                }
                Paired d = paired(raw);
                Paired dl = paired(levelled);
                boolean flag = !source.getKey().equals("sleeper") && Math.abs(dl.mean()) > 3 * dl.se();
                if(flag){
                    flagged++;
                }
                out.append(String.format("%-4s %-10s %5d %7.3f %7.3f %7.2f %+6.2f | %-24s | %s%s%n", position, source.getKey(),
                        pairs.size(), WeeklyFeedAudit.fit(pairs).r(), spearman(pairs), mae, bias,
                        source.getKey().equals("sleeper") ? "-"
                                : String.format("%+.2f +- %.2f (n %d)", d.mean(), d.se(), d.n()),
                        source.getKey().equals("sleeper") ? "-"
                                : String.format("%+.2f +- %.2f%s", dl.mean(), dl.se(), flag ? "  <- past 3 se" : ""),
                        fillIns == 0 ? "" : String.format("   (%d men were Sleeper's number in this column, dropped)", fillIns)));
            }
            out.append('\n');
        }
        out.append(String.format("%d of %d paired tests past three standard errors (the bar for this many tests; at two, about half of all weeks flag one by chance).%n",
                flagged, tests));
        out.append("r and rho are scale-free, so a source that projects every elite forty points higher is not punished for its level;\n");
        out.append("MAE, bias and the paired columns are in points a game. 'per game' is the season number over " + games + " for every source, so a source\n");
        out.append("that hedged an injured man's games reads low the week he plays: bias shows it, and the last column removes each source's\n");
        out.append("level over the common men before comparing. One week of one man is one draw from his own scatter.\n");
        out.append("A man the archive filled with Sleeper's number for a source is dropped from that source's row (the count is printed);\n");
        out.append("every defence in every archived column is Sleeper's number, so DEF is not compared.\n");

        // ------------------------------------------------------------ rosters
        out.append("\n== ROSTERS: the league's scores against each source's ranking of the same rosters ==\n");
        record Team(String manager, double points, List<String> roster, List<String> started){}
        List<Team> teams = new ArrayList<>();
        for(LineupPromotion.RosterWeek row : rosters){
            String manager = managerOf.get(row.rosterID());
            if(manager != null){
                teams.add(new Team(manager, row.points(), row.roster(), row.started()));
            }
        }
        teams.sort(Comparator.comparingDouble((Team t) -> -t.points()));
        Map<String, Map<String, Double>> bestBy = new LinkedHashMap<>();
        Map<String, Map<String, Double>> startedBy = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> unpricedBy = new LinkedHashMap<>();   // source -> manager -> started men it does not price
        Map<String, Integer> holesBy = new LinkedHashMap<>();                     // source -> lineup holes over all rosters
        for(Map.Entry<String, Map<String, Double>> source : columns.entrySet()){
            boolean isWeek = source.getKey().equals("week");
            Map<String, Double> best = new LinkedHashMap<>();
            Map<String, Double> started = new LinkedHashMap<>();
            Map<String, Integer> unpriced = new LinkedHashMap<>();
            int holes = 0;
            for(Team team : teams){
                List<TeamRankings.Man> men = new ArrayList<>();
                for(String id : team.roster()){
                    Double p = source.getValue().get(id);
                    MarketMovers.Row row = meta.get(id);
                    if(p == null || row == null){
                        continue;
                    }
                    men.add(new TeamRankings.Man(id, row.name(), row.position(), row.team(),
                            isWeek ? p : p / games, false, 0, ""));
                }
                TeamRankings.Lineup lineup = TeamRankings.bestLineup(men);
                best.put(team.manager(), lineup.starters());
                holes += lineup.holes();
                double sum = 0;
                int missing = 0;
                for(String id : team.started()){
                    Double p = source.getValue().get(id);
                    if(p != null){
                        sum += isWeek ? p : p / games;
                    }
                    else{
                        missing++;
                    }
                }
                started.put(team.manager(), sum);
                unpriced.put(team.manager(), missing);
            }
            bestBy.put(source.getKey(), best);
            startedBy.put(source.getKey(), started);
            unpricedBy.put(source.getKey(), unpriced);
            holesBy.put(source.getKey(), holes);
        }
        out.append(String.format("%-13s %7s %4s", "manager", "points", "rank"));
        for(String source : columns.keySet()){
            out.append(String.format(" | %-8s%5s", cut(source, 8), "rk"));
        }
        out.append("   (each source: its best legal lineup from the week's roster, and that lineup's rank; DEF is Sleeper's number in every archived column)\n");
        Map<String, Map<String, Integer>> rankBy = new LinkedHashMap<>();
        for(Map.Entry<String, Map<String, Double>> e : bestBy.entrySet()){
            List<String> order = new ArrayList<>(e.getValue().keySet());
            order.sort(Comparator.comparingDouble((String m) -> -e.getValue().get(m)));
            Map<String, Integer> rank = new LinkedHashMap<>();
            for(int i = 0; i < order.size(); i++){
                rank.put(order.get(i), i + 1);
            }
            rankBy.put(e.getKey(), rank);
        }
        int place = 0;
        for(Team team : teams){
            place++;
            out.append(String.format("%-13s %7.1f %4d", cut(team.manager(), 13), team.points(), place));
            for(String source : columns.keySet()){
                out.append(String.format(" | %8.1f %4d", bestBy.get(source).get(team.manager()),
                        rankBy.get(source).get(team.manager())));
            }
            out.append(team.manager().equals(me) ? "   <- you\n" : "\n");
        }
        out.append(String.format("%-26s", "rank correlation, best lineup vs points"));
        for(String source : columns.keySet()){
            List<double[]> pairs = new ArrayList<>();
            for(Team team : teams){
                pairs.add(new double[]{bestBy.get(source).get(team.manager()), team.points()});
            }
            out.append(String.format(" | %8.2f     ", spearman(pairs)));
        }
        out.append(String.format("%n%-26s", "rank correlation, started ten vs points"));
        for(String source : columns.keySet()){
            List<double[]> pairs = new ArrayList<>();
            for(Team team : teams){
                if(unpricedBy.get(source).get(team.manager()) == 0){
                    pairs.add(new double[]{startedBy.get(source).get(team.manager()), team.points()});
                }
            }
            out.append(String.format(" | %5.2f (%2d)  ", spearman(pairs), pairs.size()));
        }
        out.append("\nmen a source does not price are left out of its sums, which reads low: started men unpriced, and lineup holes, per source -");
        for(String source : columns.keySet()){
            int total = 0;
            for(int n : unpricedBy.get(source).values()){
                total += n;
            }
            out.append(String.format(" %s %d/%d", source, total, holesBy.get(source)));
        }
        out.append("\n(the started-ten correlation counts only rosters every started man of which the source prices; the count is in brackets)");
        out.append(String.format("%ntwelve managers: a rank correlation's standard error is about %.2f, so one week separates nothing;%n",
                1 / Math.sqrt(Math.max(1, teams.size() - 1))));
        out.append("these rows are here to accumulate. A source's best lineup is the ranking that source would have made.\n");

        // ------------------------------------------------------------ the top scorer, and me
        List<String> spotlight = new ArrayList<>();
        if(!teams.isEmpty()){
            spotlight.add(teams.get(0).manager());
        }
        if(!spotlight.contains(me)){
            spotlight.add(me);
        }
        for(String manager : spotlight){
            Team team = null;
            for(Team t : teams){
                if(t.manager().equals(manager)){
                    team = t;
                }
            }
            if(team == null){
                continue;
            }
            out.append(String.format("%n== %s: the started ten, per game under each source, and what each scored ==%n", manager));
            out.append(String.format("%-22s %-3s %7s", "player", "pos", "scored"));
            for(String source : columns.keySet()){
                out.append(String.format(" %8s", cut(source, 8)));
            }
            out.append('\n');
            List<String> started = new ArrayList<>(team.started());
            started.sort(Comparator.comparingDouble((String id) -> -actual.getOrDefault(id, 0.0)));
            for(String id : started){
                MarketMovers.Row row = meta.get(id);
                Double scored = actual.get(id);
                out.append(String.format("%-22s %-3s %7s", cut(row == null ? id : row.name(), 22),
                        row == null ? "?" : row.position(), scored == null ? "-" : String.format("%.1f", scored)));
                for(Map.Entry<String, Map<String, Double>> source : columns.entrySet()){
                    Double p = source.getValue().get(id);
                    out.append(String.format(" %8s", p == null ? "-"
                            : String.format("%.1f", source.getKey().equals("week") ? p : p / games)));
                }
                out.append('\n');
            }
            out.append(String.format("%-22s %-3s %7.1f", "started ten", "", team.points()));
            for(String source : columns.keySet()){
                out.append(String.format(" %8.1f", startedBy.get(source).get(manager)));
            }
            out.append("   (the league's points for him, then each source's sum for the same ten)\n");
        }
        out.append("\nNOT MEASURED: whether any source would have set a better lineup on Sunday - that is StartSit's question, on the week feed.\n");

        System.out.print(out);
        Path report = Path.of("data", "projection-shootout-" + season + "-w" + week + ".txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }

    /**
     * Teams whose game was in progress when a live read was taken: their men
     * carry a score but no snap count yet, where every finished game's men do.
     * Only meaningful on the live feed of the current week.
     */
    static java.util.Set<String> inProgressTeams(com.google.gson.JsonObject feed){
        java.util.Set<String> teams = new java.util.TreeSet<>();
        for(Map.Entry<String, com.google.gson.JsonElement> e : feed.entrySet()){
            if(!LeagueActuals.isMan(e.getKey()) || !e.getValue().isJsonObject()){
                continue;
            }
            com.google.gson.JsonObject stats = e.getValue().getAsJsonObject();
            if(stats.has("pts_half_ppr") && !stats.get("pts_half_ppr").isJsonNull() && !stats.has("off_snp")){
                Player player = Player.getPlayerFromSIDV2(e.getKey());
                if(player != null && player.team != null){
                    teams.add(player.team);
                }
            }
        }
        return teams;
    }

    private static String cut(String s, int n){
        return s == null ? "?" : s.length() <= n ? s : s.substring(0, n);
    }
}
