import PlayerImportAndSetup.Position;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The draft-night console: one warm engine, many picks.
 *
 * Every live tool in this repo pays the same 17-20 second start-up - fitting
 * the choice model, building the planner, filling the waiting table - and the
 * 2026-08-28 mock measured a full decision cycle at 25-45 seconds against a
 * 60-second clock, with three separate tools each paying it again. That is the
 * difference between using the model and autodrafting.
 *
 * So pay it once. This holds the warm engine open and re-answers on demand:
 * press enter, it re-reads the live board (uncached - the mock caught the Java
 * client reading an eleven-pick-stale CDN copy) and prints the committee vote
 * and the wait-or-take table from the current state.
 *
 * Rounds 8-9 print the measured base rates instead of an engine, because
 * BenchValue found the position choice there sits inside its own error bars -
 * there is nothing for a simulation to resolve.
 *
 *   ./gradlew run -Pmain=DraftNight
 *   ./gradlew run -Pmain=DraftNight -PdraftId=<id> [-Ptrials=150] [-Pscenarios=60]
 */
public class DraftNight {

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 150);
        int scenarios = Integer.getInteger("scenarios", 60);
        int waitRollouts = Integer.getInteger("waitTrials", 200);
        String draftID = System.getProperty("draftId", configuration.getDraftID());

        long warm = System.currentTimeMillis();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        TimingPlanner timing = new TimingPlanner(planner);
        timing.fillWaitingTable(200);
        DraftSimulator simulator = planner.simulator();
        Map<String, Double> points = planner.points();
        Map<Position, Double> benchBaseRate = BenchValue.overWireByPosition(configuration);
        double warmSeconds = (System.currentTimeMillis() - warm) / 1000.0;

        System.out.printf("%n================ DRAFT NIGHT ================%n");
        System.out.printf("engine warm in %.1fs - paid ONCE, not per pick%n", warmSeconds);
        System.out.printf("draft %s, me = %s%n", draftID, planner.me());
        System.out.println("press enter to re-read the board and answer; q to quit");

        BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in));
        int cycle = 0;
        while(true){
            System.out.printf("%n[enter] refresh  |  q quit > ");
            System.out.flush();
            String line = keyboard.readLine();
            if(line == null || line.trim().equalsIgnoreCase("q")){
                System.out.println("done - good luck.");
                return;
            }
            cycle++;
            long t0 = System.currentTimeMillis();
            try {
                answer(configuration, planner, timing, simulator, points, benchBaseRate,
                        draftID, rollouts, scenarios, waitRollouts);
            }
            catch(Exception failed){
                // never leave the human staring at a stack trace on the clock
                System.out.printf("%n   THIS CYCLE FAILED: %s%n   the board may have"
                        + " moved mid-read; press enter to try again.%n",
                        failed.getMessage());
            }
            System.out.printf("%n--- cycle %d took %.1fs (warm-up %.1fs already paid) ---%n",
                    cycle, (System.currentTimeMillis() - t0) / 1000.0, warmSeconds);
        }
    }

    static void answer(AAAConfiguration configuration, DraftPlanner planner,
                       TimingPlanner timing, DraftSimulator simulator,
                       Map<String, Double> points, Map<Position, Double> benchBaseRate,
                       String draftID, int rollouts, int scenarios,
                       int waitRollouts) throws Exception {
        List<String> taken = LiveDraft.livePicks(draftID);
        DraftSimulator.SimState state = simulator.stateAfter(taken);
        DraftSimulator.Slot slot = simulator.slotOf(state);
        String drift = scheduleDrift(simulator, state, taken.size());
        if(drift != null){
            System.out.print(drift);
        }
        if(slot == null){
            lateRounds(benchBaseRate);
            return;
        }
        List<String> roster = new ArrayList<>(planner.myKeeperIDs());
        for(String id : taken){
            Integer at = state.takenAtOf(id);
            if(at != null && simulator.slotAt(at) != null
                    && planner.me().equals(simulator.slotAt(at).manager())){
                roster.add(id);
            }
        }
        System.out.printf("%npick %d (round %d) belongs to %s | %d gone, my roster %d deep%n",
                slot.pickNumber(), slot.round(), slot.manager(), taken.size(),
                roster.size());
        if(!planner.me().equals(slot.manager())){
            System.out.println("   (not my pick - this is the read for when it is)");
        }

        LiveCommittee.vote(timing, planner, simulator, state, roster, rollouts, scenarios);
        if(slot.round() >= 8){
            // WaitCheck asks "will he last until my next pick". Once the nine is
            // full there is no next pick inside the game, so every position comes
            // back 100% SURVIVES / 0.0 COST / FREE - a table that looks like an
            // answer and contains nothing. Do not print it.
            benchGuidance(benchBaseRate);
        }
        else {
            WaitCheck.report(timing, planner, simulator, state, points, waitRollouts);
        }
    }

    /**
     * IS THE SIMULATOR STILL IN STEP WITH THE REAL DRAFT?
     *
     * `DraftSimulator.stateAfter` advances its schedule only for a man on our
     * board - the increment sits inside the `board.contains` guard. That is
     * right for a keeper, whose keeper slot the loop above has already skipped.
     * It is wrong for a real pick of a man the board does not carry: a kicker,
     * someone past the ADP cut, an id we do not know. He spends a live pick, the
     * schedule does not move, and every later pick is priced one seat early -
     * and so is every attribution of a player to a manager, which is how
     * DraftNight builds MY ROSTER.
     *
     * LiveBoard detects this; Model A shared the same increment and said
     * nothing. Returns the warning to print, or null when the board is in step.
     *
     * THE COMPARISON IS AGAINST LIVE SLOTS, NOT PICK NUMBERS. LiveBoard's
     * version of this check reads `slot.pickNumber() != taken.size() + 1`, and
     * those are only the same question while no keeper slot has gone by. A
     * keeper slot IS a pick number and consumes no pick, so the moment the
     * draft passes one the two quantities part company permanently - and this
     * league has twenty-four of them, the earliest of them in the opening
     * rounds. Written that way the detector fires on a perfectly clean board
     * and tells Justin to distrust a tool that is working. Pinned by
     * ScheduleDriftTest.aKeeperSlotIsNotDrift, whose own first failure was
     * against exactly that formula.
     *
     * @param picksIn how many LIVE picks are really in - LiveDraft.livePicks
     *                already drops the keepers.
     */
    static String scheduleDrift(DraftSimulator simulator, DraftSimulator.SimState state,
                                int picksIn){
        DraftSimulator.Slot slot = simulator.slotOf(state);
        if(slot == null){
            return null;
        }
        int liveSlotsBefore = 0;
        for(int pick = 1; pick < slot.pickNumber(); pick++){
            DraftSimulator.Slot before = simulator.slotAt(pick);
            if(before != null && !before.keeperSlot()){
                liveSlotsBefore++;
            }
        }
        if(liveSlotsBefore == picksIn){
            return null;
        }
        return String.format("%n   *** SCHEDULE DRIFT: %d picks are in, but the simulator is"
                + " on its%n   *** slot number %d (pick %d), which has %d live picks before"
                + " it.%n   *** Somebody drafted a man this board does not carry. My roster"
                + "%n   *** and every number below may be attributed to the wrong seat.%n",
                picksIn, liveSlotsBefore + 1, slot.pickNumber(), liveSlotsBefore);
    }

    /**
     * Rounds 8-9. BenchValue measured 111 of this league's own picks here and
     * the position means overlap inside two standard errors, so no engine is
     * run - printing a ranking would be printing noise.
     */
    static void benchGuidance(Map<Position, Double> baseRate){
        System.out.printf("%n   THE STARTING NINE IS FULL - measured base rates, not an"
                + " engine:%n");
        Map<Position, Double> ordered = new EnumMap<>(baseRate);
        ordered.entrySet().stream()
                .sorted(Map.Entry.<Position, Double>comparingByValue().reversed())
                .forEach(entry -> System.out.printf("     %-3s %6.1f pts over the wire,"
                        + " historically%n", entry.getKey(), entry.getValue()));
        System.out.println("   these overlap inside their own error bars, so position does"
                + " not\n   decide this pick. StarterRisk says my nine are 0.90x as"
                + " fragile as\n   average, which shrinks bench cover further. take the"
                + " highest upside;\n   a bust costs only the roster spot, because the"
                + " wire replaces him.");
    }

    static void lateRounds(Map<Position, Double> baseRate){
        System.out.println("\n   The nine-round game is over - rounds 10-16 are keeper"
                + " stashes now.\n   Run LiveLateRounds for live survival odds:"
                + "\n     ./gradlew run -Pmain=LiveLateRounds -PdraftId=<id>"
                + "\n   (NOT LateRoundTargets - it never sets scheduleRounds=16, so"
                + " its\n   survival race stops at pick 108 and my last three picks"
                + " are 162,\n   175 and 186. LiveLateRounds sets it.)"
                + "\n   LateRoundValue's base rate: QB stashes hit 41% of the time,"
                + " rookies\n   and young players 23-24%, veterans 15%. My keeper pair"
                + " is the weakest\n   in the league, so next year's pair is being"
                + " drafted right now.");
    }
}
