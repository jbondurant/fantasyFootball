import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The combiner for rounds 8-16: what a candidate is worth, whether he lasts,
 * and therefore whether to take him now.
 *
 * StashValue says what a pick at a position and round has been worth.
 * LateSurvival says who is still on the board at my next pick. Neither decides
 * anything alone - this multiplies them, in the same shape WaitCheck uses for
 * rounds 1-7:
 *
 *     expected loss from waiting = P(gone) x (his value - the best left instead)
 *
 * Value is deliberately built from two different kinds of evidence, and the
 * output says which is which. THIS SEASON is per-player, from projections:
 * his points over the replacement the waiver wire would hand me for free.
 * KEEPER is a base rate, from five seasons of this league's own picks at that
 * position and round - history sets the level, projections do the ranking
 * inside it. Inventing a per-player keeper estimate on ~50 positive examples
 * would be precision this data cannot support.
 *
 *   ./gradlew run -Pmain=LateWaitOrTake -PdraftId=<id> [-Ptrials=300]
 *   ./gradlew run -Pmain=LateWaitOrTake -Ppick=114     (plan a pick pre-draft)
 */
public class LateWaitOrTake {

    static final int SHOWN = 8;

    /** A player on the board, with the two halves of his value kept apart. */
    record Candidate(String id, String name, Position position, double thisSeason,
                     double keeper, double total, boolean young){}

    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", System.getProperty("scheduleRounds", "16"));

        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 300);
        String draftID = System.getProperty("draftId");

        List<Keeper> myKeepers = DraftPlanner.keepersFromProperty(configuration);
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, myKeepers,
                model, earliness);
        DraftSimulator simulator = planner.simulator();
        Map<String, Double> points = planner.points();
        Map<Position, Double> wire = wireProjection(points,
                InsuranceTest.replacementRanks(configuration));

        // HistoricalProjections refuses the current season by design; the live
        // feed carries the same rookie_year metadata.
        java.util.Set<String> young = SleeperProjections.youngPlayers(2);
        double youngFactor = StashValue.youthMultiplier(configuration, true);
        double veteranFactor = StashValue.youthMultiplier(configuration, false);

        List<String> taken = draftID == null ? List.of() : LiveDraft.livePicks(draftID);
        DraftSimulator.SimState state = simulator.stateAfter(taken);

        // Roll forward to the pick being planned, if one was named.
        Integer target = Integer.getInteger("pick");
        Random roller = new Random(DraftSimulator.SEED);
        while(simulator.slotOf(state) != null
                && (!planner.me().equals(simulator.slotOf(state).manager())
                    || (target != null && simulator.slotOf(state).pickNumber() < target))){
            simulator.simulateOneFrom(state, roller);
        }
        DraftSimulator.Slot slot = simulator.slotOf(state);
        if(slot == null){
            System.out.println("no picks of mine left on the board");
            return;
        }
        if(slot.round() < 8){
            System.out.printf("pick %d is round %d - that is Model A's game."
                    + " Use DraftNight.%n", slot.pickNumber(), slot.round());
            return;
        }
        int nextPick = nextPickOf(simulator, state, planner, slot.pickNumber());

        System.out.printf("%nkeeper term scaled by youth: young x%.2f, veteran x%.2f"
                + " (measured)%n", youngFactor, veteranFactor);
        System.out.printf("%npick %d (round %d); my next pick is %s%n", slot.pickNumber(),
                slot.round(), nextPick < 0 ? "NONE - this is my last"
                        : nextPick + " (" + (nextPick - slot.pickNumber())
                          + " picks away)");

        // Value every available player: per-player this season, base rate keeper.
        List<Candidate> board = new ArrayList<>();
        for(String id : simulator.players()){
            if(state.takenAtOf(id) != null){
                continue;
            }
            Player player = Player.getPlayerFromSIDV2(id);
            if(player == null || !StartingLineup.isSkillPosition(player.position)){
                continue;
            }
            double thisSeason = Math.max(0, points.getOrDefault(id, 0.0)
                    - wire.getOrDefault(player.position, 0.0));
            boolean isYoung = young.contains(id);
            double keeper = StashValue.keeperTermFor(configuration, player.position,
                    slot.round()) * (isYoung ? youngFactor : veteranFactor);
            board.add(new Candidate(id, player.firstName + " " + player.lastName,
                    player.position, thisSeason, keeper, thisSeason + keeper, isYoung));
        }
        board.sort(Comparator.comparingDouble(Candidate::total).reversed());
        List<Candidate> shortlist = board.subList(0, Math.min(SHOWN, board.size()));

        if(nextPick < 0){
            System.out.println("\nNothing to wait for - take the top of this list.");
            print(shortlist, new HashMap<>(), new HashMap<>(), false,
                    new HashMap<>(), 1);
            return;
        }

        // WaitCheck's question, asked per candidate: if I spend this pick on
        // someone else, is he still here next time - and what is left if not?
        Map<String, Integer> survived = new HashMap<>();
        Map<String, Double> replacementTotal = new HashMap<>();
        Map<String, Integer> goneCount = new HashMap<>();
        for(Candidate candidate : shortlist){
            survived.put(candidate.id(), 0);
            replacementTotal.put(candidate.id(), 0.0);
            goneCount.put(candidate.id(), 0);
        }
        for(int trial = 0; trial < trials; trial++){
            for(Candidate candidate : shortlist){
                Random random = new Random(551_000L + 7919L * trial);
                // spend this pick on the best man who is NOT the candidate, so
                // simulated-me cannot take the player whose survival I am measuring
                Candidate spend = shortlist.stream()
                        .filter(other -> !other.id().equals(candidate.id()))
                        .findFirst().orElse(null);
                DraftSimulator.SimState branch = spend == null ? state.copy()
                        : simulator.branchWith(state, spend.id());
                while(simulator.slotOf(branch) != null
                        && simulator.slotOf(branch).pickNumber() < nextPick){
                    simulator.simulateOneFrom(branch, random);
                }
                if(branch.takenAtOf(candidate.id()) == null){
                    survived.merge(candidate.id(), 1, Integer::sum);
                }
                else {
                    goneCount.merge(candidate.id(), 1, Integer::sum);
                    replacementTotal.merge(candidate.id(),
                            bestLeft(simulator, branch, points, wire, configuration,
                                    slot.round()), Double::sum);
                }
            }
        }
        print(shortlist, survived, replacementTotal, true, goneCount, trials);
    }

    static double bestLeft(DraftSimulator simulator, DraftSimulator.SimState branch,
                           Map<String, Double> points, Map<Position, Double> wire,
                           AAAConfiguration configuration, int round){
        double best = 0;
        for(String id : simulator.players()){
            if(branch.takenAtOf(id) != null){
                continue;
            }
            Player player = Player.getPlayerFromSIDV2(id);
            if(player == null || !StartingLineup.isSkillPosition(player.position)){
                continue;
            }
            double total = Math.max(0, points.getOrDefault(id, 0.0)
                    - wire.getOrDefault(player.position, 0.0))
                    + StashValue.keeperTermFor(configuration, player.position, round);
            // the replacement is valued on the same base rate, youth aside - it
            // is a ceiling on what is left, not a named recommendation
            best = Math.max(best, total);
        }
        return best;
    }

    /** Projected points of the best man the wire would hand me at each position. */
    static Map<Position, Double> wireProjection(Map<String, Double> points,
                                                Map<Position, Integer> ranks){
        Map<Position, List<Double>> byPosition = new EnumMap<>(Position.class);
        for(Map.Entry<String, Double> entry : points.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player != null && StartingLineup.isSkillPosition(player.position)){
                byPosition.computeIfAbsent(player.position, u -> new ArrayList<>())
                        .add(entry.getValue());
            }
        }
        Map<Position, Double> wire = new EnumMap<>(Position.class);
        for(Map.Entry<Position, List<Double>> entry : byPosition.entrySet()){
            List<Double> values = entry.getValue();
            values.sort(Comparator.reverseOrder());
            int rank = ranks.getOrDefault(entry.getKey(), 24);
            wire.put(entry.getKey(), values.size() >= rank
                    ? values.get(rank - 1) : 0.0);
        }
        return wire;
    }

    static int nextPickOf(DraftSimulator simulator, DraftSimulator.SimState state,
                          DraftPlanner planner, int afterPick){
        DraftSimulator.SimState probe = state.copy();
        Random random = new Random(DraftSimulator.SEED);
        while(simulator.slotOf(probe) != null){
            DraftSimulator.Slot slot = simulator.slotOf(probe);
            if(planner.me().equals(slot.manager()) && slot.pickNumber() > afterPick){
                return slot.pickNumber();
            }
            simulator.simulateOneFrom(probe, random);
        }
        return -1;
    }

    static void print(List<Candidate> shortlist, Map<String, Integer> survived,
                      Map<String, Double> replacement, boolean withWait,
                      Map<String, Integer> goneCount, int trials){
        System.out.printf("%n%-24s %-4s %-6s %11s %8s %8s", "CANDIDATE", "POS", "AGE",
                "this season", "keeper", "VALUE");
        if(withWait){
            System.out.printf(" %9s %13s   %s", "SURVIVES", "LOSS IF WAIT", "verdict");
        }
        System.out.println();
        for(Candidate candidate : shortlist){
            System.out.printf("%-24s %-4s %-6s %11.1f %8.1f %8.1f", candidate.name(),
                    candidate.position(), candidate.young() ? "young" : "vet",
                    candidate.thisSeason(), candidate.keeper(), candidate.total());
            if(withWait){
                double survives = survived.getOrDefault(candidate.id(), 0)
                        / (double) trials;
                int gone = goneCount.getOrDefault(candidate.id(), 0);
                double left = gone == 0 ? candidate.total()
                        : replacement.getOrDefault(candidate.id(), 0.0) / gone;
                double loss = (1 - survives) * Math.max(0, candidate.total() - left);
                String verdict = loss < 3 ? "wait - he keeps"
                        : loss < 10 ? String.format("lean take (%.0f)", loss)
                        : String.format("TAKE NOW (%.0f)", loss);
                System.out.printf(" %8.0f%% %13.1f   %s", survives * 100, loss, verdict);
            }
            System.out.println();
        }
        System.out.println("\nthis season = his projection over the man the wire would"
                + " give me free (per player).\nkeeper = what a stash at this position"
                + " and round has been worth next season\n(a measured base rate, not a"
                + " per-player estimate - ~50 positives cannot support one).\nLOSS IF"
                + " WAIT = P(gone) x (his value - the best left instead).");
        System.out.println("\nKNOWN WEAKNESS: a player projecting 0.0 this season still"
                + " draws the full keeper\nbase rate, because that rate was measured on"
                + " players who had a real role when\nthey were drafted. Read a zero in"
                + " the 'this season' column as UNPROVEN, not safe -\nand it is the"
                + " reason a per-player keeper estimate is the next thing to build.");
    }
}
