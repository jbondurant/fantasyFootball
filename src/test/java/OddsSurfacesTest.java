import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The round-two odds families, tested on their construction rather than on
 * football.
 *
 * Three of these are load-bearing for the conclusion rather than hygiene.
 *
 * OddsSurfaces reports a NULL RESULT - no challenger clears its own bar. An
 * estimator that is simply broken reports the same thing, and the two look
 * identical from outside, so each family is shown recovering something PLANTED
 * before its null is worth reading. That is what
 * theBradleyTerryCurveRecoversAPlantedStrength and
 * theKernelCorrectionFindsAPlantedMissTheLatentFormCannot are for.
 *
 * The kernel families claim P(r, r) = 0.5 and antisymmetry EXACTLY, bought by
 * entering every cell at its mirror. That is the whole reason they are a fair
 * comparison rather than a control, so it is asserted rather than argued.
 *
 * And btStrength is a near-copy of OddsFamilies.jointStrength with the spread
 * removed and the smoother exposed. The two are pinned to each other, so the
 * copy cannot drift into a different estimator wearing the same name.
 */
class OddsSurfacesTest {

    /** A decreasing latent strength with a known shape. */
    private static double planted(int rank){
        return -Math.log(rank);
    }

    /**
     * Expected-count cells from a known strength curve, optionally with a
     * log-odds bump applied to the pairs a filter accepts.
     *
     * No sampling: the test asks what the estimator does with a clean signal,
     * not what it does with one particular random draw.
     */
    private static OddsFamilies.Cells plant(int cap, double perCell, Miss miss){
        List<int[]> keys = new ArrayList<>();
        for(int e = 1; e <= cap; e++){
            for(int l = e + 1; l <= cap; l++){
                keys.add(new int[]{e, l});
            }
        }
        int[] early = new int[keys.size()];
        int[] late = new int[keys.size()];
        double[] count = new double[keys.size()];
        double[] wins = new double[keys.size()];
        for(int i = 0; i < keys.size(); i++){
            early[i] = keys.get(i)[0];
            late[i] = keys.get(i)[1];
            count[i] = perCell;
            double z = planted(late[i]) - planted(early[i])
                    + (miss == null ? 0 : miss.at(early[i], late[i]));
            wins[i] = perCell * OddsFamilies.sigmoid(z);
        }
        return new OddsFamilies.Cells(early, late, count, wins);
    }

    /** A departure from the latent form, in log odds, added to the planted pair. */
    private interface Miss {
        double at(int early, int late);
    }

    /** The corner step OddsFamilies diagnosed: deep against deep, and nowhere else. */
    private static final Miss CORNER = (early, late) ->
            early > 36 && late > 36 ? 0.8 : 0;

    /**
     * A smooth departure no latent strength can express. b1*d + b2*d*m IS latent -
     * d*m telescopes into (log late)^2 - (log early)^2 - but d^3 does not, so this
     * is the shape a second dimension would actually have to carry.
     */
    private static final Miss CUBIC = (early, late) -> {
        double d = Math.log(late) - Math.log(early);
        return 0.03 * d * d * d;
    };

    private static double[] plantedCurve(int cap){
        double[] curve = new double[cap + 1];
        for(int r = 1; r <= cap; r++){
            curve[r] = planted(r);
        }
        return curve;
    }

    /** The planted strength read as a latent model at every position. */
    private static PairwiseOdds.Model plantedModel(){
        Map<Position, double[]> curves = new EnumMap<>(Position.class);
        Map<Position, Double> alphas = new EnumMap<>(Position.class);
        for(Position position : PairwiseOdds.POSITIONS){
            curves.put(position, plantedCurve(PairwiseOdds.CAP.get(position)));
            alphas.put(position, 1.0);
        }
        return OddsFamilies.latentModel(curves, alphas, null);
    }

    // ==================================================== the Bradley-Terry fit

    /**
     * btStrength with the incumbent's smoother is the same estimator as
     * OddsFamilies.jointStrength at tau = 0. If the near-copy ever stops
     * agreeing, one of the two has quietly become a different model.
     */
    @Test
    void theBradleyTerryFitAgreesWithTheJointFitterAtZeroSpread(){
        int cap = 40;
        OddsFamilies.Cells cells = plant(cap, 300, null);
        double[] start = plantedCurve(cap);
        double[] mine = OddsSurfaces.btStrength(start, cells, cap,
                OddsFamilies.INCUMBENT_H, 20);
        double[] theirs = OddsFamilies.jointStrength(start, cells, cap, 0, 20);
        for(int r = 1; r <= cap; r++){
            assertEquals(theirs[r], mine[r], 1e-12,
                    "the two Bradley-Terry fitters parted company at rank " + r);
        }
    }

    /**
     * The null in table 2 is only worth reading if the likelihood fit could have
     * found a curve at all. Start it flat, plant a known one, and it has to walk
     * to it.
     */
    @Test
    void theBradleyTerryCurveRecoversAPlantedStrength(){
        int cap = 40;
        OddsFamilies.Cells cells = plant(cap, 300, null);
        double[] flat = new double[cap + 1];
        double[] fitted = OddsSurfaces.btStrength(flat, cells, cap, 0, 400);
        double[] truth = plantedCurve(cap);
        double fittedCentre = 0;
        double truthCentre = 0;
        for(int r = 1; r <= cap; r++){
            fittedCentre += fitted[r] / cap;
            truthCentre += truth[r] / cap;
        }
        double worst = 0;
        for(int r = 1; r <= cap; r++){
            worst = Math.max(worst, Math.abs((fitted[r] - fittedCentre)
                    - (truth[r] - truthCentre)));
        }
        assertTrue(worst < 0.02, "started flat, the likelihood fit is still "
                + worst + " away from the planted curve in log odds");
    }

    /**
     * Twenty rounds is asserted to be a fixed point. If it is not, every
     * Bradley-Terry number in the tool is reading iteration noise rather than a
     * fit.
     */
    @Test
    void theBradleyTerryIterationHasSettledByTwentyRounds(){
        int cap = 40;
        OddsFamilies.Cells cells = plant(cap, 300, null);
        double[] start = plantedCurve(cap);
        double[] twenty = OddsSurfaces.btStrength(start, cells, cap,
                OddsFamilies.INCUMBENT_H, OddsSurfaces.BT_ROUNDS);
        double[] many = OddsSurfaces.btStrength(start, cells, cap,
                OddsFamilies.INCUMBENT_H, 400);
        double worst = 0;
        for(int r = 1; r <= cap; r++){
            worst = Math.max(worst, Math.abs(twenty[r] - many[r]));
        }
        assertTrue(worst < 1e-3, "twenty rounds and four hundred still differ by "
                + worst + ", so the fit is not at a fixed point");
    }

    // ================================================ the parametric log-rank fits

    /**
     * The one-term LOGIT family is PairwiseOdds.logLinear by another route -
     * cell-weighted Newton instead of raw-pair Newton. They must land on the
     * same slope, or the LOGIT d row is measuring this file's arithmetic rather
     * than the family.
     */
    @Test
    void theOneTermLogitIsTheShippedLogLinearSlope(){
        int cap = 24;
        List<PairwiseOdds.Pair> pairs = new ArrayList<>();
        for(int season = 0; season < 6; season++){
            for(int e = 1; e <= cap; e++){
                for(int l = e + 1; l <= cap; l++){
                    // deterministic, and not a function of the model being fitted
                    boolean lateWon = ((season * 31 + e * 7 + l * 13) % 10) < 3;
                    pairs.add(new PairwiseOdds.Pair(season, Position.RB, e, l, lateWon));
                }
            }
        }
        double[] gap = new double[pairs.size()];
        boolean[] won = new boolean[pairs.size()];
        for(int i = 0; i < pairs.size(); i++){
            // PairwiseOdds.logLinear fits log(early) - log(late), which is the
            // negative of this file's d, so the slopes come out negated.
            gap[i] = Math.log(pairs.get(i).early()) - Math.log(pairs.get(i).late());
            won[i] = pairs.get(i).lateWon();
        }
        double onPairs = PairwiseOdds.fitSlope(gap, won);

        OddsFamilies.Cells cells = OddsFamilies.cellsOf(pairs, Position.RB, cap);
        double[][] design = new double[cells.size()][];
        for(int i = 0; i < cells.size(); i++){
            design[i] = OddsSurfaces.basis(cells.early()[i], cells.late()[i],
                    OddsSurfaces.PARAMETRIC[0]);
        }
        double onCells = OddsSurfaces.fitLogistic(design, cells.wins(), cells.count())[0];

        assertEquals(-onPairs, onCells, 1e-9,
                "the one-term LOGIT must be the shipped log-linear slope negated");
    }

    /**
     * Every LOGIT basis term carries a factor of d, so the family must be a coin
     * flip on the diagonal and antisymmetric off it - by construction, with no
     * projection step to enforce it. The three-parameter form is the one with
     * the most room to break it.
     */
    @Test
    void theParametricFamiliesAreExactlyFairAndAntisymmetric(){
        Map<Position, OddsFamilies.Cells> cells = new EnumMap<>(Position.class);
        for(Position position : PairwiseOdds.POSITIONS){
            cells.put(position, plant(PairwiseOdds.CAP.get(position), 200, null));
        }
        for(int[] terms : OddsSurfaces.PARAMETRIC){
            OddsFamilies.Properties property = OddsFamilies.properties(
                    OddsSurfaces.parametricModel(cells, terms));
            assertEquals(0.0, property.selfPlay(), 1e-12,
                    "a LOGIT family put a man against himself off a coin flip");
            assertEquals(0.0, property.antisymmetry(), 1e-12,
                    "a LOGIT family is not antisymmetric");
        }
    }

    /** The linear solver behind those fits, on a system with a known answer. */
    @Test
    void theLinearSolverSolves(){
        double[][] matrix = {{4, 1, 2}, {1, 3, 0}, {2, 0, 5}};
        double[] truth = {1.5, -2.0, 0.25};
        double[] rhs = new double[3];
        for(int i = 0; i < 3; i++){
            for(int j = 0; j < 3; j++){
                rhs[i] += matrix[i][j] * truth[j];
            }
        }
        double[] solved = OddsSurfaces.solve(matrix, rhs);
        for(int i = 0; i < 3; i++){
            assertEquals(truth[i], solved[i], 1e-10);
        }
    }

    // ====================================================== the kernel surfaces

    /**
     * The mirroring claim, which is what makes the kernel families a fair
     * comparison and not a control. Entering every cell at its mirror with the
     * win count flipped is supposed to buy P(r, r) = 0.5 and antisymmetry
     * EXACTLY, not approximately.
     */
    @Test
    void theKernelSurfaceIsExactlyFairAndAntisymmetric(){
        Map<Position, OddsFamilies.Cells> cells = new EnumMap<>(Position.class);
        for(Position position : PairwiseOdds.POSITIONS){
            cells.put(position, plant(PairwiseOdds.CAP.get(position), 200, null));
        }
        OddsFamilies.Properties property =
                OddsFamilies.properties(OddsSurfaces.kernelModel(cells, 0.25));
        assertEquals(0.0, property.selfPlay(), 1e-12,
                "the mirrored kernel put a man against himself off a coin flip");
        assertEquals(0.0, property.antisymmetry(), 1e-12,
                "the mirrored kernel surface is not antisymmetric");
    }

    /** And the same for the correction, which adds an antisymmetric field to a latent base. */
    @Test
    void theKernelCorrectionIsExactlyFairAndAntisymmetric(){
        Map<Position, OddsFamilies.Cells> cells = new EnumMap<>(Position.class);
        for(Position position : PairwiseOdds.POSITIONS){
            cells.put(position, plant(PairwiseOdds.CAP.get(position), 200, CORNER));
        }
        PairwiseOdds.Model base = plantedModel();
        Map<Position, double[][]> corrections = new EnumMap<>(Position.class);
        for(Position position : PairwiseOdds.POSITIONS){
            int cap = PairwiseOdds.CAP.get(position);
            corrections.put(position, OddsSurfaces.correctionGrid(base, position,
                    cells.get(position), cap, 0.25));
        }
        PairwiseOdds.Model corrected = (position, early, late) -> {
            double p = base.probability(position, early, late);
            double[][] grid = corrections.get(position);
            double clipped = Math.min(1 - 1e-12, Math.max(1e-12, p));
            return OddsFamilies.sigmoid(Math.log(clipped / (1 - clipped))
                    + grid[early][late]);
        };
        OddsFamilies.Properties property = OddsFamilies.properties(corrected);
        assertEquals(0.0, property.selfPlay(), 1e-12,
                "the kernel correction moved P(r, r) off a coin flip");
        assertEquals(0.0, property.antisymmetry(), 1e-12,
                "the kernel correction broke antisymmetry");
    }

    /**
     * The correction nests the incumbent: given a base model that is already
     * exactly right on every cell, there is nothing to correct and it has to
     * return zero.
     */
    @Test
    void theKernelCorrectionIsZeroWhenTheBaseIsAlreadyRight(){
        int cap = 30;
        OddsFamilies.Cells cells = plant(cap, 400, null);
        double[][] grid = OddsSurfaces.correctionGrid(plantedModel(), Position.QB, cells,
                cap, 0.25);
        double worst = 0;
        for(int e = 1; e <= cap; e++){
            for(int l = 1; l <= cap; l++){
                worst = Math.max(worst, Math.abs(grid[e][l]));
            }
        }
        assertTrue(worst < 1e-9, "the base model was exactly right and the correction"
                + " still moved the log odds by " + worst);
    }

    /**
     * And the null is only worth reading if the correction could have found a
     * miss. Plant a surface no latent strength can express - a bump in the
     * deep-against-deep corner only, which is the shape OddsFamilies diagnosed -
     * and it has to come back there and nowhere else.
     */
    @Test
    void theKernelCorrectionFindsAPlantedMissTheLatentFormCannot(){
        int cap = 60;
        PairwiseOdds.Model base = plantedModel();
        OddsFamilies.Cells cells = plant(cap, 400, CORNER);
        double baseLoss = cellLoss(base, Position.RB, cells);
        double correctedLoss = cellLoss(corrected(base, Position.RB, cells, cap, 0.25),
                Position.RB, cells);
        double truthLoss = cellLoss(truth(CORNER), Position.RB, cells);
        double closed = (baseLoss - correctedLoss) / (baseLoss - truthLoss);
        assertTrue(closed > 0.15, "a miss no latent curve can express was planted and"
                + " the correction closed only " + closed + " of the gap to it");

        double[][] grid = OddsSurfaces.correctionGrid(base, Position.RB, cells, cap, 0.25);
        assertTrue(grid[44][58] > 0.15, "the planted miss is +0.8 deep against deep and"
                + " the correction found only " + grid[44][58] + " there");
        assertEquals(0.0, grid[4][12], 1e-9, "nothing was planted at the top of the"
                + " board and the correction invented something anyway");
    }

    /** Given nothing to find, the correction has to leave the base model alone. */
    @Test
    void theKernelCorrectionInventsNothingWhenNothingIsThere(){
        int cap = 60;
        PairwiseOdds.Model base = plantedModel();
        OddsFamilies.Cells cells = plant(cap, 400, null);
        assertEquals(cellLoss(base, Position.RB, cells),
                cellLoss(corrected(base, Position.RB, cells, cap, 0.25), Position.RB, cells),
                1e-9, "the base model was already the truth and the correction moved it");
    }

    /**
     * A property of the correction, pinned so it is known rather than a surprise:
     * ONE Newton step overshoots badly where the base probability is extreme.
     *
     * The step is (p_true - p_base) / (p_base * (1 - p_base)), which is the exact
     * log-odds move only in the limit of a small one. RB1 against RB60 sits at
     * p_base = 0.016, so a miss of 2.1 in log odds is answered with a far larger
     * step. This costs nothing on the real board - the incumbent's diagnosed
     * misses are a few points of probability, near the middle of the range, where
     * one step is close to exact - but it is why a family that nests the incumbent
     * can still come out WORSE than it on data with a big miss in the tail, and
     * anyone reading section 2 should know that is possible.
     */
    @Test
    void theKernelCorrectionOvershootsWhereTheBaseProbabilityIsExtreme(){
        int cap = 60;
        OddsFamilies.Cells cells = plant(cap, 400, CUBIC);
        double[][] grid = OddsSurfaces.correctionGrid(plantedModel(), Position.RB, cells,
                cap, 0.25);
        double planted = CUBIC.at(1, 60);
        assertTrue(grid[1][60] > 1.5 * planted, "the overshoot in the extreme tail is"
                + " documented behaviour and it did not happen: planted " + planted
                + ", corrected by " + grid[1][60]);
    }

    /** Mean log loss per pair over the cells, which is PairwiseOdds.logLoss summed by cell. */
    private static double cellLoss(PairwiseOdds.Model model, Position position,
                                   OddsFamilies.Cells cells){
        double total = 0;
        double pairs = 0;
        for(int i = 0; i < cells.size(); i++){
            double p = Math.min(1 - 1e-6, Math.max(1e-6,
                    model.probability(position, cells.early()[i], cells.late()[i])));
            total += -cells.wins()[i] * Math.log(p)
                    - (cells.count()[i] - cells.wins()[i]) * Math.log(1 - p);
            pairs += cells.count()[i];
        }
        return total / pairs;
    }

    /** The base model with the kernel correction added to its log odds. */
    private static PairwiseOdds.Model corrected(PairwiseOdds.Model base, Position position,
                                                OddsFamilies.Cells cells, int cap,
                                                double halfWidth){
        double[][] grid = OddsSurfaces.correctionGrid(base, position, cells, cap, halfWidth);
        return (at, early, late) -> {
            double p = base.probability(at, early, late);
            double clipped = Math.min(1 - 1e-12, Math.max(1e-12, p));
            return OddsFamilies.sigmoid(Math.log(clipped / (1 - clipped))
                    + grid[early][late]);
        };
    }

    /** The surface the cells were actually generated from. */
    private static PairwiseOdds.Model truth(Miss miss){
        return (position, early, late) -> OddsFamilies.sigmoid(planted(late)
                - planted(early) + (miss == null ? 0 : miss.at(early, late)));
    }

    // ============================================== pricing ten comparisons

    /**
     * The floor of the sign-flip test. A challenger that wins every fold has the
     * largest |t| the enumeration can produce, and exactly two of the assignments
     * reach it - all signs kept and all signs flipped - so its p-value is
     * 2 / 2^folds and cannot be smaller. With twelve folds that is 4096 flips.
     */
    @Test
    void aCleanSweepGetsTheSmallestPossibleSignFlipPValue(){
        int folds = 12;
        double[][] diffs = new double[1][folds];
        for(int s = 0; s < folds; s++){
            diffs[0][s] = -0.010 - 0.0004 * s;         // every fold favours it, not identically
        }
        double[][] p = OddsSurfaces.signFlipPValues(diffs);
        assertEquals(2.0 / (1 << folds), p[0][0], 1e-12,
                "a clean sweep should sit at the floor of the enumeration");
        assertEquals(2.0 / (1 << folds), p[1][0], 1e-12,
                "with one challenger, family-wise and alone are the same test");
    }

    /**
     * The point of the whole section: adding challengers must make the winner's
     * p-value WORSE, because the null it is read against is now the largest |t|
     * any of them reached. A test that did not do this would be pricing nothing.
     */
    @Test
    void tryingMoreFamiliesMakesTheWinnersPValueWorse(){
        int folds = 12;
        // Nine folds favour it and three do not, so its |t| is well short of the
        // largest the enumeration can reach and there is room for the p-value to
        // move. A clean sweep is already at the floor and cannot be made worse.
        double[] winner = new double[folds];
        for(int s = 0; s < folds; s++){
            winner[s] = s % 4 == 0 ? 0.003 : -0.004;
        }
        double alone = OddsSurfaces.signFlipPValues(new double[][]{winner})[1][0];
        double[][] crowd = new double[6][folds];
        crowd[0] = winner;
        for(int m = 1; m < 6; m++){
            for(int s = 0; s < folds; s++){
                // deterministic noise, no seed, and nothing that sweeps the folds
                crowd[m][s] = 0.003 * Math.sin(m * 2.1 + s * 1.7);
            }
        }
        double crowded = OddsSurfaces.signFlipPValues(crowd)[1][0];
        assertTrue(crowded > alone, "six families and one gave the winner the same"
                + " family-wise p-value, " + crowded + " against " + alone
                + ", so the multiplicity is not being priced");
    }

    /** And a family that did nothing has to come back at the top of the range. */
    @Test
    void aFamilyThatDidNothingIsNotSignificant(){
        int folds = 12;
        double[][] diffs = new double[1][folds];
        for(int s = 0; s < folds; s++){
            diffs[0][s] = s % 2 == 0 ? 0.001 : -0.001;
        }
        double[][] p = OddsSurfaces.signFlipPValues(diffs);
        assertTrue(p[0][0] > 0.5, "a family with no signal came back at p = " + p[0][0]);
    }

    /**
     * The kernel surface has to track a planted surface, or its log loss is
     * measuring a smoother that never fitted anything.
     *
     * It does not track it exactly, and is not meant to: a Nadaraya-Watson
     * average is biased wherever the surface curves, which here is the steep top
     * of the board. So the residual is also shown SHRINKING as the bandwidth
     * narrows, which is what separates smoothing bias from a fit that never
     * happened.
     */
    @Test
    void theKernelSurfaceTracksAPlantedSurface(){
        int cap = 60;
        OddsFamilies.Cells cells = plant(cap, 400, null);
        double wide = worstMiss(OddsSurfaces.kernelSurfaceGrid(cells, cap, 0.25));
        double narrow = worstMiss(OddsSurfaces.kernelSurfaceGrid(cells, cap, 0.10));
        assertTrue(wide < 0.07, "the kernel surface is off the planted one by "
                + wide + " at its worst");
        assertTrue(narrow < wide, "narrowing the bandwidth from 0.25 to 0.10 left the"
                + " worst miss at " + narrow + " against " + wide + ", so the residual"
                + " is not the smoother's bias");
    }

    /** The largest probability disagreement with the planted surface, over a spread of pairs. */
    private static double worstMiss(double[][] grid){
        double worst = 0;
        for(int e : new int[]{2, 6, 12, 24, 48}){
            for(int l : new int[]{6, 12, 24, 48, 60}){
                if(l <= e){
                    continue;
                }
                worst = Math.max(worst,
                        Math.abs(OddsFamilies.sigmoid(planted(l) - planted(e)) - grid[e][l]));
            }
        }
        return worst;
    }
}
