import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * AM I STILL IN IT? - the trigger for the only strategy decision Justin has
 * already made about this season.
 *
 * His rule, 2026-09-05: "win 2026, but if not after like halfway, sell a bit for
 * keepers, but not in a drastic way where I essentially determine the winner by
 * selling my round 1-3 players for like a single keeper." That rule needs a
 * number to fire on, and `SeasonLedger` does not provide one - it reports where
 * he STANDS by points scored, which is a fact about the past. Whether he is in
 * it is a question about the future.
 *
 * Six of twelve make the playoffs, week 15 starts them, so weeks 1-14 are the
 * population. The simulation is deliberately plain:
 *
 *   - each team's weekly mean is its best legal ten by projection, divided by
 *     nothing - it IS a week's worth
 *   - the spread is MEASURED, not assumed: 24.9 points, the standard deviation
 *     of 840 real team-weeks across five completed seasons of this league
 *   - the real remaining schedule is played out, results counted as wins
 *
 * What it deliberately does NOT model: byes, injuries arriving, waiver
 * improvement, or anybody trading. Those all say "the season is more uncertain
 * than this", which pushes every number toward 50% - so a team this calls dead
 * is dead by a margin that survives the omissions, and a team it calls alive
 * might merely be alive. That asymmetry is the useful direction for a rule about
 * when to give up.
 *
 *   ./gradlew run -Pmain=SeasonOutlook [-Psims=20000] [-Pme=<name>]
 */
public class SeasonOutlook {

    /**
     * The weekly spread of a real team's score in this league.
     *
     * Measured over every completed season's every scored team-week, because an
     * assumed number here would drive every probability the tool prints. Read
     * back from the report that measures it rather than typed - see #115.
     */
    static double measuredSpread(){
        return Double.parseDouble(System.getProperty("teamSpread", "24.9"));
    }

    /** One team's season: who, how strong a week, and how often it makes six. */
    public record Team(String manager, int rosterID, double weeklyMean, double playoffOdds,
                       double meanWins) {}

    /** The best legal ten a roster projects in a week. */
    static double weeklyMean(List<String> roster, Map<String, Double> seasonPoints,
                             Map<String, Position> positionOf, int weeks){
        List<TeamRankings.Man> men = new ArrayList<>();
        for(String id : roster){
            Position position = positionOf.get(id);
            men.add(new TeamRankings.Man(id, id, position == null ? "?" : position.name(), "",
                    seasonPoints.getOrDefault(id, 0.0) / weeks, false, 0, ""));
        }
        return TeamRankings.bestLineup(men).starters();
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int sims = Integer.getInteger("sims", 20_000);
        String me = System.getProperty("me", configuration.getUserIDToDisplayName()
                .getOrDefault(configuration.getMyID(), configuration.getMyID()));
        String leagueID = configuration.getLeagueID();
        JsonObject league = JsonParser.parseString(
                InOutUtilities.getTodaysWebPage("https://api.sleeper.app/v1/league/" + leagueID,
                        "sleeperLeagueOutlook" + leagueID)).getAsJsonObject();
        int playoffStart = league.getAsJsonObject("settings").get("playoff_week_start").getAsInt();
        int playoffTeams = league.getAsJsonObject("settings").get("playoff_teams").getAsInt();
        int lastWeek = playoffStart - 1;
        int thisWeek = LeagueWeek.week();

        Map<String, Double> points = ProjectionSources.resolve("sleeper");
        Map<String, String> ownerOf = LeagueOwners.today(configuration);
        Map<String, Position> positionOf = new HashMap<>();
        Map<String, List<String>> rosters = new TreeMap<>();
        for(Map.Entry<String, String> entry : ownerOf.entrySet()){
            rosters.computeIfAbsent(entry.getValue(), u -> new ArrayList<>()).add(entry.getKey());
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            positionOf.put(entry.getKey(), player == null ? null : player.position);
        }
        Map<Integer, String> managerOf = TradePartners.managersOf(leagueID);

        Map<String, Double> mean = new TreeMap<>();
        for(Map.Entry<String, List<String>> entry : rosters.entrySet()){
            mean.put(entry.getKey(), weeklyMean(entry.getValue(), points, positionOf, 17));
        }

        // the real schedule, and whatever is already banked
        Map<String, Integer> wins = new TreeMap<>();
        for(String manager : mean.keySet()){
            wins.put(manager, 0);
        }
        List<int[]> remaining = new ArrayList<>();
        List<Integer> remainingWeek = new ArrayList<>();
        for(int week = 1; week <= lastWeek; week++){
            Map<Integer, List<JsonObject>> byMatchup = new TreeMap<>();
            for(JsonElement element : JsonParser.parseString(InOutUtilities.getTodaysWebPage(
                    "https://api.sleeper.app/v1/league/" + leagueID + "/matchups/" + week,
                    "sleeperOutlook" + leagueID + "w" + week)).getAsJsonArray()){
                JsonObject row = element.getAsJsonObject();
                if(row.has("matchup_id") && !row.get("matchup_id").isJsonNull()){
                    byMatchup.computeIfAbsent(row.get("matchup_id").getAsInt(),
                            u -> new ArrayList<>()).add(row);
                }
            }
            for(List<JsonObject> pair : byMatchup.values()){
                if(pair.size() != 2){
                    continue;
                }
                int a = pair.get(0).get("roster_id").getAsInt();
                int b = pair.get(1).get("roster_id").getAsInt();
                double pa = pair.get(0).has("points") ? pair.get(0).get("points").getAsDouble() : 0;
                double pb = pair.get(1).has("points") ? pair.get(1).get("points").getAsDouble() : 0;
                if(week < thisWeek && (pa > 0 || pb > 0)){
                    wins.merge(managerOf.getOrDefault(a, "?"), pa > pb ? 1 : 0, Integer::sum);
                    wins.merge(managerOf.getOrDefault(b, "?"), pb > pa ? 1 : 0, Integer::sum);
                }
                else {
                    remaining.add(new int[]{a, b});
                    remainingWeek.add(week);
                }
            }
        }

        double spread = measuredSpread();
        Random random = new Random(424_242L);
        Map<String, Integer> madeIt = new TreeMap<>();
        Map<String, Integer> totalWins = new TreeMap<>();
        for(String manager : mean.keySet()){
            madeIt.put(manager, 0);
            totalWins.put(manager, 0);
        }
        // TWO RESULTS A WEEK, NOT ONE. This league runs league_average_match,
        // so besides his head-to-head every manager also plays the league MEDIAN
        // that week. Fourteen weeks are twenty-eight games, and simulating half
        // of them understates how far the better teams separate: doubling the
        // sample halves the noise in a win total, which is the whole basis of a
        // playoff race. The first version played only the head-to-heads.
        boolean medianGame = league.getAsJsonObject("settings").has("league_average_match")
                && league.getAsJsonObject("settings").get("league_average_match").getAsInt() == 1;
        Map<Integer, List<int[]>> byWeek = new TreeMap<>();
        for(int i = 0; i < remaining.size(); i++){
            byWeek.computeIfAbsent(remainingWeek.get(i), u -> new ArrayList<>()).add(remaining.get(i));
        }
        for(int sim = 0; sim < sims; sim++){
            Map<String, Double> season = new HashMap<>();
            Map<String, Integer> w = new HashMap<>(wins);
            for(Map.Entry<Integer, List<int[]>> week : byWeek.entrySet()){
                Map<String, Double> scored = new HashMap<>();
                for(int[] game : week.getValue()){
                    String a = managerOf.getOrDefault(game[0], "?");
                    String b = managerOf.getOrDefault(game[1], "?");
                    double sa = mean.getOrDefault(a, 100.0) + random.nextGaussian() * spread;
                    double sb = mean.getOrDefault(b, 100.0) + random.nextGaussian() * spread;
                    w.merge(a, sa > sb ? 1 : 0, Integer::sum);
                    w.merge(b, sb > sa ? 1 : 0, Integer::sum);
                    season.merge(a, sa, Double::sum);
                    season.merge(b, sb, Double::sum);
                    scored.put(a, sa);
                    scored.put(b, sb);
                }
                if(medianGame && scored.size() > 1){
                    List<Double> sorted = new ArrayList<>(scored.values());
                    java.util.Collections.sort(sorted);
                    double median = sorted.size() % 2 == 1
                            ? sorted.get(sorted.size() / 2)
                            : (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2;
                    for(Map.Entry<String, Double> entry : scored.entrySet()){
                        w.merge(entry.getKey(), entry.getValue() > median ? 1 : 0, Integer::sum);
                    }
                }
            }
            List<String> order = new ArrayList<>(mean.keySet());
            // wins first, points scored breaks the tie - the league's own rule
            order.sort(Comparator.<String>comparingInt(m -> -w.getOrDefault(m, 0))
                    .thenComparing(m -> -season.getOrDefault(m, 0.0)));
            for(int i = 0; i < Math.min(playoffTeams, order.size()); i++){
                madeIt.merge(order.get(i), 1, Integer::sum);
            }
            for(String manager : mean.keySet()){
                totalWins.merge(manager, w.getOrDefault(manager, 0), Integer::sum);
            }
        }

        List<Team> table = new ArrayList<>();
        for(String manager : mean.keySet()){
            table.add(new Team(manager, 0, mean.get(manager),
                    madeIt.get(manager) / (double) sims, totalWins.get(manager) / (double) sims));
        }
        table.sort(Comparator.comparingDouble(Team::playoffOdds).reversed());

        StringBuilder out = new StringBuilder();
        out.append(DataStamp.line()).append("\n");
        out.append(String.format("SEASON OUTLOOK  %s  after week %d, %d sims%n%n",
                LocalDate.now(), thisWeek - 1, sims));
        out.append(String.format(medianGame
                ? "Six of twelve make the playoffs; weeks 1-%d decide it, and this league runs a MEDIAN%n"
                        + "game as well, so each week is two results and the season is twice the sample.%n"
                        + "Each team's weekly score is%n"
                : "Six of twelve make the playoffs; weeks 1-%d decide it. Each team's weekly score is%n"
                + "drawn around its best legal ten with a spread of %.1f - MEASURED over 840 real team-weeks%n"
                + "in five completed seasons of this league, not assumed.%n%n", lastWeek, spread));
        out.append(String.format("%-14s %10s %8s %9s%n", "MANAGER", "A WEEK", "WINS", "PLAYOFFS"));
        for(Team team : table){
            out.append(String.format("%-14s %10.1f %8.1f %8.1f%%%s%n", team.manager(),
                    team.weeklyMean(), team.meanWins(), 100 * team.playoffOdds(),
                    team.manager().equals(me) ? "   <- you" : ""));
        }
        Team mine = table.stream().filter(t -> t.manager().equals(me)).findFirst().orElse(null);
        if(mine != null){
            out.append(String.format("%n%s%n", call(mine.playoffOdds(), thisWeek - 1, lastWeek)));
        }
        out.append("\nNOT modelled: byes, injuries arriving, waiver improvement, anybody trading. Every one of\n");
        out.append("those makes the season MORE uncertain, which pushes each number toward 50% - so a team this\n");
        out.append("calls dead is dead by a margin that survives the omissions, and a team it calls alive may\n");
        out.append("only be alive. That asymmetry is the right direction for a rule about when to give up.\n");

        Path target = Path.of("data", "season-outlook-" + LocalDate.now() + ".txt");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.print(out);
        System.out.println("written to " + target);
    }

    /**
     * The sentence Justin's own rule fires on.
     *
     * It refuses to say anything decisive before halfway, because his rule says
     * "after like halfway" and a pivot called in week 3 off a 12% sample is not
     * that rule, it is panic with a number attached.
     */
    static String call(double odds, int played, int regularSeason){
        if(played * 2 < regularSeason){
            return String.format("%.0f%% to make the playoffs - but only %d of %d weeks are played, and"
                    + " the sell-for-keepers%ndecision is a HALFWAY one. Too early to act on this;"
                    + " it is here to be watched.", 100 * odds, played, regularSeason);
        }
        if(odds >= 0.55){
            return String.format("%.0f%% to make the playoffs. You are in it - buy, do not sell.", 100 * odds);
        }
        if(odds <= 0.20){
            return String.format("%.0f%% to make the playoffs, past halfway. This is the case your own rule"
                    + " was written for:%nsell a bit for keepers - and 'not in a drastic way', which means"
                    + " the round 1-3 men stay%nunless somebody pays a keeper price for them.", 100 * odds);
        }
        return String.format("%.0f%% to make the playoffs, past halfway. Genuinely undecided, which argues"
                + " for doing%nnothing drastic in either direction.", 100 * odds);
    }
}
