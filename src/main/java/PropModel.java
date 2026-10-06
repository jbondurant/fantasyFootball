import PlayerImportAndSetup.Position;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A PLAYER-PROP STAT'S DISTRIBUTION, FROM SLEEPER'S PROJECTION AND WHAT MEN
 * PROJECTED LIKE HIM ACTUALLY DID.
 *
 * PropShape measured that Rotowire's weekly projected stat lines are close to
 * unbiased means (mean outcome over mean projection 0.91-1.00) with a stable
 * right skew around them. That is enough to price any threshold: for a man
 * projected for m, the chance he clears k is the share of the played
 * player-weeks 2018-2025 at his position, projected nearest to m, that cleared
 * k. Nearest neighbours in the projection, no parametric shape - the skew,
 * the zeros of an early exit and the lumpiness of counts come along for free.
 *
 * Checked the way a price has to be checked: each season is priced from the
 * other seasons, at thresholds from half the projection to one and a half
 * times it, and what the model said is set against what happened (the
 * reliability table). A model that says 30% for things that happen 30% of
 * the time is a precondition for trading against anyone; it is not an edge.
 *
 *     ./gradlew run -Pmain=PropModel         (the calibration report)
 */
public class PropModel {

    static final int NEIGHBOURS = 600;

    /** A prop stat at a position: the Sleeper keys summed, projected and actual. */
    record Stat(String name, Position position, String[] keys) {}

    static final List<Stat> STATS = List.of(
            new Stat("passing yards", Position.QB, new String[]{"pass_yd"}),
            new Stat("passing TDs", Position.QB, new String[]{"pass_td"}),
            new Stat("completions", Position.QB, new String[]{"pass_cmp"}),
            new Stat("pass attempts", Position.QB, new String[]{"pass_att"}),
            new Stat("rushing yards", Position.QB, new String[]{"rush_yd"}),
            new Stat("rushing yards", Position.RB, new String[]{"rush_yd"}),
            new Stat("receiving yards", Position.RB, new String[]{"rec_yd"}),
            new Stat("receptions", Position.RB, new String[]{"rec"}),
            new Stat("rush + rec yards", Position.RB, new String[]{"rush_yd", "rec_yd"}),
            new Stat("rushing yards", Position.WR, new String[]{"rush_yd"}),
            new Stat("receiving yards", Position.WR, new String[]{"rec_yd"}),
            new Stat("receptions", Position.WR, new String[]{"rec"}),
            new Stat("rush + rec yards", Position.WR, new String[]{"rush_yd", "rec_yd"}),
            new Stat("receiving yards", Position.TE, new String[]{"rec_yd"}),
            new Stat("receptions", Position.TE, new String[]{"rec"}),
            new Stat("rush + rec yards", Position.TE, new String[]{"rush_yd", "rec_yd"}),
            // touchdowns he scores (Kalshi's KXNFLTD): rushing and receiving, a QB's rushing only
            new Stat("touchdowns", Position.QB, new String[]{"rush_td"}),
            new Stat("touchdowns", Position.RB, new String[]{"rush_td", "rec_td"}),
            new Stat("touchdowns", Position.WR, new String[]{"rush_td", "rec_td"}),
            new Stat("touchdowns", Position.TE, new String[]{"rush_td", "rec_td"}),
            // 2018 projections name interceptions 'int', later ones 'pass_int'; a row carries one or the other
            new Stat("interceptions", Position.QB, new String[]{"pass_int", "int"}));

    static Stat find(String name, Position position){
        for(Stat s : STATS){
            if(s.name().equals(name) && s.position() == position){
                return s;
            }
        }
        return null;
    }

    /** One played player-week: projected, actual, season. */
    record Pair(double projected, double actual, String season) {}

    /** The fitted pairs of one stat, sorted by projection. */
    static final class Fitted {
        final double[] projected;
        final double[] actual;
        final String[] season;

        Fitted(List<Pair> pairs){
            List<Pair> sorted = new ArrayList<>(pairs);
            sorted.sort((a, b) -> Double.compare(a.projected(), b.projected()));
            projected = sorted.stream().mapToDouble(Pair::projected).toArray();
            actual = sorted.stream().mapToDouble(Pair::actual).toArray();
            season = sorted.stream().map(Pair::season).toArray(String[]::new);
        }

        /**
         * P(stat >= k) for a man projected m: the share of the NEIGHBOURS pairs
         * with the nearest projections (leaving out {@code exclude}'s season)
         * that reached k.
         */
        double over(double m, double k, String exclude){
            int at = Arrays.binarySearch(projected, m);
            if(at < 0){
                at = -at - 1;
            }
            int lo = at - 1;
            int hi = at;
            int taken = 0;
            int reached = 0;
            while(taken < NEIGHBOURS && (lo >= 0 || hi < projected.length)){
                int pick;
                if(lo < 0){
                    pick = hi++;
                }
                else if(hi >= projected.length){
                    pick = lo--;
                }
                else if(m - projected[lo] <= projected[hi] - m){
                    pick = lo--;
                }
                else{
                    pick = hi++;
                }
                if(season[pick].equals(exclude)){
                    continue;
                }
                taken++;
                reached += actual[pick] >= k ? 1 : 0;
            }
            // a pseudo-count either way keeps an empty tail off 0 and 1
            return (reached + 0.5) / (taken + 1.0);
        }

        /** E[payoff(stat)] for a man projected m, over the same neighbours. */
        double expect(double m, java.util.function.DoubleUnaryOperator payoff){
            return expect(m, payoff, null);
        }

        /** The same, leaving out {@code exclude}'s season (so a season can be scored with a shape it did not shape). */
        double expect(double m, java.util.function.DoubleUnaryOperator payoff, String exclude){
            int at = Arrays.binarySearch(projected, m);
            if(at < 0){
                at = -at - 1;
            }
            int lo = at - 1;
            int hi = at;
            int taken = 0;
            double sum = 0;
            while(taken < NEIGHBOURS && (lo >= 0 || hi < projected.length)){
                int pick = lo < 0 ? hi++ : hi >= projected.length ? lo-- : m - projected[lo] <= projected[hi] - m ? lo-- : hi++;
                if(season[pick].equals(exclude)){
                    continue;
                }
                taken++;
                sum += payoff.applyAsDouble(actual[pick]);
            }
            return taken == 0 ? Double.NaN : sum / taken;
        }
    }

    /** Every played player-week 2018-2025 of every stat, from the cached feeds. */
    static Map<Stat, Fitted> fit() throws IOException {
        LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        Map<Stat, List<Pair>> pairs = new java.util.LinkedHashMap<>();
        for(Stat s : STATS){
            pairs.put(s, new ArrayList<>());
        }
        for(int y = 2018; y <= 2025; y++){
            String season = String.valueOf(y);
            ScreenData.Season s = ScreenData.load(season, true, true, scoring);
            for(Map.Entry<Integer, Map<String, JsonObject>> week : s.projected.entrySet()){
                Map<String, JsonObject> actual = PropShape.statsById(LeagueWeek.teamStatsBody(season, week.getKey()));
                Map<String, ScreenData.Line> lines = s.lines.getOrDefault(week.getKey(), Map.of());
                for(Map.Entry<String, JsonObject> p : week.getValue().entrySet()){
                    ScreenData.Line line = lines.get(p.getKey());
                    if(line == null || !line.played()){
                        continue;
                    }
                    JsonObject a = actual.get(p.getKey());
                    for(Stat stat : STATS){
                        if(stat.position() != line.position()){
                            continue;
                        }
                        double m = PropShape.sum(p.getValue(), stat.keys());
                        if(m > 0){
                            pairs.get(stat).add(new Pair(m, a == null ? 0 : PropShape.sum(a, stat.keys()), season));
                        }
                    }
                }
            }
        }
        Map<Stat, Fitted> out = new java.util.LinkedHashMap<>();
        pairs.forEach((s, list) -> out.put(s, new Fitted(list)));
        return out;
    }

    /** Thresholds a prop market would list for a projection m: whole numbers near the given multiples of it. */
    static final double[] MULTIPLES = {0.5, 0.75, 1.0, 1.25, 1.5};

    public static void main(String[] args) throws IOException {
        Map<Stat, Fitted> fitted = fit();
        StringBuilder out = new StringBuilder();
        out.append(String.format("PROP MODEL CALIBRATION  %s  (each season priced from the others; nearest %d projections)%n", LocalDate.now(), NEIGHBOURS));
        out.append("For thresholds at a multiple of the projection, the mean predicted P(stat >= k) against the share that reached it,\n");
        out.append("and the Brier score of the model against the base rate at that multiple (lower is better).\n\n");
        out.append(String.format("%-4s %-18s %7s   %s%n", "pos", "stat", "weeks", "k = 0.5m / 0.75m / m / 1.25m / 1.5m : predicted vs actual"));
        double worst = 0;
        for(Map.Entry<Stat, Fitted> e : fitted.entrySet()){
            Stat s = e.getKey();
            Fitted f = e.getValue();
            StringBuilder cells = new StringBuilder();
            for(double mult : MULTIPLES){
                double sumP = 0;
                double sumY = 0;
                int n = 0;
                for(int i = 0; i < f.projected.length; i++){
                    double k = Math.max(1, Math.round(f.projected[i] * mult));
                    double p = f.over(f.projected[i], k, f.season[i]);
                    sumP += p;
                    sumY += f.actual[i] >= k ? 1 : 0;
                    n++;
                }
                worst = Math.max(worst, Math.abs(sumP - sumY) / n);
                cells.append(String.format("%5.1f/%-5.1f ", 100 * sumP / n, 100 * sumY / n));
            }
            out.append(String.format("%-4s %-18s %7d   %s%n", s.position(), s.name(), f.projected.length, cells));
        }
        out.append(String.format("%nlargest gap between predicted and actual in any cell: %.1f points%n", 100 * worst));
        out.append("\nReliability, pooled over every stat and multiple (predicted P binned):\n");
        double[] edges = {0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0001};
        double[][] bins = new double[edges.length - 1][3];
        for(Fitted f : fitted.values()){
            for(int i = 0; i < f.projected.length; i += 3){        // every third row keeps it quick; the bins stay full
                for(double mult : MULTIPLES){
                    double k = Math.max(1, Math.round(f.projected[i] * mult));
                    double p = f.over(f.projected[i], k, f.season[i]);
                    for(int b = 0; b + 1 < edges.length; b++){
                        if(p >= edges[b] && p < edges[b + 1]){
                            bins[b][0] += p;
                            bins[b][1] += f.actual[i] >= k ? 1 : 0;
                            bins[b][2]++;
                        }
                    }
                }
            }
        }
        for(int b = 0; b < bins.length; b++){
            if(bins[b][2] > 0){
                out.append(String.format("  %.1f-%.1f  n %6.0f   predicted %5.1f%%   happened %5.1f%%%n", edges[b], Math.min(1, edges[b + 1]),
                        bins[b][2], 100 * bins[b][0] / bins[b][2], 100 * bins[b][1] / bins[b][2]));
            }
        }
        System.out.print(out);
        Path report = Path.of("data", "prop-model-" + LocalDate.now() + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }
}
