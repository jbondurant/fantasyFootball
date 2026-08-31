import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Every plan, scored on every usable season. The matrix everything else reads.
 *
 * One row per plan, one column per season, and the entry is POINTS ABOVE THE
 * ADP BASELINE for that season - never the raw total. Raw totals are not
 * comparable across eras: a 2024 roster outscores a 2013 roster by hundreds of
 * points because the league throws more, the season is a week longer and the
 * scoring environment moved. Pooling raw totals would let that drift masquerade
 * as a difference between plans, and the whole regime question would be
 * answered by inflation. Differencing against the same season's
 * take-the-best-man-left baseline removes exactly that.
 *
 * Deterministic: opponents follow ADP, so the same plan on the same season
 * always scores the same number. Nothing here has a random seed, which means
 * any difference between eras is a difference in the football, not in the
 * sampling.
 */
public class EraScores {

    public record Table(List<String> seasons, List<List<Position>> plans,
                        double[][] value, double[] baseline, int rounds){

        public double[] season(int index){
            double[] column = new double[plans.size()];
            for(int plan = 0; plan < plans.size(); plan++){
                column[plan] = value[plan][index];
            }
            return column;
        }

        /** Mean value of one plan over a chosen set of seasons. */
        public double mean(int plan, List<Integer> seasonIndexes){
            double total = 0;
            for(int index : seasonIndexes){
                total += value[plan][index];
            }
            return seasonIndexes.isEmpty() ? 0 : total / seasonIndexes.size();
        }

        /** Weighted mean, for the decay-weighting question. */
        public double mean(int plan, List<Integer> seasonIndexes, double[] weights){
            double total = 0;
            double sum = 0;
            for(int index : seasonIndexes){
                total += value[plan][index] * weights[index];
                sum += weights[index];
            }
            return sum == 0 ? 0 : total / sum;
        }

        /** The plan that scores best over these seasons, at these weights. */
        public int best(List<Integer> seasonIndexes, double[] weights){
            int best = 0;
            double bestValue = Double.NEGATIVE_INFINITY;
            for(int plan = 0; plan < plans.size(); plan++){
                double mean = weights == null ? mean(plan, seasonIndexes)
                        : mean(plan, seasonIndexes, weights);
                if(mean > bestValue){
                    bestValue = mean;
                    best = plan;
                }
            }
            return best;
        }

        public List<Integer> indexesBefore(int cutoff){
            List<Integer> indexes = new ArrayList<>();
            for(int i = 0; i < seasons.size(); i++){
                if(Integer.parseInt(seasons.get(i)) < cutoff){
                    indexes.add(i);
                }
            }
            return indexes;
        }

        public List<Integer> indexesFrom(int cutoff){
            List<Integer> indexes = new ArrayList<>();
            for(int i = 0; i < seasons.size(); i++){
                if(Integer.parseInt(seasons.get(i)) >= cutoff){
                    indexes.add(i);
                }
            }
            return indexes;
        }
    }

    public static Table compute(Map<String, EraBoards.Board> boards, int rounds){
        return compute(boards, rounds, Integer.getInteger("planSample", 0));
    }

    /**
     * @param sample 0 for every legal plan; otherwise a seeded random subset.
     *
     * The full space is four hundred thousand sequences and takes nine minutes
     * to score. That is the right space for the headline run - the fit should
     * range over every plan that is legal, not over a shortlist somebody chose.
     * For the control runs, where the question is whether a conclusion MOVES
     * rather than what the exact number is, a seeded subsample answers the same
     * question in a minute. The seed is fixed so two control runs are comparable
     * to each other.
     */
    public static Table compute(Map<String, EraBoards.Board> boards, int rounds,
                                int sample){
        List<String> seasons = new ArrayList<>(boards.keySet());
        Map<String, List<String>> keepers = new LinkedHashMap<>();
        Map<Position, Integer> held = new LinkedHashMap<>();
        for(String season : seasons){
            EraBoards.Board board = boards.get(season);
            List<String> mine = EraGame.keepers(board);
            keepers.put(season, mine);
            Map<Position, Integer> thisSeason = new LinkedHashMap<>();
            for(String id : mine){
                thisSeason.merge(board.positionOf().get(id), 1, Integer::sum);
            }
            // The FEWEST any season hands me, so the plan space is legal on
            // every board rather than only on the last one enumerated.
            if(held.isEmpty()){
                held.putAll(thisSeason);
            }
            else {
                held.replaceAll((position, count) ->
                        Math.min(count, thisSeason.getOrDefault(position, 0)));
            }
        }
        List<List<Position>> enumerated = EraPlans.all(rounds, held);
        if(sample > 0 && sample < enumerated.size()){
            java.util.Collections.shuffle(enumerated, new java.util.Random(20260830L));
            enumerated = new ArrayList<>(enumerated.subList(0, sample));
        }
        final List<List<Position>> plans = enumerated;

        double[] baseline = new double[seasons.size()];
        for(int s = 0; s < seasons.size(); s++){
            baseline[s] = EraGame.bestAvailableScore(boards.get(seasons.get(s)), rounds,
                    keepers.get(seasons.get(s)));
        }

        double[][] value = new double[plans.size()][seasons.size()];
        IntStream.range(0, plans.size()).parallel().forEach(p -> {
            for(int s = 0; s < seasons.size(); s++){
                String season = seasons.get(s);
                value[p][s] = EraGame.score(boards.get(season), plans.get(p),
                        keepers.get(season)) - baseline[s];
            }
        });
        return new Table(seasons, plans, value, baseline, rounds);
    }

    /** Flat weights - the "pool everything equally" policy. */
    public static double[] flat(int seasons){
        double[] weights = new double[seasons];
        java.util.Arrays.fill(weights, 1.0);
        return weights;
    }

    /**
     * Exponential recency weights with half-life h, in seasons.
     *
     * A half-life rather than a cutoff because a cutoff is a half-life of zero
     * dressed up as a principle: it throws away everything on one side of a
     * line chosen by eye. The half-life that actually predicts held-out seasons
     * best is an empirical question, and RegimeShift asks it.
     */
    public static double[] decay(List<String> seasons, double halfLife){
        double[] weights = new double[seasons.size()];
        int newest = Integer.parseInt(seasons.get(seasons.size() - 1));
        for(int i = 0; i < seasons.size(); i++){
            int age = newest - Integer.parseInt(seasons.get(i));
            weights[i] = Math.pow(0.5, age / halfLife);
        }
        return weights;
    }

    /**
     * Kish's effective sample size.
     *
     * Thirteen seasons weighted unequally are not thirteen seasons' worth of
     * evidence. (sum w)^2 / sum w^2 is how many equally-weighted seasons the
     * weighting is actually worth, and it is the honest number to quote after
     * recommending that the old ones count for less.
     */
    public static double effectiveSampleSize(double[] weights){
        double sum = 0;
        double squares = 0;
        for(double weight : weights){
            sum += weight;
            squares += weight * weight;
        }
        return squares == 0 ? 0 : sum * sum / squares;
    }
}
