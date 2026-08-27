import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * The tournament's winning search, ported to the FULL-RULES game (nine live
 * picks, keepers free at their r10+ costs, best nine of eleven): commit only
 * the treacherous timing dimensions - which of my nine picks takes a QB (or
 * none: Purdy alone) and which takes the TE - and let every other pick choose
 * RB-vs-WR live by roster-aware VORP (best-nine marginal now minus the
 * expected marginal of waiting one of my rounds). The lab result this ports:
 * timing heads priced globally cannot fall into frontier valleys, and an
 * adaptive smooth dimension beats any fully-committed sequence.
 *
 * Heads: (qbAt in {none, 0..8}) x (teAt in {none, 0..8}), qbAt != teAt - 100
 * heads, priced on CRN search seeds; the winner and the incumbents (the
 * shipped committed plan, the staged search's plan) are then re-priced at
 * -Ptrials rollouts on fresh eval seeds, PAIRED, per-trial arrays kept.
 *
 *   ./gradlew run -Pmain=TimingPlanner [-Ptrials=10000] [-Psearch=300]
 */
public class TimingPlanner {

    static final long SEARCH_SEED = DraftSimulator.SEED + 51_000_000L;
    static final long EVAL_SEED = DraftSimulator.SEED + 52_000_000L;

    private final DraftSimulator simulator;
    private final String me;
    private final List<String> myKeeperIDs;
    private final Map<String, Double> points;
    private final int[] myPicks;
    /** my pick number -> position -> mean best-available points if I wait. */
    private final Map<Integer, Map<Position, Double>> waitingTable = new HashMap<>();

    /** What the season actually did - scoring only, never visible to picks. */
    private Map<String, Double> truth;

    TimingPlanner(DraftPlanner planner){
        this(planner, planner.points());
    }

    /** With my valuation overridden - fog draws value under a sampled TRUTH
     *  while the world keeps drafting off its unchanged sheet. */
    TimingPlanner(DraftPlanner planner, Map<String, Double> pointsOverride){
        this.simulator = planner.simulator();
        this.me = planner.me();
        this.myKeeperIDs = planner.myKeeperIDs();
        this.points = pointsOverride;
        this.myPicks = simulator.pickNumbersOf(me);
    }

    /**
     * Decisions use `points` (the projections I can see at the draft);
     * scoring uses `truth` (what the season did). Without this split a fog
     * study is clairvoyant - the policy would dodge exactly the players it
     * could not have known would bust.
     */
    void scoreUnder(Map<String, Double> seasonTruth){
        this.truth = seasonTruth;
    }

    /** Best-nine of a roster under truth when set, else the planner's own. */
    double scoreMine(List<String> mine){
        return StartingLineup.bestNine(mine, truth == null ? points : truth);
    }

    // ---- a best-nine that accepts phantoms (position + points, no ID) ----

    /**
     * Best nine over (position, points) pairs: dedicated slots take the best
     * at each position, the two flexes take the best leftovers among
     * RB/WR/TE. Greedy is exact for this slot structure. Mirrors
     * StartingLineup.bestNine but lets the policy price a hypothetical
     * "the player I could get by waiting" without inventing a player id.
     */
    static double bestNine(List<double[]> players){
        Map<Position, List<Double>> byPosition = new EnumMap<>(Position.class);
        for(Position position : Position.values()){
            byPosition.put(position, new ArrayList<>());
        }
        for(double[] player : players){
            byPosition.get(Position.values()[(int) player[0]]).add(player[1]);
        }
        for(List<Double> values : byPosition.values()){
            values.sort(Collections.reverseOrder());
        }
        double total = 0;
        List<Double> leftovers = new ArrayList<>();
        int[][] dedicated = {{Position.QB.ordinal(), 1}, {Position.RB.ordinal(), 2},
                {Position.WR.ordinal(), 3}, {Position.TE.ordinal(), 1}};
        for(int[] slot : dedicated){
            List<Double> values = byPosition.get(Position.values()[slot[0]]);
            for(int i = 0; i < values.size(); i++){
                if(i < slot[1]){
                    total += values.get(i);
                }
                else if(slot[0] != Position.QB.ordinal()){
                    leftovers.add(values.get(i));
                }
            }
        }
        leftovers.sort(Collections.reverseOrder());
        for(int flex = 0; flex < 2 && flex < leftovers.size(); flex++){
            total += leftovers.get(flex);
        }
        return total;
    }

    List<double[]> rosterPairs(List<String> mine){
        List<double[]> players = new ArrayList<>();
        for(String sleeperID : mine){
            players.add(new double[]{Player.getPlayerFromSIDV2(sleeperID).position.ordinal(),
                    points.getOrDefault(sleeperID, 0.0)});
        }
        return players;
    }

    // ---- the policies ----

    /** A policy that can report the roster it drafted. */
    interface RosterPolicy extends DraftSimulator.MyPolicy {
        List<String> mine();
    }

    /** Follows (qbAt, teAt); RB-vs-WR live by roster-aware VORP elsewhere. */
    class TimingPolicy implements RosterPolicy {
        final int qbAt;
        final int teAt;
        final List<String> mine = new ArrayList<>(myKeeperIDs);
        final List<Position> played = new ArrayList<>();
        int decision = 0;

        TimingPolicy(int qbAt, int teAt){
            this.qbAt = qbAt;
            this.teAt = teAt;
        }

        @Override
        public String choose(List<String> board, DraftSimulator.Slot slot){
            Map<Position, String> best = bestAvailable(board);
            Position position;
            if(decision == qbAt && best.get(Position.QB) != null){
                position = Position.QB;
            }
            else if(decision == teAt && best.get(Position.TE) != null){
                position = Position.TE;
            }
            else {
                position = rbOrWr(best, slot.pickNumber());
            }
            String chosen = best.get(position);
            decision++;
            mine.add(chosen);
            played.add(position);
            return chosen;
        }

        private Position rbOrWr(Map<Position, String> best, int pickNumber){
            List<double[]> roster = rosterPairs(mine);
            double baseline = bestNine(roster);
            int next = nextPickAfter(pickNumber);
            Position top = Position.WR;
            double topValue = -Double.MAX_VALUE;
            for(Position position : new Position[]{Position.RB, Position.WR}){
                String candidate = best.get(position);
                if(candidate == null){
                    continue;
                }
                List<double[]> withNow = new ArrayList<>(roster);
                withNow.add(new double[]{position.ordinal(),
                        points.getOrDefault(candidate, 0.0)});
                double marginalNow = bestNine(withNow) - baseline;
                double marginalWait = 0;
                if(next > 0){
                    List<double[]> withWaiter = new ArrayList<>(roster);
                    withWaiter.add(new double[]{position.ordinal(),
                            waitingTable.getOrDefault(next, Map.of())
                                    .getOrDefault(position, 0.0)});
                    marginalWait = bestNine(withWaiter) - baseline;
                }
                double value = marginalNow - marginalWait;
                if(value > topValue){
                    topValue = value;
                    top = position;
                }
            }
            return top;
        }

        @Override
        public List<String> mine(){
            return mine;
        }
    }

    /**
     * Real-game reactive VORP over all four positions: marginal best-nine of
     * taking the best at a position now, minus the marginal of its phantom
     * waiter at my next pick. Roster-aware by construction: an Allen only
     * out-marginals Purdy by their difference, an empty TE slot screams.
     */
    /** The shared chooser: best marginal-now minus marginal-of-waiting. */
    Position vorpPosition(List<String> mine, Map<Position, String> best, int pickNumber){
        List<double[]> roster = rosterPairs(mine);
        double baseline = bestNine(roster);
        int next = nextPickAfter(pickNumber);
        Position top = Position.WR;
        double topValue = -Double.MAX_VALUE;
        for(Position position : new Position[]{Position.QB, Position.RB,
                Position.WR, Position.TE}){
            String candidate = best.get(position);
            if(candidate == null){
                continue;
            }
            List<double[]> withNow = new ArrayList<>(roster);
            withNow.add(new double[]{position.ordinal(),
                    points.getOrDefault(candidate, 0.0)});
            double value = bestNine(withNow) - baseline;
            if(next > 0){
                List<double[]> withWaiter = new ArrayList<>(roster);
                withWaiter.add(new double[]{position.ordinal(),
                        waitingTable.getOrDefault(next, Map.of())
                                .getOrDefault(position, 0.0)});
                value -= bestNine(withWaiter) - baseline;
            }
            if(value > topValue){
                topValue = value;
                top = position;
            }
        }
        return top;
    }

    class VorpPolicy implements RosterPolicy {
        final List<String> mine = new ArrayList<>(myKeeperIDs);

        @Override
        public String choose(List<String> board, DraftSimulator.Slot slot){
            Map<Position, String> best = bestAvailable(board);
            String chosen = best.get(vorpPosition(mine, best, slot.pickNumber()));
            mine.add(chosen);
            return chosen;
        }

        @Override
        public List<String> mine(){
            return mine;
        }
    }

    /**
     * The receding-horizon policy in the full-rules game: at each of my
     * picks, enumerate ordered position pairs two deep, price each head by
     * `inner` completions of the live state (head, then VorpPolicy plays the
     * rest), take the winning head's first position. The real-game measure
     * of the lab's +6..+10 adaptive premium - and the draft-night engine.
     */
    class AdaptivePolicy implements RosterPolicy {
        final int inner;
        final long seed;
        final List<String> mine = new ArrayList<>(myKeeperIDs);
        int decision = 0;

        AdaptivePolicy(int inner, long seed){
            this.inner = inner;
            this.seed = seed;
        }

        @Override
        public String choose(List<String> board, DraftSimulator.Slot slot){
            throw new IllegalStateException("adaptive policy needs the stateful path");
        }

        @Override
        public String choose(List<String> board, DraftSimulator.Slot slot,
                             DraftSimulator.SimState state){
            Position[] positions = {Position.QB, Position.RB, Position.WR, Position.TE};
            int remaining = myPicks.length - decision;
            Position top = null;
            double topValue = -Double.MAX_VALUE;
            Map<Position, String> best = bestAvailable(board);
            for(Position first : positions){
                if(best.get(first) == null){
                    continue;
                }
                Position[] seconds = remaining > 1
                        ? positions : new Position[]{null};
                for(Position second : seconds){
                    double total = 0;
                    for(int r = 0; r < inner; r++){
                        long innerSeed = seed + 101L * decision + 7919L * r;
                        HeadPolicy completion = new HeadPolicy(
                                second == null ? List.of(first) : List.of(first, second),
                                mine);
                        DraftSimulator.SimState branch = state.copy();
                        simulator.simulateFrom(branch, new Random(innerSeed), me,
                                completion);
                        total += StartingLineup.bestNine(completion.mine, points);
                    }
                    double value = total / inner;
                    if(value > topValue){
                        topValue = value;
                        top = first;
                    }
                }
            }
            String chosen = best.get(top);
            decision++;
            mine.add(chosen);
            return chosen;
        }

        @Override
        public List<String> mine(){
            return mine;
        }
    }

    /** Inner completion: play the head positions, then the VORP chooser. */
    class HeadPolicy implements RosterPolicy {
        final List<Position> head;
        final List<String> mine;
        int decision = 0;

        HeadPolicy(List<Position> head, List<String> outerMine){
            this.head = head;
            this.mine = new ArrayList<>(outerMine);
        }

        @Override
        public String choose(List<String> board, DraftSimulator.Slot slot){
            Map<Position, String> best = bestAvailable(board);
            String chosen = decision < head.size() ? best.get(head.get(decision)) : null;
            if(chosen == null){
                chosen = best.get(vorpPosition(mine, best, slot.pickNumber()));
            }
            decision++;
            mine.add(chosen);
            return chosen;
        }

        @Override
        public List<String> mine(){
            return mine;
        }
    }

    /** Follows a committed nine-position sequence, best player each time. */
    class CommittedPolicy implements RosterPolicy {
        final List<Position> sequence;
        final List<String> mine = new ArrayList<>(myKeeperIDs);
        int decision = 0;

        CommittedPolicy(List<Position> sequence){
            this.sequence = sequence;
        }

        @Override
        public String choose(List<String> board, DraftSimulator.Slot slot){
            Map<Position, String> best = bestAvailable(board);
            Position position = sequence.get(decision);
            String chosen = best.get(position);
            if(chosen == null){
                for(Position fallback : new Position[]{Position.WR, Position.RB,
                        Position.TE, Position.QB}){
                    if(best.get(fallback) != null){
                        chosen = best.get(fallback);
                        break;
                    }
                }
            }
            decision++;
            mine.add(chosen);
            return chosen;
        }

        @Override
        public List<String> mine(){
            return mine;
        }
    }

    Map<Position, String> bestAvailable(List<String> board){
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

    int nextPickAfter(int pickNumber){
        for(int pick : myPicks){
            if(pick > pickNumber){
                return pick;
            }
        }
        return -1;
    }

    void fillWaitingTable(int trials){
        Random random = new Random(SEARCH_SEED + 9_000_000L);
        java.util.Set<String> onBoard = simulator.players();
        Map<Integer, Map<Position, Double>> sums = new HashMap<>();
        for(int pick : myPicks){
            sums.put(pick, new EnumMap<>(Position.class));
        }
        for(int trial = 0; trial < trials; trial++){
            Map<String, Integer> takenAt = simulator.simulateOnce(random);
            for(int pick : myPicks){
                Map<Position, Double> best = new EnumMap<>(Position.class);
                for(String sleeperID : onBoard){
                    if(takenAt.getOrDefault(sleeperID, Integer.MAX_VALUE) < pick){
                        continue;
                    }
                    best.merge(Player.getPlayerFromSIDV2(sleeperID).position,
                            points.getOrDefault(sleeperID, 0.0), Math::max);
                }
                for(Map.Entry<Position, Double> entry : best.entrySet()){
                    sums.get(pick).merge(entry.getKey(), entry.getValue(), Double::sum);
                }
            }
        }
        for(int pick : myPicks){
            Map<Position, Double> row = new EnumMap<>(Position.class);
            for(Map.Entry<Position, Double> entry : sums.get(pick).entrySet()){
                row.put(entry.getKey(), entry.getValue() / trials);
            }
            waitingTable.put(pick, row);
        }
    }

    /** Per-trial best-nine scores of a policy on a given seed stream. */
    double[] evaluate(java.util.function.IntFunction<RosterPolicy> factory,
                      int trials, long baseSeed){
        return IntStream.range(0, trials).parallel().mapToDouble(r -> {
            RosterPolicy policy = factory.apply(r);
            simulator.simulateOnce(new Random(baseSeed + 7919L * r), me, policy);
            return StartingLineup.bestNine(policy.mine(), points);
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
        double sum = 0;
        for(double score : scores){
            sum += (score - center) * (score - center);
        }
        return Math.sqrt(sum / (scores.length - 1) / scores.length);
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 10000);
        int search = Integer.getInteger("search", 300);

        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, lastCompleted);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, lastCompleted,
                earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        TimingPlanner timing = new TimingPlanner(planner);
        System.out.printf("full-rules game: my picks %s, keepers free %s%n",
                java.util.Arrays.toString(timing.myPicks),
                timing.myKeeperIDs.stream()
                        .map(id -> Player.getPlayerFromSIDV2(id).lastName).toList());
        timing.fillWaitingTable(Math.max(search, 200));

        // ---- head search on CRN seeds ----
        List<int[]> heads = new ArrayList<>();
        for(int qbAt = -1; qbAt < timing.myPicks.length; qbAt++){
            for(int teAt = -1; teAt < timing.myPicks.length; teAt++){
                if(teAt != qbAt || teAt == -1 && qbAt == -1){
                    if(qbAt != teAt){
                        heads.add(new int[]{qbAt, teAt});
                    }
                    else if(qbAt == -1){
                        heads.add(new int[]{-1, -1});
                    }
                }
            }
        }
        long startTime = System.currentTimeMillis();
        double[] headMeans = IntStream.range(0, heads.size()).parallel().mapToDouble(h -> {
            double total = 0;
            for(int r = 0; r < search; r++){
                TimingPolicy policy = timing.new TimingPolicy(heads.get(h)[0],
                        heads.get(h)[1]);
                timing.simulator.simulateOnce(new Random(SEARCH_SEED + 7919L * r),
                        timing.me, policy);
                total += StartingLineup.bestNine(policy.mine, timing.points);
            }
            return total / search;
        }).toArray();
        Integer[] order = new Integer[heads.size()];
        for(int h = 0; h < heads.size(); h++){
            order[h] = h;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(headMeans[b], headMeans[a]));
        System.out.printf("%n%d heads at %d CRN rollouts (%.0fs); top ten:%n",
                heads.size(), search, (System.currentTimeMillis() - startTime) / 1000.0);
        for(int rank = 0; rank < 10 && rank < heads.size(); rank++){
            int[] head = heads.get(order[rank]);
            System.out.printf("   QB@%-5s TE@%-5s %9.1f%n",
                    head[0] < 0 ? "none" : "r" + (head[0] + 1),
                    head[1] < 0 ? "none" : "r" + (head[1] + 1), headMeans[order[rank]]);
        }
        int[] bestHead = heads.get(order[0]);

        // ---- fresh-seed paired pricing: new search vs the incumbents ----
        List<Position> shipped = List.of(Position.RB, Position.WR, Position.RB,
                Position.WR, Position.WR, Position.WR, Position.TE, Position.QB,
                Position.RB);
        System.out.printf("%nstaged search (incumbent) planning at %d rollouts...%n", search);
        List<Position> staged = planner.plan(search, 0, 0.10, DraftSimulator.SEED).positions();

        double[] timingScores = timing.evaluate(
                r -> timing.new TimingPolicy(bestHead[0], bestHead[1]), trials, EVAL_SEED);
        double[] shippedScores = timing.evaluate(
                r -> timing.new CommittedPolicy(shipped), trials, EVAL_SEED);
        double[] stagedScores = timing.evaluate(
                r -> timing.new CommittedPolicy(staged), trials, EVAL_SEED);

        System.out.printf("%n%-44s %10s %8s %14s%n", "PLAN", "mean", "+/-SE", "vs shipped");
        Object[][] rows = {
                {"timing QB@" + (bestHead[0] < 0 ? "none" : "r" + (bestHead[0] + 1))
                        + " TE@" + (bestHead[1] < 0 ? "none" : "r" + (bestHead[1] + 1))
                        + " (RB/WR live)", timingScores},
                {"staged " + staged, stagedScores},
                {"shipped " + shipped, shippedScores}};
        for(Object[] row : rows){
            double[] scores = (double[]) row[1];
            double delta = 0;
            for(int r = 0; r < trials; r++){
                delta += scores[r] - shippedScores[r];
            }
            System.out.printf("%-44s %10.1f %8.1f %+14.1f%n", row[0], mean(scores),
                    standardError(scores), delta / trials);
        }
        System.out.printf("%nsearch %d CRN rollouts, eval %d fresh-seed rollouts, paired; "
                + "projections exact per the game spec.%n", search, trials);
    }
}
