import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

/**
 * WHAT A MAN WHO IS OUT FOR A WHILE FETCHES IN A TRADE, measured.
 *
 * Justin, 2026-11-07: "is there a way to have like a way to consider the ir as per the jfmarino,
 * since a lot of trades would not be as enticing for others as my current model shows if their
 * players are on ir". TradeScreens read such a man at zero on both pace and projection, so a trade
 * sending him cost the other manager nothing, and yet Sleeper knows when he is due back. Before
 * replacing that zero, this asks what managers really paid: in other leagues' completed trades,
 * for a man with no projection in the next two weeks and one later, what share of his worth when
 * back made the two sides even.
 *
 * Each trade with such men on one side only gives one answer: (other side - his side's healthy men)
 * / the absent men's full worth, on the season screen (pace and next projection, each off its chart,
 * in the ratio other leagues' trades put between them; a returning man's projection is his first
 * one back, or his average over the weeks he is projected; a man with no game yet reads on
 * projection alone). That share is not a discount by itself: adding men up does the same to a
 * healthy man, so each answer is also set against healthy men in trades of the same shape and
 * nearest worth, and that gap is the number to read.
 *
 * WHAT THIS CANNOT SAY. A season's week projections were published at that week, so who is absent
 * and when he is due back are known here earlier than they were then: a man hurt after the trade
 * reads as a man traded while hurt, which is why trades where he had already missed the last week
 * are split out, and nothing here shows how a known long absence was priced. Past about six weeks
 * there are few men. Draft value is left out of the headline because Justin's page drops it from
 * eight games on, but other leagues' managers traded on it, so it is reported as well.
 *
 *   ./gradlew run -Pmain=AwayPrice
 */
public class AwayPrice {

    /** Under this a row is not a projection: Sleeper publishes 0.0 and 0.1 rows for men it has written off. */
    static final double REAL = 1.0;

    /** A man's weeks after g games: the first week he is projected, its points, how many of the weeks left he is projected in, and his average in those. */
    record Back(int week, double points, int weeks, int weeksLeft, double average) {
        double share(){
            return weeksLeft == 0 ? 0 : (double) weeks / weeksLeft;
        }
    }

    /** Every man with a real projection in some week from g+1 on. */
    static Map<String, Back> backs(List<Map<String, Double>> weeksAfterG){
        Map<String, Integer> count = new HashMap<>();
        Map<String, double[]> first = new HashMap<>();
        Map<String, Double> total = new HashMap<>();
        for(int k = 0; k < weeksAfterG.size(); k++){
            int at = k;
            weeksAfterG.get(k).forEach((id, points) -> {
                if(points >= REAL){
                    count.merge(id, 1, Integer::sum);
                    total.merge(id, points, Double::sum);
                    first.putIfAbsent(id, new double[]{at, points});
                }
            });
        }
        Map<String, Back> out = new HashMap<>();
        first.forEach((id, f) -> out.put(id, new Back((int) f[0], f[1], count.get(id), weeksAfterG.size(), total.get(id) / count.get(id))));
        return out;
    }

    /** Those with no projection in the next two weeks. */
    static Map<String, Back> absent(List<Map<String, Double>> weeksAfterG){
        Map<String, Back> out = backs(weeksAfterG);
        out.values().removeIf(back -> back.week() < 2);
        return out;
    }

    record Sample(double implied, double impliedAverage, double full, double fullAverage, double healthy, double other, double share,
                  int weeksAway, boolean alone, boolean missedLastWeek, int games, int shape) {}

    private static final class Tally {
        final List<Sample> samples = new ArrayList<>();
        final Map<Integer, List<double[]>> control = new HashMap<>();    // shape -> {worth, implied} of healthy men, by worth

        double gap(double implied, double full, int shape){
            List<double[]> men = control.get(shape);
            if(men == null || men.isEmpty()){
                return Double.NaN;
            }
            int at = 0;
            while(at < men.size() - 1 && men.get(at)[0] < full){
                at++;
            }
            double[] near = new double[Math.min(5, men.size())];
            int from = Math.max(0, Math.min(at - 2, men.size() - near.length));
            for(int i = 0; i < near.length; i++){
                near[i] = men.get(from + i)[1];
            }
            Arrays.sort(near);
            return implied - near[near.length / 2];
        }
    }

    public static void main(String[] args) throws IOException {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int teams = configuration.getLeagueJson().getAsJsonObject("settings").get("num_teams").getAsInt();
        int flex = StartingLineup.flexSlotsPerTeam(configuration);
        Map<String, EraBoards.Board> boards = EraBoards.usable("ppr", EraIngest.MIN_RATE, EraIngest.minDepth());
        TradeScreens.Chart chart = TradeScreens.chart(boards.values(), teams, flex);
        java.util.function.IntFunction<TradeScreens.Chart> paceCharts = TradeScreens.paceCharts(boards.values(), teams, flex);
        java.util.function.IntFunction<TradeScreens.Chart> projectionCharts = TradeScreens.projectionCharts(boards.values(), teams, flex);
        java.util.function.DoubleUnaryOperator measured = DraftWeightCurve.paceShare();
        java.util.function.DoubleUnaryOperator paceShare = measured == null ? g -> 1.0 : measured;
        java.util.function.DoubleUnaryOperator draftCurve = DraftWeightCurve.curve();
        Map<String, TreeMap<Integer, LocalDateTime>> ends = DraftWeightCurve.weekEnds(NflverseGames.games());
        Map<String, Map<String, Position>> positions = new HashMap<>();
        Map<String, Map<Integer, Map<String, Back>>> absentCache = new HashMap<>();
        Map<String, Map<Integer, Map<String, Double>>> paceCache = new HashMap<>(), projectionCache = new HashMap<>();
        Tally plain = new Tally(), withDraft = new Tally();
        int trades = 0;
        for(OtherLeagues.League league : OtherLeagues.manifest()){
            EraBoards.Board board = boards.get(league.season());
            if(board == null || !ends.containsKey(league.season()) || Integer.parseInt(league.season()) < TradeScreens.FIRST_PROJECTED){
                continue;
            }
            String season = league.season();
            Map<String, Position> positionOf = positions.computeIfAbsent(season, TradeScreens::positions);
            for(JsonElement element : OtherLeagues.cachedTrades(league)){
                JsonObject row = element.getAsJsonObject();
                List<List<String>> two = DraftWeightCurve.sides(row, positionOf);
                if(two == null || !row.has("created")){
                    continue;
                }
                int g = Math.min(DraftWeightCurve.gamesPlayed(ends.get(season), row.get("created").getAsLong()), board.weekly().size());
                if(g == 0 || g + 3 > EraActuals.weeks(season)){
                    continue;
                }
                trades++;
                Map<String, Back> absent = absentCache.computeIfAbsent(season, k -> new HashMap<>()).computeIfAbsent(g, k -> {
                    List<Map<String, Double>> weeks = new ArrayList<>();
                    for(int w = k + 1; w <= EraActuals.weeks(season); w++){
                        weeks.add(LeagueWeek.projected(season, w));
                    }
                    return absent(weeks);
                });
                boolean inA = two.get(0).stream().anyMatch(absent::containsKey), inB = two.get(1).stream().anyMatch(absent::containsKey);
                if(inA && inB){
                    continue;
                }
                Map<String, Double> pace = paceCache.computeIfAbsent(season, k -> new HashMap<>()).computeIfAbsent(g, k ->
                        TradeScreens.predicted(TradeScreens.soFar(board.weekly().subList(0, k)), positionOf, k, teams, flex, paceCharts.apply(k), Set.of()));
                Map<String, Double> projection = projectionCache.computeIfAbsent(season, k -> new HashMap<>()).computeIfAbsent(g, k ->
                        TradeScreens.readOff(TradeScreens.projectedAboveLine(season, k, positionOf, teams, flex), projectionCharts.apply(k)));
                Map<Position, Double> line = TradeScreens.line(TradeScreens.nextGame(season, g), positionOf, teams, flex);
                double s = paceShare.applyAsDouble(g);
                for(boolean draftToo : new boolean[]{false, true}){
                    Tally tally = draftToo ? withDraft : plain;
                    double w = draftToo && draftCurve != null ? draftCurve.applyAsDouble(g) : 0;
                    ToDoubleFunction<String> draft = id -> chart.at(board.adp().getOrDefault(id, 999.0));
                    ToDoubleFunction<String> healthy = id -> w * draft.applyAsDouble(id)
                            + (1 - w) * (s * pace.getOrDefault(id, 0.0) + (1 - s) * projection.getOrDefault(id, 0.0));
                    java.util.function.BiFunction<String, Double, Double> worth = (id, projected) -> {
                        Position p = positionOf.get(id);
                        double back = TradeScreens.skill(p) && line.containsKey(p) ? projectionCharts.apply(g).at(-(projected - line.get(p))) : 0;
                        return w * draft.applyAsDouble(id) + (1 - w) * (s * pace.getOrDefault(id, back) + (1 - s) * back);
                    };
                    if(!inA && !inB){
                        for(int side = 0; side < 2; side++){
                            List<String> his = two.get(side), other = two.get(1 - side);
                            double total = his.stream().mapToDouble(healthy).sum(), o = other.stream().mapToDouble(healthy).sum();
                            for(String id : his){
                                double v = healthy.applyAsDouble(id);
                                if(v >= TradeScreens.NOTHING && o + total - v >= TradeScreens.NOTHING){
                                    tally.control.computeIfAbsent(100 * his.size() + other.size(), k -> new ArrayList<>()).add(new double[]{v, (o - (total - v)) / v});
                                }
                            }
                        }
                        continue;
                    }
                    List<String> withAbsent = two.get(inA ? 0 : 1), other = two.get(inA ? 1 : 0);
                    List<String> out = withAbsent.stream().filter(absent::containsKey).toList();
                    double h = withAbsent.stream().filter(id -> !absent.containsKey(id)).mapToDouble(healthy).sum();
                    double f = out.stream().mapToDouble(id -> worth.apply(id, absent.get(id).points())).sum();
                    double fa = out.stream().mapToDouble(id -> worth.apply(id, absent.get(id).average())).sum();
                    double o = other.stream().mapToDouble(healthy).sum();
                    if(f < TradeScreens.NOTHING || fa < TradeScreens.NOTHING || o + h < TradeScreens.NOTHING){
                        continue;
                    }
                    Map<String, Double> lastWeek = board.weekly().get(g - 1);
                    tally.samples.add(new Sample((o - h) / f, (o - h) / fa, f, fa, h, o, out.stream().mapToDouble(id -> absent.get(id).share()).average().orElse(0),
                            out.stream().mapToInt(id -> absent.get(id).week()).max().orElse(0), withAbsent.size() == 1,
                            out.stream().allMatch(id -> lastWeek.getOrDefault(id, 0.0) <= 0), g, 100 * withAbsent.size() + other.size()));
                }
            }
        }
        plain.control.values().forEach(l -> l.sort(Comparator.comparingDouble(x -> x[0])));
        withDraft.control.values().forEach(l -> l.sort(Comparator.comparingDouble(x -> x[0])));
        List<Sample> all = plain.samples;
        System.out.printf("AWAY PRICE: %,d trades in other leagues' seasons with week projections; %,d with absent men worth something on one side only%n", trades, all.size());
        System.out.printf("  share of his worth paid, and that against healthy men in trades of the same shape and nearest worth (median, 90%% interval)%n");
        report("all", all, plain);
        report("had already missed the last week", all.stream().filter(Sample::missedLastWeek).toList(), plain);
        report("played the last week (may be hurt since)", all.stream().filter(x -> !x.missedLastWeek()).toList(), plain);
        report("the only man on his side", all.stream().filter(Sample::alone).toList(), plain);
        report("with other men on his side", all.stream().filter(x -> !x.alone()).toList(), plain);
        report("8+ games played", all.stream().filter(x -> x.games() >= 8).toList(), plain);
        report("back within 3 to 6 weeks", all.stream().filter(x -> x.weeksAway() <= 5).toList(), plain);
        report("back later than that", all.stream().filter(x -> x.weeksAway() > 5).toList(), plain);
        report("all, with draft value as traded", withDraft.samples, withDraft);
        report("already missed, with draft value", withDraft.samples.stream().filter(Sample::missedLastWeek).toList(), withDraft);
        for(String treatment : List.of("zero", "share of weeks left", "half", "full, first game back", "full, his average")){
            double[] uneven = all.stream().mapToDouble(x -> {
                double d = switch(treatment){
                    case "zero" -> 0;
                    case "half" -> 0.5;
                    case "full, first game back", "full, his average" -> 1;
                    default -> x.share();
                };
                double a = x.healthy() + d * (treatment.endsWith("average") ? x.fullAverage() : x.full());
                return a + x.other() <= 0 ? 0 : Math.abs(a - x.other()) / (a + x.other());
            }).sorted().toArray();
            System.out.printf("  priced at %-24s: median unevenness %.3f, mean %.3f%n", treatment,
                    uneven.length == 0 ? 0 : uneven[uneven.length / 2], Arrays.stream(uneven).average().orElse(0));
        }
    }

    private static String medianWithInterval(double[] values){
        double[] sorted = Arrays.stream(values).filter(v -> v == v).sorted().toArray();
        if(sorted.length == 0){
            return "none";
        }
        Random random = new Random(11);
        double[] medians = new double[2000];
        for(int b = 0; b < medians.length; b++){
            double[] draw = new double[sorted.length];
            for(int i = 0; i < draw.length; i++){
                draw[i] = sorted[random.nextInt(sorted.length)];
            }
            Arrays.sort(draw);
            medians[b] = draw[draw.length / 2];
        }
        Arrays.sort(medians);
        return String.format("%+.2f (%+.2f to %+.2f)", sorted[sorted.length / 2], medians[100], medians[1900]);
    }

    private static void report(String name, List<Sample> samples, Tally tally){
        double[] first = samples.stream().mapToDouble(Sample::implied).toArray();
        double[] average = samples.stream().mapToDouble(Sample::impliedAverage).toArray();
        double[] firstGap = samples.stream().mapToDouble(x -> tally.gap(x.implied(), x.full(), x.shape())).toArray();
        double[] averageGap = samples.stream().mapToDouble(x -> tally.gap(x.impliedAverage(), x.fullAverage(), x.shape())).toArray();
        System.out.printf("  %-42s %4d | first game back %s, vs healthy %s | average %s, vs healthy %s%n", name, samples.size(),
                medianWithInterval(first), medianWithInterval(firstGap), medianWithInterval(average), medianWithInterval(averageGap));
    }
}
