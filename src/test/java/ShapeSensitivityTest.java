import PlayerImportAndSetup.Position;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The shape arithmetic behind the plateau-or-spike sweep, and the statistics the
 * verdict rests on. Offline: none of this touches a board.
 *
 * These are small functions, but the whole result is built on them. A `move`
 * that quietly did nothing would print a flat cost curve and be read as "the
 * tight end's round does not matter" - which is precisely the sort of too-clean
 * finding this repo has produced before by leaving a knob at its no-op default.
 * So the no-op cases are asserted to BE no-ops, and the real ones asserted to
 * actually move somebody.
 */
class ShapeSensitivityTest {

    private static final String COMMITTED = "RB RB RB WR WR WR WR TE WR QB TE QB RB DEF";

    @Test
    void theCommittedShapeIsFourteenPicksAndMatchesPlanBacktest(){
        Assertions.assertEquals(COMMITTED, ShapeSensitivity.COMMITTED,
                "the sweep must centre on the same string PlanBacktest scores");
        Assertions.assertEquals(14, ShapeSensitivity.parse(COMMITTED).size());
        Assertions.assertEquals(14, PlanBacktest.MY_PICKS.length);
        Assertions.assertEquals(14, ShapeSensitivity.ROUND.length,
                "one round label per pick");
    }

    @Test
    void roundLabelsSkipTheKeeperRounds(){
        // Keepers hold rounds 12 and 13, so the fourteen picks are rounds 1-11
        // then 14-16. A cost curve mislabelled here would put the tight end in a
        // round Justin does not own.
        Assertions.assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 14, 15, 16},
                ShapeSensitivity.ROUND);
    }

    @Test
    void renderRoundTripsParse(){
        Assertions.assertEquals(COMMITTED,
                ShapeSensitivity.render(ShapeSensitivity.parse(COMMITTED)));
    }

    @Test
    void substituteChangesOneSlotAndLeavesTheRestAlone(){
        List<Position> base = ShapeSensitivity.parse(COMMITTED);
        List<Position> changed = ShapeSensitivity.substitute(base, 0, Position.WR);

        Assertions.assertEquals("WR RB RB WR WR WR WR TE WR QB TE QB RB DEF",
                ShapeSensitivity.render(changed));
        Assertions.assertEquals(COMMITTED, ShapeSensitivity.render(base),
                "the original must not be mutated - every sweep reuses it");
    }

    @Test
    void swapExchangesTwoSlots(){
        List<Position> base = ShapeSensitivity.parse(COMMITTED);
        List<Position> changed = ShapeSensitivity.swap(base, 7, 8);

        Assertions.assertEquals("RB RB RB WR WR WR WR WR TE QB TE QB RB DEF",
                ShapeSensitivity.render(changed));
        Assertions.assertEquals(COMMITTED, ShapeSensitivity.render(base));
    }

    @Test
    void moveTakesTheManOutAndRedraftsHimElsewhere(){
        List<Position> base = ShapeSensitivity.parse(COMMITTED);

        // The tight end sits at index 7 (round 8). Moving him to index 3
        // (round 4) pushes rounds 4-7 back one each - it does NOT swap him with
        // whoever stood there.
        Assertions.assertEquals("RB RB RB TE WR WR WR WR WR QB TE QB RB DEF",
                ShapeSensitivity.render(ShapeSensitivity.move(base, 7, 3)));

        // Moving later: the defence at index 13 to index 0 makes it the first
        // pick and shifts everything else one round later.
        Assertions.assertEquals("DEF RB RB RB WR WR WR WR TE WR QB TE QB RB",
                ShapeSensitivity.render(ShapeSensitivity.move(base, 13, 0)));

        Assertions.assertEquals(COMMITTED, ShapeSensitivity.render(base));
    }

    @Test
    void movingAManToWhereHeAlreadyStandsIsANoOp(){
        List<Position> base = ShapeSensitivity.parse(COMMITTED);
        for(int slot = 0; slot < base.size(); slot++){
            Assertions.assertEquals(COMMITTED,
                    ShapeSensitivity.render(ShapeSensitivity.move(base, slot, slot)),
                    "moving slot " + slot + " onto itself must change nothing");
        }
    }

    @Test
    void moveNeverLosesOrDuplicatesAPick(){
        List<Position> base = ShapeSensitivity.parse(COMMITTED);
        for(int from = 0; from < base.size(); from++){
            for(int to = 0; to < base.size(); to++){
                List<Position> moved = ShapeSensitivity.move(base, from, to);
                Assertions.assertEquals(14, moved.size(),
                        "still fourteen picks after move " + from + "->" + to);
                for(Position position : ShapeSensitivity.DRAFTABLE){
                    Assertions.assertEquals(count(base, position), count(moved, position),
                            "move must not change the position budget");
                }
            }
        }
    }

    @Test
    void movingTheTightEndActuallyMovesHim(){
        // The guard against a flat cost curve that means nothing. Every target
        // slot except his own must produce a genuinely different sequence.
        List<Position> base = ShapeSensitivity.parse(COMMITTED);
        int tightEnd = base.indexOf(Position.TE);
        int distinct = 0;
        for(int to = 0; to < base.size(); to++){
            if(!ShapeSensitivity.render(ShapeSensitivity.move(base, tightEnd, to))
                    .equals(COMMITTED)){
                distinct++;
            }
        }
        Assertions.assertTrue(distinct >= 10,
                "a cost curve made of no-ops would read as 'this decision is free'; got "
                        + distinct + " genuinely different shapes");
    }

    @Test
    void legalMeansTheRosterHasADefence(){
        Assertions.assertTrue(ShapeSensitivity.legal(ShapeSensitivity.parse(COMMITTED)));
        Assertions.assertFalse(ShapeSensitivity.legal(ShapeSensitivity.parse(
                        "RB RB RB WR WR WR WR TE WR QB TE QB RB RB")),
                "no defence drafted is not a plan Justin can submit");
        Assertions.assertTrue(ShapeSensitivity.legal(ShapeSensitivity.parse(
                        "DEF RB RB WR WR WR WR TE WR QB TE QB RB RB")),
                "a defence anywhere is legal, however wasteful the round");
    }

    @Test
    void theCommittedSeasonsAreTheOnesPlanBacktestPrints(){
        double[] seasons = {2035, 1654, 2191, 1960, 2148};
        Assertions.assertEquals(1997.6, ShapeSensitivity.mean(seasons), 0.05);
        Assertions.assertEquals(1654, ShapeSensitivity.min(seasons), 0.001);
        Assertions.assertEquals(95.1, ShapeSensitivity.standardError(seasons), 0.5);
    }

    @Test
    void theTieBandIsTheMeasuredPowerThresholdNotTheCrudeStandardError(){
        // The distinction matters and has already caused one wrong reading. The
        // crude five-season error is about 95; the band is 125, the gap this
        // design detects 80% of the time under clustering on season. Declaring
        // ties at 95 would call a real difference a tie one time in five, so the
        // band must stay ABOVE the crude error, not equal to it.
        double crude = ShapeSensitivity.standardError(new double[]{2035, 1654, 2191, 1960, 2148});
        Assertions.assertEquals(125.0, ShapeSensitivity.TIE_BAND, 0.001);
        Assertions.assertTrue(ShapeSensitivity.TIE_BAND > crude,
                "a band tighter than the crude error would manufacture differences");
    }

    @Test
    void standardErrorIsZeroWhenNothingVaries(){
        Assertions.assertEquals(0, ShapeSensitivity.standardError(
                new double[]{1998, 1998, 1998, 1998, 1998}), 1e-9);
        Assertions.assertEquals(0, ShapeSensitivity.standardError(new double[]{1998}), 1e-9);
    }

    @Test
    void leaveOneOutDropsTheSeasonThatFlattersMost(){
        // A shape that beats the plan only because of one huge season: dropping
        // that season is what exposes it.
        ShapeSensitivity.Scored lucky = new ShapeSensitivity.Scored(
                "x", new double[]{1900, 1900, 2800, 1900, 1900}, 2080, 1900, 82, 40, 3, 1, true);
        Assertions.assertEquals(1900, ShapeSensitivity.worstLeaveOneOut(lucky), 0.001);

        ShapeSensitivity.Scored steady = new ShapeSensitivity.Scored(
                "y", new double[]{2080, 2080, 2080, 2080, 2080}, 2080, 2080, 82, 40, 3, 5, true);
        Assertions.assertEquals(2080, ShapeSensitivity.worstLeaveOneOut(steady), 0.001);
    }

    @Test
    void theDefenceCurveIsTheSameSweepDefenceRoundAlreadyRuns(){
        // DefenceRound slides the defence through every slot of the same
        // thirteen fixed picks. If `move` is right, the two tools build
        // identical sequences and must therefore print identical numbers -
        // which makes DefenceRound a free, independently written check on this
        // whole sweep. If they ever disagree, one of them is wrong.
        List<Position> base = ShapeSensitivity.parse(COMMITTED);
        int defence = base.lastIndexOf(Position.DEF);
        for(int at = 0; at < DefenceRound.WITHOUT_DEFENCE.length + 1; at++){
            List<String> theirs = new java.util.ArrayList<>(
                    List.of(DefenceRound.WITHOUT_DEFENCE));
            theirs.add(at, "DEF");
            Assertions.assertEquals(String.join(" ", theirs),
                    ShapeSensitivity.render(ShapeSensitivity.move(base, defence, at)),
                    "defence placed at slot " + at);
        }
    }

    @Test
    void theCommittedPlanIsItselfAPlanAHumanWouldWrite(){
        // If the honest denominator excluded the plan being tested, every
        // percentile in section 6b would be meaningless.
        Assertions.assertTrue(ShapeSensitivity.plausible(ShapeSensitivity.parse(COMMITTED)));
    }

    @Test
    void implausiblePlansAreTheOnesNobodyWouldSubmit(){
        Assertions.assertFalse(ShapeSensitivity.plausible(ShapeSensitivity.parse(
                        "RB RB RB WR WR WR WR TE WR QB TE QB RB RB")),
                "no defence at all");
        Assertions.assertFalse(ShapeSensitivity.plausible(ShapeSensitivity.parse(
                        "DEF RB RB WR WR WR WR TE WR QB TE QB RB RB")),
                "a defence with the seventh pick is not a plan anyone writes");
        Assertions.assertFalse(ShapeSensitivity.plausible(ShapeSensitivity.parse(
                        "RB RB RB WR WR WR WR TE WR QB TE DEF RB DEF")),
                "two defences wastes a pick on a slot that starts one");
        Assertions.assertFalse(ShapeSensitivity.plausible(ShapeSensitivity.parse(
                        "RB RB RB RB RB RB RB RB RB RB TE QB RB DEF")),
                "three receivers start every week; one is not a lineup");
        Assertions.assertFalse(ShapeSensitivity.plausible(ShapeSensitivity.parse(
                        "RB RB RB WR WR WR WR WR WR QB WR QB RB DEF")),
                "a tight end slot starts every week too");
        Assertions.assertTrue(ShapeSensitivity.plausible(ShapeSensitivity.parse(
                        "WR WR WR RB RB TE QB WR RB WR TE QB RB DEF")),
                "WR-heavy with a late defence is a plan a human writes");
    }

    @Test
    void defenceInRoundTenIsTheEarliestPlausible(){
        // Slot index 9 is round 10 - the first round this league ever sees a
        // defence go. Index 8 is round 9 and must be rejected.
        Assertions.assertTrue(ShapeSensitivity.plausible(ShapeSensitivity.parse(
                "RB RB RB WR WR WR WR TE WR DEF TE QB RB QB")));
        Assertions.assertFalse(ShapeSensitivity.plausible(ShapeSensitivity.parse(
                "RB RB RB WR WR WR WR TE DEF WR TE QB RB QB")));
    }

    @Test
    void percentileCountsHowMuchOfTheFamilyIsBelow(){
        List<ShapeSensitivity.Scored> family = List.of(
                scored(1800), scored(1900), scored(2000), scored(2100));

        Assertions.assertEquals(0, ShapeSensitivity.percentile(family, 1700), 0.001);
        Assertions.assertEquals(50, ShapeSensitivity.percentile(family, 1950), 0.001);
        Assertions.assertEquals(100, ShapeSensitivity.percentile(family, 2200), 0.001);
        Assertions.assertEquals(0, ShapeSensitivity.percentile(List.of(), 1998), 0.001,
                "an empty family must not divide by zero");
    }

    @Test
    void familySpreadIsTheSampleSdOfTheMeans(){
        List<ShapeSensitivity.Scored> family = List.of(
                scored(1800), scored(1900), scored(2000), scored(2100));
        Assertions.assertEquals(1950, ShapeSensitivity.familyMean(family), 0.001);
        Assertions.assertEquals(129.1, ShapeSensitivity.familySd(family), 0.1);
        Assertions.assertEquals(1800, ShapeSensitivity.familyMin(family), 0.001);
        Assertions.assertEquals(2100, ShapeSensitivity.familyMax(family), 0.001);
    }

    private static ShapeSensitivity.Scored scored(double mean){
        return new ShapeSensitivity.Scored("x", new double[]{mean}, mean, mean, 0, 0, 0, 0, true);
    }

    private static long count(List<Position> shape, Position position){
        return shape.stream().filter(p -> p == position).count();
    }
}
