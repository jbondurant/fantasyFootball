import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
 * in the ratio other leagues' trades put between them, a returning man's next projection being his
 * first one back). Medians, with a bootstrap interval over trades, and how even each treatment
 * leaves those trades. One leak: a season's week projections were published at that week, so "due
 * back in week w" is known here earlier than it was then.
 *
 *   ./gradlew run -Pmain=AwayPrice
 */
public class AwayPrice {

    /** A man's weeks after g games: the first week he is projected, its points, and how many of the weeks left he is projected in. */
    record Back(int week, double points, int weeks, int weeksLeft) {
        double share(){
            return weeksLeft == 0 ? 0 : (double) weeks / weeksLeft;
        }
    }

    /** For a season after g games, each man with no projection in the next two weeks and at least one later. */
    static Map<String, Back> absent(List<Map<String, Double>> weeksAfterG){
        Map<String, Back> out = new HashMap<>();
        Map<String, Integer> count = new HashMap<>();
        Map<String, double[]> first = new HashMap<>();
        for(int k = 0; k < weeksAfterG.size(); k++){
            int at = k;
            weeksAfterG.get(k).forEach((id, points) -> {
                count.merge(id, 1, Integer::sum);
                first.putIfAbsent(id, new double[]{at, points});
            });
        }
        first.forEach((id, f) -> {
            if(f[0] >= 2){
                out.put(id, new Back((int) f[0], f[1], count.get(id), weeksAfterG.size()));
            }
        });
        return out;
    }

    record Sample(double implied, double share, int weeksAway, double other, double healthy, double full, boolean pure, int games) {}

    public static void main(String[] args) throws IOException {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int teams = configuration.getLeagueJson().getAsJsonObject("settings").get("num_teams").getAsInt();
        int flex = StartingLineup.flexSlotsPerTeam(configuration);
        Map<String, EraBoards.Board> boards = EraBoards.usable("ppr", EraIngest.MIN_RATE, EraIngest.minDepth());
        java.util.function.IntFunction<TradeScreens.Chart> paceCharts = TradeScreens.paceCharts(boards.values(), teams, flex);
        java.util.function.IntFunction<TradeScreens.Chart> projectionCharts = TradeScreens.projectionCharts(boards.values(), teams, flex);
        java.util.function.DoubleUnaryOperator measured = DraftWeightCurve.paceShare();
        java.util.function.DoubleUnaryOperator paceShare = measured == null ? g -> 1.0 : measured;
        Map<String, TreeMap<Integer, LocalDateTime>> ends = DraftWeightCurve.weekEnds(NflverseGames.games());
        Map<String, Map<String, Position>> positions = new HashMap<>();
        Map<String, Map<Integer, Map<String, Back>>> absentCache = new HashMap<>();
        Map<String, Map<Integer, Map<String, Double>>> paceCache = new HashMap<>(), projectionCache = new HashMap<>();
        List<Sample> samples = new ArrayList<>();
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
                if(inA == inB){
                    continue;
                }
                Map<String, Double> pace = paceCache.computeIfAbsent(season, k -> new HashMap<>()).computeIfAbsent(g, k ->
                        TradeScreens.predicted(TradeScreens.soFar(board.weekly().subList(0, k)), positionOf, k, teams, flex, paceCharts.apply(k), Set.of()));
                Map<String, Double> projection = projectionCache.computeIfAbsent(season, k -> new HashMap<>()).computeIfAbsent(g, k ->
                        TradeScreens.readOff(TradeScreens.projectedAboveLine(season, k, positionOf, teams, flex), projectionCharts.apply(k)));
                Map<Position, Double> line = TradeScreens.line(TradeScreens.nextGame(season, g), positionOf, teams, flex);
                double s = paceShare.applyAsDouble(g);
                ToDoubleFunction<String> healthy = id -> s * pace.getOrDefault(id, 0.0) + (1 - s) * projection.getOrDefault(id, 0.0);
                ToDoubleFunction<String> whenBack = id -> {
                    Position p = positionOf.get(id);
                    double back = TradeScreens.skill(p) && line.containsKey(p) ? projectionCharts.apply(g).at(-(absent.get(id).points() - line.get(p))) : 0;
                    return s * pace.getOrDefault(id, 0.0) + (1 - s) * back;
                };
                List<String> withAbsent = two.get(inA ? 0 : 1), other = two.get(inA ? 1 : 0);
                double h = withAbsent.stream().filter(id -> !absent.containsKey(id)).mapToDouble(healthy).sum();
                double f = withAbsent.stream().filter(absent::containsKey).mapToDouble(whenBack).sum();
                double o = other.stream().mapToDouble(healthy).sum();
                if(f < TradeScreens.NOTHING || o + h < TradeScreens.NOTHING){
                    continue;
                }
                double share = withAbsent.stream().filter(absent::containsKey).mapToDouble(id -> absent.get(id).share()).average().orElse(0);
                int away = withAbsent.stream().filter(absent::containsKey).mapToInt(id -> absent.get(id).week()).max().orElse(0);
                samples.add(new Sample((o - h) / f, share, away, o, h, f, h < TradeScreens.NOTHING, g));
            }
        }
        System.out.printf("AWAY PRICE: %,d trades in other leagues' seasons with week projections; %,d with absent men worth something on one side only%n", trades, samples.size());
        report("all", samples);
        report("he is all that side sends", samples.stream().filter(Sample::pure).toList());
        report("back within 3 weeks", samples.stream().filter(x -> x.weeksAway() <= 2).toList());
        report("back in 4 to 6 weeks", samples.stream().filter(x -> x.weeksAway() > 2 && x.weeksAway() <= 5).toList());
        report("back after that", samples.stream().filter(x -> x.weeksAway() > 5).toList());
        report("share of weeks left under 40%", samples.stream().filter(x -> x.share() < 0.4).toList());
        report("share of weeks left 40% to 70%", samples.stream().filter(x -> x.share() >= 0.4 && x.share() < 0.7).toList());
        report("share of weeks left 70% and over", samples.stream().filter(x -> x.share() >= 0.7).toList());
        // how even each treatment leaves these trades: 0 is what TradeScreens does today, 1 his full worth, share his worth times the weeks he has left
        for(String treatment : List.of("zero", "share of weeks left", "half", "full")){
            double[] uneven = samples.stream().mapToDouble(x -> {
                double d = switch(treatment){
                    case "zero" -> 0;
                    case "half" -> 0.5;
                    case "full" -> 1;
                    default -> x.share();
                };
                double a = x.healthy() + d * x.full();
                return a + x.other() <= 0 ? 0 : Math.abs(a - x.other()) / (a + x.other());
            }).sorted().toArray();
            System.out.printf("  priced at %-20s: median unevenness %.2f, mean %.2f%n", treatment,
                    uneven.length == 0 ? 0 : uneven[uneven.length / 2], Arrays.stream(uneven).average().orElse(0));
        }
    }

    private static void report(String name, List<Sample> samples){
        if(samples.isEmpty()){
            System.out.printf("  %-34s none%n", name);
            return;
        }
        double[] implied = samples.stream().mapToDouble(Sample::implied).sorted().toArray();
        Random random = new Random(11);
        double[] medians = new double[2000];
        for(int b = 0; b < medians.length; b++){
            double[] draw = new double[implied.length];
            for(int i = 0; i < draw.length; i++){
                draw[i] = implied[random.nextInt(implied.length)];
            }
            Arrays.sort(draw);
            medians[b] = draw[draw.length / 2];
        }
        Arrays.sort(medians);
        System.out.printf("  %-34s %4d trades: share of his worth paid, median %.2f (90%% %.2f to %.2f); mean share of weeks left %.2f%n", name, implied.length,
                implied[implied.length / 2], medians[100], medians[1900], samples.stream().mapToDouble(Sample::share).average().orElse(0));
    }
}
