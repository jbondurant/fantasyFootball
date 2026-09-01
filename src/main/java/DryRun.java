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
        System.setProperty("scheduleRounds", System.getProperty("scheduleRounds", "16"));
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        DraftSimulator simulator = planner.simulator();

        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Set<String> kept = LiveBoard.kept(configuration);
        Map<Position, double[]> curve = LiveBoard.thisYear(planner, kept);
        Map<Position, List<List<Double>>> pools = BoardValue.pools(men, curve);

        Map<Position, List<String>> ordered = new EnumMap<>(Position.class);
        for(String id : planner.points().keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null && !kept.contains(id)
                    && PairwiseOdds.CAP.containsKey(player.position)){
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

        System.out.printf("%-6s %-6s %-26s %-4s %s%n",
                "ROUND", "PICK", "THE MODEL TAKES", "POS", "check");
        while(simulator.slotOf(state) != null){
            DraftSimulator.Slot slot = simulator.slotOf(state);
            if(!planner.me().equals(slot.manager())){
                simulator.simulateOneFrom(state, random);
                continue;
            }
            int round = slot.round();
            Position best = null;
            double most = -1e9;
            String bestId = null;
            for(Position position : new Position[]{Position.RB, Position.WR,
                    Position.TE, Position.QB, Position.DEF}){
                if(!legal.canDraft(position, round)){
                    continue;
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
                List<BoardValue.Slot> after = new ArrayList<>(held);
                after.add(new BoardValue.Slot(position, rank));
                double value = BoardValue.empirical(after, pools, curve, order.size(), true);
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
            held.add(new BoardValue.Slot(best,
                    held.size()));
            have.merge(best, 1, Integer::sum);
            legal = legal.canDraft(best, round) ? legal.draft(name, best, round) : legal;
            simulator.simulateOneFrom(state, random);
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
