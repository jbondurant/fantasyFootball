import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * The adversarial pass over Model A and the DraftNight path.
 *
 * LiveBoard - the other half of Draft2026 - was audited on 2026-08-31 and six
 * faults came out of one night, one of them certain to fire. Model A never had
 * the equivalent: it is the oldest thing here, its plan has been byte-identical
 * for a fortnight, and that stability is exactly why nobody has pointed a gun
 * at it. Two faults have already fallen out of it by accident (the quarterback
 * cap that cannot see a kept man, the keeper-slot scan in Draft2026.roundNow).
 *
 * This drives the live path - DraftSimulator.stateAfter, LiveCommittee.vote,
 * WaitCheck.report - from states a real draft can actually reach, and reports
 * numbers rather than opinions:
 *
 *   1  SETUP           what board and schedule the tool is really carrying
 *   2  ALIGNMENT       does stateAfter stay in step with the real pick count,
 *                      and what does an off-board pick cost
 *   3  BOUNDARY        where Model A's objective actually goes flat, measured
 *                      by its own indifference test, against where Draft2026
 *                      silences it
 *   4  COMMITTEE       can the four engines disagree, and are they four
 *   5  CLOCK           cycle time against picks-in, on a sixty second clock
 *   6  KEEPERS         is a kept man ever nameable, and is a held man priced
 *                      at his own points
 *   7  OFF-TURN        whose board gets NAMED when the clock is not mine
 *
 * Run it at both schedules - they are DIFFERENT MODELS and both are shipped:
 *
 *   ./gradlew run -Pmain=ModelAAudit -Pkeepers=Tuten,Purdy -PscheduleRounds=16 -q
 *   ./gradlew run -Pmain=ModelAAudit -Pkeepers=Tuten,Purdy -PscheduleRounds=9 -q
 *
 * 16 is what Draft2026 forces at startup; 9 is what a bare DraftNight uses.
 */
public class ModelAAudit {

    /** An id no board carries - a kicker, a man past the ADP cut, a typo. */
    static final String OFF_BOARD_ID = "0000000";

    public static void main(String[] args) throws Exception {
        // Draft2026 forces 16. A bare DraftNight leaves the default 9. The
        // audit takes whatever it is given so the two can be compared.
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 150);
        int scenarios = Integer.getInteger("scenarios", 60);
        int waitRollouts = Integer.getInteger("waitTrials", 200);

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
        double warmSeconds = (System.nanoTime() - warm) / 1e9;

        System.out.printf("%n============ MODEL A, ADVERSARIAL ============%n");
        System.out.printf("schedule %d rounds | warm %.1fs | trials %d, scenarios %d,"
                + " waitTrials %d%n", DraftPlanner.scheduleRounds(), warmSeconds,
                rollouts, scenarios, waitRollouts);

        setup(configuration, planner, simulator, points);
        List<String> order = alignment(simulator, planner);
        boundary(planner, simulator, timing, order, rollouts, scenarios);
        committee(planner, simulator, timing, order, rollouts, scenarios);
        clock(planner, simulator, timing, points, order, rollouts, scenarios, waitRollouts);
        keepers(configuration, planner, simulator, timing, order);
        offTurn(planner, simulator, timing, order);
    }

    /**
     * What DraftNight names when the pick on the clock is not Justin's.
     *
     * LiveBoard fixed this and wrote down why: "Always price MY next pick, never
     * whichever pick the draft happens to be on. Before the draft starts slotOf()
     * returns pick 1, which is not Justin's, and the first version cheerfully
     * priced taking a man at a pick he does not own." DraftNight still prices the
     * pick on the clock. Its ROLLOUTS are safe - simulateFrom only hands the
     * policy a slot whose manager is me, so the head position lands at pick 7
     * whatever the state - but the NAME printed beside the verdict, and
     * vorp-greedy's whole vote, come from bestAvailable() on the CURRENT board.
     */
    static void offTurn(DraftPlanner planner, DraftSimulator simulator,
                        TimingPlanner timing, List<String> order){
        System.out.printf("%n--- 7  OFF-TURN READS (whose board is being named?) ---%n");
        System.out.printf("   %-10s %-8s %-28s %-28s%n", "picks in", "on pick", "best RB named",
                "best WR named");
        for(int picksIn : new int[]{0, 3, 6}){
            DraftSimulator.SimState state = simulator.stateAfter(order.subList(0, picksIn));
            DraftSimulator.Slot slot = simulator.slotOf(state);
            Map<Position, String> best = timing.bestAvailable(state.boardView());
            System.out.printf("   %-10d %-8d %-28s %-28s%n", picksIn, slot.pickNumber(),
                    name(best.get(Position.RB)), name(best.get(Position.WR)));
        }
        System.out.printf("   picks-in 6 is Justin's own pick 7. Refreshing before it names"
                + " men%n   who will not be there, though the POSITION the engines vote for"
                + " is%n   still computed at pick 7 - simulateFrom only calls my policy at"
                + " my slots.%n");
    }

    // ---- 1. setup ----

    static void setup(AAAConfiguration configuration, DraftPlanner planner,
                      DraftSimulator simulator, Map<String, Double> points){
        System.out.printf("%n--- 1  SETUP ---%n");
        Set<String> kept = LiveBoard.kept(configuration);
        System.out.printf("   me                %s%n", planner.me());
        System.out.printf("   kept league-wide  %d%n", kept.size());
        System.out.printf("   MY keepers        %d  %s%n", planner.myKeeperIDs().size(),
                planner.myKeeperIDs().stream().map(id -> name(id) + " "
                        + String.format("%.0f", points.getOrDefault(id, 0.0)) + "pts")
                        .toList());
        System.out.printf("   board             %d men%n",
                simulator.initialState().boardView().size());
        int liveSlots = 0;
        int keeperSlots = 0;
        int mineLive = 0;
        int mineKeeper = 0;
        int earliestKeeperSlot = -1;
        for(int pick = 1; pick <= 400; pick++){
            DraftSimulator.Slot slot = simulator.slotAt(pick);
            if(slot == null){
                continue;
            }
            if(slot.keeperSlot()){
                keeperSlots++;
                if(earliestKeeperSlot < 0){
                    earliestKeeperSlot = pick;
                }
                if(planner.me().equals(slot.manager())){
                    mineKeeper++;
                }
            }
            else {
                liveSlots++;
                if(planner.me().equals(slot.manager())){
                    mineLive++;
                }
            }
        }
        System.out.printf("   slots             %d live, %d keeper (earliest keeper slot"
                + " is pick %d)%n", liveSlots, keeperSlots, earliestKeeperSlot);
        System.out.printf("   MY slots          %d live %s, %d keeper%n",
                mineLive, planner.myPicks(), mineKeeper);
    }

    // ---- 2. alignment ----

    /**
     * Replays a whole simulated draft through stateAfter one pick at a time and
     * checks the simulator's idea of "the pick on the clock" against the real
     * pick count - the same check LiveBoard prints as SCHEDULE DRIFT. Then it
     * injects an id the board does not carry and measures what that costs.
     *
     * Returns the pick order, for the later sections to plan from.
     */
    static List<String> alignment(DraftSimulator simulator, DraftPlanner planner){
        System.out.printf("%n--- 2  ALIGNMENT (stateAfter vs the real pick count) ---%n");
        Map<String, Integer> takenAt = simulator.simulateOnce(new Random(DraftSimulator.SEED));
        List<String> order = new ArrayList<>(takenAt.keySet());
        order.sort(Comparator.comparingInt(takenAt::get));

        int drifted = 0;
        int liveBoardWouldWarn = 0;
        int firstFalseAlarm = -1;
        for(int k = 0; k <= order.size(); k++){
            DraftSimulator.SimState state = simulator.stateAfter(order.subList(0, k));
            DraftSimulator.Slot slot = simulator.slotOf(state);
            if(slot == null){
                continue;
            }
            if(slot.pickNumber() != expectedPickAfter(simulator, k)){
                drifted++;
            }
            // LiveBoard.answer's shipped test, verbatim: pick NUMBER against
            // pick COUNT. A keeper slot is a pick number that consumes no pick.
            if(slot.pickNumber() != k + 1){
                liveBoardWouldWarn++;
                if(firstFalseAlarm < 0){
                    firstFalseAlarm = k;
                }
            }
        }
        System.out.printf("   clean replay      %d picks, %d really drifted%n",
                order.size(), drifted);
        System.out.printf("   LiveBoard's shipped drift test on this CLEAN board:%n");
        System.out.printf("      would warn at %d of %d refreshes, first at %s picks in%n",
                liveBoardWouldWarn, order.size() + 1,
                firstFalseAlarm < 0 ? "never" : String.valueOf(firstFalseAlarm));
        System.out.printf("      DraftNight.scheduleDrift would warn at %d%n",
                warnCount(simulator, order));

        // Now the adversarial version: one pick of a man the board does not
        // carry, dropped in at pick 20. LiveDraft.livePicks WILL return him -
        // he is a real pick with a real player_id - and stateAfter's increment
        // sits inside its board.contains guard, so the schedule does not move.
        List<String> polluted = new ArrayList<>(order);
        polluted.add(19, OFF_BOARD_ID);
        int firstBad = -1;
        int worst = 0;
        for(int k = 1; k <= Math.min(polluted.size(), 120); k++){
            DraftSimulator.SimState state = simulator.stateAfter(polluted.subList(0, k));
            DraftSimulator.Slot slot = simulator.slotOf(state);
            if(slot == null){
                continue;
            }
            int expected = expectedPickAfter(simulator, k);
            if(expected == Integer.MAX_VALUE){
                // The drifted schedule has run off the end of the board, so
                // there is no live slot left to compare against. Reporting the
                // sentinel as a "lag" turned a 9-round run's worst case into
                // 2147483539, which is a number about int, not about the draft.
                continue;
            }
            if(slot.pickNumber() != expected){
                if(firstBad < 0){
                    firstBad = k;
                }
                worst = Math.max(worst, expected - slot.pickNumber());
            }
        }
        System.out.printf("   one off-board pick at pick 20:%n");
        System.out.printf("      first wrong slot after %s picks are in, worst lag %d slot(s)%n",
                firstBad < 0 ? "never" : String.valueOf(firstBad), worst);

        // Who does the roster scan think took what, once it has drifted? This
        // is the part that reaches Model A: DraftNight builds MY roster by
        // asking which slot each id landed in.
        DraftSimulator.SimState clean = simulator.stateAfter(order.subList(0, 60));
        DraftSimulator.SimState dirty = simulator.stateAfter(polluted.subList(0, 61));
        System.out.printf("      my roster after 60 real picks: clean %d, after one"
                        + " off-board pick %d%n",
                rosterFrom(order.subList(0, 60), clean, simulator, planner).size(),
                rosterFrom(polluted.subList(0, 61), dirty, simulator, planner).size());
        System.out.printf("      both live tools now print DraftNight.scheduleDrift for"
                + " this;%n      Model A printed nothing at all until 2026-09-01.%n");
        return order;
    }

    /** How often the fixed detector fires on a board that is in step. */
    static int warnCount(DraftSimulator simulator, List<String> order){
        int warned = 0;
        for(int k = 0; k <= order.size(); k++){
            if(DraftNight.scheduleDrift(simulator,
                    simulator.stateAfter(order.subList(0, k)), k) != null){
                warned++;
            }
        }
        return warned;
    }

    /** The pick number of the (k+1)-th LIVE slot - keeper slots select nobody. */
    static int expectedPickAfter(DraftSimulator simulator, int picksIn){
        int seen = 0;
        for(int pick = 1; pick <= 400; pick++){
            DraftSimulator.Slot slot = simulator.slotAt(pick);
            if(slot == null || slot.keeperSlot()){
                continue;
            }
            if(seen == picksIn){
                return pick;
            }
            seen++;
        }
        return Integer.MAX_VALUE;
    }

    /** DraftNight's own roster recipe, verbatim. */
    static List<String> rosterFrom(List<String> taken, DraftSimulator.SimState state,
                                   DraftSimulator simulator, DraftPlanner planner){
        List<String> roster = new ArrayList<>(planner.myKeeperIDs());
        for(String id : taken){
            Integer at = state.takenAtOf(id);
            if(at != null && simulator.slotAt(at) != null
                    && planner.me().equals(simulator.slotAt(at).manager())){
                roster.add(id);
            }
        }
        return roster;
    }

    // ---- 3. the boundary ----

    /**
     * Model A's objective is the best legal NINE. Two keepers plus seven picks
     * fills it, so Draft2026 silences it from round 8. This measures where the
     * objective ACTUALLY goes flat, using the committee's own indifference test
     * - the widest spread any scoring engine shows across positions.
     */
    static void boundary(DraftPlanner planner, DraftSimulator simulator,
                         TimingPlanner timing, List<String> order, int rollouts,
                         int scenarios){
        System.out.printf("%n--- 3  BOUNDARY (where the objective really goes flat) ---%n");
        System.out.printf("   %-5s %-6s %-8s %-9s %-10s %-9s%n",
                "ROUND", "PICK", "roster", "nine full", "spread", "verdict");
        for(int[] stop : myStops(simulator, planner, order)){
            int picksIn = stop[0];
            int pickNumber = stop[1];
            int round = stop[2];
            DraftSimulator.SimState state = simulator.stateAfter(order.subList(0, picksIn));
            List<String> roster = rosterFrom(order.subList(0, picksIn), state, simulator,
                    planner);
            Map<Position, String> best = timing.bestAvailable(state.boardView());
            // Depth 1, which is four rollout sets rather than sixteen. The
            // question here is only whether the objective still DISCRIMINATES,
            // and LiveCommittee's own bench test asks the same thing of the
            // widest spread any scoring engine shows.
            double spread = spread(LiveCommittee.lookahead(timing, planner, simulator, state,
                    roster, best, rollouts, 1));
            System.out.printf("   %-5d %-6d %-8d %-9s %-10.2f %-9s%n", round, pickNumber,
                    roster.size(), nineFull(roster) ? "YES" : "no", spread,
                    spread < 0.05 ? "SILENT" : "speaks");
        }
        System.out.printf("   Draft2026 runs Model A when MY NEXT pick is round <= 7.%n");
        planBoundary(planner, simulator, timing, rollouts,
                List.of(Position.RB, Position.WR, Position.RB, Position.WR, Position.WR,
                        Position.WR, Position.TE, Position.QB, Position.QB),
                "Model A's own plan");
        // The one deviation the RUNBOOK itself recommends. DRAFT-READY prices
        // "TE round 8 (runbook)" against "TE round 7" and calls the nine points
        // between them a tie - so this is not a hypothetical, it is the shape
        // Justin is most likely to actually draft. It is also precisely the
        // shape that leaves a starting slot open at round 8.
        planBoundary(planner, simulator, timing, rollouts,
                List.of(Position.RB, Position.WR, Position.RB, Position.WR, Position.WR,
                        Position.WR, Position.RB, Position.TE, Position.QB),
                "the RUNBOOK front, tight end deferred to round 8");
    }

    /**
     * The same boundary, but with Justin playing a NAMED SHAPE.
     *
     * The table above draws its roster from a draft where Justin is played by
     * the generic manager model, and that is not the world the round-7 claim is
     * made about. The claim is arithmetic: two keepers plus seven picks fills
     * the nine skill slots exactly, so from round 8 there is nothing left to
     * distinguish. It holds only if those seven picks were the plan's - RB WR
     * RB WR WR WR TE, which with Purdy at QB and Tuten at RB covers QB1 RB2 WR3
     * TE1 and both flexes. Any other seven can leave a slot open, and then the
     * objective still has an opinion at round 8 while Draft2026 has already
     * gone silent.
     */
    static void planBoundary(DraftPlanner planner, DraftSimulator simulator,
                             TimingPlanner timing, int rollouts, List<Position> plan,
                             String label){
        System.out.printf("%n   ...and with Justin playing %s,%n   %s:%n", label, plan);
        System.out.printf("   %-5s %-6s %-8s %-9s %-10s %-9s%n",
                "ROUND", "PICK", "roster", "nine full", "spread", "verdict");
        DraftSimulator.SimState state = simulator.initialState();
        Random random = new Random(DraftSimulator.SEED);
        List<String> roster = new ArrayList<>(planner.myKeeperIDs());
        int index = 0;
        while(true){
            DraftSimulator.Slot slot = simulator.slotOf(state);
            if(slot == null){
                break;
            }
            if(!planner.me().equals(slot.manager())){
                simulator.simulateOneFrom(state, random);
                continue;
            }
            Map<Position, String> best = timing.bestAvailable(state.boardView());
            double spread = spread(LiveCommittee.lookahead(timing, planner, simulator, state,
                    roster, best, rollouts, 1));
            System.out.printf("   %-5d %-6d %-8d %-9s %-10.2f %-9s%n", slot.round(),
                    slot.pickNumber(), roster.size(), nineFull(roster) ? "YES" : "no",
                    spread, spread < 0.05 ? "SILENT" : "speaks");
            String chosen = index < plan.size() ? best.get(plan.get(index)) : null;
            if(chosen == null){
                chosen = best.get(timing.vorpPosition(roster, best, slot.pickNumber()));
            }
            if(chosen == null){
                break;
            }
            state = simulator.branchWith(state, chosen);
            roster.add(chosen);
            index++;
        }
    }

    /** True when the roster already fields all nine skill starters. */
    static boolean nineFull(List<String> roster){
        int qb = 0;
        int rb = 0;
        int wr = 0;
        int te = 0;
        for(String id : roster){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player == null){
                continue;
            }
            switch(player.position){
                case QB -> qb++;
                case RB -> rb++;
                case WR -> wr++;
                case TE -> te++;
                default -> { }
            }
        }
        int flex = Math.max(0, rb - 2) + Math.max(0, wr - 3) + Math.max(0, te - 1);
        return qb >= 1 && rb >= 2 && wr >= 3 && te >= 1 && flex >= 2;
    }

    static double spread(Map<Position, Double> values){
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for(Double value : values.values()){
            if(value != null){
                low = Math.min(low, value);
                high = Math.max(high, value);
            }
        }
        return high - low < 0 ? 0 : high - low;
    }

    /** Each of my live picks: {picks already in, my pick number, round}. */
    static List<int[]> myStops(DraftSimulator simulator, DraftPlanner planner,
                               List<String> order){
        List<int[]> stops = new ArrayList<>();
        int picksIn = 0;
        for(int pick = 1; pick <= 400; pick++){
            DraftSimulator.Slot slot = simulator.slotAt(pick);
            if(slot == null || slot.keeperSlot()){
                continue;
            }
            if(planner.me().equals(slot.manager()) && picksIn <= order.size()){
                stops.add(new int[]{picksIn, pick, slot.round()});
            }
            picksIn++;
        }
        return stops;
    }

    // ---- 4. the committee ----

    /**
     * Four engines vote. Two of them - lookahead-1 and hindsight - are the SAME
     * estimator: both build a one-position HeadPolicy, complete it with
     * simulateFrom and score bestNine, differing only in sample size and seed
     * offset. This measures how often each pair agrees, which is the only way
     * to tell a committee from a chorus.
     */
    static void committee(DraftPlanner planner, DraftSimulator simulator,
                          TimingPlanner timing, List<String> order, int rollouts,
                          int scenarios){
        System.out.printf("%n--- 4  COMMITTEE (can they disagree?) ---%n");
        System.out.printf("   %-5s %-6s | %-11s %-11s %-11s %-11s | %-8s  %s%n", "ROUND",
                "PICK", "lookahead-2", "lookahead-1", "hindsight", "vorp-greedy",
                "spread(L2)", "verdict");
        Map<String, Integer> agree = new LinkedHashMap<>();
        int rows = 0;
        int ties = 0;
        for(int[] stop : myStops(simulator, planner, order)){
            if(stop[2] > 9){
                continue;
            }
            DraftSimulator.SimState state = simulator.stateAfter(order.subList(0, stop[0]));
            List<String> roster = rosterFrom(order.subList(0, stop[0]), state, simulator,
                    planner);
            Map<Position, String> best = timing.bestAvailable(state.boardView());
            Map<Position, Double> l2 = LiveCommittee.lookahead(timing, planner, simulator,
                    state, roster, best, rollouts, 2);
            Map<Position, Double> l1 = LiveCommittee.lookahead(timing, planner, simulator,
                    state, roster, best, rollouts, 1);
            Map<Position, Double> hs = LiveCommittee.hindsight(timing, planner, simulator,
                    state, roster, best, scenarios);
            Map<Position, Double> vg = LiveCommittee.greedy(timing, state, roster, best,
                    stop[1]);
            Position a = argmax(l2);
            Position b = argmax(l1);
            Position c = argmax(hs);
            Position d = argmax(vg);
            // LiveCommittee.vote's arbitration, verbatim: a plurality tally in
            // an EnumMap, scanned with a strict >. Iteration order is enum
            // order - QB, RB, WR, TE, DEF - so a tie goes to whichever position
            // is DECLARED first, and prints as "2 of 4 engines say RB" with no
            // sign that it was a coin flip.
            Map<Position, Integer> tally = new EnumMap<>(Position.class);
            for(Position vote : new Position[]{a, b, c, d}){
                tally.merge(vote, 1, Integer::sum);
            }
            Position consensus = null;
            int most = 0;
            int tied = 0;
            for(Map.Entry<Position, Integer> entry : tally.entrySet()){
                if(entry.getValue() > most){
                    most = entry.getValue();
                    consensus = entry.getKey();
                }
            }
            for(Integer count : tally.values()){
                if(count == most){
                    tied++;
                }
            }
            System.out.printf("   %-5d %-6d | %-11s %-11s %-11s %-11s | %-8.2f  %d of 4 say"
                            + " %s%s%n", stop[2], stop[1], a, b, c, d, spread(l2), most,
                    consensus, tied > 1 ? "   <- TIE, broken by enum order" : "");
            if(tied > 1){
                ties++;
            }
            agree.merge("L2 = L1", a == b ? 1 : 0, Integer::sum);
            agree.merge("L1 = HS", b == c ? 1 : 0, Integer::sum);
            agree.merge("L2 = HS", a == c ? 1 : 0, Integer::sum);
            agree.merge("L2 = VG", a == d ? 1 : 0, Integer::sum);
            rows++;
        }
        System.out.printf("%n   %d of %d picks were decided by a TIE the vote does not"
                + " report as one.%n", ties, rows);
        System.out.printf("   pairwise agreement over %d picks:%n", rows);
        for(Map.Entry<String, Integer> entry : agree.entrySet()){
            System.out.printf("      %-10s %d/%d%n", entry.getKey(), entry.getValue(), rows);
        }
        System.out.printf("   lookahead-1 and hindsight are the same estimator with a"
                + " different%n   seed offset and sample size - LiveCommittee's own comment"
                + " says hindsight%n   solves each scenario EXACTLY with no tail policy,"
                + " which it does not.%n");
    }

    static Position argmax(Map<Position, Double> values){
        Position top = null;
        double best = -Double.MAX_VALUE;
        for(Map.Entry<Position, Double> entry : values.entrySet()){
            if(entry.getValue() != null && entry.getValue() > best){
                best = entry.getValue();
                top = entry.getKey();
            }
        }
        return top;
    }

    // ---- 5. the clock ----

    /**
     * Sixty seconds a pick. DraftNight's cycle is LiveCommittee.vote plus
     * WaitCheck.report plus one uncached read of the live board; Draft2026 adds
     * LiveBoard and TWO more reads of the same board. This times the engine
     * work at every one of my picks, so a cost that grows with picks-in shows
     * up where it would actually bite.
     */
    static void clock(DraftPlanner planner, DraftSimulator simulator, TimingPlanner timing,
                      Map<String, Double> points, List<String> order, int rollouts,
                      int scenarios, int waitRollouts){
        System.out.printf("%n--- 5  CLOCK (engine seconds per pick, 60s budget) ---%n");
        System.out.printf("   %-5s %-6s %-8s %-9s %-9s %-9s%n", "ROUND", "PICK", "picks-in",
                "vote(s)", "wait(s)", "total(s)");
        java.io.PrintStream real = System.out;
        for(int[] stop : myStops(simulator, planner, order)){
            if(stop[2] > 9){
                continue;
            }
            DraftSimulator.SimState state = simulator.stateAfter(order.subList(0, stop[0]));
            List<String> roster = rosterFrom(order.subList(0, stop[0]), state, simulator,
                    planner);
            java.io.ByteArrayOutputStream quiet = new java.io.ByteArrayOutputStream();
            long t0 = System.nanoTime();
            System.setOut(new java.io.PrintStream(quiet));
            try {
                LiveCommittee.vote(timing, planner, simulator, state, roster, rollouts,
                        scenarios);
            }
            finally {
                System.setOut(real);
            }
            double voteSeconds = (System.nanoTime() - t0) / 1e9;
            double waitSeconds = 0;
            if(stop[2] <= 7){
                long t1 = System.nanoTime();
                System.setOut(new java.io.PrintStream(quiet));
                try {
                    WaitCheck.report(timing, planner, simulator, state, points, waitRollouts);
                }
                finally {
                    System.setOut(real);
                }
                waitSeconds = (System.nanoTime() - t1) / 1e9;
            }
            System.out.printf("   %-5d %-6d %-8d %-9.1f %-9.1f %-9.1f%n", stop[2], stop[1],
                    stop[0], voteSeconds, waitSeconds, voteSeconds + waitSeconds);
        }
    }

    // ---- 6. keepers ----

    /**
     * Two questions. Can Model A ever NAME a man somebody keeps - the claim
     * DRAFT-READY makes in bold. And is a man Justin HOLDS priced at his own
     * points throughout, or repriced by other people's picks - the fault
     * LiveBoard had, where a keeper's rank was read off how many of his
     * position had left the board.
     */
    static void keepers(AAAConfiguration configuration, DraftPlanner planner,
                        DraftSimulator simulator, TimingPlanner timing, List<String> order){
        System.out.printf("%n--- 6  KEEPERS ---%n");
        Set<String> kept = LiveBoard.kept(configuration);
        int namedKept = 0;
        int onBoard = 0;
        for(String id : simulator.initialState().boardView()){
            if(kept.contains(id)){
                onBoard++;
            }
        }
        List<String> problems = new ArrayList<>();
        // What a held man is worth, measured at every one of my picks. LiveBoard
        // read a keeper's rank off depth() - how many of his position had LEFT
        // THE BOARD - so Tuten was RB1 before a pick and RB55 by round 15
        // without playing a down. Model A prices a roster with
        // StartingLineup.bestNine, which reads points straight out of the
        // projection map, so the held man's OWN number cannot move. The MARGINAL
        // he adds does fall as the roster fills, and that fall is correct.
        System.out.printf("   %-5s %-6s %-8s %-12s %-12s%n", "ROUND", "PICK", "kept-best",
                "keeper pts", "keeper marginal");
        for(int[] stop : myStops(simulator, planner, order)){
            DraftSimulator.SimState state = simulator.stateAfter(order.subList(0, stop[0]));
            int keptHere = 0;
            for(String id : timing.bestAvailable(state.boardView()).values()){
                if(kept.contains(id)){
                    namedKept++;
                    keptHere++;
                }
            }
            List<String> roster = rosterFrom(order.subList(0, stop[0]), state, simulator,
                    planner);
            double held = 0;
            for(String id : planner.myKeeperIDs()){
                held += planner.points().getOrDefault(id, 0.0);
                if(java.util.Collections.frequency(roster, id) != 1){
                    problems.add(name(id) + " appears "
                            + java.util.Collections.frequency(roster, id)
                            + " times on my roster at pick " + stop[1]);
                }
                if(state.takenAtOf(id) != null){
                    problems.add(name(id) + " was DRAFTED by somebody at pick "
                            + state.takenAtOf(id));
                }
            }
            List<String> without = new ArrayList<>(roster);
            without.removeAll(planner.myKeeperIDs());
            double marginal = StartingLineup.bestNine(roster, planner.points())
                    - StartingLineup.bestNine(without, planner.points());
            System.out.printf("   %-5d %-6d %-8d %-12.1f %-12.1f%n", stop[2], stop[1],
                    keptHere, held, marginal);
        }
        System.out.printf("%n   kept men still on the board            %d  (must be 0)%n",
                onBoard);
        System.out.printf("   times a kept man was best-available    %d  (must be 0)%n",
                namedKept);
        System.out.printf("   roster/keeper problems                 %d%n", problems.size());
        for(String problem : problems){
            System.out.printf("      %s%n", problem);
        }
    }

    static String name(String sleeperID){
        Player player = Player.getPlayerFromSIDV2(sleeperID);
        return player == null ? sleeperID : player.lastName;
    }
}
