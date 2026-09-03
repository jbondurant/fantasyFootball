import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TRAPS.md D16 and section F: the discipline around a measurement, rather than
 * the measurement.
 *
 *   D16  the bar is real and large. Every gap smaller than it is a TIE, and must
 *        be reported as one - including gaps we spent days ranking.
 *   F25  a default that means "never deviate" reproduces the committed plan and
 *        looks perfect. -Pdeviate defaulted to 1e9.
 *   F26  Model A is a ROUNDS 1-7 model. Two keepers plus seven picks fills the
 *        starting nine; after that its objective is indifferent and prints
 *        whatever. Scoring it as a 14-round strategy measures nothing.
 *
 * F27, prose drift, is in ProseDriftTest - it needs to read source rather than
 * call it.
 */
class ModelDisciplineTest {

    // =====================================================================
    // D16. A gap smaller than the bar is a tie.
    // =====================================================================

    /** n seasons, each contributing `each` draws, clustered in order. */
    private static int[] clusters(int seasons, int each){
        int[] clusterOf = new int[seasons * each];
        for(int k = 0; k < clusterOf.length; k++){
            clusterOf[k] = k / each;
        }
        return clusterOf;
    }

    /** Seasons that differ by a known amount, with no within-season noise. */
    private static PowerBacktest.Paired seasonal(double[] perSeason, int each){
        double[] diff = new double[perSeason.length * each];
        for(int k = 0; k < diff.length; k++){
            diff[k] = perSeason[k / each];
        }
        return PowerBacktest.paired("fixture", new double[diff.length], diff,
                clusters(perSeason.length, each), perSeason.length);
    }

    /**
     * The verdict is the bar, never the mean and never the win rate.
     *
     * These five seasons hand the challenger +100 on average and it wins four of
     * them - which reads as a convincing improvement and is a TIE. Every finding
     * this repo has had to withdraw looked exactly like this row.
     */
    @Test
    void anImprovementInsideTheBarIsATieHoweverGoodItLooks(){
        PowerBacktest.Paired row = seasonal(new double[]{260, 180, 120, 40, -100}, 96);

        assertEquals(100.0, row.diff(), 1e-9, "a +100 mean");
        assertEquals(4 * 96, row.wins(), "and it wins four seasons out of five");
        assertTrue(row.bar() > row.diff(),
                "the bar must exceed +100 on this spread, got " + row.bar());
        assertFalse(row.real(),
                "+100 with a " + Math.round(row.bar()) + "-point bar was reported"
                        + " as a win. It is a tie.");
    }

    /** And a gap that genuinely clears the bar is not called a tie either. */
    @Test
    void aGapPastTheBarIsCalledReal(){
        PowerBacktest.Paired row = seasonal(new double[]{620, 540, 480, 400, 460}, 96);

        assertTrue(row.diff() > row.bar(),
                "the fixture must clear its own bar: " + row.diff() + " against "
                        + row.bar());
        assertTrue(row.real(), "a gap past the bar must be reported as real");
    }

    /**
     * The decision is on the CLUSTERED error, so a challenger cannot buy
     * significance by drafting from more slots or more opponent worlds.
     *
     * Same five seasons, ten times the draws each. The bar does not move.
     */
    @Test
    void moreDrawsInsideASeasonDoNotShrinkTheBar(){
        double[] seasons = {260, 180, 120, 40, -100};
        PowerBacktest.Paired few = seasonal(seasons, 12);
        PowerBacktest.Paired many = seasonal(seasons, 480);

        assertEquals(few.bar(), many.bar(), 1e-6,
                "forty times the draws moved the bar - the error is not clustered"
                        + " on season, and 480 draws are being read as 480 observations");
        assertFalse(many.real());
    }

    /**
     * The two published figures are one arithmetic step apart, so they cannot
     * drift from each other.
     *
     * MODEL.md: a 94-point significance bar at five seasons and a 125-point
     * minimum detectable effect. The second is the first plus the 80% power
     * term, on the same standard error, and that is asserted here rather than
     * left as two numbers in a paragraph.
     */
    @Test
    void theNinetyFourPointBarAndTheHundredAndTwentyFivePointEffectAgree(){
        double seSeason = 94.0 / PowerBacktest.t975(4);

        assertEquals(94.0, PowerBacktest.t975(4) * seSeason, 0.5);
        assertEquals(125.0, PowerBacktest.minimumDetectable(seSeason, 5), 1.0,
                "the published 125-point detectable effect no longer follows from"
                        + " the published 94-point significance bar");
    }

    /**
     * And sixteen seasons is a smaller bar than five on the same season-to-season
     * spread, which is the only axis that ever helped.
     */
    @Test
    void sixteenSeasonsBuysARealReductionAndMoreSlotsDoNot(){
        double sd = 100.0;
        double barAtFive = PowerBacktest.t975(4) * sd / Math.sqrt(5);
        double barAtSixteen = PowerBacktest.t975(15) * sd / Math.sqrt(16);

        assertTrue(barAtSixteen < barAtFive,
                "seasons are the axis that helps: " + barAtSixteen + " against "
                        + barAtFive);
        assertTrue(barAtSixteen < 0.5 * barAtFive,
                "and the reduction is large, not cosmetic");
    }

    // =====================================================================
    // F25. A default that silently disables the thing under test.
    // =====================================================================

    /**
     * -Pdeviate defaults to 0, which means "ignore the prior".
     *
     * It defaulted to 1e9, meaning "never leave the committed plan", and a
     * nightly backtest came back scoring exactly the committed plan in all five
     * seasons and choosing its exact sequence. That was not agreement, it was
     * the plan replaying itself. Opting INTO the prior has to be the deliberate
     * act, so the default is asserted rather than commented.
     */
    @Test
    void deviateDefaultsToIgnoringThePriorAndNotToReplayingIt(){
        assertEquals(0.0, PolicyBacktest.DEVIATE, 1e-9,
                "a non-zero -Pdeviate default pins the search to the committed plan"
                        + " and every backtest then measures the prior");
        assertTrue(PolicyBacktest.FRONT_SHAPE.isBlank(),
                "-PfrontShape defaults to pinning nothing, for the same reason");
    }

    // =====================================================================
    // F26. Model A is a rounds 1-7 model.
    // =====================================================================

    /**
     * The objective goes flat once the nine skill slots are full.
     *
     * Two keepers plus seven picks IS the starting nine. From the eighth pick
     * on, best-nine cannot tell one position from another, and the live run
     * shows it: at round 9 the four columns print 1813.0, 1813.0, 1813.0,
     * 1813.0.
     *
     * So the shape's first seven picks are a claim and the rest is filler.
     * Scoring `RB WR RB WR WR WR TE QB QB QB QB QB QB DEF` as a 14-round
     * strategy measures the filler.
     */
    @Test
    void bestNineCannotTellOnePositionFromAnotherOnceTheNineAreFull(){
        Map<String, Double> points = new HashMap<>();
        List<String> nine = new ArrayList<>();
        // a full, comfortably-better starting nine
        add(points, nine, "qb1", 380);
        add(points, nine, "rb1", 300);
        add(points, nine, "rb2", 280);
        add(points, nine, "wr1", 290);
        add(points, nine, "wr2", 270);
        add(points, nine, "wr3", 260);
        add(points, nine, "te1", 200);
        add(points, nine, "rb3", 250);      // flex
        add(points, nine, "wr4", 240);      // flex

        double full = StartingLineup.bestNine(nine, points, BY_NAME);
        assertEquals(380 + 300 + 280 + 290 + 270 + 260 + 200 + 250 + 240, full, 1e-9,
                "the fixture's nine are all starting, so nothing below is masked");

        // five more picks, one of each position, every one of them worse
        for(String extra : new String[]{"qbX", "rbX", "wrX", "teX", "rbY"}){
            List<String> longer = new ArrayList<>(nine);
            points.put(extra, 150.0);
            longer.add(extra);
            assertEquals(full, StartingLineup.bestNine(longer, points, BY_NAME), 1e-9,
                    "best-nine moved when " + extra + " was added; while it does not,"
                            + " the objective past the nine is choosing at random");
        }
    }

    /**
     * And the indifference is total, not merely a tie at the top: the eighth
     * pick's four positions all return the same number, which is what makes the
     * argmax print whatever the enum order happens to be.
     */
    @Test
    void everyPositionScoresIdenticallyOnceTheNineAreFull(){
        Map<String, Double> points = new HashMap<>();
        List<String> nine = new ArrayList<>();
        add(points, nine, "qb1", 380);
        add(points, nine, "rb1", 300);
        add(points, nine, "rb2", 280);
        add(points, nine, "wr1", 290);
        add(points, nine, "wr2", 270);
        add(points, nine, "wr3", 260);
        add(points, nine, "te1", 200);
        add(points, nine, "rb3", 250);
        add(points, nine, "wr4", 240);
        double full = StartingLineup.bestNine(nine, points, BY_NAME);

        for(String candidate : new String[]{"qbZ", "rbZ", "wrZ", "teZ"}){
            List<String> longer = new ArrayList<>(nine);
            points.put(candidate, 199.0);
            longer.add(candidate);
            assertEquals(full, StartingLineup.bestNine(longer, points, BY_NAME), 1e-9,
                    candidate + " changed the objective, so the tenth man is being"
                            + " scored - which the nine-round game cannot do");
        }
    }

    /** Position from the fixture's own naming, so no player dump is needed. */
    private static final StartingLineup.PositionLookup BY_NAME =
            id -> Position.valueOf(id.substring(0, 2).toUpperCase());

    /**
     * Stated as the domain rule, so a harness cannot quietly score it past its
     * edge: two keepers plus seven picks fills the starting nine exactly.
     */
    @Test
    void twoKeepersPlusSevenPicksFillsTheStartingNine(){
        assertEquals(9, StartingLineup.SKILL_SLOTS);
        assertEquals(9, 2 + 7, "the domain of Model A is rounds 1-7");
        assertEquals(9, StartingLineup.lastStarterRound(),
                "the nine-round game is nine picks with no keepers, seven with two");
    }

    /**
     * The committed Model A shape is exactly what that predicts: seven real
     * picks and then a tail the objective is indifferent to.
     *
     * The tail here is six quarterbacks. Nobody believes that; it is what an
     * indifferent argmax prints. This asserts the tail is DEGENERATE rather than
     * asserting it is right, which is the honest thing to pin.
     */
    @Test
    void theModelAStrategyStringHasAnIndifferentTail(){
        String shape = PlanBacktest.STRATEGIES.get("best-nine (Model A)");
        String[] picks = shape.trim().split("\\s+");

        assertEquals(14, picks.length);
        // the seven picks the objective can actually distinguish
        assertEquals("RB WR RB WR WR WR TE",
                String.join(" ", java.util.Arrays.copyOfRange(picks, 0, 7)),
                "Model A's rounds 1-7 are its whole content, and DraftPlanner"
                        + " still produces them");

        int quarterbacks = 0;
        for(int pick = 7; pick < 13; pick++){
            if(picks[pick].equals("QB")){
                quarterbacks++;
            }
        }
        assertTrue(quarterbacks >= 4,
                "the tail stopped being degenerate, which means somebody has"
                        + " started reading it as advice: " + shape);
        assertTrue(quarterbacks > 1,
                "a roster starting ONE quarterback would never buy " + quarterbacks
                        + " of them - this tail is indifference, not a plan");
    }

    private static void add(Map<String, Double> points, List<String> roster,
                            String id, double value){
        points.put(id, value);
        roster.add(id);
    }
}
