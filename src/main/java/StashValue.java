import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One model for every pick from round 8 to round 16.
 *
 * These were split before - rounds 8-9 valued for this season, rounds 10-16 as
 * keeper stashes - and the split was on the wrong axis. Both values belong to
 * every pick in the range: a round-8 breakout is keepable at round 8 next year,
 * and a round-14 stash can start for you this season. The data shows no cliff
 * either, just a smooth decay (44.0 / 32.8 / 31.2 points over the wire across
 * 8-9, 10-12, 13-16) and flat next-season hit rates.
 *
 * So value each pick as the sum of what it is actually worth:
 *
 *     value(player, round R) = max(0, this season - wire)
 *                            + max(0, next season - wire - price of a round-R pick)
 *
 * Both terms are floored because both are options. You drop a bust in week 4
 * and stream, and you decline a keeper who is not worth his round. The second
 * term prices its own opportunity cost: keeping him at R costs you the round-R
 * pick next year, and this same dataset says what a round-R pick is worth.
 *
 * Usage:
 *   ./gradlew run -Pmain=StashValue
 */
public class StashValue {

    private static BenchValue.History cached;
    private static Map<Integer, Double> cachedPrice;
    private static double cachedCapture;

    private static synchronized void load(AAAConfiguration configuration){
        if(cached != null){
            return;
        }
        cached = BenchValue.gather(configuration);
        cachedCapture = KeeperOrigin.captureRate(configuration);
        cachedPrice = new HashMap<>();
        for(int round = 8; round <= 16; round++){
            final int r = round;
            List<BenchValue.Bench> band = cached.benches().stream()
                    .filter(b -> b.round() == r).toList();
            cachedPrice.put(round, band.isEmpty() ? 0
                    : band.stream().mapToDouble(BenchValue.Bench::overWire)
                        .average().orElse(0));
        }
    }

    /**
     * The measured keeper term for a position at a round - what a stash there
     * has historically been worth NEXT season, over the price of the pick it
     * costs to keep him, scaled by how often the drafter is the keeper.
     *
     * This is a base rate, not a per-player estimate: it is the level history
     * sets, and the caller supplies the ranking within it.
     */
    public static double keeperTermFor(AAAConfiguration configuration,
                                       Position position, int round){
        load(configuration);
        int low = round <= 9 ? 8 : round <= 12 ? 10 : 13;
        int high = round <= 9 ? 9 : round <= 12 ? 12 : 16;
        List<BenchValue.Bench> group = cached.benches().stream()
                .filter(b -> b.hasNextSeason() && b.position() == position
                        && b.round() >= low && b.round() <= high)
                .toList();
        if(group.size() < 4){
            return 0.0;
        }
        return group.stream()
                .mapToDouble(b -> keeperSurplus(b, cachedPrice, cachedCapture))
                .average().orElse(0);
    }

    /**
     * How much more (or less) a young stash's keeper term has been worth than
     * the average one, measured across all of rounds 8-16 rather than inside a
     * position and band, where the counts collapse.
     *
     * Without this the keeper term is a flat position constant, which credits
     * a 30-year-old journeyman quarterback with exactly what it credits a
     * second-year starter. That is plainly wrong and the fix is measurable.
     */
    public static double youthMultiplier(AAAConfiguration configuration, boolean young){
        load(configuration);
        List<BenchValue.Bench> scored = cached.benches().stream()
                .filter(BenchValue.Bench::hasNextSeason).toList();
        double overall = scored.stream()
                .mapToDouble(b -> keeperSurplus(b, cachedPrice, cachedCapture))
                .average().orElse(0);
        List<BenchValue.Bench> group = scored.stream()
                .filter(b -> b.young() == young).toList();
        if(group.size() < 10 || overall <= 0){
            return 1.0;
        }
        return group.stream()
                .mapToDouble(b -> keeperSurplus(b, cachedPrice, cachedCapture))
                .average().orElse(0) / overall;
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        List<BenchValue.Bench> all = BenchValue.gather(configuration).benches();
        List<BenchValue.Bench> scored = all.stream()
                .filter(BenchValue.Bench::hasNextSeason).toList();

        // A keeper is only worth drafting for if the drafter is the one who
        // keeps him. KeeperOrigin measures how often that holds in this league;
        // the rest of the keeper supply arrives by trade, costing no pick.
        double capture = KeeperOrigin.captureRate(configuration);

        // What a round-R pick is worth in its own draft year - the price of
        // keeping someone at round R, paid in the pick you no longer have.
        Map<Integer, Double> priceOfRound = new HashMap<>();
        for(int round = 8; round <= 16; round++){
            final int r = round;
            List<BenchValue.Bench> band = all.stream()
                    .filter(b -> b.round() == r).toList();
            priceOfRound.put(round, band.isEmpty() ? 0
                    : band.stream().mapToDouble(BenchValue.Bench::overWire)
                        .average().orElse(0));
        }

        System.out.printf("%d picks in rounds 8-16 with a following season to judge"
                + " them by.%nkeeper term scaled by %.2f - the share of this league's"
                + " keepers whose%ndrafter is the one keeping him (KeeperOrigin);"
                + " the rest came by trade.%n%n", scored.size(), capture);
        System.out.printf("what a pick in each round is worth in its own year"
                + " (the keeper price):%n   ");
        for(int round = 8; round <= 16; round++){
            System.out.printf("r%d %.0f   ", round, priceOfRound.get(round));
        }

        System.out.printf("%n%n%-4s %-8s %5s %11s %12s %11s %8s%n", "POS", "ROUNDS", "n",
                "this season", "keeper next", "TOTAL", "+/-2se");
        int[][] bands = {{8, 9}, {10, 12}, {13, 16}};
        for(Position position : new Position[]{Position.QB, Position.RB,
                Position.WR, Position.TE}){
            for(int[] band : bands){
                List<BenchValue.Bench> group = scored.stream()
                        .filter(b -> b.position() == position
                                && b.round() >= band[0] && b.round() <= band[1])
                        .toList();
                if(group.size() < 4){
                    continue;
                }
                double thisSeason = group.stream()
                        .mapToDouble(BenchValue.Bench::overWire).average().orElse(0);
                double keeper = group.stream()
                        .mapToDouble(b -> keeperSurplus(b, priceOfRound, capture))
                        .average().orElse(0);
                System.out.printf("%-4s %-8s %5d %11.1f %12.1f %11.1f %8.1f%n", position,
                        band[0] + "-" + band[1], group.size(), thisSeason, keeper,
                        thisSeason + keeper, twoStandardErrors(group, priceOfRound,
                        capture));
            }
        }

        System.out.println("\nthe fifteen best picks this league ever made in rounds"
                + " 8-16, on this model:");
        System.out.printf("   %-24s %-3s %-5s %-6s %9s %9s %8s%n", "PLAYER", "POS",
                "ROUND", "SEASON", "this yr", "keeper", "TOTAL");
        scored.stream()
                .sorted(Comparator.comparingDouble(
                        (BenchValue.Bench b) -> b.overWire()
                                + keeperSurplus(b, priceOfRound, capture)).reversed())
                .limit(15)
                .forEach(b -> System.out.printf("   %-24s %-3s r%-4d %-6s %9.1f %9.1f"
                        + " %8.1f%n", b.name(), b.position(), b.round(), b.season(),
                        b.overWire(), keeperSurplus(b, priceOfRound, capture),
                        b.overWire() + keeperSurplus(b, priceOfRound, capture)));

        double thisTotal = scored.stream()
                .mapToDouble(BenchValue.Bench::overWire).average().orElse(0);
        double keeperTotal = scored.stream()
                .mapToDouble(b -> keeperSurplus(b, priceOfRound, capture)).average().orElse(0);
        System.out.printf("%naveraged over every pick in the range, this season is worth"
                + " %.1f and the%nkeeper option %.1f - so %.0f%% of a rounds 8-16 pick's"
                + " value is next year.%nMost of it is still THIS season: the keeper term"
                + " is real but secondary,%nand it is worth more to this seat than to"
                + " others only because this seat's%nkeeper pair is the league's"
                + " weakest.%n", thisTotal, keeperTotal,
                100 * keeperTotal / Math.max(0.01, thisTotal + keeperTotal));
        System.out.println("\nraw points flatter QB here - this league pays 6 per passing"
                + " touchdown, so a\nquarterback outscores a receiver before any skill is"
                + " involved. and a second\nQB cannot start behind Purdy. read the QB rows"
                + " as a keeper argument, not a\nlineup one.");
    }

    /**
     * Two standard errors on the total. Five seasons of one league leaves some
     * cells at n=7, and a gap smaller than these bars is not a gap - the same
     * trap BenchValue's position table set earlier today.
     */
    static double twoStandardErrors(List<BenchValue.Bench> group,
                                    Map<Integer, Double> priceOfRound, double capture){
        int n = group.size();
        if(n < 2){
            return 0.0;
        }
        double[] totals = new double[n];
        for(int i = 0; i < n; i++){
            totals[i] = group.get(i).overWire()
                    + keeperSurplus(group.get(i), priceOfRound, capture);
        }
        double mean = java.util.Arrays.stream(totals).average().orElse(0);
        double variance = 0;
        for(double total : totals){
            variance += (total - mean) * (total - mean);
        }
        variance /= n - 1;
        return 2.0 * Math.sqrt(variance / n);
    }

    /**
     * What keeping him next year is worth over simply using that round's pick.
     * Floored at zero: nobody is forced to keep a player who is not worth his
     * round, so the downside of a stash that busts is not negative, only nil.
     */
    static double keeperSurplus(BenchValue.Bench bench, Map<Integer, Double> priceOfRound){
        return keeperSurplus(bench, priceOfRound, 1.0);
    }

    static double keeperSurplus(BenchValue.Bench bench, Map<Integer, Double> priceOfRound,
                                double capture){
        return capture * Math.max(0.0, bench.nextOverWire()
                - priceOfRound.getOrDefault(bench.round(), 0.0));
    }
}
