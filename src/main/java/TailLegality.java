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
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        System.out.printf("%nrequired starters, DERIVED from the lineup: %s%n",
                RosterRules.live().empty().stillNeeds());

        int last = Integer.parseInt(configuration.getSeason()) - 1;
        var earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        Set<String> kept = LiveBoard.kept(configuration);
        Map<Position, double[]> curve = LiveBoard.thisYear(planner, kept);
        // The tail's every future rank comes from LiveBoard.expectedRank, so
        // this measures a different tail depending on whether the survival
        // table is up. It never built one, and the survival table is exactly
        // what moved the late DEF and TE ranks - the two positions the tail is
        // being audited for. Same knob as everywhere else: -PsurvivalDraws=0.
        LiveBoard.warmSurvival(planner, planner.simulator());
        System.out.printf("survival table: %s%n", LiveBoard.SURVIVAL == null
                ? "OFF - measuring the retired ADP cutoff" : "on, as in Draft2026");
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Map<Position, List<List<Double>>> pools =
                new EnumMap<>(BoardValue.pools(men, curve));
        List<List<Double>> defence = LiveBoard.defenceScatter();
        if(!defence.isEmpty()){
            pools.put(Position.DEF, defence);
        }

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
