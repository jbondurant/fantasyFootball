import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * Do the value model and the odds model agree about the same fact?
 *
 * Justin asked whether the scoring accounts for "the odds that a position at
 * pick P beats a same position at a lower pick". Two different pieces of this
 * repo claim to know that, by completely different routes:
 *
 *   PairwiseOdds  fits it directly - 65,855 same-position pairs over sixteen
 *                 seasons, isotonic in log rank, held out by season.
 *   BoardValue    never fits it at all. It draws each man from what men of his
 *                 rank really scored and takes the best legal lineup, so the
 *                 odds are implied by the draws rather than stated.
 *
 * If the second does not reproduce the first, one of them is wrong, and every
 * number either has produced is suspect. Nobody had checked.
 *
 *   ./gradlew run -Pmain=OddsConsistency -q
 */
public class OddsConsistency {

    public static void main(String[] args){
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        int[] ties = new int[1];
        List<PairwiseOdds.Pair> pairs = PairwiseOdds.pairs(men, -1, false, ties);
        PairwiseOdds.Model odds = PairwiseOdds.latent(pairs, 0, 0.25);
        Map<Position, double[]> curve = RankDraft.pointsByRank(men);
        Map<Position, List<List<Double>>> pools = BoardValue.pools(men);

        System.out.printf("%nDO THE TWO MODELS AGREE ABOUT THE SAME FACT?%n%n");
        System.out.printf("P(the man at the LATER rank outscores the man at the earlier one)%n");
        System.out.printf("FITTED is PairwiseOdds, which measures it. IMPLIED is BoardValue's%n");
        System.out.printf("draws, which never model it and should reproduce it anyway.%n%n");
        System.out.printf("%-5s %6s %6s %10s %10s %9s%n",
                "POS", "EARLY", "LATE", "FITTED", "IMPLIED", "GAP");

        int[][] cases = {{2, 8}, {2, 20}, {4, 40}, {8, 24}, {12, 36}, {20, 50}, {30, 55}};
        double worst = 0;
        for(Position position : new Position[]{Position.RB, Position.WR,
                Position.TE, Position.QB}){
            Integer cap = PairwiseOdds.CAP.get(position);
            for(int[] pair : cases){
                if(cap == null || pair[1] > cap){
                    continue;
                }
                double fitted = odds.probability(position, pair[0], pair[1]);
                double implied = implied(pools, curve, position, pair[0], pair[1]);
                worst = Math.max(worst, Math.abs(fitted - implied));
                System.out.printf("%-5s %6d %6d %9.0f%% %9.0f%% %+8.1f%%%n", position,
                        pair[0], pair[1], 100 * fitted, 100 * implied,
                        100 * (implied - fitted));
            }
        }
        System.out.printf("%nlargest disagreement anywhere: %.1f points.%n", 100 * worst);
        System.out.printf("%nIMPLIED is computed from the SAME draws BoardValue values a%n"
                + "roster with, in the same worlds and the same order, so this is the%n"
                + "value model's own opinion rather than a re-derivation of it.%n");
    }

    /** How often BoardValue's own draws put the later man ahead. */
    static double implied(Map<Position, List<List<Double>>> pools,
                          Map<Position, double[]> curve, Position position,
                          int early, int late){
        int ahead = 0;
        for(int world = 0; world < BoardValue.WORLDS; world++){
            double a = BoardValue.drawn(pools, position, early, world, curve);
            double b = BoardValue.drawn(pools, position, late, world, curve);
            if(b > a){
                ahead++;
            }
        }
        return (double) ahead / BoardValue.WORLDS;
    }
}
