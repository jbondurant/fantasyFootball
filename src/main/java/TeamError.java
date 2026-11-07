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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * HOW WRONG A TEAM'S PROJECTION IS FROM MIDSEASON - the error TitleOdds left out.
 *
 * TitleOdds draws every week around each team's projected lineup, as if Sleeper
 * knew exactly how strong each roster is for the rest of the season. It does not,
 * and on 2026-10-06 that made three teams level on record and projection read as
 * 18%, 15% and 12% to win the title. Justin: "would team level error work from
 * midway projections, or only starting projections" - midway, and this measures
 * it there, in this league's own completed seasons.
 *
 * For every season and every week w: each team's roster AS IT WAS that week (the
 * matchups feed lists it), each man at his projection for week w (his week w-1
 * projection when week w was his bye), carried flat to every week left with known
 * byes taken out (nflverse's schedule, his club from his latest stat row) - only
 * what was known at week w. The best legal lineup of that, averaged over the
 * weeks to the end of the regular season, is the team's projected weekly score
 * from w; what the team actually scored over those weeks (its matchup points, so
 * its later trades, pickups, injuries and lineup calls included) is the outcome.
 *
 * The miss is centred across the season's twelve teams at each w (a scoring rule
 * or a league-wide projection lean moves everyone), and what is left has two
 * parts: the team's real strength missing from the projection, and the
 * week-to-week noise TitleOdds already draws, which over n weeks has variance
 * spread^2 / n. So the team error is
 *
 *     sigma_team(w)^2 = (12/11) var(centred miss) - spread^2 / n
 *
 * floored at zero, with a bootstrap interval over team-seasons. The table goes to
 * data/team-error.tsv, which TitleOdds reads.
 *
 *   ./gradlew run -Pmain=TeamError
 */
public class TeamError {

    static final Path TABLE = Path.of("data", "team-error.tsv");

    /** sigma_team from centred misses (one per team-season), the teams per season, the weeks averaged and the weekly spread. */
    static double sigma(List<Double> centred, int teams, int weeks, double spread){
        if(centred.size() < 2){
            return Double.NaN;
        }
        double ss = 0;
        for(double d : centred){
            ss += d * d;
        }
        double variance = ss / centred.size() * teams / (teams - 1.0);
        return Math.sqrt(Math.max(0, variance - spread * spread / weeks));
    }

    /** Each id's club in a past season week: his latest stat row at or before it. */
    static Map<String, String> clubs(String season, int upTo, LeagueScoringSettings scoring){
        Map<String, String> out = new HashMap<>();
        for(int w = 1; w <= upTo; w++){
            try {
                ScreenData.lines(LeagueWeek.teamStatsBody(season, w), scoring).forEach((id, line) -> out.put(id, line.team()));
            }
            catch(RuntimeException unread){
                // a week the feed cannot give: earlier clubs stand
            }
        }
        return out;
    }

    /** The clubs that play in a week of a season (Sleeper's codes). */
    static Set<String> playing(String season, int week){
        Set<String> out = new HashSet<>();
        for(NflverseGames.Game g : NflverseGames.games()){
            if(g.season().equals(season) && g.week() == week){
                out.add(NflverseGames.sleeperTeam(g.home()));
                out.add(NflverseGames.sleeperTeam(g.away()));
            }
        }
        return out;
    }

    /** One team-season from week w: {projected weekly mean, actual weekly mean}, keyed season|w|roster. */
    record Miss(String season, int from, int weeks, int roster, double projected, double actual) {}

    static List<Miss> misses(AAAConfiguration configuration){
        LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        List<Miss> out = new ArrayList<>();
        String leagueID = configuration.getPreviousLeagueID();
        int guard = 0;
        while(leagueID != null && guard++ < 8){
            JsonObject league = JsonParser.parseString(InOutUtilities.getCachedForever(
                    "https://api.sleeper.app/v1/league/" + leagueID, "leagueChain" + leagueID)).getAsJsonObject();
            String season = league.get("season").getAsString();
            int lastWeek = league.getAsJsonObject("settings").get("playoff_week_start").getAsInt() - 1;
            Map<Integer, Map<Integer, JsonObject>> rows = new TreeMap<>();     // week -> roster -> row
            for(int week = 1; week <= lastWeek; week++){
                Map<Integer, JsonObject> byRoster = new TreeMap<>();
                for(JsonElement e : JsonParser.parseString(InOutUtilities.getCachedForever(
                        "https://api.sleeper.app/v1/league/" + leagueID + "/matchups/" + week,
                        "sleeperMatchups" + leagueID + "w" + week)).getAsJsonArray()){
                    JsonObject row = e.getAsJsonObject();
                    byRoster.put(row.get("roster_id").getAsInt(), row);
                }
                rows.put(week, byRoster);
            }
            Map<Integer, Set<String>> plays = new HashMap<>();
            for(int week = 1; week <= lastWeek; week++){
                plays.put(week, playing(season, week));
            }
            for(int from = 2; from <= lastWeek - 2; from++){
                Map<String, Double> now = LeagueWeek.projected(season, from);
                Map<String, Double> before = LeagueWeek.projected(season, from - 1);
                Map<String, String> club = clubs(season, from, scoring);
                for(Map.Entry<Integer, JsonObject> e : rows.get(from).entrySet()){
                    JsonObject row = e.getValue();
                    if(!row.has("players") || !row.get("players").isJsonArray()){
                        continue;
                    }
                    // each man's rate as known at week w
                    Map<String, Double> rate = new HashMap<>();
                    Map<String, String> clubOf = new HashMap<>();
                    for(JsonElement p : row.getAsJsonArray("players")){
                        String id = p.getAsString();
                        String team = Character.isLetter(id.charAt(0)) ? id : club.get(id);
                        clubOf.put(id, team);
                        Double r = now.get(id);
                        if(r == null && team != null && !plays.get(from).contains(team)){
                            r = before.get(id);           // his bye: last week's number is what was known
                        }
                        if(r != null && r > 0){
                            rate.put(id, r);
                        }
                    }
                    double projected = 0, actual = 0;
                    int n = 0;
                    for(int k = from; k <= lastWeek; k++){
                        JsonObject played = rows.get(k).get(e.getKey());
                        double points = played == null ? 0 : SeasonOutlook.weekPoints(played);
                        if(points <= 0){
                            continue;
                        }
                        List<TeamRankings.Man> men = new ArrayList<>();
                        for(Map.Entry<String, Double> r : rate.entrySet()){
                            String team = clubOf.get(r.getKey());
                            Player player = Player.getPlayerFromSIDV2(r.getKey());
                            if(player == null || player.position == null || player.position == Position.OTHER
                                    || team != null && !plays.get(k).contains(team)){
                                continue;
                            }
                            men.add(new TeamRankings.Man(r.getKey(), r.getKey(), player.position.name(), team, r.getValue(), false, 0, null));
                        }
                        projected += TeamRankings.bestLineup(men).starters();
                        actual += points;
                        n++;
                    }
                    if(n > 0){
                        out.add(new Miss(season, from, n, e.getKey(), projected / n, actual / n));
                    }
                }
            }
            leagueID = league.has("previous_league_id") && !league.get("previous_league_id").isJsonNull()
                    ? league.get("previous_league_id").getAsString() : null;
        }
        return out;
    }

    /** Each miss less its season-week's mean across teams: what a league-wide lean cannot explain. */
    static Map<Integer, List<double[]>> centred(List<Miss> misses){
        Map<String, double[]> mean = new HashMap<>();
        for(Miss m : misses){
            double[] s = mean.computeIfAbsent(m.season() + "|" + m.from(), k -> new double[2]);
            s[0] += m.actual() - m.projected();
            s[1]++;
        }
        Map<Integer, List<double[]>> out = new TreeMap<>();
        for(Miss m : misses){
            double[] s = mean.get(m.season() + "|" + m.from());
            out.computeIfAbsent(m.from(), k -> new ArrayList<>()).add(new double[]{m.actual() - m.projected() - s[0] / s[1], m.weeks()});
        }
        return out;
    }

    /** The table TitleOdds reads: from-week -> sigma. Missing file: no team error, as before. */
    static Map<Integer, Double> table(){
        Map<Integer, Double> out = new TreeMap<>();
        try {
            for(String line : Files.readAllLines(TABLE, StandardCharsets.UTF_8)){
                String[] c = line.split("\t");
                if(c.length >= 2 && Character.isDigit(c[0].charAt(0))){
                    out.put(Integer.parseInt(c[0]), Double.parseDouble(c[1]));
                }
            }
        }
        catch(IOException | NumberFormatException missing){
            // no table: TitleOdds draws no team error and says so
        }
        return out;
    }

    /** The measured sigma for a team projected from this week: the nearest measured week, none past the last. */
    static double sigmaFrom(int week, Map<Integer, Double> table){
        if(table.isEmpty()){
            return 0;
        }
        Integer key = ((TreeMap<Integer, Double>) table).floorKey(week);
        return table.get(key == null ? ((TreeMap<Integer, Double>) table).firstKey() : key);
    }

    public static void main(String[] args) throws IOException {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        double spread = SeasonOutlook.measuredSpread(SeasonOutlook.measureSpread(configuration));
        List<Miss> misses = misses(configuration);
        Map<Integer, List<double[]>> byWeek = centred(misses);
        StringBuilder out = new StringBuilder();
        StringBuilder tsv = new StringBuilder("from_week\tsigma\tlow\thigh\tteam_seasons\tweeks_left\n");
        out.append(String.format("TEAM ERROR  %s  (this league's completed seasons; %d team-season-weeks)%n", LocalDate.now(), misses.size()));
        out.append(String.format("Each team's roster as it stood at week w, priced on what was projected then and carried forward with byes out,%n"
                + "against what the team actually scored to the end of the regular season. The miss, centred across the season's%n"
                + "teams, less the week-to-week noise (spread %.1f, over the weeks left), is the team error TitleOdds draws once per%n"
                + "team per season. 90%% interval by bootstrap over team-seasons.%n%n", spread));
        out.append(String.format("%-9s %8s %10s %8s %13s %11s%n", "FROM WK", "LEFT", "sd(miss)", "SIGMA", "90% interval", "teams"));
        Random random = new Random(7);
        for(Map.Entry<Integer, List<double[]>> e : byWeek.entrySet()){
            List<double[]> list = e.getValue();
            double weeks = 0, ss = 0;
            List<Double> d = new ArrayList<>();
            for(double[] x : list){
                d.add(x[0]);
                weeks += x[1];
                ss += x[0] * x[0];
            }
            int n = (int) Math.round(weeks / list.size());
            double sigma = sigma(d, 12, n, spread);
            List<Double> boot = new ArrayList<>();
            for(int b = 0; b < 1000; b++){
                List<Double> draw = new ArrayList<>();
                for(int i = 0; i < d.size(); i++){
                    draw.add(d.get(random.nextInt(d.size())));
                }
                boot.add(sigma(draw, 12, n, spread));
            }
            boot.sort(Double::compare);
            out.append(String.format("%-9d %8d %10.1f %8.1f %6.1f-%-6.1f %11d%n", e.getKey(), n, Math.sqrt(ss / list.size()), sigma,
                    boot.get(50), boot.get(949), list.size()));
            tsv.append(String.format("%d\t%.3f\t%.3f\t%.3f\t%d\t%d%n", e.getKey(), sigma, boot.get(50), boot.get(949), list.size(), n));
        }
        out.append("\nSIGMA is in points of weekly score: a team's true strength from week w is its projection plus or minus\n");
        out.append("about that much for the rest of the season. Written to " + TABLE + " for TitleOdds.\n");
        System.out.print(out);
        Files.writeString(TABLE, tsv.toString(), StandardCharsets.UTF_8);
        Path report = Path.of("data", "team-error-" + LocalDate.now() + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + report);
    }
}
