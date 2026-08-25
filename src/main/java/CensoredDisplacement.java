import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The learned availability distribution, with the censoring fixed.
 *
 * PickDisplacement lost the gate because it fit only on players who were
 * drafted - it never saw the ones who sat, so its deep-board sample was biased
 * toward early exits. This fit includes them: an undrafted player with par
 * rank p in a draft of S selections is a censored observation, residual > S-p,
 * and enters the likelihood as survival mass rather than being dropped.
 *
 * The family is a split normal - location mu, a left scale and a right scale -
 * because the asymmetry is real (nobody goes forty selections early off a top
 * par, plenty of players fall forty late) and a parametric tail can
 * extrapolate where a bootstrap cannot. Location carries a per-position offset
 * and a linear depth term; both scales are log-linear in depth, so the spread
 * grows continuously down the board instead of jumping at bin edges. Nine
 * parameters, fitted by censored maximum likelihood over ~1000 observations.
 *
 *     ./gradlew run -Pmain=CensoredDisplacement
 */
public class CensoredDisplacement implements DisplacementModel {

    /** value = residual if exact; the survival threshold if censored. */
    public record Row(Position position, int parDepth, double value, boolean censored) {}

    static final double PAR_LIMIT_FOR_CENSORED = 200;

    // theta: offQB, offRB, offWR, offTE, depthSlope, l0, l1, r0, r1
    private final double[] theta;
    private final int exactRows;
    private final int censoredRows;

    private CensoredDisplacement(double[] theta, int exactRows, int censoredRows){
        this.theta = theta;
        this.exactRows = exactRows;
        this.censoredRows = censoredRows;
    }

    private static int offsetIndex(Position position){
        return switch (position) {
            case QB -> 0;
            case RB -> 1;
            case WR -> 2;
            case TE -> 3;
            default -> 1;
        };
    }

    private static double normalizedDepth(int parDepth){
        return (parDepth - 60.0) / 60.0;
    }

    double mu(int parDepth, Position position){
        return theta[offsetIndex(position)] + theta[4] * normalizedDepth(parDepth);
    }

    double sigmaLeft(int parDepth){
        return clampSigma(Math.exp(theta[5] + theta[6] * normalizedDepth(parDepth)));
    }

    double sigmaRight(int parDepth){
        return clampSigma(Math.exp(theta[7] + theta[8] * normalizedDepth(parDepth)));
    }

    private static double clampSigma(double sigma){
        return Math.max(1.5, Math.min(sigma, 120.0));
    }

    @Override
    public double sample(Random random, int parDepth, Position position){
        double mu = mu(parDepth, position);
        double left = sigmaLeft(parDepth);
        double right = sigmaRight(parDepth);
        if(random.nextDouble() < left / (left + right)){
            return mu - Math.abs(random.nextGaussian()) * left;
        }
        return mu + Math.abs(random.nextGaussian()) * right;
    }

    // ---- split-normal math ----

    static double logDensity(double x, double mu, double left, double right){
        double a = Math.sqrt(2.0 / Math.PI) / (left + right);
        double sigma = x < mu ? left : right;
        double z = (x - mu) / sigma;
        return Math.log(a) - z * z / 2.0;
    }

    static double cdf(double x, double mu, double left, double right){
        if(x < mu){
            return 2.0 * left / (left + right) * phi((x - mu) / left);
        }
        return left / (left + right) + 2.0 * right / (left + right) * (phi((x - mu) / right) - 0.5);
    }

    static double phi(double z){
        return 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
    }

    static double erf(double z){
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(z));
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t * Math.exp(-z * z);
        return z >= 0 ? y : -y;
    }

    // ---- fitting ----

    static double negativeLogLikelihood(double[] theta, List<Row> rows){
        CensoredDisplacement model = new CensoredDisplacement(theta, 0, 0);
        double total = 0.0;
        for(Row row : rows){
            double mu = model.mu(row.parDepth(), row.position());
            double left = model.sigmaLeft(row.parDepth());
            double right = model.sigmaRight(row.parDepth());
            if(row.censored()){
                double survival = 1.0 - cdf(row.value(), mu, left, right);
                total -= Math.log(Math.max(survival, 1e-12));
            }
            else {
                total -= logDensity(row.value(), mu, left, right);
            }
        }
        return total;
    }

    /** Nelder-Mead, restarted once; the surface is smooth and low-dimensional. */
    public static CensoredDisplacement fitFromRows(List<Row> rows){
        double[] start = {16, -5, -16, 4, 0, Math.log(12), 0.3, Math.log(15), 0.3};
        double[] best = nelderMead(start, rows);
        double[] retry = best.clone();
        for(int i = 0; i < retry.length; i++){
            retry[i] += (i % 2 == 0 ? 1 : -1) * 0.5;
        }
        double[] second = nelderMead(retry, rows);
        if(negativeLogLikelihood(second, rows) < negativeLogLikelihood(best, rows)){
            best = second;
        }
        int exact = (int) rows.stream().filter(r -> !r.censored()).count();
        return new CensoredDisplacement(best, exact, rows.size() - exact);
    }

    private static double[] nelderMead(double[] start, List<Row> rows){
        int n = start.length;
        double[][] simplex = new double[n + 1][];
        double[] steps = {3, 3, 3, 3, 2, 0.3, 0.2, 0.3, 0.2};
        simplex[0] = start.clone();
        for(int i = 0; i < n; i++){
            simplex[i + 1] = start.clone();
            simplex[i + 1][i] += steps[i];
        }
        double[] values = new double[n + 1];
        for(int i = 0; i <= n; i++){
            values[i] = negativeLogLikelihood(simplex[i], rows);
        }
        for(int iteration = 0; iteration < 2000; iteration++){
            Integer[] order = new Integer[n + 1];
            for(int i = 0; i <= n; i++) order[i] = i;
            java.util.Arrays.sort(order, Comparator.comparingDouble(i -> values[i]));
            int bestI = order[0], worstI = order[n], secondWorstI = order[n - 1];
            if(values[worstI] - values[bestI] < 1e-8){
                break;
            }
            double[] centroid = new double[n];
            for(int i = 0; i <= n; i++){
                if(i == worstI) continue;
                for(int d = 0; d < n; d++) centroid[d] += simplex[i][d] / n;
            }
            double[] reflected = blend(centroid, simplex[worstI], -1.0);
            double reflectedValue = negativeLogLikelihood(reflected, rows);
            if(reflectedValue < values[bestI]){
                double[] expanded = blend(centroid, simplex[worstI], -2.0);
                double expandedValue = negativeLogLikelihood(expanded, rows);
                if(expandedValue < reflectedValue){
                    simplex[worstI] = expanded; values[worstI] = expandedValue;
                } else {
                    simplex[worstI] = reflected; values[worstI] = reflectedValue;
                }
            }
            else if(reflectedValue < values[secondWorstI]){
                simplex[worstI] = reflected; values[worstI] = reflectedValue;
            }
            else {
                double[] contracted = blend(centroid, simplex[worstI], 0.5);
                double contractedValue = negativeLogLikelihood(contracted, rows);
                if(contractedValue < values[worstI]){
                    simplex[worstI] = contracted; values[worstI] = contractedValue;
                }
                else {
                    for(int i = 0; i <= n; i++){
                        if(i == bestI) continue;
                        simplex[i] = blend(simplex[bestI], simplex[i], 0.5);
                        values[i] = negativeLogLikelihood(simplex[i], rows);
                    }
                }
            }
        }
        int best = 0;
        for(int i = 1; i <= n; i++){
            if(values[i] < values[best]) best = i;
        }
        return simplex[best];
    }

    private static double[] blend(double[] centroid, double[] point, double factor){
        double[] out = new double[centroid.length];
        for(int d = 0; d < out.length; d++){
            out[d] = centroid[d] + factor * (point[d] - centroid[d]);
        }
        return out;
    }

    // ---- data ----

    public static CensoredDisplacement fitThroughSeason(AAAConfiguration configuration, int lastSeason){
        return fitFromRows(loadRows(configuration, lastSeason));
    }

    public static List<Row> loadRows(AAAConfiguration configuration, int lastSeason){
        List<Row> rows = new ArrayList<>();
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();
        for(int i = 0; i < drafts.size() && i < seasons.size(); i++){
            String season = seasons.get(i);
            if(season == null || Integer.parseInt(season) > lastSeason){
                continue;
            }
            Map<String, Double> adp = HistoricalProjections.adpBySleeperID(configuration, season);

            List<JsonObject> picks = new ArrayList<>();
            Set<String> kept = new HashSet<>();
            for(JsonElement pickElement : drafts.get(i)){
                JsonObject pick = pickElement.getAsJsonObject();
                JsonElement isKeeper = pick.get("is_keeper");
                if(isKeeper != null && !isKeeper.isJsonNull() && isKeeper.getAsBoolean()){
                    kept.add(pick.get("player_id").getAsString());
                }
                else {
                    picks.add(pick);
                }
            }
            picks.sort(Comparator.comparingInt(p -> p.get("pick_no").getAsInt()));
            int totalSelections = picks.size();

            List<Map.Entry<String, Double>> pool = new ArrayList<>();
            for(Map.Entry<String, Double> entry : adp.entrySet()){
                if(entry.getValue() < 900 && !kept.contains(entry.getKey())){
                    pool.add(entry);
                }
            }
            pool.sort(Map.Entry.comparingByValue());
            Map<String, Integer> parDepth = new HashMap<>();
            for(int rank = 0; rank < pool.size(); rank++){
                parDepth.put(pool.get(rank).getKey(), rank + 1);
            }

            Set<String> drafted = new HashSet<>();
            int selection = 0;
            for(JsonObject pick : picks){
                selection++;
                String sleeperID = pick.get("player_id").getAsString();
                drafted.add(sleeperID);
                Integer par = parDepth.get(sleeperID);
                if(par == null){
                    continue;
                }
                Position position = skillPositionOf(sleeperID);
                if(position != null){
                    rows.add(new Row(position, par, selection - par, false));
                }
            }

            // The fix: the players who SAT are observations too. An undrafted
            // player with par p survived all S selections - residual > S - p.
            for(Map.Entry<String, Double> entry : pool){
                String sleeperID = entry.getKey();
                int par = parDepth.get(sleeperID);
                if(drafted.contains(sleeperID) || par > PAR_LIMIT_FOR_CENSORED){
                    continue;
                }
                Position position = skillPositionOf(sleeperID);
                if(position != null){
                    rows.add(new Row(position, par, totalSelections - par, true));
                }
            }
        }
        return rows;
    }

    private static Position skillPositionOf(String sleeperID){
        Player player = Player.getPlayerFromSIDV2(sleeperID);
        if(player == null || !StartingLineup.isSkillPosition(player.position)){
            return null;
        }
        return player.position;
    }

    /**
     * The same fitted shape with deviations widened by a factor.
     *
     * The MLE fits MARGINAL displacements, but the simulator draws
     * independently and rank-sorts, and sorting shrinks dispersion - so the
     * drawn spread must be wider than the observed marginals to reproduce them
     * after the sort. The factor is tuned end-to-end through the simulator on
     * a season the fit never saw, exactly how the Gaussian's sigma was tuned.
     */
    public DisplacementModel scaled(double factor){
        CensoredDisplacement base = this;
        return (random, parDepth, position) -> {
            double mu = base.mu(parDepth, position);
            return mu + (base.sample(random, parDepth, position) - mu) * factor;
        };
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        CensoredDisplacement fitted = fitThroughSeason(configuration, lastCompleted);

        System.out.printf("Censored MLE on %d exact + %d censored rows, 2021-%d%n%n",
                fitted.exactRows, fitted.censoredRows, lastCompleted);
        System.out.println("position offsets, selections (positive = the league lets them fall):");
        for(Position position : List.of(Position.QB, Position.RB, Position.WR, Position.TE)){
            System.out.printf("   %-3s %+7.1f%n", position, fitted.theta[offsetIndex(position)]);
        }
        System.out.printf("%ndepth slope: %+.1f selections per 60 of par depth%n", fitted.theta[4]);
        System.out.println("\nspread by depth (left = reach direction, right = fall direction):");
        for(int par : new int[]{20, 60, 120, 180}){
            System.out.printf("   par %-4d  sigmaL %5.1f   sigmaR %5.1f%n",
                    par, fitted.sigmaLeft(par), fitted.sigmaRight(par));
        }
    }

}
