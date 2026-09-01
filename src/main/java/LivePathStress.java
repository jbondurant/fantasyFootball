import PlayerImportAndSetup.Position;
import java.io.*;
import java.util.*;

/**
 * DOES THE LIVE PATH SURVIVE A WHOLE DRAFT?
 *
 * Justin has sixty seconds a pick and no way to debug a stack trace at the
 * table. This replays a full sixteen-round draft, driven by the fitted choice
 * model, and calls the REAL LiveBoard.answer at every one of his fourteen
 * seats - the printed path, not a reimplementation of it. Any throw is a
 * failure; so is an illegal roster at the end.
 *
 * The second adversarial pass did this against the pre-fix tree in a worktree.
 * A great deal has changed since, including the roster attribution, the held
 * man ranks, the tail's legality and the arbiter.
 *
 *   ./gradlew run -Pmain=LivePathStress -Pkeepers=Tuten,Purdy -q
 */
public class LivePathStress {
    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", "16");
        int seeds = Integer.getInteger("seeds", 3);
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
        Map<Position, List<List<Double>>> pools =
                new EnumMap<>(BoardValue.pools(men, curve));
        List<List<Double>> defence = LiveBoard.defenceScatter();
        if(!defence.isEmpty()){
            pools.put(Position.DEF, defence);
        }
        String draftID = configuration.getDraftID();

        PrintStream real = System.out;
        PrintStream quiet = new PrintStream(OutputStream.nullOutputStream());
        int priced = 0;
        int failures = 0;

        for(int seed = 0; seed < seeds; seed++){
            Random random = new Random(4242L + seed);
            List<String> taken = new ArrayList<>();
            DraftSimulator.SimState state = simulator.initialState();
            List<Position> myShape = new ArrayList<>();
            while(true){
                DraftSimulator.Slot slot = simulator.slotOf(state);
                if(slot == null){
                    break;
                }
                boolean mine = planner.me().equals(slot.manager());
                Position advised = null;
                if(mine){
                    LiveDraft.freezeWith(taken);
                    try {
                        System.setOut(quiet);
                        advised = LiveBoard.answer(configuration, planner, simulator,
                                draftID, curve, pools, order, men, kept);
                        System.setOut(real);
                        priced++;
                    }
                    catch(Exception broke){
                        System.setOut(real);
                        failures++;
                        real.printf("seed %d, pick %d: THREW %s at %s%n", seed,
                                slot.pickNumber(), broke,
                                broke.getStackTrace().length == 0 ? "?"
                                        : broke.getStackTrace()[0]);
                    }
                    finally {
                        LiveDraft.thaw();
                    }
                }
                // At HIS seats, take what the tool actually advised - otherwise
                // this harness tests a pick rule of its own invention rather
                // than the tool. Everyone else takes the best man left.
                String pick = null;
                if(mine && advised != null){
                    pick = LiveBoard.bestAvailable(planner, taken, advised);
                }
                if(pick == null){
                    // Opponents reach for one of the best few rather than always
                    // the very best. A deterministic room makes every seed the
                    // same draft, which explores nothing - the first version of
                    // this printed three identical rosters and looked like
                    // three passes.
                    List<String> live = new ArrayList<>();
                    for(Map.Entry<String, Double> entry : planner.points().entrySet()){
                        String id = entry.getKey();
                        if(state.takenAtOf(id) == null && !taken.contains(id)
                                && !kept.contains(id)){
                            live.add(id);
                        }
                    }
                    live.sort(Comparator.comparingDouble(
                            (String id) -> planner.points().getOrDefault(id, 0.0))
                            .reversed());
                    if(!live.isEmpty()){
                        pick = live.get(random.nextInt(Math.min(6, live.size())));
                    }
                }
                if(pick == null){
                    break;
                }
                if(mine){
                    Player player = Player.getPlayerFromSIDV2(pick);
                    if(player != null){
                        myShape.add(player.position);
                    }
                }
                state = simulator.branchWith(state, pick);
                taken.add(pick);
            }
            Map<Position, Integer> shape = new EnumMap<>(Position.class);
            for(Position position : myShape){
                shape.merge(position, 1, Integer::sum);
            }
            boolean legal = true;
            String why = "";
            try {
                RosterRules.Roster roster = RosterRules.live().justins();
                for(int i = 0; i < myShape.size(); i++){
                    roster = roster.holdAnyway("m" + i, myShape.get(i), i + 1);
                }
                legal = roster.fieldsLegalLineup();
            }
            catch(RuntimeException broke){
                legal = false;
                why = " (" + broke.getMessage() + ")";
            }
            real.printf("seed %d: %d seats, took %s -> %s  legal lineup? %s%s%n",
                    seed, myShape.size(), myShape, shape, legal ? "yes" : "NO", why);
            if(!legal){
                failures++;
            }
        }
        real.printf("%n%d picks priced through the real live path, %d failures.%n",
                priced, failures);
        if(failures > 0){
            throw new IllegalStateException(failures + " throws on the live path");
        }
        real.printf("no throw at any of his seats, across %d full drafts.%n", seeds);
    }
}
