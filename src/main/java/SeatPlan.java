import PlayerImportAndSetup.Position;
import java.io.*;
import java.util.*;

/**
 * WHAT THE MODEL EXPECTS AT EACH OF MY FOURTEEN SEATS, before the draft starts.
 *
 * A plan to eyeball beforehand, not a rule to follow: the board moves and the
 * live tool re-prices every pick. What it is good for is seeing the SHAPE of
 * the draft in advance - and in particular the gaps.
 *
 * Justin's keepers sit in rounds 12 and 13, which select nobody. So between
 * pick 127 and pick 162 there are THIRTY-FIVE picks with no turn of his - by
 * far the longest wait in his draft, three times any other. Whatever he wants
 * from rounds 12-14 has to be taken at 127, and this table is where that is
 * visible.
 *
 *   ./gradlew run -Pmain=SeatPlan -Pkeepers=Tuten,Purdy -q
 */
public class SeatPlan {
    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", "16");
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        DraftSimulator simulator = planner.simulator();
        LiveBoard.warmSurvival(planner, simulator);
        Set<String> kept = LiveBoard.kept(configuration);
        Map<Position, double[]> curve = LiveBoard.thisYear(planner, kept);
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Map<Position, List<List<Double>>> pools =
                new EnumMap<>(BoardValue.pools(men, curve));
        List<List<Double>> defence = LiveBoard.defenceScatter();
        if(!defence.isEmpty()){
            pools.put(Position.DEF, defence);
        }
        String draftID = configuration.getDraftID();

        List<Integer> seats = new ArrayList<>();
        for(int p = 1; p <= 200; p++){
            DraftSimulator.Slot slot = simulator.slotAt(p);
            if(slot != null && planner.me().equals(slot.manager()) && !slot.keeperSlot()){
                seats.add(p);
            }
        }

        PrintStream real = System.out;
        PrintStream quiet = new PrintStream(OutputStream.nullOutputStream());

        System.out.printf("%nMY FOURTEEN SEATS - the model's expectation on today's board.%n"
                + "It re-prices every pick live; this is the shape, not the orders.%n%n");
        real.printf("%-6s %-6s %-6s %-7s %-5s %-26s%n",
                "PICK", "ROUND", "NEXT", "GAP", "TAKE", "LIKELIEST MAN");

        // WALK THE SCHEDULE SLOT BY SLOT, never by counting picks.
        //
        // My first version advanced with `while(taken.size() < pick - 1)`, which
        // silently desynchronises: branchWith skips the twenty-four keeper
        // slots, so the pick count and the schedule index drift apart. It
        // produced a table with two defences and blank rows at picks 103 and
        // 114, and neither was a fact about the model. LivePathStress already
        // walks slots correctly; this does the same.
        List<String> taken = new ArrayList<>();
        DraftSimulator.SimState state = simulator.initialState();
        Random random = new Random(20260901L);
        int seatIndex = 0;
        while(simulator.slotOf(state) != null){
            DraftSimulator.Slot slot = simulator.slotOf(state);
            boolean mine = planner.me().equals(slot.manager());
            Position advised = null;
            if(mine){
                LiveDraft.freezeWith(taken);
                try {
                    System.setOut(quiet);
                    advised = LiveBoard.answer(configuration, planner, simulator,
                            draftID, curve, pools, order, men, kept);
                }
                finally {
                    System.setOut(real);
                    LiveDraft.thaw();
                }
            }
            String pickedID = null;
            if(mine && advised != null){
                pickedID = LiveBoard.bestAvailable(planner, taken, advised);
            }
            if(pickedID == null){
                List<String> live = new ArrayList<>();
                for(Map.Entry<String, Double> entry : planner.points().entrySet()){
                    String id = entry.getKey();
                    if(state.takenAtOf(id) == null && !taken.contains(id)
                            && !kept.contains(id)){
                        live.add(id);
                    }
                }
                if(live.isEmpty()){
                    break;
                }
                live.sort(Comparator.comparingDouble(
                        (String id) -> planner.points().getOrDefault(id, 0.0)).reversed());
                pickedID = live.get(random.nextInt(Math.min(6, live.size())));
            }
            if(mine){
                int pick = slot.pickNumber();
                int next = seatIndex + 1 < seats.size() ? seats.get(seatIndex + 1) : -1;
                Player player = Player.getPlayerFromSIDV2(pickedID);
                String gap = next < 0 ? "(last)" : String.valueOf(next - pick);
                real.printf("%-6d %-6d %-6s %-7s %-5s %-26s%s%n",
                        pick, slot.round(),
                        next < 0 ? "-" : String.valueOf(next), gap,
                        advised == null ? "-" : advised,
                        player == null ? "-" : player.firstName + " " + player.lastName,
                        next > 0 && next - pick > 20 ? "   <- THE LONG WAIT" : "");
                seatIndex++;
            }
            state = simulator.branchWith(state, pickedID);
            taken.add(pickedID);
        }

        // The table must describe a roster he could actually own.
        Map<Position, Integer> shape = new EnumMap<>(Position.class);
        RosterRules.Roster roster = RosterRules.live().justins();
        int round = 1;
        for(String id : taken){
            Integer at = state.takenAtOf(id);
            if(at == null || simulator.slotAt(at) == null
                    || !planner.me().equals(simulator.slotAt(at).manager())){
                continue;
            }
            Player player = Player.getPlayerFromSIDV2(id);
            if(player == null){
                continue;
            }
            shape.merge(player.position, 1, Integer::sum);
            roster = roster.holdAnyway("seat" + round, player.position, round);
            round++;
        }
        for(String id : planner.myKeeperIDs()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null){
                shape.merge(player.position, 1, Integer::sum);
            }
        }
        real.printf("%nfinishes %s, %d men, legal lineup? %s%n", shape,
                roster.size(), roster.fieldsLegalLineup() ? "yes" : "NO");

        real.printf("%nThe keeper slots in rounds 12 and 13 select nobody, so pick 127%n"
                + "is followed by THIRTY-FIVE picks before your next turn. Anything you%n"
                + "want from that stretch has to be taken at 127.%n");
    }
}
