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
 * THE TITLE, NOT THE POINTS.
 *
 * Every tool here scored a roster in season points: seventeen weeks of the best
 * legal lineup, each week weighted the same. A title in this league is not that.
 * Six of twelve make the playoffs; there is a median game, so each week is two
 * results; the top two seeds sit out the first round and need two wins where
 * seeds three to six need three; and weeks 15-17 are single elimination, so a
 * point in week 16 is worth many points in week 5. Justin, 2026-10-03, on
 * building what serious tools build: "let's do all of what you'd build."
 *
 * So this plays out the season that is left, week by week:
 *
 *   - each team's mean in a week is its best legal lineup on Sleeper's
 *     projection for THAT week, which already leaves out byes and men ruled out;
 *   - a week's score is drawn around that mean with the within-team spread
 *     SeasonOutlook measures from the league's real team-weeks;
 *   - played weeks are banked as they happened (head to head and median game);
 *   - the top six by wins (then points) are seeded, 3 v 6 and 4 v 5 in week 15,
 *     1 and 2 meet those winners in week 16 (a fixed bracket), the final in 17.
 *
 * A move is priced as the change in a manager's title odds. Both seasons are
 * drawn from the SAME random numbers - one draw per team per week, in a fixed
 * order - so the difference is the move and not the dice; its standard error is
 * computed from the paired seasons.
 *
 * What it does not model: injuries that have not happened yet (the season-points
 * objective draws those; this does not), waiver moves, or anyone else trading.
 * So it is the right yardstick for WHEN points matter, and the old one is still
 * the better one for how deep a bench should be.
 *
 *   ./gradlew run -Pmain=TitleOdds [-Psims=20000]
 */
public class TitleOdds {

    /** Draws a move is priced on. A title moves by fractions of a point; fewer seasons cannot see that. */
    static final int SIMS = 20_000;
    static final long SEED = 424_242L;

    /** The league as the simulation needs it. Managers are in a fixed order, which fixes the order of the draws. */
    public record League(String season, int thisWeek, int lastRegular, int playoffStart, int playoffTeams,
                         boolean medianGame, double spread, List<String> managers, Map<Integer, String> managerOf,
                         Map<String, Integer> wins, Map<String, Double> banked, Map<Integer, List<int[]>> schedule,
                         Map<String, double[]> live) {
        /**
         * THE WEEK IN PROGRESS (2026-10-05). Justin: "include week 4 results." On a
         * Monday Sleeper still names the week whose Monday game is unplayed, and the
         * week was played out from projections as if Sunday had not happened. `live`
         * holds each manager's {points his starters have scored in games that have
         * kicked off, projection of his starters still to play}: this week's mean is
         * their sum, and its spread is the measured spread scaled by the square root
         * of the share still to play, so a finished week is settled exactly.
         */
        double liveSpread(String manager){
            double[] l = live.get(manager);
            if(l == null){
                return spread;
            }
            double total = l[0] + l[1];
            return total <= 0 ? 0 : spread * Math.sqrt(Math.max(0, l[1]) / total);
        }
        int lastWeek(){
            return playoffStart + 2;
        }
        int weeks(){
            return lastWeek() - thisWeek + 1;
        }
    }

    /** What happened in each drawn season: the champion's index, and who made the playoffs and the bye, as bit masks. */
    public record Outcomes(List<String> managers, int[] champion, int[] playoffs, int[] byes) {
        int index(String manager){
            return managers.indexOf(manager);
        }
        public double title(String manager){
            int i = index(manager), n = 0;
            for(int c : champion){
                n += c == i ? 1 : 0;
            }
            return n / (double) champion.length;
        }
        public double playoffs(String manager){
            return share(playoffs, index(manager));
        }
        public double bye(String manager){
            return share(byes, index(manager));
        }
        static double share(int[] masks, int i){
            int n = 0;
            for(int m : masks){
                n += (m >> i) & 1;
            }
            return n / (double) masks.length;
        }
    }

    /** A paired change in title odds and its standard error. */
    public record Delta(double change, double se) {}

    /** The change in one manager's title odds between two seasons drawn from the same numbers. */
    static Delta title(Outcomes before, Outcomes after, String manager){
        int i = before.index(manager);
        int n = before.champion().length;
        double sum = 0, sq = 0;
        for(int s = 0; s < n; s++){
            int d = (after.champion()[s] == i ? 1 : 0) - (before.champion()[s] == i ? 1 : 0);
            sum += d;
            sq += d * d;
        }
        double mean = sum / n;
        return new Delta(mean, Math.sqrt(Math.max(0, sq / n - mean * mean) / n));
    }

    /** The season so far and the schedule left, from the league's own feeds (the reads SeasonOutlook uses). */
    static League league(AAAConfiguration configuration){
        String leagueID = configuration.getLeagueID();
        JsonObject settings = JsonParser.parseString(InOutUtilities.getTodaysWebPage(
                "https://api.sleeper.app/v1/league/" + leagueID, "sleeperLeagueOutlook" + leagueID))
                .getAsJsonObject().getAsJsonObject("settings");
        int playoffStart = settings.get("playoff_week_start").getAsInt();
        int playoffTeams = settings.get("playoff_teams").getAsInt();
        if(playoffTeams != 6){
            throw new IllegalStateException("the bracket here is the six-team one with two byes; this league has "
                    + playoffTeams + " playoff teams");
        }
        boolean medianGame = settings.has("league_average_match") && settings.get("league_average_match").getAsInt() == 1;
        int thisWeek = LeagueWeek.week();
        int lastRegular = playoffStart - 1;
        Map<Integer, String> managerOf = SeasonLedger.managerByRoster(configuration);
        List<String> managers = new ArrayList<>(new java.util.TreeSet<>(managerOf.values()));
        Map<String, Integer> wins = new TreeMap<>();
        for(String m : managers){
            wins.put(m, 0);
        }
        Map<String, Double> banked = new TreeMap<>();
        Map<Integer, List<int[]>> schedule = new TreeMap<>();
        for(int week = 1; week <= lastRegular; week++){
            List<JsonObject> rows = new ArrayList<>();
            for(JsonElement element : JsonParser.parseString(InOutUtilities.getTodaysWebPage(
                    "https://api.sleeper.app/v1/league/" + leagueID + "/matchups/" + week,
                    "sleeperOutlook" + leagueID + "w" + week)).getAsJsonArray()){
                rows.add(element.getAsJsonObject());
            }
            if(week < thisWeek && rows.stream().anyMatch(r -> SeasonOutlook.weekPoints(r) > 0)){
                SeasonOutlook.bankWeek(rows, managerOf, medianGame, wins).forEach((m, p) -> banked.merge(m, p, Double::sum));
                continue;
            }
            Map<Integer, List<JsonObject>> byMatchup = new TreeMap<>();
            for(JsonObject row : rows){
                if(row.has("matchup_id") && !row.get("matchup_id").isJsonNull()){
                    byMatchup.computeIfAbsent(row.get("matchup_id").getAsInt(), u -> new ArrayList<>()).add(row);
                }
            }
            for(List<JsonObject> pair : byMatchup.values()){
                if(pair.size() == 2){
                    schedule.computeIfAbsent(week, u -> new ArrayList<>()).add(new int[]{
                            pair.get(0).get("roster_id").getAsInt(), pair.get(1).get("roster_id").getAsInt()});
                }
            }
        }
        double spread = SeasonOutlook.measuredSpread(SeasonOutlook.measureSpread(configuration));
        return new League(LeagueWeek.season(), thisWeek, lastRegular, playoffStart, playoffTeams, medianGame, spread,
                managers, managerOf, wins, banked, schedule, live(leagueID, thisWeek, managerOf));
    }

    /** Each manager's {scored, still to play} this week, from the lineups as set and the games that have kicked off. */
    static Map<String, double[]> live(String leagueID, int week, Map<Integer, String> managerOf){
        String season = LeagueWeek.season();
        java.util.Set<String> started = NflverseGames.kickedOff(NflverseGames.games(), season, week,
                java.time.LocalDateTime.now(java.time.ZoneId.of("America/New_York")));
        if(started.isEmpty()){
            return Map.of();      // nothing has kicked off: the week is all projection, as before
        }
        Map<String, Double> projected = LeagueWeek.projected(season, week);
        Map<String, double[]> out = new HashMap<>();
        for(JsonElement element : JsonParser.parseString(LeagueWeek.matchups(leagueID, week)).getAsJsonArray()){
            JsonObject row = element.getAsJsonObject();
            String manager = managerOf.get(row.get("roster_id").getAsInt());
            if(manager == null || !row.has("starters") || !row.get("starters").isJsonArray()){
                continue;
            }
            JsonObject scored = row.has("players_points") && row.get("players_points").isJsonObject()
                    ? row.getAsJsonObject("players_points") : new JsonObject();
            double done = 0, left = 0;
            for(JsonElement e : row.getAsJsonArray("starters")){
                String id = e.isJsonNull() ? "0" : e.getAsString();
                if(id.equals("0")){
                    continue;      // an empty slot scores nothing
                }
                if(started.contains(ProjectionSources.teamOf(id))){
                    done += scored.has(id) && !scored.get(id).isJsonNull() ? scored.get(id).getAsDouble() : 0;
                }
                else{
                    left += projected.getOrDefault(id, 0.0);
                }
            }
            out.put(manager, new double[]{done, left});
        }
        return out;
    }

    /** Sleeper's projection for every week left, today's read of each. */
    static Map<Integer, Map<String, Double>> projections(League league){
        Map<Integer, Map<String, Double>> out = new TreeMap<>();
        for(int w = league.thisWeek(); w <= league.lastWeek(); w++){
            out.put(w, LeagueWeek.projected(league.season(), w));
        }
        return out;
    }

    /** One roster's best legal lineup on one week's projection: the men with a projection that week. */
    static double weekMean(List<String> roster, Map<String, Double> projection){
        List<TeamRankings.Man> men = new ArrayList<>();
        for(String id : roster){
            Double points = projection.get(id);
            Player p = points == null ? null : Player.getPlayerFromSIDV2(id);
            if(p != null && p.position != null && p.position != Position.OTHER){
                men.add(new TeamRankings.Man(id, id, p.position.name(), p.team, points, false, 0, null));
            }
        }
        return TeamRankings.bestLineup(men).starters();
    }

    /** Each manager's mean in every week left, index 0 being this week. */
    static Map<String, double[]> means(League league, Map<String, List<String>> rosters, Map<Integer, Map<String, Double>> projected){
        Map<String, double[]> out = new HashMap<>();
        for(Map.Entry<String, List<String>> e : rosters.entrySet()){
            out.put(e.getKey(), liveWeek(league, e.getKey(), teamMeans(league, e.getValue(), projected)));
        }
        return out;
    }

    /** This week's mean from the lineup as set when the week is under way - a roster change now cannot touch it. */
    static double[] liveWeek(League league, String manager, double[] m){
        double[] l = league.live().get(manager);
        if(l != null){
            m[0] = l[0] + l[1];
        }
        return m;
    }

    static double[] teamMeans(League league, List<String> roster, Map<Integer, Map<String, Double>> projected){
        double[] m = new double[league.weeks()];
        for(int w = league.thisWeek(); w <= league.lastWeek(); w++){
            m[w - league.thisWeek()] = weekMean(roster, projected.getOrDefault(w, Map.of()));
        }
        return m;
    }

    /** The same means with some managers' rosters replaced - a trade, a claim. */
    static Map<String, double[]> with(Map<String, double[]> base, League league, Map<String, List<String>> changed,
                                      Map<Integer, Map<String, Double>> projected){
        Map<String, double[]> out = new HashMap<>(base);
        changed.forEach((m, roster) -> out.put(m, liveWeek(league, m, teamMeans(league, roster, projected))));
        return out;
    }

    /**
     * Play the season left `sims` times from `seed`. One normal draw per manager
     * per week, always in manager order and always all twelve, so two calls with
     * the same seed see the same luck for every team in every week.
     */
    static Outcomes play(League league, Map<String, double[]> means, int sims, long seed){
        List<String> managers = league.managers();
        int n = managers.size();
        Map<String, Integer> indexOf = new HashMap<>();
        for(int i = 0; i < n; i++){
            indexOf.put(managers.get(i), i);
        }
        double[][] mean = new double[n][];
        for(int i = 0; i < n; i++){
            mean[i] = means.getOrDefault(managers.get(i), new double[league.weeks()]);
        }
        int[] baseWins = new int[n];
        double[] basePoints = new double[n];
        for(int i = 0; i < n; i++){
            baseWins[i] = league.wins().getOrDefault(managers.get(i), 0);
            basePoints[i] = league.banked().getOrDefault(managers.get(i), 0.0);
        }
        List<int[]>[] games = new List[league.weeks()];
        for(int w = league.thisWeek(); w <= league.lastRegular(); w++){
            List<int[]> list = new ArrayList<>();
            for(int[] g : league.schedule().getOrDefault(w, List.of())){
                Integer a = indexOf.get(league.managerOf().get(g[0]));
                Integer b = indexOf.get(league.managerOf().get(g[1]));
                if(a != null && b != null){
                    list.add(new int[]{a, b});
                }
            }
            games[w - league.thisWeek()] = list;
        }
        Random random = new Random(seed);
        int[] champion = new int[sims];
        int[] playoffs = new int[sims];
        int[] byes = new int[sims];
        double[] score = new double[n];
        int[] wins = new int[n];
        double[] points = new double[n];
        double spread = league.spread();
        double[] firstWeek = new double[n];
        for(int i = 0; i < n; i++){
            firstWeek[i] = league.liveSpread(managers.get(i));
        }
        for(int s = 0; s < sims; s++){
            System.arraycopy(baseWins, 0, wins, 0, n);
            System.arraycopy(basePoints, 0, points, 0, n);
            double[][] drawn = new double[league.weeks()][n];
            for(int w = 0; w < league.weeks(); w++){
                for(int i = 0; i < n; i++){
                    drawn[w][i] = mean[i][w] + (w == 0 ? firstWeek[i] : spread) * random.nextGaussian();
                }
            }
            for(int w = league.thisWeek(); w <= league.lastRegular(); w++){
                int k = w - league.thisWeek();
                System.arraycopy(drawn[k], 0, score, 0, n);
                for(int[] g : games[k]){
                    if(score[g[0]] > score[g[1]]){
                        wins[g[0]]++;
                    }
                    else if(score[g[1]] > score[g[0]]){
                        wins[g[1]]++;
                    }
                }
                for(int i = 0; i < n; i++){
                    points[i] += score[i];
                }
                if(league.medianGame()){
                    double median = median(score);
                    for(int i = 0; i < n; i++){
                        wins[i] += score[i] > median ? 1 : 0;
                    }
                }
            }
            Integer[] order = new Integer[n];
            for(int i = 0; i < n; i++){
                order[i] = i;
            }
            final int[] w0 = wins;
            final double[] p0 = points;
            java.util.Arrays.sort(order, Comparator.<Integer>comparingInt(i -> -w0[i]).thenComparingDouble(i -> -p0[i]));
            int mask = 0;
            for(int i = 0; i < 6; i++){
                mask |= 1 << order[i];
            }
            playoffs[s] = mask;
            byes[s] = (1 << order[0]) | (1 << order[1]);
            int r1 = league.playoffStart() - league.thisWeek();
            int threeSix = winner(order[2], order[5], drawn[r1]);
            int fourFive = winner(order[3], order[4], drawn[r1]);
            int semiA = winner(order[0], fourFive, drawn[r1 + 1]);
            int semiB = winner(order[1], threeSix, drawn[r1 + 1]);
            champion[s] = winner(semiA, semiB, drawn[r1 + 2]);
        }
        return new Outcomes(managers, champion, playoffs, byes);
    }

    /** The higher score; the first-named (higher) seed on a tie. */
    static int winner(int higherSeed, int lowerSeed, double[] week){
        return week[lowerSeed] > week[higherSeed] ? lowerSeed : higherSeed;
    }

    static double median(double[] values){
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    /**
     * How much one manager's title odds move per point of weekly mean, measured
     * by adding `bump` to every week he has left and playing the same seasons.
     * This is what a point of season value is worth to him: near zero for a team
     * out of it, largest for a contender.
     */
    static double sensitivity(League league, Map<String, double[]> means, Outcomes base, String manager, double bump){
        Map<String, double[]> bumped = new HashMap<>(means);
        double[] m = means.get(manager).clone();
        for(int w = 0; w < m.length; w++){
            m[w] += bump;
        }
        bumped.put(manager, m);
        return title(base, play(league, bumped, base.champion().length, SEED), manager).change() / bump;
    }

    /**
     * One simulation, kept for the trade and wire tools: the base seasons, and
     * for every manager how much a point of weekly mean is worth to him relative
     * to the league average (`weight`) and his playoff odds.
     */
    public record Stakes(League league, Map<Integer, Map<String, Double>> projected, Map<String, double[]> means,
                         Outcomes base, Map<String, Double> weight, Map<String, Double> playoffs) {

        /** The title-odds change for two managers when their rosters become these. */
        public Delta[] swap(String a, List<String> aAfter, String b, List<String> bAfter){
            Map<String, List<String>> changed = new HashMap<>();
            changed.put(a, aAfter);
            changed.put(b, bAfter);
            Outcomes after = play(league, with(means, league, changed, projected), base.champion().length, SEED);
            return new Delta[]{TitleOdds.title(base, after, a), TitleOdds.title(base, after, b)};
        }

        /** The title-odds change for one manager whose roster becomes this. */
        public Delta one(String a, List<String> aAfter){
            Outcomes after = play(league, with(means, league, Map.of(a, aAfter), projected), base.champion().length, SEED);
            return TitleOdds.title(base, after, a);
        }

        public double title(String manager){
            return base.title(manager);
        }

        /** A rival's season, said in a word: presentation only, nothing is decided on it. */
        public String state(String manager){
            double p = playoffs.getOrDefault(manager, 0.0);
            return p >= 0.6 ? "contender" : p >= 0.25 ? "bubble" : "out of it";
        }
    }

    static Stakes stakes(AAAConfiguration configuration, int sims){
        League league = league(configuration);
        Map<Integer, Map<String, Double>> projected = projections(league);
        Map<String, double[]> means = means(league, rosters(configuration), projected);
        Outcomes base = play(league, means, sims, SEED);
        Map<String, Double> raw = new HashMap<>();
        double total = 0;
        for(String m : league.managers()){
            double s = Math.max(0, sensitivity(league, means, base, m, 3.0));
            raw.put(m, s);
            total += s;
        }
        double average = total / league.managers().size();
        Map<String, Double> weight = new HashMap<>();
        Map<String, Double> playoffs = new HashMap<>();
        for(String m : league.managers()){
            weight.put(m, average <= 0 ? 1.0 : raw.get(m) / average);
            playoffs.put(m, base.playoffs(m));
        }
        return new Stakes(league, projected, means, base, weight, playoffs);
    }

    /** Every manager's roster: every man held, IR slots included - a man on IR now is projected back when he is. */
    static Map<String, List<String>> rosters(AAAConfiguration configuration){
        Map<String, List<String>> out = new TreeMap<>();
        LeagueOwners.today(configuration).forEach((id, m) -> out.computeIfAbsent(m, k -> new ArrayList<>()).add(id));
        return out;
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int sims = Integer.getInteger("sims", SIMS);
        String me = configuration.getUserIDToDisplayName().getOrDefault(configuration.getMyID(), configuration.getMyID());
        League league = league(configuration);
        Map<Integer, Map<String, Double>> projected = projections(league);
        Map<String, double[]> means = means(league, rosters(configuration), projected);
        Outcomes base = play(league, means, sims, SEED);
        StringBuilder out = new StringBuilder();
        out.append(DataStamp.line()).append('\n');
        out.append(String.format("TITLE ODDS  %s  season %s from week %d, %d seasons drawn%n", LocalDate.now(), league.season(),
                league.thisWeek(), sims));
        if(!league.live().isEmpty()){
            out.append(String.format("Week %d is under way: each team's week is what its starters have scored in games that have kicked off,%n"
                    + "plus the projection of starters still to play, with the spread shrunk to the share still to play.%n", league.thisWeek()));
        }
        out.append(String.format("Weeks %d-%d played out on Sleeper's projection for each week (byes and men ruled out leave the lineup),%n"
                + "spread %.1f a week (SeasonOutlook's measurement)%s; six make it, seeds 1-2 skip week %d, a fixed bracket after.%n",
                league.thisWeek(), league.lastWeek(), league.spread(), league.medianGame() ? ", a median game every regular week" : "",
                league.playoffStart()));
        out.append("'per pt' is how much his title odds move per point of weekly mean added to every week he has left.\n\n");
        // THE RECORD AS SLEEPER KEEPS IT, and how strong each team projects from here.
        // Justin, 2026-10-06: "I don't think i have that much ahead of jake and d0ddi,
        // if I'm even ahead." He was not: 5-3 with both, fourth on points. The odds
        // still favoured him, and the reason was not on the page - it is the weeks
        // to come, so the table now shows the record and the projection beside them.
        Map<String, String> record = new HashMap<>();
        Map<String, Double> pointsFor = new HashMap<>();
        for(JsonElement element : JsonParser.parseString(configuration.getTodaysRosterWebPageSerious()).getAsJsonArray()){
            JsonObject roster = element.getAsJsonObject();
            String m = league.managerOf().get(roster.get("roster_id").getAsInt());
            JsonObject s = roster.getAsJsonObject("settings");
            if(m != null && s != null){
                record.put(m, s.get("wins").getAsInt() + "-" + s.get("losses").getAsInt());
                pointsFor.put(m, s.get("fpts").getAsDouble() + (s.has("fpts_decimal") ? s.get("fpts_decimal").getAsDouble() / 100 : 0));
            }
        }
        int firstFuture = league.live().isEmpty() ? 0 : 1;
        int lastRegularIndex = league.lastRegular() - league.thisWeek();
        // each manager's average projected weekly mean in the playoff weeks, and the
        // average AHEAD of the opponents he still has to play in the regular season
        Map<String, Double> ahead = new HashMap<>();
        Map<String, Double> playoffWeeks = new HashMap<>();
        for(String m : league.managers()){
            double a = 0, p = 0;
            int n = 0;
            for(int k = firstFuture; k <= lastRegularIndex; k++){
                a += means.get(m)[k];
                n++;
            }
            for(int k = lastRegularIndex + 1; k < league.weeks(); k++){
                p += means.get(m)[k];
            }
            ahead.put(m, n == 0 ? 0 : a / n);
            playoffWeeks.put(m, p / Math.max(1, league.weeks() - lastRegularIndex - 1));
        }
        Map<String, double[]> schedule = new HashMap<>();
        for(int w = league.thisWeek() + firstFuture; w <= league.lastRegular(); w++){
            for(int[] g : league.schedule().getOrDefault(w, List.of())){
                String a = league.managerOf().get(g[0]), b = league.managerOf().get(g[1]);
                if(a != null && b != null){
                    schedule.computeIfAbsent(a, k -> new double[2])[0] += ahead.getOrDefault(b, 0.0);
                    schedule.get(a)[1]++;
                    schedule.computeIfAbsent(b, k -> new double[2])[0] += ahead.getOrDefault(a, 0.0);
                    schedule.get(b)[1]++;
                }
            }
        }
        out.append(String.format("RECORD and PF are Sleeper's own standings (median game included). AHEAD is each team's projected weekly%n"
                + "score over the regular-season weeks still to start (%d-%d), P-WKS the same over the playoff weeks (%d-%d),%n"
                + "OPP the average AHEAD of the opponents he still has to play, and LOW his weakest week ahead (byes): the%n"
                + "odds are made of these and how they fall week by week - one bad bye week costs one game, an even sag costs several.%n%n",
                league.thisWeek() + firstFuture, league.lastRegular(), league.playoffStart(), league.lastWeek()));
        out.append(String.format("%-14s %6s %7s %7s %7s %7s %10s %8s %7s %7s %10s%n", "MANAGER", "RECORD", "PF", "AHEAD", "P-WKS", "OPP",
                "LOW (wk)", "PLAYOFF", "BYE", "TITLE", "per pt"));
        List<String> order = new ArrayList<>(league.managers());
        order.sort(Comparator.comparingDouble((String m) -> -base.title(m)));
        for(String m : order){
            double perPoint = sensitivity(league, means, base, m, 3.0);
            double[] opp = schedule.getOrDefault(m, new double[]{0, 1});
            int low = firstFuture;
            for(int k = firstFuture; k <= lastRegularIndex; k++){
                if(means.get(m)[k] < means.get(m)[low]){
                    low = k;
                }
            }
            out.append(String.format("%-14s %6s %7.1f %7.1f %7.1f %7.1f %6.1f (%d) %7.1f%% %6.1f%% %6.1f%% %+9.2fpp%s%n", m, record.getOrDefault(m, "-"),
                    pointsFor.getOrDefault(m, 0.0), ahead.get(m), playoffWeeks.get(m), opp[0] / Math.max(1, opp[1]),
                    means.get(m)[low], league.thisWeek() + low,
                    100 * base.playoffs(m), 100 * base.bye(m), 100 * base.title(m), 100 * perPoint,
                    m.equals(me) ? "   <- you" : ""));
        }
        out.append("\nNot modelled: injuries yet to happen, waiver moves, other trades. A team this calls out of it has\n");
        out.append("less upside than the dice here give it only if nobody around it gets hurt.\n");
        System.out.print(out);
        Path report = Path.of("data", "title-odds-" + LocalDate.now() + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + report);
    }
}
