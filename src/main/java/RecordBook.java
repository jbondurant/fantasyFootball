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
 *
 * Past seasons are frozen and read once; the current season's finished weeks
 * are frozen too, its live week read through the day's cache
 * ({@link LeagueWeek#matchups}), so a week in progress shows as such and is
 * never ranked as if it were over.
 *
 *   ./gradlew run -Pmain=RecordBook [-Pweek=n] [-Ptop=10]
 */
public class RecordBook {

    /** One team's week. */
    public record TeamWeek(String season, int week, String manager, double points,
                           String opponent, double opponentPoints, boolean playoff, boolean noGame) {
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
                    opponent, opponentPoints, playoff, opponent == null));
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
                List<TeamWeek> weekRows = teamWeeks(body, year.season(), week, managerOf, week >= playoffStart);
                if(current && !LeagueWeek.finished(week)){
                    continue;   // a week in progress is not a record yet
                }
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
        out.append(String.format("RECORD BOOK  %s  (%d seasons %s-%s, %d weeks with points, %d team-weeks; the current week excluded until it is over)%n",
                LocalDate.now(), seasons.size(), seasons.get(0), seasons.get(seasons.size() - 1), weeksRead, teams.size()));
        out.append("points are the league's own, as Sleeper scored the week; playoff weeks and weeks with no game scheduled are marked.\n");

        Comparator<TeamWeek> best = Comparator.comparingDouble(TeamWeek::points).reversed();
        List<TeamWeek> ranked = new ArrayList<>(teams);
        ranked.sort(best);
        out.append(String.format("%n== THE %d HIGHEST TEAM WEEKS EVER ==%n", top));
        out.append(String.format("%-3s %-13s %6s %3s %7s   %s%n", "#", "manager", "season", "wk", "points", "result"));
        for(int i = 0; i < Math.min(top, ranked.size()); i++){
            TeamWeek t = ranked.get(i);
            out.append(String.format("%-3d %-13s %6s %3d %7.1f   %s%s%n", i + 1, t.manager(), t.season(), t.week(), t.points(),
                    t.result(), t.playoff() ? "  [playoff]" : ""));
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
                out.append(String.format("%-3d %-13s %6s %7.1f   %s%s%n", i + 1, t.manager(), t.season(), t.points(),
                        t.result(), t.playoff() ? "  [playoff]" : ""));
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
            out.append(String.format("%-13s %6s wk %2d %7.1f   %s%s%n", t.manager(), t.season(), t.week(), t.points(),
                    t.result(), t.playoff() ? "  [playoff]" : ""));
        }

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
