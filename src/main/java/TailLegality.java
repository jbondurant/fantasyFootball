import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * Does the rollout tail ever finish without a defence?
 *
 * The greedy tail was pure marginal capped by MOST, with no legality
 * constraint, so it could end on a roster that fields no defence - and
 * BoardValue.oneSeason then charges that roster the streaming penalty. That
 * made "take the defence now" look like the only way to ever have one, which
 * is why a defence turned up in round 7 or 8 in five of six audited drafts.
 *
 *   ./gradlew run -Pmain=TailLegality -Pkeepers=Tuten,Purdy -q
 */
public class TailLegality {
    public static void main(String[] args) throws Exception {
                // ONE WARM-UP, SHARED. See LiveSetup: five separate copies of this
        // block had drifted apart, three of them measuring a configuration
        // nobody runs.
        LiveSetup setup = LiveSetup.forTonight();
        AAAConfiguration configuration = setup.configuration;
        DraftPlanner planner = setup.planner;
        DraftSimulator simulator = setup.simulator;
        Set<String> kept = setup.kept;
        Map<Position, double[]> curve = setup.curve;
        Map<Position, List<List<Double>>> pools = setup.pools;
        List<String> order = setup.order;
        List<PairwiseOdds.Man> men = setup.men;

        // Justin's two keepers, the roster every rollout starts from.
        List<BoardValue.Slot> held = new ArrayList<>();
        held.add(new BoardValue.Slot(Position.RB, 24));
        held.add(new BoardValue.Slot(Position.QB, 6));

        System.out.printf("%n%-6s %-46s %s%n", "FIRST", "TAIL FINISHES WITH", "DEF?");
        int missing = 0;
        for(Position first : new Position[]{Position.RB, Position.WR,
                Position.TE, Position.QB, Position.DEF}){
            List<BoardValue.Slot> roster = LiveBoard.rolloutRoster(planner,
                    new ArrayList<>(), curve, pools, 200, held, first, 1, 6);
            Map<Position, Integer> shape = new EnumMap<>(Position.class);
            for(BoardValue.Slot slot : roster){
                shape.merge(slot.position(), 1, Integer::sum);
            }
            boolean hasDef = shape.getOrDefault(Position.DEF, 0) >= 1;
            if(!hasDef){
                missing++;
            }
            System.out.printf("%-6s %-46s %s%n", first, shape,
                    hasDef ? "yes" : "NO  <- illegal, charged the stream penalty");
        }
        System.out.printf("%n%d of 5 tails finish with no defence.%n", missing);
        if(missing > 0){
            throw new IllegalStateException("the rollout tail can imagine an"
                    + " illegal roster, which is what made the defence look"
                    + " urgent in round 7");
        }
    }
}
