import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * Does it matter that scatter is indexed by DRAFTABLE rank but learned from
 * FULL-BOARD rank?
 *
 * Listed as a known fault in DRAFT-READY.md. Historical "RB4" was genuinely the
 * fourth-best back that season. Justin's draftable RB4 is James Cook, who is
 * really RB6 overall once Taylor and Achane are kept - so the model attaches
 * RB4's historical volatility to an RB6-quality man. The LEVEL already comes
 * from 2026 projections, which is right; only the SCATTER index is in question.
 *
 * Whether that is worth fixing depends on how fast scatter changes with rank,
 * which nobody has looked at. If the ratio distribution at rank 4 and rank 10
 * are much the same, a two-or-three rank offset is noise and the fault can be
 * closed rather than carried. Read-only: this measures, it does not change the
 * model.
 *
 *   ./gradlew run -Pmain=RankIndexCheck -q
 */
public class RankIndexCheck {

    public static void main(String[] args){
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Map<Position, double[]> curve = RankDraft.pointsByRank(men);
        Map<Position, List<List<Double>>> pools = BoardValue.pools(men, curve);

        System.out.printf("%nHOW FAST DOES SCATTER CHANGE WITH RANK?%n%n");
        System.out.printf("the spread of the ratio pool at each rank. if these barely move%n"
                + "between neighbouring ranks, indexing scatter two or three ranks off is%n"
                + "not worth fixing.%n%n");
        System.out.printf("%-5s %6s %8s %8s %8s %8s%n",
                "POS", "RANK", "MEAN", "SD", "P10", "P90");
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE}){
            List<List<Double>> byRank = pools.get(position);
            if(byRank == null){
                continue;
            }
            for(int rank : new int[]{2, 4, 6, 8, 12, 20, 30}){
                if(rank >= byRank.size() || byRank.get(rank).size() < 20){
                    continue;
                }
                List<Double> pool = new ArrayList<>(byRank.get(rank));
                Collections.sort(pool);
                double mean = pool.stream().mapToDouble(Double::doubleValue)
                        .average().orElse(0);
                double variance = pool.stream()
                        .mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
                System.out.printf("%-5s %6d %8.2f %8.2f %8.2f %8.2f%n", position, rank,
                        mean, Math.sqrt(variance),
                        pool.get(pool.size() / 10), pool.get(pool.size() * 9 / 10));
            }
            System.out.println();
        }
        System.out.printf("A ratio pool is what men of that rank RETURNED divided by what%n"
                + "the rank was centrally worth, so mean near 1.00 is expected and the SD%n"
                + "is the thing to read. Compare rank 4 against rank 6: that gap is the%n"
                + "size of the error this fault actually causes.%n");
    }
}
