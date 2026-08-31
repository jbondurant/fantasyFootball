import PlayerImportAndSetup.Position;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

/**
 * The parts of the shape search that could be wrong without looking wrong.
 *
 * The leave-one-out claim rests on three things a reader has to be able to
 * trust: that the fold never sees the season it is scored on, that the search
 * space is what the tool says it is, and that the climb is a real climb rather
 * than something that quietly returns its seed. Offline - no boards, no network.
 */
class ShapeSearchTest {

    private static final List<Position> RUNBOOK = ShapeSearch.parse(ShapeSearch.RUNBOOK);

    // ------------------------------------------------------------- legality

    @Test
    void theCommittedShapeIsInsideTheSearchSpace(){
        Assertions.assertTrue(ShapeSearch.legal(RUNBOOK),
                "the shape under investigation must be reachable, or the comparison is empty");
        Assertions.assertEquals(14, RUNBOOK.size());
    }

    @Test
    void illegalRostersAreRejected(){
        Assertions.assertFalse(ShapeSearch.legal(ShapeSearch.parse(
                "RB RB RB WR WR WR WR TE WR QB TE QB DEF DEF")), "two defences");
        Assertions.assertFalse(ShapeSearch.legal(ShapeSearch.parse(
                "RB RB RB WR WR WR WR TE WR QB TE QB RB RB")), "no defence");
        Assertions.assertFalse(ShapeSearch.legal(ShapeSearch.parse(
                "RB WR WR WR WR WR WR TE WR QB TE QB WR DEF")), "one back, needs two");
        Assertions.assertFalse(ShapeSearch.legal(ShapeSearch.parse(
                "RB RB RB RB RB RB RB TE RB QB TE QB RB DEF")), "no receivers");
        Assertions.assertFalse(ShapeSearch.legal(ShapeSearch.parse(
                "QB QB QB WR WR WR WR TE WR RB TE RB RB DEF")), "three quarterbacks");
        Assertions.assertFalse(ShapeSearch.legal(ShapeSearch.parse(
                "RB RB RB WR WR WR TE TE TE QB WR QB RB DEF")), "three tight ends");
        Assertions.assertFalse(ShapeSearch.legal(ShapeSearch.parse(
                "RB RB RB WR WR WR WR TE WR QB TE QB DEF")), "thirteen slots");
    }

    // ----------------------------------------------------------- the space

    @Test
    void theAdvertisedSpaceSizeIsTheRealOne(){
        // Brute force is impossible at fourteen slots, so the multinomial count is
        // checked against exhaustive enumeration at a width where both can run.
        for(int slots = 8; slots <= 10; slots++){
            Assertions.assertEquals(BigInteger.valueOf(bruteForceCount(slots)),
                    ShapeSearch.spaceSize(slots),
                    "multinomial count disagrees with enumeration at " + slots + " slots");
        }
    }

    private long bruteForceCount(int slots){
        return enumerate(new ArrayList<>(), slots);
    }

    private long enumerate(List<Position> partial, int slots){
        if(partial.size() == slots){
            return legalAt(partial, slots) ? 1 : 0;
        }
        long found = 0;
        for(Position position : ShapeSearch.ALPHABET){
            partial.add(position);
            found += enumerate(partial, slots);
            partial.remove(partial.size() - 1);
        }
        return found;
    }

    /** ShapeSearch.legal is hard-wired to fourteen; this is the same rule, any width. */
    private boolean legalAt(List<Position> shape, int slots){
        if(shape.size() != slots){
            return false;
        }
        Map<Position, Integer> counts = ShapeSearch.counts(shape);
        for(Position position : ShapeSearch.ALPHABET){
            int[] bounds = ShapeSearch.LIMITS.get(position);
            int held = counts.get(position);
            if(held < bounds[0] || held > Math.min(bounds[1], slots)){
                return false;
            }
        }
        return true;
    }

    @Test
    void everyRandomDrawIsLegalAndTheDrawIsNotDegenerate(){
        Random random = new Random(11);
        Set<String> distinct = new HashSet<>();
        for(int i = 0; i < 2000; i++){
            List<Position> shape = ShapeSearch.randomLegal(random);
            Assertions.assertTrue(ShapeSearch.legal(shape),
                    "random seed produced an illegal shape: " + ShapeSearch.render(shape));
            distinct.add(ShapeSearch.render(shape));
        }
        Assertions.assertTrue(distinct.size() > 1900,
                "2000 draws collapsed to " + distinct.size()
                        + " shapes - the sampler is stuck, not uniform");
    }

    @Test
    void theSamplerIsUniformOverOrderings(){
        // The defence has exactly one legal count, so under a uniform draw over
        // ORDERED shapes it must land in each of the fourteen slots equally often.
        // Drawing a composition uniformly and shuffling would also give this; what
        // it would NOT give is the receiver-count distribution checked below.
        Random random = new Random(4242);
        int[] defenceSlot = new int[ShapeSearch.SLOTS];
        Map<Integer, Integer> receiverCounts = new HashMap<>();
        int draws = 60000;
        for(int i = 0; i < draws; i++){
            List<Position> shape = ShapeSearch.randomLegal(random);
            defenceSlot[shape.indexOf(Position.DEF)]++;
            receiverCounts.merge(ShapeSearch.counts(shape).get(Position.WR), 1, Integer::sum);
        }
        double expected = draws / (double) ShapeSearch.SLOTS;
        for(int slot = 0; slot < ShapeSearch.SLOTS; slot++){
            Assertions.assertEquals(expected, defenceSlot[slot], expected * 0.15,
                    "the defence is not landing uniformly across slots");
        }
        // Under a uniform draw the modal receiver count is the balanced one, not
        // the extreme. Nine receivers is a single composition with few orderings.
        Assertions.assertTrue(receiverCounts.getOrDefault(5, 0) > receiverCounts.getOrDefault(9, 0),
                "lopsided rosters are over-sampled - the composition weighting is wrong");
    }

    // ---------------------------------------------------------- neighbours

    @Test
    void neighboursAreLegalDistinctAndOneMoveAway(){
        List<List<Position>> neighbours = ShapeSearch.neighbours(RUNBOOK);
        Assertions.assertFalse(neighbours.isEmpty());
        Set<String> seen = new HashSet<>();
        for(List<Position> neighbour : neighbours){
            Assertions.assertTrue(ShapeSearch.legal(neighbour),
                    "illegal neighbour: " + ShapeSearch.render(neighbour));
            Assertions.assertTrue(seen.add(ShapeSearch.render(neighbour)), "duplicate neighbour");
            int apart = ShapeSearch.hamming(RUNBOOK, neighbour);
            Assertions.assertTrue(apart == 1 || apart == 2,
                    "a substitution changes one slot and a swap changes two, not " + apart);
        }
        Assertions.assertFalse(seen.contains(ShapeSearch.RUNBOOK),
                "a shape must not be its own neighbour");
    }

    @Test
    void theDefenceCanMove(){
        // DEF is capped and floored at one, so substitution can never relocate it.
        // If swaps were dropped from the neighbourhood the search could not reach
        // any other defence round, which is the single most-argued slot in the plan.
        int defenceAt = RUNBOOK.indexOf(Position.DEF);
        boolean moved = false;
        for(List<Position> neighbour : ShapeSearch.neighbours(RUNBOOK)){
            if(neighbour.indexOf(Position.DEF) != defenceAt){
                moved = true;
            }
        }
        Assertions.assertTrue(moved, "no neighbour moves the defence - the placement"
                + " curve would be unsearchable");
    }

    @Test
    void theNeighbourhoodConnectsTheWholeSpace(){
        // A climb that cannot reach a shape can never choose it. Breadth-first from
        // one shape must reach every legal shape at a width small enough to walk.
        List<Position> start = ShapeSearch.parse("RB RB WR WR WR TE QB DEF");
        Set<String> reached = new HashSet<>();
        List<List<Position>> queue = new ArrayList<>();
        queue.add(start);
        reached.add(ShapeSearch.render(start));
        for(int at = 0; at < queue.size(); at++){
            for(List<Position> neighbour : neighboursAt(queue.get(at), 8)){
                if(reached.add(ShapeSearch.render(neighbour))){
                    queue.add(neighbour);
                }
            }
        }
        Assertions.assertEquals(bruteForceCount(8), reached.size(),
                "the move set does not connect the space - some shapes are unreachable");
    }

    /** The production move set, applied at a width the test can enumerate. */
    private List<List<Position>> neighboursAt(List<Position> shape, int slots){
        List<List<Position>> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(ShapeSearch.render(shape));
        for(int slot = 0; slot < shape.size(); slot++){
            for(Position position : ShapeSearch.ALPHABET){
                if(position == shape.get(slot)){
                    continue;
                }
                List<Position> candidate = new ArrayList<>(shape);
                candidate.set(slot, position);
                if(legalAt(candidate, slots) && seen.add(ShapeSearch.render(candidate))){
                    found.add(candidate);
                }
            }
        }
        for(int a = 0; a < shape.size(); a++){
            for(int b = a + 1; b < shape.size(); b++){
                if(shape.get(a) == shape.get(b)){
                    continue;
                }
                List<Position> candidate = new ArrayList<>(shape);
                candidate.set(a, shape.get(b));
                candidate.set(b, shape.get(a));
                if(legalAt(candidate, slots) && seen.add(ShapeSearch.render(candidate))){
                    found.add(candidate);
                }
            }
        }
        return found;
    }

    // ------------------------------------------------------------ climbing

    @Test
    void theClimbActuallyClimbsAndStopsAtALocalOptimum(){
        // A synthetic objective with a known peak: how many slots match a target.
        List<Position> target = ShapeSearch.parse("WR WR WR RB RB TE QB DEF RB WR TE QB WR RB");
        Assertions.assertTrue(ShapeSearch.legal(target));
        Function<List<Position>, Double> objective =
                shape -> (double) (ShapeSearch.SLOTS - ShapeSearch.hamming(shape, target));
        Random random = new Random(7);
        for(int trial = 0; trial < 12; trial++){
            List<Position> start = ShapeSearch.randomLegal(random);
            List<Position> peak = ShapeSearch.hillClimb(start, objective);
            Assertions.assertTrue(objective.apply(peak) >= objective.apply(start),
                    "the climb went downhill");
            for(List<Position> neighbour : ShapeSearch.neighbours(peak)){
                Assertions.assertTrue(objective.apply(neighbour) <= objective.apply(peak) + 1e-9,
                        "stopped somewhere a neighbour is better - not a local optimum");
            }
            Assertions.assertEquals(ShapeSearch.render(target), ShapeSearch.render(peak),
                    "a match-count objective is unimodal under this move set; the climb"
                            + " should reach the target");
        }
    }

    @Test
    void theClimbDoesNotSimplyReturnItsSeed(){
        // The trap this repo already fell into: a search that reproduces its prior
        // and reads as a triumph. A climb on a real gradient must MOVE.
        List<Position> target = ShapeSearch.parse("QB RB RB WR WR WR TE WR RB WR TE QB RB DEF");
        Function<List<Position>, Double> objective =
                shape -> (double) (ShapeSearch.SLOTS - ShapeSearch.hamming(shape, target));
        Random random = new Random(99);
        int moved = 0;
        for(int trial = 0; trial < 20; trial++){
            List<Position> start = ShapeSearch.randomLegal(random);
            if(ShapeSearch.hamming(start, ShapeSearch.hillClimb(start, objective)) > 0){
                moved++;
            }
        }
        Assertions.assertEquals(20, moved, "the climb is returning its start unchanged");
    }

    @Test
    void theSearchNeverStartsFromTheCommittedShape(){
        // The whole claim depends on this. If the committed shape were a seed, the
        // search would be handed the answer it is supposed to rediscover.
        Random random = new Random(20260830);
        for(int i = 0; i < 20000; i++){
            Assertions.assertNotEquals(ShapeSearch.RUNBOOK,
                    ShapeSearch.render(ShapeSearch.randomLegal(random)),
                    "the sampler drew the committed shape as a seed at draw " + i);
        }
    }

    // -------------------------------------------------------------- folding

    @Test
    void aFoldNeverSeesTheSeasonItIsScoredOn(){
        for(int heldOut = 0; heldOut < 5; heldOut++){
            int[] training = ShapeSearch.trainingSeasons(5, heldOut);
            Assertions.assertEquals(4, training.length);
            for(int index : training){
                Assertions.assertNotEquals(heldOut, index,
                        "the held-out season leaked into the training set");
            }
            Set<Integer> distinct = new HashSet<>();
            for(int index : training){
                Assertions.assertTrue(distinct.add(index), "a season appears twice in training");
                Assertions.assertTrue(index >= 0 && index < 5);
            }
        }
    }

    @Test
    void trainingStatisticsReadOnlyTheTrainingSeasons(){
        double[] all = {100, 200, 300, 400, 5000};
        int[] training = ShapeSearch.trainingSeasons(5, 4);
        Assertions.assertEquals(250, ShapeSearch.Evaluator.meanOn(all, training), 1e-9,
                "the held-out 5000 contaminated the training mean");
        Assertions.assertEquals(100, ShapeSearch.Evaluator.minOn(all, training), 1e-9);
        double[] frontier = {150, 200, 300, 500, 9999};
        Assertions.assertEquals(-100, ShapeSearch.negatedMaxRegret(all, training, frontier), 1e-9,
                "regret must be the worst training shortfall and ignore the held-out season");
    }

    // ------------------------------------------------------------ distances

    @Test
    void distancesSeparateOrderFromRoster(){
        List<Position> reordered = ShapeSearch.parse(
                "DEF RB RB WR WR WR WR TE WR QB TE QB RB RB");
        Assertions.assertEquals(0, ShapeSearch.compositionDistance(RUNBOOK, reordered),
                "same fourteen men, different order - the roster gap is zero");
        Assertions.assertTrue(ShapeSearch.hamming(RUNBOOK, reordered) > 0);
        List<Position> swappedPosition = new ArrayList<>(RUNBOOK);
        swappedPosition.set(0, Position.WR);
        Assertions.assertEquals(1, ShapeSearch.compositionDistance(RUNBOOK, swappedPosition));
        Assertions.assertEquals(0, ShapeSearch.hamming(RUNBOOK, RUNBOOK));
    }

    @Test
    void firstSlotSpreadFindsWhereAPositionIsTaken(){
        Assertions.assertEquals(9, ShapeSearch.firstSlot(RUNBOOK, Position.QB),
                "the committed plan takes its quarterback in round 10, index 9");
        Assertions.assertEquals(13, ShapeSearch.firstSlot(RUNBOOK, Position.DEF));
        Assertions.assertEquals(0, ShapeSearch.firstSlot(RUNBOOK, Position.RB));
        Assertions.assertEquals(3, ShapeSearch.firstSlot(RUNBOOK, Position.WR));
        Assertions.assertEquals(7, ShapeSearch.firstSlot(RUNBOOK, Position.TE));

        // A spread that is genuinely pinned must read as pinned, and one that is
        // scattered must not - this is the whole basis of the PINNED/FREE verdict.
        List<String> pinned = List.of(
                "QB RB RB WR WR WR WR TE WR RB TE WR RB DEF",
                "QB RB RB WR WR WR TE WR WR RB TE WR RB DEF",
                "QB RB WR WR WR WR RB TE WR RB TE WR RB DEF");
        double[] tight = ShapeSearch.firstSlotSpread(pinned, Position.QB);
        Assertions.assertEquals(0, tight[0], 1e-9);
        Assertions.assertEquals(0, tight[2], 1e-9);
        List<String> scattered = List.of(
                "QB RB RB WR WR WR WR TE WR RB TE WR RB DEF",
                "RB RB WR WR WR WR TE WR QB RB TE WR RB DEF",
                "RB RB WR WR WR WR TE WR RB RB TE WR QB DEF");
        double[] wide = ShapeSearch.firstSlotSpread(scattered, Position.QB);
        Assertions.assertTrue(wide[2] - wide[0] > tight[2] - tight[0],
                "a scattered position must read wider than a pinned one");
    }

    @Test
    void movingTheDefenceLastKeepsTheRosterAndOnlyChangesWhen(){
        List<Position> moved = ShapeSearch.defenceLast(RUNBOOK);
        Assertions.assertEquals(ShapeSearch.RUNBOOK, ShapeSearch.render(moved),
                "the committed plan already takes its defence last - this must be a no-op");

        List<Position> early = ShapeSearch.parse("RB RB QB DEF WR WR RB TE WR QB RB WR WR TE");
        List<Position> lastly = ShapeSearch.defenceLast(early);
        Assertions.assertEquals(13, ShapeSearch.firstSlot(lastly, Position.DEF),
                "the defence must end up on the final pick");
        Assertions.assertTrue(ShapeSearch.legal(lastly));
        Assertions.assertEquals(0, ShapeSearch.compositionDistance(early, lastly),
                "the same fourteen roster spots, only the timing changed");
        // The skill positions must keep their relative order, each one pick earlier.
        List<Position> skillBefore = new ArrayList<>();
        for(Position position : early){
            if(position != Position.DEF){
                skillBefore.add(position);
            }
        }
        Assertions.assertEquals(skillBefore, lastly.subList(0, 13),
                "moving the defence must not reorder anything else");
    }

    @Test
    void pairedDifferencesAreSharperThanComparingTwoMeans(){
        // The committed shape's five seasons, and a hypothetical rival that beats
        // it by exactly 50 every year. Compared as two independent means the gap
        // is buried under a spread of 213; paired, it is exact and its standard
        // error is zero. That gap between the two tests is the reason the paired
        // one is reported.
        double[] runbook = {2035, 1654, 2191, 1960, 2148};
        double[] rival = {2085, 1704, 2241, 2010, 2198};
        double[] gaps = ShapeSearch.differences(rival, runbook);
        Assertions.assertEquals(50, ShapeSearch.mean(gaps), 1e-9);
        Assertions.assertEquals(0, ShapeSearch.standardError(gaps), 1e-9,
                "a constant edge has no uncertainty once the seasons are paired");
        Assertions.assertTrue(ShapeSearch.standardError(runbook) > 90,
                "the unpaired standard error is large enough to hide a 50-point edge");
    }

    @Test
    void standardErrorShrinksWithAgreementNotWithSize(){
        double[] noisy = {200, -300, 100, -50, 400};
        Assertions.assertTrue(ShapeSearch.standardError(noisy) > 100,
                "wildly disagreeing folds must report a wide interval");
        double[] steady = {40, 45, 50, 55, 60};
        Assertions.assertTrue(ShapeSearch.standardError(steady) < 10,
                "folds that agree must report a narrow one");
        Assertions.assertTrue(Double.isNaN(ShapeSearch.standardError(new double[]{5})),
                "one observation has no standard error, and must not pretend to");
    }

    @Test
    void percentilesAreOrderedAndInRange(){
        double[] sorted = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        Assertions.assertEquals(1, ShapeSearch.percentile(sorted, 0.0), 1e-9);
        Assertions.assertEquals(10, ShapeSearch.percentile(sorted, 1.0), 1e-9);
        Assertions.assertTrue(ShapeSearch.percentile(sorted, 0.10)
                <= ShapeSearch.percentile(sorted, 0.50));
        Assertions.assertTrue(ShapeSearch.percentile(sorted, 0.50)
                <= ShapeSearch.percentile(sorted, 0.90));
    }

    @Test
    void statisticsAreTheOnesTheyClaimToBe(){
        double[] values = {2035, 1654, 2191, 1960, 2148};
        Assertions.assertEquals(1997.6, ShapeSearch.mean(values), 1e-9);
        Assertions.assertEquals(1654, ShapeSearch.min(values), 1e-9);
        Assertions.assertEquals(212.7, ShapeSearch.stdev(values), 0.1);
    }
}
