import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * THE LINEUP THAT WINS THE WEEK, which is not always the one that scores most.
 *
 * Start/sit here picks the best ten by projection: the most points on average.
 * But a week is won against one opponent and the league median, not on average,
 * and the two differ in close calls: a team projected to lose should prefer the
 * starter with more upside, a team projected to win the one with less downside.
 * Justin, 2026-10-03, on the ideas serious tools use: "let's do all of what
 * you'd build."
 *
 * So this draws the week many times. Each man's score is his projection plus a
 * residual drawn from the 200 played player-weeks 2018-2025 whose Sleeper
 * projection at his position was nearest his (ScreenData's population, the one
 * ProjectionTiers calibrates), which carries each position's real spread and
 * skew. Every rival's lineup is what he has set in Sleeper; a man whose game has
 * kicked off scores what he scored and cannot move. Each candidate lineup - the
 * best by projection and every legal one-man change of it - is scored on the
 * SAME draws, by expected wins: P(beat the opponent) + P(beat the median).
 *
 * What it leaves out: correlation (a quarterback and his own receiver rise
 * together), defences and kickers (scored at their projection, no spread), and
 * any lineup change a rival makes after the read. In the regular season the
 * difference is usually small; it is largest in the single-elimination weeks.
 *
 *   ./gradlew run -Pmain=WinProbability
 */
public class WinProbability {

    static final int SIMS = 20_000;
    static final int NEAREST = 200;

    /** Historical (projection, outcome - projection) pairs at one position, sorted by projection. */
    record Residuals(double[] projected, double[] residual) {

        /** His projection plus a residual drawn from the `k` historical men projected nearest him. */
        double draw(double m, Random random, int k){
            int i = Arrays.binarySearch(projected, m);
            i = i < 0 ? -i - 1 : i;
            int lo = Math.max(0, Math.min(projected.length - k, i - k / 2));
            int hi = Math.min(projected.length, lo + k);
            return m + residual[lo + random.nextInt(hi - lo)];
        }
    }

    static Residuals residuals(List<double[]> pairs){
        pairs.sort(Comparator.comparingDouble(a -> a[0]));
        double[] p = new double[pairs.size()];
        double[] r = new double[pairs.size()];
        for(int i = 0; i < pairs.size(); i++){
            p[i] = pairs.get(i)[0];
            r[i] = pairs.get(i)[1] - pairs.get(i)[0];
        }
        return new Residuals(p, r);
    }

    /** Ten men who can fill QB RB RB WR WR WR TE FLEX FLEX DEF. */
    static boolean legal(List<String> starters, Map<String, Position> positionOf){
        if(starters.size() != 10){
            return false;
        }
        int qb = 0, rb = 0, wr = 0, te = 0, def = 0;
        for(String id : starters){
            Position p = positionOf.get(id);
            if(p == Position.QB) qb++;
            else if(p == Position.RB) rb++;
            else if(p == Position.WR) wr++;
            else if(p == Position.TE) te++;
            else if(p == Position.DEF) def++;
            else return false;
        }
        return qb == 1 && def == 1 && rb >= 2 && wr >= 3 && te >= 1 && rb + wr + te == 8;
    }

    /** The lineup and every legal lineup one change away from it, swapping only men whose games have not started. */
    static List<List<String>> candidates(List<String> best, List<String> bench, Set<String> locked, Map<String, Position> positionOf){
        List<List<String>> out = new ArrayList<>();
        out.add(best);
        for(String outMan : best){
            if(locked.contains(outMan)){
                continue;
            }
            for(String inMan : bench){
                if(locked.contains(inMan)){
                    continue;
                }
                List<String> c = new ArrayList<>(best);
                c.set(c.indexOf(outMan), inMan);
                if(legal(c, positionOf)){
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** Expected wins of each candidate on the same draws: {mean, standard error of its difference from candidate 0}. */
    static double[][] expectedWins(double[][] candidateScores, double[] opponent, double[][] others){
        int k = candidateScores.length, n = opponent.length;
        double[][] out = new double[k][2];
        double[] base = new double[n];
        for(int c = 0; c < k; c++){
            double sum = 0, dSum = 0, dSq = 0;
            for(int s = 0; s < n; s++){
                double mine = candidateScores[c][s];
                double[] week = new double[others.length + 2];
                week[0] = mine;
                week[1] = opponent[s];
                for(int o = 0; o < others.length; o++){
                    week[o + 2] = others[o][s];
                }
                double wins = (mine > opponent[s] ? 1 : 0) + (mine > TitleOdds.median(week) ? 1 : 0);
                if(c == 0){
                    base[s] = wins;
                }
                double d = wins - base[s];
                sum += wins;
                dSum += d;
                dSq += d * d;
            }
            double dMean = dSum / n;
            out[c][0] = sum / n;
            out[c][1] = Math.sqrt(Math.max(0, dSq / n - dMean * dMean) / n);
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        String season = LeagueWeek.season();
        int week = LeagueWeek.week();
        String leagueID = configuration.getLeagueID();
        Map<String, Double> projected = LeagueWeek.projected(season, week);
        Map<String, Double> actual = LeagueWeek.actualSoFar(season, week);
        Set<String> started = NflverseGames.kickedOff(NflverseGames.games(), season, week, LocalDateTime.now(ZoneId.of("America/New_York")));

        // the spread of each position, from history
        Map<String, ScreenData.Season> seasons = new TreeMap<>();
        List<String> which = new ArrayList<>();
        for(int y = 2018; y <= 2025; y++){
            seasons.put(String.valueOf(y), ScreenData.load(String.valueOf(y), true, true, scoring));
            which.add(String.valueOf(y));
        }
        Map<Position, List<double[]>> pairs = new HashMap<>();
        for(ScreenData.Row r : ScreenData.population(seasons, which, NflverseGames.sides(), 0.5, new ScreenData.Audit())){
            pairs.computeIfAbsent(r.position(), k -> new ArrayList<>()).add(new double[]{r.p, r.y});
        }
        Map<Position, Residuals> spread = new HashMap<>();
        pairs.forEach((p, list) -> spread.put(p, residuals(list)));

        // this week's matchups and every lineup as set
        Map<Integer, String> managerOf = SeasonLedger.managerByRoster(configuration);
        String me = configuration.getUserIDToDisplayName().getOrDefault(configuration.getMyID(), configuration.getMyID());
        Set<String> reserve = LeagueOwners.reserve(configuration);
        Map<String, List<String>> setLineup = new HashMap<>();
        Map<String, List<String>> held = new HashMap<>();
        Map<String, Integer> matchupOf = new HashMap<>();
        for(JsonElement element : JsonParser.parseString(LeagueWeek.matchups(leagueID, week)).getAsJsonArray()){
            JsonObject row = element.getAsJsonObject();
            String manager = managerOf.get(row.get("roster_id").getAsInt());
            if(manager == null){
                continue;
            }
            List<String> starters = new ArrayList<>();
            if(row.has("starters") && row.get("starters").isJsonArray()){
                row.getAsJsonArray("starters").forEach(e -> {
                    if(!e.isJsonNull() && !"0".equals(e.getAsString())){
                        starters.add(e.getAsString());
                    }
                });
            }
            List<String> players = new ArrayList<>();
            if(row.has("players") && row.get("players").isJsonArray()){
                row.getAsJsonArray("players").forEach(e -> {
                    if(!reserve.contains(e.getAsString())){
                        players.add(e.getAsString());
                    }
                });
            }
            setLineup.put(manager, starters);
            held.put(manager, players);
            if(row.has("matchup_id") && !row.get("matchup_id").isJsonNull()){
                matchupOf.put(manager, row.get("matchup_id").getAsInt());
            }
        }
        String opponent = null;
        for(Map.Entry<String, Integer> e : matchupOf.entrySet()){
            if(!e.getKey().equals(me) && e.getValue().equals(matchupOf.get(me))){
                opponent = e.getKey();
            }
        }
        if(opponent == null){
            throw new IllegalStateException("no opponent found for " + me + " in week " + week);
        }
        Map<String, Position> positionOf = new HashMap<>();
        Map<String, String> nameOf = new HashMap<>();
        Set<String> locked = new HashSet<>();
        for(List<String> list : held.values()){
            for(String id : list){
                Player p = Player.getPlayerFromSIDV2(id);
                positionOf.put(id, p == null ? null : p.position);
                nameOf.put(id, p == null ? id : p.firstName + " " + p.lastName);
                if(started.contains(ProjectionSources.teamOf(id))){
                    locked.add(id);
                }
            }
        }

        // my best lineup by projection, with locked starters kept and locked bench men out of reach
        List<String> mine = held.get(me);
        List<String> mySet = setLineup.getOrDefault(me, List.of());
        List<TeamRankings.Man> pool = new ArrayList<>();
        for(String id : mine){
            boolean lockedStarter = locked.contains(id) && mySet.contains(id);
            if(locked.contains(id) && !lockedStarter){
                continue;
            }
            Position p = positionOf.get(id);
            double value = lockedStarter ? 1_000 + actual.getOrDefault(id, 0.0) : projected.getOrDefault(id, 0.0);
            if(p != null && p != Position.OTHER){
                pool.add(new TeamRankings.Man(id, nameOf.get(id), p.name(), "", value, false, 0, null));
            }
        }
        List<String> best = new ArrayList<>();
        TeamRankings.bestLineup(pool).starting().forEach(m -> best.add(m.id()));
        List<String> bench = new ArrayList<>(mine);
        bench.removeAll(best);
        List<List<String>> candidates = candidates(best, bench, locked, positionOf);
        if(legal(mySet, positionOf) && !candidates.contains(mySet) && candidates.stream().noneMatch(c -> new HashSet<>(c).equals(new HashSet<>(mySet)))){
            candidates.add(mySet);
        }

        // the draws: every man who can score this week, once per season
        Set<String> everyone = new HashSet<>(mine);
        setLineup.forEach((m, l) -> everyone.addAll(l));
        List<String> ids = new ArrayList<>(everyone);
        Map<String, Integer> slot = new HashMap<>();
        for(int i = 0; i < ids.size(); i++){
            slot.put(ids.get(i), i);
        }
        Random random = new Random(424_242L);
        double[][] draw = new double[SIMS][ids.size()];
        for(int s = 0; s < SIMS; s++){
            for(int i = 0; i < ids.size(); i++){
                String id = ids.get(i);
                Position p = positionOf.get(id);
                double m = projected.getOrDefault(id, 0.0);
                draw[s][i] = locked.contains(id) ? actual.getOrDefault(id, 0.0)
                        : spread.containsKey(p) && projected.containsKey(id) ? spread.get(p).draw(m, random, NEAREST) : m;
            }
        }
        java.util.function.BiFunction<List<String>, Integer, Double> total = (lineup, s) -> {
            double sum = 0;
            for(String id : lineup){
                Integer i = slot.get(id);
                sum += i == null ? 0 : draw[s][i];
            }
            return sum;
        };
        double[] opp = new double[SIMS];
        List<String> rivals = new ArrayList<>(setLineup.keySet());
        rivals.remove(me);
        rivals.remove(opponent);
        double[][] others = new double[rivals.size()][SIMS];
        double[][] scores = new double[candidates.size()][SIMS];
        for(int s = 0; s < SIMS; s++){
            opp[s] = total.apply(setLineup.get(opponent), s);
            for(int o = 0; o < rivals.size(); o++){
                others[o][s] = total.apply(setLineup.get(rivals.get(o)), s);
            }
            for(int c = 0; c < candidates.size(); c++){
                scores[c][s] = total.apply(candidates.get(c), s);
            }
        }
        double[][] wins = expectedWins(scores, opp, others);

        Integer[] order = new Integer[candidates.size()];
        for(int i = 0; i < order.length; i++){
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble((Integer i) -> -wins[i][0]));
        StringBuilder out = new StringBuilder();
        out.append(DataStamp.line()).append('\n');
        out.append(String.format("WIN PROBABILITY  %s  week %d, %s v %s, %d weeks drawn%n", LocalDate.now(), week, me, opponent, SIMS));
        out.append(String.format("Expected wins this week = P(beat %s) + P(beat the league median). Each man is his projection plus a%n"
                + "residual from the %d nearest-projected player-weeks at his position, 2018-2025; men whose games have started%n"
                + "score what they scored. Rivals as set in Sleeper. '-> in/out' is the change from the best lineup by projection.%n%n",
                opponent, NEAREST));
        double baseProjection = 0;
        for(String id : best){
            baseProjection += locked.contains(id) ? actual.getOrDefault(id, 0.0) : projected.getOrDefault(id, 0.0);
        }
        out.append(String.format("%-46s %9s %8s %8s%n", "LINEUP", "proj", "E[wins]", "vs best"));
        for(int r = 0; r < Math.min(8, order.length); r++){
            int c = order[r];
            List<String> lineup = candidates.get(c);
            double proj = 0;
            for(String id : lineup){
                proj += locked.contains(id) ? actual.getOrDefault(id, 0.0) : projected.getOrDefault(id, 0.0);
            }
            String label = c == 0 ? "best by projection" : describe(best, lineup, nameOf);
            if(new HashSet<>(lineup).equals(new HashSet<>(mySet))){
                label += " (as set)";
            }
            out.append(String.format("%-46s %9.1f %8.3f %+8.3f%s%n", label, proj, wins[c][0], wins[c][0] - wins[0][0],
                    c == 0 ? "" : String.format(" +-%.3f", wins[c][1])));
        }
        int asSet = -1;
        for(int c = 0; c < candidates.size(); c++){
            if(new HashSet<>(candidates.get(c)).equals(new HashSet<>(mySet))){
                asSet = c;
            }
        }
        out.append(asSet < 0 ? String.format("%nYour lineup as set in Sleeper is not a legal ten (an empty slot?) - set it.%n")
                : String.format("%nYour lineup as set in Sleeper: E[wins] %.3f, %+.3f against the best by projection.%n",
                        wins[asSet][0], wins[asSet][0] - wins[0][0]));
        out.append(String.format("%nThe best by projection scores %.1f. A change is worth making when it beats it by more than twice its%n"
                + "standard error; anything smaller is the draws, not the lineup.%n", baseProjection));
        System.out.print(out);
        Path report = Path.of("data", "win-probability-" + season + "-w" + week + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + report);
    }

    static String describe(List<String> best, List<String> lineup, Map<String, String> nameOf){
        List<String> in = new ArrayList<>(lineup);
        in.removeAll(best);
        List<String> out = new ArrayList<>(best);
        out.removeAll(lineup);
        return "-> " + String.join(", ", in.stream().map(nameOf::get).toList()) + " / " + String.join(", ", out.stream().map(nameOf::get).toList());
    }
}
