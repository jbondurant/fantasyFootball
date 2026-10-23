import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * HOW LONG DRAFT POSITION HOLDS OUT, measured on thousands of other leagues'
 * accepted trades, one estimate per number of games played.
 *
 * TradeScreens reads a man as the other manager sees him: part draft value
 * (what a man drafted where he was is worth, on average), part this season's
 * pace. How much of each was a single number k fitted to this league's sixty
 * trade sides - draft value counting as k games - which forces the weight on
 * draft to fall as k/(k+g) whatever the data say. Justin, 2026-10-22: "the
 * draft value should barely matter after like game 4, it's just that before
 * then, people are unsure about what's a new normal, and what's just a
 * aberrant fluke", and his guess, "at least like 40% after week 1, but less
 * than 10% after week 5". Then: "no. try to make a curve that's not so wishy
 * washy. I don't mind downloading data".
 *
 * So for each number of games played g, on its own: every accepted
 * player-for-player trade made after exactly g weeks of games (OtherLeagues
 * harvests them), both sides priced on the two screens TradeScreens uses -
 * draft value off this repo's chart from that season's ADP, pace from that
 * season's games so far read off the pace chart (TradeScreens.paceChart: what
 * that pace went on to be worth), in this league's scoring - and the weight w on draft
 * that makes those trades read most even. Even means the least mean
 * |A - B| / (A + B) between what the two sides received at weight w: zero for
 * a trade both sides priced the same, one for a trade where one side got
 * nothing. Bounded, so a trade with nothing on one screen (an undrafted
 * breakout, a first-rounder who has not played) counts rather than being
 * dropped as an infinite ratio - and those are exactly the trades that tell
 * the two screens apart.
 *
 * Games played is counted from the clock, not from Sleeper's leg: a trade
 * proposed on the Tuesday after week 3's games carries leg 3 but was made
 * with three weeks on the board. The count is the weeks whose last game had
 * ended (kickoff plus four hours, nflverse's schedule) when it was proposed.
 *
 * Checks printed with it, because a minimum can be found in anything:
 *   - SHUFFLED: the same sides paired at random within the week. If real
 *     trades are not far more even than random pairs, the weight is fitting
 *     noise.
 *   - INTERVAL: a 90% range for each week's weight, resampling whole leagues
 *     (a league's trades are made by the same dozen people).
 *   - SPLITS: the weight in redraft and keeper leagues separately, and in
 *     half- and full-PPR leagues.
 * The curve TradeScreens reads is each week's weight forced never to rise
 * with games played (pool-adjacent violators, weighted by trades), because
 * no manager trusts a draft slot more after seeing more games.
 *
 *   ./gradlew run -Pmain=DraftWeightCurve
 */
public class DraftWeightCurve {

    static final Path TABLE = Path.of("data", "draft-weight.tsv");
    static final Path REPORT = Path.of("data", "draft-weight.txt");
    static final ZoneId EASTERN = ZoneId.of("America/New_York");
    /** Weeks pooled at the tail: from here on, one bin. */
    static final int LAST_BIN = 12;
    static final int GRID = 100;

    /** One trade from side A: what A received and what B received, on both screens. */
    record Trade(String season, String league, int type, double rec, int games,
                 double draftA, double draftB, double paceA, double paceB, boolean samePosition, boolean allProducing,
                 String gotA, String gotB, double projA, double projB, boolean allProjected) {

        Trade(String season, String league, int type, double rec, int games,
              double draftA, double draftB, double paceA, double paceB){
            this(season, league, type, rec, games, draftA, draftB, paceA, paceB, false, false, "", "", 0, 0, false);
        }

        /** What A received at weights (draft, pace) and the rest on Sleeper's projection. */
        double a3(double wd, double wp){
            return wd * draftA + wp * paceA + (1 - wd - wp) * projA;
        }
        double b3(double wd, double wp){
            return wd * draftB + wp * paceB + (1 - wd - wp) * projB;
        }
        double uneven3(double wd, double wp){
            double a = a3(wd, wp), b = b3(wd, wp);
            return a + b <= 0 ? 0 : Math.abs(a - b) / (a + b);
        }
        /** Value on all three screens. */
        boolean informs3(){
            return informs() && projA + projB >= TradeScreens.NOTHING;
        }

        double a(double w){
            return w * draftA + (1 - w) * paceA;
        }
        double b(double w){
            return w * draftB + (1 - w) * paceB;
        }
        /** |A - B| / (A + B) at draft weight w: 0 even, 1 all one way. */
        double uneven(double w){
            double total = a(w) + b(w);
            return total <= 0 ? 0 : Math.abs(a(w) - b(w)) / total;
        }
        /** Value on both screens, so the trade can say something about the weight between them. */
        boolean informs(){
            return draftA + draftB >= TradeScreens.NOTHING && paceA + paceB >= TradeScreens.NOTHING;
        }
    }

    /** When each week's games were all over, Eastern: last kickoff plus four hours. */
    static Map<String, TreeMap<Integer, LocalDateTime>> weekEnds(List<NflverseGames.Game> games){
        Map<String, TreeMap<Integer, LocalDateTime>> out = new HashMap<>();
        for(NflverseGames.Game g : games){
            if(g.gameday() == null || g.gameday().isBlank()){
                continue;
            }
            LocalDateTime end = LocalDateTime.parse(g.gameday() + "T"
                    + (g.gametime() == null || g.gametime().isBlank() ? "13:00" : g.gametime())).plusHours(4);
            out.computeIfAbsent(g.season(), k -> new TreeMap<>()).merge(g.week(), end, (x, y) -> x.isAfter(y) ? x : y);
        }
        return out;
    }

    /** Weeks whose games were all over at this moment. */
    static int gamesPlayed(TreeMap<Integer, LocalDateTime> ends, long epochMillis){
        LocalDateTime at = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), EASTERN);
        int n = 0;
        for(Map.Entry<Integer, LocalDateTime> e : ends.entrySet()){
            if(!at.isBefore(e.getValue())){
                n = Math.max(n, e.getKey());
            }
        }
        return n;
    }

    /**
     * A trade's two sides: what each roster received from the other. Null for
     * anything the screens cannot price - more than two rosters, a pick or FAAB
     * on either side, a side that received nothing, a kicker or defence moved.
     * A man one side cut to make room is not part of the trade and is left out.
     */
    static List<List<String>> sides(JsonObject row, Map<String, Position> positionOf){
        List<Integer> rosters = LeagueTransactions.rosterIDs(row);
        if(rosters.size() != 2 || TradeScreens.nonEmpty(row, "draft_picks") || TradeScreens.nonEmpty(row, "waiver_budget")){
            return null;
        }
        Map<Integer, List<String>> in = TradeScreens.byRoster(row, "adds");
        JsonElement drops = row.get("drops");
        List<String> toA = new ArrayList<>(), toB = new ArrayList<>();
        int a = rosters.get(0), b = rosters.get(1);
        for(int to : List.of(a, b)){
            for(String id : in.getOrDefault(to, List.of())){
                JsonElement from = drops != null && drops.isJsonObject() ? drops.getAsJsonObject().get(id) : null;
                if(from == null || from.isJsonNull() || from.getAsInt() != (to == a ? b : a)){
                    return null;            // came from nowhere in this trade: a third roster
                }
                if(!TradeScreens.skill(positionOf.get(id))){
                    return null;
                }
                (to == a ? toA : toB).add(id);
            }
        }
        return toA.isEmpty() || toB.isEmpty() ? null : List.of(toA, toB);
    }

    /** Every harvested trade priced on both screens. */
    static List<Trade> trades(TradeScreens.Chart chart, Map<String, EraBoards.Board> boards, int teams, int flex) throws IOException {
        return trades(chart, boards, teams, flex, null, null);
    }

    static List<Trade> trades(TradeScreens.Chart chart, Map<String, EraBoards.Board> boards, int teams, int flex,
                              java.util.function.IntFunction<TradeScreens.Chart> paceCharts) throws IOException {
        return trades(chart, boards, teams, flex, paceCharts, null);
    }

    /** The same, with each man's pace read off the pace chart for his games ({@link #paceChart}) when one is given. */
    static List<Trade> trades(TradeScreens.Chart chart, Map<String, EraBoards.Board> boards, int teams, int flex,
                              java.util.function.IntFunction<TradeScreens.Chart> paceCharts,
                              java.util.function.IntFunction<TradeScreens.Chart> projectionCharts) throws IOException {
        return trades(chart, boards, teams, flex, paceCharts, projectionCharts, 1);
    }

    static List<Trade> trades(TradeScreens.Chart chart, Map<String, EraBoards.Board> boards, int teams, int flex,
                              java.util.function.IntFunction<TradeScreens.Chart> paceCharts,
                              java.util.function.IntFunction<TradeScreens.Chart> projectionCharts, int span) throws IOException {
        Map<String, Map<Integer, Map<String, Double>>> projected = new HashMap<>();
        Map<String, TreeMap<Integer, LocalDateTime>> ends = weekEnds(NflverseGames.games());
        Map<String, Map<String, Position>> positions = new HashMap<>();
        Map<String, Map<String, String>> names = new HashMap<>();
        Map<String, Map<Integer, Map<String, Double>>> pace = new HashMap<>();
        Map<String, Map<Integer, Map<String, Double>>> above = new HashMap<>();
        List<Trade> out = new ArrayList<>();
        for(OtherLeagues.League league : OtherLeagues.manifest()){
            EraBoards.Board board = boards.get(league.season());
            if(board == null || !ends.containsKey(league.season())){
                continue;
            }
            Map<String, Position> positionOf = positions.computeIfAbsent(league.season(), TradeScreens::positions);
            Map<String, String> nameOf = names.computeIfAbsent(league.season(), TradeScreens::names);
            for(JsonElement element : OtherLeagues.cachedTrades(league)){
                JsonObject row = element.getAsJsonObject();
                List<List<String>> two = sides(row, positionOf);
                if(two == null || !row.has("created")){
                    continue;
                }
                int g = Math.min(gamesPlayed(ends.get(league.season()), row.get("created").getAsLong()), board.weekly().size());
                if(g == 0){
                    continue;                // before any game, pace does not exist
                }
                Map<String, Double> production = pace.computeIfAbsent(league.season(), k -> new HashMap<>())
                        .computeIfAbsent(g, k -> TradeScreens.production(TradeScreens.soFar(board.weekly().subList(0, k)),
                                positionOf, k, teams, flex, Set.of()));
                java.util.function.ToDoubleFunction<String> draft = id -> chart.at(board.adp().getOrDefault(id, 999.0));
                java.util.function.ToDoubleFunction<String> raw = id -> production.getOrDefault(id, 0.0);
                java.util.function.ToDoubleFunction<String> points = raw;
                if(paceCharts != null){
                    TradeScreens.Chart read = paceCharts.apply(g);
                    Map<String, Double> x = above.computeIfAbsent(league.season(), k -> new HashMap<>()).computeIfAbsent(g,
                            k -> TradeScreens.aboveLine(TradeScreens.soFar(board.weekly().subList(0, k)), positionOf, k, teams, flex));
                    points = id -> x.containsKey(id) ? read.at(-x.get(id)) : 0;
                }
                Set<Position> moved = new java.util.HashSet<>();
                two.forEach(side -> side.forEach(id -> moved.add(positionOf.get(id))));
                java.util.function.ToDoubleFunction<String> projection = id -> 0;
                java.util.function.Predicate<String> isProjected = id -> false;
                if(projectionCharts != null){
                    Map<String, Double> worth = projected.computeIfAbsent(league.season(), k -> new HashMap<>()).computeIfAbsent(g,
                            k -> TradeScreens.readOff(TradeScreens.projectedAboveLine(league.season(), k, positionOf, teams, flex, span),
                                    projectionCharts.apply(k)));
                    projection = id -> worth.getOrDefault(id, 0.0);
                    isProjected = worth::containsKey;
                }
                java.util.function.Predicate<String> hasProjection = isProjected;
                out.add(new Trade(league.season(), league.id(), league.type(), league.rec(), g,
                        TradeScreens.sum(two.get(0), draft), TradeScreens.sum(two.get(1), draft),
                        TradeScreens.sum(two.get(0), points), TradeScreens.sum(two.get(1), points), moved.size() == 1,
                        two.stream().allMatch(side -> side.stream().allMatch(id -> raw.applyAsDouble(id) > 0)),
                        TradeScreens.named(two.get(0), nameOf), TradeScreens.named(two.get(1), nameOf),
                        TradeScreens.sum(two.get(0), projection), TradeScreens.sum(two.get(1), projection),
                        two.stream().allMatch(side -> side.stream().allMatch(hasProjection))));
            }
        }
        return out;
    }

    static int bin(int games){
        return Math.min(games, LAST_BIN);
    }

    static double loss(List<Trade> trades, double w){
        double total = 0;
        for(Trade t : trades){
            total += t.uneven(w);
        }
        return trades.isEmpty() ? 0 : total / trades.size();
    }

    /** The draft weight, in steps of 1/GRID, that makes these trades read most even. */
    static double best(List<Trade> trades){
        double best = 1, lowest = Double.MAX_VALUE;
        for(int i = 0; i <= GRID; i++){
            double w = (double) i / GRID;
            double l = loss(trades, w);
            if(l < lowest - 1e-12){
                lowest = l;
                best = w;
            }
        }
        return best;
    }

    /** The same sides paired at random: A's side of one trade against B's side of another, same week. */
    static List<Trade> shuffled(List<Trade> trades, Random random){
        List<Trade> out = new ArrayList<>();
        for(Trade t : trades){
            Trade other = trades.get(random.nextInt(trades.size()));
            out.add(new Trade(t.season(), t.league(), t.type(), t.rec(), t.games(), t.draftA(), other.draftB(), t.paceA(), other.paceB(),
                    false, false, t.gotA(), other.gotB(), t.projA(), other.projB(), t.allProjected() && other.allProjected()));
        }
        return out;
    }

    /** Mean unevenness of random pairings at w, averaged over this many shuffles so one draw does not decide it. */
    static double randomLoss(List<List<Trade>> shuffles, double w){
        double total = 0;
        for(List<Trade> s : shuffles){
            total += loss(s, w);
        }
        return total / shuffles.size();
    }

    /**
     * The CHECK on {@link #best}: the weight at which real trades beat random
     * pairings of the same sides by the most. A screen that is merely less
     * spread out makes every pairing look even, real or not; this weight is
     * the one where being an actual trade adds the most evenness.
     */
    static double bestOverRandom(List<Trade> trades, List<List<Trade>> shuffles){
        double best = 1, most = -Double.MAX_VALUE;
        for(int i = 0; i <= GRID; i++){
            double w = (double) i / GRID;
            double gap = randomLoss(shuffles, w) - loss(trades, w);
            if(gap > most + 1e-12){
                most = gap;
                best = w;
            }
        }
        return best;
    }

    /** A 90% range for the weight: whole leagues resampled with replacement. */
    static double[] interval(List<Trade> trades, int reps, Random random){
        Map<String, List<Trade>> byLeague = new TreeMap<>();
        for(Trade t : trades){
            byLeague.computeIfAbsent(t.season() + "-" + t.league(), k -> new ArrayList<>()).add(t);
        }
        List<List<Trade>> clusters = new ArrayList<>(byLeague.values());
        double[] w = new double[reps];
        for(int r = 0; r < reps; r++){
            List<Trade> sample = new ArrayList<>();
            for(int i = 0; i < clusters.size(); i++){
                sample.addAll(clusters.get(random.nextInt(clusters.size())));
            }
            w[r] = best(sample);
        }
        java.util.Arrays.sort(w);
        return new double[]{w[(int) Math.floor(0.05 * (reps - 1))], w[(int) Math.ceil(0.95 * (reps - 1))]};
    }

    /** Non-increasing weighted least squares through the weekly weights (pool-adjacent violators). */
    static double[] nonIncreasing(double[] y, double[] weight){
        List<double[]> blocks = new ArrayList<>();    // {weighted sum, weight, count}
        for(int i = 0; i < y.length; i++){
            blocks.add(new double[]{y[i] * weight[i], weight[i], 1});
            while(blocks.size() >= 2){
                double[] last = blocks.get(blocks.size() - 1), previous = blocks.get(blocks.size() - 2);
                if(previous[0] / previous[1] >= last[0] / last[1]){
                    break;
                }
                previous[0] += last[0];
                previous[1] += last[1];
                previous[2] += last[2];
                blocks.remove(blocks.size() - 1);
            }
        }
        double[] out = new double[y.length];
        int i = 0;
        for(double[] block : blocks){
            for(int j = 0; j < block[2]; j++){
                out[i++] = block[0] / block[1];
            }
        }
        return out;
    }

    /** One week's weights as TradeScreens reads them: draft's share, and pace's share of the rest (the projection has the remainder). */
    public record Weights(double draft, double paceShare) {}

    /** The committed curve: games played -> weights. Empty when it has not been measured. */
    public static TreeMap<Integer, Weights> table(){
        TreeMap<Integer, Weights> out = new TreeMap<>();
        if(!Files.exists(TABLE)){
            return out;
        }
        try {
            List<String> lines = Files.readAllLines(TABLE, StandardCharsets.UTF_8);
            List<String> header = List.of(lines.get(0).split("\t"));
            int games = header.indexOf("games"), curve = header.indexOf("curve3"), pace = header.indexOf("pace3"),
                    projection = header.indexOf("projection3");
            if(curve < 0){
                return out;
            }
            for(String line : lines.subList(1, lines.size())){
                String[] c = line.split("\t");
                double draft = Double.parseDouble(c[curve]);
                double p = Double.parseDouble(c[pace]), j = Double.parseDouble(c[projection]);
                if(!Double.isNaN(draft)){
                    // a week fitted all-draft has no pace/projection split: keep its draft weight, leave the split to its neighbours
                    out.put(Integer.parseInt(c[games]), new Weights(draft, !Double.isNaN(p) && p + j > 0 ? p / (p + j) : Double.NaN));
                }
            }
        }
        catch(IOException unreadable){
            throw new IllegalStateException(TABLE + " is unreadable", unreadable);
        }
        return out;
    }

    /** The measured curve as a function of games played, or null when it has not been measured. */
    public static java.util.function.DoubleUnaryOperator curve(){
        TreeMap<Integer, Double> draft = new TreeMap<>();
        table().forEach((g, w) -> draft.put(g, w.draft()));
        return draft.isEmpty() ? null : games -> at(draft, games, 1);
    }

    /** Pace's share of the season screen (the projection has the rest) by games played, or null when not measured. */
    public static java.util.function.DoubleUnaryOperator paceShare(){
        TreeMap<Integer, Double> pace = new TreeMap<>();
        table().forEach((g, w) -> {
            if(!Double.isNaN(w.paceShare())){
                pace.put(g, w.paceShare());
            }
        });
        return pace.isEmpty() ? null : games -> at(pace, games, pace.firstEntry().getValue());
    }

    /** All draft value before a game; the measured weeks joined by straight lines; flat after the last. */
    static double at(TreeMap<Integer, Double> table, double games){
        return at(table, games, 1);
    }

    /** The table read at games played: `before` before a game, straight lines between measured weeks, flat after the last. */
    static double at(TreeMap<Integer, Double> table, double games, double before){
        if(games <= 0){
            return before;
        }
        Map.Entry<Integer, Double> below = table.floorEntry((int) Math.floor(games));
        Map.Entry<Integer, Double> above = table.ceilingEntry((int) Math.ceil(games));
        double x0 = below == null ? 0 : below.getKey();
        double y0 = below == null ? before : below.getValue();
        if(above == null){
            return y0;
        }
        if(above.getKey() == x0){
            return above.getValue();
        }
        return y0 + (games - x0) / (above.getKey() - x0) * (above.getValue() - y0);
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int teams = configuration.getLeagueJson().getAsJsonObject("settings").get("num_teams").getAsInt();
        int flex = StartingLineup.flexSlotsPerTeam(configuration);
        Map<String, EraBoards.Board> boards = EraBoards.usable("ppr", EraIngest.MIN_RATE, EraIngest.minDepth());
        TradeScreens.Chart chart = TradeScreens.chart(boards.values(), teams, flex);
        List<Trade> all = trades(chart, boards, teams, flex, TradeScreens.paceCharts(boards.values(), teams, flex));
        Map<Integer, List<Trade>> byBin = new TreeMap<>();
        int uninformative = 0;
        for(Trade t : all){
            if(t.informs()){
                byBin.computeIfAbsent(bin(t.games()), k -> new ArrayList<>()).add(t);
            }
            else{
                uninformative++;
            }
        }
        Random random = new Random(20261022L);
        Map<Integer, double[]> three = new TreeMap<>();
        String threeReport = threeScreens(chart, boards, teams, flex, random, three);
        int bins = byBin.size();
        int[] g = new int[bins];
        double[] w = new double[bins], n = new double[bins];
        StringBuilder rows = new StringBuilder();
        StringBuilder tsv = new StringBuilder("games\ttrades\tleagues\tweight\tlow90\thigh90\tcurve\tuneven\tshuffled\tdraftOnly\tpaceOnly"
                + "\tsideP10\tsideP25\tsideP50\tdraft3\tpace3\tprojection3\ttrades3\tcurve3\tforecastDraft\tforecastPace\tforecastProjection\n");
        int i = 0;
        List<double[]> stats = new ArrayList<>();
        for(Map.Entry<Integer, List<Trade>> e : byBin.entrySet()){
            List<Trade> trades = e.getValue();
            g[i] = e.getKey();
            n[i] = trades.size();
            w[i] = best(trades);
            double[] range = interval(trades, 300, random);
            List<List<Trade>> shuffles = new ArrayList<>();
            for(int r = 0; r < 20; r++){
                shuffles.add(shuffled(trades, random));
            }
            long leagues = trades.stream().map(t -> t.season() + "-" + t.league()).distinct().count();
            stats.add(new double[]{leagues, range[0], range[1], loss(trades, w[i]), randomLoss(shuffles, w[i]),
                    loss(trades, 1), loss(trades, 0), bestOverRandom(trades, shuffles),
                    split(trades, t -> t.type() == 0), split(trades, t -> t.type() == 1),
                    split(trades, t -> t.rec() < 0.75), split(trades, t -> t.rec() >= 0.75),
                    split(trades, Trade::samePosition), trades.stream().filter(Trade::samePosition).count(),
                    split(trades, Trade::allProducing), trades.stream().filter(Trade::allProducing).count()});
            i++;
        }
        double[] curve = nonIncreasing(w, n);
        List<List<Trade>> binned = new ArrayList<>(byBin.values());
        StringBuilder envelope = new StringBuilder();
        for(i = 0; i < bins; i++){
            double[] s = stats.get(i);
            double[] side = sides(binned.get(i), curve[i]);
            String label = g[i] == LAST_BIN ? LAST_BIN + "+" : String.valueOf(g[i]);
            double[] t3 = three.getOrDefault(g[i], new double[]{Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN});
            tsv.append(String.format("%d\t%d\t%d\t%.2f\t%.2f\t%.2f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%d\t%.3f\t%.3f\t%.3f\t%.3f%n",
                    g[i], (int) n[i], (int) s[0], w[i], s[1], s[2], curve[i], s[3], s[4], s[5], s[6], side[0], side[1], side[2],
                    t3[0], t3[1], t3[2], (int) t3[3], t3[4], t3[5], t3[6], t3[7]));
            envelope.append(String.format("%5s %7d     %4.0f%%     %4.0f%%     %4.0f%%%n", g[i] == LAST_BIN ? LAST_BIN + "+" : String.valueOf(g[i]),
                    2 * (int) n[i], 100 * side[0], 100 * side[1], 100 * side[2]));
            rows.append(String.format("%5s %7d %6d   %4.0f%%  %4.0f-%3.0f%%   %4.0f%%    %.3f  %.3f  %.3f  %.3f   %5s    %5s %5s   %5s %5s   %5s (%d)   %5s (%d)%n",
                    label, (int) n[i], (int) s[0], 100 * w[i], 100 * s[1], 100 * s[2], 100 * curve[i],
                    s[3], s[4], s[5], s[6], pct(s[7]), pct(s[8]), pct(s[9]), pct(s[10]), pct(s[11]), pct(s[12]), (int) s[13], pct(s[14]), (int) s[15]));
        }
        long leagues = all.stream().map(t -> t.season() + "-" + t.league()).distinct().count();
        StringBuilder out = new StringBuilder();
        out.append(String.format("DRAFT WEIGHT BY GAMES PLAYED  %s%n", java.time.LocalDate.now()));
        out.append(String.format("%d accepted player-for-player trades made after week 1's games, from %d league-seasons like this one%n"
                        + "(OtherLeagues; %s). %d more had value on only one screen and cannot inform the weight.%n"
                        + "Both sides priced as TradeScreens prices them, in this league's scoring.%n%n",
                all.size() - uninformative, leagues, seasons(all), uninformative));
        out.append(threeReport);
        out.append(String.format("TWO SCREENS - draft value and pace alone, the fit before Sleeper's projection was a screen (2026-10-22):%n"));
        out.append("games  trades  leagues  weight  90% range   CURVE    uneven  random  draft  pace   vs-rnd    redraft keeper  half  full   same pos     above line\n");
        out.append(rows);
        out.append(String.format("%nweight: the share of a man's value that is draft value, the rest his pace, that makes that week's trades%n"
                + "most even. CURVE: the same, never rising with games played. uneven: mean |A-B|/(A+B)%n"
                + "at that weight; random: the same sides paired at random, 20 shuffles (real trades should be far more even);%n"
                + "draft and pace: unevenness on either screen alone. vs-rnd: the check - the weight where real trades beat random%n"
                + "pairs by the most. redraft..full refit the weight on one kind of league. same pos: refit on trades where every%n"
                + "man is at one position (RB for RB, WR for WR), with their count - a need at a position cannot explain those.%n"
                + "above line: refit on trades where every man moved is producing above the last starter, so the pace screen's%n"
                + "floor at zero never bites - if the weight on draft fell a long way here, the floor was what kept it high.%n"
                + "A refit on fewer than 30 trades is '-'.%n"));
        out.append(String.format("%nTHE ROOM THE UNKNOWNS LEAVE - a positional need, an injury the box score has not caught up with, a%n"
                + "projection, a friend: whatever made an accepted trade read uneven on the blend. At the curve's weight, each side's%n"
                + "blended value back over value given; the share of sides that got back this little or less:%n"
                + "games   sides    10%%       25%%       50%%%n"));
        out.append(envelope);
        out.append(String.format("So after a few games one accepted side in ten got back no more than the first column: an ask below it%n"
                + "is asking for something nine in ten managers did not give.%n"));
        out.append(rawPace(chart, boards, teams, flex, byBin, random));
        out.append(eyeball(byBin, new Random(11)));
        System.out.print(out);
        Files.writeString(TABLE, tsv.toString(), StandardCharsets.UTF_8);
        Files.writeString(REPORT, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + TABLE + " and " + REPORT);
    }

    /**
     * The first fit, for the record: pace as the raw number at 17 games. It
     * read draft value at 73% from week 5 on, and Justin would not have it:
     * "no, there's no way draft weight is over 50% by week 5". A hot start
     * overstates the season, and with one dial the only way to regress it was
     * weight on draft value. Printed beside the fit on predicted pace, with
     * the unevenness of each - the better description of what managers accept
     * is the one under which their trades read more even - and the pace chart
     * itself, so the regression can be seen.
     */
    static String rawPace(TradeScreens.Chart chart, Map<String, EraBoards.Board> boards, int teams, int flex,
                          Map<Integer, List<Trade>> predictedByBin, Random random) throws IOException {
        Map<Integer, List<Trade>> byBin = new TreeMap<>();
        for(Trade t : trades(chart, boards, teams, flex)){
            if(t.informs()){
                byBin.computeIfAbsent(bin(t.games()), k -> new ArrayList<>()).add(t);
            }
        }
        StringBuilder out = new StringBuilder(String.format("%nTHE FIRST FIT, ON RAW PACE (a hot start at 17 games), beside this one. The better description of what%n"
                + "managers accept is the one under which their trades read more even ('uneven', lower is better):%n"
                + "games   raw pace: trades weight  uneven   |   predicted pace: weight  uneven%n"));
        for(Map.Entry<Integer, List<Trade>> e : byBin.entrySet()){
            List<Trade> raw = e.getValue();
            List<Trade> predicted = predictedByBin.getOrDefault(e.getKey(), List.of());
            double rw = best(raw), pw = best(predicted);
            out.append(String.format("%5s %16d  %4.0f%%   %.3f   |   %20.0f%%   %.3f%n",
                    e.getKey() == LAST_BIN ? LAST_BIN + "+" : String.valueOf(e.getKey()), raw.size(), 100 * rw, loss(raw, rw),
                    100 * pw, loss(predicted, pw)));
        }
        java.util.function.IntFunction<TradeScreens.Chart> charts = TradeScreens.paceCharts(boards.values(), teams, flex);
        out.append(String.format("%nTHE PACE CHART - rest-of-season worth (season points above the last starter, at 17 games) of a man this many%n"
                + "points a game above (+) or below (-) the last starter at his position after g games, every board season; the%n"
                + "draft chart for scale: ADP 1 %.0f, 12 %.0f, 36 %.0f, 72 %.0f.%n          -4     -2      0     +2     +4     +6    +10%n",
                chart.at(1), chart.at(12), chart.at(36), chart.at(72)));
        for(int g : new int[]{1, 3, 5, 8, 12}){
            TradeScreens.Chart c = charts.apply(g);
            StringBuilder row = new StringBuilder(String.format("  g=%-3d", g));
            for(double x : new double[]{-4, -2, 0, 2, 4, 6, 10}){
                row.append(String.format("%7.0f", c.at(-x)));
            }
            out.append(row).append('\n');
        }
        return out.toString();
    }

    /** Weights (draft, pace, projection) on a simplex grid of this step that make the trades most even. */
    static double[] best3(List<Trade> trades, double step){
        double[] best = {1, 0, 0};
        double lowest = Double.MAX_VALUE;
        int n = (int) Math.round(1 / step);
        for(int i = 0; i <= n; i++){
            for(int j = 0; i + j <= n; j++){
                double wd = i * step, wp = j * step;
                double total = 0;
                for(Trade t : trades){
                    total += t.uneven3(wd, wp);
                }
                if(total < lowest - 1e-9){
                    lowest = total;
                    best = new double[]{wd, wp, 1 - wd - wp};
                }
            }
        }
        return best;
    }

    static double loss3(List<Trade> trades, double[] w){
        double total = 0;
        for(Trade t : trades){
            total += t.uneven3(w[0], w[1]);
        }
        return trades.isEmpty() ? 0 : total / trades.size();
    }

    /** 90% ranges for each of the three weights, whole leagues resampled. */
    static double[][] interval3(List<Trade> trades, int reps, Random random){
        Map<String, List<Trade>> byLeague = new TreeMap<>();
        for(Trade t : trades){
            byLeague.computeIfAbsent(t.season() + "-" + t.league(), k -> new ArrayList<>()).add(t);
        }
        List<List<Trade>> clusters = new ArrayList<>(byLeague.values());
        double[][] w = new double[3][reps];
        for(int r = 0; r < reps; r++){
            List<Trade> sample = new ArrayList<>();
            for(int i = 0; i < clusters.size(); i++){
                sample.addAll(clusters.get(random.nextInt(clusters.size())));
            }
            double[] b = best3(sample, 0.05);
            for(int k = 0; k < 3; k++){
                w[k][r] = b[k];
            }
        }
        double[][] out = new double[3][2];
        for(int k = 0; k < 3; k++){
            java.util.Arrays.sort(w[k]);
            out[k] = new double[]{w[k][(int) Math.floor(0.05 * (reps - 1))], w[k][(int) Math.ceil(0.95 * (reps - 1))]};
        }
        return out;
    }

    /** Least squares with the columns of x (the first a column of ones for an intercept). */
    static double[] ols(List<double[]> x, List<Double> y){
        int k = x.get(0).length;
        double[][] a = new double[k][k + 1];
        for(int r = 0; r < x.size(); r++){
            double[] row = x.get(r);
            for(int i = 0; i < k; i++){
                for(int j = 0; j < k; j++){
                    a[i][j] += row[i] * row[j];
                }
                a[i][k] += row[i] * y.get(r);
            }
        }
        for(int c = 0; c < k; c++){
            int pivot = c;
            for(int r = c + 1; r < k; r++){
                if(Math.abs(a[r][c]) > Math.abs(a[pivot][c])){
                    pivot = r;
                }
            }
            double[] swap = a[c];
            a[c] = a[pivot];
            a[pivot] = swap;
            for(int r = 0; r < k; r++){
                if(r != c && a[c][c] != 0){
                    double f = a[r][c] / a[c][c];
                    for(int j = c; j <= k; j++){
                        a[r][j] -= f * a[c][j];
                    }
                }
            }
        }
        double[] b = new double[k];
        for(int i = 0; i < k; i++){
            b[i] = a[i][k] / a[i][i];
        }
        return b;
    }

    /**
     * WHAT THE THREE SCREENS DESERVE - a forecaster's weights, to set the
     * managers' against. The review of 2026-10-22 asked the question Justin's
     * objection turns on: is a draft share of a third at week 5 managers stuck
     * on the draft, or what the draft slot is still worth knowing? For every
     * man on a board with a game by week g and a next-game projection, every
     * season Sleeper projects ({@link TradeScreens#FIRST_PROJECTED} on), his
     * rest-of-season worth regressed on his three screen values with an
     * intercept, the three coefficients scaled to sum to one. A manager who
     * uses each screen exactly as much as it predicts would show these
     * weights; managers anchored on the draft would show more on draft.
     */
    static double[] forecast(Map<String, EraBoards.Board> boards, TradeScreens.Chart chart,
                             java.util.function.IntFunction<TradeScreens.Chart> paceCharts,
                             java.util.function.IntFunction<TradeScreens.Chart> projectionCharts,
                             Map<String, Map<String, Position>> positions, int g, int teams, int flex){
        List<double[]> x = new ArrayList<>();
        List<Double> y = new ArrayList<>();
        for(EraBoards.Board board : boards.values()){
            if(Integer.parseInt(board.season()) < TradeScreens.FIRST_PROJECTED || g >= board.weekly().size()){
                continue;
            }
            Map<String, Position> positionOf = positions.computeIfAbsent(board.season(), TradeScreens::positions);
            Map<String, Double> pace = TradeScreens.predicted(TradeScreens.soFar(board.weekly().subList(0, g)), positionOf, g,
                    teams, flex, paceCharts.apply(g), Set.of());
            Map<String, Double> projection = TradeScreens.readOff(TradeScreens.projectedAboveLine(board.season(), g, positionOf,
                    teams, flex), projectionCharts.apply(g));
            Map<String, Double> worth = TradeScreens.restWorth(board, positionOf, g, teams, flex);
            for(String id : board.ids()){
                if(TradeScreens.skill(positionOf.get(id)) && pace.containsKey(id) && projection.containsKey(id)){
                    x.add(new double[]{1, chart.at(board.adp().get(id)), pace.get(id), projection.get(id)});
                    y.add(worth.getOrDefault(id, 0.0));
                }
            }
        }
        if(x.size() < 10){
            return new double[]{Double.NaN, Double.NaN, Double.NaN, 0};
        }
        double[] b = ols(x, y);
        double sum = b[1] + b[2] + b[3];
        return new double[]{b[1] / sum, b[2] / sum, b[3] / sum, x.size()};
    }

    /**
     * THREE SCREENS - WHAT TRADESCREENS USES. Justin, 2026-10-22: "I honestly
     * think the draft value's share is wrong at this point compared to sleeper
     * weekly projections which have adapted (i.e. cam skateboo reduced usage
     * due to fumble loss of trust from coaches)." Draft value, pace and
     * Sleeper's projection for each man's next game, each read off its own
     * chart, and the three weights that make each week's accepted trades most
     * even - fitted on trades where every man moved has a next-game
     * projection, because a man out with an injury reads zero on that screen
     * while his draft value and pace stand, which leans the fit toward draft
     * (the review measured about 3 points). Beside it: the same fit on every
     * trade, the forecaster's weights ({@link #forecast}), and the fit with
     * projections averaged over three weeks (less matchup noise, but it knows
     * news the man opposite did not - a check, not a screen).
     */
    static String threeScreens(TradeScreens.Chart chart, Map<String, EraBoards.Board> boards, int teams, int flex,
                               Random random, Map<Integer, double[]> fitted) throws IOException {
        java.util.function.IntFunction<TradeScreens.Chart> paceCharts = TradeScreens.paceCharts(boards.values(), teams, flex);
        java.util.function.IntFunction<TradeScreens.Chart> projectionCharts = TradeScreens.projectionCharts(boards.values(), teams, flex);
        List<Trade> all = trades(chart, boards, teams, flex, paceCharts, projectionCharts);
        List<Trade> smoothed = trades(chart, boards, teams, flex, paceCharts,
                TradeScreens.projectionCharts(boards.values(), teams, flex, 3), 3);
        Map<Integer, List<Trade>> everyBin = new TreeMap<>(), healthyBin = new TreeMap<>(), smoothBin = new TreeMap<>();
        for(Trade t : all){
            if(t.informs3()){
                everyBin.computeIfAbsent(bin(t.games()), k -> new ArrayList<>()).add(t);
                if(t.allProjected()){
                    healthyBin.computeIfAbsent(bin(t.games()), k -> new ArrayList<>()).add(t);
                }
            }
        }
        for(Trade t : smoothed){
            if(t.informs3() && t.allProjected()){
                smoothBin.computeIfAbsent(bin(t.games()), k -> new ArrayList<>()).add(t);
            }
        }
        Map<String, Map<String, Position>> positions = new HashMap<>();
        StringBuilder out = new StringBuilder(String.format("THREE SCREENS - WHAT TRADESCREENS USES. Draft value, pace, and Sleeper's projection for each man's next%n"
                + "game (Justin: \"compared to sleeper weekly projections which have adapted\"), each read off its own chart; the%n"
                + "weights that make each week's accepted trades most even, on trades where every man moved has a projection%n"
                + "(step 0.02; 90%% ranges resample leagues at step 0.05). CURVE: draft's weight never rising with games played.%n"
                + "games  trades    draft (90%%)      pace (90%%)     projection (90%%)   CURVE  uneven  | all trades | forecaster: draft pace proj | 3-wk: draft%n"));
        for(Map.Entry<Integer, List<Trade>> e : healthyBin.entrySet()){
            List<Trade> trades = e.getValue();
            double[] w = best3(trades, 0.02);
            fitted.put(e.getKey(), new double[]{w[0], w[1], w[2], trades.size()});
        }
        double[] draft = new double[fitted.size()], n = new double[fitted.size()];
        int i = 0;
        for(double[] f : fitted.values()){
            draft[i] = f[0];
            n[i++] = f[3];
        }
        double[] curve = nonIncreasing(draft, n);
        i = 0;
        for(Map.Entry<Integer, List<Trade>> e : healthyBin.entrySet()){
            List<Trade> trades = e.getValue();
            double[] w = fitted.get(e.getKey());
            double[][] range = interval3(trades, 150, random);
            double[] every = best3(everyBin.getOrDefault(e.getKey(), List.of()), 0.02);
            double[] smooth = best3(smoothBin.getOrDefault(e.getKey(), List.of()), 0.02);
            double[] f = forecast(boards, chart, paceCharts, projectionCharts, positions, e.getKey(), teams, flex);
            fitted.put(e.getKey(), new double[]{w[0], w[1], w[2], w[3], curve[i], f[0], f[1], f[2]});
            out.append(String.format("%5s %7d   %4.0f%% (%2.0f-%3.0f)   %4.0f%% (%2.0f-%3.0f)   %4.0f%% (%2.0f-%3.0f)   %4.0f%%   %.3f  |    %4.0f%%    |   %4.0f%%  %4.0f%%  %4.0f%%     |   %4.0f%%%n",
                    e.getKey() == LAST_BIN ? LAST_BIN + "+" : String.valueOf(e.getKey()), trades.size(),
                    100 * w[0], 100 * range[0][0], 100 * range[0][1], 100 * w[1], 100 * range[1][0], 100 * range[1][1],
                    100 * w[2], 100 * range[2][0], 100 * range[2][1], 100 * curve[i], loss3(trades, w),
                    100 * every[0], 100 * f[0], 100 * f[1], 100 * f[2], 100 * smooth[0]));
            i++;
        }
        out.append(String.format("all trades: draft's weight with injured men's trades kept (they read zero on the projection). forecaster: the%n"
                + "weights a forecaster would put on the three screens, from what each predicted of the rest of the season%n"
                + "(board men 2018 on, least squares, scaled to sum to one) - managers anchored on the draft would sit above it.%n"
                + "3-wk: draft's weight with projections averaged over the next three weeks (knows news the trade did not).%n"
                + "TradeScreens blends draft (CURVE) with this season's screen: pace and projection in that week's fitted ratio.%n%n"));
        return out.toString();
    }

    /**
     * THE DATA BY EYE: a few trades from weeks 2, 4 and 6 drawn at random, men
     * named, both screens, so anyone can check a pace or a draft value against
     * what they remember of that season.
     */
    static String eyeball(Map<Integer, List<Trade>> byBin, Random random){
        StringBuilder out = new StringBuilder(String.format("%nTHE DATA BY EYE - trades drawn at random, each side's draft value and pace (season points above the%n"
                + "last starter; pace read off the pace chart), what A received against what B received:%n"));
        for(int g : new int[]{2, 4, 6}){
            List<Trade> trades = byBin.getOrDefault(g, List.of());
            for(int i = 0; i < Math.min(4, trades.size()); i++){
                Trade t = trades.get(random.nextInt(trades.size()));
                out.append(String.format("  %s after %d: A got %s (draft %.0f, pace %.0f)  B got %s (draft %.0f, pace %.0f)%n",
                        t.season(), g, t.gotA(), t.draftA(), t.paceA(), t.gotB(), t.draftB(), t.paceB()));
            }
        }
        return out.toString();
    }

    /** Both sides of every trade at weight w, value back over value given: the 10th, 25th and 50th percentiles. */
    static double[] sides(List<Trade> trades, double w){
        List<Double> r = new ArrayList<>();
        for(Trade t : trades){
            if(t.a(w) > 0 && t.b(w) > 0){
                r.add(t.a(w) / t.b(w));
                r.add(t.b(w) / t.a(w));
            }
        }
        if(r.isEmpty()){
            return new double[]{Double.NaN, Double.NaN, Double.NaN};
        }
        r.sort(Double::compare);
        return new double[]{r.get((int) (0.10 * (r.size() - 1))), r.get((int) (0.25 * (r.size() - 1))), r.get((int) (0.50 * (r.size() - 1)))};
    }

    static double split(List<Trade> trades, java.util.function.Predicate<Trade> keep){
        List<Trade> some = trades.stream().filter(keep).toList();
        return some.size() < 30 ? Double.NaN : best(some);
    }

    static String pct(double w){
        return Double.isNaN(w) ? "-" : String.format("%.0f%%", 100 * w);
    }

    static String seasons(List<Trade> all){
        return String.join(", ", new java.util.TreeSet<>(all.stream().map(Trade::season).toList()));
    }
}
