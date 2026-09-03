import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Plain-Java gradient-boosted regression trees, squared error - the
 * dependency-free regression sibling of BoostedSelectionModel (same
 * XGBoost-style Newton leaves G/(H+lambda), quantile splits, deterministic
 * fit). Small on purpose: the tournament's learned policies need a function
 * approximator, not a framework.
 */
public class BoostedRegressor {

    private static final class Node {
        int feature = -1;
        double threshold;
        Node left, right;
        double value;

        double score(double[] row){
            Node node = this;
            while(node.feature >= 0){
                node = row[node.feature] <= node.threshold ? node.left : node.right;
            }
            return node.value;
        }
    }

    private static final int MIN_LEAF = 20;
    private static final int THRESHOLDS = 16;
    private static final double LAMBDA = 1.0;

    private final List<Node> trees = new ArrayList<>();
    private final double learningRate;
    private final double base;

    private BoostedRegressor(double base, double learningRate){
        this.base = base;
        this.learningRate = learningRate;
    }

    public double predict(double[] row){
        double sum = base;
        for(Node tree : trees){
            sum += learningRate * tree.score(row);
        }
        return sum;
    }

    /** Squared-error boosting: each tree fits the current residuals. */
    public static BoostedRegressor fit(double[][] rows, double[] targets,
                                       int treeCount, int depth, double learningRate){
        double mean = 0;
        for(double target : targets){
            mean += target;
        }
        mean /= Math.max(targets.length, 1);
        BoostedRegressor model = new BoostedRegressor(mean, learningRate);
        double[] predictions = new double[targets.length];
        Arrays.fill(predictions, mean);
        int[] all = new int[rows.length];
        for(int i = 0; i < all.length; i++){
            all[i] = i;
        }
        for(int t = 0; t < treeCount; t++){
            double[] residuals = new double[targets.length];
            for(int i = 0; i < targets.length; i++){
                residuals[i] = targets[i] - predictions[i];
            }
            Node tree = grow(rows, residuals, all, depth);
            model.trees.add(tree);
            for(int i = 0; i < targets.length; i++){
                predictions[i] += learningRate * tree.score(rows[i]);
            }
        }
        return model;
    }

    private static Node grow(double[][] rows, double[] residuals, int[] members, int depth){
        Node node = new Node();
        double sum = 0;
        for(int member : members){
            sum += residuals[member];
        }
        node.value = sum / (members.length + LAMBDA);
        if(depth == 0 || members.length < 2 * MIN_LEAF){
            return node;
        }
        double bestGain = 1e-9;
        int bestFeature = -1;
        double bestThreshold = 0;
        double parentScore = sum * sum / (members.length + LAMBDA);
        int features = rows[0].length;
        for(int f = 0; f < features; f++){
            double[] sorted = new double[members.length];
            for(int i = 0; i < members.length; i++){
                sorted[i] = rows[members[i]][f];
            }
            Arrays.sort(sorted);
            double previous = Double.NaN;
            for(int q = 1; q < THRESHOLDS; q++){
                double threshold = sorted[(int) ((long) q * (sorted.length - 1) / THRESHOLDS)];
                if(threshold == previous){
                    continue;
                }
                previous = threshold;
                double leftSum = 0;
                int leftCount = 0;
                for(int member : members){
                    if(rows[member][f] <= threshold){
                        leftSum += residuals[member];
                        leftCount++;
                    }
                }
                int rightCount = members.length - leftCount;
                if(leftCount < MIN_LEAF || rightCount < MIN_LEAF){
                    continue;
                }
                double rightSum = sum - leftSum;
                double gain = leftSum * leftSum / (leftCount + LAMBDA)
                        + rightSum * rightSum / (rightCount + LAMBDA) - parentScore;
                if(gain > bestGain){
                    bestGain = gain;
                    bestFeature = f;
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
        node.left = grow(rows, residuals, left, depth - 1);
        node.right = grow(rows, residuals, right, depth - 1);
        return node;
    }
}
