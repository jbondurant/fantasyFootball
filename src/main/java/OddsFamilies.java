import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Can a different MODEL FAMILY beat the incumbent on the pairwise odds surface?
 *
 * The incumbent is PairwiseOdds' ISOTONIC + LOG-SMOOTH h=0.25: a latent strength
 * curve s(rank) with logit p = alpha * (s(P) - s(Q)). It won a thirteen-variant
 * bake-off, and its design argument is that it fits a CURVE, not a surface -
 * P(r, r) = 0.5, antisymmetry and monotone order come free, and a two-dimensional
 * problem collapses to one dimension, which is where the sample size goes.
 *
 * THE DIAGNOSED MISS, which is the reason to try anything at all. Held out, the
 * incumbent under-predicts the DEEP-AGAINST-DEEP corner by about +8 points:
 * RB49-60 against RB49-60 comes in at +7.9%. Late men are more VARIABLE, not
 * merely worse, and one strength curve cannot say that. The note left on that
 * finding was that a second dimension, if ever added, should be a SPREAD that
 * grows with rank rather than more flexibility in the mean.
 *
 * THE FAMILIES, FIXED BEFORE ANY SCORE WAS READ. Four challengers, no more, and
 * every one of them is reported below whatever it did. In the thirteen-variant
 * bake-off NOT ONE ROW CLEARED ITS OWN BAR; trying families until one looks good
 * finds the luckiest, not the best, and that is the mistake that produced a
 * sequence search scoring +61 on train and -191 on test in this repo.
 *
 *   BOOSTED STRENGTH   gradient-boosted trees estimate s(rank) instead of
 *                      isotonic-plus-smoother. Structure kept whole; flexibility
 *                      added only where the curve is estimated. With rank as the
 *                      single ordered input a tree is an adaptive-width step
 *                      function, so this is precisely "let the fit choose where
 *                      to spend resolution" against the incumbent's fixed
 *                      log-width window. Projected back onto a non-increasing
 *                      curve so the three properties still hold.
 *   STRENGTH + SPREAD  the direct fix for the diagnosed miss. Each rank gets a
 *                      spread sigma(r) = r^tau as well as a strength, and
 *                      z = alpha * (s(P) - s(Q)) / sqrt((sigma(P)^2 + sigma(Q)^2)/2).
 *                      ONE extra parameter a position, and tau = 0 reproduces the
 *                      incumbent exactly, so the family is nested around it. It is
 *                      reported at BOTH fitting orders - curve frozen at the
 *                      incumbent's, and curve refitted at every tau - because the
 *                      frozen one collapses to tau = 0 and only the refitted one
 *                      is a fair test. Both rows stay in the table.
 *   BOOSTED BOTH       the two above at once: boosted strength, fitted spread.
 *   BOOSTED SURFACE    the control that ABANDONS the structure - trees on the
 *                      pair itself, features (early, late, log gap, rank gap),
 *                      plain binary logistic loss. It is here to price what the
 *                      structure is worth, and section 3 measures the three
 *                      properties it gives up.
 *
 * NO HYPERPARAMETER WAS TUNED. 300 trees, depth 2, learning rate 0.1 are copied
 * from BoostedSelectionModel, which chose them on a different problem. Sweeping
 * them on these held-out seasons and reporting the winner would be the same
 * argmax-of-a-noisy-field mistake the bake-off refused. Section 6 varies them
 * anyway, with every row and every bar shown, purely so the verdict cannot be an
 * artifact of one arbitrary setting - nothing is selected from it.
 *
 * THE PROTOCOL IS THE INCUMBENT'S, UNCHANGED. Hold out SEASONS, sixteen folds,
 * never pairs: pairs inside a season are scored on the same realised football,
 * and holding out pairs would shrink the error bar by roughly sqrt(4115). Score
 * with held-out LOG LOSS, because accuracy is useless here - every variant in the
 * bake-off landed at 67-68%. Bar it with PowerBacktest.paired, clustered on
 * season, the same statistic that prices the 125-point draft bar.
 *
 *   ./gradlew run -Pmain=OddsFamilies -q
 */
public class OddsFamilies {

    /** Copied from BoostedSelectionModel, not fitted here. */
    static final int TREES = 300;
    static final int DEPTH = 2;
    static final double LEARNING_RATE = 0.1;

    /** L2 on leaf values, XGBoost-style, as in BoostedSelectionModel. */
    static final double LAMBDA = 1.0;

    /** A leaf must stand on this many real pairs. */
    static final double MIN_LEAF_PAIRS = 200;

    /**
     * Histogram resolution for the split search.
     *
     * This must exceed the deepest cap, and the reason is not efficiency. At 32
     * bins over 72 ranks the quantile edges land two ranks apart, so RANK 1 AND
     * RANK 2 SHARE A BIN and no tree can ever separate them - the boosted curve
     * was structurally unable to fit the steepest part of the board, which is
     * the part Justin drafts in, and the symptom looked exactly like honest
     * shrinkage. OddsFamiliesTest caught it by planting a known strength curve
     * and asking the fit to return it: 0.070 off at rank 1, 0.005 everywhere
     * else. At 128 every rank is its own bin (thresholdsOf keeps all distinct
     * values when there are fewer than BINS of them), so the boosting is
     * handicapped by nothing but its own fitting.
     */
    static final int BINS = 128;

    /** The incumbent's log-rank smoothing half-width. */
    static final double INCUMBENT_H = 0.25;

    // ------------------------------------------------------------- the sample
    //
    // Every family sees the same pairs the incumbent sees. Pairs are collapsed to
    // CELLS - one row per (position, early rank, late rank) carrying a count and
    // a win count - which is exact, not an approximation: a model that depends on
    // nothing but those three numbers cannot tell the two representations apart,
    // and the likelihood, its gradient and its hessian all sum the same way. It
    // turns 65,855 rows into about 5,300 and is why the sweep in section 6 is
    // affordable at all.

    /** One (early, late) cell of the surface, with its real pair count. */
    record Cells(int[] early, int[] late, double[] count, double[] wins){
        int size(){
            return early.length;
        }
    }

    static Cells cellsOf(List<PairwiseOdds.Pair> pairs, Position position, int cap){
        double[][] count = new double[cap + 1][cap + 1];
        double[][] wins = new double[cap + 1][cap + 1];
        for(PairwiseOdds.Pair pair : pairs){
            if(pair.position() != position || pair.early() > cap || pair.late() > cap){
                continue;
            }
            count[pair.early()][pair.late()]++;
            if(pair.lateWon()){
                wins[pair.early()][pair.late()]++;
            }
        }
        List<int[]> keys = new ArrayList<>();
        for(int e = 1; e <= cap; e++){
            for(int l = e + 1; l <= cap; l++){
                if(count[e][l] > 0){
                    keys.add(new int[]{e, l});
                }
            }
        }
        int[] early = new int[keys.size()];
        int[] late = new int[keys.size()];
        double[] n = new double[keys.size()];
        double[] w = new double[keys.size()];
        for(int i = 0; i < keys.size(); i++){
            early[i] = keys.get(i)[0];
            late[i] = keys.get(i)[1];
            n[i] = count[early[i]][late[i]];
            w[i] = wins[early[i]][late[i]];
        }
        return new Cells(early, late, n, w);
    }

    // ------------------------------------------------------- weighted fitting

    static double sigmoid(double z){
        return 1 / (1 + Math.exp(-z));
    }

    /**
     * logit p = slope * gap, on weighted cells, by Newton.
     *
     * The same one-parameter calibration PairwiseOdds.fitSlope performs on raw
     * pairs; identical arithmetic, summed over cells instead of rows.
     */
    static double fitSlopeWeighted(double[] gap, double[] wins, double[] count){
        double slope = 1.0;
        for(int iteration = 0; iteration < 60; iteration++){
            double gradient = 0;
            double hessian = 0;
            for(int i = 0; i < gap.length; i++){
                double p = sigmoid(slope * gap[i]);
                gradient += gap[i] * (wins[i] - count[i] * p);
                hessian += gap[i] * gap[i] * count[i] * p * (1 - p);
            }
            if(hessian <= 1e-12){
                break;
            }
            double step = gradient / hessian;
            slope += step;
            if(Math.abs(step) < 1e-11){
                break;
            }
        }
        return slope;
    }

    static double logLikelihood(double[] gap, double[] wins, double[] count, double slope){
        double total = 0;
        for(int i = 0; i < gap.length; i++){
            double p = Math.min(1 - 1e-12, Math.max(1e-12, sigmoid(slope * gap[i])));
            total += wins[i] * Math.log(p) + (count[i] - wins[i]) * Math.log(1 - p);
        }
        return total;
    }

    // ------------------------------------------------------------- the trees
    //
    // The same shape as BoostedSelectionModel: depth-limited regression trees,
    // Newton leaves (gradient over hessian, L2-regularised), added with
    // shrinkage, no randomness anywhere. One deliberate difference: split
    // thresholds are the feature's global quantiles, computed once before
    // boosting, and the search accumulates a histogram per node rather than
    // re-sorting the node's members. That is LightGBM's arrangement rather than
    // XGBoost's exact-greedy one; it is still deterministic and it is what makes
    // the sixteen folds times nine configurations of section 6 run in a minute
    // instead of an hour.

    static final class Node {
        int feature = -1;
        double threshold;
        Node left;
        Node right;
        double value;

        double score(double[] row){
            Node node = this;
            while(node.feature >= 0){
                node = row[node.feature] <= node.threshold ? node.left : node.right;
            }
            return node.value;
        }
    }

    /** Global quantile split points, one ascending array a feature. */
    static double[][] thresholdsOf(double[][] rows, int bins){
        int features = rows.length == 0 ? 0 : rows[0].length;
        double[][] out = new double[features][];
        for(int f = 0; f < features; f++){
            double[] values = new double[rows.length];
            for(int r = 0; r < rows.length; r++){
                values[r] = rows[r][f];
            }
            Arrays.sort(values);
            List<Double> distinct = new ArrayList<>();
            for(double value : values){
                if(distinct.isEmpty() || distinct.get(distinct.size() - 1) != value){
                    distinct.add(value);
                }
            }
            List<Double> edges = new ArrayList<>();
            if(distinct.size() <= bins){
                edges.addAll(distinct);
            }
            else {
                for(int b = 0; b < bins; b++){
                    double edge = distinct.get((int) ((long) (b + 1) * (distinct.size() - 1) / bins));
                    if(edges.isEmpty() || edges.get(edges.size() - 1) != edge){
                        edges.add(edge);
                    }
                }
                double top = distinct.get(distinct.size() - 1);
                if(edges.get(edges.size() - 1) != top){
                    edges.add(top);
                }
            }
            double[] array = new double[edges.size()];
            for(int i = 0; i < array.length; i++){
                array[i] = edges.get(i);
            }
            out[f] = array;
        }
        return out;
    }

    static int[][] binsOf(double[][] rows, double[][] thresholds){
        int[][] out = new int[rows.length][thresholds.length];
        for(int r = 0; r < rows.length; r++){
            for(int f = 0; f < thresholds.length; f++){
                int b = 0;
                while(b < thresholds[f].length - 1 && rows[r][f] > thresholds[f][b]){
                    b++;
                }
                out[r][f] = b;
            }
        }
        return out;
    }

    static Node grow(int[][] bins, double[][] thresholds, double[] gradient,
                     double[] hessian, double[] weight, int[] members, int depth,
                     double minLeaf){
        Node node = new Node();
        double gradientSum = 0;
        double hessianSum = 0;
        double weightSum = 0;
        for(int member : members){
            gradientSum += gradient[member];
            hessianSum += hessian[member];
            weightSum += weight[member];
        }
        node.value = gradientSum / (hessianSum + LAMBDA);
        if(depth == 0 || weightSum < 2 * minLeaf){
            return node;
        }
        double parentScore = gradientSum * gradientSum / (hessianSum + LAMBDA);
        double bestGain = 1e-9;
        int bestFeature = -1;
        int bestBin = -1;
        for(int f = 0; f < thresholds.length; f++){
            int count = thresholds[f].length;
            double[] binGradient = new double[count];
            double[] binHessian = new double[count];
            double[] binWeight = new double[count];
            for(int member : members){
                int b = bins[member][f];
                binGradient[b] += gradient[member];
                binHessian[b] += hessian[member];
                binWeight[b] += weight[member];
            }
            double leftGradient = 0;
            double leftHessian = 0;
            double leftWeight = 0;
            for(int b = 0; b < count - 1; b++){
                leftGradient += binGradient[b];
                leftHessian += binHessian[b];
                leftWeight += binWeight[b];
                if(leftWeight < minLeaf || weightSum - leftWeight < minLeaf){
                    continue;
                }
                double rightGradient = gradientSum - leftGradient;
                double rightHessian = hessianSum - leftHessian;
                double gain = leftGradient * leftGradient / (leftHessian + LAMBDA)
                        + rightGradient * rightGradient / (rightHessian + LAMBDA)
                        - parentScore;
                if(gain > bestGain){
                    bestGain = gain;
                    bestFeature = f;
                    bestBin = b;
                }
            }
        }
        if(bestFeature < 0){
            return node;
        }
        int leftCount = 0;
        for(int member : members){
            if(bins[member][bestFeature] <= bestBin){
                leftCount++;
            }
        }
        int[] left = new int[leftCount];
        int[] right = new int[members.length - leftCount];
        int l = 0;
        int r = 0;
        for(int member : members){
            if(bins[member][bestFeature] <= bestBin){
                left[l++] = member;
            }
            else {
                right[r++] = member;
            }
        }
        node.feature = bestFeature;
        node.threshold = thresholds[bestFeature][bestBin];
        node.left = grow(bins, thresholds, gradient, hessian, weight, left, depth - 1, minLeaf);
        node.right = grow(bins, thresholds, gradient, hessian, weight, right, depth - 1, minLeaf);
        return node;
    }

    // ------------------------------------------------- family 1: the incumbent

    static PairwiseOdds.Model incumbent(List<PairwiseOdds.Pair> training){
        return PairwiseOdds.latent(training, 0, INCUMBENT_H);
    }

    /** The incumbent's own curve, so the spread family can be nested around it. */
    static double[] incumbentCurve(List<PairwiseOdds.Pair> training, Position position, int cap){
        double[] raw = PairwiseOdds.strength(training, position, cap);
        double[] body = new double[cap];
        System.arraycopy(raw, 1, body, 0, cap);
        double[] smoothed = PairwiseOdds.smoothLog(body, INCUMBENT_H);
        double[] curve = new double[cap + 1];
        System.arraycopy(smoothed, 0, curve, 1, cap);
        return curve;
    }

    // -------------------------------------------- family 2: boosted strength
    //
    // Gradient boosting on the Bradley-Terry likelihood of the pairs, with rank
    // as the only input. Every cell contributes to BOTH of its ranks - +g to the
    // late man, -g to the early one - which is what makes this a fit of the
    // curve rather than of the surface. The hessian is the usual diagonal Newton
    // approximation that LambdaMART uses.

    static double[] boostedCurve(Cells cells, int cap, int trees, int depth, double rate){
        double[][] rows = new double[cap][1];
        for(int r = 1; r <= cap; r++){
            rows[r - 1][0] = r;
        }
        double[][] thresholds = thresholdsOf(rows, BINS);
        int[][] bins = binsOf(rows, thresholds);
        double[] pairsAt = new double[cap];
        for(int i = 0; i < cells.size(); i++){
            pairsAt[cells.early()[i] - 1] += cells.count()[i];
            pairsAt[cells.late()[i] - 1] += cells.count()[i];
        }
        int[] all = new int[cap];
        for(int r = 0; r < cap; r++){
            all[r] = r;
        }
        double[] strength = new double[cap];
        double[] gradient = new double[cap];
        double[] hessian = new double[cap];
        for(int round = 0; round < trees; round++){
            Arrays.fill(gradient, 0);
            Arrays.fill(hessian, 0);
            for(int i = 0; i < cells.size(); i++){
                int e = cells.early()[i] - 1;
                int l = cells.late()[i] - 1;
                double p = sigmoid(strength[l] - strength[e]);
                double g = cells.wins()[i] - cells.count()[i] * p;
                double h = Math.max(cells.count()[i] * p * (1 - p), 1e-6);
                gradient[l] += g;
                gradient[e] -= g;
                hessian[l] += h;
                hessian[e] += h;
            }
            Node tree = grow(bins, thresholds, gradient, hessian, pairsAt, all, depth,
                    MIN_LEAF_PAIRS);
            for(int r = 0; r < cap; r++){
                strength[r] += rate * tree.score(rows[r]);
            }
        }
        // The projection is what keeps the three properties. Boosting is free to
        // hand back a curve that turns back up in a thin patch of the tail; PAVA
        // pools that patch into a tie, weighted by how many real pairs stand
        // behind each rank, and cannot move a rank the data actually separates.
        double[] projected = PairwiseOdds.isotonicDecreasing(strength, pairsAt);
        double[] curve = new double[cap + 1];
        System.arraycopy(projected, 0, curve, 1, cap);
        return curve;
    }

    // ------------------------------------------- family 3: strength + spread
    //
    // sigma(r) = r^tau, and
    //     z = alpha * (s(P) - s(Q)) / sqrt((sigma(P)^2 + sigma(Q)^2) / 2).
    //
    // tau = 0 gives sigma == 1 and the incumbent back exactly, so nothing is
    // risked by the extra parameter in-sample; the question is entirely whether
    // it survives a held-out season. tau is fitted on the TRAINING fold by a
    // grid with an inner Newton for alpha - coarse then fine, deterministic, no
    // held-out data touched.

    static final double TAU_MAX = 1.20;

    static double[] spreadGaps(double[] curve, Cells cells, double tau){
        double[] gap = new double[cells.size()];
        for(int i = 0; i < cells.size(); i++){
            int e = cells.early()[i];
            int l = cells.late()[i];
            gap[i] = (curve[l] - curve[e]) / scale(e, l, tau);
        }
        return gap;
    }

    static double scale(int early, int late, double tau){
        if(tau == 0){
            return 1;
        }
        double a = Math.pow(early, tau);
        double b = Math.pow(late, tau);
        return Math.sqrt((a * a + b * b) / 2);
    }

    /** {tau, alpha} maximising the training likelihood. */
    static double[] fitSpread(double[] curve, Cells cells){
        double bestTau = 0;
        double bestAlpha = 1;
        double best = Double.NEGATIVE_INFINITY;
        for(int step = 0; step < 41; step++){
            double tau = TAU_MAX * step / 40.0;
            double[] gap = spreadGaps(curve, cells, tau);
            double alpha = fitSlopeWeighted(gap, cells.wins(), cells.count());
            double value = logLikelihood(gap, cells.wins(), cells.count(), alpha);
            if(value > best){
                best = value;
                bestTau = tau;
                bestAlpha = alpha;
            }
        }
        double coarse = TAU_MAX / 40.0;
        for(int step = -9; step <= 9; step++){
            double tau = bestTau + step * coarse / 10.0;
            if(tau < 0 || tau > TAU_MAX){
                continue;
            }
            double[] gap = spreadGaps(curve, cells, tau);
            double alpha = fitSlopeWeighted(gap, cells.wins(), cells.count());
            double value = logLikelihood(gap, cells.wins(), cells.count(), alpha);
            if(value > best){
                best = value;
                bestTau = tau;
                bestAlpha = alpha;
            }
        }
        return new double[]{bestTau, bestAlpha};
    }

    // ------------------------------------ family 4: strength and spread JOINTLY
    //
    // The frozen version above cannot move: the curve it divides was itself
    // estimated with tau = 0, so it has already absorbed whatever a spread would
    // have explained, and the maximum lands back at tau = 0 at all four
    // positions. That is a fact about the fitting order, not about football, and
    // it would be a bad answer to the question the miss actually poses.
    //
    // So here mu(r) is RE-ESTIMATED at every tau, by local scoring: a diagonal
    // Newton step on the pairwise likelihood under the spread link, then the
    // incumbent's own two disciplines - pool-adjacent-violators onto a
    // non-increasing curve, then the h=0.25 log-rank smoother. Iterated to a
    // fixed point, which is a penalised maximum likelihood. tau then gets a fair
    // grid search on the TRAINING fold with a fully refitted curve at each step.

    static final int JOINT_ROUNDS = 20;
    static final double RIDGE = 1e-6;

    static double[] jointStrength(double[] start, Cells cells, int cap, double tau){
        return jointStrength(start, cells, cap, tau, JOINT_ROUNDS);
    }

    static double[] jointStrength(double[] start, Cells cells, int cap, double tau,
                                  int rounds){
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
                double s = scale(e + 1, l + 1, tau);
                double p = sigmoid((mu[l] - mu[e]) / s);
                double g = (cells.wins()[i] - cells.count()[i] * p) / s;
                double h = cells.count()[i] * p * (1 - p) / (s * s);
                gradient[l] += g;
                gradient[e] -= g;
                hessian[l] += h;
                hessian[e] += h;
            }
            for(int r = 0; r < cap; r++){
                mu[r] += gradient[r] / (hessian[r] + RIDGE);
            }
            mu = PairwiseOdds.smoothLog(
                    PairwiseOdds.isotonicDecreasing(mu, pairsAt), INCUMBENT_H);
            // Only DIFFERENCES of mu are identified, so its level is free to
            // wander and, left alone, it does: twenty rounds and two hundred
            // drifted 1.64 apart while every probability between them was
            // identical. Centring pins it, so "has the fit settled" becomes a
            // question the curve can actually answer.
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

    /** A fitted spread model for one position. */
    record Joint(double[] curve, double tau, double alpha, double logLikelihood){}

    static Joint fitJoint(double[] start, Cells cells, int cap){
        Joint best = null;
        for(int step = 0; step < 41; step++){
            best = better(best, start, cells, cap, TAU_MAX * step / 40.0);
        }
        double coarse = TAU_MAX / 40.0;
        double centre = best.tau();
        for(int step = -9; step <= 9; step++){
            double tau = centre + step * coarse / 10.0;
            if(tau >= 0 && tau <= TAU_MAX){
                best = better(best, start, cells, cap, tau);
            }
        }
        return best;
    }

    static Joint better(Joint best, double[] start, Cells cells, int cap, double tau){
        double[] curve = jointStrength(start, cells, cap, tau);
        double[] gap = spreadGaps(curve, cells, tau);
        double alpha = fitSlopeWeighted(gap, cells.wins(), cells.count());
        double value = logLikelihood(gap, cells.wins(), cells.count(), alpha);
        if(best == null || value > best.logLikelihood()){
            return new Joint(curve, tau, alpha, value);
        }
        return best;
    }

    /** A latent-strength model, optionally with a rank-dependent spread. */
    static PairwiseOdds.Model latentModel(Map<Position, double[]> curves, Map<Position, Double> alphas,
                             Map<Position, Double> taus){
        return (position, early, late) -> {
            double[] curve = curves.get(position);
            if(curve == null || early >= curve.length || late >= curve.length){
                return 0.5;
            }
            double tau = taus == null ? 0 : taus.get(position);
            double z = alphas.get(position) * (curve[late] - curve[early])
                    / scale(early, late, tau);
            return sigmoid(z);
        };
    }

    // ------------------------------------------- family 5: boosted surface
    //
    // The control. Trees on the pair itself with a plain binary logistic loss and
    // no latent curve at all. It is allowed every interaction the structure
    // forbids, and section 3 prices what it gives up for them.

    static final class Surface {
        final List<Node> trees = new ArrayList<>();
        double rate;
    }

    static double[] surfaceRow(int early, int late){
        return new double[]{early, late, Math.log(late) - Math.log(early), late - early};
    }

    static Surface fitSurface(Cells cells, int trees, int depth, double rate){
        double[][] rows = new double[cells.size()][];
        for(int i = 0; i < cells.size(); i++){
            rows[i] = surfaceRow(cells.early()[i], cells.late()[i]);
        }
        double[][] thresholds = thresholdsOf(rows, BINS);
        int[][] bins = binsOf(rows, thresholds);
        int[] all = new int[cells.size()];
        for(int i = 0; i < all.length; i++){
            all[i] = i;
        }
        double[] score = new double[cells.size()];
        double[] gradient = new double[cells.size()];
        double[] hessian = new double[cells.size()];
        Surface surface = new Surface();
        surface.rate = rate;
        for(int round = 0; round < trees; round++){
            for(int i = 0; i < cells.size(); i++){
                double p = sigmoid(score[i]);
                gradient[i] = cells.wins()[i] - cells.count()[i] * p;
                hessian[i] = Math.max(cells.count()[i] * p * (1 - p), 1e-6);
            }
            Node tree = grow(bins, thresholds, gradient, hessian, cells.count(), all, depth,
                    MIN_LEAF_PAIRS);
            surface.trees.add(tree);
            for(int i = 0; i < cells.size(); i++){
                score[i] += rate * tree.score(rows[i]);
            }
        }
        return surface;
    }

    // ------------------------------------------------------ assembling models

    static Map<Position, Cells> cellsByPosition(List<PairwiseOdds.Pair> training){
        Map<Position, Cells> out = new EnumMap<>(Position.class);
        for(Position position : PairwiseOdds.POSITIONS){
            out.put(position, cellsOf(training, position, PairwiseOdds.CAP.get(position)));
        }
        return out;
    }

    /**
     * @param boosted  estimate the strength curve by boosting instead of
     *                 isotonic-plus-log-smooth
     * @param spread   fit a rank-dependent spread as well as a strength
     */
    static PairwiseOdds.Model strengthModel(List<PairwiseOdds.Pair> training, Map<Position, Cells> cells,
                               boolean boosted, boolean spread, int trees, int depth,
                               double rate){
        Map<Position, double[]> curves = new EnumMap<>(Position.class);
        Map<Position, Double> alphas = new EnumMap<>(Position.class);
        Map<Position, Double> taus = new EnumMap<>(Position.class);
        for(Position position : PairwiseOdds.POSITIONS){
            int cap = PairwiseOdds.CAP.get(position);
            Cells mine = cells.get(position);
            double[] curve = boosted ? boostedCurve(mine, cap, trees, depth, rate)
                    : incumbentCurve(training, position, cap);
            curves.put(position, curve);
            if(spread){
                double[] fit = fitSpread(curve, mine);
                taus.put(position, fit[0]);
                alphas.put(position, fit[1]);
            }
            else {
                double[] gap = spreadGaps(curve, mine, 0);
                alphas.put(position, fitSlopeWeighted(gap, mine.wins(), mine.count()));
                taus.put(position, 0.0);
            }
        }
        return latentModel(curves, alphas, spread ? taus : null);
    }

    /** Strength and spread refitted together, one tau a position. */
    static PairwiseOdds.Model jointModel(List<PairwiseOdds.Pair> training,
                                         Map<Position, Cells> cells){
        Map<Position, double[]> curves = new EnumMap<>(Position.class);
        Map<Position, Double> alphas = new EnumMap<>(Position.class);
        Map<Position, Double> taus = new EnumMap<>(Position.class);
        for(Position position : PairwiseOdds.POSITIONS){
            int cap = PairwiseOdds.CAP.get(position);
            Joint fit = fitJoint(incumbentCurve(training, position, cap),
                    cells.get(position), cap);
            curves.put(position, fit.curve());
            alphas.put(position, fit.alpha());
            taus.put(position, fit.tau());
        }
        return latentModel(curves, alphas, taus);
    }

    static PairwiseOdds.Model surfaceModel(Map<Position, Cells> cells, int trees, int depth, double rate){
        Map<Position, Surface> fitted = new EnumMap<>(Position.class);
        for(Position position : PairwiseOdds.POSITIONS){
            fitted.put(position, fitSurface(cells.get(position), trees, depth, rate));
        }
        return (position, early, late) -> {
            Surface surface = fitted.get(position);
            if(surface == null){
                return 0.5;
            }
            double[] row = surfaceRow(early, late);
            double score = 0;
            for(Node tree : surface.trees){
                score += surface.rate * tree.score(row);
            }
            return sigmoid(score);
        };
    }

    /** A named family, fitted from one training fold. */
    interface Family {
        PairwiseOdds.Model fit(List<PairwiseOdds.Pair> training, Map<Position, Cells> cells);
    }

    static List<String> names(){
        return new ArrayList<>(List.of(
                "INCUMBENT iso+log h=0.25",
                "BOOSTED STRENGTH",
                "SPREAD, curve frozen",
                "SPREAD, curve refitted",
                "BOOSTED BOTH",
                "BOOSTED SURFACE (no structure)",
                "LOG-LINEAR (old challenger)"));
    }

    static List<Family> families(){
        return new ArrayList<>(List.of(
                (training, cells) -> incumbent(training),
                (training, cells) -> strengthModel(training, cells, true, false,
                        TREES, DEPTH, LEARNING_RATE),
                (training, cells) -> strengthModel(training, cells, false, true,
                        TREES, DEPTH, LEARNING_RATE),
                OddsFamilies::jointModel,
                (training, cells) -> strengthModel(training, cells, true, true,
                        TREES, DEPTH, LEARNING_RATE),
                (training, cells) -> surfaceModel(cells, TREES, DEPTH, LEARNING_RATE),
                (training, cells) -> PairwiseOdds.logLinear(training)));
    }

    // -------------------------------------------------- structural properties
    //
    // The incumbent's design argument is not its log loss, it is that a latent
    // strength cannot contradict itself at the draft table. A challenger that
    // buys a decimal place by giving up P(r, r) = 0.5 has not won anything, so
    // every family is audited on the same five properties over its whole fitted
    // grid.

    record Properties(double selfPlay, double antisymmetry, int orderBreaks,
                      int monotoneBreaks, double worstMonotone, int strongBreaks,
                      int triples){}

    static Properties properties(PairwiseOdds.Model model){
        double selfPlay = 0;
        double antisymmetry = 0;
        int orderBreaks = 0;
        int monotoneBreaks = 0;
        double worstMonotone = 0;
        int strongBreaks = 0;
        int triples = 0;
        for(Position position : PairwiseOdds.POSITIONS){
            int cap = PairwiseOdds.CAP.get(position);
            for(int r = 1; r <= cap; r++){
                selfPlay = Math.max(selfPlay,
                        Math.abs(model.probability(position, r, r) - 0.5));
            }
            for(int a = 1; a <= cap; a++){
                for(int b = a + 1; b <= cap; b++){
                    double forward = model.probability(position, a, b);
                    double back = model.probability(position, b, a);
                    antisymmetry = Math.max(antisymmetry, Math.abs(forward + back - 1));
                    if(forward > 0.5 + 1e-12){
                        orderBreaks++;                 // the better rank is the underdog
                    }
                    if(b > a + 1){
                        double previous = model.probability(position, a, b - 1);
                        if(forward > previous + 1e-12){
                            monotoneBreaks++;
                            worstMonotone = Math.max(worstMonotone, forward - previous);
                        }
                    }
                }
            }
            for(int a = 1; a <= cap; a++){
                for(int b = a + 1; b <= cap; b++){
                    for(int c = b + 1; c <= cap; c++){
                        triples++;
                        double ac = model.probability(position, a, c);
                        double ab = model.probability(position, a, b);
                        double bc = model.probability(position, b, c);
                        if(ac > Math.min(ab, bc) + 1e-9){
                            strongBreaks++;
                        }
                    }
                }
            }
        }
        return new Properties(selfPlay, antisymmetry, orderBreaks, monotoneBreaks,
                worstMonotone, strongBreaks, triples);
    }

    // -------------------------------------------------------------- reporting

    /** One held-out pair with what each family said about it. */
    record Scored(PairwiseOdds.Pair pair, double[] predicted){}

    static double loss(double p, boolean won){
        double clipped = Math.min(1 - 1e-6, Math.max(1e-6, p));
        return won ? -Math.log(clipped) : -Math.log(1 - clipped);
    }

    /**
     * The paired table. `scores` is one held-out log loss a season a family;
     * every row is printed against the INCUMBENT, with its own clustered bar.
     */
    static void table(List<String> names, double[][] scores, int baseline, int clusters){
        int[] clusterOf = new int[clusters];
        for(int s = 0; s < clusters; s++){
            clusterOf[s] = s;
        }
        System.out.printf("%-32s %10s %12s %10s %9s %6s %s%n", "FAMILY", "log loss",
                "vs INCUMB", "SE(seas)", "95% bar", "beats", "verdict");
        for(int m = 0; m < names.size(); m++){
            double[] diff = new double[clusters];
            int beats = 0;
            for(int s = 0; s < clusters; s++){
                diff[s] = scores[m][s] - scores[baseline][s];
                if(diff[s] < 0){
                    beats++;
                }
            }
            PowerBacktest.Paired paired = PowerBacktest.paired(names.get(m), scores[m],
                    diff, clusterOf, clusters);
            String verdict;
            if(m == baseline){
                verdict = "<- baseline";
            }
            else if(!paired.real()){
                verdict = "TIE - inside its own bar";
            }
            else {
                verdict = paired.diff() < 0 ? "REAL IMPROVEMENT" : "REAL, and worse";
            }
            System.out.printf("%-32s %10.5f %+12.5f %10.5f %9.5f %5d/%-2d %s%n",
                    names.get(m), paired.mean(), paired.diff(), paired.seSeason(),
                    paired.bar(), beats, clusters, verdict);
        }
    }

    /** Held-out log loss a season, restricted to the pairs a filter accepts. */
    interface Region {
        boolean accepts(PairwiseOdds.Pair pair);
    }

    static void regionTable(String title, String why, List<String> names,
                            List<List<Scored>> byFold, Region region, int baseline){
        int clusters = byFold.size();
        double[][] scores = new double[names.size()][clusters];
        int total = 0;
        for(int s = 0; s < clusters; s++){
            double[] sum = new double[names.size()];
            int seen = 0;
            for(Scored scored : byFold.get(s)){
                if(!region.accepts(scored.pair())){
                    continue;
                }
                seen++;
                for(int m = 0; m < names.size(); m++){
                    sum[m] += loss(scored.predicted()[m], scored.pair().lateWon());
                }
            }
            total += seen;
            for(int m = 0; m < names.size(); m++){
                scores[m][s] = seen == 0 ? 0 : sum[m] / seen;
            }
        }
        System.out.printf("%n%s   (%d held-out pairs)%n%s%n%n", title, total, why);
        table(names, scores, baseline, clusters);
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
        List<Family> families = families();

        System.out.printf("%nCAN A DIFFERENT MODEL FAMILY BEAT THE INCUMBENT ODDS SURFACE?%n%n");
        System.out.printf("P(the man at positional rank P outscores the man at rank Q), Q < P,"
                + " same%nposition, same season. seasons %d (%s-%s)   drafted men %d   pairs"
                + " %d%n", clusters, seasons.get(0), seasons.get(clusters - 1), men.size(),
                everything.size());
        System.out.printf("caps: QB %d RB %d WR %d TE %d   boosting: %d trees, depth %d,"
                + " rate %.2f (copied, not tuned)%n", PairwiseOdds.CAP.get(Position.QB),
                PairwiseOdds.CAP.get(Position.RB), PairwiseOdds.CAP.get(Position.WR),
                PairwiseOdds.CAP.get(Position.TE), TREES, DEPTH, LEARNING_RATE);

        // ---------------------------------------------------- 1. the bake-off
        double[][] scores = new double[names.size()][clusters];
        List<List<Scored>> byFold = new ArrayList<>();
        for(int s = 0; s < clusters; s++){
            int[] scratch = new int[1];
            List<PairwiseOdds.Pair> training = PairwiseOdds.pairs(men, s, false, scratch);
            List<PairwiseOdds.Pair> held = PairwiseOdds.pairs(men, s, true, scratch);
            Map<Position, Cells> cells = cellsByPosition(training);
            List<PairwiseOdds.Model> fitted = new ArrayList<>();
            for(Family family : families){
                fitted.add(family.fit(training, cells));
            }
            for(int m = 0; m < fitted.size(); m++){
                scores[m][s] = PairwiseOdds.logLoss(fitted.get(m), held);
            }
            List<Scored> fold = new ArrayList<>();
            for(PairwiseOdds.Pair pair : held){
                double[] predicted = new double[fitted.size()];
                for(int m = 0; m < fitted.size(); m++){
                    predicted[m] = fitted.get(m).probability(pair.position(), pair.early(),
                            pair.late());
                }
                fold.add(new Scored(pair, predicted));
            }
            byFold.add(fold);
        }

        System.out.printf("%n%n1. LEAVE-ONE-SEASON-OUT, ALL %d PAIRS%n%n", everything.size());
        System.out.printf("Fitted on fifteen seasons, scored on the sixteenth, sixteen times."
                + " The held-out%nunit is the SEASON. Every family that was tried is in this"
                + " table; none was%ndropped for scoring badly, because reporting only the"
                + " best of several families%nfinds the luckiest and not the best.%n%n");
        table(names, scores, 0, clusters);
        System.out.printf("%nThe bar is 95%%, two-sided, clustered on season, from the same%n"
                + "PowerBacktest.paired that prices the 125-point draft bar. A gap smaller%n"
                + "than the bar is a TIE however good the point estimate looks.%n");

        // -------------------------------------------- 2. what the spread found
        System.out.printf("%n%n2. WHAT THE SPREAD PARAMETER ACTUALLY FOUND%n%n");
        System.out.printf("sigma(r) = r^tau, fitted on all %d seasons. tau = 0 is the"
                + " incumbent - one%nstrength curve, constant spread. tau > 0 says late men"
                + " are more VARIABLE and%nnot merely worse, which is the diagnosed miss.%n%n",
                clusters);
        Map<Position, Cells> allCells = cellsByPosition(everything);
        System.out.printf("%-6s %10s %10s %10s %12s %14s%n", "POS", "tau frozen",
                "tau refit", "alpha", "sigma(deep)", "log-lik gain");
        for(Position position : PairwiseOdds.POSITIONS){
            int cap = PairwiseOdds.CAP.get(position);
            double[] curve = incumbentCurve(everything, position, cap);
            Cells mine = allCells.get(position);
            double[] frozen = fitSpread(curve, mine);
            Joint joint = fitJoint(curve, mine, cap);
            // The honest null: the SAME estimator with the spread switched off,
            // not the same curve read under a link it was not fitted for.
            Joint zero = better(null, curve, mine, cap, 0);
            System.out.printf("%-6s %10.3f %10.3f %10.3f %12.2f %14.1f%n", position,
                    frozen[0], joint.tau(), joint.alpha(), Math.pow(cap, joint.tau()),
                    joint.logLikelihood() - zero.logLikelihood());
        }
        System.out.printf("%ntau frozen is the maximum when the curve is left at the"
                + " incumbent's; it is 0%neverywhere, which is a fact about the fitting order"
                + " - that curve has already%nabsorbed whatever a spread would explain. tau"
                + " refit re-estimates the curve at%nevery tau, which is the fair test."
                + " sigma(deep) is how many times wider the%ndeepest man's spread comes out"
                + " than the first man's. log-lik gain is IN-SAMPLE%nand buys nothing on its"
                + " own - one free parameter always gains in-sample.%nTable 1 is where it has"
                + " to survive.%n");
        System.out.printf("%nThe likelihood profile in tau, RB, in-sample, so it is visible"
                + " whether tau is%nidentified at all or the surface is flat in it:%n%n");
        int rbCap = PairwiseOdds.CAP.get(Position.RB);
        double[] rbStart = incumbentCurve(everything, Position.RB, rbCap);
        Cells rbCells = allCells.get(Position.RB);
        double flatBase = Double.NaN;
        System.out.printf("%-8s %16s%n", "tau", "log-lik - tau0");
        for(double tau : new double[]{0, 0.1, 0.2, 0.3, 0.4, 0.6, 0.8, 1.0, 1.2}){
            double[] curve = jointStrength(rbStart, rbCells, rbCap, tau);
            double[] gap = spreadGaps(curve, rbCells, tau);
            double alpha = fitSlopeWeighted(gap, rbCells.wins(), rbCells.count());
            double value = logLikelihood(gap, rbCells.wins(), rbCells.count(), alpha);
            if(Double.isNaN(flatBase)){
                flatBase = value;
            }
            System.out.printf("%-8.2f %+16.1f%n", tau, value - flatBase);
        }

        // A parameter that lands somewhere different every time a season is
        // dropped is not measured, it is noise wearing a decimal point. This is
        // the same tau the held-out table above actually used, fold by fold.
        System.out.printf("%nAnd the tau each of the %d TRAINING folds chose for itself,"
                + " which is what the%nheld-out table was scored with:%n%n", clusters);
        for(Position position : PairwiseOdds.POSITIONS){
            System.out.printf("%-6s", position);
            for(int s = 0; s < clusters; s++){
                int[] scratch = new int[1];
                List<PairwiseOdds.Pair> training = PairwiseOdds.pairs(men, s, false, scratch);
                int cap = PairwiseOdds.CAP.get(position);
                Joint fit = fitJoint(incumbentCurve(training, position, cap),
                        cellsOf(training, position, cap), cap);
                System.out.printf(" %5.2f", fit.tau());
            }
            System.out.println();
        }

        // ---------------------------------------- 3. the structural properties
        System.out.printf("%n%n3. DOES THE FIT STILL MEAN ANYTHING AT THE TABLE?%n%n");
        System.out.printf("Fitted on all %d seasons, then read over every rank pair in the"
                + " grid. The%nincumbent's whole design argument is that these hold by"
                + " construction; a%nchallenger has to say what it gives up.%n%n", clusters);
        List<PairwiseOdds.Model> full = new ArrayList<>();
        for(Family family : families){
            full.add(family.fit(everything, allCells));
        }
        System.out.printf("%-32s %10s %10s %8s %10s %11s %10s%n", "FAMILY", "|P(r,r)-.5|",
                "antisym", "order", "monotone", "worst jump", "strong-ST");
        for(int m = 0; m < names.size(); m++){
            Properties property = properties(full.get(m));
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
                + " triples breaking strong stochastic transitivity:%nP(a beats c) >="
                + " max(P(a beats b), P(b beats c)).%n");

        // --------------------------------------------- 4. calibration by region
        System.out.printf("%n%n4. CALIBRATION BY REGION - RB, HELD OUT, POOLED OVER %d"
                + " FOLDS%n%n", clusters);
        System.out.printf("Observed minus predicted, percentage points. A 2.7-point miss at"
                + " the top of%nthe board is invisible in a log loss averaged over 55,000"
                + " mostly-deep pairs,%nand where a model is wrong matters more than how"
                + " wrong it is on average.%n");
        region(names, byFold, Position.RB, 5, 12);
        System.out.printf("%n%nAnd the same for WR, where Justin spends four of his first"
                + " six picks.%n");
        region(names, byFold, Position.WR, 6, 12);

        // ------------------------------------------ 5. the region that counts
        System.out.printf("%n%n5. THE REGION JUSTIN DRAFTS IN (pick 7 is RB/WR 1-12)%n%n");
        System.out.printf("The same paired test as table 1, restricted. A family that is"
                + " right where he%npicks and wrong in the tail is worth more to him than"
                + " the average says.%n");
        regionTable("TOP OF THE BOARD - early rank 1-12, RB and WR",
                "the first pick, where the cliff is", names, byFold,
                pair -> (pair.position() == Position.RB || pair.position() == Position.WR)
                        && pair.early() <= 12, 0);
        regionTable("DEEP AGAINST DEEP - both ranks past 36",
                "the diagnosed miss, and the reason the spread family exists", names, byFold,
                pair -> pair.early() > 36 && pair.late() > 36, 0);
        regionTable("EVERYTHING ELSE", "so the two regions above can be read as a split",
                names, byFold,
                pair -> !((pair.position() == Position.RB || pair.position() == Position.WR)
                        && pair.early() <= 12) && !(pair.early() > 36 && pair.late() > 36), 0);

        // -------------------------------------- 6. the hyperparameter check
        System.out.printf("%n%n6. IS THE VERDICT AN ARTIFACT OF THE COPIED SETTINGS?%n%n");
        System.out.printf("300 trees, depth 2, rate 0.1 were copied from"
                + " BoostedSelectionModel, which%nchose them on a different problem. This"
                + " varies them. NOTHING IS SELECTED FROM%nTHIS TABLE - it exists only so"
                + " the verdict above cannot be blamed on one%narbitrary setting. Picking"
                + " the best row here would be the argmax-of-a-noisy%nfield mistake the"
                + " bake-off refused.%n%n");
        int[][] settings = {{100, 2}, {300, 1}, {300, 2}, {300, 3}, {600, 2}, {600, 4}};
        List<String> sweepNames = new ArrayList<>();
        sweepNames.add("INCUMBENT iso+log h=0.25");
        List<Family> sweep = new ArrayList<>();
        sweep.add((training, cells) -> incumbent(training));
        for(int[] setting : settings){
            int trees = setting[0];
            int depth = setting[1];
            sweepNames.add(String.format("BOOSTED STRENGTH t=%d d=%d", trees, depth));
            sweep.add((training, cells) -> strengthModel(training, cells, true, false,
                    trees, depth, LEARNING_RATE));
            sweepNames.add(String.format("BOOSTED SURFACE  t=%d d=%d", trees, depth));
            sweep.add((training, cells) -> surfaceModel(cells, trees, depth, LEARNING_RATE));
        }
        double[][] sweepScores = new double[sweepNames.size()][clusters];
        for(int s = 0; s < clusters; s++){
            int[] scratch = new int[1];
            List<PairwiseOdds.Pair> training = PairwiseOdds.pairs(men, s, false, scratch);
            List<PairwiseOdds.Pair> held = PairwiseOdds.pairs(men, s, true, scratch);
            Map<Position, Cells> cells = cellsByPosition(training);
            for(int m = 0; m < sweep.size(); m++){
                sweepScores[m][s] = PairwiseOdds.logLoss(sweep.get(m).fit(training, cells), held);
            }
        }
        table(sweepNames, sweepScores, 0, clusters);
        System.out.printf("%nEvery row is against the same incumbent, with its own clustered"
                + " bar.%n");
    }

    /** Held-out observed-minus-predicted by rank band, one block a family. */
    static void region(List<String> names, List<List<Scored>> byFold, Position position,
                       int bands, int width){
        double[][] observed = new double[bands][bands];
        double[][] count = new double[bands][bands];
        double[][][] predicted = new double[names.size()][bands][bands];
        for(List<Scored> fold : byFold){
            for(Scored scored : fold){
                PairwiseOdds.Pair pair = scored.pair();
                if(pair.position() != position){
                    continue;
                }
                int a = Math.min(bands - 1, (pair.early() - 1) / width);
                int b = Math.min(bands - 1, (pair.late() - 1) / width);
                observed[a][b] += pair.lateWon() ? 1 : 0;
                count[a][b]++;
                for(int m = 0; m < names.size(); m++){
                    predicted[m][a][b] += scored.predicted()[m];
                }
            }
        }
        System.out.printf("%n%-12s", "n");
        for(int b = 0; b < bands; b++){
            System.out.printf(" %10s", position + "" + (b * width + 1) + "-" + (b * width + width));
        }
        System.out.println();
        for(int a = 0; a < bands; a++){
            System.out.printf("%-12s", position + "" + (a * width + 1) + "-" + (a * width + width));
            for(int b = 0; b < bands; b++){
                System.out.printf(" %10.0f", count[a][b]);
            }
            System.out.println();
        }
        for(int m = 0; m < names.size(); m++){
            System.out.printf("%n%s%n%-12s", names.get(m), "EARLY \\ LATE");
            for(int b = 0; b < bands; b++){
                System.out.printf(" %10s", position + "" + (b * width + 1) + "-" + (b * width + width));
            }
            System.out.println();
            for(int a = 0; a < bands; a++){
                System.out.printf("%-12s", position + "" + (a * width + 1) + "-" + (a * width + width));
                for(int b = 0; b < bands; b++){
                    if(count[a][b] < 30){
                        System.out.printf(" %10s", "-");
                    }
                    else {
                        System.out.printf(" %+9.1f%%", 100 * (observed[a][b]
                                - predicted[m][a][b]) / count[a][b]);
                    }
                }
                System.out.println();
            }
        }
    }
}
