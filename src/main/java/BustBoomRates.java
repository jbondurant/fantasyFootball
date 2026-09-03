import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * How often a drafted starter falls far enough to be benched, and how often a
 * late pick rises far enough to take his place - by position and by where on
 * the board he was taken.
 *
 * {@link FogFit} already answers a version of this: actual over projected
 * season points, five seasons, bust at under half and boom at over 1.3x. This
 * class does not replace it, it WIDENS it, and changes three things that
 * matter for a promotion rule:
 *
 *   THIRTEEN SEASONS, not five. FogFit is anchored on a projection feed that
 *   exists from 2021. The ADP board runs to 2010, so the denominator here is
 *   what the MARKET expected rather than what one projection service said -
 *   available for every season, and arguably the better number anyway, since
 *   the board is the price twelve managers actually paid.
 *
 *   PER GAME, not per season. A season total has already absorbed the weeks a
 *   man missed, so FogFit's bust rate is injuries and slumps added together.
 *   The model ALREADY prices injuries, through the !up() draw. The channel
 *   being costed here is the man who suits up and is bad, so availability is
 *   reported separately rather than folded in. -PperWeek=true puts them back
 *   together for comparison with FogFit's definition.
 *
 *   DRAFT TIERS, not projection tiers. The question a promotion rule asks is
 *   "should the round-10 man replace the round-4 man", so the buckets are
 *   rounds of a twelve-team draft.
 *
 * Error bars are bootstrapped over SEASONS. Two backs in one season shared a
 * schedule and one realised football year; resampling players instead would
 * report a bar several times too narrow.
 *
 *   ./gradlew run -Pmain=BustBoomRates
 *   ./gradlew run -Pmain=BustBoomRates -PperWeek=true
 */
public class BustBoomRates {

    /** Bucket edges in overall picks: rounds 1-2, 3-4, 5-6, 7-9, 10-13, 14-16. */
    static final int[] EDGES = {24, 48, 72, 108, 156, 192};
    static final String[] LABELS = {"rounds 1-2", "rounds 3-4", "rounds 5-6",
            "rounds 7-9", "rounds 10-13", "rounds 14-16"};

    /** FogFit's thresholds, kept identical so the two numbers can be compared. */
    static final double BUST = 0.5;
    static final double BOOM = 1.3;

    static final int MIN_GAMES = 4;
    static final int BOOTSTRAP = 2000;

    static int bucket(double adp){
        for(int i = 0; i < EDGES.length; i++){
            if(adp <= EDGES[i]){
                return i;
            }
        }
        return -1;
    }

    /** One man's season reduced to the two numbers this class is about. */
    public record Outcome(String season, Position position, int bucket,
                          double ratio, int games, boolean startable){}

    /**
     * Did he finish as a man you would actually have started?
     *
     * The bust rate on its own is a ratio question and answers "did he return
     * his price". A manager asks a different one: "is he still one of the best
     * twelve quarterbacks".
     *
     * The league starts 1 QB, 2 RB, 3 WR, 1 TE and two flexes across twelve
     * teams: 108 skill slots, of which 84 are fixed (12 QB, 24 RB, 36 WR,
     * 12 TE) and 24 are flexes. The flexes are split evenly between backs and
     * receivers, which is an assumption and the only one in this class - a
     * tight end in a flex is rare enough to ignore and a quarterback cannot go
     * there at all. The four depths are asserted to sum to 108 in the test, so
     * a future change to the lineup cannot silently leave slots unaccounted
     * for; the first version of this method quietly summed to 96.
     */
    static int startableDepth(Position position){
        return switch(position){
            case QB -> 12;                          // 12 fixed
            case TE -> 12;                          // 12 fixed
            case RB -> 24 + 12;                     // 24 fixed + half the flexes
            case WR -> 36 + 12;                     // 36 fixed + half the flexes
            default -> 12;
        };
    }

    public static List<Outcome> collect(boolean perWeek){
        Map<String, List<DetectionLag.Man>> bySeason = DetectionLag.load(null);
        Map<String, Map<Position, DetectionLag.Curve>> curves =
                DetectionLag.leaveOneSeasonOut(bySeason, perWeek);
        List<Outcome> outcomes = new ArrayList<>();
        for(Map.Entry<String, List<DetectionLag.Man>> entry : bySeason.entrySet()){
            Map<Position, DetectionLag.Curve> curve = curves.get(entry.getKey());

            // The startable line for this season, measured on this season: the
            // Nth best per-game rate at each position among men who played
            // enough to hold a slot. This is a WITHIN-season ranking, so it is
            // hindsight by construction - it has to be, because "would you have
            // started him" is a question about the finished season. It is used
            // ONLY to label outcomes, never to choose a lineup.
            Map<Position, Double> line = new EnumMap<>(Position.class);
            for(Position position : new Position[]{Position.QB, Position.RB,
                    Position.WR, Position.TE}){
                List<Double> rates = new ArrayList<>();
                for(DetectionLag.Man man : entry.getValue()){
                    if(man.position() != position
                            || man.games(1, man.weeks()) < MIN_GAMES){
                        continue;
                    }
                    double rate = man.rate(1, man.weeks(), perWeek);
                    if(!Double.isNaN(rate)){
                        rates.add(rate);
                    }
                }
                rates.sort((a, b) -> Double.compare(b, a));
                int depth = startableDepth(position);
                line.put(position, rates.size() >= depth ? rates.get(depth - 1) : 0);
            }

            for(DetectionLag.Man man : entry.getValue()){
                int bucket = bucket(man.adp());
                DetectionLag.Curve positionCurve = curve == null ? null
                        : curve.get(man.position());
                if(bucket < 0 || positionCurve == null){
                    continue;
                }
                int games = man.games(1, man.weeks());
                double expected = positionCurve.predict(man.positionRank());
                double rate = games == 0 ? 0 : man.rate(1, man.weeks(), perWeek);
                if(Double.isNaN(rate)){
                    rate = 0;
                }
                double ratio = expected <= 0 ? Double.NaN
                        : Math.min(rate / expected, 2.5);
                if(Double.isNaN(ratio)){
                    continue;
                }
                outcomes.add(new Outcome(entry.getKey(), man.position(), bucket, ratio,
                        games, games >= MIN_GAMES
                        && rate >= line.getOrDefault(man.position(), 0.0)));
            }
        }
        return outcomes;
    }

    // ------------------------------------------------------------------

    public static void main(String[] args){
        boolean perWeek = Boolean.getBoolean("perWeek");
        List<Outcome> outcomes = collect(perWeek);
        List<String> seasons = outcomes.stream().map(Outcome::season).distinct()
                .sorted().toList();

        System.out.printf("%nBUST AND BOOM RATES%n%n");
        System.out.printf("seasons          %d (%s)%n", seasons.size(),
                String.join(" ", seasons));
        System.out.printf("denominator      the ADP board's own curve, fitted"
                + " leave-one-season-out%n");
        System.out.printf("rate             points per %s%n",
                perWeek ? "WEEK, a missed week scored zero (-PperWeek)"
                        : "GAME PLAYED (injury reported separately)");
        System.out.printf("bust             ratio < %.1f      boom  ratio > %.1f"
                + "   (FogFit's thresholds)%n%n", BUST, BOOM);

        System.out.printf("%-4s %-13s %6s %7s %6s %7s %7s %8s %8s%n", "POS", "TIER",
                "n", "mean", "sd", "bust", "boom", "startable", "games");
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR,
                Position.TE}){
            for(int bucket = 0; bucket < LABELS.length; bucket++){
                final int b = bucket;
                List<Outcome> some = outcomes.stream()
                        .filter(o -> o.position() == position && o.bucket() == b)
                        .toList();
                if(some.size() < 15){
                    continue;
                }
                double mean = some.stream().mapToDouble(Outcome::ratio).average()
                        .orElse(0);
                double var = some.stream().mapToDouble(o -> sq(o.ratio() - mean)).sum()
                        / (some.size() - 1);
                double bust = share(some, o -> o.ratio() < BUST);
                double boom = share(some, o -> o.ratio() > BOOM);
                double startable = share(some, Outcome::startable);
                double games = some.stream().mapToInt(Outcome::games).average().orElse(0);
                System.out.printf("%-4s %-13s %6d %7.2f %6.2f %6.0f%% %6.0f%%"
                                + " %7.0f%% %8.1f%n", position, LABELS[bucket],
                        some.size(), mean, Math.sqrt(var), bust * 100, boom * 100,
                        startable * 100, games);
            }
        }

        System.out.printf("%nTHE TWO NUMBERS A PROMOTION RULE NEEDS,"
                + " WITH ERROR BARS%n%n");
        System.out.printf("%-4s %26s %26s%n", "",
                "BUST: a rounds 1-9 pick", "BOOM: a rounds 10-16 pick");
        System.out.printf("%-4s %12s %13s %12s %13s%n", "POS", "not startable",
                "95% interval", "startable", "95% interval");
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR,
                Position.TE}){
            List<Outcome> early = outcomes.stream()
                    .filter(o -> o.position() == position && o.bucket() <= 3).toList();
            List<Outcome> late = outcomes.stream()
                    .filter(o -> o.position() == position && o.bucket() >= 4).toList();
            if(early.size() < 15 || late.size() < 15){
                continue;
            }
            double[] bustDraws = bootstrap(early, o -> !o.startable(), 20260831L);
            double[] boomDraws = bootstrap(late, Outcome::startable, 20260831L);
            System.out.printf("%-4s %11.0f%% [%4.0f%%, %4.0f%%] %11.0f%%"
                            + " [%4.0f%%, %4.0f%%]%n", position,
                    share(early, o -> !o.startable()) * 100,
                    DetectionLag.percentile(bustDraws, 2.5) * 100,
                    DetectionLag.percentile(bustDraws, 97.5) * 100,
                    share(late, Outcome::startable) * 100,
                    DetectionLag.percentile(boomDraws, 2.5) * 100,
                    DetectionLag.percentile(boomDraws, 97.5) * 100);
        }
        System.out.printf("%n   'startable' = finished inside the league's own"
                + " starting depth at his%n   position (%d QB, %d RB, %d WR, %d TE"
                + " - the 108 skill slots twelve teams%n   start, flexes split"
                + " evenly between backs and receivers) on points per%n   game,"
                + " having played at least %d. Bootstrapped over %d seasons.%n",
                startableDepth(Position.QB), startableDepth(Position.RB),
                startableDepth(Position.WR), startableDepth(Position.TE), MIN_GAMES,
                seasons.size());
        System.out.printf("%n   The line is measured among BOARD players only -"
                + " men with a national ADP.%n   That is nearly all of the"
                + " startable population at every position, but it%n   makes the"
                + " line very slightly lenient at the deep ones.%n%n");
    }

    static double sq(double x){
        return x * x;
    }

    static double share(List<Outcome> outcomes,
                        java.util.function.Predicate<Outcome> test){
        return outcomes.isEmpty() ? 0
                : outcomes.stream().filter(test).count() / (double) outcomes.size();
    }

    /** Resample seasons, not players - a season is one realised football year. */
    static double[] bootstrap(List<Outcome> outcomes,
                              java.util.function.Predicate<Outcome> test, long seed){
        Map<String, List<Outcome>> bySeason = new HashMap<>();
        for(Outcome outcome : outcomes){
            bySeason.computeIfAbsent(outcome.season(), s -> new ArrayList<>())
                    .add(outcome);
        }
        List<String> seasons = new ArrayList<>(bySeason.keySet());
        Random random = new Random(seed);
        double[] draws = new double[BOOTSTRAP];
        for(int draw = 0; draw < BOOTSTRAP; draw++){
            List<Outcome> pooled = new ArrayList<>();
            for(int i = 0; i < seasons.size(); i++){
                pooled.addAll(bySeason.get(seasons.get(random.nextInt(seasons.size()))));
            }
            draws[draw] = share(pooled, test);
        }
        return draws;
    }
}
