import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
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
    /**
     * Candidate positions everywhere; feasibility trims the list per game.
     * With a kept QB the QB slot is consumed up front and QB never appears
     * (it is not flex-eligible); swap the QB keeper out (-Pkeepers) and the
     * QB dimension - and its whole timing problem - re-enters the game.
     */
    private static final Position[] SKILL =
            {Position.QB, Position.RB, Position.WR, Position.TE};

    private final DraftSimulator simulator;
    private final String me;
    private final List<String> myKeeperIDs;
    private final Map<String, Double> points;
    /** my pick number -> position -> mean best-available points if I wait. */
    private final Map<Integer, Map<Position, Double>> waitingTable = new HashMap<>();
    /** my pick number -> position -> mean k-th-best available, k=1..5 - the
     *  certainty-equivalent availability the DP and the B&B bound consume. */
    private final Map<Integer, Map<Position, double[]>> depthTable = new HashMap<>();
    /** my pick number -> position -> mean count already drafted - the pace
     *  expectations the online corrector compares the live board against. */
    private final Map<Integer, Map<Position, Double>> expectedGone = new HashMap<>();
    private final Map<Position, Integer> initialCounts = new EnumMap<>(Position.class);
    private final int[] myPicks;
    static final int DEPTH_K = 5;

    PolicyTournament(DraftSimulator simulator, String me, List<String> myKeeperIDs,
                     Map<String, Double> points){
        this.simulator = simulator;
        this.me = me;
        this.myKeeperIDs = myKeeperIDs;
        this.points = points;
        this.myPicks = simulator.pickNumbersOf(me);
    }

    List<String> myKeeperIDs(){
        return myKeeperIDs;
    }

    DraftSimulator.SimState simulatorState(){
        return simulator.initialState();
    }

    int myPickCount(){
        return myPicks.length;
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
                if(needs.feasible(position)){
                    return position;
                }
            }
            return needs.feasibleSkill().get(0);
        }
    }

    /** Best raw-points position among those `needs` still allows. */
    Position bestRawPosition(Needs needs, List<String> board){
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

    /** Best points-minus-waiting position - the depletion-aware greedy. */
    Position bestVorpPosition(Needs needs, List<String> board, int pickNumber){
        Map<Position, String> best = bestByPosition(board, points);
        int next = nextPickAfter(pickNumber);
        Position top = null;
        double topValue = -Double.MAX_VALUE;
        for(Position position : needs.feasibleSkill()){
            String candidate = best.get(position);
            if(candidate == null){
                continue;
            }
            double replacement = next < 0 ? 0.0
                    : waitingTable.getOrDefault(next, Map.of()).getOrDefault(position, 0.0);
            double value = points.getOrDefault(candidate, 0.0) - replacement;
            if(value > topValue){
                topValue = value;
                top = position;
            }
        }
        return top;
    }

    class GreedyRaw extends TournamentPolicy {
        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            return bestRawPosition(needs, board);
        }
    }

    class GreedyVorp extends TournamentPolicy {
        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            return bestVorpPosition(needs, board, slot.pickNumber());
        }
    }

    /** What plays the rounds a lookahead head does not commit. */
    enum Tail { RANDOM, RAW, VORP, MODEL }

    // ---- hindsight machinery: determinized futures, solved exactly ----

    /** Per-position points-sorted alive lists at each of my remaining picks
     *  in ONE determinized future. */
    static final class Scenario {
        final Map<Integer, Map<Position, List<String>>> alive = new HashMap<>();
    }

    /** Plays out a future while interfering minimally (my slots take the
     *  bottom of the board, invisible to opponents' truncated choice sets),
     *  recording who is alive at each of my picks. */
    class ScenarioRecorder implements DraftSimulator.MyPolicy {
        final Scenario scenario = new Scenario();

        @Override
        public String choose(List<String> board, DraftSimulator.Slot slot){
            Map<Position, List<String>> top = new EnumMap<>(Position.class);
            for(Position position : SKILL){
                top.put(position, new ArrayList<>());
            }
            for(String sleeperID : board){
                top.get(Player.getPlayerFromSIDV2(sleeperID).position).add(sleeperID);
            }
            for(Position position : SKILL){
                List<String> ids = top.get(position);
                ids.sort((a, b) -> Double.compare(points.getOrDefault(b, 0.0),
                        points.getOrDefault(a, 0.0)));
                if(ids.size() > 8){
                    top.put(position, new ArrayList<>(ids.subList(0, 8)));
                }
            }
            scenario.alive.put(slot.pickNumber(), top);
            return board.get(board.size() - 1);
        }
    }

    Scenario sampleScenario(DraftSimulator.SimState state, long seed){
        ScenarioRecorder recorder = new ScenarioRecorder();
        simulator.simulateFrom(state.copy(), new Random(seed), me, recorder);
        return recorder.scenario;
    }

    /** Best available points at a position at one of my picks in a future. */
    double scenarioBest(Scenario scenario, int epoch, Position position){
        List<String> alive = scenario.alive.getOrDefault(myPicks[epoch], Map.of())
                .getOrDefault(position, List.of());
        return alive.isEmpty() ? 0.0 : points.getOrDefault(alive.get(0), 0.0);
    }

    /** Exact value of playing `sequence` from `fromDecision` inside one
     *  determinized future. */
    double scenarioValue(Scenario scenario, List<String> mineSoFar, int fromDecision,
                         List<Position> sequence){
        List<String> mine = new ArrayList<>(mineSoFar);
        Set<String> taken = new HashSet<>(mineSoFar);
        for(int i = 0; i < sequence.size(); i++){
            List<String> alive = scenario.alive
                    .getOrDefault(myPicks[fromDecision + i], Map.of())
                    .getOrDefault(sequence.get(i), List.of());
            for(String sleeperID : alive){
                if(taken.add(sleeperID)){
                    mine.add(sleeperID);
                    break;
                }
            }
        }
        return StartingLineup.bestNine(mine, points);
    }

    /**
     * Hindsight-family adaptive policy. maxInside=true is classic hindsight
     * optimization (HOP): each sampled future is solved EXACTLY and actions
     * are scored by the average of their per-future optima - no stand-in
     * tail at all, at the price of mild clairvoyance in the comparison.
     * maxInside=false is receding-horizon SAA: sequences are scored by their
     * scenario-average first (max outside), the unbiased committed cousin.
     */
    class HindsightPolicy extends TournamentPolicy {
        final int scenarios;
        final boolean maxInside;
        final long seed;

        HindsightPolicy(int scenarios, boolean maxInside, long seed){
            this.scenarios = scenarios;
            this.maxInside = maxInside;
            this.seed = seed;
        }

        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            int remaining = myPicks.length - decision;
            List<List<Position>> sequences = allSequences(needs.copy(), remaining);
            if(sequences.size() == 1){
                return sequences.get(0).get(0);
            }
            Map<Position, Double> actionTotals = new EnumMap<>(Position.class);
            double[] sequenceTotals = new double[sequences.size()];
            for(int s = 0; s < scenarios; s++){
                Scenario scenario = sampleScenario(state,
                        seed + 101L * decision + 7919L * s);
                Map<Position, Double> bestByFirst = new EnumMap<>(Position.class);
                for(int q = 0; q < sequences.size(); q++){
                    double value = scenarioValue(scenario, mine, decision,
                            sequences.get(q));
                    sequenceTotals[q] += value;
                    bestByFirst.merge(sequences.get(q).get(0), value, Math::max);
                }
                for(Map.Entry<Position, Double> entry : bestByFirst.entrySet()){
                    actionTotals.merge(entry.getKey(), entry.getValue(), Double::sum);
                }
            }
            if(maxInside){
                Position top = null;
                double topValue = -Double.MAX_VALUE;
                for(Map.Entry<Position, Double> entry : actionTotals.entrySet()){
                    if(entry.getValue() > topValue){
                        topValue = entry.getValue();
                        top = entry.getKey();
                    }
                }
                return top;
            }
            int argmax = 0;
            for(int q = 1; q < sequences.size(); q++){
                if(sequenceTotals[q] > sequenceTotals[argmax]){
                    argmax = q;
                }
            }
            return sequences.get(argmax).get(0);
        }
    }

    /**
     * The two-stage stochastic program, solved at every pick: sample futures,
     * and for each candidate action solve the recourse EXACTLY inside each
     * future (availability is known there), then aggregate. lambda selects
     * the objective: 0 = expected value, negative = minimise worst-case
     * regret, positive = mean minus lambda x downside (mean - p25).
     */
    class TwoStage extends TournamentPolicy {
        final int scenarioCount;
        final long seed;
        final double lambda;

        TwoStage(int scenarioCount, long seed, double lambda){
            this.scenarioCount = scenarioCount;
            this.seed = seed;
            this.lambda = lambda;
        }

        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            int remaining = myPicks.length - decision;
            List<Position> open = needs.feasibleSkill();
            if(open.size() == 1 || remaining == 0){
                return open.get(0);
            }
            List<Scenario> futures = new ArrayList<>();
            for(int s = 0; s < scenarioCount; s++){
                futures.add(sampleScenario(state, seed + 101L * decision + 7919L * s));
            }
            // per action: its exactly-solved value in each future
            Map<Position, double[]> perAction = new EnumMap<>(Position.class);
            for(Position action : open){
                Needs after = needs.copy();
                after.consume(action);
                List<List<Position>> completions = allSequences(after, remaining - 1);
                double[] values = new double[futures.size()];
                for(int f = 0; f < futures.size(); f++){
                    double best = -Double.MAX_VALUE;
                    for(List<Position> completion : completions){
                        List<Position> full = new ArrayList<>();
                        full.add(action);
                        full.addAll(completion);
                        best = Math.max(best, scenarioValue(futures.get(f), mine,
                                decision, full));
                    }
                    values[f] = best;
                }
                perAction.put(action, values);
            }
            Position top = null;
            double topScore = -Double.MAX_VALUE;
            for(Position action : open){
                double[] values = perAction.get(action);
                double score;
                if(lambda < 0){
                    // minimise the worst regret against the best action per future
                    double worst = 0;
                    for(int f = 0; f < values.length; f++){
                        double bestHere = -Double.MAX_VALUE;
                        for(Position other : open){
                            bestHere = Math.max(bestHere, perAction.get(other)[f]);
                        }
                        worst = Math.max(worst, bestHere - values[f]);
                    }
                    score = -worst;
                }
                else {
                    double mean = 0;
                    for(double value : values){
                        mean += value;
                    }
                    mean /= values.length;
                    double[] sorted = values.clone();
                    java.util.Arrays.sort(sorted);
                    double downside = mean - sorted[sorted.length / 4];
                    score = mean - lambda * downside;
                }
                if(score > topScore){
                    topScore = score;
                    top = action;
                }
            }
            return top;
        }
    }

    /** SAA-committed: full sequences scored by their mean exact value over
     *  scenarios sampled from the draft's start - the distribution-aware DP,
     *  scenario form. */
    List<Position> saaPlan(int scenarioCount){
        List<Scenario> futures = new ArrayList<>();
        for(int s = 0; s < scenarioCount; s++){
            futures.add(sampleScenario(simulator.initialState(),
                    TRAIN_SEED + 31_000_000L + 7919L * s));
        }
        Needs start = Needs.afterKeepers(myKeeperIDs);
        List<List<Position>> sequences = allSequences(start, myPicks.length);
        List<String> keepers = new ArrayList<>(myKeeperIDs);
        double[] means = IntStream.range(0, sequences.size()).parallel()
                .mapToDouble(q -> {
                    double total = 0;
                    for(Scenario scenario : futures){
                        total += scenarioValue(scenario, keepers, 0, sequences.get(q));
                    }
                    return total / futures.size();
                }).toArray();
        int argmax = 0;
        for(int q = 1; q < sequences.size(); q++){
            if(means[q] > means[argmax]){
                argmax = q;
            }
        }
        return sequences.get(argmax);
    }

    /** Expert iteration: distill the lookahead, then look ahead WITH the
     *  distilled tails, and distill again - policy iteration toward the
     *  fixed point, each cycle provably no worse in expectation. */
    BoostedRegressor trainExit(int iterations, int episodes, int inner){
        BoostedRegressor model = null;
        for(int iteration = 0; iteration < iterations; iteration++){
            model = trainImitation(episodes, inner, model);
        }
        return model;
    }

    /**
     * The adaptive family: at each of my picks, enumerate feasible position
     * heads `depth` deep, value each head by `inner` completions of the live
     * state (my remaining picks played head-then-tail), take the winning
     * head's first position. RANDOM tails are Justin's 2022-23 design -
     * unbiased about timing; RAW is the modern-but-QB-poisoned stand-in;
     * VORP is competent AND unbiased, the combo Justin proposed. Inner seeds
     * are common across heads (paired), disjoint from the rollout's stream.
     */
    class Lookahead extends TournamentPolicy {
        final int depth;
        final int inner;
        final Tail tail;
        final long seed;
        final BoostedRegressor tailModel;

        Lookahead(int depth, int inner, Tail tail, long seed){
            this(depth, inner, tail, seed, null);
        }

        Lookahead(int depth, int inner, Tail tail, long seed, BoostedRegressor tailModel){
            this.depth = depth;
            this.inner = inner;
            this.tail = tail;
            this.seed = seed;
            this.tailModel = tailModel;
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
                    HeadThenTail completion = new HeadThenTail(head, tail, needs, mine,
                            new Random(innerSeed ^ 0x5DEECE66DL), tailModel);
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

    /** Inner completion: follow the head, then the chosen tail stand-in. */
    class HeadThenTail extends TournamentPolicy {
        final List<Position> head;
        final Tail tail;
        final Random random;
        final BoostedRegressor tailModel;

        HeadThenTail(List<Position> head, Tail tail, Needs outerNeeds,
                     List<String> outerMine, Random random){
            this(head, tail, outerNeeds, outerMine, random, null);
        }

        HeadThenTail(List<Position> head, Tail tail, Needs outerNeeds,
                     List<String> outerMine, Random random, BoostedRegressor tailModel){
            this.head = head;
            this.tail = tail;
            this.random = random;
            this.tailModel = tailModel;
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
            switch(tail){
                case RAW:
                    return bestRawPosition(needs, board);
                case VORP:
                    return bestVorpPosition(needs, board, slot.pickNumber());
                case MODEL:
                    Position top = null;
                    double topValue = -Double.MAX_VALUE;
                    for(Position position : needs.feasibleSkill()){
                        double value = tailModel.predict(mlFeatures(this, board,
                                slot.pickNumber(), position));
                        if(value > topValue){
                            topValue = value;
                            top = position;
                        }
                    }
                    return top;
                default:
                    List<Position> open = needs.feasibleSkill();
                    return open.get(random.nextInt(open.size()));
            }
        }
    }

    /**
     * Justin's structured tail (2026-08-26): commit ONLY the treacherous
     * dimensions - which of my decisions takes the QB (if one is owed) and
     * which the dedicated TE - and let every other round choose RB-vs-WR
     * live by VORP. The head space is tiny (at most decisions^2), the QB
     * round is priced globally instead of slid to locally, and the smooth
     * dimension stays adaptive. qbAt/teAt are decision indices; -1 = no QB
     * owed. Extra TEs beyond the dedicated one are not explored - the flexes
     * stay RB/WR.
     */
    class TimingCommitted extends TournamentPolicy {
        final int qbAt;
        final int teAt;

        TimingCommitted(int qbAt, int teAt){
            this.qbAt = qbAt;
            this.teAt = teAt;
        }

        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            if(decision == qbAt){
                return Position.QB;
            }
            if(decision == teAt){
                return Position.TE;
            }
            // Every unreserved round is RB-vs-WR, decided live by VORP; the
            // counts work out exactly (unreserved rounds = RB+WR+flex owed).
            Map<Position, String> best = bestByPosition(board, points);
            int next = nextPickAfter(slot.pickNumber());
            Position top = null;
            double topValue = -Double.MAX_VALUE;
            for(Position position : new Position[]{Position.RB, Position.WR}){
                String candidate = best.get(position);
                if(!needs.feasible(position) || candidate == null){
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
            return top != null ? top : needs.feasibleSkill().get(0);
        }
    }

    // ---- the learned strategies ----

    static final long TRAIN_SEED = DraftSimulator.SEED + 44_000_000L;
    static final int ML_FEATURES = 11;

    /**
     * One row per (state, candidate position) for the learned policies:
     * where we are, what we still owe, what the board offers at the position
     * now, and what waiting would return.
     */
    double[] mlFeatures(TournamentPolicy self, List<String> board, int pickNumber,
                        Position position){
        Map<Position, String> best = bestByPosition(board, points);
        String candidate = best.get(position);
        double available = candidate == null ? 0 : points.getOrDefault(candidate, 0.0);
        int next = nextPickAfter(pickNumber);
        double waiting = next < 0 ? 0.0
                : waitingTable.getOrDefault(next, Map.of()).getOrDefault(position, 0.0);
        double bestFeasible = 0;
        for(Position open : self.needs.feasibleSkill()){
            String other = best.get(open);
            if(other != null){
                bestFeasible = Math.max(bestFeasible, points.getOrDefault(other, 0.0));
            }
        }
        double rosterTotal = 0;
        for(String sleeperID : self.mine){
            rosterTotal += points.getOrDefault(sleeperID, 0.0);
        }
        return new double[]{
                self.decision / (double) myPicks.length,
                self.needs.dedicated.getOrDefault(position, 0),
                self.needs.flex,
                available / 100.0,
                (available - waiting) / 10.0,
                rosterTotal / 100.0,
                position == Position.QB ? 1 : 0,
                position == Position.RB ? 1 : 0,
                position == Position.WR ? 1 : 0,
                position == Position.TE ? 1 : 0,
                (available - bestFeasible) / 100.0};
    }

    /** Acts greedily on a learned (state, position) -> score model. */
    class ModelPolicy extends TournamentPolicy {
        final BoostedRegressor model;

        ModelPolicy(BoostedRegressor model){
            this.model = model;
        }

        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            Position top = null;
            double topValue = -Double.MAX_VALUE;
            for(Position position : needs.feasibleSkill()){
                double value = model.predict(mlFeatures(this, board,
                        slot.pickNumber(), position));
                if(value > topValue){
                    topValue = value;
                    top = position;
                }
            }
            return top;
        }
    }

    /** Linear-softmax policy over the same features (REINFORCE's actor). */
    class SoftmaxPolicy extends TournamentPolicy {
        final double[] weights;
        final Random random;   // null = act greedily (argmax score)
        double[][] chosenFeatures;
        double[][][] optionFeatures;
        double[][] optionProbabilities;

        SoftmaxPolicy(double[] weights, Random random, boolean record){
            this.weights = weights;
            this.random = random;
            if(record){
                chosenFeatures = new double[myPicks.length][];
                optionFeatures = new double[myPicks.length][][];
                optionProbabilities = new double[myPicks.length][];
            }
        }

        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            List<Position> open = needs.feasibleSkill();
            double[][] rows = new double[open.size()][];
            double[] scores = new double[open.size()];
            double max = -Double.MAX_VALUE;
            for(int a = 0; a < open.size(); a++){
                rows[a] = mlFeatures(this, board, slot.pickNumber(), open.get(a));
                for(int f = 0; f < ML_FEATURES; f++){
                    scores[a] += weights[f] * rows[a][f];
                }
                max = Math.max(max, scores[a]);
            }
            double total = 0;
            double[] probabilities = new double[open.size()];
            for(int a = 0; a < open.size(); a++){
                probabilities[a] = Math.exp(scores[a] - max);
                total += probabilities[a];
            }
            int chosen = 0;
            if(random == null){
                for(int a = 1; a < open.size(); a++){
                    if(probabilities[a] > probabilities[chosen]){
                        chosen = a;
                    }
                }
            }
            else {
                double u = random.nextDouble() * total;
                double cumulative = 0;
                for(int a = 0; a < open.size(); a++){
                    cumulative += probabilities[a];
                    if(u < cumulative){
                        chosen = a;
                        break;
                    }
                    chosen = a;
                }
            }
            if(chosenFeatures != null && decision < chosenFeatures.length){
                chosenFeatures[decision] = rows[chosen];
                optionFeatures[decision] = rows;
                double[] normalized = new double[open.size()];
                for(int a = 0; a < open.size(); a++){
                    normalized[a] = probabilities[a] / total;
                }
                optionProbabilities[decision] = normalized;
            }
            return open.get(chosen);
        }
    }

    /** Behavior policy for fitted-Q data: explore around a guide policy. */
    class ExploringPolicy extends TournamentPolicy {
        final BoostedRegressor guide;   // null = guide by VORP
        final Random random;
        final double epsilon;
        final List<double[]> visited = new ArrayList<>();

        ExploringPolicy(BoostedRegressor guide, Random random, double epsilon){
            this.guide = guide;
            this.random = random;
            this.epsilon = epsilon;
        }

        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            List<Position> open = needs.feasibleSkill();
            Position chosen;
            if(random.nextDouble() < epsilon){
                chosen = open.get(random.nextInt(open.size()));
            }
            else if(guide == null){
                chosen = bestVorpPosition(needs, board, slot.pickNumber());
            }
            else {
                chosen = null;
                double topValue = -Double.MAX_VALUE;
                for(Position position : open){
                    double value = guide.predict(mlFeatures(this, board,
                            slot.pickNumber(), position));
                    if(value > topValue){
                        topValue = value;
                        chosen = position;
                    }
                }
            }
            visited.add(mlFeatures(this, board, slot.pickNumber(), chosen));
            return chosen;
        }
    }

    /**
     * Fitted Q: Monte-Carlo returns of an exploratory policy regressed onto
     * (state, action) features, then act greedily - one approximate policy
     * improvement step per iteration, the second iteration exploring around
     * the first iteration's greedy policy.
     */
    BoostedRegressor trainFittedQ(int episodesPerIteration, int iterations){
        BoostedRegressor model = null;
        for(int iteration = 0; iteration < iterations; iteration++){
            BoostedRegressor guide = model;
            long iterationBase = TRAIN_SEED + 1_000_000L * iteration;
            List<ExploringPolicy> episodes =
                    IntStream.range(0, episodesPerIteration).parallel().mapToObj(episode -> {
                        long seed = iterationBase + 7919L * episode;
                        ExploringPolicy policy = new ExploringPolicy(guide,
                                new Random(seed ^ 0x9E3779B9L), 0.35);
                        simulator.simulateOnce(new Random(seed), me, policy);
                        return policy;
                    }).toList();
            List<double[]> rows = new ArrayList<>();
            List<Double> targets = new ArrayList<>();
            for(ExploringPolicy policy : episodes){
                double score = policy.score();
                for(double[] row : policy.visited){
                    rows.add(row);
                    targets.add(score);
                }
            }
            double[][] rowArray = rows.toArray(new double[0][]);
            double[] targetArray = new double[targets.size()];
            for(int i = 0; i < targetArray.length; i++){
                targetArray[i] = targets.get(i);
            }
            model = BoostedRegressor.fit(rowArray, targetArray, 120, 3, 0.15);
        }
        return model;
    }

    /**
     * Imitation: distill a slow, strong teacher - Justin's oldschool-2 with
     * VORP tails - into a per-position score model that answers in O(trees).
     * The draft-night thesis: keep the lookahead's judgment, lose its clock.
     */
    BoostedRegressor trainImitation(int episodes, int inner){
        return trainImitation(episodes, inner, null);
    }

    /** With a tailModel, the teacher looks ahead using the PREVIOUS distilled
     *  policy as its tails - the expert-iteration cycle. */
    BoostedRegressor trainImitation(int episodes, int inner, BoostedRegressor tailModel){
        List<List<double[]>> perEpisodeRows = IntStream.range(0, episodes).parallel()
                .mapToObj(episode -> {
                    long seed = TRAIN_SEED + 5_000_000L + 7919L * episode;
                    List<double[]> recorded = new ArrayList<>();
                    Lookahead teacher = new Lookahead(2, inner,
                            tailModel == null ? Tail.VORP : Tail.MODEL,
                            seed ^ 0x7F4A7C15L, tailModel){
                        @Override
                        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                                              DraftSimulator.SimState state){
                            Position chosen = super.pickPosition(board, slot, state);
                            for(Position position : needs.feasibleSkill()){
                                double[] row = mlFeatures(this, board,
                                        slot.pickNumber(), position);
                                double[] labeled = new double[row.length + 1];
                                System.arraycopy(row, 0, labeled, 0, row.length);
                                labeled[row.length] = position == chosen ? 1.0 : 0.0;
                                recorded.add(labeled);
                            }
                            return chosen;
                        }
                    };
                    simulator.simulateOnce(new Random(seed), me, teacher);
                    return recorded;
                }).toList();
        List<double[]> rows = new ArrayList<>();
        List<Double> targets = new ArrayList<>();
        for(List<double[]> episodeRows : perEpisodeRows){
            for(double[] labeled : episodeRows){
                rows.add(java.util.Arrays.copyOf(labeled, ML_FEATURES));
                targets.add(labeled[ML_FEATURES]);
            }
        }
        double[][] rowArray = rows.toArray(new double[0][]);
        double[] targetArray = new double[targets.size()];
        for(int i = 0; i < targetArray.length; i++){
            targetArray[i] = targets.get(i);
        }
        return BoostedRegressor.fit(rowArray, targetArray, 120, 3, 0.15);
    }

    /** REINFORCE with a running baseline on the linear-softmax actor. */
    double[] trainReinforce(int episodes){
        double[] weights = new double[ML_FEATURES];
        double baseline = 0;
        boolean baselineSet = false;
        for(int episode = 0; episode < episodes; episode++){
            long seed = TRAIN_SEED + 9_000_000L + 7919L * episode;
            SoftmaxPolicy policy = new SoftmaxPolicy(weights,
                    new Random(seed ^ 0x2545F491L), true);
            simulator.simulateOnce(new Random(seed), me, policy);
            double score = policy.score();
            if(!baselineSet){
                baseline = score;
                baselineSet = true;
            }
            double advantage = score - baseline;
            baseline += 0.02 * (score - baseline);
            double step = 0.02 / (1.0 + episode / 800.0);
            for(int d = 0; d < policy.chosenFeatures.length; d++){
                if(policy.chosenFeatures[d] == null){
                    continue;
                }
                double[][] options = policy.optionFeatures[d];
                double[] probabilities = policy.optionProbabilities[d];
                for(int f = 0; f < ML_FEATURES; f++){
                    double expected = 0;
                    for(int a = 0; a < options.length; a++){
                        expected += probabilities[a] * options[a][f];
                    }
                    weights[f] += step * advantage
                            * (policy.chosenFeatures[d][f] - expected);
                }
            }
        }
        return weights;
    }

    /**
     * Cross-entropy method over committed sequences: sample legal sequences
     * from a per-decision position distribution, score on CRN rollouts, refit
     * the distribution to the elite - a learned search, judged like any other
     * committed plan on the fresh eval stream.
     */
    List<Position> trainCem(int iterations, int population, int elite, int rollouts){
        double[][] probabilities = new double[myPicks.length][SKILL.length];
        for(double[] row : probabilities){
            java.util.Arrays.fill(row, 1.0 / SKILL.length);
        }
        Random random = new Random(TRAIN_SEED + 13_000_000L);
        List<Position> bestSequence = null;
        double bestMean = -Double.MAX_VALUE;
        for(int iteration = 0; iteration < iterations; iteration++){
            List<List<Position>> samples = new ArrayList<>();
            for(int s = 0; s < population; s++){
                samples.add(sampleSequence(probabilities, random));
            }
            double[] means = samples.parallelStream()
                    .mapToDouble(sequence -> searchMean(sequence, rollouts)).toArray();
            Integer[] order = new Integer[population];
            for(int s = 0; s < population; s++){
                order[s] = s;
            }
            java.util.Arrays.sort(order, (a, b) -> Double.compare(means[b], means[a]));
            if(means[order[0]] > bestMean){
                bestMean = means[order[0]];
                bestSequence = samples.get(order[0]);
            }
            double[][] refit = new double[myPicks.length][SKILL.length];
            for(int e = 0; e < elite; e++){
                List<Position> sequence = samples.get(order[e]);
                for(int d = 0; d < sequence.size(); d++){
                    refit[d][sequence.get(d).ordinal()]++;
                }
            }
            for(int d = 0; d < myPicks.length; d++){
                for(int p = 0; p < SKILL.length; p++){
                    probabilities[d][p] = 0.4 * probabilities[d][p]
                            + 0.6 * (refit[d][p] + 0.5) / (elite + 0.5 * SKILL.length);
                }
            }
        }
        return bestSequence;
    }

    /** Population hill-climb over sequences: swap mutations plus fresh blood. */
    List<Position> trainEvolution(int generations, int population, int rollouts){
        Random random = new Random(TRAIN_SEED + 17_000_000L);
        List<List<Position>> pool = new ArrayList<>();
        Needs start = Needs.afterKeepers(myKeeperIDs);
        for(int s = 0; s < population; s++){
            pool.add(randomSequence(start.copy(), random));
        }
        List<Position> best = null;
        double bestMean = -Double.MAX_VALUE;
        for(int generation = 0; generation < generations; generation++){
            List<List<Position>> generationPool = pool;
            double[] means = generationPool.parallelStream()
                    .mapToDouble(sequence -> searchMean(sequence, rollouts)).toArray();
            Integer[] order = new Integer[generationPool.size()];
            for(int s = 0; s < order.length; s++){
                order[s] = s;
            }
            java.util.Arrays.sort(order, (a, b) -> Double.compare(means[b], means[a]));
            if(means[order[0]] > bestMean){
                bestMean = means[order[0]];
                best = generationPool.get(order[0]);
            }
            List<List<Position>> next = new ArrayList<>();
            for(int keep = 0; keep < population / 3; keep++){
                next.add(generationPool.get(order[keep]));
            }
            while(next.size() < population){
                if(random.nextDouble() < 0.15){
                    next.add(randomSequence(start.copy(), random));
                }
                else {
                    List<Position> parent = next.get(random.nextInt(population / 3));
                    List<Position> child = new ArrayList<>(parent);
                    int a = random.nextInt(child.size());
                    int b = random.nextInt(child.size());
                    Position swap = child.get(a);
                    child.set(a, child.get(b));
                    child.set(b, swap);
                    next.add(child);
                }
            }
            pool = next;
        }
        return best;
    }

    private List<Position> sampleSequence(double[][] probabilities, Random random){
        Needs needs = Needs.afterKeepers(myKeeperIDs);
        List<Position> sequence = new ArrayList<>();
        for(int d = 0; d < myPicks.length; d++){
            List<Position> open = needs.feasibleSkill();
            double total = 0;
            for(Position position : open){
                total += probabilities[d][position.ordinal()];
            }
            double u = random.nextDouble() * total;
            Position chosen = open.get(open.size() - 1);
            double cumulative = 0;
            for(Position position : open){
                cumulative += probabilities[d][position.ordinal()];
                if(u < cumulative){
                    chosen = position;
                    break;
                }
            }
            needs.consume(chosen);
            sequence.add(chosen);
        }
        return sequence;
    }

    private List<Position> randomSequence(Needs needs, Random random){
        List<Position> sequence = new ArrayList<>();
        while(sequence.size() < myPicks.length){
            List<Position> open = needs.feasibleSkill();
            Position chosen = open.get(random.nextInt(open.size()));
            needs.consume(chosen);
            sequence.add(chosen);
        }
        return sequence;
    }

    // ---- the exact family: DP, CE values, branch-and-bound screening ----

    /**
     * Certainty-equivalent value of a committed sequence: my k-th pick at a
     * position is worth the mean k-th-best available at that pick number.
     * The exact family optimizes THIS objective - fast and valley-proof,
     * blind to availability correlations. The lab's rollout ground truth
     * measures exactly how much that blindness costs.
     */
    double ceValue(List<Position> sequence){
        Map<Position, Integer> taken = new EnumMap<>(Position.class);
        double total = 0;
        for(int d = 0; d < sequence.size(); d++){
            Position position = sequence.get(d);
            int k = taken.merge(position, 1, Integer::sum) - 1;
            double[] depth = depthTable.get(myPicks[d]).get(position);
            total += depth[Math.min(k, DEPTH_K - 1)];
        }
        return total;
    }

    /**
     * Exact dynamic program over (decision, positions-taken) under the CE
     * objective - global in every timing dimension at once, so no valley can
     * exist for it. Returns the argmax committed sequence.
     */
    List<Position> dpPlan(){
        Needs start = Needs.afterKeepers(myKeeperIDs);
        Map<Long, double[]> memo = new HashMap<>();
        int[] counts = new int[SKILL.length];
        dpValue(0, counts, start, memo);
        List<Position> sequence = new ArrayList<>();
        Needs needs = start.copy();
        for(int d = 0; d < myPicks.length; d++){
            double[] entry = memo.get(dpKey(d, counts));
            Position chosen = SKILL[(int) entry[1]];
            sequence.add(chosen);
            counts[(int) entry[1]]++;
            needs.consume(chosen);
        }
        return sequence;
    }

    private long dpKey(int decision, int[] counts){
        long key = decision;
        for(int count : counts){
            key = key * 16 + count;
        }
        return key;
    }

    private double dpValue(int decision, int[] counts, Needs needs, Map<Long, double[]> memo){
        if(decision == myPicks.length){
            return 0;
        }
        long key = dpKey(decision, counts);
        double[] cached = memo.get(key);
        if(cached != null){
            return cached[0];
        }
        double best = -Double.MAX_VALUE;
        int bestIndex = 0;
        for(int p = 0; p < SKILL.length; p++){
            Position position = SKILL[p];
            if(!needs.feasible(position)){
                continue;
            }
            double[] depth = depthTable.get(myPicks[decision]).get(position);
            double now = depth[Math.min(counts[p], DEPTH_K - 1)];
            Needs next = needs.copy();
            next.consume(position);
            counts[p]++;
            double value = now + dpValue(decision + 1, counts, next, memo);
            counts[p]--;
            if(value > best){
                best = value;
                bestIndex = p;
            }
        }
        memo.put(key, new double[]{best, bestIndex});
        return best;
    }

    /**
     * Branch-and-bound screening of the rollout search: evaluate sequences
     * in descending CE-value order, stop once the next CE value (plus the
     * measured CE-vs-rollout calibration slack) cannot beat the incumbent
     * rollout mean. In the lab the exhaustive truth grades it: regret and
     * fraction pruned are both printed, not assumed.
     */
    int[] bnbScreen(List<List<Position>> sequences, double[] rolloutMeans, int argmaxTruth){
        Integer[] order = new Integer[sequences.size()];
        double[] bounds = new double[sequences.size()];
        for(int s = 0; s < sequences.size(); s++){
            order[s] = s;
            bounds[s] = ceValue(sequences.get(s));
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(bounds[b], bounds[a]));
        double slack = 0;
        for(int s = 0; s < sequences.size(); s++){
            slack = Math.max(slack, rolloutMeans[s] - bounds[s]);
        }
        double incumbent = -Double.MAX_VALUE;
        int evaluated = 0;
        int found = -1;
        for(Integer s : order){
            if(bounds[s] + slack <= incumbent){
                break;
            }
            evaluated++;
            if(rolloutMeans[s] > incumbent){
                incumbent = rolloutMeans[s];
                found = s;
            }
        }
        return new int[]{evaluated, found, found == argmaxTruth ? 1 : 0};
    }

    // ---- fancier metaheuristics: annealing and NRPA ----

    /** Simulated annealing over committed sequences, swap moves, CRN scores. */
    List<Position> saPlan(int steps, int rollouts){
        Random random = new Random(TRAIN_SEED + 23_000_000L);
        Needs start = Needs.afterKeepers(myKeeperIDs);
        List<Position> current = randomSequence(start.copy(), random);
        double currentScore = searchMean(current, rollouts);
        List<Position> best = current;
        double bestScore = currentScore;
        for(int step = 0; step < steps; step++){
            double temperature = 8.0 * Math.pow(0.995, step);
            List<Position> candidate = new ArrayList<>(current);
            if(random.nextDouble() < 0.2){
                candidate = randomSequence(start.copy(), random);
            }
            else {
                int a = random.nextInt(candidate.size());
                int b = random.nextInt(candidate.size());
                Position swap = candidate.get(a);
                candidate.set(a, candidate.get(b));
                candidate.set(b, swap);
            }
            double score = searchMean(candidate, rollouts);
            if(score > currentScore
                    || random.nextDouble() < Math.exp((score - currentScore) / temperature)){
                current = candidate;
                currentScore = score;
            }
            if(score > bestScore){
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Nested Rollout Policy Adaptation (Cazenave): a softmax policy over
     * (decision, position) is adapted toward the best sequence found, nested
     * one level. Scores are CRN means, so the stochastic game looks
     * deterministic to the adaptation.
     */
    List<Position> nrpaPlan(int iterations, int playouts, int rollouts){
        double[][] weights = new double[myPicks.length][SKILL.length];
        Random random = new Random(TRAIN_SEED + 29_000_000L);
        List<Position> best = null;
        double bestScore = -Double.MAX_VALUE;
        for(int iteration = 0; iteration < iterations; iteration++){
            List<Position> iterationBest = null;
            double iterationScore = -Double.MAX_VALUE;
            for(int playout = 0; playout < playouts; playout++){
                List<Position> sequence = softmaxSequence(weights, random);
                double score = searchMean(sequence, rollouts);
                if(score > iterationScore){
                    iterationScore = score;
                    iterationBest = sequence;
                }
            }
            if(iterationScore > bestScore){
                bestScore = iterationScore;
                best = iterationBest;
            }
            // adapt toward the best sequence seen this iteration
            Needs needs = Needs.afterKeepers(myKeeperIDs);
            for(int d = 0; d < best.size(); d++){
                List<Position> open = needs.feasibleSkill();
                double total = 0;
                double[] probabilities = new double[SKILL.length];
                for(Position position : open){
                    probabilities[indexOf(position)] =
                            Math.exp(weights[d][indexOf(position)]);
                    total += probabilities[indexOf(position)];
                }
                for(Position position : open){
                    int p = indexOf(position);
                    weights[d][p] -= probabilities[p] / total;
                }
                weights[d][indexOf(best.get(d))] += 1.0;
                needs.consume(best.get(d));
            }
        }
        return best;
    }

    private List<Position> softmaxSequence(double[][] weights, Random random){
        Needs needs = Needs.afterKeepers(myKeeperIDs);
        List<Position> sequence = new ArrayList<>();
        for(int d = 0; d < myPicks.length; d++){
            List<Position> open = needs.feasibleSkill();
            double total = 0;
            double[] cumulative = new double[open.size()];
            for(int a = 0; a < open.size(); a++){
                total += Math.exp(weights[d][indexOf(open.get(a))]);
                cumulative[a] = total;
            }
            double u = random.nextDouble() * total;
            Position chosen = open.get(open.size() - 1);
            for(int a = 0; a < open.size(); a++){
                if(u < cumulative[a]){
                    chosen = open.get(a);
                    break;
                }
            }
            needs.consume(chosen);
            sequence.add(chosen);
        }
        return sequence;
    }

    private static int indexOf(Position position){
        for(int p = 0; p < SKILL.length; p++){
            if(SKILL[p] == position){
                return p;
            }
        }
        throw new IllegalArgumentException(position.name());
    }

    /**
     * MCTS over my remaining position choices (UCT): the tree is the
     * sequence prefix tree, a simulation walks it by UCB1, completes the
     * prefix with a greedy-raw tail rollout from the LIVE state, and
     * backpropagates the score. Same budget as the flat lookahead, spent
     * asymmetrically - dominated lines die after a few visits, contested
     * lines get the depth. Adaptive: re-run at every one of my picks.
     */
    class MctsPolicy extends TournamentPolicy {
        final int budget;
        final long seed;

        MctsPolicy(int budget, long seed){
            this.budget = budget;
            this.seed = seed;
        }

        final class Node {
            final Map<Position, Node> children = new EnumMap<>(Position.class);
            int visits;
            double total;
        }

        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            List<Position> open = needs.feasibleSkill();
            if(open.size() == 1){
                return open.get(0);
            }
            Node root = new Node();
            for(int simulation = 0; simulation < budget; simulation++){
                long innerSeed = seed + 101L * decision + 7919L * simulation;
                List<Position> prefix = new ArrayList<>();
                Needs walk = needs.copy();
                Node node = root;
                while(true){
                    List<Position> walkOpen = walk.feasibleSkill();
                    if(walkOpen.isEmpty()
                            || prefix.size() + decision >= myPicks.length){
                        break;
                    }
                    Position pick = null;
                    double bestUcb = -Double.MAX_VALUE;
                    for(Position option : walkOpen){
                        Node child = node.children.get(option);
                        double ucb = child == null || child.visits == 0
                                ? Double.MAX_VALUE
                                : child.total / child.visits + 25.0 * Math.sqrt(
                                        Math.log(node.visits + 1) / child.visits);
                        if(ucb > bestUcb){
                            bestUcb = ucb;
                            pick = option;
                        }
                    }
                    prefix.add(pick);
                    walk.consume(pick);
                    Node child = node.children.computeIfAbsent(pick, u -> new Node());
                    boolean expand = child.visits == 0;
                    node = child;
                    if(expand){
                        break;
                    }
                }
                HeadThenTail completion = new HeadThenTail(prefix, Tail.VORP, needs, mine,
                        new Random(innerSeed ^ 0x5DEECE66DL));
                DraftSimulator.SimState branch = state.copy();
                simulator.simulateFrom(branch, new Random(innerSeed), me, completion);
                double score = completion.score();
                Node backprop = root;
                backprop.visits++;
                backprop.total += score;
                for(Position step : prefix){
                    backprop = backprop.children.get(step);
                    backprop.visits++;
                    backprop.total += score;
                }
            }
            Position top = open.get(0);
            int topVisits = -1;
            for(Position option : open){
                Node child = root.children.get(option);
                int visits = child == null ? 0 : child.visits;
                if(visits > topVisits){
                    topVisits = visits;
                    top = option;
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

    /** Mean k-th-best available points per position at each of my picks. */
    void fillWaitingTable(int trials){
        Map<Integer, Map<Position, double[]>> sums = new HashMap<>();
        for(int pick : myPicks){
            Map<Position, double[]> perPosition = new EnumMap<>(Position.class);
            for(Position position : SKILL){
                perPosition.put(position, new double[DEPTH_K]);
            }
            sums.put(pick, perPosition);
        }
        Random random = new Random(SEARCH_SEED + 9_000_000L);
        Set<String> onBoard = simulator.players();
        for(int trial = 0; trial < trials; trial++){
            Map<String, Integer> takenAt = simulator.simulateOnce(random);
            for(int pick : myPicks){
                Map<Position, List<Double>> alive = new EnumMap<>(Position.class);
                for(Position position : SKILL){
                    alive.put(position, new ArrayList<>());
                }
                for(String sleeperID : onBoard){
                    if(takenAt.getOrDefault(sleeperID, Integer.MAX_VALUE) < pick){
                        continue;
                    }
                    alive.get(Player.getPlayerFromSIDV2(sleeperID).position)
                            .add(points.getOrDefault(sleeperID, 0.0));
                }
                for(Position position : SKILL){
                    List<Double> values = alive.get(position);
                    values.sort(java.util.Collections.reverseOrder());
                    double[] sum = sums.get(pick).get(position);
                    for(int k = 0; k < DEPTH_K && k < values.size(); k++){
                        sum[k] += values.get(k);
                    }
                }
            }
        }
        for(int pick : myPicks){
            Map<Position, Double> row = new EnumMap<>(Position.class);
            Map<Position, double[]> depth = new EnumMap<>(Position.class);
            for(Position position : SKILL){
                double[] sum = sums.get(pick).get(position);
                double[] means = new double[DEPTH_K];
                for(int k = 0; k < DEPTH_K; k++){
                    means[k] = sum[k] / trials;
                }
                row.put(position, means[0]);
                depth.put(position, means);
            }
            waitingTable.put(pick, row);
            depthTable.put(pick, depth);
        }

        // Pace expectations: how many of each position are usually gone by
        // each of my picks, and the board's starting counts per position.
        for(Position position : SKILL){
            initialCounts.put(position, 0);
        }
        for(String sleeperID : onBoard){
            initialCounts.merge(Player.getPlayerFromSIDV2(sleeperID).position, 1,
                    Integer::sum);
        }
        Random paceRandom = new Random(SEARCH_SEED + 10_000_000L);
        Map<Integer, Map<Position, Double>> goneSums = new HashMap<>();
        int paceTrials = Math.min(trials, 150);
        for(int trial = 0; trial < paceTrials; trial++){
            Map<String, Integer> takenAt = simulator.simulateOnce(paceRandom);
            for(int pick : myPicks){
                Map<Position, Double> gone = goneSums.computeIfAbsent(pick,
                        u -> new EnumMap<>(Position.class));
                for(Map.Entry<String, Integer> entry : takenAt.entrySet()){
                    if(entry.getValue() < pick && onBoard.contains(entry.getKey())){
                        gone.merge(Player.getPlayerFromSIDV2(entry.getKey()).position,
                                1.0, Double::sum);
                    }
                }
            }
        }
        for(int pick : myPicks){
            Map<Position, Double> row = new EnumMap<>(Position.class);
            for(Position position : SKILL){
                row.put(position, goneSums.get(pick).getOrDefault(position, 0.0)
                        / paceTrials);
            }
            expectedGone.put(pick, row);
        }
    }

    /**
     * The online-inference corrector (algorithm 5, v1): plays VORP off the
     * base beliefs, but measures the LIVE board's depletion pace against the
     * base expectation and shifts each position's waiting value down the
     * depth table by the excess. Knows nothing about the true world - it
     * reads the world off the board, which is exactly what draft night
     * allows.
     */
    class PaceVorp extends TournamentPolicy {
        @Override
        Position pickPosition(List<String> board, DraftSimulator.Slot slot,
                              DraftSimulator.SimState state){
            Map<Position, String> best = bestByPosition(board, points);
            Map<Position, Integer> remaining = new EnumMap<>(Position.class);
            for(String sleeperID : board){
                remaining.merge(Player.getPlayerFromSIDV2(sleeperID).position, 1,
                        Integer::sum);
            }
            int next = nextPickAfter(slot.pickNumber());
            Position top = null;
            double topValue = -Double.MAX_VALUE;
            for(Position position : needs.feasibleSkill()){
                String candidate = best.get(position);
                if(candidate == null){
                    continue;
                }
                double replacement = 0;
                if(next > 0){
                    double gone = initialCounts.getOrDefault(position, 0)
                            - remaining.getOrDefault(position, 0);
                    double expected = expectedGone
                            .getOrDefault(slot.pickNumber(), Map.of())
                            .getOrDefault(position, 0.0);
                    int excess = (int) Math.round(Math.max(0, gone - expected));
                    double[] depth = depthTable.getOrDefault(next, Map.of())
                            .get(position);
                    replacement = depth == null ? 0
                            : depth[Math.min(excess, DEPTH_K - 1)];
                }
                double value = points.getOrDefault(candidate, 0.0) - replacement;
                if(value > topValue){
                    topValue = value;
                    top = position;
                }
            }
            return top;
        }
    }

    /** Evaluate a policy bound to THIS tournament's beliefs inside another
     *  world's simulator - the mismatch protocol: plan with your model, live
     *  in the true one. */
    double[] evaluateIn(PolicyTournament trueWorld, Factory factory, int trials){
        return IntStream.range(0, trials).parallel().mapToDouble(r -> {
            TournamentPolicy policy = factory.create(POLICY_SEED + 7919L * r);
            trueWorld.simulator.simulateOnce(new Random(EVAL_SEED + 7919L * r),
                    trueWorld.me, policy);
            return policy.score();
        }).toArray();
    }

    /** A committed sequence's CRN mean inside another world's simulator. */
    double searchMeanIn(PolicyTournament trueWorld, List<Position> sequence, int rollouts){
        double total = 0;
        for(int r = 0; r < rollouts; r++){
            SequencePolicy policy = new SequencePolicy(sequence);
            trueWorld.simulator.simulateOnce(new Random(SEARCH_SEED + 7919L * r),
                    trueWorld.me, policy);
            total += policy.score();
        }
        return total / rollouts;
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

    /**
     * The tournament's world, buildable by any diagnostic: the -Pkeepers
     * scenario resolved (declared pair excluded, named pair at real cost
     * rounds), out-of-game keepers pinned onto my last live rounds, the
     * waiting table filled. A swapped-out QB keeper reopens the QB dimension
     * (2520 feasible sequences instead of 742).
     */
    static PolicyTournament forCurrentGame(AAAConfiguration configuration, int waitingTrials){
        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, lastCompleted);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, lastCompleted,
                earliness);
        return forCurrentGame(configuration, waitingTrials, model, earliness);
    }

    /** The same world under a different brain - ensembles and mismatch tests. */
    static PolicyTournament forCurrentGame(AAAConfiguration configuration, int waitingTrials,
                                           ChoiceModel model, Map<String, Double> earliness){
        List<Keeper> scenario = DraftPlanner.keepersFromProperty(configuration);
        java.util.Set<String> excluded = new java.util.HashSet<>();
        List<Keeper> myEffective = new ArrayList<>();
        for(Keeper keeper : configuration.getTodaysKeepers()){
            if(configuration.getMyID().equals(keeper.humanWhoCanKeep)){
                if(scenario.isEmpty()){
                    myEffective.add(keeper);
                }
                else {
                    excluded.add(keeper.player.sleeperIDString);
                }
            }
        }
        myEffective.addAll(scenario);
        DraftPlanner planner = DraftPlanner.forCurrentSeasonAs(configuration,
                configuration.getMyID(), scenario, excluded, model, earliness);

        // In-game keeper costs already occupy their rounds; out-of-game
        // keepers pin onto my LAST live rounds (9 first, then 8) - the
        // tournament's "keepers on rounds 8-9" rule, made generic.
        int outOfGame = (int) myEffective.stream()
                .filter(keeper -> keeper.roundCanBeKept > SelectionModel.GAME_ROUNDS).count();
        DraftSimulator base = planner.simulator();
        int[] liveBefore = base.pickNumbersOf(planner.me());
        java.util.Set<Integer> pinRounds = new java.util.HashSet<>();
        for(int i = 0; i < outOfGame; i++){
            pinRounds.add(base.slotAt(liveBefore[liveBefore.length - 1 - i]).round());
        }
        DraftSimulator pinned = base.withKeeperSlots(planner.me(), pinRounds);
        PolicyTournament tournament = new PolicyTournament(pinned, planner.me(),
                planner.myKeeperIDs(), planner.points());
        System.out.printf("game: my picks %s, keepers %s%n",
                java.util.Arrays.toString(tournament.myPicks),
                tournament.myKeeperIDs.stream()
                        .map(id -> Player.getPlayerFromSIDV2(id).lastName).toList());
        tournament.fillWaitingTable(waitingTrials);
        return tournament;
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 800);
        int search = Integer.getInteger("search", 150);
        int adaptiveTrials = Integer.getInteger("adaptiveTrials", 150);
        int inner = Integer.getInteger("inner", 16);

        System.out.println("policy tournament: 7 live picks + keepers pinned late, "
                + "composition 1QB/2RB/3WR/1TE/2FLEX");
        PolicyTournament tournament = forCurrentGame(configuration, Math.max(search, 100));
        Map<String, Double> adp = new HashMap<>();
        for(String sleeperID : tournament.points.keySet()){
            double value = SleeperProjections.adpOf(sleeperID);
            if(value < Double.MAX_VALUE){
                adp.put(sleeperID, value);
            }
        }

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
                    TournamentPolicy policy = tournament.new HeadThenTail(prefix,
                            Tail.RAW, start.copy(),
                            new ArrayList<>(tournament.myKeeperIDs), new Random(0));
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

        // ---- the exact family and the fancier searches, graded by truth ----
        List<Position> dpBest = tournament.dpPlan();
        double maxCe = -Double.MAX_VALUE;
        for(List<Position> sequence : sequences){
            maxCe = Math.max(maxCe, tournament.ceValue(sequence));
        }
        System.out.printf("dp-composition %s, CE %.1f vs enumerated max CE %.1f%s%n",
                dpBest, tournament.ceValue(dpBest), maxCe,
                Math.abs(tournament.ceValue(dpBest) - maxCe) < 1e-6
                        ? " (EXACT, as proved)" : " (MISMATCH - bug!)");
        int[] screen = tournament.bnbScreen(sequences, sequenceMeans, argmax);
        System.out.printf("bnb screen: evaluated %d of %d sequences, found the true "
                        + "optimum: %s, regret %.1f%n", screen[0], sequences.size(),
                screen[2] == 1, sequenceMeans[argmax] - sequenceMeans[screen[1]]);
        List<Position> saBest = tournament.saPlan(1200, 20);
        List<Position> nrpaBest = tournament.nrpaPlan(40, 10, 20);
        System.out.printf("sa %s, nrpa %s%n", saBest, nrpaBest);
        int mctsBudget = Integer.getInteger("mcts", 192);

        // ---- Justin's structured head: (QB round, TE round), RB/WR live ----
        boolean qbOwed = start.dedicated.getOrDefault(Position.QB, 0) > 0;
        boolean teOwed = start.dedicated.getOrDefault(Position.TE, 0) > 0;
        List<int[]> timingHeads = new ArrayList<>();
        int picks = tournament.myPicks.length;
        for(int qbAt : qbOwed
                ? IntStream.range(0, picks).toArray() : new int[]{-1}){
            if(teOwed){
                for(int teAt = 0; teAt < picks; teAt++){
                    if(teAt != qbAt){
                        timingHeads.add(new int[]{qbAt, teAt});
                    }
                }
            }
            else {
                timingHeads.add(new int[]{qbAt, -1});
            }
        }
        double[] timingMeans = IntStream.range(0, timingHeads.size()).parallel()
                .mapToDouble(h -> {
                    int[] head = timingHeads.get(h);
                    double total = 0;
                    for(int r = 0; r < search; r++){
                        TournamentPolicy policy =
                                tournament.new TimingCommitted(head[0], head[1]);
                        tournament.simulator.simulateOnce(
                                new Random(SEARCH_SEED + 7919L * r), tournament.me, policy);
                        total += policy.score();
                    }
                    return total / search;
                }).toArray();
        int timingArgmax = 0;
        for(int h = 1; h < timingMeans.length; h++){
            if(timingMeans[h] > timingMeans[timingArgmax]){
                timingArgmax = h;
            }
        }
        int[] bestTiming = timingHeads.get(timingArgmax);
        String timingLabel = String.format("QB@%s TE@%s",
                bestTiming[0] < 0 ? "none" : "r" + tournament.simulator
                        .slotAt(tournament.myPicks[bestTiming[0]]).round(),
                bestTiming[1] < 0 ? "none" : "r" + tournament.simulator
                        .slotAt(tournament.myPicks[bestTiming[1]]).round());
        System.out.printf("timing-committed winner %s over %d heads, search mean %.1f%n",
                timingLabel, timingHeads.size(), timingMeans[timingArgmax]);

        // ---- train the learned strategies (their own seed block) ----
        // -PmlScale=0.05 shrinks every training budget for smoke runs.
        double mlScale = Double.parseDouble(System.getProperty("mlScale", "1.0"));
        long trainStart = System.currentTimeMillis();
        BoostedRegressor fittedQ = tournament.trainFittedQ(
                (int) Math.max(100, 2000 * mlScale), 2);
        BoostedRegressor imitation = tournament.trainImitation(
                (int) Math.max(5, 150 * mlScale), 12);
        double[] reinforceWeights = tournament.trainReinforce(
                (int) Math.max(200, 4000 * mlScale));
        List<Position> cemBest = tournament.trainCem(20,
                (int) Math.max(8, 80 * mlScale), (int) Math.max(3, 16 * mlScale),
                (int) Math.max(4, 40 * mlScale));
        List<Position> evolutionBest = tournament.trainEvolution(30,
                (int) Math.max(6, 30 * mlScale), (int) Math.max(4, 40 * mlScale));
        System.out.printf("learned strategies trained (%.0fs); cem %s, evolution %s%n",
                (System.currentTimeMillis() - trainStart) / 1000.0,
                cemBest, evolutionBest);

        List<Position> shipped = List.of(Position.RB, Position.WR, Position.RB,
                Position.WR, Position.WR, Position.WR, Position.TE);

        // ---- the roster, evaluated on the shared fresh stream ----
        Map<String, double[]> results = new LinkedHashMap<>();
        Map<String, Integer> trialsUsed = new LinkedHashMap<>();
        List<Object[]> roster = new ArrayList<>();
        roster.add(new Object[]{"random-feasible", trials,
                (Factory) named("random-feasible",
                        seed -> tournament.new RandomFeasible(seed))});
        roster.add(new Object[]{"adp-follower", trials,
                named("adp-follower", seed -> tournament.new AdpFollower(adp))});
        roster.add(new Object[]{"greedy-raw", trials,
                named("greedy-raw", seed -> tournament.new GreedyRaw())});
        roster.add(new Object[]{"greedy-vorp", trials,
                named("greedy-vorp", seed -> tournament.new GreedyVorp())});
        // The locked plan belongs to the declared-keeper game; in a scenario
        // whose composition it cannot legally fill, it sits out.
        if(feasibleSequence(start.copy(), shipped)){
            roster.add(new Object[]{"shipped-plan " + label(shipped), trials,
                    named("shipped-plan", seed -> tournament.new SequencePolicy(shipped))});
        }
        roster.add(new Object[]{"staged-frontier " + label(stagedBest), trials,
                named("staged-frontier",
                        seed -> tournament.new SequencePolicy(stagedBest))});
        roster.add(new Object[]{"exhaustive-committed " + label(exhaustiveBest), trials,
                named("exhaustive",
                        seed -> tournament.new SequencePolicy(exhaustiveBest))});
        roster.add(new Object[]{"timing-committed " + timingLabel, trials,
                named("timing-committed", seed -> tournament.new TimingCommitted(
                        bestTiming[0], bestTiming[1]))});
        roster.add(new Object[]{"ml-fittedq", trials,
                named("ml-fittedq", seed -> tournament.new ModelPolicy(fittedQ))});
        roster.add(new Object[]{"ml-imitation (of oldschool-2-vorp)", trials,
                named("ml-imitation", seed -> tournament.new ModelPolicy(imitation))});
        roster.add(new Object[]{"ml-reinforce", trials,
                named("ml-reinforce", seed -> tournament.new SoftmaxPolicy(
                        reinforceWeights, null, false))});
        roster.add(new Object[]{"ml-cem " + label(cemBest), trials,
                named("ml-cem", seed -> tournament.new SequencePolicy(cemBest))});
        roster.add(new Object[]{"ml-evolution " + label(evolutionBest), trials,
                named("ml-evolution",
                        seed -> tournament.new SequencePolicy(evolutionBest))});
        roster.add(new Object[]{"dp-composition " + label(dpBest), trials,
                named("dp-composition", seed -> tournament.new SequencePolicy(dpBest))});
        roster.add(new Object[]{"sa " + label(saBest), trials,
                named("sa", seed -> tournament.new SequencePolicy(saBest))});
        roster.add(new Object[]{"nrpa " + label(nrpaBest), trials,
                named("nrpa", seed -> tournament.new SequencePolicy(nrpaBest))});
        roster.add(new Object[]{"mcts (budget " + mctsBudget + ", vorp tails)",
                adaptiveTrials,
                named("mcts", seed -> tournament.new MctsPolicy(mctsBudget, seed))});
        roster.add(new Object[]{"oldschool-1 (random tails)", adaptiveTrials,
                named("oldschool-1",
                        seed -> tournament.new Lookahead(1, inner, Tail.RANDOM, seed))});
        roster.add(new Object[]{"oldschool-2 (random tails)", adaptiveTrials,
                named("oldschool-2",
                        seed -> tournament.new Lookahead(2, inner, Tail.RANDOM, seed))});
        roster.add(new Object[]{"oldschool-3 (random tails)", adaptiveTrials,
                named("oldschool-3",
                        seed -> tournament.new Lookahead(3, inner, Tail.RANDOM, seed))});
        roster.add(new Object[]{"oldschool-1 (vorp tails)", adaptiveTrials,
                named("oldschool-1-vorp",
                        seed -> tournament.new Lookahead(1, inner, Tail.VORP, seed))});
        roster.add(new Object[]{"oldschool-2 (vorp tails)", adaptiveTrials,
                named("oldschool-2-vorp",
                        seed -> tournament.new Lookahead(2, inner, Tail.VORP, seed))});
        roster.add(new Object[]{"adaptive-greedy (d1, raw tails)", adaptiveTrials,
                named("adaptive-greedy",
                        seed -> tournament.new Lookahead(1, inner, Tail.RAW, seed))});

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

    /** Can the sequence be played to completion under the composition? */
    static boolean feasibleSequence(Needs needs, List<Position> sequence){
        for(Position position : sequence){
            if(!needs.feasible(position)){
                return false;
            }
            needs.consume(position);
        }
        return true;
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
