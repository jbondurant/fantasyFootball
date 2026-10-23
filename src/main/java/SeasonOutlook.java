import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
 *   - the spread is MEASURED, by this tool, every run: the within-team standard
 *     deviation of every real team-week in the league's completed seasons,
 *     printed with its n. An earlier version typed 24.9 and called it measured.
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

    /** The spread, measured: its value and the population it came from. */
    public record Spread(double value, int teamWeeks, int seasons){}

    /**
     * The within-team standard deviation of a real team's weekly score, pooled
     * over every completed season in the league chain.
     *
     * WITHIN-TEAM, because that is how the simulation applies it: each team's
     * week is drawn around ITS OWN mean. The pooled standard deviation over all
     * team-weeks also carries the spread BETWEEN teams and is the wrong number
     * by about a point. Until 2026-09-13 this was the typed literal 24.9 under a
     * comment that said it was read back from a measuring report; no such
     * report or tool existed, and every probability this prints rested on it.
     *
     * Completed seasons never change, so their matchups are cached forever; the
     * current season is excluded - it is the thing being predicted.
     */
    static Spread measureSpread(AAAConfiguration configuration){
        List<List<Double>> teamSeasons = new ArrayList<>();
        int seasons = 0;
        String leagueID = configuration.getPreviousLeagueID();
        int guard = 0;
        while(leagueID != null && guard++ < 8){
            JsonObject league = JsonParser.parseString(InOutUtilities.getCachedForever(
                    "https://api.sleeper.app/v1/league/" + leagueID,
                    "leagueChain" + leagueID)).getAsJsonObject();
            int lastWeek = league.getAsJsonObject("settings").get("playoff_week_start").getAsInt() - 1;
            Map<Integer, List<Double>> byRoster = new TreeMap<>();
            for(int week = 1; week <= lastWeek; week++){
                for(JsonElement e : JsonParser.parseString(InOutUtilities.getCachedForever(
                        "https://api.sleeper.app/v1/league/" + leagueID + "/matchups/" + week,
                        "sleeperMatchups" + leagueID + "w" + week)).getAsJsonArray()){
                    JsonObject row = e.getAsJsonObject();
                    double p = weekPoints(row);
                    if(p <= 0){
                        continue;                            // an unplayed week, not a zero
                    }
                    byRoster.computeIfAbsent(row.get("roster_id").getAsInt(),
                            u -> new ArrayList<>()).add(p);
                }
            }
            if(!byRoster.isEmpty()){
                seasons++;
                teamSeasons.addAll(byRoster.values());
            }
            leagueID = league.has("previous_league_id") && !league.get("previous_league_id").isJsonNull()
                    ? league.get("previous_league_id").getAsString() : null;
        }
        Spread measured = residualSpread(teamSeasons);
        return new Spread(measured.value(), measured.teamWeeks(), seasons);
    }

    /**
     * The arithmetic alone, so a test can pin it: one mean estimated per
     * team-season, and that many degrees of freedom taken off n.
     */
    static Spread residualSpread(Collection<List<Double>> teamSeasons){
        double ss = 0;
        int n = 0;
        int estimated = 0;
        for(List<Double> weeks : teamSeasons){
            if(weeks.isEmpty()){
                continue;
            }
            double mean = 0;
            for(double w : weeks){
                mean += w;
            }
            mean /= weeks.size();
            for(double w : weeks){
                ss += (w - mean) * (w - mean);
                n++;
            }
            estimated++;
        }
        int dof = n - estimated;
        return new Spread(dof > 0 ? Math.sqrt(ss / dof) : 0, n, 0);
    }

    /** The spread the simulation runs on: the measurement, unless -PteamSpread overrides it for a what-if. */
    static double measuredSpread(Spread measured){
        String override = System.getProperty("teamSpread");
        return override == null || override.isBlank() ? measured.value() : Double.parseDouble(override);
    }

    static String spreadProvenance(Spread measured, double used){
        String basis = String.format("the within-team standard deviation over %d real team-weeks in %d"
                + " completed seasons of this league", measured.teamWeeks(), measured.seasons());
        return Math.abs(used - measured.value()) < 1e-9
                ? "MEASURED here: " + basis
                : String.format("OVERRIDDEN by -PteamSpread=%.1f; measured %.1f, %s", used, measured.value(), basis);
    }

    static double weekPoints(JsonObject row){
        return row.has("points") && !row.get("points").isJsonNull() ? row.get("points").getAsDouble() : 0;
    }

    static double median(Collection<Double> values){
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    /**
     * Bank one finished week: every head-to-head, and - when the league plays
     * the median game - every score against the week's median. Returns the
     * week's scores by manager so the caller can seed the points tiebreak.
     *
     * 81e616c gave the SIMULATED weeks two results and left the banked loop at
     * one. From week 2 the real standings would read 2-0 / 1-1 / 0-2 while every
     * manager here was seeded with half his games, and by week 7 - the week the
     * buy/sell rule fires - each total would be missing up to seven median wins.
     */
    static Map<String, Double> bankWeek(List<JsonObject> rows, Map<Integer, String> managerOf,
                                        boolean medianGame, Map<String, Integer> wins){
        Map<Integer, List<JsonObject>> byMatchup = new TreeMap<>();
        Map<String, Double> scored = new TreeMap<>();
        for(JsonObject row : rows){
            if(row.has("matchup_id") && !row.get("matchup_id").isJsonNull()){
                byMatchup.computeIfAbsent(row.get("matchup_id").getAsInt(), u -> new ArrayList<>()).add(row);
            }
        }
        for(List<JsonObject> pair : byMatchup.values()){
            if(pair.size() != 2){
                continue;
            }
            String a = managerOf.getOrDefault(pair.get(0).get("roster_id").getAsInt(), "?");
            String b = managerOf.getOrDefault(pair.get(1).get("roster_id").getAsInt(), "?");
            double pa = weekPoints(pair.get(0));
            double pb = weekPoints(pair.get(1));
            wins.merge(a, pa > pb ? 1 : 0, Integer::sum);
            wins.merge(b, pb > pa ? 1 : 0, Integer::sum);
            scored.put(a, pa);
            scored.put(b, pb);
        }
        if(medianGame && scored.size() > 1){
            double median = median(scored.values());
            for(Map.Entry<String, Double> entry : scored.entrySet()){
                wins.merge(entry.getKey(), entry.getValue() > median ? 1 : 0, Integer::sum);
            }
        }
        return scored;
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
        // THE SAME ROSTERS THE MEANS COME FROM. TradePartners.managersOf caches a
        // league's users and rosters forever - right for a finished season, and
        // for the live one it froze 2026-09-07's copy while `mean` is keyed by
        // today's names: a rename or an owner change would give a team a silent
        // 100.0 default and zero banked wins.
        Map<Integer, String> managerOf = SeasonLedger.managerByRoster(configuration);

        Map<String, Double> mean = new TreeMap<>();
        for(Map.Entry<String, List<String>> entry : rosters.entrySet()){
            mean.put(entry.getKey(), weeklyMean(entry.getValue(), points, positionOf, 17));
        }

        for(String name : managerOf.values()){
            if(!mean.containsKey(name)){
                throw new IllegalStateException("the roster join names '" + name
                        + "' but the roster means do not - two reads of the league disagree");
            }
        }

        // TWO RESULTS A WEEK, NOT ONE. This league runs league_average_match,
        // so besides his head-to-head every manager also plays the league MEDIAN
        // that week. Fourteen weeks are twenty-eight games, and simulating half
        // of them understates how far the better teams separate: doubling the
        // sample halves the noise in a win total, which is the whole basis of a
        // playoff race. The first version played only the head-to-heads; the
        // second fixed the simulated weeks and not the banked ones.
        boolean medianGame = league.getAsJsonObject("settings").has("league_average_match")
                && league.getAsJsonObject("settings").get("league_average_match").getAsInt() == 1;

        // the real schedule, and whatever is already banked
        Map<String, Integer> wins = new TreeMap<>();
        for(String manager : mean.keySet()){
            wins.put(manager, 0);
        }
        List<int[]> remaining = new ArrayList<>();
        List<Integer> remainingWeek = new ArrayList<>();
        Map<String, Double> banked = new TreeMap<>();
        for(int week = 1; week <= lastWeek; week++){
            List<JsonObject> rows = new ArrayList<>();
            for(JsonElement element : JsonParser.parseString(InOutUtilities.getTodaysWebPage(
                    "https://api.sleeper.app/v1/league/" + leagueID + "/matchups/" + week,
                    "sleeperOutlook" + leagueID + "w" + week)).getAsJsonArray()){
                rows.add(element.getAsJsonObject());
            }
            boolean played = week < thisWeek && rows.stream().anyMatch(r -> weekPoints(r) > 0);
            if(played){
                for(Map.Entry<String, Double> entry : bankWeek(rows, managerOf, medianGame, wins).entrySet()){
                    banked.merge(entry.getKey(), entry.getValue(), Double::sum);
                }
                continue;
            }
            Map<Integer, List<JsonObject>> byMatchup = new TreeMap<>();
            for(JsonObject row : rows){
                if(row.has("matchup_id") && !row.get("matchup_id").isJsonNull()){
                    byMatchup.computeIfAbsent(row.get("matchup_id").getAsInt(),
                            u -> new ArrayList<>()).add(row);
                }
            }
            for(List<JsonObject> pair : byMatchup.values()){
                if(pair.size() != 2){
                    continue;
                }
                remaining.add(new int[]{pair.get(0).get("roster_id").getAsInt(),
                        pair.get(1).get("roster_id").getAsInt()});
                remainingWeek.add(week);
            }
        }

        Spread measured = measureSpread(configuration);
        double spread = measuredSpread(measured);
        Random random = new Random(424_242L);
        Map<String, Integer> madeIt = new TreeMap<>();
        Map<String, Integer> totalWins = new TreeMap<>();
        for(String manager : mean.keySet()){
            madeIt.put(manager, 0);
            totalWins.put(manager, 0);
        }
        Map<Integer, List<int[]>> byWeek = new TreeMap<>();
        for(int i = 0; i < remaining.size(); i++){
            byWeek.computeIfAbsent(remainingWeek.get(i), u -> new ArrayList<>()).add(remaining.get(i));
        }
        for(int sim = 0; sim < sims; sim++){
            Map<String, Double> season = new HashMap<>(banked);   // the tiebreak starts from real points
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
                    double median = median(scored.values());
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
        // two appends, because `+` binds tighter than `?:` and the first version's
        // spread sentence silently belonged to the no-median branch alone
        out.append(String.format(medianGame
                ? "Six of twelve make the playoffs; weeks 1-%d decide it, and this league runs a MEDIAN%n"
                        + "game as well, so each week is two results and the season is twice the sample.%n"
                : "Six of twelve make the playoffs; weeks 1-%d decide it.%n", lastWeek));
        out.append(String.format("Each team's weekly score is drawn around its best legal ten with a spread of %.1f%n"
                + "- %s.%n%n", spread, spreadProvenance(measured, spread)));
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
