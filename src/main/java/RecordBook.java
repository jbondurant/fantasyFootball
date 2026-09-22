import com.google.gson.JsonArray;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * THE RECORD BOOK: THE HIGHEST WEEKS IN THE LEAGUE'S HISTORY.
 *
 * Every season in the league's chain, every week with points, every roster:
 * the matchups feed carries what each team scored, whom it played, and what
 * each started man contributed, all under the league's own scoring as Sleeper
 * applied it that week. From that:
 *
 *   - the ten highest team weeks ever, with the opponent, the margin, and
 *     whether the week was a playoff week or one with no game scheduled
 *   - the same for one week number across every season (-Pweek=n), which is
 *     the "given week" reading of the question
 *   - each manager's own best week
 *   - the ten highest single-man weeks by a STARTED player - a bench 40 is
 *     not on the scoreboard
 *   - the ten LOWEST weeks that had a game to lose; a team out of the bracket
 *     still records points with nobody setting its lineup, and those rows are
 *     counted apart rather than ranked as futility
 *   - and what carried the record weeks, two ways. The SHARE its best starter
 *     and best three produced, against an ordinary week: a record is either
 *     one man going off or ten men all playing well. And what each started
 *     man was PROJECTED that week against what he scored - the four biggest
 *     ratios per week, and then every start in those weeks that beat its
 *     number by a significant margin - with "significant" set by the measured
 *     distribution
 *     of every start in the league's history rather than by a round number.
 *     The weekly projection is a forecast rather than a result: tested on
 *     2021 it correlates 0.914 with the genuine August snapshot and 0.703
 *     with the week's outcome ({@link WeeklyProjections})
 *
 * Past seasons are frozen and read once; the current season's finished weeks
 * are frozen too, its live week read through the day's cache
 * ({@link LeagueWeek#matchups}). A week Sleeper has not closed is left out by
 * default - its points are partial and would rank as if they were final -
 * and {@code -Plive} puts it in, every row marked, so a week that is over on
 * the field before Sleeper says so can still be read. Sleeper advances on
 * Tuesday: on the Monday of week 2 that week was held out of this report, and
 * it entered the next day at 185.3.
 *
 *   ./gradlew run -Pmain=RecordBook [-Pweek=n] [-Ptop=10] [-Plive]
 */
public class RecordBook {

    /** One team's week. */
    public record TeamWeek(String season, int week, String manager, double points,
                           String opponent, double opponentPoints, boolean playoff, boolean noGame, boolean live) {
        /** The shape before a live week could be ranked; tests still build it. */
        public TeamWeek(String season, int week, String manager, double points,
                        String opponent, double opponentPoints, boolean playoff, boolean noGame){
            this(season, week, manager, points, opponent, opponentPoints, playoff, noGame, false);
        }

        String result(){
            if(opponent == null){
                return "no game";
            }
            return points > opponentPoints ? String.format("beat %s %.1f", opponent, opponentPoints)
                    : points < opponentPoints ? String.format("lost to %s %.1f", opponent, opponentPoints)
                    : String.format("tied %s", opponent);
        }
    }

    /** One started man's week. */
    public record ManWeek(String season, int week, String manager, String playerID, double points) {}

    /** Every roster's week from a matchups feed, paired with its opponent through matchup_id. */
    static List<TeamWeek> teamWeeks(String body, String season, int week, Map<Integer, String> managerOf,
                                    boolean playoff){
        return teamWeeks(body, season, week, managerOf, playoff, false);
    }

    /** The same, marking the rows of a week whose games are not all played. */
    static List<TeamWeek> teamWeeks(String body, String season, int week, Map<Integer, String> managerOf,
                                    boolean playoff, boolean live){
        JsonArray rows = JsonParser.parseString(body).getAsJsonArray();
        Map<Integer, List<JsonObject>> byMatchup = new HashMap<>();
        List<JsonObject> all = new ArrayList<>();
        for(JsonElement e : rows){
            JsonObject row = e.getAsJsonObject();
            all.add(row);
            if(row.has("matchup_id") && !row.get("matchup_id").isJsonNull()){
                byMatchup.computeIfAbsent(row.get("matchup_id").getAsInt(), k -> new ArrayList<>()).add(row);
            }
        }
        List<TeamWeek> out = new ArrayList<>();
        for(JsonObject row : all){
            double points = SeasonOutlook.weekPoints(row);
            if(points <= 0){
                continue;   // an unplayed week, not a zero
            }
            int rosterID = row.get("roster_id").getAsInt();
            String opponent = null;
            double opponentPoints = 0;
            if(row.has("matchup_id") && !row.get("matchup_id").isJsonNull()){
                for(JsonObject other : byMatchup.get(row.get("matchup_id").getAsInt())){
                    if(other != row){
                        opponent = managerOf.getOrDefault(other.get("roster_id").getAsInt(), "roster " + other.get("roster_id").getAsInt());
                        opponentPoints = SeasonOutlook.weekPoints(other);
                    }
                }
            }
            out.add(new TeamWeek(season, week, managerOf.getOrDefault(rosterID, "roster " + rosterID), points,
                    opponent, opponentPoints, playoff, opponent == null, live));
        }
        return out;
    }

    /** Every started man's week from a matchups feed. */
    static List<ManWeek> manWeeks(String body, String season, int week, Map<Integer, String> managerOf){
        List<ManWeek> out = new ArrayList<>();
        for(JsonElement e : JsonParser.parseString(body).getAsJsonArray()){
            JsonObject row = e.getAsJsonObject();
            if(!row.has("starters") || !row.get("starters").isJsonArray()
                    || !row.has("starters_points") || !row.get("starters_points").isJsonArray()){
                continue;
            }
            JsonArray starters = row.getAsJsonArray("starters");
            JsonArray points = row.getAsJsonArray("starters_points");
            int rosterID = row.get("roster_id").getAsInt();
            for(int i = 0; i < Math.min(starters.size(), points.size()); i++){
                if(starters.get(i).isJsonNull() || points.get(i).isJsonNull()){
                    continue;
                }
                String id = starters.get(i).getAsString();
                if(id.isBlank() || id.equals("0")){
                    continue;
                }
                out.add(new ManWeek(season, week, managerOf.getOrDefault(rosterID, "roster " + rosterID), id,
                        points.get(i).getAsDouble()));
            }
        }
        return out;
    }

    /** The share of a team's points its best {@code n} starters produced; 0 when the week has no points. */
    static double shareOfTop(List<ManWeek> men, double total, int n){
        if(total <= 0 || men.isEmpty()){
            return 0;
        }
        List<ManWeek> sorted = new ArrayList<>(men);
        sorted.sort(Comparator.comparingDouble(ManWeek::points).reversed());
        double sum = 0;
        for(int i = 0; i < Math.min(n, sorted.size()); i++){
            sum += sorted.get(i).points();
        }
        return sum / total;
    }

    /** Season, week and manager: what pairs a team's week with the men it started. */
    static String key(String season, int week, String manager){
        return season + "|" + week + "|" + manager;
    }

    static String name(String playerID){
        Player player = Player.getPlayerFromSIDV2(playerID);
        if(player == null){
            return playerID;
        }
        return player.position != null && player.position.name().equals("DEF")
                ? player.team + " D/ST" : player.firstName + " " + player.lastName
                + (player.position == null ? "" : " (" + player.position + ")");
    }

    public static void main(String[] args) throws IOException {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int top = Integer.getInteger("top", 10);
        Integer onlyWeek = Integer.getInteger("week");
        // -Plive ranks the week Sleeper has not closed yet. Its points are partial
        // until the last game ends, so every row from it is marked and the header
        // says which week it was and what it holds.
        boolean live = System.getProperty("live") != null
                && !"false".equalsIgnoreCase(System.getProperty("live"));
        String currentLeague = configuration.getLeagueID();
        int currentWeek = LeagueWeek.week();

        List<TeamWeek> teams = new ArrayList<>();
        List<ManWeek> men = new ArrayList<>();
        List<String> seasons = new ArrayList<>();
        int weeksRead = 0;
        for(LeagueTransactions.Year year : LeagueTransactions.chain(currentLeague)){
            boolean current = year.leagueID().equals(currentLeague);
            JsonObject league = JsonParser.parseString(LeagueTransactions.leagueRaw(year.leagueID())).getAsJsonObject();
            int playoffStart = league.getAsJsonObject("settings").get("playoff_week_start").getAsInt();
            Map<Integer, String> managerOf = current ? SeasonLedger.managerByRoster(configuration)
                    : TradePartners.managersOf(year.leagueID());
            int lastWeek = current ? currentWeek : WeeklyActuals.WEEKS;
            for(int week = 1; week <= lastWeek; week++){
                String body;
                if(current){
                    body = LeagueWeek.matchups(year.leagueID(), week);
                }
                else{
                    body = InOutUtilities.getCachedForeverAllowingEmpty(
                            "https://api.sleeper.app/v1/league/" + year.leagueID() + "/matchups/" + week,
                            "sleeperMatchups" + year.leagueID() + "w" + week);
                }
                if(body == null || body.isBlank() || body.trim().equals("[]")){
                    continue;
                }
                boolean unfinished = current && !LeagueWeek.finished(week);
                if(unfinished && !live){
                    continue;   // a week in progress is not a record yet
                }
                List<TeamWeek> weekRows = teamWeeks(body, year.season(), week, managerOf, week >= playoffStart, unfinished);
                if(!weekRows.isEmpty()){
                    weeksRead++;
                }
                teams.addAll(weekRows);
                men.addAll(manWeeks(body, year.season(), week, managerOf));
            }
            seasons.add(year.season());
        }
        seasons.sort(Comparator.naturalOrder());

        StringBuilder out = new StringBuilder();
        List<TeamWeek> liveRows = new ArrayList<>();
        for(TeamWeek t : teams){
            if(t.live()){
                liveRows.add(t);
            }
        }
        out.append(String.format("RECORD BOOK  %s  (%d seasons %s-%s, %d weeks with points, %d team-weeks; %s)%n",
                LocalDate.now(), seasons.size(), seasons.get(0), seasons.get(seasons.size() - 1), weeksRead, teams.size(),
                !live ? "the week Sleeper has open is excluded until it closes - -Plive includes it"
                        : liveRows.isEmpty() ? "-Plive given; the open week has no points yet, so nothing was added"
                                : String.format("-Plive given: week %d of %s is IN PROGRESS and its %d rows are ranked, marked [live]",
                                        liveRows.get(0).week(), liveRows.get(0).season(), liveRows.size())));
        out.append("points are the league's own, as Sleeper scored the week; playoff weeks and weeks with no game scheduled are marked.\n");

        Comparator<TeamWeek> best = Comparator.comparingDouble(TeamWeek::points).reversed();
        List<TeamWeek> ranked = new ArrayList<>(teams);
        ranked.sort(best);
        out.append(String.format("%n== THE %d HIGHEST TEAM WEEKS EVER ==%n", top));
        out.append(String.format("%-3s %-13s %6s %3s %7s   %s%n", "#", "manager", "season", "wk", "points", "result"));
        for(int i = 0; i < Math.min(top, ranked.size()); i++){
            TeamWeek t = ranked.get(i);
            out.append(String.format("%-3d %-13s %6s %3d %7.1f   %s%s%s%n", i + 1, t.manager(), t.season(), t.week(), t.points(),
                    t.result(), t.playoff() ? "  [playoff]" : "", t.live() ? "  [live, partial]" : ""));
        }

        if(onlyWeek != null){
            List<TeamWeek> thatWeek = new ArrayList<>();
            for(TeamWeek t : teams){
                if(t.week() == onlyWeek){
                    thatWeek.add(t);
                }
            }
            thatWeek.sort(best);
            out.append(String.format("%n== THE %d HIGHEST WEEK-%d SCORES ACROSS THE SEASONS ==%n", top, onlyWeek));
            out.append(String.format("%-3s %-13s %6s %7s   %s%n", "#", "manager", "season", "points", "result"));
            for(int i = 0; i < Math.min(top, thatWeek.size()); i++){
                TeamWeek t = thatWeek.get(i);
                out.append(String.format("%-3d %-13s %6s %7.1f   %s%s%s%n", i + 1, t.manager(), t.season(), t.points(),
                        t.result(), t.playoff() ? "  [playoff]" : "", t.live() ? "  [live, partial]" : ""));
            }
        }

        out.append("\n== EACH MANAGER'S BEST WEEK ==\n");
        Map<String, TeamWeek> bestOf = new TreeMap<>();
        for(TeamWeek t : teams){
            TeamWeek have = bestOf.get(t.manager());
            if(have == null || t.points() > have.points()){
                bestOf.put(t.manager(), t);
            }
        }
        List<TeamWeek> managers = new ArrayList<>(bestOf.values());
        managers.sort(best);
        for(TeamWeek t : managers){
            out.append(String.format("%-13s %6s wk %2d %7.1f   %s%s%s%n", t.manager(), t.season(), t.week(), t.points(),
                    t.result(), t.playoff() ? "  [playoff]" : "", t.live() ? "  [live, partial]" : ""));
        }

        // A WEEK WITH NO GAME IS NOT A BAD WEEK. After the bracket starts, a team
        // out of it still has points recorded and nobody setting its lineup; those
        // rows filled most of a first draft of this list and are a record of not
        // playing, not of playing badly. Ranked separately, counted, named.
        List<TeamWeek> played = new ArrayList<>();
        List<TeamWeek> noGame = new ArrayList<>();
        for(TeamWeek t : teams){
            (t.noGame() ? noGame : played).add(t);
        }
        played.sort(Comparator.comparingDouble(TeamWeek::points));
        noGame.sort(Comparator.comparingDouble(TeamWeek::points));
        out.append(String.format("%n== THE %d LOWEST TEAM WEEKS EVER, WITH A GAME TO LOSE ==%n", top));
        out.append(String.format("%-3s %-13s %6s %3s %7s   %s%n", "#", "manager", "season", "wk", "points", "result"));
        for(int i = 0; i < Math.min(top, played.size()); i++){
            TeamWeek t = played.get(i);
            out.append(String.format("%-3d %-13s %6s %3d %7.1f   %s%s%s%n", i + 1, t.manager(), t.season(), t.week(), t.points(),
                    t.result(), t.playoff() ? "  [playoff]" : "", t.live() ? "  [live, partial]" : ""));
        }
        out.append("a roster with no points at all is an unplayed week and is not ranked here at all.\n");
        if(!noGame.isEmpty()){
            out.append(String.format("%d team-weeks with NO game scheduled are excluded from this list - after the bracket starts, a team%n", noGame.size()));
            out.append("out of it still records points and nobody is setting its lineup. The lowest of those: ");
            for(int i = 0; i < Math.min(3, noGame.size()); i++){
                TeamWeek t = noGame.get(i);
                out.append(String.format("%s%s %s wk %d %.1f", i == 0 ? "" : ", ", t.manager(), t.season(), t.week(), t.points()));
            }
            out.append(".\n");
        }

        // WHAT CARRIED THE RECORD WEEKS. One man going off and ten men all playing
        // well are different weeks, and the share says which it was.
        Map<String, List<ManWeek>> lineupOf = new HashMap<>();
        for(ManWeek m : men){
            lineupOf.computeIfAbsent(key(m.season(), m.week(), m.manager()), k -> new ArrayList<>()).add(m);
        }
        out.append(String.format("%n== WHAT CARRIED THE TOP %d: THE SHARE ITS BEST STARTERS PRODUCED ==%n", top));
        out.append(String.format("%-3s %-13s %6s %3s %7s %6s %6s   %s%n", "#", "manager", "season", "wk", "points",
                "best", "top 3", "his best three, with each man's share"));
        double bestShareSum = 0;
        double topThreeSum = 0;
        int counted = 0;
        for(int i = 0; i < Math.min(top, ranked.size()); i++){
            TeamWeek t = ranked.get(i);
            List<ManWeek> lineup = lineupOf.getOrDefault(key(t.season(), t.week(), t.manager()), List.of());
            if(lineup.isEmpty()){
                continue;
            }
            List<ManWeek> sorted = new ArrayList<>(lineup);
            sorted.sort(Comparator.comparingDouble(ManWeek::points).reversed());
            double bestShare = shareOfTop(lineup, t.points(), 1);
            double threeShare = shareOfTop(lineup, t.points(), 3);
            bestShareSum += bestShare;
            topThreeSum += threeShare;
            counted++;
            StringBuilder three = new StringBuilder();
            for(int j = 0; j < Math.min(3, sorted.size()); j++){
                three.append(String.format("%s%s %.1f (%.0f%%)", j == 0 ? "" : ", ", name(sorted.get(j).playerID()),
                        sorted.get(j).points(), 100 * sorted.get(j).points() / t.points()));
            }
            out.append(String.format("%-3d %-13s %6s %3d %7.1f %5.0f%% %5.0f%%   %s%n", i + 1, t.manager(), t.season(),
                    t.week(), t.points(), 100 * bestShare, 100 * threeShare, three));
        }
        // the same share over every team-week there has ever been: the ordinary week
        double ordinaryBest = 0;
        double ordinaryThree = 0;
        int ordinary = 0;
        for(TeamWeek t : teams){
            List<ManWeek> lineup = lineupOf.getOrDefault(key(t.season(), t.week(), t.manager()), List.of());
            if(lineup.isEmpty()){
                continue;
            }
            ordinaryBest += shareOfTop(lineup, t.points(), 1);
            ordinaryThree += shareOfTop(lineup, t.points(), 3);
            ordinary++;
        }
        if(counted > 0 && ordinary > 0){
            out.append(String.format("%nmean over the top %d: best starter %.0f%% of the team's points, best three %.0f%%.%n",
                    counted, 100 * bestShareSum / counted, 100 * topThreeSum / counted));
            out.append(String.format("mean over all %d team-weeks:  best starter %.0f%%, best three %.0f%%.%n",
                    ordinary, 100 * ordinaryBest / ordinary, 100 * ordinaryThree / ordinary));
            out.append("a record week that concentrates MORE than an ordinary one was one man going off; the same or less was the whole lineup.\n");
        }

        // WHAT HE WAS PROJECTED, AND WHAT HE DID. Sleeper publishes a projection
        // per man per week, league-scored here through the same path the lineup
        // page reads. A past season's week is immutable, so each is fetched once.
        Map<String, Map<String, Double>> projectionOf = new HashMap<>();
        for(TeamWeek t : teams){
            projectionOf.computeIfAbsent(t.season() + "|" + t.week(), k -> {
                try {
                    return LeagueWeek.projected(t.season(), t.week());
                }
                catch(RuntimeException unavailable){
                    return Map.of();
                }
            });
        }
        // the bar: every start in the league's history, actual minus projected
        List<Double> everyDelta = new ArrayList<>();
        List<double[]> projectedAgainstActual = new ArrayList<>();
        int noProjection = 0;
        for(ManWeek m : men){
            Double projected = projectionOf.getOrDefault(m.season() + "|" + m.week(), Map.of()).get(m.playerID());
            if(projected == null || projected <= 0){
                noProjection++;
                continue;
            }
            everyDelta.add(m.points() - projected);
            projectedAgainstActual.add(new double[]{projected, m.points()});
        }
        double[] sortedDelta = new double[everyDelta.size()];
        for(int i = 0; i < sortedDelta.length; i++){
            sortedDelta[i] = everyDelta.get(i);
        }
        java.util.Arrays.sort(sortedDelta);
        double p50 = RosBands.quantile(sortedDelta, 0.50);
        double p90 = RosBands.quantile(sortedDelta, 0.90);
        double p95 = RosBands.quantile(sortedDelta, 0.95);
        double p99 = RosBands.quantile(sortedDelta, 0.99);
        out.append(String.format("%n== PROJECTED AGAINST ACTUAL: THE BAR, FROM EVERY START THE LEAGUE HAS MADE ==%n"));
        out.append(String.format("%d starts with a projection (%d had none and are left out - a man Sleeper did not expect to play).%n",
                sortedDelta.length, noProjection));
        out.append(String.format("points above projection: median %+.1f, 90th %+.1f, 95th %+.1f, 99th %+.1f.%n", p50, p90, p95, p99));
        out.append(String.format("A start is called SIGNIFICANT here when it beats its projection by %+.1f or more - the 95th percentile, so one start in twenty.%n", p95));
        // IS THE PROJECTION A FORECAST? Sleeper serves one number per past week and
        // does not say when it was made. A result dressed as a projection would
        // track the outcome almost exactly; a forecast cannot. The correlation says
        // which this is, and it is the whole licence for the ratios below.
        out.append(String.format("projected against actual over those starts: r = %.3f. A projection revised after the games would read near 1;%n",
                WeeklyFeedAudit.fit(projectedAgainstActual).r()));
        out.append("this is the scatter of a forecast, which is what WeeklyProjections measured a different way on 2021 (0.914 with the\n");
        out.append("August snapshot, 0.703 with the outcome). Week-1 projections of THIS season still moved for 81 of 809 men between the\n");
        out.append("last pre-kickoff read and the post-game one (TRAPS #137), so treat a single ratio as indicative, not exact.\n");

        record Popped(TeamWeek week, ManWeek man, double projected) {
            double delta(){
                return man.points() - projected;
            }
            double ratio(){
                return projected <= 0 ? Double.NaN : man.points() / projected;
            }
        }
        List<Popped> significant = new ArrayList<>();
        out.append(String.format("%n== THE TOP %d: THE FOUR MEN WHO MOST BEAT THEIR NUMBER, BY RATIO ==%n", top));
        for(int i = 0; i < Math.min(top, ranked.size()); i++){
            TeamWeek t = ranked.get(i);
            List<ManWeek> lineup = new ArrayList<>(lineupOf.getOrDefault(key(t.season(), t.week(), t.manager()), List.of()));
            if(lineup.isEmpty()){
                continue;
            }
            Map<String, Double> projections = projectionOf.getOrDefault(t.season() + "|" + t.week(), Map.of());
            List<Popped> rows = new ArrayList<>();
            double teamProjected = 0;
            for(ManWeek m : lineup){
                Double projected = projections.get(m.playerID());
                rows.add(new Popped(t, m, projected == null ? 0 : projected));
                teamProjected += projected == null ? 0 : projected;
            }
            // every start is weighed for the significance list below; the week's own
            // table shows the four that most beat their number, biggest ratio first
            for(Popped r : rows){
                if(r.projected() > 0 && r.delta() >= p95){
                    significant.add(r);
                }
            }
            rows.sort(Comparator.comparingDouble((Popped r) -> Double.isNaN(r.ratio()) ? -1 : r.ratio()).reversed());
            out.append(String.format("%n%d. %s, %s week %d: %.1f scored against %.1f projected%s%n", i + 1, t.manager(),
                    t.season(), t.week(), t.points(), teamProjected, t.live() ? "  [live, partial]" : ""));
            out.append(String.format("   %-26s %9s %8s %7s%n", "player", "projected", "actual", "ratio"));
            for(Popped r : rows.subList(0, Math.min(4, rows.size()))){
                out.append(String.format("   %-26s %9s %8.1f %7s%n", name(r.man().playerID()),
                        r.projected() <= 0 ? "-" : String.format("%.1f", r.projected()), r.man().points(),
                        Double.isNaN(r.ratio()) ? "-" : String.format("%.2fx", r.ratio())));
            }
        }
        significant.sort(Comparator.comparingDouble(Popped::delta).reversed());
        out.append(String.format("%n== THE SIGNIFICANT ONES: every start in those %d weeks that beat its projection by %+.1f or more ==%n", top, p95));
        out.append(String.format("%-26s %-13s %6s %3s %9s %8s %7s %8s%n", "player", "started by", "season", "wk", "projected", "actual", "ratio", "vs proj"));
        for(Popped r : significant){
            out.append(String.format("%-26s %-13s %6s %3d %9.1f %8.1f %6.2fx %+8.1f%n", name(r.man().playerID()),
                    r.week().manager(), r.week().season(), r.week().week(), r.projected(), r.man().points(),
                    r.ratio(), r.delta()));
        }
        out.append(String.format("%n%d of the %d starts in those weeks were significant, %.1f a week against the %.1f a week one start in twenty implies%n",
                significant.size(), Math.min(top, ranked.size()) * 10, significant.size() / (double) Math.max(1, Math.min(top, ranked.size())),
                0.05 * 10));
        out.append("- so a record week is not one man popping off; it is half a lineup doing it at once.\n");

        List<ManWeek> starters = new ArrayList<>(men);
        starters.sort(Comparator.comparingDouble(ManWeek::points).reversed());
        out.append(String.format("%n== THE %d HIGHEST WEEKS BY A STARTED PLAYER ==%n", top));
        out.append(String.format("%-3s %-26s %-13s %6s %3s %7s%n", "#", "player", "started by", "season", "wk", "points"));
        for(int i = 0; i < Math.min(top, starters.size()); i++){
            ManWeek m = starters.get(i);
            out.append(String.format("%-3d %-26s %-13s %6s %3d %7.1f%n", i + 1, name(m.playerID()), m.manager(), m.season(), m.week(), m.points()));
        }

        System.out.print(out);
        Path report = Path.of("data", "record-book-" + LocalDate.now() + ".txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }
}
