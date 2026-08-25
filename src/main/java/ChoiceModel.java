/**
 * Anything that can turn a choice set's feature matrix into pick
 * probabilities. The simulator only needs this - which is what lets model
 * classes compete through the same gates.
 */
public interface ChoiceModel {
    double[] choiceProbabilities(double[][] features);
}
