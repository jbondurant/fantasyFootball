import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * DOES THE BENCH BASE RATE DEPEND ON WHICH ROUNDS IT IS MEASURED OVER?
 *
 * `BenchValue.overWireByPosition` collects bench picks from ROUNDS 1-9 and
 * DraftNight.benchGuidance prints the result as guidance for a ROUND 8+ pick.
 * Those are different populations: a man benched after a round-2 pick is a
 * bust, one benched after a round-9 pick is depth, and DRAFT-READY already
 * records the rate falling 44.0 to 32.8 to 31.2 across bands.
 *
 * If the per-position ORDERING changes with the window, the guidance is being
 * drawn from the wrong men. If it does not, the mismatch is harmless and worth
 * saying so.
 *
 *   ./gradlew run -Pmain=BenchWindow -q
 */
public class BenchWindow {
    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int[][] windows = {{1, 9}, {8, 9}, {8, 16}, {10, 16}};
        System.out.printf("%nmean points over the wire, by position, by the rounds the%n"
                + "bench picks were MADE in.%n%n");
        System.out.printf("%-12s", "WINDOW");
        for(Position position : new Position[]{Position.RB, Position.WR,
                Position.TE, Position.QB}){
            System.out.printf(" %8s", position);
        }
        System.out.printf("   n%n");
        for(int[] window : windows){
            Map<Position, List<Double>> collected = new EnumMap<>(Position.class);
            int n = 0;
            for(BenchValue.Bench bench : BenchValue.gather(configuration).benches()){
                if(bench.round() >= window[0] && bench.round() <= window[1]){
                    collected.computeIfAbsent(bench.position(), u -> new ArrayList<>())
                            .add(bench.overWire());
                    n++;
                }
            }
            System.out.printf("rounds %-5s", window[0] + "-" + window[1]);
            for(Position position : new Position[]{Position.RB, Position.WR,
                    Position.TE, Position.QB}){
                List<Double> values = collected.get(position);
                System.out.printf(" %8.1f", values == null ? Double.NaN
                        : values.stream().mapToDouble(Double::doubleValue)
                                .average().orElse(0));
            }
            System.out.printf(" %3d%s%n", n,
                    window[0] == 1 ? "   <- what benchGuidance uses" : "");
        }
        System.out.printf("%nbenchGuidance prints this to advise a ROUND 8+ pick. If the%n"
                + "ordering of the positions changes between the first row and the%n"
                + "others, it is advising from the wrong men.%n");
    }
}
