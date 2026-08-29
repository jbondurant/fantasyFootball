import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * The objective the rest of the repo has never had.
 *
 * Every engine here maximizes expected points. But six of twelve teams make
 * the playoffs, so points only matter through P(top six) - a point added to a
 * season already won buys nothing, and neither does a point added to a season
 * already lost. It does not matter whether you are the best team eliminated
 * or the worst one.
 *
 * This measures the conversion rate. It walks the league chain, pools every
 * completed team-season as a deviation from that season's league average
 * (which removes scoring inflation between years), then bootstraps: put a
 * focal team at deviation d, draw eleven opponents from the pooled history,
 * and count how often the focal team finishes in the top six. The slope of
 * that curve is what a marginal point is actually worth, and it is steepest
 * at the cutoff and flat in both tails - which is the whole point.
 *
 * This league also runs league_average_match, so each week scores twice: once
 * against an opponent and once against the league average. That suppresses
 * schedule luck, so points-rank tracks qualification far more closely than in
 * an ordinary head-to-head league. The agreement rate is reported rather than
 * assumed.
 *
 * Usage:
 *   ./gradlew run -Pmain=PlayoffOdds [-Pdraws=200000] [-Pdeltas=44]
 */
public class PlayoffOdds {

    record TeamSeason(String season, double points, int wins, double deviation){}

    static final int PLAYOFF_TEAMS = 6;
    static final int LEAGUE_SIZE = 12;

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int draws = Integer.getInteger("draws", 200_000);

        List<TeamSeason> pooled = new ArrayList<>();
        Map<String, List<TeamSeason>> bySeason = new TreeMap<>();
        Map<String, Double> seasonMean = new TreeMap<>();

        String leagueID = configuration.getLeagueJson().get("league_id").getAsString();
        while(leagueID != null){
            String leagueData = InOutUtilities.getCachedForever(
                    "https://api.sleeper.app/v1/league/" + leagueID,
                    "leagueChain" + leagueID);
            JsonObject league = JsonParser.parseString(leagueData).getAsJsonObject();
            String season = league.get("season").getAsString();
            String rosterData = InOutUtilities.getCachedForever(
                    "https://api.sleeper.app/v1/league/" + leagueID + "/rosters",
                    "rostersChain" + leagueID);
            List<TeamSeason> teams = new ArrayList<>();
            for(JsonElement element : JsonParser.parseString(rosterData).getAsJsonArray()){
                JsonObject settings = element.getAsJsonObject()
                        .getAsJsonObject("settings");
                if(settings == null || !settings.has("fpts")){
                    continue;
                }
                double points = settings.get("fpts").getAsDouble()
                        + (settings.has("fpts_decimal")
                           ? settings.get("fpts_decimal").getAsDouble() / 100.0 : 0);
                int wins = settings.has("wins") ? settings.get("wins").getAsInt() : 0;
                if(points > 0){
                    teams.add(new TeamSeason(season, points, wins, 0));
                }
            }
            if(teams.size() >= LEAGUE_SIZE){
                double mean = teams.stream().mapToDouble(TeamSeason::points)
                        .average().orElse(0);
                List<TeamSeason> scaled = new ArrayList<>();
                for(TeamSeason team : teams){
                    scaled.add(new TeamSeason(season, team.points(), team.wins(),
                            team.points() / mean - 1.0));
                }
                bySeason.put(season, scaled);
                seasonMean.put(season, mean);
                pooled.addAll(scaled);
            }
            JsonElement previous = league.get("previous_league_id");
            leagueID = previous == null || previous.isJsonNull()
                    || previous.getAsString().equals("0") ? null : previous.getAsString();
        }

        if(pooled.isEmpty()){
            System.out.println("no completed seasons on the chain");
            return;
        }

        System.out.printf("%d completed team-seasons across %d seasons%n%n",
                pooled.size(), bySeason.size());
        System.out.printf("%-8s %10s %12s %12s %10s%n", "SEASON", "avg pts",
                "6th (in)", "7th (out)", "the gap");
        for(Map.Entry<String, List<TeamSeason>> entry : bySeason.entrySet()){
            List<TeamSeason> sorted = new ArrayList<>(entry.getValue());
            sorted.sort(Comparator.comparingDouble(TeamSeason::points).reversed());
            System.out.printf("%-8s %10.1f %12.1f %12.1f %10.1f%n", entry.getKey(),
                    seasonMean.get(entry.getKey()), sorted.get(5).points(),
                    sorted.get(6).points(),
                    sorted.get(5).points() - sorted.get(6).points());
        }

        // Does scoring actually decide it? league_average_match says it should.
        int agree = 0;
        int counted = 0;
        for(List<TeamSeason> teams : bySeason.values()){
            List<TeamSeason> byPoints = new ArrayList<>(teams);
            byPoints.sort(Comparator.comparingDouble(TeamSeason::points).reversed());
            List<TeamSeason> byWins = new ArrayList<>(teams);
            byWins.sort(Comparator.comparingInt(TeamSeason::wins).reversed());
            for(int i = 0; i < PLAYOFF_TEAMS; i++){
                counted++;
                if(byWins.subList(0, PLAYOFF_TEAMS).contains(byPoints.get(i))){
                    agree++;
                }
            }
        }
        System.out.printf("%ntop-six by POINTS and top-six by WINS agree on %d of %d"
                + " seats (%.0f%%).%nleague_average_match is on, so scoring, not the"
                + " schedule, is what qualifies you.%n", agree, counted,
                100.0 * agree / counted);

        double currentAverage = seasonMean.values().stream()
                .mapToDouble(Double::doubleValue).average().orElse(1600);
        long seed = 90_210L;
        System.out.printf("%nP(top six) for a team scoring X relative to league average,"
                + "%nbootstrapped from %d drawn leagues (avg season = %.0f pts):%n%n",
                draws, currentAverage);
        System.out.printf("%12s %10s %12s %18s%n", "vs average", "P(top 6)",
                "per +10 pts", "");
        double[] offsets = {-0.15, -0.10, -0.05, -0.025, 0.0, 0.025, 0.05, 0.10, 0.15};
        for(double offset : offsets){
            // common random numbers: the same drawn leagues on both sides, so
            // the difference is the effect and not the noise.
            double probability = probabilityTopSix(pooled, offset, draws,
                    new Random(seed));
            double nudged = probabilityTopSix(pooled,
                    offset + 10.0 / currentAverage, draws, new Random(seed));
            double slope = nudged - probability;
            String bar = "#".repeat(Math.max(0, (int) Math.round(slope * 1000)));
            System.out.printf("%+11.0f %9.0f%% %11.1f%% %18s%n",
                    offset * currentAverage, 100 * probability, 100 * slope, bar);
        }

        String deltas = System.getProperty("deltas", "44");
        System.out.printf("%n%swhat a draft pick buys, at each starting position:%n",
                System.lineSeparator());
        System.out.printf("%12s %14s %14s %14s%n", "vs average", "P(top 6) now",
                "with +" + deltas, "gain");
        double gain = Double.parseDouble(deltas.split(",")[0]);
        for(double offset : new double[]{-0.10, -0.05, 0.0, 0.05, 0.10}){
            double before = probabilityTopSix(pooled, offset, draws, new Random(seed));
            double after = probabilityTopSix(pooled,
                    offset + gain / currentAverage, draws, new Random(seed));
            System.out.printf("%+11.0f %13.0f%% %13.0f%% %+13.1f%%%n",
                    offset * currentAverage, 100 * before, 100 * after,
                    100 * (after - before));
        }
        System.out.println("\na point is worth most at the cutoff and nearly nothing in"
                + " either tail.\nthat is the whole argument for ignoring seasons already"
                + " lost - but note\nhow wide the live band is when six of twelve"
                + " qualify.");
    }

    /** Draw eleven opponents from history; how often does the focal team finish top six? */
    static double probabilityTopSix(List<TeamSeason> pooled, double deviation,
                                    int draws, Random random){
        int made = 0;
        for(int draw = 0; draw < draws; draw++){
            int better = 0;
            for(int opponent = 0; opponent < LEAGUE_SIZE - 1; opponent++){
                if(pooled.get(random.nextInt(pooled.size())).deviation() > deviation){
                    better++;
                }
            }
            if(better < PLAYOFF_TEAMS){
                made++;
            }
        }
        return (double) made / draws;
    }
}
