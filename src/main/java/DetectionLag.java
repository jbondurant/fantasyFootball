import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * The hinge: how many weeks of evidence does it take to tell a bust from a
 * slow start?
 *
 * `WeeklyStarterValue` promotes a bench man through exactly one channel, an
 * injury draw. Survivors keep their PRESEASON ranking for all eighteen weeks,
 * so a starter who plays every game and disappoints keeps starting and a bench
 * man who breaks out is never promoted. Pricing that missing channel needs one
 * number above all others: the week at which a manager could first have KNOWN.
 * A channel that fires in week 12 is worth a fraction of one that fires in
 * week 3, because the payoff is the weeks that remain.
 *
 * OPERATIONALLY. For every drafted starter, at each cut week k, two predictors
 * of his rest-of-season scoring rate compete:
 *
 *   PRESEASON   what the market thought of him in August, read off the ADP
 *               board through a per-position curve fitted on OTHER seasons.
 *   SEASON-TO-DATE   his own points per game over weeks 1..k.
 *
 * The detection lag is the first k at which season-to-date beats preseason.
 * Reported alongside it is the optimal blend weight w*(k) on season-to-date,
 * which is the more useful object: the crossover is where w* passes 0.5, and
 * w* is what a promotion rule would actually use.
 *
 * NO HINDSIGHT. This repo has twice been bitten by a lineup filled from
 * realised points, which reversed several findings before it was fixed, so
 * every input is dated:
 *
 *   - the season-to-date rate reads weeks 1..k only;
 *   - the target reads weeks k+1..W only, so the two never share a week;
 *   - the preseason curve is fitted LEAVE-ONE-SEASON-OUT, so no player's own
 *     season - and no other player's outcome in that same season - touches the
 *     projection he is graded against. It does borrow the SHAPE of the
 *     ADP-to-points curve from seasons later than the one being scored, which
 *     a real August manager would not have. That shape is close to stationary
 *     and it helps the PRESEASON side, so it makes the detection lag look
 *     LONGER than it is: the number below is a conservative bound.
 *
 * RATES, NOT TOTALS. Points per GAME PLAYED, not per week. A season total has
 * already absorbed the weeks a man missed, and missing weeks is the injury
 * channel the model ALREADY has. The new channel is the man who suits up
 * seventeen times and is bad, so availability is divided out on both sides.
 * -PperWeek=true reruns the whole thing crediting a missed week as zero, which
 * folds the two channels back together; the crossover moves earlier, because
 * an injury is much easier to detect than a slump.
 *
 *   ./gradlew run -Pmain=DetectionLag
 *   ./gradlew run -Pmain=DetectionLag -PperWeek=true
 */
public class DetectionLag {

    /** Rounds 1-9 of a 12-team draft: the picks that fill the nine skill slots. */
    public static final int STARTER_PICKS = 108;

    /** A target rate needs this many games after the cut to be worth grading. */
    public static final int MIN_GAMES_AFTER = 3;

    /** A curve point needs this many games to be a rate rather than a rumour. */
    public static final int MIN_GAMES_FOR_CURVE = 4;

    static final int BOOTSTRAP = 2000;

    /** One man's season: his August price and what he did each week. */
    public record Man(String season, String id, Position position, double adp,
                      int positionRank, double[] weekly){

        /** Points per game over an inclusive week window, or NaN if he never played. */
        public double rate(int from, int to, boolean perWeek){
            double sum = 0;
            int counted = 0;
            for(int week = from; week <= to && week <= weekly.length; week++){
                double points = weekly[week - 1];
                if(!Double.isNaN(points)){
                    sum += points;
                    counted++;
                }
                else if(perWeek){
                    counted++;             // a missed week is a zero he cost you
                }
            }
            return counted == 0 ? Double.NaN : sum / counted;
        }

        public int games(int from, int to){
            int counted = 0;
            for(int week = from; week <= to && week <= weekly.length; week++){
                if(!Double.isNaN(weekly[week - 1])){
                    counted++;
                }
            }
            return counted;
        }

        public int weeks(){
            return weekly.length;
        }
    }

    // ------------------------------------------------------------------
    // The preseason curve: ADP -> points per game, per position.
    // ------------------------------------------------------------------

    /**
     * rate ~ a + b * ln(positional rank), fitted by ordinary least squares.
     *
     * Log rank rather than rank because the board is a price ladder, not a
     * line: the gap between the 1st and 2nd receiver is worth many times the
     * gap between the 41st and 42nd, and a straight line through both ends up
     * wrong at both. Two parameters per position is all thirteen seasons can
     * identify without the fit starting to memorise seasons.
     */
    public record Curve(double intercept, double slope){
        public double predict(int positionRank){
            return intercept + slope * Math.log(Math.max(positionRank, 1));
        }
    }

    public static Curve fit(List<double[]> rankAndRate){
        int n = rankAndRate.size();
        if(n < 3){
            return new Curve(0, 0);
        }
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for(double[] point : rankAndRate){
            double x = Math.log(Math.max(point[0], 1));
            sx += x;
            sy += point[1];
            sxx += x * x;
            sxy += x * point[1];
        }
        double denominator = n * sxx - sx * sx;
        if(Math.abs(denominator) < 1e-9){
            return new Curve(sy / n, 0);
        }
        double slope = (n * sxy - sx * sy) / denominator;
        return new Curve((sy - slope * sx) / n, slope);
    }

    /**
     * The curve each season is graded against - fitted on every OTHER season.
     *
     * This is the whole no-hindsight guarantee for the preseason side. Held-out
     * season s contributes nothing to the curve that prices season s.
     */
    public static Map<String, Map<Position, Curve>> leaveOneSeasonOut(
            Map<String, List<Man>> bySeason, boolean perWeek){
        Map<String, Map<Position, Curve>> curves = new TreeMap<>();
        for(String held : bySeason.keySet()){
            Map<Position, List<double[]>> points = new EnumMap<>(Position.class);
            for(Map.Entry<String, List<Man>> entry : bySeason.entrySet()){
                if(entry.getKey().equals(held)){
                    continue;
                }
                for(Man man : entry.getValue()){
                    if(man.games(1, man.weeks()) < MIN_GAMES_FOR_CURVE){
                        continue;
                    }
                    double rate = man.rate(1, man.weeks(), perWeek);
                    if(Double.isNaN(rate)){
                        continue;
                    }
                    points.computeIfAbsent(man.position(), p -> new ArrayList<>())
                            .add(new double[]{man.positionRank(), rate});
                }
            }
            Map<Position, Curve> perPosition = new EnumMap<>(Position.class);
            points.forEach((position, sample) -> perPosition.put(position, fit(sample)));
            curves.put(held, perPosition);
        }
        return curves;
    }

    // ------------------------------------------------------------------
    // The head-to-head at one cut week.
    // ------------------------------------------------------------------

    /** One graded man at one cut week: what each predictor said, and the truth. */
    public record Contest(String season, Position position, double preseason,
                          double toDate, double rest){}

    public record Verdict(int week, int n, double rmsePreseason, double rmseToDate,
                          double blendWeight, double rmseBlend){
        /** Positive means season-to-date is the better predictor. */
        public double edge(){
            return rmsePreseason - rmseToDate;
        }
    }

    /**
     * The optimal weight on season-to-date in pred = w*toDate + (1-w)*preseason.
     *
     * One free parameter over roughly a thousand graded men, so the in-sample
     * optimum is not meaningfully overfitted - and it is bootstrapped below
     * regardless. Solved rather than searched: with d = toDate - preseason and
     * e = rest - preseason, w* = sum(d*e) / sum(d*d).
     */
    public static double blendWeight(List<Contest> contests){
        double numerator = 0, denominator = 0;
        for(Contest contest : contests){
            double d = contest.toDate() - contest.preseason();
            double e = contest.rest() - contest.preseason();
            numerator += d * e;
            denominator += d * d;
        }
        return denominator < 1e-9 ? 0 : numerator / denominator;
    }

    public static Verdict judge(int week, List<Contest> contests){
        if(contests.isEmpty()){
            return new Verdict(week, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double w = blendWeight(contests);
        double sePre = 0, seTo = 0, seBlend = 0;
        for(Contest contest : contests){
            double blended = w * contest.toDate() + (1 - w) * contest.preseason();
            sePre += sq(contest.rest() - contest.preseason());
            seTo += sq(contest.rest() - contest.toDate());
            seBlend += sq(contest.rest() - blended);
        }
        int n = contests.size();
        return new Verdict(week, n, Math.sqrt(sePre / n), Math.sqrt(seTo / n), w,
                Math.sqrt(seBlend / n));
    }

    static double sq(double x){
        return x * x;
    }

    /** The first week at which season-to-date beats preseason outright. */
    public static int crossover(List<Verdict> verdicts){
        for(Verdict verdict : verdicts){
            if(verdict.n() > 0 && verdict.edge() > 0){
                return verdict.week();
            }
        }
        return -1;
    }

    /** The first week at which a blend leans more than half on the season. */
    public static int halfWeightWeek(List<Verdict> verdicts){
        for(Verdict verdict : verdicts){
            if(verdict.n() > 0 && verdict.blendWeight() >= 0.5){
                return verdict.week();
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Loading.
    // ------------------------------------------------------------------

    /**
     * The paired board-and-outcome sample, from whichever feed is asked for.
     *
     * -Psource=nflverse swaps Sleeper's outcomes for the nflverse files on
     * disk. That is not a cosmetic change: Sleeper carries no rows at all for
     * men who left before it existed, and since the men who vanish are
     * disproportionately the ones who BUSTED, the Sleeper sample is biased in
     * exactly the direction this study cares about. nflverse also lifts the
     * usable count from thirteen seasons to sixteen. Sleeper remains the
     * default so every earlier number in MODEL.md stays reproducible.
     */
    public static Map<String, List<Man>> load(String format){
        if("nflverse".equals(System.getProperty("source"))){
            return NflverseBoards.usable(format);
        }
        Map<String, EraBoards.Board> boards = EraBoards.usable(format,
                EraIngest.MIN_RATE, EraIngest.minDepth());
        Map<String, List<Man>> bySeason = new TreeMap<>();
        for(EraBoards.Board board : boards.values()){
            List<Man> men = new ArrayList<>();
            Map<Position, Integer> rankCounter = new EnumMap<>(Position.class);
            List<String> ordered = new ArrayList<>(board.ids());
            ordered.sort(Comparator.comparingDouble(id -> board.adp().get(id)));
            for(String id : ordered){
                Position position = board.positionOf().get(id);
                if(position == null || !StartingLineup.isSkillPosition(position)){
                    continue;                       // defences are excluded on purpose
                }
                int rank = rankCounter.merge(position, 1, Integer::sum);
                double[] weekly = new double[board.weeks()];
                for(int week = 1; week <= board.weeks(); week++){
                    Double points = board.weekly().get(week - 1).get(id);
                    weekly[week - 1] = points == null ? Double.NaN : points;
                }
                men.add(new Man(board.season(), id, position, board.adp().get(id),
                        rank, weekly));
            }
            bySeason.put(board.season(), men);
        }
        return bySeason;
    }

    /**
     * Every graded man at one cut week, for the drafted starters only.
     *
     * A man with no games through week k is DROPPED, not defaulted: his manager
     * has no season-to-date evidence at all, so there is nothing to compare the
     * preseason against. That is a real and separate case - it is the injury
     * channel the model already prices - and folding it in here would credit
     * the season-to-date side with detecting injuries it never saw.
     */
    public static List<Contest> contestsAt(Map<String, List<Man>> bySeason,
                                           Map<String, Map<Position, Curve>> curves,
                                           int week, boolean perWeek, int maxPick){
        List<Contest> contests = new ArrayList<>();
        for(Map.Entry<String, List<Man>> entry : bySeason.entrySet()){
            Map<Position, Curve> curve = curves.get(entry.getKey());
            for(Man man : entry.getValue()){
                if(man.adp() > maxPick || week >= man.weeks()){
                    continue;
                }
                if(man.games(1, week) < 1
                        || man.games(week + 1, man.weeks()) < MIN_GAMES_AFTER){
                    continue;
                }
                Curve positionCurve = curve.get(man.position());
                if(positionCurve == null){
                    continue;
                }
                double toDate = man.rate(1, week, perWeek);
                double rest = man.rate(week + 1, man.weeks(), perWeek);
                if(Double.isNaN(toDate) || Double.isNaN(rest)){
                    continue;
                }
                contests.add(new Contest(entry.getKey(), man.position(),
                        positionCurve.predict(man.positionRank()), toDate, rest));
            }
        }
        return contests;
    }

    // ------------------------------------------------------------------
    // Error bars. The season is the unit of independent randomness.
    // ------------------------------------------------------------------

    /**
     * Bootstrap over SEASONS, not over men.
     *
     * Two receivers in the same season are not independent draws - they shared
     * a schedule, a set of injuries and one realised football season - so
     * resampling men would report an error bar several times too narrow. This
     * is the mistake that made three earlier results in this repo look real.
     */
    public static double[] bootstrapCrossover(Map<String, List<Man>> bySeason,
                                              Map<String, Map<Position, Curve>> curves,
                                              int lastWeek, boolean perWeek,
                                              int maxPick, long seed){
        List<String> seasons = new ArrayList<>(bySeason.keySet());
        Map<Integer, Map<String, List<Contest>>> byWeek = new HashMap<>();
        for(int week = 1; week <= lastWeek; week++){
            Map<String, List<Contest>> perSeason = new HashMap<>();
            for(Contest contest : contestsAt(bySeason, curves, week, perWeek, maxPick)){
                perSeason.computeIfAbsent(contest.season(), s -> new ArrayList<>())
                        .add(contest);
            }
            byWeek.put(week, perSeason);
        }
        Random random = new Random(seed);
        double[] draws = new double[BOOTSTRAP];
        for(int draw = 0; draw < BOOTSTRAP; draw++){
            List<String> resampled = new ArrayList<>();
            for(int i = 0; i < seasons.size(); i++){
                resampled.add(seasons.get(random.nextInt(seasons.size())));
            }
            int found = lastWeek + 1;
            for(int week = 1; week <= lastWeek; week++){
                List<Contest> pooled = new ArrayList<>();
                for(String season : resampled){
                    List<Contest> some = byWeek.get(week).get(season);
                    if(some != null){
                        pooled.addAll(some);
                    }
                }
                Verdict verdict = judge(week, pooled);
                if(verdict.n() > 0 && verdict.edge() > 0){
                    found = week;
                    break;
                }
            }
            draws[draw] = found;
        }
        return draws;
    }

    public static double percentile(double[] sorted, double p){
        double[] copy = sorted.clone();
        java.util.Arrays.sort(copy);
        int index = (int) Math.round(p / 100.0 * (copy.length - 1));
        return copy[Math.max(0, Math.min(copy.length - 1, index))];
    }

    // ------------------------------------------------------------------

    public static void main(String[] args){
        boolean perWeek = Boolean.getBoolean("perWeek");
        int maxPick = Integer.getInteger("maxPick", STARTER_PICKS);
        String format = System.getProperty("format");

        Map<String, List<Man>> bySeason = load(format);
        Map<String, Map<Position, Curve>> curves = leaveOneSeasonOut(bySeason, perWeek);

        System.out.printf("%nDETECTION LAG%n%n");
        System.out.printf("seasons          %d (%s)%n", bySeason.size(),
                String.join(" ", bySeason.keySet()));
        System.out.printf("population       drafted starters, ADP <= %d"
                + " (rounds 1-%d of 12)%n", maxPick, maxPick / 12);
        System.out.printf("rate             points per %s%n",
                perWeek ? "WEEK, a missed week scored zero (-PperWeek)"
                        : "GAME PLAYED (availability divided out)");
        System.out.printf("target           rest-of-season rate, weeks k+1..W,"
                + " needs %d+ games%n%n", MIN_GAMES_AFTER);

        int lastWeek = 14;
        List<Verdict> verdicts = new ArrayList<>();
        System.out.printf("%-6s %6s %10s %10s %8s %8s %9s%n", "WEEK k", "n",
                "RMSE pre", "RMSE std", "edge", "w* std", "RMSE mix");
        for(int week = 1; week <= lastWeek; week++){
            Verdict verdict = judge(week,
                    contestsAt(bySeason, curves, week, perWeek, maxPick));
            verdicts.add(verdict);
            if(verdict.n() == 0){
                continue;
            }
            System.out.printf("%-6d %6d %10.2f %10.2f %+8.2f %8.2f %9.2f%n",
                    verdict.week(), verdict.n(), verdict.rmsePreseason(),
                    verdict.rmseToDate(), verdict.edge(), verdict.blendWeight(),
                    verdict.rmseBlend());
        }

        int crossover = crossover(verdicts);
        int half = halfWeightWeek(verdicts);
        double[] draws = bootstrapCrossover(bySeason, curves, lastWeek, perWeek,
                maxPick, 20260831L);
        double low = percentile(draws, 2.5);
        double high = percentile(draws, 97.5);
        double median = percentile(draws, 50);

        System.out.printf("%nCROSSOVER%n");
        System.out.printf("   season-to-date first beats preseason at week %s%n",
                crossover < 0 ? "never, through " + lastWeek : String.valueOf(crossover));
        System.out.printf("   the blend first leans more than half on the season"
                + " at week %s%n",
                half < 0 ? "never, through " + lastWeek : String.valueOf(half));
        System.out.printf("   bootstrap over seasons: median %.0f,"
                + " 95%% interval [%.0f, %.0f]%n", median, low, high);
        System.out.printf("   (%d resamples of the %d seasons; a draw that never"
                + " crossed is recorded as %d)%n", BOOTSTRAP, bySeason.size(),
                lastWeek + 1);

        System.out.printf("%nBY POSITION (crossover week, own bootstrap)%n");
        System.out.printf("%-5s %8s %10s %10s%n", "POS", "cross", "95% low",
                "95% high");
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR,
                Position.TE}){
            Map<String, List<Man>> filtered = new TreeMap<>();
            bySeason.forEach((season, men) -> filtered.put(season,
                    men.stream().filter(m -> m.position() == position).toList()));
            List<Verdict> own = new ArrayList<>();
            for(int week = 1; week <= lastWeek; week++){
                own.add(judge(week, contestsAt(filtered, curves, week, perWeek, maxPick)));
            }
            double[] ownDraws = bootstrapCrossover(filtered, curves, lastWeek, perWeek,
                    maxPick, 20260831L);
            int ownCross = crossover(own);
            System.out.printf("%-5s %8s %10.0f %10.0f%n", position,
                    ownCross < 0 ? ">" + lastWeek : String.valueOf(ownCross),
                    percentile(ownDraws, 2.5), percentile(ownDraws, 97.5));
        }

        System.out.printf("%nWHAT THE LAG IS WORTH%n");
        System.out.printf("%-6s %14s %14s %10s%n", "WEEK k", "weeks left",
                "mix beats pre by", "per season");
        for(Verdict verdict : verdicts){
            if(verdict.n() == 0){
                continue;
            }
            int weeksLeft = 18 - verdict.week();
            System.out.printf("%-6d %14d %14.2f %10s%n", verdict.week(), weeksLeft,
                    verdict.rmsePreseason() - verdict.rmseBlend(), "");
        }
        System.out.println("\n   RMSE is in points per game; 'weeks left' is how"
                + "\n   many weeks a promotion made at k could still pay out.");
    }
}
