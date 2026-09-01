import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The odds-surface families PairwiseOdds and OddsFamilies did not try.
 *
 * Justin asked whether the pairwise surface should be tested against other
 * models, "such as another boosted model". Boosting has already been answered:
 * OddsFamilies scored BOOSTED STRENGTH and BOOSTED SURFACE against the incumbent
 * and both came back REAL, AND WORSE. Those two rows are re-fitted here rather
 * than quoted, so every family in the question sits in ONE table on ONE protocol,
 * fold by fold.
 *
 * The incumbent is PairwiseOdds' ISOTONIC + LOG-SMOOTH h=0.25: a latent strength
 * s(rank), non-increasing, smoothed at constant width in log rank, read as
 * logit p = alpha * (s(P) - s(Q)). Held-out log loss 0.59991 over sixteen
 * seasons. NOTHING HERE CHANGES IT - this file has no path into the draft tools
 * and PairwiseOdds is untouched.
 *
 * THE FAMILIES, all eight fixed before any score was read, all eight reported.
 * Selecting families after seeing their scores finds the luckiest, not the best.
 *
 *   BT-MLE            Bradley-Terry properly. The incumbent estimates s(rank)
 *                     MARGINALLY - the logit of a rank's win rate against the
 *                     whole field - and a comment in PairwiseOdds.strength
 *                     claims iterating the likelihood instead "lands in the same
 *                     place" at this sample size. That is a testable claim about
 *                     the shipped code, so it is tested: Newton on the pairwise
 *                     likelihood, projected onto a non-increasing curve by
 *                     pool-adjacent-violators every round, iterated to a fixed
 *                     point. Reported unsmoothed and with the incumbent's own
 *                     h=0.25 log-rank smoother.
 *   LOGIT d           logit p = b1 * d, d = log(late rank) - log(early rank),
 *                     one slope a position. Identical to PairwiseOdds.logLinear;
 *                     kept as a cross-check that this file's cell-weighted
 *                     Newton reproduces the raw-pair one.
 *   LOGIT d + d*m     m is the log of the pair's geometric-mean rank, so b2 lets
 *                     the odds per unit of log gap change with how deep on the
 *                     board the pair sits. IT IS STILL A LATENT CURVE, which is
 *                     worth saying out loud because it does not look like one:
 *                     d*m telescopes into ((log late)^2 - (log early)^2) / 2, so
 *                     this is exactly a latent strength
 *                     s(r) = b1*log r + (b2/2)*(log r)^2 - a two-parameter
 *                     PARAMETRIC rival to the incumbent's non-parametric curve,
 *                     not a second dimension.
 *   LOGIT d + d^3     d^3 does NOT telescope, so this one really does leave the
 *                     latent family: the odds depend on the size of the gap in a
 *                     way no per-rank strength can reproduce.
 *   LOGIT d+d*m+d^3   both at once, three parameters a position.
 *   KERNEL SURFACE    the structure abandoned, but smoothly: a Nadaraya-Watson
 *                     average of the real win rates over a Gaussian kernel in
 *                     (log early, log late), bandwidth h. The boosted surface
 *                     answered "trees instead of a curve" and lost by 0.012;
 *                     this asks whether that was the surface or the trees.
 *   INCUMBENT+KERNEL  the incumbent plus a kernel-smoothed one-step Newton
 *                     correction to its log odds. This NESTS the incumbent -
 *                     a zero correction returns it exactly - so it is the most
 *                     favourable form the "add a second dimension" question can
 *                     take, and the direct test of the deep-against-deep miss
 *                     OddsFamilies diagnosed at +7.9 points. ONE step, not
 *                     iterated: that is exact for a small correction and
 *                     OVERSHOOTS where the base probability is extreme, so
 *                     nesting the incumbent does not stop this family scoring
 *                     WORSE than it. OddsSurfacesTest pins both halves of that.
 *   BOOSTED x2        OddsFamilies' two boosted families, re-fitted here.
 *
 * WHAT THE PARAMETRIC AND KERNEL FAMILIES KEEP. Every LOGIT basis term carries a
 * factor of d, and swapping the two ranks flips the sign of d while leaving m
 * alone, so the score is odd under the swap: P(r, r) = 0.5 and
 * P(a, b) = 1 - P(b, a) hold by construction, and there is no intercept. The
 * kernel families get the same two properties a different way - every observed
 * cell is entered at its mirror with the win count flipped, and a kernel that is
 * symmetric in its two arguments then cannot produce an asymmetric answer.
 * Monotonicity and transitivity are NOT protected in either family; section 4
 * measures what they cost.
 *
 * THE PROTOCOL IS THE INCUMBENT'S, UNCHANGED. Sixteen leave-one-SEASON-out
 * folds, never held out by pair: every pair inside one season is scored on the
 * same realised football, and holding out pairs would shrink the error bar by
 * roughly sqrt(4115) and report a certainty that is not there. Scored by held-out
 * log loss, barred with PowerBacktest.paired clustered on season - the same
 * statistic that prices the 125-point draft bar.
 *
 * NO BANDWIDTH AND NO TREE SETTING IS CHOSEN HERE. The headline table uses
 * h=0.25 for both kernel families because that is the incumbent's own smoothing
 * width, fixed in advance, not because it scored best; section 5 varies it with
 * every row and every bar shown, and selects nothing from it. The boosted
 * settings are OddsFamilies' copied 300/2/0.1.
 *
 *   ./gradlew run -Pmain=OddsSurfaces -q
 */
public class OddsSurfaces {

    /** Newton rounds for the Bradley-Terry curve. OddsSurfacesTest pins this as a fixed point. */
    static final int BT_ROUNDS = 20;

    /**
     * Laplace pseudo-count for the kernel surface, in pairs.
     *
     * It only bites where the smoothed count is small, which on this grid means
     * the far corner of the deepest position; everywhere else the kernel already
     * sums thousands of pairs.
     */
    static final double KERNEL_PRIOR = 1.0;

    /**
     * Ridge on the kernel correction's denominator, in units of smoothed Fisher
     * information. Same role as KERNEL_PRIOR: it stops a division by a nearly
     * empty neighbourhood and is negligible everywhere else.
     */
    static final double CORRECTION_RIDGE = 1.0;

    /** Log-rank bandwidths shown in section 5. Nothing is selected from that sweep. */
    static final double[] BANDWIDTHS = {0.15, 0.25, 0.40, 0.60};

    /**
     * Which basis terms each LOGIT family uses, indexing {d, d*m, d^3}.
     *
     * d is the log rank ratio and m the log of the pair's geometric-mean rank.
     */
    static final int[][] PARAMETRIC = {{0}, {0, 1}, {0, 2}, {0, 1, 2}};

    // -------------------------------------------------------- Bradley-Terry

    /**
     * The latent strength by maximum likelihood instead of marginally.
     *
     * One diagonal Newton step per rank on the pairwise Bradley-Terry
     * likelihood - each cell pushes its late man up and its early man down by
     * the same residual - then pool-adjacent-violators onto a non-increasing
     * curve, then optionally the log-rank smoother, then centring. Iterated
     * `rounds` times.
     *
     * Centring is not cosmetic: only DIFFERENCES of the curve are identified, so
     * its level is free to drift and, left alone, it does. Pinning it is what
     * makes "has the iteration settled" a question the curve can answer, and
     * OddsSurfacesTest asks it.
     *
     * With halfWidth = PairwiseOdds' 0.25 this is exactly
     * OddsFamilies.jointStrength at tau = 0; the test asserts that equality so
     * the two cannot drift apart.
     */
    static double[] btStrength(double[] start, OddsFamilies.Cells cells, int cap,
                               double halfWidth, int rounds){
        double[] mu = new double[cap];
        System.arraycopy(start, 1, mu, 0, cap);
        double[] pairsAt = new double[cap];
        for(int i = 0; i < cells.size(); i++){
            pairsAt[cells.early()[i] - 1] += cells.count()[i];
            pairsAt[cells.late()[i] - 1] += cells.count()[i];
        }
        double[] gradient = new double[cap];
        double[] hessian = new double[cap];
        for(int round = 0; round < rounds; round++){
            Arrays.fill(gradient, 0);
            Arrays.fill(hessian, 0);
            for(int i = 0; i < cells.size(); i++){
                int e = cells.early()[i] - 1;
                int l = cells.late()[i] - 1;
                double p = OddsFamilies.sigmoid(mu[l] - mu[e]);
                double g = cells.wins()[i] - cells.count()[i] * p;
                double h = cells.count()[i] * p * (1 - p);
                gradient[l] += g;
                gradient[e] -= g;
                hessian[l] += h;
                hessian[e] += h;
            }
            for(int r = 0; r < cap; r++){
                mu[r] += gradient[r] / (hessian[r] + OddsFamilies.RIDGE);
            }
            mu = PairwiseOdds.isotonicDecreasing(mu, pairsAt);
            if(halfWidth > 0){
                mu = PairwiseOdds.smoothLog(mu, halfWidth);
            }
            double centre = 0;
            for(double value : mu){
                centre += value / cap;
            }
            for(int r = 0; r < cap; r++){
                mu[r] -= centre;
            }
        }
        double[] curve = new double[cap + 1];
        System.arraycopy(mu, 0, curve, 1, cap);
        return curve;
    }

    /**
     * A Bradley-Terry model, one curve a position.
     *
     * The iteration starts from the incumbent's own marginal curve, and the
     * calibration slope is fitted afterwards exactly as PairwiseOdds fits it, so
     * the only thing that differs from the incumbent is how s(rank) was
     * estimated.
     */
    static PairwiseOdds.Model btModel(List<PairwiseOdds.Pair> training,
                                      Map<Position, OddsFamilies.Cells> cells,
                                      double halfWidth){
        Map<Position, double[]> curves = new EnumMap<>(Position.class);
        Map<Position, Double> alphas = new EnumMap<>(Position.class);
        for(Position position : PairwiseOdds.POSITIONS){
            int cap = PairwiseOdds.CAP.get(position);
            OddsFamilies.Cells mine = cells.get(position);
            double[] curve = btStrength(
                    OddsFamilies.incumbentCurve(training, position, cap), mine, cap,
                    halfWidth, BT_ROUNDS);
            curves.put(position, curve);
            alphas.put(position, OddsFamilies.fitSlopeWeighted(
                    OddsFamilies.spreadGaps(curve, mine, 0), mine.wins(), mine.count()));
        }
        return OddsFamilies.latentModel(curves, alphas, null);
    }

    // ------------------------------------------------ parametric log-rank fits

    /**
     * The basis for one rank pair, selecting from {d, d*m, d^3}.
     *
     * d = log(late) - log(early) is the log rank ratio; m is the log of the
     * pair's geometric-mean rank. Every term has a factor of d in it, so the
     * whole score is odd under swapping the two ranks.
     */
    static double[] basis(int early, int late, int[] terms){
        double d = Math.log(late) - Math.log(early);
        double m = 0.5 * (Math.log(late) + Math.log(early));
        double[] all = {d, d * m, d * d * d};
        double[] out = new double[terms.length];
        for(int i = 0; i < terms.length; i++){
            out[i] = all[terms[i]];
        }
        return out;
    }

    /**
     * Weighted binomial logistic regression on cells, by Newton.
     *
     * NO INTERCEPT, deliberately: an intercept is even under the rank swap and
     * would put P(r, r) somewhere other than a coin flip. The fit is through the
     * origin, which is the whole reason this family keeps the two structural
     * properties the kernel surface has to earn a different way.
     */
    static double[] fitLogistic(double[][] design, double[] wins, double[] count){
        int k = design[0].length;
        double[] beta = new double[k];
        for(int iteration = 0; iteration < 100; iteration++){
            double[] gradient = new double[k];
            double[][] hessian = new double[k][k];
            for(int i = 0; i < design.length; i++){
                double z = 0;
                for(int j = 0; j < k; j++){
                    z += beta[j] * design[i][j];
                }
                double p = OddsFamilies.sigmoid(z);
                double residual = wins[i] - count[i] * p;
                double weight = count[i] * p * (1 - p);
                for(int j = 0; j < k; j++){
                    gradient[j] += design[i][j] * residual;
                    for(int j2 = 0; j2 < k; j2++){
                        hessian[j][j2] += design[i][j] * design[i][j2] * weight;
                    }
                }
            }
            for(int j = 0; j < k; j++){
                hessian[j][j] += 1e-9;
            }
            double[] step = solve(hessian, gradient);
            double size = 0;
            for(int j = 0; j < k; j++){
                beta[j] += step[j];
                size = Math.max(size, Math.abs(step[j]));
            }
            if(size < 1e-12){
                break;
            }
        }
        return beta;
    }

    /** Gaussian elimination with partial pivoting, for the 1x1 to 3x3 above. */
    static double[] solve(double[][] matrix, double[] rhs){
        int n = rhs.length;
        double[][] a = new double[n][n + 1];
        for(int i = 0; i < n; i++){
            System.arraycopy(matrix[i], 0, a[i], 0, n);
            a[i][n] = rhs[i];
        }
        for(int c = 0; c < n; c++){
            int pivot = c;
            for(int r = c + 1; r < n; r++){
                if(Math.abs(a[r][c]) > Math.abs(a[pivot][c])){
                    pivot = r;
                }
            }
            double[] swap = a[c];
            a[c] = a[pivot];
            a[pivot] = swap;
            if(Math.abs(a[c][c]) < 1e-300){
                continue;
            }
            for(int r = 0; r < n; r++){
                if(r == c){
                    continue;
                }
                double factor = a[r][c] / a[c][c];
                for(int j = c; j <= n; j++){
                    a[r][j] -= factor * a[c][j];
                }
            }
        }
        double[] out = new double[n];
        for(int i = 0; i < n; i++){
            out[i] = Math.abs(a[i][i]) < 1e-300 ? 0 : a[i][n] / a[i][i];
        }
        return out;
    }

    static PairwiseOdds.Model parametricModel(Map<Position, OddsFamilies.Cells> cells,
                                              int[] terms){
        Map<Position, double[]> betas = new EnumMap<>(Position.class);
        for(Position position : PairwiseOdds.POSITIONS){
            OddsFamilies.Cells mine = cells.get(position);
            double[][] design = new double[mine.size()][];
            for(int i = 0; i < mine.size(); i++){
                design[i] = basis(mine.early()[i], mine.late()[i], terms);
            }
            betas.put(position, fitLogistic(design, mine.wins(), mine.count()));
        }
        return (position, early, late) -> {
            double[] beta = betas.get(position);
            if(beta == null || early < 1 || late < 1){
                return 0.5;
            }
            double[] row = basis(early, late, terms);
            double z = 0;
            for(int j = 0; j < beta.length; j++){
                z += beta[j] * row[j];
            }
            return OddsFamilies.sigmoid(z);
        };
    }

    // ---------------------------------------------------------- kernel surfaces

    /** exp(-(log a - log b)^2 / 2h^2) for every pair of ranks, 1-indexed. */
    static double[][] kernelWeights(int cap, double halfWidth){
        double[][] kernel = new double[cap + 1][cap + 1];
        for(int a = 1; a <= cap; a++){
            for(int b = 1; b <= cap; b++){
                double gap = Math.log(a) - Math.log(b);
                kernel[a][b] = Math.exp(-gap * gap / (2 * halfWidth * halfWidth));
            }
        }
        return kernel;
    }

    /**
     * out[E][L] = sum over e and l of kernel[e][E] * kernel[l][L] * field[e][l].
     *
     * Done as two passes over one index at a time, which is the same arithmetic
     * as the double sum and turns a cap^4 job into a cap^3 one.
     */
    static double[][] smoothField(double[][] field, double[][] kernel, int cap){
        double[][] half = new double[cap + 1][cap + 1];
        for(int e2 = 1; e2 <= cap; e2++){
            for(int l = 1; l <= cap; l++){
                double sum = 0;
                for(int e = 1; e <= cap; e++){
                    sum += kernel[e][e2] * field[e][l];
                }
                half[e2][l] = sum;
            }
        }
        double[][] out = new double[cap + 1][cap + 1];
        for(int e2 = 1; e2 <= cap; e2++){
            for(int l2 = 1; l2 <= cap; l2++){
                double sum = 0;
                for(int l = 1; l <= cap; l++){
                    sum += kernel[l][l2] * half[e2][l];
                }
                out[e2][l2] = sum;
            }
        }
        return out;
    }

    /**
     * Wins and counts on the full grid, every cell also entered at its mirror
     * with the win count flipped.
     *
     * The mirroring is what buys the two structural properties. With a kernel
     * that is symmetric in its two arguments, relabelling the summation turns
     * the smoothed win total at (L, E) into (smoothed count - smoothed wins) at
     * (E, L), so P(a, b) + P(b, a) = 1 exactly and P(r, r) = 0.5 exactly.
     *
     * @return {wins, count}
     */
    static double[][][] mirrored(OddsFamilies.Cells cells, int cap){
        double[][] wins = new double[cap + 1][cap + 1];
        double[][] count = new double[cap + 1][cap + 1];
        for(int i = 0; i < cells.size(); i++){
            int e = cells.early()[i];
            int l = cells.late()[i];
            wins[e][l] += cells.wins()[i];
            count[e][l] += cells.count()[i];
            wins[l][e] += cells.count()[i] - cells.wins()[i];
            count[l][e] += cells.count()[i];
        }
        return new double[][][]{wins, count};
    }

    /** The Nadaraya-Watson win rate over the mirrored grid, Laplace-padded. */
    static double[][] kernelSurfaceGrid(OddsFamilies.Cells cells, int cap, double halfWidth){
        double[][][] field = mirrored(cells, cap);
        double[][] kernel = kernelWeights(cap, halfWidth);
        double[][] wins = smoothField(field[0], kernel, cap);
        double[][] count = smoothField(field[1], kernel, cap);
        double[][] out = new double[cap + 1][cap + 1];
        for(int e = 1; e <= cap; e++){
            for(int l = 1; l <= cap; l++){
                out[e][l] = (wins[e][l] + KERNEL_PRIOR) / (count[e][l] + 2 * KERNEL_PRIOR);
            }
        }
        return out;
    }

    /**
     * A kernel-smoothed one-step Newton correction to a base model's log odds.
     *
     * Each cell contributes its score residual and its Fisher information at the
     * base model's prediction; both fields are mirrored - the residual with its
     * sign flipped, the information as it stands - then smoothed with the same
     * separable log-rank kernel, and the correction is their ratio. Because a
     * mirrored-antisymmetric field stays antisymmetric under a symmetric kernel
     * and a mirrored-symmetric one stays symmetric, the correction is zero on
     * the diagonal and flips sign under the swap, so adding it to the base log
     * odds preserves P(r, r) = 0.5 and antisymmetry.
     *
     * A correction of zero returns the base model exactly, which is what makes
     * this family nest the incumbent.
     *
     * It is ONE step and is not iterated. The step is
     * (p_true - p_base) / (p_base * (1 - p_base)), which is the exact log-odds
     * move only in the limit of a small one; where p_base is extreme it
     * overshoots, badly. On the real board the incumbent's misses are a few
     * points of probability near the middle of the range, where one step is
     * close to exact - but the overshoot is real and
     * OddsSurfacesTest.theKernelCorrectionOvershootsWhereTheBaseProbabilityIsExtreme
     * holds it in place so it stays a known property rather than a surprise.
     */
    static double[][] correctionGrid(PairwiseOdds.Model base, Position position,
                                     OddsFamilies.Cells cells, int cap, double halfWidth){
        double[][] gradient = new double[cap + 1][cap + 1];
        double[][] information = new double[cap + 1][cap + 1];
        for(int i = 0; i < cells.size(); i++){
            int e = cells.early()[i];
            int l = cells.late()[i];
            double p = base.probability(position, e, l);
            double residual = cells.wins()[i] - cells.count()[i] * p;
            double weight = cells.count()[i] * p * (1 - p);
            gradient[e][l] += residual;
            gradient[l][e] -= residual;
            information[e][l] += weight;
            information[l][e] += weight;
        }
        double[][] kernel = kernelWeights(cap, halfWidth);
        double[][] smoothedGradient = smoothField(gradient, kernel, cap);
        double[][] smoothedInformation = smoothField(information, kernel, cap);
        double[][] out = new double[cap + 1][cap + 1];
        for(int e = 1; e <= cap; e++){
            for(int l = 1; l <= cap; l++){
                out[e][l] = smoothedGradient[e][l]
                        / (smoothedInformation[e][l] + CORRECTION_RIDGE);
            }
        }
        return out;
    }

    /** Read a per-position grid of probabilities, 0.5 off the end of it. */
    static PairwiseOdds.Model gridModel(Map<Position, double[][]> grids){
        return (position, early, late) -> {
            double[][] grid = grids.get(position);
            if(grid == null || early < 1 || late < 1 || early >= grid.length
                    || late >= grid.length){
                return 0.5;
            }
            return grid[early][late];
        };
    }

    static PairwiseOdds.Model kernelModel(Map<Position, OddsFamilies.Cells> cells,
                                          double halfWidth){
        Map<Position, double[][]> grids = new EnumMap<>(Position.class);
        for(Position position : PairwiseOdds.POSITIONS){
            grids.put(position, kernelSurfaceGrid(cells.get(position),
                    PairwiseOdds.CAP.get(position), halfWidth));
        }
        return gridModel(grids);
    }

    static PairwiseOdds.Model correctedModel(List<PairwiseOdds.Pair> training,
                                             Map<Position, OddsFamilies.Cells> cells,
                                             double halfWidth){
        PairwiseOdds.Model base = OddsFamilies.incumbent(training);
        Map<Position, double[][]> corrections = new EnumMap<>(Position.class);
        for(Position position : PairwiseOdds.POSITIONS){
            corrections.put(position, correctionGrid(base, position, cells.get(position),
                    PairwiseOdds.CAP.get(position), halfWidth));
        }
        return (position, early, late) -> {
            double p = base.probability(position, early, late);
            double[][] correction = corrections.get(position);
            if(correction == null || early < 1 || late < 1
                    || early >= correction.length || late >= correction.length){
                return p;
            }
            double clipped = Math.min(1 - 1e-12, Math.max(1e-12, p));
            return OddsFamilies.sigmoid(Math.log(clipped / (1 - clipped))
                    + correction[early][late]);
        };
    }

    // ------------------------------------------------- pricing ten comparisons

    /** mean / (sd / sqrt(n)) over the fold differences. */
    static double tStatistic(double[] diff){
        int n = diff.length;
        double mean = 0;
        for(double value : diff){
            mean += value / n;
        }
        double sum = 0;
        for(double value : diff){
            sum += (value - mean) * (value - mean);
        }
        double se = Math.sqrt(sum / (n - 1) / n);
        return mean / Math.max(se, 1e-15);
    }

    /**
     * Family-wise p-values by exact sign-flip enumeration.
     *
     * PowerBacktest.paired prices ONE comparison. Ten challengers were run, and
     * at a 95% bar apiece the chance that at least one clears by luck alone is
     * not 5% - so the best row in table 2 cannot be read off its own bar.
     *
     * Under the null that a family is no different from the incumbent, each
     * season's paired difference was as likely to have come out with the
     * opposite sign, so flipping signs generates the null exactly. THE SAME FLIP
     * IS APPLIED TO EVERY FAMILY AT ONCE, which keeps whatever correlation they
     * have with each other, and the statistic taken from each flip is the
     * LARGEST |t| over all the challengers. A family's own |t| read against that
     * distribution is what prices having tried ten rather than one.
     *
     * All 2^clusters assignments are enumerated - 65,536 at sixteen seasons - so
     * there is no seed and no sampling error in the p-value.
     *
     * @param diffs one row a challenger, one column a season
     * @return {p on its own, p family-wise}, one column a challenger
     */
    static double[][] signFlipPValues(double[][] diffs){
        int challengers = diffs.length;
        int clusters = diffs[0].length;
        double[] observed = new double[challengers];
        for(int m = 0; m < challengers; m++){
            observed[m] = Math.abs(tStatistic(diffs[m]));
        }
        long[] alone = new long[challengers];
        long[] familyWise = new long[challengers];
        long total = 1L << clusters;
        double[] flipped = new double[clusters];
        double[] statistic = new double[challengers];
        for(long mask = 0; mask < total; mask++){
            double worst = 0;
            for(int m = 0; m < challengers; m++){
                for(int s = 0; s < clusters; s++){
                    flipped[s] = ((mask >> s) & 1) == 0 ? diffs[m][s] : -diffs[m][s];
                }
                statistic[m] = Math.abs(tStatistic(flipped));
                worst = Math.max(worst, statistic[m]);
            }
            for(int m = 0; m < challengers; m++){
                if(statistic[m] >= observed[m] - 1e-12){
                    alone[m]++;
                }
                if(worst >= observed[m] - 1e-12){
                    familyWise[m]++;
                }
            }
        }
        double[][] out = new double[2][challengers];
        for(int m = 0; m < challengers; m++){
            out[0][m] = (double) alone[m] / total;
            out[1][m] = (double) familyWise[m] / total;
        }
        return out;
    }

    // ------------------------------------------------------- the family table

    static List<String> names(){
        return new ArrayList<>(List.of(
                "INCUMBENT iso+log h=0.25",
                "BT-MLE monotone, no smooth",
                "BT-MLE monotone + log h=0.25",
                "LOGIT d",
                "LOGIT d + d*m",
                "LOGIT d + d^3",
                "LOGIT d + d*m + d^3",
                "KERNEL SURFACE h=0.25",
                "INCUMBENT + KERNEL h=0.25",
                "BOOSTED STRENGTH",
                "BOOSTED SURFACE (no structure)"));
    }

    /** Six characters a family, for the fold-by-fold table's column heads. */
    static List<String> codes(){
        return new ArrayList<>(List.of("INCUMB", "BT-RAW", "BT-SM", "LOG-D", "LOG-DM",
                "LOG-D3", "LOG-3P", "KERNEL", "INC+K", "BOOST-S", "BOOST-F"));
    }

    static List<OddsFamilies.Family> families(){
        return new ArrayList<>(List.of(
                (training, cells) -> OddsFamilies.incumbent(training),
                (training, cells) -> btModel(training, cells, 0),
                (training, cells) -> btModel(training, cells, OddsFamilies.INCUMBENT_H),
                (training, cells) -> parametricModel(cells, PARAMETRIC[0]),
                (training, cells) -> parametricModel(cells, PARAMETRIC[1]),
                (training, cells) -> parametricModel(cells, PARAMETRIC[2]),
                (training, cells) -> parametricModel(cells, PARAMETRIC[3]),
                (training, cells) -> kernelModel(cells, OddsFamilies.INCUMBENT_H),
                (training, cells) -> correctedModel(training, cells, OddsFamilies.INCUMBENT_H),
                (training, cells) -> OddsFamilies.strengthModel(training, cells, true, false,
                        OddsFamilies.TREES, OddsFamilies.DEPTH, OddsFamilies.LEARNING_RATE),
                (training, cells) -> OddsFamilies.surfaceModel(cells, OddsFamilies.TREES,
                        OddsFamilies.DEPTH, OddsFamilies.LEARNING_RATE)));
    }

    public static void main(String[] args){
        String format = System.getProperty("format");
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(format);
        List<String> seasons = new ArrayList<>(new TreeMap<>(wider).keySet());
        int clusters = seasons.size();
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, seasons);
        int[] ties = new int[1];
        List<PairwiseOdds.Pair> everything = PairwiseOdds.pairs(men, -1, false, ties);

        List<String> names = names();
        List<String> codes = codes();
        List<OddsFamilies.Family> families = families();

        System.out.printf("%nWHICH MODEL FAMILY FOR THE PAIRWISE ODDS SURFACE?"
                + " ROUND TWO%n%n");
        System.out.printf("P(the man at positional rank P outscores the man at rank Q), Q < P,"
                + " same%nposition, same season. seasons %d (%s-%s)   drafted men %d   pairs"
                + " %d%n", clusters, seasons.get(0), seasons.get(clusters - 1), men.size(),
                everything.size());
        System.out.printf("caps: QB %d RB %d WR %d TE %d%n", PairwiseOdds.CAP.get(Position.QB),
                PairwiseOdds.CAP.get(Position.RB), PairwiseOdds.CAP.get(Position.WR),
                PairwiseOdds.CAP.get(Position.TE));

        // ------------------------------------------------- 1. fold by fold
        double[][] scores = new double[names.size()][clusters];
        List<List<OddsFamilies.Scored>> byFold = new ArrayList<>();
        for(int s = 0; s < clusters; s++){
            int[] scratch = new int[1];
            List<PairwiseOdds.Pair> training = PairwiseOdds.pairs(men, s, false, scratch);
            List<PairwiseOdds.Pair> held = PairwiseOdds.pairs(men, s, true, scratch);
            Map<Position, OddsFamilies.Cells> cells = OddsFamilies.cellsByPosition(training);
            List<PairwiseOdds.Model> fitted = new ArrayList<>();
            for(OddsFamilies.Family family : families){
                fitted.add(family.fit(training, cells));
            }
            for(int m = 0; m < fitted.size(); m++){
                scores[m][s] = PairwiseOdds.logLoss(fitted.get(m), held);
            }
            List<OddsFamilies.Scored> fold = new ArrayList<>();
            for(PairwiseOdds.Pair pair : held){
                double[] predicted = new double[fitted.size()];
                for(int m = 0; m < fitted.size(); m++){
                    predicted[m] = fitted.get(m).probability(pair.position(), pair.early(),
                            pair.late());
                }
                fold.add(new OddsFamilies.Scored(pair, predicted));
            }
            byFold.add(fold);
        }

        System.out.printf("%n%n1. HELD-OUT LOG LOSS, FOLD BY FOLD%n%n");
        System.out.printf("One row a season. The model is fitted on the other fifteen and"
                + " scored on that%none, so every column is sixteen independent-ish numbers"
                + " and the spread DOWN a%ncolumn is what the error bar in table 2 is made"
                + " of. Lower is better.%n%n");
        System.out.printf("%-8s", "SEASON");
        for(String code : codes){
            System.out.printf(" %7s", code);
        }
        System.out.println();
        for(int s = 0; s < clusters; s++){
            System.out.printf("%-8s", seasons.get(s));
            for(int m = 0; m < names.size(); m++){
                System.out.printf(" %7.5f", scores[m][s]);
            }
            System.out.println();
        }
        System.out.printf("%-8s", "MEAN");
        for(int m = 0; m < names.size(); m++){
            System.out.printf(" %7.5f", PairwiseOdds.mean(scores[m]));
        }
        System.out.println();
        System.out.printf("%nColumn key:%n");
        for(int m = 0; m < names.size(); m++){
            System.out.printf("   %-8s %s%n", codes.get(m), names.get(m));
        }

        // ------------------------------------------------- 2. the paired test
        System.out.printf("%n%n2. LEAVE-ONE-SEASON-OUT, ALL %d PAIRS%n%n", everything.size());
        System.out.printf("Every family tried is in this table; none was dropped for scoring"
                + " badly.%nThe bar is 95%%, two-sided, clustered on season. A gap smaller"
                + " than the bar is%na TIE however good the point estimate looks.%n%n");
        OddsFamilies.table(names, scores, 0, clusters);

        int[] clusterOf = new int[clusters];
        for(int s = 0; s < clusters; s++){
            clusterOf[s] = s;
        }
        System.out.printf("%nAnd the size of gap this design could detect at all, family by"
                + " family:%nthe smallest true difference that would clear the 95%% bar 80%%"
                + " of the time%nwith sixteen seasons.%n%n");
        System.out.printf("%-32s %12s %14s%n", "FAMILY", "SE(seas)", "detectable");
        for(int m = 0; m < names.size(); m++){
            double[] diff = new double[clusters];
            for(int s = 0; s < clusters; s++){
                diff[s] = scores[m][s] - scores[0][s];
            }
            PowerBacktest.Paired paired = PowerBacktest.paired(names.get(m), scores[m],
                    diff, clusterOf, clusters);
            System.out.printf("%-32s %12.5f %14.5f%n", names.get(m), paired.seSeason(),
                    PowerBacktest.minimumDetectable(paired.seSeason(), clusters));
        }

        // ------------------------------- 2b. what ten comparisons cost
        System.out.printf("%n%n2b. AND WHAT DOES HAVING TRIED TEN FAMILIES COST?%n%n");
        System.out.printf("Each bar in table 2 prices ONE comparison. Ten challengers were"
                + " run, so the%nbest row cannot be read off its own bar: at 95%% apiece the"
                + " chance one of ten%nclears by luck is far more than 5%%. Below, the same"
                + " sixteen fold differences,%nwith every one of the %d ways their signs"
                + " could have fallen enumerated -%nno seed, no sampling error. p ALONE is"
                + " the family read as if it were the only%none tried; p FAMILY-WISE reads"
                + " its |t| against the largest |t| any of the ten%nreached under the same"
                + " flip, which is the honest one.%n%n", 1L << clusters);
        double[][] diffs = new double[names.size() - 1][clusters];
        for(int m = 1; m < names.size(); m++){
            for(int s = 0; s < clusters; s++){
                diffs[m - 1][s] = scores[m][s] - scores[0][s];
            }
        }
        double[][] pValues = signFlipPValues(diffs);
        System.out.printf("%-32s %10s %10s %12s %14s%n", "FAMILY", "vs INCUMB", "t",
                "p alone", "p family-wise");
        for(int m = 1; m < names.size(); m++){
            System.out.printf("%-32s %+10.5f %10.2f %12.4f %14.4f%n", names.get(m),
                    PairwiseOdds.mean(scores[m]) - PairwiseOdds.mean(scores[0]),
                    tStatistic(diffs[m - 1]), pValues[0][m - 1], pValues[1][m - 1]);
        }
        System.out.printf("%nA negative t is a family that scored BETTER than the incumbent."
                + " This prices%nthe ten families in THIS table only. PairwiseOdds already"
                + " spent thirteen%nsmoothers and OddsFamilies six more families on the same"
                + " sixteen seasons, so%nthe real correction is larger than this one and this"
                + " is a floor on it.%n");

        // ----------------------------------- 3. the claim inside the shipped code
        System.out.printf("%n%n3. IS THE MARGINAL CURVE THE SAME AS THE LIKELIHOOD ONE?%n%n");
        System.out.printf("PairwiseOdds.strength estimates s(rank) MARGINALLY and its comment"
                + " says that%nat this sample size iterating a Bradley-Terry likelihood lands"
                + " in the same%nplace. Fitted on all %d seasons, here is how far apart the"
                + " two land - on the%nSTRENGTH curve, and on the PROBABILITIES that come out"
                + " of it, which is the%nonly place a difference could reach Justin.%n%n",
                clusters);
        Map<Position, OddsFamilies.Cells> allCells = OddsFamilies.cellsByPosition(everything);
        PairwiseOdds.Model incumbentAll = OddsFamilies.incumbent(everything);
        PairwiseOdds.Model btAll = btModel(everything, allCells, OddsFamilies.INCUMBENT_H);
        System.out.printf("%-6s %14s %16s %16s%n", "POS", "worst |ds|",
                "worst |dP| (all)", "worst |dP| (top 12)");
        for(Position position : PairwiseOdds.POSITIONS){
            int cap = PairwiseOdds.CAP.get(position);
            double[] marginal = OddsFamilies.incumbentCurve(everything, position, cap);
            double[] likelihood = btStrength(marginal, allCells.get(position), cap,
                    OddsFamilies.INCUMBENT_H, BT_ROUNDS);
            // Only differences of a strength curve are identified, so the two are
            // compared after centring each on its own mean.
            double marginalCentre = 0;
            double likelihoodCentre = 0;
            for(int r = 1; r <= cap; r++){
                marginalCentre += marginal[r] / cap;
                likelihoodCentre += likelihood[r] / cap;
            }
            double worstStrength = 0;
            for(int r = 1; r <= cap; r++){
                worstStrength = Math.max(worstStrength,
                        Math.abs((marginal[r] - marginalCentre)
                                - (likelihood[r] - likelihoodCentre)));
            }
            double worstAll = 0;
            double worstTop = 0;
            for(int e = 1; e <= cap; e++){
                for(int l = e + 1; l <= cap; l++){
                    double gap = Math.abs(incumbentAll.probability(position, e, l)
                            - btAll.probability(position, e, l));
                    worstAll = Math.max(worstAll, gap);
                    if(e <= 12 && l <= 12){
                        worstTop = Math.max(worstTop, gap);
                    }
                }
            }
            System.out.printf("%-6s %14.4f %15.2f%% %15.2f%%%n", position, worstStrength,
                    100 * worstAll, 100 * worstTop);
        }
        System.out.printf("%nworst |ds| is on the centred strength curve, in log odds."
                + " worst |dP| is the%nlargest disagreement in probability over every rank"
                + " pair in the grid, and%nover the pairs both inside the first twelve, where"
                + " Justin's first two picks%nlive. Table 2 is where it decides anything.%n");

        // ------------------------------------------ 4. the structural properties
        System.out.printf("%n%n4. WHAT DOES EACH FAMILY GIVE UP AT THE TABLE?%n%n");
        System.out.printf("Fitted on all %d seasons, then read over every rank pair in the"
                + " grid. The%nincumbent's design argument is that these hold by"
                + " construction, so a%nchallenger has to say what it drops.%n%n", clusters);
        List<PairwiseOdds.Model> full = new ArrayList<>();
        for(OddsFamilies.Family family : families){
            full.add(family.fit(everything, allCells));
        }
        System.out.printf("%-32s %10s %10s %8s %10s %11s %10s%n", "FAMILY", "|P(r,r)-.5|",
                "antisym", "order", "monotone", "worst jump", "strong-ST");
        for(int m = 0; m < names.size(); m++){
            OddsFamilies.Properties property = OddsFamilies.properties(full.get(m));
            System.out.printf("%-32s %10.2e %10.2e %8d %10d %10.1f%% %9.2f%%%n",
                    names.get(m), property.selfPlay(), property.antisymmetry(),
                    property.orderBreaks(), property.monotoneBreaks(),
                    100 * property.worstMonotone(),
                    100.0 * property.strongBreaks() / property.triples());
        }
        System.out.printf("%n|P(r,r)-.5| a man against himself must be a coin flip."
                + " antisym  P(a,b)+P(b,a)%nmust be 1. order  how many pairs where the"
                + " better rank is the underdog.%nmonotone  how many pairs where a DEEPER"
                + " man is given a better chance than a%nshallower one against the same"
                + " opponent, and the worst such jump. strong-ST%nis the share of rank"
                + " triples breaking P(a beats c) >= max(P(a beats b),%nP(b beats c)).%n");

        // ------------------------------------------------- 5. the bandwidth check
        System.out.printf("%n%n5. IS THE KERNEL VERDICT AN ARTIFACT OF h = 0.25?%n%n");
        System.out.printf("h = 0.25 was fixed in advance because it is the incumbent's own"
                + " smoothing%nwidth. This varies it. NOTHING IS SELECTED FROM THIS TABLE -"
                + " it exists so the%nverdict above cannot be blamed on one arbitrary"
                + " bandwidth. Picking the best%nrow here would be the argmax-of-a-noisy-field"
                + " mistake the bake-off refused.%n%n");
        List<String> sweepNames = new ArrayList<>();
        List<OddsFamilies.Family> sweep = new ArrayList<>();
        sweepNames.add("INCUMBENT iso+log h=0.25");
        sweep.add((training, cells) -> OddsFamilies.incumbent(training));
        for(double halfWidth : BANDWIDTHS){
            sweepNames.add(String.format("KERNEL SURFACE h=%.2f", halfWidth));
            sweep.add((training, cells) -> kernelModel(cells, halfWidth));
            sweepNames.add(String.format("INCUMBENT + KERNEL h=%.2f", halfWidth));
            sweep.add((training, cells) -> correctedModel(training, cells, halfWidth));
        }
        double[][] sweepScores = new double[sweepNames.size()][clusters];
        for(int s = 0; s < clusters; s++){
            int[] scratch = new int[1];
            List<PairwiseOdds.Pair> training = PairwiseOdds.pairs(men, s, false, scratch);
            List<PairwiseOdds.Pair> held = PairwiseOdds.pairs(men, s, true, scratch);
            Map<Position, OddsFamilies.Cells> cells = OddsFamilies.cellsByPosition(training);
            for(int m = 0; m < sweep.size(); m++){
                sweepScores[m][s] = PairwiseOdds.logLoss(sweep.get(m).fit(training, cells),
                        held);
            }
        }
        OddsFamilies.table(sweepNames, sweepScores, 0, clusters);

        // ------------------------------------------------- 6. where it matters
        System.out.printf("%n%n6. THE TWO REGIONS THAT DECIDE PICKS%n%n");
        System.out.printf("A log loss averaged over %d mostly-deep pairs can hide a miss"
                + " exactly where%nJustin drafts. The same paired test, restricted.%n",
                everything.size());
        OddsFamilies.regionTable("TOP OF THE BOARD - early rank 1-12, RB and WR",
                "his first two picks, where the cliff is", names, byFold,
                pair -> (pair.position() == Position.RB || pair.position() == Position.WR)
                        && pair.early() <= 12, 0);
        OddsFamilies.regionTable("DEEP AGAINST DEEP - both ranks past 36",
                "the miss OddsFamilies diagnosed at +7.9 points", names, byFold,
                pair -> pair.early() > 36 && pair.late() > 36, 0);
    }
}
