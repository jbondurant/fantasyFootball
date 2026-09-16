import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * DOES USAGE SAY MORE THAN THE SCORE?
 *
 * Justin, after week 1: can the weekly data say whether a big score was backed
 * by the stats that persist - targets, carries, air yards, red-zone touches -
 * rather than by a touchdown that will not repeat, and does that usage predict
 * the rest of the season beyond the points themselves? Sleeper's weekly stat
 * lines carry those counts back to 2013 in the same id space as the harvest,
 * so both halves are measured on thirteen seasons here, the same way
 * {@link InSeasonLearning} measured the points-only rule:
 *
 *  A. WHICH STATS carry signal the score does not: the correlation of each
 *     usage rate through week k with the RESIDUAL of the points-only
 *     posterior - what the man went on to score per game, minus what the
 *     rule already said - per position, clustered on season.
 *  B. HOW MUCH: a ridge fit of that residual on the usage rates, fitted on
 *     every season but one and scored on the one left out. Reported as the
 *     mean absolute error on his realised rate with and without the usage
 *     term, and as the pick-level flip accuracy with and without it - the
 *     bounded, per-decision claim that resolves at thirteen seasons.
 *  C. THIS WEEK: the fit on all seasons, applied to every rostered man's
 *     usage from the live stats feed, beside the points-only posterior
 *     WeekReaction prints - so a big week with the targets behind it and a
 *     big week without them read differently.
 *
 * Usage is per played game through week k. Snap counts appear in Sleeper's
 * rows only from 2016 and are not used; team shares would need the man's
 * club each week, which the row does not carry.
 *
 *   ./gradlew run -Pmain=UsageSignal [-Pweek=n]
 */
public class UsageSignal {

    /** The stat keys in Sleeper's weekly row, and their names in the report. */
    static final String[] KEYS = {"rec_tgt", "rush_att", "pass_att", "rec_air_yd", "rec_rz_tgt", "rush_rz_att"};
    static final String[] NAMES = {"targets", "carries", "pass att", "air yds", "rz tgts", "rz carries"};
    static final int[] SEEN = {1, 2, 4};
    /** Ridge shrinkage on standardised features - a small number, the same for every fit. */
    static final double LAMBDA = 1.0;

    /** Which of KEYS a position's usage is made of. */
    static int[] featuresFor(Position position){
        switch(position){
            case QB: return new int[]{2, 1, 5};
            case RB: return new int[]{1, 0, 5, 4};
            default: return new int[]{0, 3, 4, 1};
        }
    }

    /* ---------------------------------------------------------- pure pieces, tested */

    /**
     * Per-game usage through the weeks given: the mean over the weeks he
     * played (a row with a score) of each KEY, a missing key in a played week
     * counting zero. Null if he never played.
     */
    static double[] usageThrough(List<JsonObject> weeks, String id){
        double[] sum = new double[KEYS.length];
        int games = 0;
        for(JsonObject week : weeks){
            JsonElement row = week.get(id);
            if(row == null || !row.isJsonObject()){
                continue;
            }
            JsonObject stats = row.getAsJsonObject();
            JsonElement points = stats.get("pts_half_ppr");
            if(points == null || points.isJsonNull()){
                continue;
            }
            games++;
            for(int i = 0; i < KEYS.length; i++){
                JsonElement v = stats.get(KEYS[i]);
                sum[i] += v == null || v.isJsonNull() ? 0 : v.getAsDouble();
            }
        }
        if(games == 0){
            return null;
        }
        for(int i = 0; i < KEYS.length; i++){
            sum[i] /= games;
        }
        return sum;
    }

    /** Mean and sd of each column over the rows; sd floored at a tiny number so a constant column standardises to zero. */
    static double[][] moments(List<double[]> rows, int columns){
        double[] mean = new double[columns];
        double[] sd = new double[columns];
        for(double[] r : rows){
            for(int j = 0; j < columns; j++){
                mean[j] += r[j];
            }
        }
        for(int j = 0; j < columns; j++){
            mean[j] /= Math.max(1, rows.size());
        }
        for(double[] r : rows){
            for(int j = 0; j < columns; j++){
                sd[j] += (r[j] - mean[j]) * (r[j] - mean[j]);
            }
        }
        for(int j = 0; j < columns; j++){
            sd[j] = Math.max(1e-9, Math.sqrt(sd[j] / Math.max(1, rows.size() - 1)));
        }
        return new double[][]{mean, sd};
    }

    static double[] standardise(double[] row, double[][] moments){
        double[] z = new double[row.length];
        for(int j = 0; j < row.length; j++){
            z[j] = (row[j] - moments[0][j]) / moments[1][j];
        }
        return z;
    }

    /**
     * Ridge regression of y on x with an intercept: (X'X + lambda I) b = X'y,
     * the intercept unpenalised. Returns [intercept, b1..bp]. Gaussian
     * elimination with partial pivoting on a (p+1)x(p+1) system.
     */
    static double[] ridge(List<double[]> x, double[] y, double lambda){
        int p = x.isEmpty() ? 0 : x.get(0).length;
        int n = p + 1;
        double[][] a = new double[n][n];
        double[] b = new double[n];
        for(int r = 0; r < x.size(); r++){
            double[] row = new double[n];
            row[0] = 1;
            System.arraycopy(x.get(r), 0, row, 1, p);
            for(int i = 0; i < n; i++){
                b[i] += row[i] * y[r];
                for(int j = 0; j < n; j++){
                    a[i][j] += row[i] * row[j];
                }
            }
        }
        for(int i = 1; i < n; i++){
            a[i][i] += lambda;
        }
        // solve
        for(int col = 0; col < n; col++){
            int pivot = col;
            for(int r = col + 1; r < n; r++){
                if(Math.abs(a[r][col]) > Math.abs(a[pivot][col])){
                    pivot = r;
                }
            }
            double[] tmp = a[col];
            a[col] = a[pivot];
            a[pivot] = tmp;
            double t = b[col];
            b[col] = b[pivot];
            b[pivot] = t;
            if(Math.abs(a[col][col]) < 1e-12){
                continue;
            }
            for(int r = 0; r < n; r++){
                if(r == col){
                    continue;
                }
                double f = a[r][col] / a[col][col];
                for(int c = col; c < n; c++){
                    a[r][c] -= f * a[col][c];
                }
                b[r] -= f * b[col];
            }
        }
        double[] beta = new double[n];
        for(int i = 0; i < n; i++){
            beta[i] = Math.abs(a[i][i]) < 1e-12 ? 0 : b[i] / a[i][i];
        }
        return beta;
    }

    static double predict(double[] beta, double[] z){
        double out = beta[0];
        for(int j = 0; j < z.length; j++){
            out += beta[j + 1] * z[j];
        }
        return out;
    }

    /** Pearson r of column j of x against y. */
    static double correlation(List<double[]> x, double[] y, int j){
        List<double[]> pairs = new ArrayList<>();
        for(int i = 0; i < x.size(); i++){
            pairs.add(new double[]{x.get(i)[j], y[i]});
        }
        return WeeklyFeedAudit.fit(pairs).r();
    }

    /** One man's row at week k: who, what the rule said, what he did, and his usage. */
    record Row(String season, InSeasonLearning.Man man, double posterior, double realised, double[] usage){
        double residual(){
            return realised - posterior;
        }
    }

    /**
     * The board's flips under an estimate: every pair the ADP order ranks A
     * over B where the estimate says B, scored on whether B outscored A from
     * week k+1 on. The same pairs {@link InSeasonLearning#flips} builds,
     * for an estimate computed outside it.
     */
    static List<InSeasonLearning.Flip> flips(List<Row> field, double[] estimate, int seen, int seasonIndex, int remaining){
        List<InSeasonLearning.Flip> found = new ArrayList<>();
        for(int i = 0; i < field.size(); i++){
            for(int j = i + 1; j < field.size(); j++){
                InSeasonLearning.Man ahead = field.get(i).man();
                InSeasonLearning.Man behind = field.get(j).man();
                if(ahead.rank() >= behind.rank() || estimate[j] <= estimate[i]){
                    continue;
                }
                double margin = behind.restPoints(seen) - ahead.restPoints(seen);
                boolean healthy = ahead.restGames(seen) >= 0.8 * remaining && behind.restGames(seen) >= 0.8 * remaining;
                found.add(new InSeasonLearning.Flip(seasonIndex, margin > 0, margin, healthy));
            }
        }
        return found;
    }

    /* ---------------------------------------------------------- the report */

    public static void main(String[] args) throws IOException {
        Map<String, EraBoards.Board> boards = EraBoards.usable("ppr", EraIngest.MIN_RATE, EraIngest.minDepth());
        Map<String, List<InSeasonLearning.Man>> harvest = InSeasonLearning.harvest(boards);
        List<String> seasons = new ArrayList<>(harvest.keySet());
        Map<String, Map<Position, double[]>> priors = new TreeMap<>();
        Map<String, Map<Position, InSeasonLearning.Kappa>> kappas = new TreeMap<>();
        for(String season : seasons){
            priors.put(season, InSeasonLearning.priorTable(harvest, season, null));
            kappas.put(season, InSeasonLearning.fitKappa(harvest, season));
        }

        StringBuilder out = new StringBuilder();
        out.append(String.format("USAGE SIGNAL  (%d seasons %s-%s; usage from Sleeper's weekly stat lines; priors, kappa, moments and fits leave-one-season-out)%n",
                seasons.size(), seasons.get(0), seasons.get(seasons.size() - 1)));
        out.append("residual = what he scored per game from week k+1 on, minus the points-only posterior (InSeasonLearning's rule).\n");
        out.append("r = correlation of a usage rate through week k with that residual, mean over seasons +- se; a stat the score already\n");
        out.append("carries reads near zero. MAE = on his realised rate; flips = the board's pairs the estimate reverses, share right (bar 95%).\n");

        Map<Position, double[]> finalBeta = new EnumMap<>(Position.class);
        Map<Position, double[][]> finalMoments = new EnumMap<>(Position.class);
        // the leave-one-out verdict per position at this week: the change in MAE and its bar
        Map<Position, double[]> finalGain = new EnumMap<>(Position.class);
        int weekForC = Math.min(SEEN[SEEN.length - 1], Math.max(1, currentWeek()));
        for(int seen : SEEN){
            // usage through week `seen`, per season
            Map<String, Map<String, double[]>> usageBySeason = new TreeMap<>();
            for(String season : seasons){
                List<JsonObject> weeks = new ArrayList<>();
                for(int w = 1; w <= seen; w++){
                    weeks.add(EraActuals.week(season, w));
                }
                Map<String, double[]> usage = new HashMap<>();
                for(InSeasonLearning.Man man : harvest.get(season)){
                    double[] u = usageThrough(weeks, man.id());
                    if(u != null){
                        usage.put(man.id(), u);
                    }
                }
                usageBySeason.put(season, usage);
            }
            out.append(String.format("%n== through week %d ==%n", seen));
            for(Position position : InSeasonLearning.POSITIONS){
                int[] features = featuresFor(position);
                Map<String, List<Row>> rowsBySeason = new TreeMap<>();
                for(String season : seasons){
                    List<InSeasonLearning.Man> men = harvest.get(season);
                    double level = InSeasonLearning.levelThrough(men, position, seen);
                    double kappa = kappas.get(season).get(position).kappa();
                    List<Row> rows = new ArrayList<>();
                    for(InSeasonLearning.Man man : men){
                        double[] u = usageBySeason.get(season).get(man.id());
                        if(man.position() != position || man.rank() > InSeasonLearning.CAP.get(position)
                                || man.gamesThrough(seen) < 1 || man.restGames(seen) < 3 || u == null){
                            continue;
                        }
                        double[] picked = new double[features.length];
                        for(int f = 0; f < features.length; f++){
                            picked[f] = u[features[f]];
                        }
                        double posterior = InSeasonLearning.estimate(man, seen, kappa, priors.get(season), level);
                        rows.add(new Row(season, man, posterior, man.restPoints(seen) / man.restGames(seen), picked));
                    }
                    rows.sort(Comparator.comparingInt((Row r) -> r.man().rank()));
                    rowsBySeason.put(season, rows);
                }
                int total = 0;
                for(List<Row> rows : rowsBySeason.values()){
                    total += rows.size();
                }
                if(total < 30){
                    continue;
                }
                // A. which stats
                Map<Integer, Map<String, List<Double>>> rBySeason = new TreeMap<>();
                for(Map.Entry<String, List<Row>> e : rowsBySeason.entrySet()){
                    List<double[]> x = new ArrayList<>();
                    double[] y = new double[e.getValue().size()];
                    for(int i = 0; i < y.length; i++){
                        x.add(e.getValue().get(i).usage());
                        y[i] = e.getValue().get(i).residual();
                    }
                    for(int f = 0; f < features.length; f++){
                        double r = x.size() >= 5 ? correlation(x, y, f) : Double.NaN;
                        if(!Double.isNaN(r)){
                            rBySeason.computeIfAbsent(f, k -> new TreeMap<>())
                                    .computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(r);
                        }
                    }
                }
                // B. leave one season out
                Map<String, List<Double>> maePlain = new TreeMap<>();
                Map<String, List<Double>> maeUsage = new TreeMap<>();
                List<InSeasonLearning.Flip> flipsPlain = new ArrayList<>();
                List<InSeasonLearning.Flip> flipsUsage = new ArrayList<>();
                for(int s = 0; s < seasons.size(); s++){
                    String held = seasons.get(s);
                    List<double[]> trainX = new ArrayList<>();
                    List<Double> trainY = new ArrayList<>();
                    for(Map.Entry<String, List<Row>> e : rowsBySeason.entrySet()){
                        if(e.getKey().equals(held)){
                            continue;
                        }
                        for(Row r : e.getValue()){
                            trainX.add(r.usage());
                            trainY.add(r.residual());
                        }
                    }
                    if(trainX.size() < 30 || rowsBySeason.get(held).isEmpty()){
                        continue;
                    }
                    double[][] m = moments(trainX, features.length);
                    List<double[]> z = new ArrayList<>();
                    double[] y = new double[trainX.size()];
                    for(int i = 0; i < y.length; i++){
                        z.add(standardise(trainX.get(i), m));
                        y[i] = trainY.get(i);
                    }
                    double[] beta = ridge(z, y, LAMBDA);
                    List<Row> field = rowsBySeason.get(held);
                    double[] plain = new double[field.size()];
                    double[] adjusted = new double[field.size()];
                    for(int i = 0; i < field.size(); i++){
                        Row r = field.get(i);
                        plain[i] = r.posterior();
                        adjusted[i] = r.posterior() + predict(beta, standardise(r.usage(), m));
                        maePlain.computeIfAbsent(held, k -> new ArrayList<>()).add(Math.abs(plain[i] - r.realised()));
                        maeUsage.computeIfAbsent(held, k -> new ArrayList<>()).add(Math.abs(adjusted[i] - r.realised()));
                    }
                    int weeks = harvest.get(held).isEmpty() ? 0 : harvest.get(held).get(0).weeks();
                    flipsPlain.addAll(flips(field, plain, seen, s, weeks - seen));
                    flipsUsage.addAll(flips(field, adjusted, seen, s, weeks - seen));
                }
                PowerBacktest.Paired accPlain = InSeasonLearning.accuracy("", flipsPlain, seasons.size(), false);
                PowerBacktest.Paired accUsage = InSeasonLearning.accuracy("", flipsUsage, seasons.size(), false);
                double[] mp = RosBands.overSeasons(maePlain);
                double[] mu = RosBands.overSeasons(maeUsage);
                Map<String, List<Double>> maeDiff = new TreeMap<>();
                for(String season : maePlain.keySet()){
                    List<Double> d = new ArrayList<>();
                    List<Double> a = maePlain.get(season);
                    List<Double> b = maeUsage.get(season);
                    for(int i = 0; i < a.size(); i++){
                        d.add(b.get(i) - a.get(i));
                    }
                    maeDiff.put(season, d);
                }
                double[] md = RosBands.overSeasons(maeDiff);
                // the fit on every season, for section C
                List<double[]> allX = new ArrayList<>();
                List<Double> allY = new ArrayList<>();
                for(List<Row> rows : rowsBySeason.values()){
                    for(Row r : rows){
                        allX.add(r.usage());
                        allY.add(r.residual());
                    }
                }
                double[][] mAll = moments(allX, features.length);
                List<double[]> zAll = new ArrayList<>();
                double[] yAll = new double[allX.size()];
                for(int i = 0; i < yAll.length; i++){
                    zAll.add(standardise(allX.get(i), mAll));
                    yAll[i] = allY.get(i);
                }
                double[] betaAll = ridge(zAll, yAll, LAMBDA);
                if(seen == weekForC){
                    finalBeta.put(position, betaAll);
                    finalMoments.put(position, mAll);
                    finalGain.put(position, md);
                }

                out.append(String.format("%-3s %5d men | r with the residual:", position, total));
                for(int f = 0; f < features.length; f++){
                    double[] r = RosBands.overSeasons(rBySeason.getOrDefault(f, Map.of()));
                    out.append(String.format(" %s %+.2f+-%.2f", NAMES[features[f]], r[0], r[1]));
                }
                out.append(String.format("%n    MAE on his rate: rule %.2f -> with usage %.2f (change %+.2f +- %.2f over %d seasons)%n",
                        mp[0], mu[0], md[0], md[1], (int) md[2]));
                out.append(String.format("    flips right: rule %s -> with usage %s%n",
                        accPlain == null ? "n/a" : String.format("%.1f%% (bar %.1f, %d flips)", 100 * (0.5 + accPlain.diff()), 100 * accPlain.bar(), flipsPlain.size()),
                        accUsage == null ? "n/a" : String.format("%.1f%% (bar %.1f, %d flips)", 100 * (0.5 + accUsage.diff()), 100 * accUsage.bar(), flipsUsage.size())));
                out.append("    all-season fit, points a game per sd of usage:");
                for(int f = 0; f < features.length; f++){
                    out.append(String.format(" %s %+.2f", NAMES[features[f]], betaAll[f + 1]));
                }
                out.append('\n');
            }
        }

        // C. this week's rostered men
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String season = LeagueWeek.season();
        int week = currentWeek();
        String me = configuration.getUserIDToDisplayName().getOrDefault(configuration.getMyID(), configuration.getMyID());
        String seasonStart = LeagueWeek.state().get("season_start_date").getAsString();
        LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        TreeMap<String, Path> days = MarketMovers.cachedDays(season);
        String priorDay = WeekReaction.priorDay(days.keySet(), seasonStart);
        out.append(String.format("%n== THIS WEEK: season %s through week %d, the all-season fit at %d game%s applied to every rostered man's usage ==%n",
                season, week, weekForC, weekForC == 1 ? "" : "s"));
        if(priorDay == null || finalBeta.isEmpty()){
            out.append("no pre-kickoff season snapshot, or no fit: nothing to apply\n");
        }
        else{
            Map<String, MarketMovers.Row> then = MarketMovers.read(days.get(priorDay), scoring);
            Map<Position, InSeasonLearning.Kappa> kappa = InSeasonLearning.fitKappa(harvest, null);
            Map<String, Double> priorPpg = new HashMap<>();
            Map<String, Position> positionOf = new HashMap<>();
            for(MarketMovers.Row row : then.values()){
                Position p = WeekReaction.skill(row.position());
                if(p != null && row.team() != null){
                    priorPpg.put(row.id(), row.points() / WeeklyStarterValue.WEEKS);
                    positionOf.put(row.id(), p);
                }
            }
            List<JsonObject> weeks = new ArrayList<>();
            List<Map<String, Double>> scored = new ArrayList<>();
            for(int w = 1; w <= week; w++){
                weeks.add(JsonParser.parseString(LeagueWeek.actualsBody(season, w)).getAsJsonObject());
                scored.add(LeagueWeek.actualSoFar(season, w));
            }
            Map<String, String> ownerOf = LeagueOwners.today(configuration);
            record Today(String name, Position position, String owner, double posterior, double adjustment, double[] usage, boolean earned){}
            List<Today> today = new ArrayList<>();
            for(Map.Entry<String, String> e : ownerOf.entrySet()){
                String id = e.getKey();
                Position p = positionOf.get(id);
                double[] u = p == null ? null : usageThrough(weeks, id);
                if(p == null || u == null || !finalBeta.containsKey(p)){
                    continue;
                }
                int games = 0;
                double total = 0;
                for(Map<String, Double> w : scored){
                    Double pts = w.get(id);
                    if(pts != null){
                        games++;
                        total += pts;
                    }
                }
                double prior = priorPpg.get(id);
                double observed = games == 0 ? prior : total / games;
                double posterior = WeekReaction.posterior(prior, observed, games, kappa.get(p).kappa());
                int[] features = featuresFor(p);
                double[] picked = new double[features.length];
                for(int f = 0; f < features.length; f++){
                    picked[f] = u[features[f]];
                }
                // The fit is applied only where the leave-one-out test says it helps:
                // a lower error by more than two standard errors. Elsewhere the
                // column prints the usage and no adjustment - an adjustment the
                // test refuted is a typed number wearing a model's clothes.
                double[] gain = finalGain.get(p);
                boolean earned = gain != null && !Double.isNaN(gain[1]) && gain[0] < -2 * gain[1];
                double adjustment = earned ? predict(finalBeta.get(p), standardise(picked, finalMoments.get(p))) : 0;
                MarketMovers.Row row = then.get(id);
                today.add(new Today(row.name(), p, e.getValue(), posterior, adjustment, picked, earned));
            }
            today.sort(Comparator.comparingDouble((Today t) -> -Math.abs(t.adjustment())).thenComparing(t -> !t.owner().equals(me)));
            out.append("positions where the usage term EARNED an adjustment (leave-one-out MAE lower by two standard errors):");
            boolean any = false;
            for(Position p : InSeasonLearning.POSITIONS){
                double[] gain = finalGain.get(p);
                if(gain != null && !Double.isNaN(gain[1]) && gain[0] < -2 * gain[1]){
                    out.append(' ').append(p);
                    any = true;
                }
            }
            out.append(any ? "\n" : " none - every 'usage' cell below is 0 and the stats are shown for reading only\n");
            out.append(String.format("%-22s %-3s %-12s %8s %8s %8s   usage per game%n", "player", "pos", "owner", "rule/g", "usage", "with"));
            int shown = 0;
            for(Today t : today){
                if(!t.owner().equals(me) && shown >= 20){
                    continue;
                }
                if(!t.owner().equals(me)){
                    shown++;
                }
                StringBuilder usage = new StringBuilder();
                int[] features = featuresFor(t.position());
                for(int f = 0; f < features.length; f++){
                    usage.append(String.format(" %s %.1f", NAMES[features[f]], t.usage()[f]));
                }
                out.append(String.format("%-22s %-3s %-12s %8.1f %+8.1f %8.1f  %s%s%n", t.name(), t.position(),
                        t.owner().length() > 12 ? t.owner().substring(0, 12) : t.owner(), t.posterior(), t.adjustment(),
                        t.posterior() + t.adjustment(), usage, t.owner().equals(me) ? "   <- you" : ""));
            }
            out.append("rule/g = the points-only posterior; usage = what the all-season fit adds where it earned it, else 0; my men all, then twenty others.\n");
            out.append("Read section B before trusting a column: the change in MAE and in flips is the whole claim, and the bar is on it.\n");
        }

        System.out.print(out);
        Path report = Path.of("data", "usage-signal-" + LocalDate.now() + ".txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }

    /** Sleeper's week, stepped back to the last week with lines, as WeekReaction does. */
    static int currentWeek(){
        String season = LeagueWeek.season();
        int week = LeagueWeek.week();
        if(Integer.getInteger("week") == null && week > 1 && LeagueWeek.actualSoFar(season, week).isEmpty()){
            week--;
        }
        return week;
    }
}
