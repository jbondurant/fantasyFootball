import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * A tournament of pick-selection policies for MY seat, all priced in the same
 * game so their average totals are comparable (Justin's spec, 2026-08-26):
 *
 *   - rounds 1-9 of the real 2026 board, opponents played by the shipped model
 *     with their declared keepers as entered;
 *   - my keepers (Tuten, Purdy) pinned onto my rounds 8-9 slots, so I make
 *     exactly seven live picks and finish with exactly nine players;
 *   - composition enforced by construction: 1 QB, 2 RB, 3 WR, 1 TE, 2 FLEX -
 *     Purdy fills the QB, Tuten one RB, and every policy may only take a
 *     position it still legally needs (so best-nine equals the sum of nine).
 *
 * The roster spans the architectures this repo has argued through:
 *
 *   committed (searched offline on separate seeds, then followed blindly)
 *     shipped-plan     the locked plan's rounds 1-7
 *     staged-frontier  one-round-frontier staged search, greedy tails
 *     exhaustive       every feasible 7-pick sequence (742 of them) priced on
 *                      common random numbers - the committed-play optimum, and
 *                      the "max over averages of full commitments" architecture
 *   reactive (no search, decide from the live board)
 *     random           uniformly random feasible position - the floor
 *     adp-follower     best market-rank player at a feasible position
 *     greedy-raw       best projected points at a feasible position
 *     greedy-vorp      points minus what waiting one of my rounds returns
 *   adaptive (re-decide each pick with inner rollouts from the live state)
 *     oldschool-1..3   Justin's 2022/2023 design at head depth k: enumerate
 *                      k-deep position heads, value each by shuffled-random
 *                      tails, take the winning head's first position
 *     adaptive-greedy  depth-1 heads with greedy-raw tails - same lookahead,
 *                      modern stand-in
 *
 * Every policy is evaluated on the SAME fresh seed stream (paired comparison;
 * searches used disjoint seeds), mean +/- SE printed with the paired delta
 * against the exhaustive committed optimum.
 *
 *   ./gradlew run -Pmain=PolicyTournament [-Ptrials=800] [-Psearch=150]
 *                 [-PadaptiveTrials=150] [-Pinner=16]
 */
public class PolicyTournament {

    static final long SEARCH_SEED = DraftSimulator.SEED + 41_000_000L;
    static final long EVAL_SEED = DraftSimulator.SEED + 42_000_000L;
    static final long POLICY_SEED = DraftSimulator.SEED + 43_000_000L;
    private static final Position[] SKILL = {Position.RB, Position.WR, Position.TE};

    private final DraftSimulator simulator;
    private final String me;
    private final List<String> myKeeperIDs;
    private final Map<String, Double> points;
    /** my pick number -> position -> mean best-available points if I wait. */
    private final Map<Integer, Map<Position, Double>> waitingTable = new HashMap<>();
    private final int[] myPicks;

    PolicyTournament(DraftSimulator simulator, String me, List<String> myKeeperIDs,
                     Map<String, Double> points){
        this.simulator = simulator;
        this.me = me;
        this.myKeeperIDs = myKeeperIDs;
        this.points = points;
        this.myPicks = simulator.pickNumbersOf(me);
    }

    // ---- the composition ledger ----

    /**
     * What my roster still owes: dedicated slots per position plus flex
     * count. A pick consumes its dedicated slot first, flex second; a
     * position is feasible while either remains.
     */
    static final class Needs {
        final EnumMap<Position, Integer> dedicated;
        int flex;

        Needs(EnumMap<Position, Integer> dedicated, int flex){
            this.dedicated = dedicated;
            this.flex = flex;
        }

        /** The full starting nine minus what the keepers already fill. */
        static Needs afterKeepers(List<String> keeperIDs){
            EnumMap<Position, Integer> dedicated = new EnumMap<>(Position.class);
            dedicated.put(Position.QB, 1);
            dedicated.put(Position.RB, 2);
            dedicated.put(Position.WR, 3);
            dedicated.put(Position.TE, 1);
            Needs needs = new Needs(dedicated, 2);
            for(String sleeperID : keeperIDs){
                needs.consume(Player.getPlayerFromSIDV2(sleeperID).position);
            }
            return needs;
        }

        boolean feasible(Position position){
            if(dedicated.getOrDefault(position, 0) > 0){
                return true;
            }
            return flex > 0 && (position == Position.RB || position == Position.WR
                    || position == Position.TE);
        }

        void consume(Position position){
            int have = dedicated.getOrDefault(position, 0);
            if(have > 0){
                dedicated.put(position, have - 1);
            }
            else if(feasible(position)){
                flex--;
            }
            else {
                throw new IllegalStateException("infeasible pick: " + position);
            }
        }

        List<Position> feasibleSkill(){
            List<Position> open = new ArrayList<>();
            for(Position position : SKILL){
                if(feasible(position)){
                    open.add(position);
                }
            }
            return open;
        }

        Needs copy(){
            return new Needs(new EnumMap<>(dedicated), flex);
        }
    }

    /** Every feasible full sequence of `length` picks under the composition. */
    static List<List<Position>> allSequences(Needs start, int length){
        List<List<Position>> sequences = new ArrayList<>();
        extend(start, new ArrayList<>(), length, sequences);
        return sequences;
    }

    private static void extend(Needs needs, List<Position> prefix, int length,
                               List<List<Position>> sequences){
        if(prefix.size() == length){
            sequences.add(new ArrayList<>(prefix));
            return;
        }
        for(Position position : needs.feasibleSkill()){
            Needs next = needs.copy();
            next.consume(position);
            prefix.add(position);
            extend(next, prefix, length, sequences);
            prefix.remove(prefix.size() - 1);
        }
    }

    static Map<Position, String> bestByPosition(List<String> board, Map<String, Double> points){
        Map<Position, String> best = new EnumMap<>(Position.class);
        Map<Position, Double> bestPoints = new EnumMap<>(Position.class);
        for(String sleeperID : board){
            Position position = Player.getPlayerFromSIDV2(sleeperID).position;
            double projected = points.getOrDefault(sleeperID, 0.0);
            if(projected > bestPoints.getOrDefault(position, -1.0)){
                bestPoints.put(position, projected);
                best.put(position, sleeperID);
            }
        }
        return best;
    }

    // ---- policies ----

    interface Factory {
        String name();
        TournamentPolicy create(long trialSeed);
    }

    /** Base: tracks needs and my roster; subclasses pick the position. */
    abstract class TournamentPolicy implements DraftSimulator.MyPolicy {
        final Needs needs = Needs.afterKeepers(myKeeperIDs);
        final List<String> mine = new ArrayList<>(myKeeperIDs);
        int decision = 0;

        abstract Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                                       DraftSimulator.SimState state);

        @Override
        public String choose(List<String> board, DraftSimulator.Slot slot){
            return choose(board, slot, null);
        }

        @Override
        public String choose(List<String> board, DraftSimulator.Slot slot,
                             DraftSimulator.SimState state){
            Position position = pickPosition(board, slot, state);
            String chosen = bestByPosition(board, points).get(position);
            if(chosen == null){   // board bare at that position: take best feasible
                Map<Position, String> best = bestByPosition(board, points);
                for(Position fallback : needs.feasibleSkill()){
                    if(best.get(fallback) != null){
                        position = fallback;
                        chosen = best.get(fallback);
                        break;
                    }
                }
            }
            needs.consume(position);
            mine.add(chosen);
            decision++;
            return chosen;
        }

        double score(){
            return StartingLineup.bestNine(mine, points);
        }
    }

    /** Follows a fixed position sequence - a committed plan. */
    class SequencePolicy extends TournamentPolicy {
        final List<Position> sequence;

        SequencePolicy(List<Position> sequence){
            this.sequence = sequence;
        }

        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            return sequence.get(decision);
        }
    }

    class RandomFeasible extends TournamentPolicy {
        final Random random;

        RandomFeasible(long seed){
            this.random = new Random(seed);
        }

        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            List<Position> open = needs.feasibleSkill();
            return open.get(random.nextInt(open.size()));
        }
    }

    class AdpFollower extends TournamentPolicy {
        final Map<String, Double> adp;

        AdpFollower(Map<String, Double> adp){
            this.adp = adp;
        }

        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            for(String sleeperID : board){   // board arrives in ADP order
                Position position = Player.getPlayerFromSIDV2(sleeperID).position;
                if(needs.feasible(position) && position != Position.QB){
                    return position;
                }
            }
            return needs.feasibleSkill().get(0);
        }
    }

    class GreedyRaw extends TournamentPolicy {
        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            Map<Position, String> best = bestByPosition(board, points);
            Position top = null;
            double topPoints = -1;
            for(Position position : needs.feasibleSkill()){
                String candidate = best.get(position);
                double projected = candidate == null ? -1 : points.getOrDefault(candidate, 0.0);
                if(projected > topPoints){
                    topPoints = projected;
                    top = position;
                }
            }
            return top;
        }
    }

    class GreedyVorp extends TournamentPolicy {
        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            Map<Position, String> best = bestByPosition(board, points);
            int next = nextPickAfter(slot.pickNumber());
            Position top = null;
            double topValue = -Double.MAX_VALUE;
            for(Position position : needs.feasibleSkill()){
                String candidate = best.get(position);
                if(candidate == null){
                    continue;
                }
                double replacement = next < 0 ? 0.0
                        : waitingTable.getOrDefault(next, Map.of())
                                .getOrDefault(position, 0.0);
                double value = points.getOrDefault(candidate, 0.0) - replacement;
                if(value > topValue){
                    topValue = value;
                    top = position;
                }
            }
            return top;
        }
    }

    /**
     * The adaptive family: at each of my picks, enumerate feasible position
     * heads `depth` deep, value each head by `inner` completions of the live
     * state (my remaining picks played head-then-tail), take the winning
     * head's first position. randomTail=true is Justin's 2022-23 design; the
     * greedy tail is the modern stand-in. Inner seeds are common across heads
     * (paired), and disjoint from the rollout's own stream.
     */
    class Lookahead extends TournamentPolicy {
        final int depth;
        final int inner;
        final boolean randomTail;
        final long seed;

        Lookahead(int depth, int inner, boolean randomTail, long seed){
            this.depth = depth;
            this.inner = inner;
            this.randomTail = randomTail;
            this.seed = seed;
        }

        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            List<List<Position>> heads = allSequences(needs.copy(),
                    Math.min(depth, myPicks.length - decision));
            if(heads.size() == 1){
                return heads.get(0).get(0);
            }
            Position top = null;
            double topValue = -Double.MAX_VALUE;
            for(List<Position> head : heads){
                double total = 0;
                for(int r = 0; r < inner; r++){
                    long innerSeed = seed + 101L * decision + 7919L * r;
                    TournamentPolicy completion = randomTail
                            ? new HeadThenRandom(head, needs, mine, new Random(innerSeed ^ 0x5DEECE66DL))
                            : new HeadThenGreedy(head, needs, mine);
                    DraftSimulator.SimState branch = state.copy();
                    simulator.simulateFrom(branch, new Random(innerSeed), me, completion);
                    total += completion.score();
                }
                double mean = total / inner;
                if(mean > topValue){
                    topValue = mean;
                    top = head.get(0);
                }
            }
            return top;
        }
    }

    /** Inner completion: follow the head, then shuffled-random feasible. */
    class HeadThenRandom extends TournamentPolicy {
        final List<Position> head;
        final Random random;

        HeadThenRandom(List<Position> head, Needs outerNeeds, List<String> outerMine,
                       Random random){
            this.head = head;
            this.random = random;
            this.needs.dedicated.clear();
            this.needs.dedicated.putAll(outerNeeds.dedicated);
            this.needs.flex = outerNeeds.flex;
            this.mine.clear();
            this.mine.addAll(outerMine);
        }

        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            if(decision < head.size()){
                return head.get(decision);
            }
            List<Position> open = needs.feasibleSkill();
            return open.get(random.nextInt(open.size()));
        }
    }

    /** Inner completion: follow the head, then greedy-raw. */
    class HeadThenGreedy extends TournamentPolicy {
        final List<Position> head;

        HeadThenGreedy(List<Position> head, Needs outerNeeds, List<String> outerMine){
            this.head = head;
            this.needs.dedicated.clear();
            this.needs.dedicated.putAll(outerNeeds.dedicated);
            this.needs.flex = outerNeeds.flex;
            this.mine.clear();
            this.mine.addAll(outerMine);
        }

        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            if(decision < head.size()){
                return head.get(decision);
            }
            Map<Position, String> best = bestByPosition(board, points);
            Position top = null;
            double topPoints = -1;
            for(Position position : needs.feasibleSkill()){
                String candidate = best.get(position);
                double projected = candidate == null ? -1 : points.getOrDefault(candidate, 0.0);
                if(projected > topPoints){
                    topPoints = projected;
                    top = position;
                }
            }
            return top;
        }
    }

    // ---- search and evaluation ----

    int nextPickAfter(int pickNumber){
        for(int pick : myPicks){
            if(pick > pickNumber){
                return pick;
            }
        }
        return -1;
    }

    /** Mean best-available points per position at each of my picks. */
    void fillWaitingTable(int trials){
        Map<Integer, Map<Position, double[]>> sums = new HashMap<>();
        for(int pick : myPicks){
            Map<Position, double[]> perPosition = new EnumMap<>(Position.class);
            for(Position position : SKILL){
                perPosition.put(position, new double[]{0});
            }
            sums.put(pick, perPosition);
        }
        Random random = new Random(SEARCH_SEED + 9_000_000L);
        Set<String> onBoard = simulator.players();
        for(int trial = 0; trial < trials; trial++){
            Map<String, Integer> takenAt = simulator.simulateOnce(random);
            for(int pick : myPicks){
                Map<Position, Double> best = new EnumMap<>(Position.class);
                for(String sleeperID : onBoard){
                    int taken = takenAt.getOrDefault(sleeperID, Integer.MAX_VALUE);
                    if(taken < pick){
                        continue;
                    }
                    best.merge(Player.getPlayerFromSIDV2(sleeperID).position,
                            points.getOrDefault(sleeperID, 0.0), Math::max);
                }
                for(Position position : SKILL){
                    sums.get(pick).get(position)[0] += best.getOrDefault(position, 0.0);
                }
            }
        }
        for(int pick : myPicks){
            Map<Position, Double> row = new EnumMap<>(Position.class);
            for(Position position : SKILL){
                row.put(position, sums.get(pick).get(position)[0] / trials);
            }
            waitingTable.put(pick, row);
        }
    }

    /** Mean score of a committed sequence over CRN search rollouts. */
    double searchMean(List<Position> sequence, int rollouts){
        double total = 0;
        for(int r = 0; r < rollouts; r++){
            SequencePolicy policy = new SequencePolicy(sequence);
            simulator.simulateOnce(new Random(SEARCH_SEED + 7919L * r), me, policy);
            total += policy.score();
        }
        return total / rollouts;
    }

    /** Per-trial scores of a policy on the shared fresh eval stream. */
    double[] evaluate(Factory factory, int trials){
        return IntStream.range(0, trials).parallel().mapToDouble(r -> {
            TournamentPolicy policy = factory.create(POLICY_SEED + 7919L * r);
            simulator.simulateOnce(new Random(EVAL_SEED + 7919L * r), me, policy);
            return policy.score();
        }).toArray();
    }

    static double mean(double[] scores){
        double total = 0;
        for(double score : scores){
            total += score;
        }
        return total / scores.length;
    }

    static double standardError(double[] scores){
        double center = mean(scores);
        double sumSquares = 0;
        for(double score : scores){
            sumSquares += (score - center) * (score - center);
        }
        return Math.sqrt(sumSquares / (scores.length - 1) / scores.length);
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 800);
        int search = Integer.getInteger("search", 150);
        int adaptiveTrials = Integer.getInteger("adaptiveTrials", 150);
        int inner = Integer.getInteger("inner", 16);

        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, lastCompleted);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, lastCompleted,
                earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        DraftSimulator pinned = planner.simulator()
                .withKeeperSlots(planner.me(), Set.of(8, 9));
        PolicyTournament tournament = new PolicyTournament(pinned, planner.me(),
                planner.myKeeperIDs(), planner.points());
        Map<String, Double> adp = new HashMap<>();
        for(String sleeperID : planner.points().keySet()){
            double value = SleeperProjections.adpOf(sleeperID);
            if(value < Double.MAX_VALUE){
                adp.put(sleeperID, value);
            }
        }

        System.out.printf("policy tournament: 7 live picks + keepers on rounds 8-9, "
                        + "composition 1QB/2RB/3WR/1TE/2FLEX%n"
                        + "my picks %s, keepers %s%n",
                java.util.Arrays.toString(tournament.myPicks),
                tournament.myKeeperIDs.stream()
                        .map(id -> Player.getPlayerFromSIDV2(id).lastName).toList());

        tournament.fillWaitingTable(Math.max(search, 100));

        // ---- offline searches, on their own seeds ----
        Needs start = Needs.afterKeepers(tournament.myKeeperIDs);
        List<List<Position>> sequences = allSequences(start, tournament.myPicks.length);
        System.out.printf("%n%d feasible committed sequences; searching all of them at %d "
                + "CRN rollouts each...%n", sequences.size(), search);
        long startTime = System.currentTimeMillis();
        double[] sequenceMeans = IntStream.range(0, sequences.size()).parallel()
                .mapToDouble(s -> tournament.searchMean(sequences.get(s), search))
                .toArray();
        int argmax = 0;
        for(int s = 1; s < sequenceMeans.length; s++){
            if(sequenceMeans[s] > sequenceMeans[argmax]){
                argmax = s;
            }
        }
        List<Position> exhaustiveBest = sequences.get(argmax);
        System.out.printf("exhaustive winner %s, search mean %.1f (%.0fs)%n", exhaustiveBest,
                sequenceMeans[argmax], (System.currentTimeMillis() - startTime) / 1000.0);

        List<Position> stagedBest = new ArrayList<>();
        Needs stagedNeeds = start.copy();
        for(int stage = 0; stage < tournament.myPicks.length; stage++){
            Position best = null;
            double bestMean = -Double.MAX_VALUE;
            for(Position candidate : stagedNeeds.feasibleSkill()){
                List<Position> prefix = new ArrayList<>(stagedBest);
                prefix.add(candidate);
                double value = IntStream.range(0, search).parallel().mapToDouble(r -> {
                    TournamentPolicy policy = tournament.new HeadThenGreedy(prefix,
                            start.copy(), new ArrayList<>(tournament.myKeeperIDs));
                    tournament.simulator.simulateOnce(
                            new Random(SEARCH_SEED + 7919L * r), tournament.me, policy);
                    return policy.score();
                }).sum() / search;
                if(value > bestMean){
                    bestMean = value;
                    best = candidate;
                }
            }
            stagedBest.add(best);
            stagedNeeds.consume(best);
        }
        System.out.printf("staged-frontier winner %s%n", stagedBest);

        List<Position> shipped = List.of(Position.RB, Position.WR, Position.RB,
                Position.WR, Position.WR, Position.WR, Position.TE);

        // ---- the roster, evaluated on the shared fresh stream ----
        Map<String, double[]> results = new LinkedHashMap<>();
        Map<String, Integer> trialsUsed = new LinkedHashMap<>();
        List<Object[]> roster = List.of(
                new Object[]{"random-feasible", trials,
                        (Factory) named("random-feasible",
                                seed -> tournament.new RandomFeasible(seed))},
                new Object[]{"adp-follower", trials,
                        named("adp-follower", seed -> tournament.new AdpFollower(adp))},
                new Object[]{"greedy-raw", trials,
                        named("greedy-raw", seed -> tournament.new GreedyRaw())},
                new Object[]{"greedy-vorp", trials,
                        named("greedy-vorp", seed -> tournament.new GreedyVorp())},
                new Object[]{"shipped-plan " + label(shipped), trials,
                        named("shipped-plan", seed -> tournament.new SequencePolicy(shipped))},
                new Object[]{"staged-frontier " + label(stagedBest), trials,
                        named("staged-frontier",
                                seed -> tournament.new SequencePolicy(stagedBest))},
                new Object[]{"exhaustive-committed " + label(exhaustiveBest), trials,
                        named("exhaustive",
                                seed -> tournament.new SequencePolicy(exhaustiveBest))},
                new Object[]{"oldschool-1 (random tails)", adaptiveTrials,
                        named("oldschool-1",
                                seed -> tournament.new Lookahead(1, inner, true, seed))},
                new Object[]{"oldschool-2 (random tails)", adaptiveTrials,
                        named("oldschool-2",
                                seed -> tournament.new Lookahead(2, inner, true, seed))},
                new Object[]{"oldschool-3 (random tails)", adaptiveTrials,
                        named("oldschool-3",
                                seed -> tournament.new Lookahead(3, inner, true, seed))},
                new Object[]{"adaptive-greedy (d1)", adaptiveTrials,
                        named("adaptive-greedy",
                                seed -> tournament.new Lookahead(1, inner, false, seed))});

        for(Object[] entry : roster){
            String name = (String) entry[0];
            int n = (Integer) entry[1];
            long policyStart = System.currentTimeMillis();
            double[] scores = tournament.evaluate((Factory) entry[2], n);
            results.put(name, scores);
            trialsUsed.put(name, n);
            System.out.printf("   evaluated %-42s %6.1f  (%d trials, %.0fs)%n",
                    name, mean(scores), n,
                    (System.currentTimeMillis() - policyStart) / 1000.0);
        }

        double[] reference = results.get("exhaustive-committed " + label(exhaustiveBest));
        System.out.printf("%n%-46s %8s %6s %18s%n", "POLICY", "MEAN", "+/-SE",
                "vs exhaustive");
        results.entrySet().stream()
                .sorted((a, b) -> Double.compare(mean(b.getValue()), mean(a.getValue())))
                .forEach(entry -> {
                    double[] scores = entry.getValue();
                    int paired = Math.min(scores.length, reference.length);
                    double[] deltas = new double[paired];
                    for(int r = 0; r < paired; r++){
                        deltas[r] = scores[r] - reference[r];
                    }
                    System.out.printf("%-46s %8.1f %6.1f %10.1f +/- %.1f%n",
                            entry.getKey(), mean(scores), standardError(scores),
                            mean(deltas), standardError(deltas));
                });
        System.out.printf("%nsearch %d rollouts (seeds disjoint from eval), eval %d trials "
                        + "(adaptive %d, inner %d); all policies share the eval streams, so "
                        + "the vs-exhaustive column is a paired difference.%n",
                search, trials, adaptiveTrials, inner);
    }

    private static String label(List<Position> sequence){
        StringBuilder text = new StringBuilder();
        for(Position position : sequence){
            text.append(position.name().charAt(0)).append(position == Position.QB ? "B" : "");
        }
        return text.toString();
    }

    private static Factory named(String name, java.util.function.LongFunction<?> create){
        return new Factory() {
            @Override
            public String name(){
                return name;
            }

            @Override
            public TournamentPolicy create(long trialSeed){
                return (TournamentPolicy) create.apply(trialSeed);
            }
        };
    }
}
