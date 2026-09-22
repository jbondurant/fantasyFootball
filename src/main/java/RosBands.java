import PlayerImportAndSetup.Position;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * REST OF SEASON AFTER k GAMES, WITH A BAND - AND IS THE BAND HONEST?
 *
 * Justin, after week 1: can the update rule be improved for estimating each
 * man's rest-of-season rate with bands? The conjugate model {@link
 * InSeasonLearning} fitted gives a band for free - the posterior variance of
 * the true rate is sigma_w^2/(kappa+g), and the mean of his next m games adds
 * sigma_w^2/m of game noise - so the question is not whether a band can be
 * drawn but whether it is CALIBRATED: does the 80% band hold the realised rate
 * 80% of the time, position by position, on thirteen seasons it was not fitted
 * to? Two candidate improvements are tested the same way, and the one the data
 * prefers is what {@link WeekReaction} prints:
 *
 *   LEVEL   the scatter is the position's, as the study fitted it: every
 *           quarterback's band is the same width in points.
 *   RATE    the scatter scales with the man's own rate: an elite man's weeks
 *           swing more in points than a deep man's. Same kappa (it is a ratio
 *           of variances and cancels), a different width per man.
 *   for each, NORMAL quantiles or EMPIRICAL ones - the quantiles of the
 *           standardised residuals on the OTHER seasons, which is where a
 *           right-skewed game goes if a normal under-covers the top.
 *
 * Judged by coverage (honesty) and by the interval score at 80% (honesty and
 * sharpness together: width plus a penalty for every point a miss falls
 * outside), every prior, level, kappa and quantile fitted leave-one-season-out.
 *
 * And the question underneath: does Sleeper's own next-week projection, made
 * after week k with the injury report and the matchup, beat the posterior as
 * an estimate of what the man does from week k+2 on? Five seasons carry the
 * weekly projections (2021-2025); the paired |error| against the same target
 * on the same men says so, clustered on season.
 *
 *   ./gradlew run -Pmain=RosBands
 */
public class RosBands {

    /** A central band on a man's rest-of-season rate, points a game. */
    public record Band(double mean, double low, double high, double sd){}

    /** The recipe the calibration chose: the scale model and, per position, the 80% multipliers. */
    public record Recipe(boolean rateScaled, boolean empirical, Map<Position, double[]> q80,
                         Map<Position, Double> within, Map<Position, Double> kappa, Map<Position, Double> cv){}

    static final double[] COVERAGE = {0.5, 0.8, 0.95};
    /** Two-sided normal quantiles for COVERAGE: mathematics, not a measurement. */
    static final double[] Z = {0.6745, 1.2816, 1.9600};
    static final int[] SEEN = {1, 2, 4, 8};

    /** Variance of the true rate after g games: the within scatter over kappa+g. */
    static double rateVariance(double scatter, double kappa, int games){
        return scatter / (kappa + games);
    }

    /** Variance of the mean of his next m games: the rate's, plus the game noise over m. */
    static double meanVariance(double scatter, double kappa, int games, int m){
        return rateVariance(scatter, kappa, games) + scatter / m;
    }

    /** The within-week variance in points^2 under the chosen scale: the level's, or the man's own rate's. */
    static double scatter(double withinLevelUnits, double level, double cv, double mean, boolean rateScaled){
        return rateScaled ? cv * cv * mean * mean : withinLevelUnits * level * level;
    }

    /** A central band from a mean, a variance and lower/upper multipliers. */
    static Band band(double mean, double variance, double lower, double upper){
        double sd = Math.sqrt(variance);
        return new Band(mean, mean + lower * sd, mean + upper * sd, sd);
    }

    /** The p-th quantile of a sorted array by linear interpolation. */
    static double quantile(double[] sorted, double p){
        if(sorted.length == 0){
            return Double.NaN;
        }
        double position = p * (sorted.length - 1);
        int i = (int) Math.floor(position);
        int j = Math.min(sorted.length - 1, i + 1);
        return sorted[i] + (position - i) * (sorted[j] - sorted[i]);
    }

    /** Interval score at coverage c: width, plus 2/alpha times every point the realised value falls outside. Lower is better. */
    static double intervalScore(double low, double high, double realised, double coverage){
        double alpha = 1 - coverage;
        double score = high - low;
        if(realised < low){
            score += 2 / alpha * (low - realised);
        }
        if(realised > high){
            score += 2 / alpha * (realised - high);
        }
        return score;
    }

    /** The coefficient of variation of a man's weeks, fitted on the field: sqrt of the mean of variance / rate^2. */
    static double fitCv(List<InSeasonLearning.Man> men, Position position, String heldOut){
        double sum = 0;
        double weight = 0;
        for(InSeasonLearning.Man man : men){
            if(man.season().equals(heldOut) || man.position() != position
                    || man.rank() > InSeasonLearning.CAP.get(position) || man.games() < InSeasonLearning.MIN_GAMES){
                continue;
            }
            double variance = man.weekVariance();
            double rate = man.ppg();
            if(Double.isNaN(variance) || rate <= 0){
                continue;
            }
            sum += (man.games() - 1) * variance / (rate * rate);
            weight += man.games() - 1;
        }
        return weight == 0 ? 0 : Math.sqrt(sum / weight);
    }

    /** One man's forecast at week k against what he then did. */
    record Case(String season, Position position, int seen, double mean, double sdLevel, double sdRate,
                double realised, int games){}

    /** Every forecast on the harvest at the given weeks seen, priors, kappas, levels and cv fitted without the man's season. */
    static List<Case> cases(Map<String, List<InSeasonLearning.Man>> harvest, List<String> seasons,
                            Map<String, Map<Position, double[]>> priors,
                            Map<String, Map<Position, InSeasonLearning.Kappa>> kappas,
                            Map<String, Map<Position, Double>> cvs, int[] seenAt, int minRest){
        List<Case> out = new ArrayList<>();
        List<InSeasonLearning.Man> everyone = new ArrayList<>();
        for(List<InSeasonLearning.Man> men : harvest.values()){
            everyone.addAll(men);
        }
        for(String season : seasons){
            List<InSeasonLearning.Man> men = harvest.get(season);
            for(int seen : seenAt){
                for(Position position : InSeasonLearning.POSITIONS){
                    double level = InSeasonLearning.levelThrough(men, position, seen);
                    InSeasonLearning.Kappa k = kappas.get(season).get(position);
                    double cv = cvs.get(season).get(position);
                    for(InSeasonLearning.Man man : men){
                        if(man.position() != position || man.rank() > InSeasonLearning.CAP.get(position)
                                || man.gamesThrough(seen) < 1 || man.restGames(seen) < minRest){
                            continue;
                        }
                        int games = man.gamesThrough(seen);
                        int m = man.restGames(seen);
                        double mean = InSeasonLearning.estimate(man, seen, k.kappa(), priors.get(season), level);
                        double realised = man.restPoints(seen) / m;
                        double varLevel = meanVariance(scatter(k.within(), level, cv, mean, false), k.kappa(), games, m);
                        double varRate = meanVariance(scatter(k.within(), level, cv, mean, true), k.kappa(), games, m);
                        out.add(new Case(season, position, seen, mean, Math.sqrt(varLevel), Math.sqrt(varRate),
                                realised, games));
                    }
                }
            }
        }
        return out;
    }

    /** Mean and standard error of a per-season statistic over the seasons it is measured in. */
    static double[] overSeasons(Map<String, List<Double>> perSeason){
        List<Double> means = new ArrayList<>();
        for(List<Double> values : perSeason.values()){
            if(values.isEmpty()){
                continue;
            }
            double sum = 0;
            for(double v : values){
                sum += v;
            }
            means.add(sum / values.size());
        }
        if(means.isEmpty()){
            return new double[]{Double.NaN, Double.NaN, 0};
        }
        double mean = 0;
        for(double v : means){
            mean += v;
        }
        mean /= means.size();
        double ss = 0;
        for(double v : means){
            ss += (v - mean) * (v - mean);
        }
        double se = means.size() < 2 ? Double.NaN : Math.sqrt(ss / (means.size() - 1) / means.size());
        return new double[]{mean, se, means.size()};
    }

    /** The empirical 80% multipliers of the standardised residuals, fitted on every season but the held-out one. */
    static double[] empiricalQ80(List<Case> cases, Position position, int seen, boolean rateScaled, String heldOut){
        List<Double> z = new ArrayList<>();
        for(Case c : cases){
            if(c.position() == position && c.seen() == seen && !c.season().equals(heldOut)){
                z.add((c.realised() - c.mean()) / (rateScaled ? c.sdRate() : c.sdLevel()));
            }
        }
        double[] sorted = new double[z.size()];
        for(int i = 0; i < sorted.length; i++){
            sorted[i] = z.get(i);
        }
        Arrays.sort(sorted);
        return new double[]{quantile(sorted, 0.10), quantile(sorted, 0.90)};
    }

    /**
     * The recipe for week `seen`: whichever of the four (level/rate x
     * normal/empirical) has the lowest 80% interval score on the harvest,
     * with its per-position multipliers and the fitted constants. The scores
     * are printed by main; this is what WeekReaction reads.
     */
    static Recipe fit(Map<String, List<InSeasonLearning.Man>> harvest, int seen){
        List<String> seasons = new ArrayList<>(harvest.keySet());
        Map<String, Map<Position, double[]>> priors = new TreeMap<>();
        Map<String, Map<Position, InSeasonLearning.Kappa>> kappas = new TreeMap<>();
        Map<String, Map<Position, Double>> cvs = new TreeMap<>();
        List<InSeasonLearning.Man> everyone = new ArrayList<>();
        for(List<InSeasonLearning.Man> men : harvest.values()){
            everyone.addAll(men);
        }
        for(String season : seasons){
            priors.put(season, InSeasonLearning.priorTable(harvest, season, null));
            kappas.put(season, InSeasonLearning.fitKappa(harvest, season));
            Map<Position, Double> cv = new EnumMap<>(Position.class);
            for(Position position : InSeasonLearning.POSITIONS){
                cv.put(position, fitCv(everyone, position, season));
            }
            cvs.put(season, cv);
        }
        List<Case> cases = cases(harvest, seasons, priors, kappas, cvs, new int[]{seen}, 3);
        double best = Double.MAX_VALUE;
        boolean bestRate = false;
        boolean bestEmpirical = false;
        for(boolean rate : new boolean[]{false, true}){
            for(boolean empirical : new boolean[]{false, true}){
                double total = 0;
                int n = 0;
                for(Case c : cases){
                    double[] q = empirical ? empiricalQ80(cases, c.position(), seen, rate, c.season())
                            : new double[]{-Z[1], Z[1]};
                    double sd = rate ? c.sdRate() : c.sdLevel();
                    total += intervalScore(c.mean() + q[0] * sd, c.mean() + q[1] * sd, c.realised(), 0.8);
                    n++;
                }
                if(n > 0 && total / n < best){
                    best = total / n;
                    bestRate = rate;
                    bestEmpirical = empirical;
                }
            }
        }
        Map<Position, double[]> q80 = new EnumMap<>(Position.class);
        Map<Position, Double> within = new EnumMap<>(Position.class);
        Map<Position, Double> kappa = new EnumMap<>(Position.class);
        Map<Position, Double> cv = new EnumMap<>(Position.class);
        Map<Position, InSeasonLearning.Kappa> all = InSeasonLearning.fitKappa(harvest, null);
        for(Position position : InSeasonLearning.POSITIONS){
            q80.put(position, bestEmpirical ? empiricalQ80(cases, position, seen, bestRate, "none")
                    : new double[]{-Z[1], Z[1]});
            within.put(position, all.get(position).within());
            kappa.put(position, all.get(position).kappa());
            cv.put(position, fitCv(everyone, position, "none"));
        }
        return new Recipe(bestRate, bestEmpirical, q80, within, kappa, cv);
    }

    /** The 80% band on a man's rest-of-season rate under a recipe: prior and observed in points a game, `left` games to come. */
    public static Band band(Recipe recipe, Position position, double prior, double observed, int games,
                            double level, int left){
        double kappa = recipe.kappa().get(position);
        double mean = WeekReaction.posterior(prior, observed, games, kappa);
        double scatter = scatter(recipe.within().get(position), level, recipe.cv().get(position), mean, recipe.rateScaled());
        double[] q = recipe.q80().get(position);
        return band(mean, meanVariance(scatter, kappa, games, Math.max(1, left)), q[0], q[1]);
    }

    /* ---------------------------------------------------------------- the report */

    public static void main(String[] args) throws IOException {
        String format = System.getProperty("format");
        Map<String, EraBoards.Board> boards = EraBoards.usable(
                format == null ? "ppr" : format, EraIngest.MIN_RATE, EraIngest.minDepth());
        Map<String, List<InSeasonLearning.Man>> harvest = InSeasonLearning.harvest(boards);
        List<String> seasons = new ArrayList<>(harvest.keySet());
        Map<String, Map<Position, double[]>> priors = new TreeMap<>();
        Map<String, Map<Position, InSeasonLearning.Kappa>> kappas = new TreeMap<>();
        Map<String, Map<Position, Double>> cvs = new TreeMap<>();
        List<InSeasonLearning.Man> everyone = new ArrayList<>();
        for(List<InSeasonLearning.Man> men : harvest.values()){
            everyone.addAll(men);
        }
        for(String season : seasons){
            priors.put(season, InSeasonLearning.priorTable(harvest, season, null));
            kappas.put(season, InSeasonLearning.fitKappa(harvest, season));
            Map<Position, Double> cv = new EnumMap<>(Position.class);
            for(Position position : InSeasonLearning.POSITIONS){
                cv.put(position, fitCv(everyone, position, season));
            }
            cvs.put(season, cv);
        }
        List<Case> cases = cases(harvest, seasons, priors, kappas, cvs, SEEN, 3);

        StringBuilder out = new StringBuilder();
        out.append(String.format("REST-OF-SEASON BANDS  (%d seasons %s-%s, %d forecasts; every prior, kappa, level, cv and quantile fitted leave-one-season-out)%n",
                seasons.size(), seasons.get(0), seasons.get(seasons.size() - 1), cases.size()));
        out.append("a forecast: the posterior mean of a man's rate after k games, against the mean of his remaining played games (at least 3).\n");
        out.append("coverage = the share of realised rates inside the band, mean over seasons +- se; score = 80% interval score, points, lower is better.\n");

        for(int seen : SEEN){
            out.append(String.format("%n== after %d game%s ==%n", seen, seen == 1 ? "" : "s"));
            out.append(String.format("%-4s %5s | %-30s | %-30s | %-34s | %s%n", "pos", "men",
                    "LEVEL normal: cov50 cov80 cov95", "RATE normal: cov50 cov80 cov95",
                    "score80: L-norm L-emp R-norm R-emp", "z p10/p90 (level) - a normal reads -1.28/+1.28"));
            for(Position position : InSeasonLearning.POSITIONS){
                Map<String, List<Double>>[] covLevel = new Map[3];
                Map<String, List<Double>>[] covRate = new Map[3];
                for(int i = 0; i < 3; i++){
                    covLevel[i] = new TreeMap<>();
                    covRate[i] = new TreeMap<>();
                }
                Map<String, List<Double>> sLn = new TreeMap<>();
                Map<String, List<Double>> sLe = new TreeMap<>();
                Map<String, List<Double>> sRn = new TreeMap<>();
                Map<String, List<Double>> sRe = new TreeMap<>();
                List<Double> zs = new ArrayList<>();
                int men = 0;
                for(Case c : cases){
                    if(c.position() != position || c.seen() != seen){
                        continue;
                    }
                    men++;
                    for(int i = 0; i < 3; i++){
                        covLevel[i].computeIfAbsent(c.season(), s -> new ArrayList<>())
                                .add(Math.abs(c.realised() - c.mean()) <= Z[i] * c.sdLevel() ? 1.0 : 0.0);
                        covRate[i].computeIfAbsent(c.season(), s -> new ArrayList<>())
                                .add(Math.abs(c.realised() - c.mean()) <= Z[i] * c.sdRate() ? 1.0 : 0.0);
                    }
                    double[] qL = empiricalQ80(cases, position, seen, false, c.season());
                    double[] qR = empiricalQ80(cases, position, seen, true, c.season());
                    sLn.computeIfAbsent(c.season(), s -> new ArrayList<>()).add(
                            intervalScore(c.mean() - Z[1] * c.sdLevel(), c.mean() + Z[1] * c.sdLevel(), c.realised(), 0.8));
                    sLe.computeIfAbsent(c.season(), s -> new ArrayList<>()).add(
                            intervalScore(c.mean() + qL[0] * c.sdLevel(), c.mean() + qL[1] * c.sdLevel(), c.realised(), 0.8));
                    sRn.computeIfAbsent(c.season(), s -> new ArrayList<>()).add(
                            intervalScore(c.mean() - Z[1] * c.sdRate(), c.mean() + Z[1] * c.sdRate(), c.realised(), 0.8));
                    sRe.computeIfAbsent(c.season(), s -> new ArrayList<>()).add(
                            intervalScore(c.mean() + qR[0] * c.sdRate(), c.mean() + qR[1] * c.sdRate(), c.realised(), 0.8));
                    zs.add((c.realised() - c.mean()) / c.sdLevel());
                }
                if(men == 0){
                    continue;
                }
                double[] sortedZ = new double[zs.size()];
                for(int i = 0; i < sortedZ.length; i++){
                    sortedZ[i] = zs.get(i);
                }
                Arrays.sort(sortedZ);
                out.append(String.format("%-4s %5d | %4.0f%%  %4.0f%%  %4.0f%% (+-%.0f)     | %4.0f%%  %4.0f%%  %4.0f%% (+-%.0f)     | %6.2f %6.2f %6.2f %6.2f         | %+.2f / %+.2f%n",
                        position, men,
                        100 * overSeasons(covLevel[0])[0], 100 * overSeasons(covLevel[1])[0], 100 * overSeasons(covLevel[2])[0], 100 * overSeasons(covLevel[1])[1],
                        100 * overSeasons(covRate[0])[0], 100 * overSeasons(covRate[1])[0], 100 * overSeasons(covRate[2])[0], 100 * overSeasons(covRate[1])[1],
                        overSeasons(sLn)[0], overSeasons(sLe)[0], overSeasons(sRn)[0], overSeasons(sRe)[0],
                        quantile(sortedZ, 0.10), quantile(sortedZ, 0.90)));
            }
            Recipe recipe = fit(harvest, seen);
            out.append(String.format("chosen by the 80%% score: %s scale, %s quantiles;  80%% multipliers ",
                    recipe.rateScaled() ? "RATE" : "LEVEL", recipe.empirical() ? "EMPIRICAL" : "normal"));
            for(Position position : InSeasonLearning.POSITIONS){
                double[] q = recipe.q80().get(position);
                out.append(String.format("%s %+.2f/%+.2f  ", position, q[0], q[1]));
            }
            out.append('\n');
        }
        out.append("\ncv = a man's week-to-week sd over his own rate, fitted on the field:");
        Map<Position, Double> cvAll = new EnumMap<>(Position.class);
        for(Position position : InSeasonLearning.POSITIONS){
            cvAll.put(position, fitCv(everyone, position, "none"));
            out.append(String.format(" %s %.2f", position, cvAll.get(position)));
        }
        out.append("\n");

        // ---------------------------------------------- Sleeper's next-week number against the posterior
        out.append("\n== SLEEPER'S NEXT-WEEK PROJECTION AGAINST THE POSTERIOR, as estimates of the man's rate from week k+2 on ==\n");
        out.append("seasons with weekly projections on disk; league-scored; paired |error| on the same men, clustered on season.\n");
        out.append(String.format("%-5s %-4s %6s %8s %8s %8s %8s   %s%n", "seen", "pos", "men", "r post", "r slpr", "MAE post", "MAE slpr",
                "|err slpr| - |err post|, mean +- se over seasons"));
        LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        for(int seen : new int[]{1, 2, 4}){
            for(Position position : InSeasonLearning.POSITIONS){
                Map<String, List<Double>> diff = new TreeMap<>();
                List<double[]> post = new ArrayList<>();
                List<double[]> slpr = new ArrayList<>();
                double maePost = 0;
                double maeSlpr = 0;
                int men = 0;
                for(String season : seasons){
                    Path file = Path.of("sleeperWeekProjection" + season + "w" + (seen + 1) + ".txt");
                    if(!Files.exists(file)){
                        continue;
                    }
                    Map<String, Double> next = LeagueWeek.projectedFrom(Files.readString(file, StandardCharsets.UTF_8));
                    List<InSeasonLearning.Man> menOf = harvest.get(season);
                    double level = InSeasonLearning.levelThrough(menOf, position, seen);
                    InSeasonLearning.Kappa k = kappas.get(season).get(position);
                    for(InSeasonLearning.Man man : menOf){
                        if(man.position() != position || man.rank() > InSeasonLearning.CAP.get(position)
                                || man.gamesThrough(seen) < 1 || man.restGames(seen + 1) < 3 || !next.containsKey(man.id())){
                            continue;
                        }
                        double target = man.restPoints(seen + 1) / man.restGames(seen + 1);
                        double posterior = InSeasonLearning.estimate(man, seen, k.kappa(), priors.get(season), level);
                        double sleeper = next.get(man.id());
                        post.add(new double[]{posterior, target});
                        slpr.add(new double[]{sleeper, target});
                        maePost += Math.abs(posterior - target);
                        maeSlpr += Math.abs(sleeper - target);
                        diff.computeIfAbsent(season, s -> new ArrayList<>()).add(Math.abs(sleeper - target) - Math.abs(posterior - target));
                        men++;
                    }
                }
                if(men < 3){
                    continue;
                }
                double[] d = overSeasons(diff);
                out.append(String.format("%-5d %-4s %6d %8.3f %8.3f %8.2f %8.2f   %+.2f +- %.2f (%d seasons)%s%n", seen, position, men,
                        WeeklyFeedAudit.fit(post).r(), WeeklyFeedAudit.fit(slpr).r(), maePost / men, maeSlpr / men,
                        d[0], d[1], (int) d[2], Math.abs(d[0]) > 2 * d[1] ? "  <- separated" : ""));
            }
        }
        out.append("negative = Sleeper's next-week number is the closer estimate. The posterior reads weeks 1..k and a preseason board;\n");
        out.append("Sleeper's week k+1 number reads the same weeks plus the injury report and the matchup for one game.\n");
        out.append("NOT here: the band on a rest-of-season TOTAL, which needs the availability draw WeeklyStarterValue already makes.\n");

        System.out.print(out);
        Path report = Path.of("data", "ros-bands-" + LocalDate.now() + ".txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }
}
