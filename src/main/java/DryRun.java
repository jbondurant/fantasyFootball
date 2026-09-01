import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * Play the whole 2026 draft with the model picking, and look for nonsense.
 *
 * Every check tonight has been ONE pick in isolation, and one pick at a time
 * is how twenty-four keepers, a stolen flag value and a defence worth 0.0 at
 * the last pick all survived. A model can be right at pick 7 and absurd by
 * round 14; nobody has watched it play a whole draft on the board Justin will
 * actually face.
 *
 * Opponents pick from the fitted model, keepers are off the board, and my
 * picks come from the same rule LiveBoard uses. What matters is not the score
 * - there is no outcome to score against - but whether the SHAPE is sane and
 * every man named is really draftable.
 *
 *   ./gradlew run -Pmain=DryRun -Pkeepers=Tuten,Purdy -q
 */
public class DryRun {

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

        Map<Position, List<String>> ordered = new EnumMap<>(Position.class);
        for(String id : planner.points().keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            // LiveBoard.CAP, which has the defence in it. PairwiseOdds.CAP does
            // not, and using it here meant the defence was never a candidate -
            // so at round 16, where the rules allow ONLY a defence, the search
            // found nothing and the draft ended one pick short with an illegal
            // roster.
            if(player != null && !kept.contains(id)
                    && LiveBoard.CAP.containsKey(player.position)){
                ordered.computeIfAbsent(player.position, u -> new ArrayList<>()).add(id);
            }
        }
        for(List<String> ids : ordered.values()){
            ids.sort(Comparator.comparingDouble(
                    (String id) -> planner.points().getOrDefault(id, 0.0)).reversed());
        }

        System.out.printf("%nA WHOLE DRAFT, MODEL PICKING, 2026 BOARD%n%n");
        System.out.printf("%d men kept league-wide and off the board%n%n", kept.size());

        DraftSimulator.SimState state = simulator.initialState();
        Random random = new Random(20260901L);
        RosterRules rules = RosterRules.live();
        RosterRules.Roster legal = rules.justins();
        List<BoardValue.Slot> held = new ArrayList<>();
        for(String id : planner.myKeeperIDs()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null){
                held.add(new BoardValue.Slot(player.position, 9));
            }
        }
        Map<Position, Integer> have = new EnumMap<>(Position.class);
        List<String> mine = new ArrayList<>();
        List<String> gone = new ArrayList<>();

        System.out.printf("%-6s %-6s %-26s %-4s %s%n",
                "ROUND", "PICK", "THE MODEL TAKES", "POS", "check");
        while(simulator.slotOf(state) != null){
            DraftSimulator.Slot slot = simulator.slotOf(state);
            if(!planner.me().equals(slot.manager())){
                simulator.simulateOneFrom(state, random);
                continue;
            }
            int round = slot.round();
            List<String> goneNow = new ArrayList<>();
            for(String id : planner.points().keySet()){
                if(state.takenAtOf(id) != null){
                    goneNow.add(id);
                }
            }
            Position best = null;
            double most = -1e9;
            String bestId = null;
            for(Position position : new Position[]{Position.RB, Position.WR,
                    Position.TE, Position.QB, Position.DEF}){
                if(!legal.canDraft(position, round)
                        || have.getOrDefault(position, 0) >= BoardValue.MOST.get(position)){
                    continue;   // the same appetite caps the backtested rule uses
                }
                String candidate = null;
                int rank = 0;
                List<String> pool = ordered.getOrDefault(position, List.of());
                for(int r = 1; r <= pool.size(); r++){
                    if(state.takenAtOf(pool.get(r - 1)) == null){
                        candidate = pool.get(r - 1);
                        rank = r;
                        break;
                    }
                }
                if(candidate == null){
                    continue;
                }
                // THE SAME RULE LiveBoard USES, which this file's header
                // claimed and did not do. It ranked on a one-ply marginal -
                // value(held + him) - where LiveBoard rolls the rest of the
                // draft out and values the FINISHED roster, and also applies a
                // fragility filter. Those are different rules and they choose
                // differently: ranking on the one-ply marginal is the greedy
                // urgency rule that scored 1916 and spent round 2 on a tight
                // end. A whole-draft harness that plays a rule nobody drafts
                // with cannot audit the tool, which is the entire point of it.
                // The real taken list, so the rollout plans against the board
                // that exists rather than the one ADP predicted in August.
                double[] both = LiveBoard.rolloutStats(planner, gone, curve, pools,
                        order.size(), held, position, rank, slot.pickNumber());
                double value = BoardValue.tooFragile(both) ? -1e9 : both[0];
                if(value > most){
                    most = value;
                    best = position;
                    bestId = candidate;
                }
            }
            if(best == null){
                break;
            }
            Player player = Player.getPlayerFromSIDV2(bestId);
            String name = player == null ? bestId
                    : player.firstName + " " + player.lastName;
            String check = kept.contains(bestId) ? "!! KEPT - NOT DRAFTABLE"
                    : state.takenAtOf(bestId) != null ? "!! ALREADY GONE" : "";
            System.out.printf("%-6d %-6d %-26s %-4s %s%n",
                    round, slot.pickNumber(), name, best, check);
            mine.add(bestId);
            // HIS RANK, not the roster's size. The first version passed
            // held.size() as the positional rank, which is meaningless and made
            // every later pick value a different man than the one named.
            int taken = 0;
            for(String id : ordered.getOrDefault(best, List.of())){
                if(id.equals(bestId)){
                    break;
                }
                taken++;
            }
            held.add(new BoardValue.Slot(best, taken + 1));
            have.merge(best, 1, Integer::sum);
            legal = legal.canDraft(best, round) ? legal.draft(name, best, round) : legal;
            // TAKE HIM OFF THE BOARD. Without this the simulator never learns I
            // drafted anybody, so the same man is "best available" at every one
            // of my picks - the first run took Jayden Reed three rounds running.
            state = simulator.branchWith(state, bestId);
        }

        System.out.printf("%nFINAL ROSTER%n");
        Map<Position, Integer> counts = new EnumMap<>(Position.class);
        for(String id : mine){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null){
                counts.merge(player.position, 1, Integer::sum);
            }
        }
        counts.merge(Position.RB, 1, Integer::sum);     // Tuten
        counts.merge(Position.QB, 1, Integer::sum);     // Purdy
        System.out.printf("   %s  (%d men, keepers included)%n", counts,
                mine.size() + 2);
        System.out.printf("   legal lineup? %s%n",
                legal.whyNotLegal() == null ? "yes" : "NO - " + legal.whyNotLegal());
    }
}
