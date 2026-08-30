import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The gradient-boosted challenger: the same listwise-softmax objective the
 * conditional logit maximizes, but with depth-limited regression trees as the
 * score function instead of a linear utility - which is what LightGBM or
 * CatBoost would bring to this problem, implemented in plain Java so the repo
 * stays dependency-free and deterministic. Trees see ALL features, including
 * every one the linear lab rejected: automatic interaction discovery is the
 * entire argument for this model class.
 *
 * Fit: per boosting round, softmax over each historical choice set gives
 * pick probabilities; the pseudo-residual for every alternative is
 * (chosen ? 1 : 0) - p; one tree is fit with XGBoost-style Newton leaves
 * (gradient over hessian, L2-regularized) and added with shrinkage. Exact
 * greedy splits on quantile thresholds, no randomness anywhere.
 */
public class BoostedSelectionModel implements ChoiceModel {

    /** One node; leaves have feature -1 and carry the value. */
    private static final class Node {
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

    private static final int MIN_LEAF = 40;
    private static final int THRESHOLDS = 16;

    private final List<Node> trees;
    private final double learningRate;

    private BoostedSelectionModel(List<Node> trees, double learningRate){
        this.trees = trees;
        this.learningRate = learningRate;
    }

    @Override
    public double[] choiceProbabilities(double[][] features){
        double[] utilities = new double[features.length];
        double max = Double.NEGATIVE_INFINITY;
        for(int a = 0; a < features.length; a++){
            double score = 0;
            for(Node tree : trees){
                score += tree.score(features[a]);
            }
            utilities[a] = learningRate * score;
            max = Math.max(max, utilities[a]);
        }
        double sum = 0;
        for(int a = 0; a < utilities.length; a++){
            utilities[a] = Math.exp(utilities[a] - max);
            sum += utilities[a];
        }
        for(int a = 0; a < utilities.length; a++){
            utilities[a] /= sum;
        }
        return utilities;
    }

    public int treeCount(){
        return trees.size();
    }

    /** The BoostLab-chosen configuration (2024 chooser, 2025 confirm). */
    public static final int SHIPPED_TREES = 300;
    public static final int SHIPPED_DEPTH = 2;
    public static final double SHIPPED_LEARNING_RATE = 0.1;

    /**
     * THE shipped simulator brain since the BoostLab verdict of 2026-08-25:
     * held-out 2025 swept every gate against the linear logit (calibration
     * 0.57% vs 1.52%, my slots 0.50% vs 1.17%, QB-timing MAE 1.91 vs 2.08).
     * Trains on the full feature matrix with all season extras populated.
     */
    public static BoostedSelectionModel fitShipped(AAAConfiguration configuration, int lastSeason,
                                                   java.util.Map<String, Double> qbEarliness){
        return fit(SelectionModel.loadObservations(configuration, 2021, lastSeason, qbEarliness,
                        SelectionModel.positionEarliness(configuration, lastSeason,
                                PlayerImportAndSetup.Position.TE),
                        SelectionModel.positionEarliness(configuration, lastSeason,
                                PlayerImportAndSetup.Position.RB),
                        false, SelectionModel.trainRounds()),
                SHIPPED_TREES, SHIPPED_DEPTH, SHIPPED_LEARNING_RATE);
    }

    public static BoostedSelectionModel fit(List<SelectionModel.Observation> observations,
                                            int treeCount, int depth, double learningRate){
        List<double[]> rowList = new ArrayList<>();
        List<int[]> setBounds = new ArrayList<>();
        List<Integer> chosenRows = new ArrayList<>();
        for(SelectionModel.Observation observation : observations){
            int start = rowList.size();
            for(int a = 0; a < observation.features().length; a++){
                rowList.add(observation.features()[a]);
            }
            setBounds.add(new int[]{start, rowList.size()});
            chosenRows.add(start + observation.chosen());
        }
        double[][] rows = rowList.toArray(new double[0][]);
        boolean[] chosen = new boolean[rows.length];
        for(int row : chosenRows){
            chosen[row] = true;
        }

        double[] scores = new double[rows.length];
        double[] gradients = new double[rows.length];
        double[] hessians = new double[rows.length];
        List<Node> trees = new ArrayList<>();
        for(int round = 0; round < treeCount; round++){
            for(int[] bounds : setBounds){
                double max = Double.NEGATIVE_INFINITY;
                for(int r = bounds[0]; r < bounds[1]; r++){
                    max = Math.max(max, scores[r]);
                }
                double sum = 0;
                for(int r = bounds[0]; r < bounds[1]; r++){
                    sum += Math.exp(scores[r] - max);
                }
                for(int r = bounds[0]; r < bounds[1]; r++){
                    double probability = Math.exp(scores[r] - max) / sum;
                    gradients[r] = (chosen[r] ? 1.0 : 0.0) - probability;
                    hessians[r] = Math.max(probability * (1 - probability), 1e-6);
                }
            }
            int[] all = new int[rows.length];
            for(int r = 0; r < all.length; r++){
                all[r] = r;
            }
            Node tree = grow(rows, gradients, hessians, all, depth);
            trees.add(tree);
            for(int r = 0; r < rows.length; r++){
                scores[r] += learningRate * tree.score(rows[r]);
            }
        }
        return new BoostedSelectionModel(trees, learningRate);
    }

    private static final double LAMBDA = 1.0;   // L2 on leaf values, XGBoost-style

    private static Node grow(double[][] rows, double[] gradient, double[] hessian,
                             int[] members, int depth){
        Node node = new Node();
        double gradientSum = 0;
        double hessianSum = 0;
        for(int member : members){
            gradientSum += gradient[member];
            hessianSum += hessian[member];
        }
        node.value = gradientSum / (hessianSum + LAMBDA);
        if(depth == 0 || members.length < 2 * MIN_LEAF){
            return node;
        }

        int featureCount = rows.length == 0 ? 0 : rows[0].length;
        double parentScore = gradientSum * gradientSum / (hessianSum + LAMBDA);
        double bestGain = 1e-9;
        int bestFeature = -1;
        double bestThreshold = 0;
        for(int feature = 0; feature < featureCount; feature++){
            double[] values = new double[members.length];
            for(int i = 0; i < members.length; i++){
                values[i] = rows[members[i]][feature];
            }
            double[] sorted = values.clone();
            Arrays.sort(sorted);
            if(sorted[0] == sorted[sorted.length - 1]){
                continue;
            }
            for(int q = 1; q < THRESHOLDS; q++){
                double threshold = sorted[(int) ((long) q * (sorted.length - 1) / THRESHOLDS)];
                if(threshold >= sorted[sorted.length - 1]){
                    continue;
                }
                double leftGradient = 0;
                double leftHessian = 0;
                int leftCount = 0;
                for(int i = 0; i < members.length; i++){
                    if(values[i] <= threshold){
                        leftGradient += gradient[members[i]];
                        leftHessian += hessian[members[i]];
                        leftCount++;
                    }
                }
                int rightCount = members.length - leftCount;
                if(leftCount < MIN_LEAF || rightCount < MIN_LEAF){
                    continue;
                }
                double rightGradient = gradientSum - leftGradient;
                double rightHessian = hessianSum - leftHessian;
                double gain = leftGradient * leftGradient / (leftHessian + LAMBDA)
                        + rightGradient * rightGradient / (rightHessian + LAMBDA)
                        - parentScore;
                if(gain > bestGain){
                    bestGain = gain;
                    bestFeature = feature;
                    bestThreshold = threshold;
                }
            }
        }
        if(bestFeature < 0){
            return node;
        }
        int leftCount = 0;
        for(int member : members){
            if(rows[member][bestFeature] <= bestThreshold){
                leftCount++;
            }
        }
        int[] left = new int[leftCount];
        int[] right = new int[members.length - leftCount];
        int l = 0;
        int r = 0;
        for(int member : members){
            if(rows[member][bestFeature] <= bestThreshold){
                left[l++] = member;
            }
            else {
                right[r++] = member;
            }
        }
        node.feature = bestFeature;
        node.threshold = bestThreshold;
        node.left = grow(rows, gradient, hessian, left, depth - 1);
        node.right = grow(rows, gradient, hessian, right, depth - 1);
        return node;
    }

}
