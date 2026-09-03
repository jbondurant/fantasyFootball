import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The property Justin actually asked for: adjacent pick numbers give adjacent
 * answers.
 *
 * He rejected the bucketed surface because it jittered - a round-10 back could
 * read 10% between a round-9 at 8% and a round-11 at 13%. A recommendation that
 * "cannot jitter by construction" is worth nothing unless the construction is
 * tested, so these tests are on the construction and not on football: monotone
 * in, monotone out, through both smoothers, including the log-space one whose
 * window WIDTH changes as it slides - which is exactly the case where a moving
 * average could stop being monotone if the window were built carelessly.
 */
class PairwiseOddsTest {

    static void assertNonIncreasing(String what, double[] x){
        for(int i = 1; i < x.length; i++){
            assertTrue(x[i] <= x[i - 1] + 1e-12, what + " turned back up at " + i
                    + ": " + x[i - 1] + " then " + x[i]);
        }
    }

    @Test
    void isotonicRemovesEveryViolationAndKeepsTheCliff(){
        // a genuine cliff between 2 and 3, then noise that jitters
        double[] y = {5.0, 4.9, 1.0, 1.2, 0.9, 1.1, 0.8};
        double[] w = new double[y.length];
        java.util.Arrays.fill(w, 1);
        double[] fitted = PairwiseOdds.isotonicDecreasing(y, w);
        assertNonIncreasing("isotonic", fitted);
        // the cliff survives: the drop from index 1 to 2 is still most of the range
        assertTrue(fitted[1] - fitted[2] > 3.0,
                "PAVA must not smear a real cliff, got " + (fitted[1] - fitted[2]));
        // and the jitter after it is gone: 1.0 then 1.2 was a violation, so the
        // two are pooled to one level, and 0.9 then 1.1 likewise
        assertEquals(fitted[2], fitted[3], 1e-12);
        assertEquals(fitted[4], fitted[5], 1e-12);
    }

    @Test
    void bothSmoothersPreserveMonotonicity(){
        Random random = new Random(4);
        double[] raw = new double[60];
        for(int i = 0; i < raw.length; i++){
            raw[i] = 3.0 / (1 + i * 0.2) + random.nextGaussian();   // decreasing + noise
        }
        double[] weight = new double[raw.length];
        java.util.Arrays.fill(weight, 1);
        double[] fitted = PairwiseOdds.isotonicDecreasing(raw, weight);
        assertNonIncreasing("isotonic", fitted);
        for(int window : new int[]{1, 2, 3, 5, 8, 12, 20}){
            assertNonIncreasing("fixed window " + window,
                    PairwiseOdds.smooth(fitted, window));
        }
        for(double halfWidth : new double[]{0.15, 0.25, 0.40, 0.60, 0.90}){
            assertNonIncreasing("log window " + halfWidth,
                    PairwiseOdds.smoothLog(fitted, halfWidth));
        }
    }

    /**
     * The log-space window is narrow at the top of the board and wide in the
     * tail. That is the whole reason it is recommended over a fixed one, so it
     * is worth pinning rather than trusting.
     */
    @Test
    void theLogWindowIsNarrowEarlyAndWideLate(){
        double[] spike = new double[60];
        spike[5] = 1;                       // a single unit at rank 6
        double[] smoothed = PairwiseOdds.smoothLog(spike, 0.25);
        double early = smoothed[5];
        double[] deep = new double[60];
        deep[47] = 1;                       // a single unit at rank 48
        double late = PairwiseOdds.smoothLog(deep, 0.25)[47];
        assertTrue(early > late * 2, "a log window must spread a spike at rank 48"
                + " much further than one at rank 6: " + early + " against " + late);
    }

    /**
     * The latent form exists so the surface cannot contradict itself at the
     * draft. Equal ranks must be a coin flip and the two orderings of a pair
     * must sum to one, whatever the data did.
     */
    @Test
    void theSurfaceIsCoherent(){
        List<PairwiseOdds.Pair> training = new ArrayList<>();
        Random random = new Random(9);
        for(int season = 0; season < 12; season++){
            for(int early = 1; early <= 30; early++){
                for(int late = early + 1; late <= 30; late++){
                    boolean lateWon = random.nextDouble()
                            < 1 / (1 + Math.exp(0.06 * (late - early)));
                    training.add(new PairwiseOdds.Pair(season, Position.RB, early,
                            late, lateWon));
                }
            }
        }
        PairwiseOdds.Model model = PairwiseOdds.latent(training, 0, 0.25);
        for(int r : new int[]{3, 10, 25}){
            assertEquals(0.5, model.probability(Position.RB, r, r), 1e-9,
                    "equal ranks must be a coin flip");
        }
        for(int[] pair : new int[][]{{2, 9}, {5, 28}, {11, 12}}){
            double forward = model.probability(Position.RB, pair[0], pair[1]);
            double back = model.probability(Position.RB, pair[1], pair[0]);
            assertEquals(1.0, forward + back, 1e-9,
                    "P(P beats Q) and P(Q beats P) must sum to one");
        }
    }
}
