import java.util.Map;
import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * One screen for draft night. Both engines, warmed once, the right pair per round.
 *
 * Justin asked whether the text will show both models for the first seven
 * rounds and this one afterwards. It did not - DraftNight, LiveLateRounds and
 * LiveBoard were three commands in three terminals, each warming its own engine
 * for thirty to forty seconds. With a sixty second clock that is a bad way to
 * spend the night.
 *
 * So this warms ONCE and drives both:
 *
 *   rounds 1-7    Model A, which is proven in those rounds and has never been
 *                 beaten there, AND the board model, so the two opinions sit
 *                 side by side and a disagreement is visible rather than
 *                 discovered in another window.
 *   rounds 8-16   the board model alone. Model A's objective is the best legal
 *                 NINE, and two keepers plus seven picks fills it, so from
 *                 round 8 it is indifferent and prints whatever - which is not
 *                 a bug and is exactly why it must not be read there.
 *
 * It calls DraftNight.answer and the board model's own routine unchanged.
 * Neither frozen file is touched: if this misbehaves, both original commands
 * still work exactly as verified.
 *
 *   ./gradlew run -Pmain=Draft2026 -Pkeepers=Tuten,Purdy -q
 */
public class Draft2026 {

    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", System.getProperty("scheduleRounds", "16"));
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String draftID = System.getProperty("draftId", configuration.getDraftID());
        // 150, which is DraftNight's own default. I had this at 300 and the
        // cycle took 26 seconds against a sixty second clock - twice the work
        // for a tool whose whole point is that it answers before the pick
        // expires. Matching the proven setting rather than inventing one.
        int rollouts = Integer.getInteger("trials", 150);
        int scenarios = Integer.getInteger("scenarios", 60);
        int waitRollouts = Integer.getInteger("waitTrials", 200);

        System.out.printf("%nwarming both engines - paid ONCE%n");
        long warm = System.nanoTime();

        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        DraftSimulator simulator = planner.simulator();
        TimingPlanner timing = new TimingPlanner(planner);
        timing.fillWaitingTable(200);
        Map<String, Double> points = planner.points();
        Map<Position, Double> benchBaseRate = BenchValue.overWireByPosition(configuration);

        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Set<String> kept = LiveBoard.kept(configuration);
        Map<Position, double[]> curve = LiveBoard.thisYear(planner, kept);
        double survivalCost = LiveBoard.warmSurvival(planner, simulator);
        Map<Position, List<List<Double>>> pools =
                new EnumMap<>(BoardValue.pools(men, curve));
        List<List<Double>> defence = LiveBoard.defenceScatter();
        if(!defence.isEmpty()){
            pools.put(Position.DEF, defence);
        }

        System.out.printf("warm in %.0fs. %d men kept league-wide.%n",
                (System.nanoTime() - warm) / 1e9, kept.size());
        if(LiveBoard.SURVIVAL != null){
            System.out.printf("survival table: %.0fs of that, paid once. It replaces the"
                    + "%nhard ADP cutoff in the rollout - measured 2.69 men of error per"
                    + "%nposition-seat down to 0.08. -PsurvivalDraws=0 restores the old"
                    + " rule.%n", survivalCost);
        }
        System.out.println("press enter each time it is your turn; q to quit");

        java.io.BufferedReader keyboard = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in));
        while(true){
            System.out.printf("%n[enter] my pick  |  q quit > ");
            System.out.flush();
            String line = keyboard.readLine();
            if(line == null || line.trim().equalsIgnoreCase("q")){
                System.out.println("done - good luck.");
                return;
            }
            long began = System.nanoTime();
            // ONE BAD CYCLE MUST NOT END THE NIGHT.
            //
            // Everything below is inside a try/finally now. Three calls in this
            // block are declared `throws Exception` - the freeze itself, which
            // reads Sleeper over the network; roundNow; and LiveBoard.answer -
            // and only DraftNight.answer was ever guarded. So a refused HTTP
            // read, or any board the fast engine had not anticipated, walked
            // out of main and ENDED THE PROCESS, taking the warm engine with
            // it: both models, sixteen seasons of nflverse, the scatter pools
            // and now the survival table, all of which are paid once and would
            // have to be paid again against a sixty-second clock.
            //
            // The asymmetry was the fault. The engine Justin reads first, and
            // the only one that speaks after round 7, was the one that could
            // kill the session; the second opinion could not. DraftNight's own
            // loop has had this guard all along.
            //
            // The thaw moves into the finally for the same reason: a snapshot
            // that outlives its cycle is a board frozen for the rest of the
            // draft, and it looks completely normal on screen.
            try {
                // One snapshot for the whole cycle. Without this the header, the
                // board model and Model A each fetch their own picks list, so a
                // manager picking during the sixteen seconds Model A takes leaves
                // the two halves of the screen answering about different boards.
                LiveDraft.freeze(draftID);
                int round = roundNow(simulator, planner, draftID);
                System.out.printf("%n================ ROUND %d ================%n", round);
                // THE FAST ANSWER FIRST. The board model takes half a second and
                // Model A takes sixteen, so printing them in that order means an
                // answer is on screen almost immediately and the second opinion
                // arrives while it is being read - rather than sixteen seconds of
                // nothing against a sixty second clock.
                System.out.printf("%n--- THE BOARD MODEL (knows the 24 keepers) ---%n");
                LiveBoard.answer(configuration, planner, simulator, draftID, curve, pools,
                        order, men, kept);
                System.out.printf("%n(board model in %.1fs - Model A follows)%n",
                        (System.nanoTime() - began) / 1e9);
                if(round <= 7){
                    System.out.printf("%n--- MODEL A (proven rounds 1-7, second opinion) ---%n");
                    try {
                        DraftNight.answer(configuration, planner, timing, simulator, points,
                                benchBaseRate, draftID, rollouts, scenarios, waitRollouts);
                    }
                    catch(Exception failed){
                        System.out.printf("   Model A failed this cycle: %s%n"
                                + "   the board model above stands on its own%n", failed);
                    }
                }
                else {
                    // SAY THE TRUE REASON. This used to assert "its starting nine
                    // is full", which is false on the RUNBOOK's own recommended
                    // shape - tight end deferred to round 8 leaves the nine short,
                    // and the audit measured Model A's spread there at 3.79,
                    // LARGER than round 7's 2.75. The gate itself is right: Justin
                    // set it, Model A is only proven in rounds 1-7. But telling
                    // him his nine is full when he still needs a tight end could
                    // cost him the tight end.
                    Map<Position, Integer> missing =
                            LiveBoard.stillNeeds(planner, simulator, draftID);
                    if(missing.isEmpty()){
                        System.out.printf("%n(Model A is silent from round 8 - its starting"
                                + " nine IS full, so it%nis indifferent here and must not be"
                                + " read.)%n");
                    }
                    else {
                        System.out.printf("%n(Model A is silent from round 8 - it is only"
                                + " proven in rounds 1-7.%nNote your starting nine is NOT yet"
                                + " full: still needs %s.%nThe board model above knows that"
                                + " and is pricing it.)%n", missing);
                    }
                }
                System.out.printf("%n(both answered in %.1fs, both against the same"
                                + " %d-pick board)%n",
                        (System.nanoTime() - began) / 1e9, LiveDraft.frozenSize());
            }
            catch(Exception failed){
                // Loud, named, and survivable. The engines stay warm, so the
                // next press of enter is another half-second answer rather
                // than another forty seconds of warm-up.
                System.out.printf("%n   *** THIS CYCLE FAILED: %s%n"
                        + "   *** the board may have moved mid-read, or Sleeper"
                        + " refused the read.%n"
                        + "   *** Both engines are still warm - press enter to"
                        + " try again.%n", failed);
            }
            finally {
                LiveDraft.thaw();
            }
        }
    }

    /** Which round my next pick falls in. */
    static int roundNow(DraftSimulator simulator, DraftPlanner planner, String draftID)
            throws Exception {
        List<String> taken = LiveDraft.livePicks(draftID);
        DraftSimulator.SimState state = simulator.stateAfter(taken);
        DraftSimulator.Slot on = simulator.slotOf(state);
        int from = on == null ? 200 : on.pickNumber();
        for(int p = from; p <= 200; p++){
            DraftSimulator.Slot mine = simulator.slotAt(p);
            // A KEEPER SLOT IS NOT A PICK. Rounds 12 and 13 are Justin's and
            // select nobody - Tuten sits in pick 138, Purdy in 151. Without
            // this the header read ROUND 12 for two rounds while LiveBoard
            // priced pick 138 and answered "nothing legal", across exactly the
            // stretch where the tight end and defence still have to be found.
            if(mine != null && planner.me().equals(mine.manager())
                    && !mine.keeperSlot()){
                return mine.round();
            }
        }
        return 16;
    }
}
