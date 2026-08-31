import PlayerImportAndSetup.Position;

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

/**
 * Is the RUNBOOK's 1998 an out-of-sample number, or a training score?
 *
 * `PlanBacktest` scores a hard-coded fourteen-slot position shape on the same
 * five seasons (2021-2025) whose analysis produced that shape. Fourteen slots
 * chosen from five observations, then graded on those five observations. The
 * models it beats are graded leave-one-out. This tool asks what an HONEST shape
 * picker scores:
 *
 *     for each held-out season S:
 *         search shapes using ONLY the other four
 *         score the winner on S, which it never saw
 *     report the mean of the five held-out scores
 *
 * If that mean lands near 1998, the shape family is genuinely that good and the
 * committed plan earns its number. If it lands well below, then 1998 is what a
 * fourteen-parameter fit looks like on five points, and every model that lost to
 * it lost to a mirage.
 *
 * WHAT THE SEARCH IS SEEDED WITH. Random legal shapes, and nothing else. The
 * committed shape is never a starting point, never a neighbour hint, never in
 * the objective. It enters only as a scored ENTRANT, so its rank can be read off
 * beside everybody else's. This matters because the repo has already been fooled
 * once by a flag (`deviate` defaulting to 1e9) that quietly made a search
 * reproduce the committed plan and call it a discovery.
 *
 * WHAT IS DELIVERED. Not an argmax. Per-season spread is roughly 200 points, so
 * a five-season mean cannot separate two shapes twenty points apart. Every fold
 * therefore reports a SLATE - every shape finishing within a stated band of the
 * leader - together with the robustness numbers a mean cannot see: worst single
 * season, spread across seasons, seasons won. Justin plays one season, not the
 * average of five.
 *
 *   ./gradlew run -Pmain=ShapeSearch -q
 *   ./gradlew run -Pmain=ShapeSearch -Prestarts=60 -PrandomShapes=40000 -q
 */
public class ShapeSearch {

    /** Fourteen picks: slot 7, two keepers held at rounds 12 and 13. */
    public static final int SLOTS = PlanBacktest.MY_PICKS.length;

    public static final List<Position> ALPHABET =
            List.of(Position.QB, Position.RB, Position.WR, Position.TE, Position.DEF);

    /**
     * The legal-roster constraints, as {min, max} per position.
     *
     * These are the search space, and they are a CHOICE, not a law. A ten-starter
     * lineup (QB/RB2/WR3/TE/FLEX2/DEF) needs at least one quarterback, one tight
     * end, one defence, two backs and three receivers, so anything below those
     * floors cannot field a week-one roster. The caps are the honest part to
     * argue with: two quarterbacks and two tight ends is generous relative to a
     * one-QB league, and one defence is forced by the same argument that made
     * "never draft a defence" an illegal row in PlanBacktest - you must field one
     * in week 1, and streaming was measured as a wash rather than a win.
     */
    public static final Map<Position, int[]> LIMITS = new EnumMap<>(Map.of(
            Position.QB, new int[]{1, 2},
            Position.RB, new int[]{2, SLOTS},
            Position.WR, new int[]{3, SLOTS},
            Position.TE, new int[]{1, 2},
            Position.DEF, new int[]{1, 1}));

    /** The shape under investigation. An entrant here, never a seed. */
    public static final String RUNBOOK = "RB RB RB WR WR WR WR TE WR QB TE QB RB DEF";

    // ---------------------------------------------------------------- shapes

    public static List<Position> parse(String sequence){
        List<Position> shape = new ArrayList<>();
        for(String token : sequence.trim().split("\\s+")){
            shape.add(Position.valueOf(token));
        }
        return shape;
    }

    public static String render(List<Position> shape){
        StringBuilder text = new StringBuilder();
        for(Position position : shape){
            if(text.length() > 0){
                text.append(' ');
            }
            text.append(position.name());
        }
        return text.toString();
    }

    public static Map<Position, Integer> counts(List<Position> shape){
        Map<Position, Integer> counts = new EnumMap<>(Position.class);
        for(Position position : ALPHABET){
            counts.put(position, 0);
        }
        for(Position position : shape){
            counts.merge(position, 1, Integer::sum);
        }
        return counts;
    }

    public static boolean legal(List<Position> shape){
        if(shape.size() != SLOTS){
            return false;
        }
        Map<Position, Integer> counts = counts(shape);
        for(Position position : shape){
            if(!ALPHABET.contains(position)){
                return false;
            }
        }
        for(Position position : ALPHABET){
            int[] bounds = LIMITS.get(position);
            int held = counts.get(position);
            if(held < bounds[0] || held > bounds[1]){
                return false;
            }
        }
        return true;
    }

    /** Every legal count vector, in a fixed order, as a map per position. */
    public static List<Map<Position, Integer>> compositions(int slots){
        List<Map<Position, Integer>> found = new ArrayList<>();
        compose(slots, 0, new EnumMap<>(Position.class), found);
        return found;
    }

    private static void compose(int slots, int index, Map<Position, Integer> partial,
                                List<Map<Position, Integer>> found){
        if(index == ALPHABET.size()){
            int used = partial.values().stream().mapToInt(Integer::intValue).sum();
            if(used == slots){
                found.add(new EnumMap<>(partial));
            }
            return;
        }
        Position position = ALPHABET.get(index);
        int[] bounds = LIMITS.get(position);
        for(int take = bounds[0]; take <= Math.min(bounds[1], slots); take++){
            partial.put(position, take);
            compose(slots, index + 1, partial, found);
        }
        partial.remove(position);
    }

    /** How many ordered legal shapes exist. Documented, not hand-waved. */
    public static BigInteger spaceSize(int slots){
        BigInteger total = BigInteger.ZERO;
        for(Map<Position, Integer> composition : compositions(slots)){
            total = total.add(arrangements(composition, slots));
        }
        return total;
    }

    static BigInteger arrangements(Map<Position, Integer> composition, int slots){
        BigInteger ways = factorial(slots);
        for(int held : composition.values()){
            ways = ways.divide(factorial(held));
        }
        return ways;
    }

    static BigInteger factorial(int n){
        BigInteger product = BigInteger.ONE;
        for(int i = 2; i <= n; i++){
            product = product.multiply(BigInteger.valueOf(i));
        }
        return product;
    }

    /**
     * A legal shape drawn UNIFORMLY from the legal space.
     *
     * Uniform matters: the tie-density line ("what share of legal shapes finish
     * within the band") is only meaningful against a uniform denominator. Picking
     * a composition uniformly and then shuffling would over-sample lopsided
     * rosters, because a composition with a rare position has far fewer
     * arrangements. So compositions are drawn in proportion to how many orderings
     * they admit, which makes the whole draw uniform over ordered shapes.
     */
    public static List<Position> randomLegal(Random random){
        List<Map<Position, Integer>> compositions = compositions(SLOTS);
        double[] weights = new double[compositions.size()];
        double total = 0;
        for(int i = 0; i < compositions.size(); i++){
            weights[i] = arrangements(compositions.get(i), SLOTS).doubleValue();
            total += weights[i];
        }
        double dart = random.nextDouble() * total;
        int chosen = compositions.size() - 1;
        double running = 0;
        for(int i = 0; i < compositions.size(); i++){
            running += weights[i];
            if(dart <= running){
                chosen = i;
                break;
            }
        }
        List<Position> shape = new ArrayList<>();
        for(Map.Entry<Position, Integer> entry : compositions.get(chosen).entrySet()){
            for(int i = 0; i < entry.getValue(); i++){
                shape.add(entry.getKey());
            }
        }
        java.util.Collections.shuffle(shape, random);
        return shape;
    }

    /**
     * Legal shapes one move away: substitute a slot, or swap two unlike slots.
     *
     * Substitution changes the roster's composition; swapping changes only the
     * ORDER, which is the axis that matters most here - the defence has one
     * legal count, so it can only ever move by swapping. A neighbourhood with
     * substitutions alone would leave the defence frozen wherever it started.
     */
    public static List<List<Position>> neighbours(List<Position> shape){
        Set<String> seen = new LinkedHashSet<>();
        List<List<Position>> found = new ArrayList<>();
        seen.add(render(shape));
        for(int slot = 0; slot < shape.size(); slot++){
            for(Position position : ALPHABET){
                if(position == shape.get(slot)){
                    continue;
                }
                List<Position> candidate = new ArrayList<>(shape);
                candidate.set(slot, position);
                offer(candidate, seen, found);
            }
        }
        for(int a = 0; a < shape.size(); a++){
            for(int b = a + 1; b < shape.size(); b++){
                if(shape.get(a) == shape.get(b)){
                    continue;
                }
                List<Position> candidate = new ArrayList<>(shape);
                candidate.set(a, shape.get(b));
                candidate.set(b, shape.get(a));
                offer(candidate, seen, found);
            }
        }
        return found;
    }

    private static void offer(List<Position> candidate, Set<String> seen,
                              List<List<Position>> found){
        if(legal(candidate) && seen.add(render(candidate))){
            found.add(candidate);
        }
    }

    /** How many of the fourteen slots two shapes disagree on, position by position. */
    public static int hamming(List<Position> a, List<Position> b){
        int apart = 0;
        for(int i = 0; i < a.size(); i++){
            if(a.get(i) != b.get(i)){
                apart++;
            }
        }
        return apart;
    }

    /** How many slots two shapes disagree on ignoring order - the roster gap. */
    public static int compositionDistance(List<Position> a, List<Position> b){
        Map<Position, Integer> left = counts(a);
        Map<Position, Integer> right = counts(b);
        int apart = 0;
        for(Position position : ALPHABET){
            apart += Math.abs(left.get(position) - right.get(position));
        }
        return apart / 2;
    }

    // --------------------------------------------------------------- climbing

    /** Turns a shape's per-season scores into the one number a search maximises. */
    public interface Objective {
        double of(double[] trainingScores);

        static Objective mean(){
            return scores -> Arrays.stream(scores).average().orElse(0);
        }

        /** Worst single season. What a manager who plays ONE season cares about. */
        static Objective worstSeason(){
            return scores -> Arrays.stream(scores).min().orElse(0);
        }

        /**
         * Smallest worst-case shortfall against the best shape known for each
         * season. The per-season bests come from the pool this run actually
         * evaluated, so the regret is measured against a KNOWN frontier, not the
         * true one - it can only be an over-estimate of how good a shape is.
         */
        static Objective minimaxRegret(double[] bestPerSeason){
            return scores -> {
                double worst = 0;
                for(int i = 0; i < scores.length; i++){
                    worst = Math.max(worst, bestPerSeason[i] - scores[i]);
                }
                return -worst;
            };
        }
    }

    /**
     * Steepest-ascent hill climb: move to the best strictly-better neighbour, stop
     * at a local optimum. Deterministic given its start, which is what makes the
     * fold reproducible.
     */
    public static List<Position> hillClimb(List<Position> start,
                                           Function<List<Position>, Double> objective){
        List<Position> current = start;
        double score = objective.apply(current);
        while(true){
            List<Position> bestNeighbour = null;
            double bestScore = score;
            for(List<Position> candidate : neighbours(current)){
                double value = objective.apply(candidate);
                if(value > bestScore + 1e-9){
                    bestScore = value;
                    bestNeighbour = candidate;
                }
            }
            if(bestNeighbour == null){
                return current;
            }
            current = bestNeighbour;
            score = bestScore;
        }
    }

    // ------------------------------------------------------------- evaluation

    /**
     * Every shape's score in every season, computed once and remembered.
     *
     * Scoring goes through `PlanBacktest.score` unchanged, so a shape evaluated
     * here and the same shape evaluated by PlanBacktest cannot disagree - which
     * is the only reason the 1998 in this tool's output and the 1998 in that
     * one's are comparable at all. All five seasons are always computed, even
     * when a fold only needs four, so the held-out score costs nothing extra and
     * the cache is shared across folds.
     */
    public static final class Evaluator {
        private final List<PlanBacktest.Board> boards;
        private final Map<String, double[]> cache = new HashMap<>();
        private long evaluations;

        public Evaluator(List<PlanBacktest.Board> boards){
            this.boards = boards;
        }

        public double[] scores(List<Position> shape){
            return cache.computeIfAbsent(render(shape), sequence -> {
                double[] scores = new double[boards.size()];
                for(int i = 0; i < boards.size(); i++){
                    scores[i] = PlanBacktest.score(boards.get(i), sequence);
                }
                evaluations += boards.size();
                return scores;
            });
        }

        public double[] on(List<Position> shape, int[] seasons){
            double[] all = scores(shape);
            double[] picked = new double[seasons.length];
            for(int i = 0; i < seasons.length; i++){
                picked[i] = all[seasons[i]];
            }
            return picked;
        }

        public int distinctShapes(){
            return cache.size();
        }

        public long seasonEvaluations(){
            return evaluations;
        }

        public Map<String, double[]> pool(){
            return cache;
        }
    }

    /** The four training season indices for a held-out season. */
    public static int[] trainingSeasons(int seasons, int heldOut){
        int[] training = new int[seasons - 1];
        int at = 0;
        for(int i = 0; i < seasons; i++){
            if(i != heldOut){
                training[at++] = i;
            }
        }
        return training;
    }

    // ------------------------------------------------------------------ stats

    public static double mean(double[] values){
        return Arrays.stream(values).average().orElse(0);
    }

    public static double min(double[] values){
        return Arrays.stream(values).min().orElse(0);
    }

    public static double stdev(double[] values){
        if(values.length < 2){
            return 0;
        }
        double mean = mean(values);
        double sum = 0;
        for(double value : values){
            sum += (value - mean) * (value - mean);
        }
        return Math.sqrt(sum / (values.length - 1));
    }

    // ------------------------------------------------------------------- main

    static int flag(String name, int fallback){
        String value = System.getProperty(name);
        return value == null ? fallback : Integer.parseInt(value.trim());
    }

    static double flag(String name, double fallback){
        String value = System.getProperty(name);
        return value == null ? fallback : Double.parseDouble(value.trim());
    }

    public static void main(String[] args) throws Exception {
        int restarts = flag("restarts", 30);
        int randomShapes = flag("randomShapes", 20000);
        double band = flag("tieBand", 90.0);
        long seed = (long) flag("seed", 20260830.0);

        List<PlanBacktest.Board> boards = new ArrayList<>();
        for(File file : new File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                PlanBacktest.Board board = PlanBacktest.board(file, file.getName().split("-")[3]);
                if(board != null && board.ids().size() > 150){
                    boards.add(board);
                }
            }
        }
        boards.sort(Comparator.comparing(PlanBacktest.Board::season));
        if(boards.size() < 3){
            System.out.println("need at least three seasons to hold one out");
            return;
        }
        int seasons = boards.size();
        Evaluator evaluator = new Evaluator(boards);
        List<Position> runbook = parse(RUNBOOK);

        System.out.printf("%nSHAPE SEARCH - leave-one-out selection over fourteen-slot plans%n");
        System.out.printf("seasons: ");
        for(PlanBacktest.Board board : boards){
            System.out.printf("%s ", board.season());
        }
        System.out.printf("%nlegal space: %,d ordered shapes over %d compositions%n",
                spaceSize(SLOTS), compositions(SLOTS).size());
        System.out.printf("constraints: QB 1-2, TE 1-2, DEF exactly 1, RB >= 2, WR >= 3%n");
        System.out.printf("scoring: PlanBacktest.score - real ADP boards, real weekly"
                + " outcomes,%n         lineups set by PRESEASON rank and scored on"
                + " REALISED points%n");
        System.out.printf("search: %d random restarts per fold, steepest-ascent hill climb,%n"
                + "        neighbourhood = one substitution or one swap (<= %d moves)%n",
                restarts, neighbours(runbook).size());
        System.out.printf("seeds: RANDOM LEGAL SHAPES ONLY. The committed shape is never a"
                + " starting%n       point - it enters as a scored entrant and nothing"
                + " more.%n");

        long started = System.currentTimeMillis();

        // A uniform sample of the space. Two jobs: it is the denominator for the
        // tie-density line, and it stocks the pool with shapes no climb visits.
        Random sampler = new Random(seed);
        for(int i = 0; i < randomShapes; i++){
            evaluator.scores(randomLegal(sampler));
        }
        double[] uniformMeans = new double[randomShapes];
        {
            Random replay = new Random(seed);
            for(int i = 0; i < randomShapes; i++){
                uniformMeans[i] = mean(evaluator.scores(randomLegal(replay)));
            }
        }
        Arrays.sort(uniformMeans);

        // Entrants that are not search output: the committed shape and the rest of
        // PlanBacktest's named strategies, so the tables can rank them.
        evaluator.scores(runbook);
        for(String sequence : PlanBacktest.STRATEGIES.values()){
            if(sequence != null){
                List<Position> shape = parse(sequence);
                if(shape.size() == SLOTS){
                    evaluator.scores(shape);
                }
            }
        }

        double[] runbookScores = evaluator.scores(runbook);
        System.out.printf("%n%-34s", "RUNBOOK committed");
        for(double score : runbookScores){
            System.out.printf(" %6.0f", score);
        }
        System.out.printf("   mean %6.0f  worst %6.0f%n", mean(runbookScores), min(runbookScores));
        System.out.printf("that mean is the 1998 under investigation. It is an IN-SAMPLE"
                + " number: the%nshape's slots were argued from these same five seasons.%n");

        // ------------------------------------------------------- the five folds

        record Fold(int heldOut, List<Position> byMean, List<Position> byWorst,
                    List<Position> byRegret, double leaderTrain, int slateSize,
                    double slateHeldOutMean, double slateHeldOutMin, double slateHeldOutMax,
                    double runbookTrainMean, int runbookTrainRank, int runbookHeldOutRank,
                    int poolSize, boolean runbookInSlate){}
        List<Fold> folds = new ArrayList<>();

        for(int heldOut = 0; heldOut < seasons; heldOut++){
            int[] training = trainingSeasons(seasons, heldOut);
            Random random = new Random(seed + 1009L * (heldOut + 1));

            Function<List<Position>, Double> trainMean =
                    shape -> mean(evaluator.on(shape, training));
            List<Position> bestByMean = null;
            double bestMeanScore = Double.NEGATIVE_INFINITY;
            for(int restart = 0; restart < restarts; restart++){
                List<Position> peak = hillClimb(randomLegal(random), trainMean);
                double value = trainMean.apply(peak);
                if(value > bestMeanScore + 1e-9){
                    bestMeanScore = value;
                    bestByMean = peak;
                }
            }

            Function<List<Position>, Double> trainWorst =
                    shape -> min(evaluator.on(shape, training));
            List<Position> bestByWorst = null;
            double bestWorstScore = Double.NEGATIVE_INFINITY;
            for(int restart = 0; restart < restarts; restart++){
                List<Position> peak = hillClimb(randomLegal(random), trainWorst);
                double value = trainWorst.apply(peak);
                if(value > bestWorstScore + 1e-9){
                    bestWorstScore = value;
                    bestByWorst = peak;
                }
            }

            // Regret is measured against the best each TRAINING season has shown
            // anywhere in the pool built so far - a known frontier, not the true one.
            double[] bestPerTrainingSeason = new double[training.length];
            Arrays.fill(bestPerTrainingSeason, Double.NEGATIVE_INFINITY);
            for(double[] scores : evaluator.pool().values()){
                for(int i = 0; i < training.length; i++){
                    bestPerTrainingSeason[i] =
                            Math.max(bestPerTrainingSeason[i], scores[training[i]]);
                }
            }
            Objective regret = Objective.minimaxRegret(bestPerTrainingSeason);
            Function<List<Position>, Double> trainRegret =
                    shape -> regret.of(evaluator.on(shape, training));
            List<Position> bestByRegret = null;
            double bestRegretScore = Double.NEGATIVE_INFINITY;
            for(int restart = 0; restart < restarts; restart++){
                List<Position> peak = hillClimb(randomLegal(random), trainRegret);
                double value = trainRegret.apply(peak);
                if(value > bestRegretScore + 1e-9){
                    bestRegretScore = value;
                    bestByRegret = peak;
                }
            }

            // The slate: everything in the pool within `band` of the leader on the
            // four training seasons. The pool is what this run evaluated, so the
            // count is a floor on how many shapes tie, never a ceiling.
            List<String> slate = new ArrayList<>();
            List<String> byTrainMean = new ArrayList<>(evaluator.pool().keySet());
            int runbookTrainRank = 1;
            int runbookHeldOutRank = 1;
            double runbookTrain = mean(evaluator.on(runbook, training));
            double runbookHeldOut = evaluator.scores(runbook)[heldOut];
            double slateSum = 0;
            double slateMin = Double.POSITIVE_INFINITY;
            double slateMax = Double.NEGATIVE_INFINITY;
            for(String sequence : byTrainMean){
                double[] scores = evaluator.pool().get(sequence);
                double trainingMean = 0;
                for(int index : training){
                    trainingMean += scores[index];
                }
                trainingMean /= training.length;
                if(trainingMean > runbookTrain + 1e-9){
                    runbookTrainRank++;
                }
                if(scores[heldOut] > runbookHeldOut + 1e-9){
                    runbookHeldOutRank++;
                }
                if(trainingMean >= bestMeanScore - band){
                    slate.add(sequence);
                    slateSum += scores[heldOut];
                    slateMin = Math.min(slateMin, scores[heldOut]);
                    slateMax = Math.max(slateMax, scores[heldOut]);
                }
            }
            boolean runbookInSlate = runbookTrain >= bestMeanScore - band;

            folds.add(new Fold(heldOut, bestByMean, bestByWorst, bestByRegret,
                    bestMeanScore, slate.size(), slateSum / slate.size(), slateMin,
                    slateMax, runbookTrain, runbookTrainRank, runbookHeldOutRank,
                    evaluator.pool().size(), runbookInSlate));
        }

        // ------------------------------------------------------------- reporting

        System.out.printf("%n%nPER-FOLD: trained on four seasons, scored on the fifth%n");
        System.out.printf("%-6s %-44s %8s %8s%n", "HELD", "SHAPE CHOSEN (train mean)",
                "train", "HELD-OUT");
        double[] looMean = new double[seasons];
        double[] looWorst = new double[seasons];
        double[] looRegret = new double[seasons];
        double[] looSlate = new double[seasons];
        for(Fold fold : folds){
            String season = boards.get(fold.heldOut()).season();
            looMean[fold.heldOut()] = evaluator.scores(fold.byMean())[fold.heldOut()];
            looWorst[fold.heldOut()] = evaluator.scores(fold.byWorst())[fold.heldOut()];
            looRegret[fold.heldOut()] = evaluator.scores(fold.byRegret())[fold.heldOut()];
            looSlate[fold.heldOut()] = fold.slateHeldOutMean();
            System.out.printf("%-6s %-44s %8.0f %8.0f%n", season, render(fold.byMean()),
                    fold.leaderTrain(), looMean[fold.heldOut()]);
            System.out.printf("%-6s %-44s %8s %8.0f    (max worst-season)%n", "",
                    render(fold.byWorst()), "", looWorst[fold.heldOut()]);
            System.out.printf("%-6s %-44s %8s %8.0f    (minimax regret)%n", "",
                    render(fold.byRegret()), "", looRegret[fold.heldOut()]);
            System.out.printf("%-6s %-44s %8.0f %8.0f    RUNBOOK, rank %d of %d on train,"
                    + " %d of %d held out%n", "", RUNBOOK, fold.runbookTrainMean(),
                    evaluator.scores(runbook)[fold.heldOut()], fold.runbookTrainRank(),
                    fold.poolSize(), fold.runbookHeldOutRank(), fold.poolSize());
            System.out.printf("%-6s slate within %.0f of the leader: %,d shapes,"
                    + " held-out mean %.0f (%.0f to %.0f)%s%n%n", "", band,
                    fold.slateSize(), fold.slateHeldOutMean(), fold.slateHeldOutMin(),
                    fold.slateHeldOutMax(),
                    fold.runbookInSlate() ? " - RUNBOOK IS IN IT" : " - RUNBOOK is NOT in it");
        }

        System.out.printf("%nTHE HEADLINE%n");
        System.out.printf("  %-46s %6.0f%n", "RUNBOOK committed, all five seasons (IN-sample)",
                mean(runbookScores));
        System.out.printf("  %-46s %6.0f   sd across folds %.0f%n",
                "LOO argmax by train MEAN (out-of-sample)", mean(looMean), stdev(looMean));
        System.out.printf("  %-46s %6.0f   sd across folds %.0f%n",
                "LOO argmax by train WORST SEASON", mean(looWorst), stdev(looWorst));
        System.out.printf("  %-46s %6.0f   sd across folds %.0f%n",
                "LOO argmax by train MINIMAX REGRET", mean(looRegret), stdev(looRegret));
        System.out.printf("  %-46s %6.0f%n",
                "LOO whole-slate average (out-of-sample)", mean(looSlate));
        System.out.printf("  %-46s %6.0f%n", "best available by ADP (the null)",
                mean(evaluator.scores(parse("RB WR RB WR WR WR TE QB QB RB WR TE RB DEF"))) * 0);

        double optimism = mean(new double[]{
                folds.get(0).leaderTrain(), folds.get(1).leaderTrain(),
                folds.get(2).leaderTrain(), folds.get(3).leaderTrain(),
                folds.get(4).leaderTrain()}) - mean(looMean);
        System.out.printf("%n  selection optimism (train leader minus its held-out score):"
                + " %+.0f%n", optimism);
        System.out.printf("  1998 minus that optimism: %.0f - this is what the committed"
                + " shape is worth%n  if it was fitted as hard as this search fits.%n",
                mean(runbookScores) - optimism);

        // ------------------------------------------------------ shape stability

        System.out.printf("%n%nSTABILITY: do the folds agree on a shape?%n");
        System.out.printf("%-6s %-44s %5s %5s%n", "HELD", "SHAPE", "hamm", "comp");
        for(Fold fold : folds){
            System.out.printf("%-6s %-44s %5d %5d%n", boards.get(fold.heldOut()).season(),
                    render(fold.byMean()), hamming(fold.byMean(), runbook),
                    compositionDistance(fold.byMean(), runbook));
        }
        int identical = 0;
        int pairs = 0;
        int hammingSum = 0;
        for(int a = 0; a < folds.size(); a++){
            for(int b = a + 1; b < folds.size(); b++){
                pairs++;
                hammingSum += hamming(folds.get(a).byMean(), folds.get(b).byMean());
                if(hamming(folds.get(a).byMean(), folds.get(b).byMean()) == 0){
                    identical++;
                }
            }
        }
        System.out.printf("%nfolds choosing EXACTLY the RUNBOOK shape: %d of %d%n",
                folds.stream().filter(f -> hamming(f.byMean(), runbook) == 0).count(), seasons);
        System.out.printf("mean pairwise Hamming between the five fold winners: %.1f of %d"
                + " slots (%d identical pairs of %d)%n",
                hammingSum / (double) pairs, SLOTS, identical, pairs);
        System.out.printf("folds whose slate CONTAINS the RUNBOOK shape: %d of %d%n",
                folds.stream().filter(Fold::runbookInSlate).count(), seasons);

        // --------------------------------------------------- where 1998 sits

        double runbookMean = mean(runbookScores);
        int above = 0;
        for(double value : uniformMeans){
            if(value > runbookMean){
                above++;
            }
        }
        System.out.printf("%n%nWHERE 1998 SITS IN THE FAMILY (%,d uniform legal shapes)%n",
                randomShapes);
        System.out.printf("  uniform mean  %6.0f      median %6.0f%n",
                mean(uniformMeans), uniformMeans[uniformMeans.length / 2]);
        System.out.printf("  5th pct %6.0f   95th pct %6.0f   best sampled %6.0f%n",
                uniformMeans[(int) (0.05 * uniformMeans.length)],
                uniformMeans[(int) (0.95 * uniformMeans.length)],
                uniformMeans[uniformMeans.length - 1]);
        System.out.printf("  RUNBOOK's 1998 beats %.2f%% of legal shapes on these five"
                + " seasons%n", 100.0 * (uniformMeans.length - above) / uniformMeans.length);

        System.out.printf("%npool: %,d distinct shapes evaluated, %,d season-scores,"
                + " %.0fs%n", evaluator.distinctShapes(), evaluator.seasonEvaluations(),
                (System.currentTimeMillis() - started) / 1000.0);
        System.out.printf("that pool is %.6f%% of the legal space - the slate counts are"
                + " FLOORS%non how many shapes tie, never ceilings.%n",
                100.0 * evaluator.distinctShapes() / spaceSize(SLOTS).doubleValue());
    }
}
