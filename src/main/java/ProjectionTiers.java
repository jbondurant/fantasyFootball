import PlayerImportAndSetup.Position;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * DOES SLEEPER SELL ITS BEST MEN SHORT?
 *
 * Justin, 2026-09-27, after the trade finder's best offer asked Renteez for
 * Ja'Marr Chase in exchange for Malik Nabers and Rhamondre Stevenson: "I feel
 * like the trade evaluator must be wrong as well to give up chase in this
 * situation." On Sleeper's weekly projections for the games left, the objective
 * says Renteez gains, because Nabers and Stevenson push Travis Etienne into a
 * flex held by Kayshon Boutte, which covers most of the step down from Chase
 * (209 left) to Nabers (158). That arithmetic is only as good as the gap it
 * starts from. If Sleeper projects its top men below what they go on to score,
 * every consolidation trade is priced in favour of the side taking depth and the
 * evaluator is wrong in exactly the direction Justin says.
 *
 * So this is calibration by tier: every played player-week of 2018-2025 with a
 * Sleeper weekly projection of 5 or more (ScreenData's population, the one
 * FeatureScreen and PropShape use), grouped by position and projected points,
 * with the mean outcome against the mean projection. The error bar is clustered
 * by season-week, because one week's scoring environment moves every man in it.
 * Conditioning on the projection, not the outcome, is what makes this a fair
 * test: it asks what a man projected at 15 goes on to score.
 *
 *   ./gradlew run -Pmain=ProjectionTiers
 */
public class ProjectionTiers {

    static final double[] BANDS = {5, 8, 11, 14, 17, 20, 1000};

    /** Mean projection, mean outcome, mean of (outcome - projection), its season-week clustered SE, and the rows. */
    record Cell(double projected, double actual, double gap, double se, int n) {
        double ratio(){
            return projected == 0 ? Double.NaN : actual / projected;
        }
    }

    /** {projection, outcome, cluster} rows -> one cell. */
    static Cell cell(List<double[]> rows, List<String> clusters){
        int n = rows.size();
        if(n == 0){
            return new Cell(0, 0, 0, 0, 0);
        }
        double p = 0, y = 0;
        for(double[] r : rows){
            p += r[0];
            y += r[1];
        }
        double gap = (y - p) / n;
        Map<String, double[]> byCluster = new HashMap<>();      // {sum of (y - p), count}
        for(int i = 0; i < n; i++){
            double[] c = byCluster.computeIfAbsent(clusters.get(i), k -> new double[2]);
            c[0] += rows.get(i)[1] - rows.get(i)[0];
            c[1]++;
        }
        int g = byCluster.size();
        double v = 0;
        for(double[] c : byCluster.values()){
            double e = c[0] - c[1] * gap;
            v += e * e;
        }
        double se = g < 2 ? Double.NaN : Math.sqrt(v * g / (g - 1.0)) / n;
        return new Cell(p / n, y / n, gap, se, n);
    }

    public static void main(String[] args) throws IOException {
        LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        Map<String, ScreenData.Season> seasons = new TreeMap<>();
        List<String> which = new ArrayList<>();
        for(int y = 2018; y <= 2025; y++){
            String season = String.valueOf(y);
            seasons.put(season, ScreenData.load(season, true, true, scoring));
            which.add(season);
        }
        ScreenData.Audit audit = new ScreenData.Audit();
        List<ScreenData.Row> rows = ScreenData.population(seasons, which, NflverseGames.sides(), 5.0, audit);

        StringBuilder out = new StringBuilder();
        out.append(String.format("PROJECTION TIERS  %s  (Sleeper's weekly projection against what the man scored, played weeks 2018-2025, league points)%n",
                LocalDate.now()));
        out.append("Each row: the men Sleeper projected in that band. gap = mean outcome minus mean projection, with its season-week\n");
        out.append("clustered standard error; t = gap / se. A top band well above zero means Sleeper sells its best men short.\n\n");
        out.append(String.format("%-4s %-10s %7s %9s %9s %7s %8s %7s %6s%n", "pos", "projected", "weeks", "mean proj", "mean got",
                "ratio", "gap", "se", "t"));
        for(Position position : List.of(Position.QB, Position.RB, Position.WR, Position.TE)){
            for(int b = 0; b + 1 < BANDS.length; b++){
                List<double[]> in = new ArrayList<>();
                List<String> clusters = new ArrayList<>();
                for(ScreenData.Row r : rows){
                    if(r.position() == position && r.p >= BANDS[b] && r.p < BANDS[b + 1]){
                        in.add(new double[]{r.p, r.y});
                        clusters.add(r.seasonWeek());
                    }
                }
                Cell c = cell(in, clusters);
                if(c.n() < 30){
                    continue;
                }
                String band = BANDS[b + 1] >= 1000 ? String.format("%.0f+", BANDS[b]) : String.format("%.0f-%.0f", BANDS[b], BANDS[b + 1]);
                out.append(String.format("%-4s %-10s %7d %9.2f %9.2f %7.3f %+8.2f %7.2f %+6.1f%n", position, band, c.n(), c.projected(),
                        c.actual(), c.ratio(), c.gap(), c.se(), c.gap() / c.se()));
            }
            out.append('\n');
        }
        out.append("Played weeks only: a man who sat out is not in a band, so this is what a projection is worth given he plays;\n");
        out.append("the objective draws availability separately, from whole historical seasons.\n");
        System.out.print(out);
        Path report = Path.of("data", "projection-tiers-" + LocalDate.now() + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }
}
