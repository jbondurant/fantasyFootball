import PlayerImportAndSetup.Position;

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
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
 * five seasons (2021-2025) whose analysis produced that shape - a measured tight
 * end crossover, a monotone defence-placement curve, an RB-heavy front. Fourteen
 * slots argued from five observations, then graded on those five observations.
 * The models it beats are graded leave-one-out. This tool asks what an HONEST
 * shape picker scores:
 *
 *     for each held-out season S:
 *         search shapes using ONLY the other four
 *         score the winner on S, which it never saw
 *     report the mean of the five held-out scores
 *
 * If that mean lands near 1998, the shape family is genuinely that good and the
 * committed plan earns its number. If it lands well below, 1998 is what a
 * fourteen-parameter fit looks like on five points, and every model that lost to
 * it lost by less than the table said.
 *
 * WHAT THE SEARCH IS SEEDED WITH. Random legal shapes, and nothing else. The
 * committed shape is never a starting point, never a neighbour hint, never in an
 * objective. It enters only as a scored ENTRANT, so its rank sits beside
 * everybody else's. This matters because the repo has already been fooled once
 * by a flag - `deviate` defaulting to 1e9 - that quietly made a search reproduce
 * the committed plan and look like a discovery.
 *
 * WHAT IS DELIVERED: A SLATE, NOT AN ARGMAX. Per-season spread is roughly 200
 * points, so a five-season mean cannot separate two shapes twenty points apart.
 * Every fold reports every shape finishing within a stated band of the leader,
 * and the count matters as much as the identity - if thousands tie, the argmax
 * is a coin flip and the committed plan is simply one of the tied. Alongside the
 * mean, each candidate carries what a mean cannot see: worst single season,
 * spread across seasons, seasons won.
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
     * The legal-roster constraints, as {min, max} per position. This IS the
     * search space, and it is a choice rather than a law, so it is stated where
     * it can be argued with.
     *
     * The floors are forced: a ten-starter lineup (QB/RB2/WR3/TE/FLEX2/DEF) needs
     * at least one quarterback, one tight end, one defence, two backs and three
     * receivers, and anything below that cannot field a week-one roster. The caps
     * are the arguable half - two quarterbacks and two tight ends is generous for
     * a one-QB league, and the single defence follows the same reasoning that made
     * "never draft a defence" an illegal row in PlanBacktest: you must field one
     * in week 1, and streaming measured as a wash rather than a win.
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
        for(Position position : shape){
            if(!ALPHABET.contains(position)){
                return false;
            }
        }
        Map<Position, Integer> counts = counts(shape);
        for(Position position : ALPHABET){
            int[] bounds = LIMITS.get(position);
            int held = counts.get(position);
            if(held < bounds[0] || held > bounds[1]){
                return false;
            }
        }
        return true;
    }

    /** Every legal count vector, in a fixed order. */
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

    private static List<Map<Position, Integer>> compositionCache;
    private static double[] compositionWeights;

    private static synchronized void primeCompositions(){
        if(compositionCache == null){
            compositionCache = compositions(SLOTS);
            compositionWeights = new double[compositionCache.size()];
            for(int i = 0; i < compositionCache.size(); i++){
                compositionWeights[i] = arrangements(compositionCache.get(i), SLOTS).doubleValue();
            }
        }
    }

    /**
     * A legal shape drawn UNIFORMLY from the legal space.
     *
     * Uniform matters because the tie-density line - what share of legal shapes
     * finish within the band - is only meaningful against a uniform denominator.
     * Drawing a composition uniformly and then shuffling would over-sample
     * lopsided rosters, since a composition holding a rare position admits far
     * fewer orderings. Compositions are therefore drawn in proportion to their
     * arrangement count, which makes the whole draw uniform over ordered shapes.
     */
    public static List<Position> randomLegal(Random random){
        primeCompositions();
        double total = 0;
        for(double weight : compositionWeights){
            total += weight;
        }
        double dart = random.nextDouble() * total;
        int chosen = compositionCache.size() - 1;
        double running = 0;
        for(int i = 0; i < compositionCache.size(); i++){
            running += compositionWeights[i];
            if(dart <= running){
                chosen = i;
                break;
            }
        }
        List<Position> shape = new ArrayList<>();
        for(Map.Entry<Position, Integer> entry : compositionCache.get(chosen).entrySet()){
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
     * ORDER, which is the axis that matters most here - the defence has exactly
     * one legal count, so it can move only by swapping. A neighbourhood built
     * from substitutions alone would freeze the defence wherever it started,
     * which is precisely the parameter the placement curve is about.
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

    /**
     * The same roster with the defence moved to the final pick.
     *
     * This is the one perturbation whose sign is close to known in advance, and
     * the argument uses no outcome data at all.
     *
     * The other eleven teams in PlanBacktest take `bestAvailableSkill`, so no
     * opponent ever removes a defence from the board: the same defence sits there
     * at pick 186 as at pick 42, and taking one early buys nothing. Meanwhile
     * every skill pick after the defence slot moves one pick EARLIER, and the
     * count of skill players consumed before it drops - by twelve, at a defence
     * moved out of round six. Each remaining position is therefore chosen from a
     * strictly less-picked-over board.
     *
     * That is not a theorem about individual players, since my own choices shift
     * what the opponents take. It is close enough that a search preferring the
     * defence EARLIER is preferring a worse board, and only what five particular
     * seasons happened to do can justify it.
     *
     * NOT reachable by the search's own moves. Moving a defence to the end is a
     * ROTATION - the ten positions behind it each slide up one - while the
     * neighbourhood offers substitutions and swaps. A swap of slot four and slot
     * fourteen would drag a round-fourteen position up to pick 42, which is a
     * different and much worse plan. So this probe asks something the hill climb
     * was never in a position to reject.
     */
    public static List<Position> defenceLast(List<Position> shape){
        List<Position> moved = new ArrayList<>();
        for(Position position : shape){
            if(position != Position.DEF){
                moved.add(position);
            }
        }
        moved.add(Position.DEF);
        return moved;
    }

    /** How many of the fourteen slots two shapes disagree on, slot by slot. */
    public static int hamming(List<Position> a, List<Position> b){
        int apart = 0;
        for(int i = 0; i < a.size(); i++){
            if(a.get(i) != b.get(i)){
                apart++;
            }
        }
        return apart;
    }

    /** How many slots they disagree on ignoring order - the roster gap. */
    public static int compositionDistance(List<Position> a, List<Position> b){
        Map<Position, Integer> left = counts(a);
        Map<Position, Integer> right = counts(b);
        int apart = 0;
        for(Position position : ALPHABET){
            apart += Math.abs(left.get(position) - right.get(position));
        }
        return apart / 2;
    }

    // ------------------------------------------------------ structural traits

    /**
     * A statement about a plan that is true or false of it, in draft language.
     *
     * The argmax of a fourteen-slot search is worthless at this sample size -
     * PowerBacktest puts the bar for a real gap at 125 points, and the tie set
     * runs to thousands of shapes. What survives is not WHICH shape but what the
     * tied shapes have in COMMON, and that only becomes a usable answer if it is
     * phrased as something Justin can act on with a pick clock running.
     *
     * Each trait is scored two ways: how often it holds among the tied plans, and
     * how often it holds among shapes drawn at random. A trait that holds in
     * nearly every tied plan and in half the random ones is a finding. One that
     * holds equally in both is the search telling you it has no opinion.
     */
    public record Trait(String name, java.util.function.Predicate<List<Position>> holds){}

    static boolean firstIsBy(List<Position> shape, Position position, int round){
        int at = shape.indexOf(position);
        return at >= 0 && at < round;
    }

    static int held(List<Position> shape, Position position){
        int count = 0;
        for(Position slot : shape){
            if(slot == position){
                count++;
            }
        }
        return count;
    }

    static int amongFirst(List<Position> shape, Position position, int rounds){
        int count = 0;
        for(int i = 0; i < Math.min(rounds, shape.size()); i++){
            if(shape.get(i) == position){
                count++;
            }
        }
        return count;
    }

    public static final List<Trait> TRAITS = List.of(
            new Trait("opens with a back", s -> s.get(0) == Position.RB),
            new Trait("two backs in the first three", s -> amongFirst(s, Position.RB, 3) >= 2),
            new Trait("three backs in the first five", s -> amongFirst(s, Position.RB, 5) >= 3),
            new Trait("no receiver until round 3", s -> amongFirst(s, Position.WR, 2) == 0),
            new Trait("first QB by round 3", s -> firstIsBy(s, Position.QB, 3)),
            new Trait("first QB by round 5", s -> firstIsBy(s, Position.QB, 5)),
            new Trait("first QB after round 8", s -> !firstIsBy(s, Position.QB, 8)),
            new Trait("carries two QBs", s -> held(s, Position.QB) == 2),
            new Trait("first TE by round 8", s -> firstIsBy(s, Position.TE, 8)),
            new Trait("carries two TEs", s -> held(s, Position.TE) == 2),
            new Trait("defence in the last three rounds",
                    s -> s.indexOf(Position.DEF) >= s.size() - 3),
            new Trait("defence in the first seven",
                    s -> s.indexOf(Position.DEF) < 7),
            new Trait("four or more backs", s -> held(s, Position.RB) >= 4),
            new Trait("five or more receivers", s -> held(s, Position.WR) >= 5));

    /** What share of a set of shapes the trait holds for. */
    public static double shareHolding(java.util.Collection<String> shapes, Trait trait){
        if(shapes.isEmpty()){
            return Double.NaN;
        }
        int holds = 0;
        for(String sequence : shapes){
            if(trait.holds().test(parse(sequence))){
                holds++;
            }
        }
        return holds / (double) shapes.size();
    }

    // --------------------------------------------------------------- climbing

    /**
     * Steepest-ascent hill climb: move to the best strictly-better neighbour,
     * stop at a local optimum. Deterministic given its start, which is what makes
     * a fold reproducible.
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

    /** Best local optimum over `restarts` random starts. */
    public static List<Position> searchFromRandomStarts(int restarts, Random random,
                                                        Function<List<Position>, Double> objective){
        List<Position> best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for(int restart = 0; restart < restarts; restart++){
            List<Position> peak = hillClimb(randomLegal(random), objective);
            double value = objective.apply(peak);
            if(value > bestScore + 1e-9){
                bestScore = value;
                best = peak;
            }
        }
        return best;
    }

    // ------------------------------------------------------------- evaluation

    /**
     * Every shape's score in every season, computed once and remembered.
     *
     * Scoring goes through `PlanBacktest.score` unchanged, so a shape scored here
     * and the same shape scored by PlanBacktest cannot disagree - the only reason
     * the 1998 in this tool's output and the 1998 in that one's are comparable at
     * all. All five seasons are always computed, even where a fold needs four, so
     * the held-out score costs nothing extra and one cache serves every fold.
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

        public double meanOn(List<Position> shape, int[] seasons){
            return meanOn(scores(shape), seasons);
        }

        public double minOn(List<Position> shape, int[] seasons){
            return minOn(scores(shape), seasons);
        }

        public static double meanOn(double[] all, int[] seasons){
            double sum = 0;
            for(int index : seasons){
                sum += all[index];
            }
            return sum / seasons.length;
        }

        public static double minOn(double[] all, int[] seasons){
            double worst = Double.POSITIVE_INFINITY;
            for(int index : seasons){
                worst = Math.min(worst, all[index]);
            }
            return worst;
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

    /** The training season indices for a held-out season. */
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

    /**
     * Worst-case shortfall against the best score each season has shown.
     *
     * The per-season bests come from the pool this run actually evaluated - a
     * KNOWN frontier, not the true one - so a regret figure here is a lower bound
     * on true regret. Negated, because the searches all maximise.
     */
    public static double negatedMaxRegret(double[] all, int[] seasons, double[] bestPerSeason){
        double worst = 0;
        for(int i = 0; i < seasons.length; i++){
            worst = Math.max(worst, bestPerSeason[seasons[i]] - all[seasons[i]]);
        }
        return -worst;
    }

    // ------------------------------------------------------------------ stats

    /** The slot a position is first taken at, zero-based. -1 if never taken. */
    public static int firstSlot(List<Position> shape, Position position){
        return shape.indexOf(position);
    }

    /** {p10, median, p90} of where a position is first taken across a set of shapes. */
    public static double[] firstSlotSpread(java.util.Collection<String> shapes, Position position){
        double[] slots = new double[shapes.size()];
        int at = 0;
        for(String sequence : shapes){
            slots[at++] = firstSlot(parse(sequence), position);
        }
        Arrays.sort(slots);
        return new double[]{percentile(slots, 0.10), percentile(slots, 0.50),
                percentile(slots, 0.90)};
    }

    public static double percentile(double[] sorted, double quantile){
        if(sorted.length == 0){
            return Double.NaN;
        }
        int index = (int) Math.round(quantile * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    public static double mean(double[] values){
        return Arrays.stream(values).average().orElse(0);
    }

    /**
     * Per-season differences between two strategies.
     *
     * Both draft the same five boards, so the seasons are PAIRED and most of the
     * variance they share cancels. Comparing two five-season means against each
     * other's spread throws that away and makes everything look indistinguishable;
     * the paired difference is the sharper test and the honest one.
     */
    public static double[] differences(double[] left, double[] right){
        double[] gaps = new double[left.length];
        for(int i = 0; i < left.length; i++){
            gaps[i] = left[i] - right[i];
        }
        return gaps;
    }

    /** Standard error of a mean. The width of the claim, printed beside it. */
    public static double standardError(double[] values){
        return values.length < 2 ? Double.NaN : stdev(values) / Math.sqrt(values.length);
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

    record Fold(int heldOut, List<Position> byMean, List<Position> byWorst,
                List<Position> byRegret, double leaderTrain, List<String> slate,
                double runbookTrainMean, int runbookTrainRank, int runbookHeldOutRank,
                boolean runbookInSlate){}

    public static void main(String[] args) throws Exception {
        int restarts = flag("restarts", 30);
        int randomShapes = flag("randomShapes", 20000);
        // 125 is measured, not guessed. PowerBacktest's clustered standard error
        // puts bare significance at 94 points and 80% power at 125, so anything
        // inside 125 of the leader has not been distinguished from it by this
        // evidence. An earlier version of this tool used 90, which was a
        // back-of-envelope figure and too tight.
        double band = flag("tieBand", 125.0);
        long seed = (long) flag("seed", 20260830.0);
        int slateRows = flag("slateRows", 15);

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
        long started = System.currentTimeMillis();

        System.out.printf("%nSHAPE SEARCH - leave-one-out selection over fourteen-slot plans%n");
        System.out.printf("seasons  ");
        for(PlanBacktest.Board board : boards){
            System.out.printf("%s ", board.season());
        }
        System.out.printf("%nspace    %,d ordered legal shapes over %d compositions%n",
                spaceSize(SLOTS), compositions(SLOTS).size());
        System.out.printf("legal    QB 1-2, TE 1-2, DEF exactly 1, RB >= 2, WR >= 3%n");
        System.out.printf("scoring  PlanBacktest.score, unchanged - real ADP boards, real"
                + " weekly outcomes,%n         lineups set by PRESEASON rank and scored on"
                + " REALISED points%n");
        System.out.printf("search   %d random restarts per fold per rule, steepest-ascent"
                + " hill climb,%n         neighbourhood = one substitution or one swap"
                + " (%d moves from a given shape)%n", restarts, neighbours(runbook).size());
        System.out.printf("seeds    RANDOM LEGAL SHAPES ONLY. The committed shape is never a"
                + " start,%n         never a hint, never in an objective. It is a scored"
                + " entrant.%n");

        // A census of what is actually ON each board, printed before any result.
        // A slot can only draft a position the board carries; if it carries none,
        // `bestAvailable` falls back to best-available-ANYWHERE and the slot
        // silently becomes something else. Any conclusion about a position's
        // timing is worthless without this line, and it costs nothing to print.
        System.out.printf("%nBOARD CENSUS - what each season's board actually carries%n");
        System.out.printf("  %-8s %7s", "SEASON", "players");
        for(Position position : ALPHABET){
            System.out.printf(" %6s", position.name());
        }
        System.out.printf("%n");
        for(PlanBacktest.Board board : boards){
            System.out.printf("  %-8s %7d", board.season(), board.ids().size());
            for(Position position : ALPHABET){
                long carried = board.ids().stream()
                        .filter(id -> board.positionOf().get(id) == position).count();
                System.out.printf(" %6d", carried);
            }
            System.out.printf("%n");
        }
        System.out.printf("  A zero in any column means that slot cannot draft what it says"
                + " it drafts.%n");

        // -Pshape="RB RB ..." prints WHO a shape drafts, beside its DEF-last twin,
        // and stops. Reasoning about the defence slot from position labels alone
        // produced a confident and wrong prediction; this exists so the next
        // person looks at the fourteen men instead of arguing about the fourteen
        // letters.
        String inspect = System.getProperty("shape");
        if(inspect != null && !inspect.isBlank()){
            List<Position> asked = parse(inspect);
            System.out.printf("%nROSTER INSPECTION%n  %s   legal=%s%n", render(asked),
                    legal(asked));
            for(PlanBacktest.Board season : boards){
                for(List<Position> variant : List.of(asked, defenceLast(asked))){
                    List<String> men = PlanBacktest.draft(season, render(variant));
                    System.out.printf("%n  %s  %s%n    %8.0f  ", season.season(),
                            render(variant), PlanBacktest.score(season, render(variant)));
                    for(int i = 0; i < men.size(); i++){
                        Player man = Player.getPlayerFromSIDV2(men.get(i));
                        System.out.printf("%s%d:%s(%s) ", i > 0 ? "| " : "",
                                PlanBacktest.MY_PICKS[i],
                                man == null ? men.get(i) : man.lastName,
                                season.positionOf().get(men.get(i)));
                    }
                    System.out.printf("%n");
                }
            }
            return;
        }

        // ------------------------------------------------- phase 1: uniform draw
        Random sampler = new Random(seed);
        List<List<Position>> uniform = new ArrayList<>();
        for(int i = 0; i < randomShapes; i++){
            List<Position> shape = randomLegal(sampler);
            uniform.add(shape);
            evaluator.scores(shape);
        }

        // entrants that are not search output, so the tables can rank them
        evaluator.scores(runbook);
        for(String sequence : PlanBacktest.STRATEGIES.values()){
            if(sequence != null){
                List<Position> shape = parse(sequence);
                if(shape.size() == SLOTS && legal(shape)){
                    evaluator.scores(shape);
                }
            }
        }
        double[] runbookScores = evaluator.scores(runbook);
        double[] adpNull = new double[seasons];
        for(int i = 0; i < seasons; i++){
            adpNull[i] = PlanBacktest.score(boards.get(i), null);
        }

        System.out.printf("%n%-34s", "RUNBOOK committed");
        for(double score : runbookScores){
            System.out.printf(" %6.0f", score);
        }
        System.out.printf("  mean %6.0f  worst %6.0f%n", mean(runbookScores), min(runbookScores));
        System.out.printf("%-34s", "best available by ADP (the null)");
        for(double score : adpNull){
            System.out.printf(" %6.0f", score);
        }
        System.out.printf("  mean %6.0f  worst %6.0f%n", mean(adpNull), min(adpNull));
        System.out.printf("%nThat 1998 is the number under investigation, and it is IN-SAMPLE:"
                + " the shape's%nslots were argued from these same five seasons.%n");

        // ------------------------------------------------ phase 2: mean and worst
        List<List<Position>> byMean = new ArrayList<>();
        List<List<Position>> byWorst = new ArrayList<>();
        for(int heldOut = 0; heldOut < seasons; heldOut++){
            int[] training = trainingSeasons(seasons, heldOut);
            byMean.add(searchFromRandomStarts(restarts, new Random(seed + 1009L * (heldOut + 1)),
                    shape -> evaluator.meanOn(shape, training)));
            byWorst.add(searchFromRandomStarts(restarts, new Random(seed + 7919L * (heldOut + 1)),
                    shape -> evaluator.minOn(shape, training)));
        }

        // ---------------------------------------------------- phase 3: regret
        // The frontier is read off the pool AFTER the mean and worst searches, so
        // every fold's regret is measured against the same known frontier.
        double[] bestPerSeason = new double[seasons];
        Arrays.fill(bestPerSeason, Double.NEGATIVE_INFINITY);
        for(double[] scores : evaluator.pool().values()){
            for(int i = 0; i < seasons; i++){
                bestPerSeason[i] = Math.max(bestPerSeason[i], scores[i]);
            }
        }
        List<List<Position>> byRegret = new ArrayList<>();
        for(int heldOut = 0; heldOut < seasons; heldOut++){
            int[] training = trainingSeasons(seasons, heldOut);
            byRegret.add(searchFromRandomStarts(restarts, new Random(seed + 3181L * (heldOut + 1)),
                    shape -> negatedMaxRegret(evaluator.scores(shape), training, bestPerSeason)));
        }

        // ------------------------------- phase 4: slates against the FINAL pool
        List<Fold> folds = new ArrayList<>();
        List<Set<String>> slateSets = new ArrayList<>();
        for(int heldOut = 0; heldOut < seasons; heldOut++){
            int[] training = trainingSeasons(seasons, heldOut);
            double leaderTrain = evaluator.meanOn(byMean.get(heldOut), training);
            double runbookTrain = evaluator.meanOn(runbook, training);
            double runbookHeldOut = runbookScores[heldOut];
            List<String> slate = new ArrayList<>();
            int trainRank = 1;
            int heldOutRank = 1;
            for(Map.Entry<String, double[]> entry : evaluator.pool().entrySet()){
                double[] scores = entry.getValue();
                double trainingMean = Evaluator.meanOn(scores, training);
                if(trainingMean > runbookTrain + 1e-9){
                    trainRank++;
                }
                if(scores[heldOut] > runbookHeldOut + 1e-9){
                    heldOutRank++;
                }
                if(trainingMean >= leaderTrain - band){
                    slate.add(entry.getKey());
                }
            }
            slateSets.add(new HashSet<>(slate));
            folds.add(new Fold(heldOut, byMean.get(heldOut), byWorst.get(heldOut),
                    byRegret.get(heldOut), leaderTrain, slate, runbookTrain, trainRank,
                    heldOutRank, runbookTrain >= leaderTrain - band));
        }

        // ------------------------------------------------------------- reporting
        int pool = evaluator.distinctShapes();
        double[] looMean = new double[seasons];
        double[] looWorst = new double[seasons];
        double[] looRegret = new double[seasons];
        double[] looSlate = new double[seasons];
        double[] trainLeaders = new double[seasons];

        System.out.printf("%n%nPER-FOLD - trained on four seasons, scored on the fifth%n%n");
        for(Fold fold : folds){
            int held = fold.heldOut();
            String season = boards.get(held).season();
            looMean[held] = evaluator.scores(fold.byMean())[held];
            looWorst[held] = evaluator.scores(fold.byWorst())[held];
            looRegret[held] = evaluator.scores(fold.byRegret())[held];
            trainLeaders[held] = fold.leaderTrain();
            double slateSum = 0;
            double slateMin = Double.POSITIVE_INFINITY;
            double slateMax = Double.NEGATIVE_INFINITY;
            for(String sequence : fold.slate()){
                double value = evaluator.pool().get(sequence)[held];
                slateSum += value;
                slateMin = Math.min(slateMin, value);
                slateMax = Math.max(slateMax, value);
            }
            looSlate[held] = slateSum / fold.slate().size();

            System.out.printf("HOLD OUT %s%n", season);
            System.out.printf("  %-44s %8s %8s%n", "rule / shape chosen from the other four",
                    "train", "HELD-OUT");
            System.out.printf("  %-44s %8.0f %8.0f%n", render(fold.byMean()),
                    fold.leaderTrain(), looMean[held]);
            System.out.printf("  %-44s %8.0f %8.0f   max worst-season%n", render(fold.byWorst()),
                    evaluator.meanOn(fold.byWorst(), trainingSeasons(seasons, held)),
                    looWorst[held]);
            System.out.printf("  %-44s %8.0f %8.0f   minimax regret%n", render(fold.byRegret()),
                    evaluator.meanOn(fold.byRegret(), trainingSeasons(seasons, held)),
                    looRegret[held]);
            System.out.printf("  %-44s %8.0f %8.0f   RUNBOOK: rank %,d of %,d on train,"
                    + " %,d of %,d held out%n", RUNBOOK, fold.runbookTrainMean(),
                    runbookScores[held], fold.runbookTrainRank(), pool,
                    fold.runbookHeldOutRank(), pool);
            System.out.printf("  slate within %.0f of the leader: %,d of %,d shapes evaluated"
                    + " (%.1f%%),%n  their held-out mean %.0f, range %.0f to %.0f.%s%n%n",
                    band, fold.slate().size(), pool, 100.0 * fold.slate().size() / pool,
                    looSlate[held], slateMin, slateMax,
                    fold.runbookInSlate() ? "  RUNBOOK IS IN THE SLATE."
                            : "  RUNBOOK is NOT in the slate.");
        }

        System.out.printf("THE HEADLINE%n");
        System.out.printf("  %-48s %6.0f%n", "RUNBOOK committed, five seasons (IN-SAMPLE)",
                mean(runbookScores));
        System.out.printf("  %-48s %6.0f   fold sd %4.0f%n",
                "LOO pick by train MEAN (out-of-sample)", mean(looMean), stdev(looMean));
        System.out.printf("  %-48s %6.0f   fold sd %4.0f%n",
                "LOO pick by train WORST SEASON", mean(looWorst), stdev(looWorst));
        System.out.printf("  %-48s %6.0f   fold sd %4.0f%n",
                "LOO pick by train MINIMAX REGRET", mean(looRegret), stdev(looRegret));
        System.out.printf("  %-48s %6.0f%n", "LOO whole-slate average", mean(looSlate));
        System.out.printf("  %-48s %6.0f%n", "best available by ADP (the null)", mean(adpNull));
        System.out.printf("  %-48s %6.0f%n", "worst season of the LOO-by-mean picks",
                min(looMean));
        System.out.printf("  %-48s %6.0f%n", "worst season of the LOO-by-worst picks",
                min(looWorst));
        System.out.printf("  %-48s %6.0f%n", "worst season of the RUNBOOK", min(runbookScores));

        double optimism = mean(trainLeaders) - mean(looMean);
        System.out.printf("%n  selection optimism, train leader minus its held-out score:"
                + " %+.0f%n", optimism);
        System.out.printf("  a fourteen-slot shape fitted this hard on four seasons loses"
                + " that much%n  when it meets a fifth. 1998 discounted by it: %.0f.%n",
                mean(runbookScores) - optimism);
        System.out.printf("  standard error of the LOO mean over five folds: %.0f."
                + " Per-season spread is%n  about %.0f, which is the resolution this whole"
                + " experiment has.%n",
                stdev(looMean) / Math.sqrt(seasons), stdev(runbookScores));

        // ------------------------------------------- is any of this resolvable
        System.out.printf("%n%nIS THE DIFFERENCE REAL? - paired, season by season%n");
        System.out.printf("Both strategies draft the same five boards, so the seasons pair"
                + " up and the%nshared variance cancels. This is the sharper test; the"
                + " five-season means above%nare the blunt one.%n%n");
        System.out.printf("  %-30s", "SEASON");
        for(PlanBacktest.Board board : boards){
            System.out.printf(" %7s", board.season());
        }
        System.out.printf(" %9s %8s%n", "mean gap", "SE");
        String[] rules = {"LOO by train mean", "LOO by worst season", "LOO by minimax regret"};
        double[][] picks = {looMean, looWorst, looRegret};
        for(int rule = 0; rule < rules.length; rule++){
            double[] gaps = differences(picks[rule], runbookScores);
            System.out.printf("  %-30s", rules[rule] + " - RUNBOOK");
            for(double gap : gaps){
                System.out.printf(" %+7.0f", gap);
            }
            System.out.printf(" %+9.0f %8.0f%n", mean(gaps), standardError(gaps));
        }
        double[] headline = differences(looMean, runbookScores);
        System.out.printf("%n  The honest picker beats the committed shape by %+.0f a season,"
                + " give or take%n  %.0f. That interval straddles zero, so on five seasons"
                + " these two are NOT%n  separated - and neither is any gap smaller than"
                + " about %.0f.%n", mean(headline), standardError(headline),
                2 * standardError(headline));
        // The same discipline, applied to the floor. A worst-season figure is a
        // MINIMUM over five draws, so it is one observation, not an average of
        // five - it carries the spread of a single season, not that spread over
        // root five. Claiming a plan is safer because its floor is higher needs a
        // gap bigger than that, and this prints the comparison rather than
        // asserting it.
        double[] worstGaps = differences(looWorst, runbookScores);
        double floorGap = min(looMean) - min(runbookScores);
        double floorNoise = stdev(headline);
        System.out.printf("%n  The floor, held to the same standard: the honest picks never"
                + " fall below %.0f,%n  the committed shape falls to %.0f - a gap of %+.0f."
                + " But a worst season is a%n  MINIMUM over five draws, one observation"
                + " rather than a mean, so its noise is the%n  full per-season spread of"
                + " %.0f, not %.0f. %s%n", min(looMean), min(runbookScores), floorGap,
                floorNoise, standardError(headline),
                floorGap > 2 * floorNoise
                        ? "That gap clears twice the spread."
                        : String.format("At %.1f spreads, that gap is SUGGESTIVE, NOT"
                                + " ESTABLISHED.", floorGap / floorNoise));
        System.out.printf("  Seasons in which each rule's pick beat the committed shape:"
                + " mean-rule %d of %d,%n  worst-rule %d of %d. A rule that wins three and"
                + " loses two has shown nothing.%n",
                (int) Arrays.stream(headline).filter(gap -> gap > 0).count(), seasons,
                (int) Arrays.stream(worstGaps).filter(gap -> gap > 0).count(), seasons);

        // ------------------------------------------------------ shape stability
        System.out.printf("%n%nSTABILITY - do the folds agree on a shape?%n%n");
        System.out.printf("  %-6s %-44s %5s %5s%n", "HELD", "SHAPE PICKED BY TRAIN MEAN",
                "hamm", "comp");
        for(Fold fold : folds){
            System.out.printf("  %-6s %-44s %5d %5d%n", boards.get(fold.heldOut()).season(),
                    render(fold.byMean()), hamming(fold.byMean(), runbook),
                    compositionDistance(fold.byMean(), runbook));
        }
        int identicalPairs = 0;
        int pairs = 0;
        int hammingSum = 0;
        for(int a = 0; a < folds.size(); a++){
            for(int b = a + 1; b < folds.size(); b++){
                pairs++;
                int apart = hamming(folds.get(a).byMean(), folds.get(b).byMean());
                hammingSum += apart;
                if(apart == 0){
                    identicalPairs++;
                }
            }
        }
        System.out.printf("%n  hamm = slots differing from the RUNBOOK shape in ORDER,"
                + " comp = in ROSTER%n");
        System.out.printf("  folds picking EXACTLY the RUNBOOK shape: %d of %d%n",
                folds.stream().filter(f -> hamming(f.byMean(), runbook) == 0).count(), seasons);
        System.out.printf("  mean pairwise Hamming between the five fold winners: %.1f of %d"
                + " slots (%d identical pairs of %d)%n", hammingSum / (double) pairs, SLOTS,
                identicalPairs, pairs);
        System.out.printf("  folds whose SLATE contains the RUNBOOK shape: %d of %d%n",
                folds.stream().filter(Fold::runbookInSlate).count(), seasons);

        // ------------------------------------------------- the consensus slate
        // A shape that is within the band no matter WHICH season you drop. Much
        // stronger than topping a five-season mean, because it cannot be carried
        // by one lucky year.
        Set<String> consensus = new HashSet<>(slateSets.get(0));
        for(int i = 1; i < slateSets.size(); i++){
            consensus.retainAll(slateSets.get(i));
        }
        System.out.printf("%n%nTHE CONSENSUS SLATE - in every fold's slate, so no single"
                + " season carries it%n");
        System.out.printf("%,d shapes of the %,d evaluated qualify. Ranked by WORST SEASON,"
                + " which is what%na manager who plays one season feels; the mean is shown"
                + " beside it, not above it.%n%n", consensus.size(), pool);
        List<String> ranked = new ArrayList<>(consensus);
        ranked.sort(Comparator.comparingDouble(
                (String sequence) -> -min(evaluator.pool().get(sequence)))
                .thenComparing(sequence -> -mean(evaluator.pool().get(sequence))));
        System.out.printf("  %-44s %6s %6s %5s %5s %5s %5s%n", "SHAPE", "worst", "mean",
                "sd", "wADP", "wRUN", "hamm");
        int shown = 0;
        for(String sequence : ranked){
            if(shown++ >= slateRows){
                break;
            }
            double[] scores = evaluator.pool().get(sequence);
            int winsAdp = 0;
            int winsRunbook = 0;
            for(int i = 0; i < seasons; i++){
                if(scores[i] > adpNull[i]){
                    winsAdp++;
                }
                if(scores[i] > runbookScores[i]){
                    winsRunbook++;
                }
            }
            System.out.printf("  %-44s %6.0f %6.0f %5.0f %4d/%d %4d/%d %5d%s%n", sequence,
                    min(scores), mean(scores), stdev(scores), winsAdp, seasons, winsRunbook,
                    seasons, hamming(parse(sequence), runbook),
                    sequence.equals(RUNBOOK) ? "   <- RUNBOOK" : "");
        }
        if(consensus.contains(RUNBOOK)){
            int worstRank = 1;
            int meanRank = 1;
            for(String sequence : consensus){
                double[] scores = evaluator.pool().get(sequence);
                if(min(scores) > min(runbookScores) + 1e-9){
                    worstRank++;
                }
                if(mean(scores) > mean(runbookScores) + 1e-9){
                    meanRank++;
                }
            }
            System.out.printf("%n  the RUNBOOK is in the consensus slate: rank %,d of %,d by"
                    + " worst season,%n  rank %,d of %,d by mean.%n", worstRank,
                    consensus.size(), meanRank, consensus.size());
        }
        else {
            System.out.printf("%n  the RUNBOOK is NOT in the consensus slate.%n");
        }

        // ------------------------------------------- the one known-sign check
        // Every other slot is an empirical question. This one is not: moving the
        // defence to the last pick strictly improves the roster in ADP terms,
        // because no opponent in this backtest ever drafts a defence. If the
        // training mean says otherwise, the training mean is reading noise, and
        // the held-out column says whether that noise cost anything.
        System.out.printf("%n%nDEF-LAST DOMINANCE TEST - the one perturbation whose sign is"
                + " known in advance%nMoving the defence to pick 186 shifts every skill pick"
                + " earlier onto a fuller board,%nso the DEF-last roster is ADP-dominant."
                + " Preferring the defence earlier can only be%njustified by what five"
                + " particular seasons happened to do.%n%n");
        System.out.printf("  %-6s %-8s %8s %8s   %8s %8s%n", "HELD", "DEF at",
                "train", "HELD-OUT", "train-DL", "HELD-DL");
        int preferredEarly = 0;
        int dominanceCostHeldOut = 0;
        double trainGain = 0;
        double heldOutGain = 0;
        for(Fold fold : folds){
            int held = fold.heldOut();
            int[] training = trainingSeasons(seasons, held);
            List<Position> chosen = fold.byMean();
            List<Position> lastly = defenceLast(chosen);
            double chosenTrain = evaluator.meanOn(chosen, training);
            double lastlyTrain = evaluator.meanOn(lastly, training);
            double chosenHeld = evaluator.scores(chosen)[held];
            double lastlyHeld = evaluator.scores(lastly)[held];
            if(chosenTrain > lastlyTrain + 1e-9){
                preferredEarly++;
            }
            if(lastlyHeld > chosenHeld + 1e-9){
                dominanceCostHeldOut++;
            }
            trainGain += chosenTrain - lastlyTrain;
            heldOutGain += chosenHeld - lastlyHeld;
            System.out.printf("  %-6s %-8d %8.0f %8.0f   %8.0f %8.0f%n",
                    boards.get(held).season(), firstSlot(chosen, Position.DEF) + 1,
                    chosenTrain, chosenHeld, lastlyTrain, lastlyHeld);
        }
        System.out.printf("%n  folds whose winner beats its own DEF-last twin ON TRAINING:"
                + " %d of %d, by %+.0f a season%n", preferredEarly, seasons,
                trainGain / seasons);
        System.out.printf("  folds where that preference LOST points out of sample:"
                + " %d of %d; mean held-out gain %+.0f%n", dominanceCostHeldOut, seasons,
                heldOutGain / seasons);
        if(heldOutGain / seasons > 0){
            System.out.printf("%n  THE PREDICTION FAILED, and the failure is the finding."
                    + " The ADP argument said%n  DEF-last must win: no opponent here ever"
                    + " drafts a defence, so the same defence%n  waits at pick 186 as at"
                    + " pick 42, while every skill pick moves onto a less%n  picked-over"
                    + " board. It lost anyway, out of sample as well as in.%n");
            System.out.printf("  Run -Pshape=\"<sequence>\" to see why: rotating the defence"
                + " does not merely move%n  WHEN you draft - the other eleven drain the"
                + " board between your picks, so the two%n  rosters share almost no"
                + " players at all. A shape is a lottery ticket over fourteen%n  board"
                + " positions, and an ADP-dominant ticket is not a better-scoring one.%n");
            System.out.printf("  DO NOT ACT ON THIS. PlanBacktest's defence market is"
                + " fictional - eleven teams%n  that never draft one - so the defence slot"
                + " is the single least trustworthy%n  parameter this instrument has, and"
                + " it is the one it argues hardest about.%n");
        }
        else {
            System.out.printf("%n  The training gain did not survive the held-out season,"
                + " on the one slot where%n  the answer was arguable beforehand. That is"
                + " overfitting measured rather than argued.%n");
        }

        // ------------------------------- which of the fourteen slots are real
        // A whole fourteen-slot shape will never repeat across folds - the space
        // is too big and the sample too small, so whole-shape agreement is the
        // wrong question. The right one is which PARAMETER the folds agree on.
        // A position whose first round is pinned in every fold is a finding; one
        // that scatters as widely as it does under a random draw is a free
        // variable the five seasons cannot see, and any plan that specifies it is
        // specifying noise.
        List<String> uniformShapes = new ArrayList<>();
        for(List<Position> shape : uniform){
            uniformShapes.add(render(shape));
        }
        System.out.printf("%n%nWHICH OF THE FOURTEEN SLOTS THE FIVE SEASONS ACTUALLY"
                + " DETERMINE%nfirst round each position is taken, as p10 / median / p90."
                + " The null column is%nthe same statistic over shapes drawn at random -"
                + " what NO information looks like.%n%n");
        System.out.printf("  %-4s %-18s %-18s %-24s %s%n", "POS", "consensus slate",
                "random null", "median per fold (2021-2025)", "verdict");
        for(Position position : ALPHABET){
            double[] slate = firstSlotSpread(consensus, position);
            double[] nullSpread = firstSlotSpread(uniformShapes, position);
            StringBuilder perFold = new StringBuilder();
            int[] foldMedians = new int[seasons];
            for(int i = 0; i < seasons; i++){
                foldMedians[i] = (int) Math.round(
                        firstSlotSpread(folds.get(i).slate(), position)[1]) + 1;
                perFold.append(String.format("%-4d", foldMedians[i]));
            }
            double slateWidth = slate[2] - slate[0];
            double nullWidth = nullSpread[2] - nullSpread[0];
            int foldMin = Arrays.stream(foldMedians).min().orElse(0);
            int foldMax = Arrays.stream(foldMedians).max().orElse(0);
            // Two signals, and the fold agreement is the one that matters. A
            // position every fold puts in the same round is determined even if
            // the tie set around it is wide; a position the folds cannot agree on
            // is free however narrow the band looks.
            String verdict;
            if(foldMax - foldMin >= 2){
                verdict = "FREE - folds disagree, do not specify";
            }
            else if(slateWidth <= 0.5 * nullWidth){
                verdict = "PINNED - folds agree, band narrow";
            }
            else {
                verdict = "LEANS - folds agree, band as wide as chance";
            }
            System.out.printf("  %-4s %-18s %-18s %-24s %s%n", position.name(),
                    String.format("%.0f / %.0f / %.0f", slate[0] + 1, slate[1] + 1, slate[2] + 1),
                    String.format("%.0f / %.0f / %.0f", nullSpread[0] + 1, nullSpread[1] + 1,
                            nullSpread[2] + 1),
                    perFold.toString(), verdict);
        }
        System.out.printf("%n  rounds are 1-14 over picks 7, 18, 31, 42, 55, 66, 79, 90, 103,"
                + " 114, 127, 162, 175, 186.%n  The RUNBOOK takes RB at 1, WR at 4, TE at 8,"
                + " QB at 10, DEF at 14.%n");

        // ------------------------------------- what the tied plans have in common
        // The usable deliverable. A specific fourteen-slot sequence is not a
        // finding at this sample size; a statement true of nearly every plan
        // inside the noise band is.
        System.out.printf("%n%nWHAT EVERY TIED PLAN HAS IN COMMON%nThe argmax is worthless"
                + " here - thousands of shapes tie. These are the statements%nthat hold"
                + " across the tie set, each against how often it holds by chance.%nLIFT is"
                + " the ratio: 1.0 means the search has no opinion.%n%n");
        System.out.printf("  %-34s %8s %8s %6s  %s%n", "TRAIT", "tied", "chance", "lift",
                "RUNBOOK");
        for(Trait trait : TRAITS){
            double tied = shareHolding(consensus, trait);
            double chance = shareHolding(uniformShapes, trait);
            double lift = chance > 0 ? tied / chance : Double.NaN;
            System.out.printf("  %-34s %7.0f%% %7.0f%% %6.2f  %-3s %s%n", trait.name(),
                    100 * tied, 100 * chance, lift,
                    trait.holds().test(runbook) ? "yes" : "no",
                    tied >= 0.95 ? "<- holds in nearly every tied plan"
                            : tied <= 0.05 ? "<- holds in almost none" : "");
        }
        System.out.printf("%n  A trait at 100%% with a lift near 1 is not a finding - it is"
                + " true of everything.%n  The usable rows are high share AND high lift, and"
                + " the rows where the committed%n  plan says 'no' to one of those are where"
                + " it differs from the tied field.%n");

        // --------------------------------------------------- where 1998 sits
        double[] uniformMeans = new double[uniform.size()];
        double[] uniformWorst = new double[uniform.size()];
        for(int i = 0; i < uniform.size(); i++){
            double[] scores = evaluator.scores(uniform.get(i));
            uniformMeans[i] = mean(scores);
            uniformWorst[i] = min(scores);
        }
        int aboveMean = 0;
        int aboveWorst = 0;
        for(int i = 0; i < uniformMeans.length; i++){
            if(uniformMeans[i] > mean(runbookScores)){
                aboveMean++;
            }
            if(uniformWorst[i] > min(runbookScores)){
                aboveWorst++;
            }
        }
        Arrays.sort(uniformMeans);
        System.out.printf("%n%nWHERE 1998 SITS IN THE FAMILY - %,d shapes drawn UNIFORMLY"
                + " from the legal space%n", uniform.size());
        System.out.printf("  mean %6.0f   median %6.0f   5th %6.0f   95th %6.0f   best"
                + " sampled %6.0f%n", mean(uniformMeans),
                uniformMeans[uniformMeans.length / 2],
                uniformMeans[(int) (0.05 * uniformMeans.length)],
                uniformMeans[(int) (0.95 * uniformMeans.length)],
                uniformMeans[uniformMeans.length - 1]);
        System.out.printf("  the RUNBOOK's %.0f beats %.2f%% of legal shapes on these five"
                + " seasons by mean,%n  and its worst season beats %.2f%% of them.%n",
                mean(runbookScores),
                100.0 * (uniformMeans.length - aboveMean) / uniformMeans.length,
                100.0 * (uniformWorst.length - aboveWorst) / uniformWorst.length);

        System.out.printf("%n%npool %,d distinct shapes, %,d season-scores, %.0fs. That is"
                + " %.5f%% of the%nlegal space, so every slate count above is a FLOOR on how"
                + " many shapes tie,%nnever a ceiling.%n", pool, evaluator.seasonEvaluations(),
                (System.currentTimeMillis() - started) / 1000.0,
                100.0 * pool / spaceSize(SLOTS).doubleValue());
    }
}
