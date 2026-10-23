import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.DoubleUnaryOperator;
import java.util.function.ToDoubleFunction;

/**
 * WHAT THE MAN ACROSS THE TABLE SEES, and whether anyone in this league ever
 * took less of it.
 *
 * Justin, 2026-09-27, on the trade the short list put first - Malik Nabers and
 * Rhamondre Stevenson for Ja'Marr Chase: "no way that trade goes through.
 * something is wrong if you thought it would have even the slightest chance of
 * getting accepted." He was right, and the board had said the opposite twice.
 * Renteez gained on the objective, a roster model he does not run. And the one
 * check on how the trade LOOKS measured draft position on a straight line:
 * Nabers went at 28.6 and Chase at 3.9, 24.7 picks, under the 25 that meant "a
 * grab", so it printed "reads even on draft position". Twenty-five picks between
 * a first-rounder and a third-rounder is not the twenty-five between the tenth
 * round and the twelfth. The numbers on his screen that week - Chase 15.2 a
 * game, Nabers 5.2 with his quarterback on IR, Stevenson 6.5 - were never read.
 *
 * So two screens, both from HIS side of the trade, both what he receives over
 * what he sends, both in season points above the last starter at a man's
 * position:
 *
 *   DRAFT VALUE - each man's ADP read off a chart of what a man drafted there
 *   has actually been worth: season points above the last starter at his
 *   position, averaged over every board EraBoards can join to outcomes and
 *   forced to fall with ADP (pool-adjacent violators). It is steep where the
 *   draft is steep, so pick 4 against pick 29 is a large gap and pick 104
 *   against 129 a small one, and it adds across a package, so two men are not
 *   priced as their best one.
 *
 *   HIS PACE, READ AS WHAT IT PREDICTS - each man's points a game so far
 *   above the per-game points of the last starter at his position (the
 *   number on the player card, less the line), read off a chart of what men
 *   that far above the line after that many games went on to be worth over
 *   the rest of the season ({@link #paceChart}); zero for a man on IR. Until
 *   2026-10-22 this screen was the raw pace at 17 games, which overstates a
 *   hot start and made draft value look like it counted 73% at week 5.
 *
 *   SLEEPER'S PROJECTION - since 2026-10-22, each man's league-scored
 *   projection for his next game, read off its own chart
 *   ({@link #projectionChart}). It has moved on news neither other screen
 *   carries (Justin: "cam skateboo reduced usage due to fumble loss of trust
 *   from coaches"). Pace and projection together are THIS SEASON's screen
 *   ({@link #seasonScreen}), in the ratio other leagues' trades put between
 *   them; draft value's share of the whole is DraftWeightCurve's.
 *
 * A trade is FAIR TO HIM when he does not lose on either. That is the bar for
 * sending it, and it is where the reputation Justin asked for comes from: the
 * man opposite can check both numbers himself.
 *
 * WHAT THIS DOES NOT DO: fit acceptance. Justin ruled that out (TradePartners:
 * "many trades were lopsided"), and the reason stands - a sample of accepted
 * trades teaches a boundary that says yes to everything. What those trades CAN
 * do is bound the other direction. {@link #history} puts every side of every
 * accepted player-for-player trade on the same two screens, and a proposal that
 * asks him to take less on BOTH than every side that ever said yes here has no
 * precedent in this league. Lopsided precedents make that bound permissive,
 * which is the right direction for "no chance": outside a permissive envelope
 * is really outside.
 *
 *   ./gradlew run -Pmain=TradeScreens -Psend="Malik Nabers,Rhamondre Stevenson" -Preceive="Ja'Marr Chase"
 */
public class TradeScreens {

    static final List<Position> SKILL = List.of(Position.QB, Position.RB, Position.WR, Position.TE);

    /** A skill man; null-safe, because List.of(...).contains(null) throws. */
    static boolean skill(Position p){
        return p != null && SKILL.contains(p);
    }

    /** Injury tags that mean he cannot produce now: his points so far are not what the other manager is buying. */
    static final Set<String> SIDELINED = Set.of("IR", "PUP", "Sus", "NA");

    /** A season, for the points screen: per-game surplus times this. */
    static final int PACE = 17;

    /** Below this many points a side sent nothing on a screen, and cannot have lost on it. */
    static final double NOTHING = 1.0;

    /** The draft-value chart: ADP -> season points above the starter line, never rising with ADP. */
    public record Chart(double[] adp, double[] value) {

        public double at(double x){
            if(adp.length == 0){
                return 0;
            }
            if(!(x > adp[0])){
                return value[0];
            }
            if(x >= adp[adp.length - 1]){
                return value[value.length - 1];
            }
            int hi = 1;
            while(adp[hi] < x){
                hi++;
            }
            double width = adp[hi] - adp[hi - 1];
            if(width <= 0){
                return value[hi];
            }
            double t = (x - adp[hi - 1]) / width;
            return value[hi - 1] + t * (value[hi] - value[hi - 1]);
        }
    }

    /**
     * The non-increasing least-squares fit to {adp, surplus} pairs
     * (pool-adjacent violators), read back as each block's mean ADP and mean
     * surplus; {@link Chart#at} interpolates between block centres.
     */
    static Chart fit(List<double[]> pairs){
        List<double[]> sorted = new ArrayList<>(pairs);
        sorted.sort(Comparator.comparingDouble(p -> p[0]));
        List<double[]> blocks = new ArrayList<>();     // {sum of adp, sum of surplus, count}
        for(double[] p : sorted){
            blocks.add(new double[]{p[0], p[1], 1});
            while(blocks.size() >= 2){
                double[] last = blocks.get(blocks.size() - 1);
                double[] previous = blocks.get(blocks.size() - 2);
                if(previous[1] / previous[2] >= last[1] / last[2]){
                    break;
                }
                previous[0] += last[0];
                previous[1] += last[1];
                previous[2] += last[2];
                blocks.remove(blocks.size() - 1);
            }
        }
        double[] x = new double[blocks.size()];
        double[] y = new double[blocks.size()];
        for(int i = 0; i < blocks.size(); i++){
            x[i] = blocks.get(i)[0] / blocks.get(i)[2];
            y[i] = blocks.get(i)[1] / blocks.get(i)[2];
        }
        return new Chart(x, y);
    }

    /** The last starter's number at each skill position: fixed slots, then the flex to the best remaining. */
    static Map<Position, Double> line(Map<String, Double> score, Map<String, Position> positionOf, int teams, int flex){
        Map<Position, List<Double>> byPosition = new EnumMap<>(Position.class);
        score.forEach((id, v) -> {
            Position p = positionOf.get(id);
            if(skill(p) && v > 0){
                byPosition.computeIfAbsent(p, k -> new ArrayList<>()).add(v);
            }
        });
        ReplacementLevel level = ReplacementLevel.greedy(byPosition, teams, flex);
        Map<Position, Double> out = new EnumMap<>(Position.class);
        for(Position p : SKILL){
            out.put(p, level.of(p));
        }
        return out;
    }

    /** The chart from history: every board man's season points above his position's starter line, by his ADP. */
    static Chart chart(Collection<EraBoards.Board> boards, int teams, int flex){
        List<double[]> pairs = new ArrayList<>();
        for(EraBoards.Board board : boards){
            Map<String, Double> season = board.seasonPoints();
            Map<Position, Double> line = line(season, positions(board.season()), teams, flex);
            for(String id : board.ids()){
                Position p = board.positionOf().get(id);
                if(skill(p)){
                    pairs.add(new double[]{board.adp().get(id), Math.max(0, season.getOrDefault(id, 0.0) - line.get(p))});
                }
            }
        }
        return fit(pairs);
    }

    /** Every man's position in a past season, from its season rows (which carry the player object). */
    static Map<String, Position> positions(String season){
        Map<String, Position> out = new HashMap<>();
        for(JsonElement element : EraActuals.skillRows(season)){
            JsonObject row = element.getAsJsonObject();
            JsonObject player = row.getAsJsonObject("player");
            String position = EraBoards.text(player, "position");
            if(player != null && row.has("player_id") && Position.isStandardPosition(position)){
                out.put(row.get("player_id").getAsString(), Position.valueOf(position));
            }
        }
        return out;
    }

    /** id -> {points, games} over the weeks given, a game being a week in which he has a stat row. */
    static Map<String, double[]> soFar(List<Map<String, Double>> weeks){
        return LeagueWeek.sumWeeks(weeks);
    }

    /**
     * The points screen per man: points a game above the per-game points of the
     * last starter at his position, times a season. The line counts men with at
     * least half the weeks, so one big game from a backup does not set it. A man
     * with no games, or one who is sidelined, shows nothing.
     */
    static Map<String, Double> production(Map<String, double[]> soFar, Map<String, Position> positionOf,
                                          int weeksPlayed, int teams, int flex, Set<String> sidelined){
        int minGames = Math.max(1, (weeksPlayed + 1) / 2);
        Map<String, Double> perGame = new HashMap<>();
        soFar.forEach((id, s) -> {
            if(s[1] >= minGames){
                perGame.put(id, s[0] / s[1]);
            }
        });
        Map<Position, Double> line = line(perGame, positionOf, teams, flex);
        Map<String, Double> out = new HashMap<>();
        soFar.forEach((id, s) -> {
            Position p = positionOf.get(id);
            if(skill(p) && s[1] > 0 && !sidelined.contains(id)){
                out.put(id, PACE * Math.max(0, s[0] / s[1] - line.get(p)));
            }
        });
        return out;
    }

    /** Each man's points a game so far less the last starter's at his position (any man with a game), not floored. */
    static Map<String, Double> aboveLine(Map<String, double[]> soFar, Map<String, Position> positionOf, int weeks, int teams, int flex){
        int minGames = Math.max(1, (weeks + 1) / 2);
        Map<String, Double> perGame = new HashMap<>();
        soFar.forEach((id, s) -> {
            if(s[1] >= minGames){
                perGame.put(id, s[0] / s[1]);
            }
        });
        Map<Position, Double> line = line(perGame, positionOf, teams, flex);
        Map<String, Double> out = new HashMap<>();
        soFar.forEach((id, s) -> {
            Position p = positionOf.get(id);
            if(skill(p) && s[1] > 0){
                out.put(id, s[0] / s[1] - line.get(p));
            }
        });
        return out;
    }

    /**
     * WHAT A PACE IS WORTH, read the way the draft chart reads an ADP. Justin,
     * 2026-10-22, of draft value counting 73% after five games: "no, there's no
     * way draft weight is over 50% by week 5". He had a point the first fit
     * could not see. The blend w x draft + (1 - w) x pace has one dial, and a
     * hot five-game pace overstates the rest of a man's season - anyone sane
     * regresses it. A manager who ignores the draft entirely but regresses
     * pace could only be fitted by weight on draft value, which sits in the
     * middle of the range and so stands in for "regress to the middle".
     *
     * So the pace screen is also read off a chart: after g games, what a man
     * whose points a game sat x above the last starter's went on to be worth
     * over the rest of the season - points above the last starter over the
     * weeks left, scaled to 17, zero for a man who did not play them - fitted
     * never to fall with x over every board season, exactly as the draft chart
     * maps an ADP to what men drafted there were worth. Both screens are then
     * the same thing, the season expected from one piece of evidence.
     */
    static Chart paceChart(Collection<EraBoards.Board> boards, Map<String, Map<String, Position>> positions,
                           int g, int teams, int flex){
        return signalChart(boards, positions, g, teams, flex,
                (board, positionOf) -> aboveLine(soFar(board.weekly().subList(0, g)), positionOf, g, teams, flex));
    }

    /**
     * SLEEPER'S PROJECTION AS A THIRD SCREEN. Justin, 2026-10-22, of draft
     * value still counting 48% at week 5: "I honestly think the draft value's
     * share is wrong at this point compared to sleeper weekly projections
     * which have adapted (i.e. cam skateboo reduced usage due to fumble loss
     * of trust from coaches)." The man opposite sees Sleeper's projection on
     * every player card, and it has already moved on news that neither a draft
     * slot nor a pace carries - a back benched for fumbles, a new quarterback.
     * With only two screens, everything expectation-like had nowhere to go but
     * draft value.
     *
     * The projection for a man's next game after g weeks: week g+1, or g+2 if
     * he has none in g+1 (a bye, or a week out), in this league's points
     * (LeagueWeek.projected; a man with no projection either week is off the
     * screen). Read, like the other two, off a chart of what men projected
     * that far above the last starter's projection went on to be worth over
     * the rest of the season, every season Sleeper's week projections cover
     * from {@link #FIRST_PROJECTED}. One leak, small and stated: for a man
     * without a week-g+1 projection, the g+2 projection was published after
     * week g+1's games.
     */
    static Chart projectionChart(Collection<EraBoards.Board> boards, Map<String, Map<String, Position>> positions,
                                 int g, int teams, int flex, int span){
        return signalChart(boards, positions, g, teams, flex,
                (board, positionOf) -> Integer.parseInt(board.season()) < FIRST_PROJECTED ? Map.of()
                        : projectedAboveLine(board.season(), g, positionOf, teams, flex, span));
    }

    /** The first season whose week projections the projection chart reads (RosModel's span; all cached). */
    static final int FIRST_PROJECTED = 2018;

    /** Sleeper's projection for each man's next game after g weeks: week g+1, else g+2, in this league's points. */
    static Map<String, Double> nextGame(String season, int g){
        Map<String, Double> out = new HashMap<>();
        if(g + 2 <= EraActuals.weeks(season)){
            out.putAll(LeagueWeek.projected(season, g + 2));
        }
        if(g + 1 <= EraActuals.weeks(season)){
            out.putAll(LeagueWeek.projected(season, g + 1));
        }
        return out;
    }

    /**
     * The mean of each man's projections over weeks g+1 to g+span where he has
     * one - span 1 is {@link #nextGame}. A SENSITIVITY CHECK ONLY: a projection
     * for week g+2 or later was published after the games that followed the
     * trade, so it knows news the man opposite did not. It is here to ask how
     * much of one week's matchup noise was holding the projection's weight down.
     */
    static Map<String, Double> nextGames(String season, int g, int span){
        if(span <= 1){
            return nextGame(season, g);
        }
        Map<String, double[]> sum = new HashMap<>();
        for(int w = g + 1; w <= Math.min(g + span, EraActuals.weeks(season)); w++){
            LeagueWeek.projected(season, w).forEach((id, p) -> {
                double[] s = sum.computeIfAbsent(id, k -> new double[2]);
                s[0] += p;
                s[1]++;
            });
        }
        Map<String, Double> out = new HashMap<>();
        sum.forEach((id, s) -> out.put(id, s[0] / s[1]));
        return out;
    }

    /** Each man's next-game projection less the last starter's projection at his position (skill men only). */
    static Map<String, Double> projectedAboveLine(String season, int g, Map<String, Position> positionOf, int teams, int flex){
        return projectedAboveLine(season, g, positionOf, teams, flex, 1);
    }

    static Map<String, Double> projectedAboveLine(String season, int g, Map<String, Position> positionOf, int teams, int flex,
                                                  int span){
        Map<String, Double> next = nextGames(season, g, span);
        Map<Position, Double> line = line(next, positionOf, teams, flex);
        Map<String, Double> out = new HashMap<>();
        next.forEach((id, p) -> {
            Position position = positionOf.get(id);
            if(skill(position)){
                out.put(id, p - line.get(position));
            }
        });
        return out;
    }

    /** The chart under both: a signal above the line after g games -> what those men were worth over the weeks left. */
    static Chart signalChart(Collection<EraBoards.Board> boards, Map<String, Map<String, Position>> positions, int g, int teams,
                             int flex, java.util.function.BiFunction<EraBoards.Board, Map<String, Position>, Map<String, Double>> signal){
        List<double[]> pairs = new ArrayList<>();
        for(EraBoards.Board board : boards){
            int weeks = board.weekly().size();
            if(g >= weeks){
                continue;
            }
            Map<String, Position> positionOf = positions.computeIfAbsent(board.season(), TradeScreens::positions);
            Map<String, Double> worth = restWorth(board, positionOf, g, teams, flex);
            signal.apply(board, positionOf).forEach((id, above) ->
                    pairs.add(new double[]{-above, worth.getOrDefault(id, 0.0)}));      // the chart falls with its key; x rises
        }
        return fit(pairs);
    }

    /**
     * What each man went on to be worth after g weeks: his points over the
     * weeks left above the last starter's at his position, scaled to 17 games,
     * zero for a man who did not play them (absent from the map).
     */
    static Map<String, Double> restWorth(EraBoards.Board board, Map<String, Position> positionOf, int g, int teams, int flex){
        int weeks = board.weekly().size();
        Map<String, double[]> rest = soFar(board.weekly().subList(g, weeks));
        Map<String, Double> restTotal = new HashMap<>();
        rest.forEach((id, r) -> restTotal.put(id, r[0]));
        Map<Position, Double> line = line(restTotal, positionOf, teams, flex);
        double scale = (double) PACE / (weeks - g);
        Map<String, Double> out = new HashMap<>();
        rest.forEach((id, r) -> {
            Position p = positionOf.get(id);
            if(skill(p)){
                out.put(id, scale * Math.max(0, r[0] - line.get(p)));
            }
        });
        return out;
    }

    /**
     * The pace screen: each man's pace read off the pace chart for the games
     * played ({@link #paceChart}), zero for a man with no game or sidelined.
     */
    static Map<String, Double> predicted(Map<String, double[]> soFar, Map<String, Position> positionOf, int games, int teams,
                                         int flex, Chart paceChart, Set<String> sidelined){
        Map<String, Double> out = new HashMap<>();
        aboveLine(soFar, positionOf, games, teams, flex).forEach((id, x) -> {
            if(!sidelined.contains(id)){
                out.put(id, paceChart.at(-x));
            }
        });
        return out;
    }

    /** A signal read off its chart: each man's value, the men the signal does not cover left out. */
    static Map<String, Double> readOff(Map<String, Double> aboveLine, Chart chart){
        Map<String, Double> out = new HashMap<>();
        aboveLine.forEach((id, x) -> out.put(id, chart.at(-x)));
        return out;
    }

    /** Projection charts by games played, each fitted once on first use. */
    static java.util.function.IntFunction<Chart> projectionCharts(Collection<EraBoards.Board> boards, int teams, int flex){
        return projectionCharts(boards, teams, flex, 1);
    }

    static java.util.function.IntFunction<Chart> projectionCharts(Collection<EraBoards.Board> boards, int teams, int flex, int span){
        Map<String, Map<String, Position>> positions = new HashMap<>();
        Map<Integer, Chart> charts = new HashMap<>();
        return g -> charts.computeIfAbsent(g, k -> projectionChart(boards, positions, k, teams, flex, span));
    }

    /**
     * THIS SEASON'S SCREEN: pace and Sleeper's next-game projection, each read
     * off its own chart, blended in the ratio other leagues' trades put between
     * them at this many games (DraftWeightCurve's three-screen fit, pace's share
     * of the two). A man on neither reads nothing.
     */
    static Map<String, Double> seasonScreen(Map<String, Double> pace, Map<String, Double> projection, double paceShare){
        Set<String> ids = new HashSet<>(pace.keySet());
        ids.addAll(projection.keySet());
        Map<String, Double> out = new HashMap<>();
        for(String id : ids){
            out.put(id, paceShare * pace.getOrDefault(id, 0.0) + (1 - paceShare) * projection.getOrDefault(id, 0.0));
        }
        return out;
    }

    /** Pace charts by games played, each fitted once on first use. */
    static java.util.function.IntFunction<Chart> paceCharts(Collection<EraBoards.Board> boards, int teams, int flex){
        Map<String, Map<String, Position>> positions = new HashMap<>();
        Map<Integer, Chart> charts = new HashMap<>();
        return g -> charts.computeIfAbsent(g, k -> paceChart(boards, positions, k, teams, flex));
    }

    static double ratio(double in, double out){
        return out < NOTHING ? Double.POSITIVE_INFINITY : in / out;
    }

    /** One side of one accepted trade in this league, on both screens. */
    public record Precedent(String season, int week, String manager, String gets, String gives,
                            double draftIn, double draftOut, double pointsIn, double pointsOut, int played) {

        /** A side whose games played are the weeks before its leg (Sleeper's count; tests and old callers). */
        public Precedent(String season, int week, String manager, String gets, String gives,
                         double draftIn, double draftOut, double pointsIn, double pointsOut){
            this(season, week, manager, gets, gives, draftIn, draftOut, pointsIn, pointsOut, Math.max(0, week - 1));
        }
        public double draftRatio(){
            return ratio(draftIn, draftOut);
        }
        public double pointsRatio(){
            return ratio(pointsIn, pointsOut);
        }
        /**
         * Games each man had played when it was made - counted from the clock
         * by history(), the count its season screen was priced at, so the draft
         * weight is read at the same week (the 2026-10-22 review: a Tuesday
         * trade's leg is one short of the games played).
         */
        public double games(){
            return played;
        }
        /** Both screens as one, draft value weighted as the curve says at this side's own week. */
        public double blendedRatio(DoubleUnaryOperator weight){
            double w = weight.applyAsDouble(games());
            return ratio(blend(draftIn, pointsIn, w), blend(draftOut, pointsOut, w));
        }
    }

    /**
     * THE TWO SCREENS AS ONE, 2026-09-29. Justin: "what if draft value is
     * slightly less important, since obviously, people will sell low people who
     * are underperforming or injured, albeit perhaps not low enough." A man's
     * value as the other manager sees it: a share w of it is his draft value,
     * the rest his pace this season.
     *
     * Since 2026-10-22, w after g games is MEASURED, one number per week of the
     * season, on thousands of other leagues' accepted trades (DraftWeightCurve,
     * data/draft-weight.tsv). Until then it was k/(k+g) with one k fitted to
     * this league's sixty trade sides - a shape assumed, one number measured.
     * Justin, on a rule of thumb for the shape: "no. try to make a curve that's
     * not so wishy washy. I don't mind downloading data".
     */
    static double blend(double draft, double pace, double weight){
        return weight * draft + (1 - weight) * pace;
    }

    /**
     * The old one-number form: draft value counted as k games against the g
     * played, a weight of k/(k+g) on draft. Kept for this league's own revealed
     * k, printed beside the measured curve, and as the fallback when the curve
     * has not been measured.
     */
    static DoubleUnaryOperator kForm(double k){
        return games -> k / (k + Math.max(0, games));
    }

    /**
     * The k, in games, that makes this league's accepted trades read most even:
     * the least total |log ratio| over every side made after week 1 (before
     * then k does not matter) with value both ways. Absolute rather than squared
     * so a handful of lopsided deals cannot drag it (TradePartners: "many trades
     * were lopsided"). Both sides of a trade carry reciprocal ratios, so each
     * trade counts twice, evenly.
     */
    static double revealedK(List<Precedent> sides, double[] grid){
        double best = grid[0], lowest = Double.MAX_VALUE;
        for(double k : grid){
            double loss = evenness(sides, k);
            if(loss < lowest){
                lowest = loss;
                best = k;
            }
        }
        return best;
    }

    /** Mean |log blended ratio| over the sides that inform k. */
    static double evenness(List<Precedent> sides, double k){
        double total = 0;
        int n = 0;
        for(Precedent p : sides){
            if(informs(p)){
                total += Math.abs(Math.log(p.blendedRatio(kForm(k))));
                n++;
            }
        }
        return n == 0 ? 0 : total / n;
    }

    /** Made after week 1, with value going both ways on the blend at any k. */
    static boolean informs(Precedent p){
        return p.games() > 0 && p.draftIn() + p.pointsIn() >= NOTHING && p.draftOut() + p.pointsOut() >= NOTHING
                && p.draftIn() > 0 && p.draftOut() > 0;
    }

    static final double[] K_GRID = {0.25, 0.5, 1, 1.5, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 30, 50, 100, 1000};

    /**
     * A trade as the man opposite sees it: what he receives and sends on each
     * screen, and who in this league ever took as little. `with` is the man
     * opposite, so a precedent can be told apart from his own.
     */
    public record Screens(double draftIn, double draftOut, double pointsIn, double pointsOut, double weight, double games,
                          List<String> takenBy, int sides, String with) {

        public double draftRatio(){
            return ratio(draftIn, draftOut);
        }

        public double pointsRatio(){
            return ratio(pointsIn, pointsOut);
        }

        /** What he gets back on what he can see: both screens blended, a share `weight` of it draft value. */
        public double blendedRatio(){
            return ratio(blend(draftIn, pointsIn, weight), blend(draftOut, pointsOut, weight));
        }

        /** Sides of accepted trades here that took as little on the blend, each read at its own week. */
        public int precedents(){
            return takenBy.size();
        }

        /** Of those, his own. */
        public int his(){
            int n = 0;
            for(String m : takenBy){
                if(m.equals(with)){
                    n++;
                }
            }
            return n;
        }

        /** He does not lose on what he can see. */
        public boolean fairToHim(){
            return blendedRatio() >= 1;
        }

        /**
         * He loses, and fewer than one accepted side in ten here ever took as
         * little. The one-in-ten is a choice, not a measurement: accepted trades
         * cannot say what was refused, and a single lopsided yes from somebody
         * else is not a reason he would say yes (Nabers + Stevenson for Chase).
         */
        public boolean noChance(){
            return !fairToHim() && (precedents() == 0 || precedents() < ASK_SHARE * sides);
        }

        /** Behind on both numbers separately - shown, no longer a rule of its own. */
        public boolean losesOnBoth(){
            return draftRatio() < 1 && pointsRatio() < 1;
        }

        /** Worth putting in front of him at all. */
        public boolean worthAsking(){
            return !noChance();
        }

        /** "78%/0% = 57%": draft value and this season's points he gets back, then the blend of the two. */
        public String ratios(){
            return share(draftRatio()) + "/" + share(pointsRatio()) + "=" + share(blendedRatio());
        }

        public String verdict(){
            String took = String.format("he gets back %s of what he gives on what he can see (%s of the draft value, %s of the"
                    + " season screen - pace and Sleeper's projection; after %s games, draft value is %s of what a man is worth in other leagues' trades)",
                    share(blendedRatio()), share(draftRatio()), share(pointsRatio()), fmt(games), share(weight));
            if(fairToHim()){
                return "fair to him - " + took;
            }
            String who = precedents() + " of " + sides + " accepted sides here took as little"
                    + (takenBy.isEmpty() ? "" : " (" + String.join(", ", new java.util.TreeSet<>(takenBy)) + ")")
                    + (with == null || takenBy.isEmpty() ? "" : his() == 0 ? ", never " + with : "");
            return (noChance() ? "NO - " : "a long shot - ") + took + "; " + who;
        }

        static String share(double r){
            return Double.isInfinite(r) ? "-" : String.format("%.0f%%", 100 * r);
        }
    }

    /** Below this share of accepted sides taking as little, a trade is not worth asking (see Screens.noChance). */
    static final double ASK_SHARE = 0.10;

    /** The managers of the sides of accepted trades that took as little on the blend (each at its own week), one entry a side. */
    static List<String> precedents(double blendedRatio, DoubleUnaryOperator weight, List<Precedent> history){
        List<String> out = new ArrayList<>();
        for(Precedent p : history){
            if(p.blendedRatio(weight) <= blendedRatio){
                out.add(p.manager());
            }
        }
        return out;
    }

    /** One trade from HIS side: he receives hisIn and sends hisOut. */
    static Screens screen(List<String> hisIn, List<String> hisOut, ToDoubleFunction<String> draft,
                          ToDoubleFunction<String> points, List<Precedent> history, String with, DoubleUnaryOperator weight,
                          double games){
        double draftIn = 0, draftOut = 0, pointsIn = 0, pointsOut = 0;
        for(String id : hisIn){
            draftIn += draft.applyAsDouble(id);
            pointsIn += points.applyAsDouble(id);
        }
        for(String id : hisOut){
            draftOut += draft.applyAsDouble(id);
            pointsOut += points.applyAsDouble(id);
        }
        double w = weight.applyAsDouble(games);
        double blended = ratio(blend(draftIn, pointsIn, w), blend(draftOut, pointsOut, w));
        return new Screens(draftIn, draftOut, pointsIn, pointsOut, w, games, precedents(blended, weight, history),
                history.size(), with);
    }

    /** Every side of every completed player-for-player trade in the league's finished seasons, on both screens. */
    record History(List<Precedent> sides, int trades, int withPicks, int noBoard) {}

    static History history(String leagueID, Map<String, EraBoards.Board> boards, Chart chart, int teams, int flex,
                           java.util.function.IntFunction<Chart> paceCharts, java.util.function.IntFunction<Chart> projectionCharts,
                           DoubleUnaryOperator paceShare){
        Map<String, TreeMap<Integer, java.time.LocalDateTime>> ends = DraftWeightCurve.weekEnds(NflverseGames.games());
        List<Precedent> sides = new ArrayList<>();
        int trades = 0, withPicks = 0, noBoard = 0;
        for(LeagueTransactions.Year year : LeagueTransactions.completedSeasons(leagueID)){
            String season = year.season();
            EraBoards.Board board = boards.containsKey(season) ? boards.get(season) : EraBoards.tryBuild(season, "ppr");
            Map<String, Position> positionOf = positions(season);
            Map<String, String> nameOf = names(season);
            Map<Integer, String> managerOf = TradePartners.managersOf(year.leagueID());
            Map<Integer, Map<String, Double>> productionAt = new HashMap<>();
            for(int w = 1; w <= EraActuals.weeks(season); w++){
                JsonArray rows = JsonParser.parseString(LeagueTransactions.transactionsRaw(year.leagueID(), w)).getAsJsonArray();
                for(JsonElement element : rows){
                    JsonObject row = element.getAsJsonObject();
                    if(!"trade".equals(EraBoards.text(row, "type")) || !"complete".equals(EraBoards.text(row, "status"))){
                        continue;
                    }
                    trades++;
                    if(nonEmpty(row, "draft_picks") || nonEmpty(row, "waiver_budget")){
                        withPicks++;       // a pick or FAAB is on neither screen, so the trade would read as a gift
                        continue;
                    }
                    if(board == null){
                        noBoard++;
                        continue;
                    }
                    int leg = row.has("leg") && !row.get("leg").isJsonNull() ? row.get("leg").getAsInt() : w;
                    // games played from the clock when the schedule and the row allow it, as DraftWeightCurve counts them
                    int played = Math.max(0, Math.min(ends.containsKey(season) && row.has("created")
                            ? DraftWeightCurve.gamesPlayed(ends.get(season), row.get("created").getAsLong()) : leg - 1,
                            board.weekly().size()));
                    Map<String, Double> production = productionAt.computeIfAbsent(played, k -> k == 0 ? Map.of() : seasonScreen(
                            predicted(soFar(board.weekly().subList(0, k)), positionOf, k, teams, flex, paceCharts.apply(k), Set.of()),
                            Integer.parseInt(season) < FIRST_PROJECTED ? Map.of()
                                    : readOff(projectedAboveLine(season, k, positionOf, teams, flex), projectionCharts.apply(k)),
                            paceShare.applyAsDouble(k)));
                    Map<Integer, List<String>> in = byRoster(row, "adds");
                    Map<Integer, List<String>> out = byRoster(row, "drops");
                    Set<Integer> rosters = new LinkedHashSet<>(in.keySet());
                    rosters.addAll(out.keySet());
                    for(int roster : rosters){
                        List<String> gets = in.getOrDefault(roster, List.of());
                        List<String> gives = out.getOrDefault(roster, List.of());
                        ToDoubleFunction<String> draft = id -> skill(positionOf.get(id))
                                ? chart.at(board.adp().getOrDefault(id, 999.0)) : 0;
                        ToDoubleFunction<String> points = id -> production.getOrDefault(id, 0.0);
                        sides.add(new Precedent(season, leg, managerOf.getOrDefault(roster, "roster " + roster),
                                named(gets, nameOf), named(gives, nameOf),
                                sum(gets, draft), sum(gives, draft), sum(gets, points), sum(gives, points), played));
                    }
                }
            }
        }
        return new History(sides, trades, withPicks, noBoard);
    }

    static double sum(List<String> ids, ToDoubleFunction<String> f){
        double total = 0;
        for(String id : ids){
            total += f.applyAsDouble(id);
        }
        return total;
    }

    static boolean nonEmpty(JsonObject row, String key){
        JsonElement e = row.get(key);
        return e != null && e.isJsonArray() && e.getAsJsonArray().size() > 0;
    }

    /** roster id -> the players a trade row moves to it (adds) or from it (drops). */
    static Map<Integer, List<String>> byRoster(JsonObject row, String key){
        Map<Integer, List<String>> out = new HashMap<>();
        JsonElement e = row.get(key);
        if(e != null && e.isJsonObject()){
            for(Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()){
                if(!entry.getValue().isJsonNull()){
                    out.computeIfAbsent(entry.getValue().getAsInt(), k -> new ArrayList<>()).add(entry.getKey());
                }
            }
        }
        return out;
    }

    static Map<String, String> names(String season){
        Map<String, String> out = new HashMap<>();
        for(JsonElement element : EraActuals.skillRows(season)){
            JsonObject row = element.getAsJsonObject();
            JsonObject player = row.getAsJsonObject("player");
            if(player != null && row.has("player_id")){
                out.put(row.get("player_id").getAsString(),
                        EraBoards.text(player, "first_name") + " " + EraBoards.text(player, "last_name"));
            }
        }
        return out;
    }

    static String named(List<String> ids, Map<String, String> nameOf){
        List<String> out = new ArrayList<>();
        for(String id : ids){
            Player p = nameOf.containsKey(id) ? null : Player.getPlayerFromSIDV2(id);
            out.add(nameOf.containsKey(id) ? nameOf.get(id) : p == null ? id : p.firstName + " " + p.lastName);
        }
        return out.isEmpty() ? "nothing" : String.join(" + ", out);
    }

    /**
     * Everything a caller needs to put today's trades on both screens. `weight`
     * is the draft share by games played (DraftWeightCurve); `k` is this
     * league's own revealed k, the old form, kept for the report.
     */
    public record Context(Chart chart, History history, ToDoubleFunction<String> draft, ToDoubleFunction<String> points,
                          int weeksPlayed, DoubleUnaryOperator weight, double k) {

        /** A trade from his side: he receives what Justin gives and sends what Justin gets (and any man he must cut). */
        public Screens of(TradeMarket.Trade trade){
            return screen(trade.give(), trade.hisOut(), draft, points, history.sides(), trade.withManager(), weight, weeksPlayed);
        }

        /** The draft share today. */
        public double weightNow(){
            return weight.applyAsDouble(weeksPlayed);
        }
    }

    public static Context load(AAAConfiguration configuration){
        int teams = configuration.getLeagueJson().getAsJsonObject("settings").get("num_teams").getAsInt();
        int flex = StartingLineup.flexSlotsPerTeam(configuration);
        Map<String, EraBoards.Board> boards = EraBoards.usable("ppr", EraIngest.MIN_RATE, EraIngest.minDepth());
        Chart chart = chart(boards.values(), teams, flex);
        java.util.function.IntFunction<Chart> paceCharts = paceCharts(boards.values(), teams, flex);
        java.util.function.IntFunction<Chart> projectionCharts = projectionCharts(boards.values(), teams, flex);
        DoubleUnaryOperator measuredShare = DraftWeightCurve.paceShare();
        DoubleUnaryOperator paceShare = measuredShare == null ? g -> 1.0 : measuredShare;
        History history = history(configuration.getLeagueID(), boards, chart, teams, flex, paceCharts, projectionCharts, paceShare);

        String season = LeagueWeek.season();
        int week = LeagueWeek.week();
        int played = played(season, week);
        List<Map<String, Double>> weeks = new ArrayList<>();
        for(int w = 1; w <= played; w++){
            weeks.add(LeagueWeek.actualSoFar(season, w));
        }
        Map<String, double[]> sums = soFar(weeks);
        Map<String, Position> positionOf = new HashMap<>();
        Set<String> sidelined = new HashSet<>();
        for(String id : sums.keySet()){
            Player p = Player.getPlayerFromSIDV2(id);
            if(p != null && p.position != null){
                positionOf.put(id, p.position);
            }
            String status = SleeperProjections.injuryStatusOf(id);
            if(status != null && SIDELINED.contains(status)){
                sidelined.add(id);
            }
        }
        // Sleeper's projection for each man's next game, today's feed, in this league's points
        Map<String, Position> projectedOf = new HashMap<>(positionOf);
        for(String id : nextGame(season, played).keySet()){
            Player p = Player.getPlayerFromSIDV2(id);
            if(p != null && p.position != null){
                projectedOf.putIfAbsent(id, p.position);
            }
        }
        Map<String, Double> production = played == 0 ? Map.of() : seasonScreen(
                predicted(sums, positionOf, played, teams, flex, paceCharts.apply(played), sidelined),
                readOff(projectedAboveLine(season, played, projectedOf, teams, flex), projectionCharts.apply(played)),
                paceShare.applyAsDouble(played));
        ToDoubleFunction<String> draft = id -> {
            Player p = Player.getPlayerFromSIDV2(id);
            return p != null && skill(p.position) ? chart.at(SleeperProjections.adpOf(id)) : 0;
        };
        double k = revealedK(history.sides(), K_GRID);
        DoubleUnaryOperator weight = DraftWeightCurve.curve();
        if(weight == null){
            System.err.println("TradeScreens: " + DraftWeightCurve.TABLE + " has no three-screen curve (curve3) to read, so"
                    + " draft value falls back to the old k-games form (k = " + fmt(k) + "); run OtherLeagues and DraftWeightCurve");
            weight = kForm(k);
        }
        return new Context(chart, history, draft, id -> production.getOrDefault(id, 0.0), played, weight, k);
    }

    /**
     * Weeks of games finished now, from nflverse's schedule - Sleeper's week
     * is the week being played (on 2026-10-22, before Thursday's game, it said
     * 7 with six weeks played, and the screens had read 7 as games played),
     * and the curve reads games played. Sleeper's week less one when the
     * schedule does not carry the season, or when -Pweek asks for an earlier
     * week (the screens as they stood before that week's games).
     */
    static int played(String season, int sleeperWeek){
        if(Integer.getInteger("week") != null){
            return Math.max(0, sleeperWeek - 1);     // -Pweek=N: the screens as they stood before week N's games
        }
        TreeMap<Integer, java.time.LocalDateTime> ends = DraftWeightCurve.weekEnds(NflverseGames.games()).get(season);
        return ends == null ? Math.max(0, sleeperWeek - 1) : DraftWeightCurve.gamesPlayed(ends, System.currentTimeMillis());
    }

    /** Today's men with these full names (a team, so a retired namesake is not picked up), in order. */
    static List<String> idsNamed(String csv) throws IOException {
        JsonObject db;
        db = PlayerRawData.database();   // through the in-season daily expiry
        List<String> out = new ArrayList<>();
        for(String raw : csv.split(",")){
            String name = raw.trim();
            if(name.isEmpty()){
                continue;
            }
            for(Map.Entry<String, JsonElement> e : db.entrySet()){
                JsonObject p = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : null;
                if(p != null && KalshiFair.normal(name).equals(KalshiFair.normal(ScreenData.text(p, "full_name")))
                        && ScreenData.text(p, "team") != null){
                    out.add(e.getKey());
                }
            }
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Context context = load(configuration);
        History history = context.history();
        StringBuilder out = new StringBuilder();
        out.append(String.format("TRADE SCREENS  %s  (what the man opposite sees; season %s, week %d)%n%n", LocalDate.now(),
                LeagueWeek.season(), context.weeksPlayed()));

        out.append("THE DRAFT-VALUE CHART - season points above the last starter at his position, for a man drafted at each ADP\n");
        out.append("(every board EraBoards joins to outcomes, fitted to fall with ADP):\n  ");
        for(double adp : new double[]{1, 4, 8, 12, 18, 24, 29, 36, 48, 60, 72, 96, 120, 150, 180}){
            out.append(String.format("%.0f:%.0f  ", adp, context.chart().at(adp)));
        }
        out.append(String.format("%n  So pick 4 against pick 29 is %.0f against %.0f, and pick 104 against 129 is %.0f against %.0f -%n"
                        + "  the same 25 picks, which the old straight-line check could not tell apart.%n%n",
                context.chart().at(4), context.chart().at(29), context.chart().at(104), context.chart().at(129)));

        List<Precedent> sides = new ArrayList<>(history.sides());
        out.append(String.format("THIS LEAGUE'S ACCEPTED TRADES on the same screens: %d completed trades in its finished seasons; %d carried%n"
                        + "a draft pick or FAAB (on neither screen, so left out) and %d fell in a season with no joined board.%n"
                        + "That leaves %d sides. Each side: what it got back on each screen, over what it gave.%n",
                history.trades(), history.withPicks(), history.noBoard(), sides.size()));
        sides.sort(Comparator.comparingDouble((Precedent p) -> Math.max(p.draftRatio(), p.pointsRatio())));
        out.append(String.format("  %-6s %-4s %-14s %8s %8s   %s%n", "season", "wk", "manager", "draft", "points", "GOT  <-  GAVE"));
        for(Precedent p : sides.subList(0, Math.min(20, sides.size()))){
            out.append(String.format("  %-6s %-4d %-14s %8s %8s   %s  <-  %s%n", p.season(), p.week(), p.manager(),
                    Screens.share(p.draftRatio()), Screens.share(p.pointsRatio()), p.gets(), p.gives()));
        }
        out.append("  (the twenty most lopsided against the side named, by the better of its two screens)\n");

        // HOW MUCH DRAFT POSITION HOLDS OUT AGAINST RESULTS, revealed by the trades
        List<Precedent> informing = new ArrayList<>();
        for(Precedent p : sides){
            if(informs(p)){
                informing.add(p);
            }
        }
        double k = context.k();
        java.util.Random random = new java.util.Random(7);
        List<Double> boot = new ArrayList<>();
        for(int b = 0; b < 1000; b++){
            List<Precedent> draw = new ArrayList<>();
            for(int i = 0; i < informing.size(); i++){
                draw.add(informing.get(random.nextInt(informing.size())));
            }
            boot.add(revealedK(draw, K_GRID));
        }
        boot.sort(Double::compare);
        out.append(String.format("%nHOW LONG DRAFT POSITION HOLDS OUT AGAINST RESULTS, in this league's accepted trades. Each side's value%n"
                + "is (k x draft value + g x this season's pace) / (k + g), g the games played when the trade was made; k is the%n"
                + "one that makes the %d sides made after week 1 read most even (mean |log ratio|):%n  ", informing.size()));
        for(double kk : K_GRID){
            out.append(String.format("k=%s:%.3f  ", kk % 1 == 0 ? String.valueOf((int) kk) : String.valueOf(kk), evenness(sides, kk)));
        }
        out.append(String.format("%n  revealed k = %s games (90%% bootstrap over sides: %s to %s). Draft position counts as that many%n"
                        + "  games of results; k = 1000 is draft value alone, k = 0.25 is this season's pace alone.%n",
                fmt(k), fmt(boot.get(50)), fmt(boot.get(949))));
        out.append(String.format("%nTHE WEIGHT THE SCREENS USE, measured week by week on other leagues' accepted trades (DraftWeightCurve):%n"
                + "  games played: draft value's share (this league's k = %s, the old form, would say)%n  ", fmt(k)));
        for(int g = 1; g <= 12; g++){
            out.append(String.format("%d: %.0f%% (%.0f%%)  ", g, 100 * context.weight().applyAsDouble(g),
                    100 * kForm(k).applyAsDouble(g)));
        }
        out.append('\n');
        List<Double> blended = new ArrayList<>();
        for(Precedent p : sides){
            blended.add(p.blendedRatio(context.weight()));
        }
        blended.sort(Double::compare);
        out.append(String.format("  On that blend, what accepted sides got back: 10th percentile %s, 25th %s, median %s. A trade that%n"
                        + "  gives him less than the 10th percentile is a NO (Screens.noChance).%n",
                Screens.share(blended.get(blended.size() / 10)), Screens.share(blended.get(blended.size() / 4)),
                Screens.share(blended.get(blended.size() / 2))));

        String give = System.getProperty("send", "");
        String get = System.getProperty("receive", "");
        if(!give.isBlank() && !get.isBlank()){
            List<String> hisIn = idsNamed(give);
            List<String> hisOut = idsNamed(get);
            Map<String, String> ownerOf = LeagueOwners.today(configuration);
            Screens s = screen(hisIn, hisOut, context.draft(), context.points(), sides,
                    hisOut.isEmpty() ? null : ownerOf.get(hisOut.get(0)), context.weight(), context.weeksPlayed());
            out.append(String.format("%nTHE TRADE - you give %s, you get %s. From HIS side:%n", give, get));
            for(String id : hisIn){
                out.append(String.format("  he gets  %-24s draft %6.1f   season %6.1f%n", name(id), context.draft().applyAsDouble(id),
                        context.points().applyAsDouble(id)));
            }
            for(String id : hisOut){
                out.append(String.format("  he gives %-24s draft %6.1f   season %6.1f%n", name(id), context.draft().applyAsDouble(id),
                        context.points().applyAsDouble(id)));
            }
            out.append(String.format("  totals: draft %.1f for %.1f, season %.1f for %.1f%n  %s%n", s.draftIn(), s.draftOut(),
                    s.pointsIn(), s.pointsOut(), s.verdict()));
            out.append(objective(configuration, ownerOf, hisIn, hisOut));
        }
        System.out.print(out);
        Path report = Path.of("data", "trade-screens-" + LocalDate.now() + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }

    /**
     * WHY THE OBJECTIVE SAID WHAT IT SAID. Justin, 2026-09-27: "I feel like the
     * trade evaluator must be wrong as well to give up chase in this situation."
     * So the same trade is priced for both managers on each projection source
     * the trade tools can use, and his best lineup is printed before and after
     * on the one that moves on news, so the slot the trade is supposed to fill
     * can be seen rather than inferred. The side receiving more men cuts his
     * lowest-projected other man, as TradeMarket.unbalanced does.
     */
    static String objective(AAAConfiguration configuration, Map<String, String> ownerOf, List<String> mySend,
                            List<String> myReceive) throws Exception {
        String me = configuration.getUserIDToDisplayName().getOrDefault(configuration.getMyID(), configuration.getMyID());
        Map<String, List<String>> rosters = new HashMap<>();
        ownerOf.forEach((id, m) -> rosters.computeIfAbsent(m, k -> new ArrayList<>()).add(id));
        String with = ownerOf.get(myReceive.get(0));
        // -Passume="Kyler Murray": free agents taken as already claimed into Justin's
        // roster before the trade is priced. Justin, 2026-10-20: "does trade make sense
        // if I end up with kyler murray" - the trade is priced against the roster he
        // would have, and the title odds as the change from that roster, not from today's
        List<String> assumed = idsNamed(System.getProperty("assume", ""));
        List<String> today = rosters.get(me);
        List<String> mine = new ArrayList<>(today);
        mine.addAll(assumed);
        List<String> his = rosters.get(with);
        StringBuilder out = new StringBuilder(String.format(
                "%nWHAT THE OBJECTIVE SAYS - 17 x the best legal lineup, the bench as insurance, 240 drawn seasons - on each pricing%s:%n",
                assumed.isEmpty() ? "" : ", with " + String.join(", ", assumed.stream().map(TradeScreens::name).toList())
                        + " already on your roster"));
        out.append(String.format("  %-18s %9s %9s   %s%n", "pricing", "you", with, "his cut (if he takes more men)"));
        Map<String, Double> remaining = null;
        for(String source : List.of("sleeper", "sleeper-remaining", "ros")){
            Map<String, Double> points = ProjectionSources.resolve(source);
            if(source.equals("sleeper-remaining")){
                remaining = points;
            }
            WeeklyStarterValue value = WeeklyStarterValue.forCurrentBoard(configuration, points, 240, 424_242L);
            List<String> mySends = new ArrayList<>(mySend);
            List<String> hisSends = new ArrayList<>(myReceive);
            String cut = null;
            if(mySend.size() > myReceive.size()){
                cut = TradeMarket.worstOther(his, myReceive, points);
                hisSends.add(cut);
            }
            if(myReceive.size() > mySend.size()){
                mySends.add(TradeMarket.worstOther(mine, mySend, points));
            }
            double myGain = value.of(TradeMarket.swap(mine, mySends, myReceive)) - value.of(mine);
            double hisGain = value.of(TradeMarket.swap(his, hisSends, mySend)) - value.of(his);
            out.append(String.format("  %-18s %+9.1f %+9.1f   %s%n", source, myGain, hisGain, cut == null ? "" : name(cut)));
        }
        out.append(String.format("%n  %s's best lineup on sleeper-remaining (Sleeper's weekly projections over the games left):%n", with));
        List<String> after = new ArrayList<>(his);
        after.removeAll(myReceive);
        if(mySend.size() > myReceive.size()){
            after.remove(TradeMarket.worstOther(his, myReceive, remaining));
        }
        after.addAll(mySend);
        TeamRankings.Lineup before = lineup(his, remaining);
        TeamRankings.Lineup then = lineup(after, remaining);
        out.append(String.format("  %-34s %-34s%n", "BEFORE", "AFTER"));
        for(int i = 0; i < Math.max(before.starting().size(), then.starting().size()); i++){
            out.append(String.format("  %-34s %-34s%n", slot(before.starting(), i), slot(then.starting(), i)));
        }
        out.append(String.format("  %-34s %-34s%n", String.format("starters %.1f", before.starters()),
                String.format("starters %.1f", then.starters())));

        // AND WHAT IT DOES TO THE TITLE (TitleOdds), for both managers, every man held
        // counted - a man in an IR slot now is projected back when he is
        List<String> myAfter = new ArrayList<>(mine);
        myAfter.removeAll(mySend);
        if(myReceive.size() > mySend.size()){
            myAfter.remove(TradeMarket.worstOther(mine, mySend, remaining));
        }
        myAfter.addAll(myReceive);
        TitleOdds.Stakes stakes = TitleOdds.stakes(configuration, TitleOdds.SIMS);
        TitleOdds.Delta[] title = stakes.swap(me, myAfter, with, after);
        // with an assumed claim, the trade is the change FROM the roster with him: both
        // read off the same draws, so the difference is the trade alone
        TitleOdds.Delta[] claimOnly = assumed.isEmpty() ? new TitleOdds.Delta[]{new TitleOdds.Delta(0, 0), new TitleOdds.Delta(0, 0)}
                : stakes.swap(me, mine, with, his);
        out.append(String.format("%n  TITLE ODDS (TitleOdds, %d seasons, the same draws before and after)%s:%n"
                        + "    you   %5.1f%%  %+.2fpp (+-%.2f)%n    %-5s %5.1f%%  %+.2fpp (+-%.2f)   he is %s%n",
                TitleOdds.SIMS, assumed.isEmpty() ? "" : String.format(" - the claim alone moves you %+.2fpp; the trade on top of it:",
                        100 * claimOnly[0].change()),
                100 * (stakes.title(me) + claimOnly[0].change()), 100 * (title[0].change() - claimOnly[0].change()), 100 * title[0].se(),
                with, 100 * stakes.title(with), 100 * (title[1].change() - claimOnly[1].change()), 100 * title[1].se(), stakes.state(with)));
        out.append(keepers(configuration, ownerOf, today, mine, myAfter, assumed));
        return out.toString();
    }

    /**
     * KEEPERS (2027), with TradeMarket's own arithmetic: each man's surplus over
     * the pick his keeper round costs, priced on the season feed, and the best
     * legal pair. Justin, 2026-10-20: "what about keeper value" - the Chase trade
     * sent Tuten, his best keeper, and the single-trade check did not say so.
     * An assumed claim is priced at the round this league's draft set for him.
     */
    static String keepers(AAAConfiguration configuration, Map<String, String> ownerOf, List<String> today,
                          List<String> withClaims, List<String> afterTrade, List<String> assumed){
        Map<String, Double> seasonFeed = ProjectionSources.resolve("sleeper");
        Map<String, Position> positionOf = new HashMap<>();
        java.util.Set<String> ids = new HashSet<>(ownerOf.keySet());
        ids.addAll(assumed);
        for(String id : ids){
            Player p = Player.getPlayerFromSIDV2(id);
            positionOf.put(id, p == null ? null : p.position);
        }
        Map<String, Position> everyPosition = TradeMarket.everyPosition(seasonFeed, positionOf);
        Map<Position, java.util.TreeMap<Double, Double>> bestByAdp = TradeMarket.bestStillAvailable(seasonFeed, everyPosition);
        Map<String, Integer> keeperRound = NextYearKeepers.roundsForThisLeague(configuration, ids);
        Map<String, Integer> slotOfPlayer = TradeMarket.draftSlotOfPlayer(ownerOf, configuration);
        Map<String, Double> surplus = new HashMap<>();
        for(String id : keeperRound.keySet()){
            surplus.put(id, TradeMarket.keeperPoints(keeperRound, seasonFeed, bestByAdp, everyPosition, configuration, slotOfPlayer, id));
        }
        java.util.function.Function<List<String>, String> pair = roster -> {
            List<String> best = TradeMarket.bestKeeperPair(roster, surplus, everyPosition);
            List<String> named = new ArrayList<>();
            for(String id : best){
                named.add(String.format("%s (r%s, %+.1f)", name(id), keeperRound.get(id), surplus.getOrDefault(id, 0.0)));
            }
            return String.format("%.1f  %s", TradeMarket.keeperValue(roster, surplus, everyPosition), String.join(" + ", named));
        };
        StringBuilder out = new StringBuilder(String.format("%n  KEEPERS (2027) - your best two keepers' surplus over the picks they cost (TradeMarket's arithmetic):%n"));
        out.append(String.format("    today            %s%n", pair.apply(today)));
        if(!assumed.isEmpty()){
            out.append(String.format("    with the claim   %s%n", pair.apply(withClaims)));
        }
        out.append(String.format("    after the trade  %s%n", pair.apply(afterTrade)));
        out.append(String.format("    the trade's keeper cost: %+.1f%n", TradeMarket.keeperValue(afterTrade, surplus, everyPosition)
                - TradeMarket.keeperValue(withClaims, surplus, everyPosition)));
        return out.toString();
    }

    static TeamRankings.Lineup lineup(List<String> roster, Map<String, Double> points){
        List<TeamRankings.Man> men = new ArrayList<>();
        for(String id : roster){
            Player p = Player.getPlayerFromSIDV2(id);
            if(p != null && p.position != null){
                men.add(new TeamRankings.Man(id, p.firstName + " " + p.lastName, p.position.name(), p.team,
                        points.getOrDefault(id, 0.0), false, 0, null));
            }
        }
        return TeamRankings.bestLineup(men);
    }

    static String slot(List<TeamRankings.Man> starting, int i){
        if(i >= starting.size()){
            return "";
        }
        TeamRankings.Man m = starting.get(i);
        return String.format("%-3s %-22s %6.1f", m.position(), m.name(), m.points());
    }

    static String fmt(double k){
        return k % 1 == 0 ? String.valueOf((int) k) : String.valueOf(k);
    }

    static String name(String id){
        Player p = Player.getPlayerFromSIDV2(id);
        return p == null ? id : p.firstName + " " + p.lastName;
    }
}
