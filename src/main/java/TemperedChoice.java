/**
 * Any choice model, at a temperature.
 *
 * `SelectionModel.scaled` implements temperature as a scaled beta, which is
 * exact for a logit and is tuned on a held-out season. The SHIPPED model is
 * BoostedSelectionModel, which has no such method - so the model the draft
 * actually runs on has never had its dispersion tuned at all.
 *
 * That matters specifically for the survival table. Temperature controls how
 * spread out opponent choices are, which IS the width of a survival curve: too
 * cold and every simulated draft agrees, so the table says a man is certainly
 * gone when he is merely likely to be.
 *
 * p_i^(1/t) renormalised. For a logit this is exactly the scaled beta, so the
 * two definitions agree where they overlap.
 */
public final class TemperedChoice implements ChoiceModel {

    private final ChoiceModel base;
    private final double temperature;

    public TemperedChoice(ChoiceModel base, double temperature){
        if(temperature <= 0){
            throw new IllegalArgumentException("temperature must be positive, got "
                    + temperature);
        }
        this.base = base;
        this.temperature = temperature;
    }

    @Override
    public double[] choiceProbabilities(double[][] features){
        double[] raw = base.choiceProbabilities(features);
        if(temperature == 1.0){
            return raw;
        }
        double[] out = new double[raw.length];
        double total = 0;
        for(int i = 0; i < raw.length; i++){
            // A zero stays zero at any temperature; pow(0, x) is 0 for x > 0.
            out[i] = Math.pow(Math.max(0, raw[i]), 1.0 / temperature);
            total += out[i];
        }
        if(total <= 0){
            // Everything underflowed. Fall back rather than return NaNs - a
            // uniform choice is wrong but it is not poison.
            java.util.Arrays.fill(out, 1.0 / out.length);
            return out;
        }
        for(int i = 0; i < out.length; i++){
            out[i] /= total;
        }
        return out;
    }
}
