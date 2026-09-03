/**
 * Different TYPES of opponent pick behavior, built by wrapping any fitted
 * ChoiceModel - the raw material for mismatch worlds where the planner's
 * assumed brain is wrong in kind, not just in parameters.
 *
 *   sharpen(model, p)  raises every choice probability to the p-th power and
 *                      renormalizes: p large = a drone league (everyone takes
 *                      the model-modal pick, autodraft-like predictability),
 *                      p in (0,1) = a sloppier league, p = 1 = unchanged.
 *   chaos(model, eps)  mixes with the uniform choice: with probability eps a
 *                      manager picks anything in the choice set - the
 *                      tilted-uncle league.
 */
public class OpponentVariants {

    public static ChoiceModel sharpen(ChoiceModel base, double power){
        return features -> {
            double[] probabilities = base.choiceProbabilities(features);
            double total = 0;
            double[] sharpened = new double[probabilities.length];
            for(int a = 0; a < probabilities.length; a++){
                sharpened[a] = Math.pow(Math.max(probabilities[a], 1e-12), power);
                total += sharpened[a];
            }
            for(int a = 0; a < sharpened.length; a++){
                sharpened[a] /= total;
            }
            return sharpened;
        };
    }

    /**
     * A Sleeper autodrafter: takes the best remaining player by ADP, every
     * time. The choice set arrives in ADP order, so that is index 0. Justin
     * reports JFMarino (slot 8, adjacent to his own slot 7) is ~50/50 to
     * autodraft the first five rounds - and an autodrafter is a DETERMINISTIC
     * opponent, which is strictly more predictable than a human and therefore
     * exploitable in wait-or-take decisions.
     */
    public static ChoiceModel autodraft(){
        return features -> {
            double[] probabilities = new double[features.length];
            if(probabilities.length > 0){
                probabilities[0] = 1.0;
            }
            return probabilities;
        };
    }

    public static ChoiceModel chaos(ChoiceModel base, double epsilon){
        return features -> {
            double[] probabilities = base.choiceProbabilities(features);
            double uniform = 1.0 / probabilities.length;
            double[] mixed = new double[probabilities.length];
            for(int a = 0; a < probabilities.length; a++){
                mixed[a] = (1 - epsilon) * probabilities[a] + epsilon * uniform;
            }
            return mixed;
        };
    }
}
