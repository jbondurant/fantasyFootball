import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * A draft rule built from the pairwise matrix, and nothing else.
 *
 * Justin: "can we create a model from this matrix... I don't want any
 * intraseason modelling." So this uses exactly two numbers a season, both
 * knowable in August: a man's preseason positional rank, and what he scored by
 * February. No weeks, no injury channel, no bust-versus-hurt distinction - a
 * season a man missed half of is simply a season he scored little in, which is
 * how the matrix already counts it.
 *
 * THE RULE. At every pick, for each position, the matrix says how often the man
 * still there at my NEXT pick outscores the man on the board now. That number
 * IS the cost of waiting, and it already contains board depletion, because the
 * ranks at both picks are read off the live ADP. So: take the position where
 * waiting costs most - the lowest cell in that row - subject to what a legal
 * roster still needs.
 *
 * Nothing here is fitted to a draft outcome. The odds curve is fitted to
 * sixteen seasons of who-beat-whom, and the rule on top of it has no free
 * parameters at all, which is the property that made the committed plan hard to
 * beat: there is nothing to overfit at the table.
 *
 * WHETHER IT IS ANY GOOD IS A SEPARATE QUESTION, and this answers it rather than
 * asserting it: the shape it produces is scored against the committed plan on
 * real outcomes, with the same 125-point bar every other claim here has had to
 * clear.
 *
 *   ./gradlew run -Pmain=RankDraft [-PholdKeepers=true]
 */
public class RankDraft {

    /** Nobody rosters nine backs; these cap the greedy rule's appetite. */
    static final Map<Position, Integer> MOST = new EnumMap<>(Map.of(
            Position.QB, 2, Position.RB, 7, Position.WR, 8,
            Position.TE, 2, Position.DEF, 1));

    public static void main(String[] args) throws Exception {
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        int[] ties = new int[1];
        List<PairwiseOdds.Pair> everything = PairwiseOdds.pairs(men, -1, false, ties);
        PairwiseOdds.Model odds = PairwiseOdds.latent(everything, 0, 0.25);

        int[] picks = {7, 18, 31, 42, 55, 66, 79, 90, 103, 114, 127, 162, 175, 186};
        Position[] shown = {Position.RB, Position.WR, Position.TE, Position.QB, Position.DEF};
        Map<Position, List<Double>> adp = board(shown);

        System.out.printf("%nA DRAFT RULE FROM THE MATRIX ALONE%n%n");
        System.out.printf("%d seasons, %d pairs. preseason rank in, season points out.%n",
                order.size(), everything.size());
        System.out.printf("no weeks, no injury channel, no free parameters at the table.%n%n");

        System.out.printf("%-14s", "PICK");
        for(Position position : shown){
            System.out.printf(" %7s", position);
        }
        System.out.printf("   %s%n", "TAKE");

        Map<Position, Integer> have = new EnumMap<>(Position.class);
        List<Position> shape = new ArrayList<>();
        for(int i = 0; i < picks.length; i++){
            int next = i + 1 < picks.length ? picks[i + 1] : -1;
            System.out.printf("%4d -> %-7s", picks[i], next < 0 ? "end" : String.valueOf(next));
            Position take = null;
            double worst = 2;
            Map<Position, Double> cost = new EnumMap<>(Position.class);
            for(Position position : shown){
                Double p = next < 0 ? null : waitCost(odds, adp, position, picks[i], next);
                cost.put(position, p == null ? Double.NaN : p);
                System.out.printf(" %6s", p == null ? "-" : String.format("%.0f%%", 100 * p));
            }
            // Lowest P(the later man is better) = the most expensive wait.
            for(Position position : shown){
                if(have.getOrDefault(position, 0) >= MOST.get(position)){
                    continue;
                }
                if(!stillPossible(have, shape.size(), picks.length, position)){
                    continue;
                }
                double p = cost.get(position);
                double effective = Double.isNaN(p) ? 0.5 : p;
                if(mustTake(have, shape.size(), picks.length, position)){
                    effective = -1;
                }
                if(effective < worst){
                    worst = effective;
                    take = position;
                }
            }
            if(take == null){
                take = Position.WR;
            }
            have.merge(take, 1, Integer::sum);
            shape.add(take);
            System.out.printf("   %s%n", take);
        }

        String rendered = render(shape);
        System.out.printf("%nthe shape this produces: %s%n", rendered);

        System.out.printf("%n%s%nIS IT ANY GOOD?%n%s%n", "=".repeat(64), "=".repeat(64));
        System.out.printf("scored on real outcomes against the plan Justin is drafting with.%n%n");
        // The matrix alone cannot do this job, and the shape above shows why:
        // it took a tight end at 18 because tight ends decay fastest there,
        // ignoring that a tight end is worth far less than a back. The matrix is
        // WITHIN-position by construction - it knows how fast a position falls
        // away, never how much it is worth - so a rule reading only probability
        // has no way to compare one position against another.
        //
        // The repair keeps Justin's constraint exactly: still two numbers a
        // season, still no weeks. Price the wait in POINTS rather than in
        // probability - what the mean man at this rank scored against the mean
        // man at the rank I would face later - and the positions become
        // comparable.
        Map<Position, double[]> curve = pointsByRank(men);
        Map<Position, Integer> held = new EnumMap<>(Position.class);
        List<Position> byPoints = new ArrayList<>();
        System.out.printf("%n%s%nTHE SAME RULE, PRICED IN POINTS%n%s%n",
                "=".repeat(64), "=".repeat(64));
        System.out.printf("%nseason points the mean man at this rank scored, minus the mean man%n"
                + "at the rank I would face at my next pick. same two numbers a season.%n%n");
        System.out.printf("%-14s", "PICK");
        for(Position position : shown){
            System.out.printf(" %7s", position);
        }
        System.out.printf("   %s%n", "TAKE");
        for(int i = 0; i < picks.length; i++){
            int next = i + 1 < picks.length ? picks[i + 1] : -1;
            System.out.printf("%4d -> %-7s", picks[i], next < 0 ? "end" : String.valueOf(next));
            Position take = null;
            double most = -1e9;
            for(Position position : shown){
                double loss = next < 0 ? Double.NaN
                        : pointsLost(curve, adp, position, picks[i], next);
                System.out.printf(" %7s", Double.isNaN(loss) ? "-"
                        : String.format("%.0f", loss));
                if(held.getOrDefault(position, 0) >= MOST.get(position)){
                    continue;
                }
                double effective = Double.isNaN(loss) ? 0 : loss;
                if(mustTake(held, byPoints.size(), picks.length, position)){
                    effective = 1e9;
                }
                if(effective > most){
                    most = effective;
                    take = position;
                }
            }
            if(take == null){
                take = Position.WR;
            }
            held.merge(take, 1, Integer::sum);
            byPoints.add(take);
            System.out.printf("   %s%n", take);
        }
        String pointsShape = render(byPoints);
        System.out.printf("%nthe shape this produces: %s%n", pointsShape);

        System.out.printf("%n%s%nARE EITHER ANY GOOD?%n%s%n", "=".repeat(64), "=".repeat(64));
        PlanBacktest.STRATEGIES.put("matrix rule, odds", rendered);
        PlanBacktest.STRATEGIES.put("matrix rule, points", pointsShape);
        PlanBacktest.main(new String[0]);
    }

    /** Mean season points by positional rank, smoothed over neighbours in log rank. */
    static Map<Position, double[]> pointsByRank(List<PairwiseOdds.Man> men){
        Map<Position, double[]> total = new EnumMap<>(Position.class);
        Map<Position, int[]> count = new EnumMap<>(Position.class);
        for(PairwiseOdds.Man man : men){
            int cap = PairwiseOdds.CAP.getOrDefault(man.position(), 0);
            if(man.rank() > cap){
                continue;
            }
            total.computeIfAbsent(man.position(), u -> new double[cap + 1])[man.rank()]
                    += man.points();
            count.computeIfAbsent(man.position(), u -> new int[cap + 1])[man.rank()]++;
        }
        Map<Position, double[]> mean = new EnumMap<>(Position.class);
        for(Map.Entry<Position, double[]> entry : total.entrySet()){
            double[] sums = entry.getValue();
            int[] seen = count.get(entry.getKey());
            double[] out = new double[sums.length];
            for(int rank = 1; rank < sums.length; rank++){
                // Same log-rank window the odds curve uses, so the two views of
                // the board agree about how wide a neighbourhood is.
                double from = rank * Math.exp(-0.25);
                double to = rank * Math.exp(0.25);
                double sum = 0;
                int n = 0;
                for(int r = Math.max(1, (int) Math.floor(from));
                        r <= Math.min(sums.length - 1, (int) Math.ceil(to)); r++){
                    sum += sums[r];
                    n += seen[r];
                }
                out[rank] = n == 0 ? 0 : sum / n;
            }
            mean.put(entry.getKey(), out);
        }
        return mean;
    }

    /** Points given up by waiting: the mean man now, minus the mean man later. */
    static double pointsLost(Map<Position, double[]> curve, Map<Position, List<Double>> adp,
                             Position position, int now, int next){
        double[] mean = curve.get(position);
        List<Double> board = adp.get(position);
        if(mean == null || board == null || board.isEmpty()){
            return Double.NaN;
        }
        int early = depth(board, now);
        int late = depth(board, next);
        if(early >= late || late >= mean.length || early < 1){
            return Double.NaN;
        }
        return mean[early] - mean[late];
    }

    /** P(the best man at this position at `next` outscores the one there now). */
    static Double waitCost(PairwiseOdds.Model odds, Map<Position, List<Double>> adp,
                           Position position, int now, int next){
        List<Double> board = adp.get(position);
        if(board == null || board.isEmpty()){
            return null;
        }
        int early = depth(board, now);
        int late = depth(board, next);
        Integer cap = PairwiseOdds.CAP.get(position);
        if(cap == null || early >= late || late > cap){
            return null;
        }
        return odds.probability(position, early, late);
    }

    /** Would skipping this position now make a legal roster impossible? */
    static boolean mustTake(Map<Position, Integer> have, int made, int total, Position position){
        int left = total - made;
        int owed = 0;
        for(Map.Entry<Position, Integer> need : PlanBacktest.requiredPicks().entrySet()){
            owed += Math.max(0, need.getValue() - have.getOrDefault(need.getKey(), 0));
        }
        int mine = Math.max(0, PlanBacktest.requiredPicks().getOrDefault(position, 0)
                - have.getOrDefault(position, 0));
        return mine > 0 && owed >= left;
    }

    static boolean stillPossible(Map<Position, Integer> have, int made, int total,
                                 Position position){
        return true;
    }

    /**
     * The draftable board. Kept men are on somebody's roster and are not on it.
     *
     * Justin caught this in LiveBoard - twenty-four men are kept league-wide and
     * every recommendation was naming one of them. The same pool is built here
     * and had the same fault, so the fix belongs here rather than at each caller.
     */
    static Map<Position, List<Double>> board(Position[] shown){
        return board(shown, LiveBoard.kept(AAAConfiguration.getInstance()));
    }

    static Map<Position, List<Double>> board(Position[] shown, java.util.Set<String> kept){
        Map<Position, List<Double>> adp = new EnumMap<>(Position.class);
        for(Position position : shown){
            adp.put(position, new ArrayList<>());
        }
        for(String id : ProjectionSources.resolve(
                System.getProperty("projections", "sleeper")).keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            double value = SleeperProjections.adpOf(id);
            if(kept.contains(id)){
                continue;
            }
            if(player != null && adp.containsKey(player.position) && value < Double.MAX_VALUE){
                adp.get(player.position).add(value);
            }
        }
        for(List<Double> values : adp.values()){
            Collections.sort(values);
        }
        return adp;
    }

    static int depth(List<Double> sorted, int pick){
        int gone = 0;
        for(double value : sorted){
            if(value < pick){
                gone++;
            }
        }
        return gone + 1;
    }

    static String render(List<Position> shape){
        StringBuilder out = new StringBuilder();
        for(Position position : shape){
            out.append(out.isEmpty() ? "" : " ").append(position);
        }
        return out.toString();
    }
}
