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

        String outlook = System.getProperty("outlook");
        if(outlook != null){
            seatOdds(pooled, outlook, currentAverage, draws, seed);
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

    /**
     * Every seat's playoff odds, from a LeagueOutlook table.
     *
     * A draft-day best-nine projection is not a season total: it knows nothing
     * about waivers, byes, weekly start/sit or luck. So the spread across seats
     * here is much narrower than the spread across real finished seasons, and
     * reading a projection straight off the historical curve would pretend the
     * draft settles the season.
     *
     * Instead, decompose. History gives the variance of finished seasons; the
     * outlook table gives the variance the draft explains. What is left is
     * in-season noise, and every team gets an independent draw of it on top of
     * its projection. Then simulate the whole twelve-team league forward and
     * count top-six finishes.
     */
    static void seatOdds(List<TeamSeason> pooled, String path, double currentAverage,
                         int draws, long seed){
        List<String> names = new ArrayList<>();
        List<Double> projected = new ArrayList<>();
        java.util.regex.Pattern row = java.util.regex.Pattern.compile(
                "^\\s*\\d+\\s+(\\S+)\\s+\\d+\\s+(\\d+\\.\\d+)\\s+(\\d+\\.\\d+)");
        try {
            for(String line : java.nio.file.Files.readAllLines(
                    java.nio.file.Path.of(path))){
                java.util.regex.Matcher matcher = row.matcher(line);
                if(matcher.find()){
                    names.add(matcher.group(1));
                    projected.add(Double.parseDouble(matcher.group(2)));
                }
            }
        }
        catch(Exception unreadable){
            System.out.println("could not read " + path + ": " + unreadable.getMessage());
            return;
        }
        if(names.size() != LEAGUE_SIZE){
            System.out.printf("%nexpected %d seats in %s, found %d - skipping seat odds%n",
                    LEAGUE_SIZE, path, names.size());
            return;
        }

        double projectedMean = projected.stream()
                .mapToDouble(Double::doubleValue).average().orElse(1);
        double[] deviation = new double[LEAGUE_SIZE];
        for(int i = 0; i < LEAGUE_SIZE; i++){
            deviation[i] = projected.get(i) / projectedMean - 1.0;
        }
        double draftVariance = 0;
        for(double d : deviation){
            draftVariance += d * d;
        }
        draftVariance /= LEAGUE_SIZE - 1;
        double outcomeVariance = 0;
        for(TeamSeason team : pooled){
            outcomeVariance += team.deviation() * team.deviation();
        }
        outcomeVariance /= pooled.size() - 1;
        double noise = Math.sqrt(Math.max(0, outcomeVariance - draftVariance));

        System.out.printf("%n%nPROJECTED PLAYOFF ODDS  (from %s)%n", path);
        System.out.printf("draft explains sd %.1f%% of a season; finished seasons vary"
                + " sd %.1f%%.%nso %.1f%% is in-season noise - waivers, byes, injuries,"
                + " luck - and every%nteam gets an independent draw of it.%n%n",
                100 * Math.sqrt(draftVariance), 100 * Math.sqrt(outcomeVariance),
                100 * noise);

        Random random = new Random(seed);
        int[] made = new int[LEAGUE_SIZE];
        double[] outcome = new double[LEAGUE_SIZE];
        for(int draw = 0; draw < draws; draw++){
            for(int i = 0; i < LEAGUE_SIZE; i++){
                outcome[i] = deviation[i] + random.nextGaussian() * noise;
            }
            for(int i = 0; i < LEAGUE_SIZE; i++){
                int better = 0;
                for(int j = 0; j < LEAGUE_SIZE; j++){
                    if(j != i && outcome[j] > outcome[i]){
                        better++;
                    }
                }
                if(better < PLAYOFF_TEAMS){
                    made[i]++;
                }
            }
        }
        Integer[] order = new Integer[LEAGUE_SIZE];
        for(int i = 0; i < LEAGUE_SIZE; i++){
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(made[b], made[a]));
        System.out.printf("%-16s %10s %12s %12s%n", "MANAGER", "best-9",
                "vs average", "P(top 6)");
        for(int i : order){
            System.out.printf("%-16s %10.1f %+11.0f %11.0f%%%n", names.get(i),
                    projected.get(i), deviation[i] * currentAverage,
                    100.0 * made[i] / draws);
        }
        System.out.println("\nthe draft explains only a fraction of the variance, so"
                + " even the top seat\nis far from safe and the bottom seat is far"
                + " from out.");
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
