import PlayerImportAndSetup.Position;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Is the committed plan's 1998 a plateau or a spike?
 *
 * The committed RUNBOOK plan is a fixed fourteen-slot position shape that beats
 * every model this repo has built on the five-season backtest. That is either
 * because the SHAPE is right, or because that particular sequence caught the
 * best bounces on the only five seasons anyone has scored it against - the same
 * luckiness the models are accused of, since the shape was chosen by us from
 * these seasons too.
 *
 * The two hypotheses make opposite predictions about the neighbourhood:
 *
 *   PLATEAU - many nearby shapes score the same. 1998 is not special; the
 *             structure is (RB-heavy open, one TE, late DEF), and any shape
 *             with that structure would do. The plan is safe to trust and not
 *             worth trying to beat by a few points.
 *   SPIKE   - the committed sequence stands alone and every perturbation falls
 *             away. That is what an overfit looks like: the sequence is tuned
 *             to five particular seasons, and the 2026 season is not one of
 *             them.
 *
 * So this walks outward from the committed shape and scores everything it finds
 * on PlanBacktest's own scorer - not a copy of it, the same code - so nothing
 * here can quietly disagree with the number it is testing.
 *
 *   ./gradlew run -Pmain=ShapeSensitivity
 *   ./gradlew run -Pmain=ShapeSensitivity -Ptrials=8000    # bigger random sweeps
 *   ./gradlew run -Pmain=ShapeSensitivity -Pseed=7         # a different sample
 *   ./gradlew run -Pmain=ShapeSensitivity -Pshape="RB RB WR ..."   # score one
 *
 * WHAT COUNTS AS A TIE. Five seasons is a very small sample and the per-season
 * spread is huge - the committed plan itself ranges 1654 to 2191. The standard
 * error of its five-season mean is about 95 points, so two shapes whose means
 * differ by less than that have not been distinguished by this evidence. The
 * headline number below is how many shapes fall inside that band. A second,
 * sharper test is also reported: because every shape drafts from the same five
 * boards, the per-season DIFFERENCE between two shapes is paired, and its
 * standard error is much smaller than either mean's. A shape can be inside the
 * crude band and still be separated by the paired test, so both are printed.
 *
 * LEGALITY. The league requires a defence on the roster and a streamed one still
 * occupies one of the sixteen spots. Shapes that draft no defence are scored and
 * shown - PlanBacktest prices the stream honestly, by dropping the last man - but
 * they are marked ILLEGAL and excluded from every count, every ranking and the
 * verdict. They are a measurement, not a candidate.
 */
public class ShapeSensitivity {

    /** The committed RUNBOOK plan, exactly as PlanBacktest holds it. */
    static final String COMMITTED = PlanBacktest.STRATEGIES.get("RUNBOOK committed");

    /** Rounds behind the fourteen picks. The keepers hold rounds 12 and 13. */
    static final int[] ROUND = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 14, 15, 16};

    /** Everything this league drafts. OTHER and K are not roster positions here. */
    static final List<Position> DRAFTABLE =
            List.of(Position.QB, Position.RB, Position.WR, Position.TE, Position.DEF);

    /**
     * Anything inside this many points of the committed mean is called a tie.
     *
     * MEASURED, not chosen. An earlier version of this tool used 90, the naive
     * standard error of a five-season mean. The right number came from a proper
     * power analysis clustered on season: 94 points is bare significance and 125
     * is the gap this design can detect 80% of the time. A tool that declared
     * ties at 94 would call a real difference a tie one time in five, so the
     * honest band for "we could not have told these apart" is the 80%-power one.
     *
     * The 95-point figure the header still prints is the crude per-season error
     * for orientation; it is not what the verdict uses.
     */
    static final double TIE_BAND = 125.0;

    /** One shape, scored, and everything the verdict needs to know about it. */
    public record Scored(String shape, double[] seasons, double mean, double worst,
                         double deltaMean, double deltaStandardError, double changed,
                         int wins, boolean legal){}

    private static List<PlanBacktest.Board> boards;
    private static final Map<String, Scored> CACHE = new LinkedHashMap<>();
    private static List<List<String>> committedRosters;
    private static Scored committed;

    public static void main(String[] args) throws Exception {
        int trials = Integer.getInteger("trials", 4000);
        long seed = Long.parseLong(System.getProperty("seed", "20260830"));
        boards = loadBoards();
        if(boards.isEmpty()){
            System.out.println("no seasons to backtest");
            return;
        }
        committedRosters = new ArrayList<>();
        for(PlanBacktest.Board board : boards){
            committedRosters.add(PlanBacktest.draft(board, COMMITTED));
        }
        committed = score(COMMITTED);

        String one = System.getProperty("shape");
        if(one != null){
            Scored scored = score(render(parse(one)));
            header();
            System.out.printf("%n%s%n", scored.shape());
            row(scored);
            return;
        }

        header();
        rivals();
        List<Scored> grid = singleSlotGrid();
        costCurves();
        List<Scored> swaps = adjacentSwaps();
        List<Scored> hamming2 = hammingTwo();
        List<Scored> orderings = sameBudgetOrderings(trials, seed);
        // The budget landscape is 560 budgets wide, so it dominates the runtime
        // while carrying the least weight in the verdict - it exists to show
        // that most position budgets are simply bad, which a few dozen orders
        // each already establishes. -Pbuckets raises its resolution.
        List<Scored> budgets = budgetLandscape(
                Integer.getInteger("buckets", Math.max(4, trials / 1000)), seed);
        List<Scored> human = plausiblePlans(trials, seed);
        verdict(grid, swaps, hamming2, orderings, budgets, human);
    }

    static void header(){
        System.out.printf("%nSHAPE SENSITIVITY - plateau or spike around the committed plan%n");
        System.out.printf("scored by PlanBacktest on %d real seasons, best legal lineup each week%n",
                boards.size());
        System.out.printf("%ncommitted  %s%n", COMMITTED);
        System.out.printf("%-11s", "");
        for(PlanBacktest.Board board : boards){
            System.out.printf(" %7s", board.season());
        }
        System.out.printf(" %8s %8s%n", "mean", "worst");
        System.out.printf("%-11s", "");
        for(double season : committed.seasons()){
            System.out.printf(" %7.0f", season);
        }
        System.out.printf(" %8.0f %8.0f%n", committed.mean(), committed.worst());
        System.out.printf("%ncrude per-season error of that mean: %.0f. THE TIE BAND IS"
                + " +/- %.0f, which is%nnot that number: it is the measured 80%%-power"
                + " detection threshold for this design%n(94 points is bare significance,"
                + " 125 is what it can catch four times in five).%n",
                standardError(committed.seasons()), TIE_BAND);
        System.out.printf("a shape inside the band has NOT been distinguished from the"
                + " committed plan by%nthis evidence. 'paired' below is the sharper test:"
                + " the same-board per-season%ndifference, whose error bar is much smaller."
                + " 'chg' is how many of the fourteen%ndrafted men actually changed - a"
                + " perturbation that changes nobody is a no-op,%nnot a plateau.%n");
        System.out.printf("%nTHE KEEPERS ARE NOT IN THIS BACKTEST. PlanBacktest scores the"
                + " fourteen DRAFTED%nmen only, so every shape here has to supply its own"
                + " starting quarterback and%nits own second back. Justin does not: Purdy"
                + " (QB) and Tuten (RB) are kept at%nrounds 13 and 12. Any result below"
                + " that turns on needing a QB early is measuring%na hole his real roster"
                + " does not have, and should be discounted accordingly.%n");
    }

    // ---------------------------------------------------------------- scoring

    static List<PlanBacktest.Board> loadBoards() throws Exception {
        List<PlanBacktest.Board> loaded = new ArrayList<>();
        File[] files = new File("data").listFiles();
        if(files == null){
            return loaded;
        }
        for(File file : files){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                PlanBacktest.Board board =
                        PlanBacktest.board(file, file.getName().split("-")[3]);
                if(board != null && board.ids().size() > 150){
                    loaded.add(board);
                }
            }
        }
        loaded.sort(Comparator.comparing(PlanBacktest.Board::season));
        return loaded;
    }

    /**
     * Score one shape on every season, memoised.
     *
     * The memo is keyed on the rendered shape, so the sweeps below can overlap
     * freely without paying twice and without double-counting a shape in the
     * tie set.
     */
    static Scored score(String shape){
        Scored hit = CACHE.get(shape);
        if(hit != null){
            return hit;
        }
        double[] seasons = new double[boards.size()];
        double[] deltas = new double[boards.size()];
        double changed = 0;
        int wins = 0;
        for(int i = 0; i < boards.size(); i++){
            PlanBacktest.Board board = boards.get(i);
            List<String> roster = PlanBacktest.draft(board, shape);
            seasons[i] = PlanBacktest.seasonPoints(board, roster);
            deltas[i] = seasons[i] - committedScore(i);
            if(deltas[i] > 0){
                wins++;
            }
            Set<String> theirs = new java.util.HashSet<>(committedRosters.get(i));
            for(String id : roster){
                if(!theirs.contains(id)){
                    changed++;
                }
            }
        }
        Scored scored = new Scored(shape, seasons, mean(seasons), min(seasons),
                mean(deltas), standardError(deltas), changed / boards.size(), wins,
                legal(parse(shape)));
        CACHE.put(shape, scored);
        return scored;
    }

    private static double committedScore(int season){
        return committed == null
                ? PlanBacktest.seasonPoints(boards.get(season), committedRosters.get(season))
                : committed.seasons()[season];
    }

    static void row(Scored scored){
        System.out.printf("%-11s", scored.legal() ? "" : "ILLEGAL");
        for(double season : scored.seasons()){
            System.out.printf(" %7.0f", season);
        }
        System.out.printf(" %8.0f %8.0f   vs committed %+6.0f  paired se %5.0f"
                + "  beats plan %d/%d  chg %4.1f%n",
                scored.mean(), scored.worst(), scored.deltaMean(),
                scored.deltaStandardError(), scored.wins(), scored.seasons().length,
                scored.changed());
    }

    // ----------------------------------------- 0. the rivals, paired properly

    /**
     * The other strategies, measured against the plan the same way as every
     * perturbation below.
     *
     * PlanBacktest already prints these means. What it does not print is the
     * PAIRED error bar, and that is the whole question: "the committed plan beats
     * every model we have built" is a claim about a difference, and a difference
     * needs its own error bar rather than the two means' eyeballed gap. Since
     * every strategy drafts from the same five boards, the per-season difference
     * is paired and its standard error is the right one.
     */
    static void rivals(){
        System.out.printf("%n%n0. THE RIVALS - the same comparison PlanBacktest prints,"
                + " with the paired error%n   bar it does not. 'beats' is how many of the"
                + " five seasons each actually won.%n%n");
        System.out.printf("   %-26s %8s %8s %9s %10s %7s%n",
                "STRATEGY", "mean", "worst", "vs plan", "paired se", "beats");
        for(Map.Entry<String, String> entry : PlanBacktest.STRATEGIES.entrySet()){
            if(entry.getValue() == null){
                continue;                 // best-available-by-ADP has no fixed shape
            }
            Scored scored = score(render(parse(entry.getValue())));
            System.out.printf("   %-26s %8.0f %8.0f %+9.0f %10.0f %5d/%d%s%n",
                    entry.getKey(), scored.mean(), scored.worst(), scored.deltaMean(),
                    scored.deltaStandardError(), scored.wins(), boards.size(),
                    scored.legal() ? "" : "   ILLEGAL");
        }
        System.out.printf("%n   A gap smaller than about twice its paired se has not been"
                + " demonstrated.%n");
    }

    // ------------------------------------------------- 1. single-slot changes

    /**
     * Every legal position in every one of the fourteen slots.
     *
     * This is the core map: it says which picks carry the plan's edge and which
     * are free. A slot whose five entries are all within the tie band is a slot
     * the plan has no opinion about, whatever the RUNBOOK writes in it.
     */
    static List<Scored> singleSlotGrid(){
        System.out.printf("%n%n1. SINGLE-SLOT GRID - each slot, each position, five-season mean%n%n");
        System.out.printf("%-6s %-5s", "ROUND", "PICK");
        for(Position position : DRAFTABLE){
            System.out.printf(" %7s", position);
        }
        System.out.printf("   %s%n", "worst cost   verdict");
        List<Position> base = parse(COMMITTED);
        List<Scored> all = new ArrayList<>();
        for(int slot = 0; slot < base.size(); slot++){
            System.out.printf("%-6d %-5d", ROUND[slot], PlanBacktest.MY_PICKS[slot]);
            double low = Double.MAX_VALUE;
            double high = -Double.MAX_VALUE;
            int tied = 0;
            for(Position position : DRAFTABLE){
                Scored scored = score(render(substitute(base, slot, position)));
                boolean isCommitted = base.get(slot) == position;
                if(!isCommitted){
                    all.add(scored);
                }
                if(!scored.legal()){
                    System.out.printf(" %6.0f!", scored.mean());
                }
                else {
                    System.out.printf(" %6.0f%s", scored.mean(), isCommitted ? "=" : " ");
                    low = Math.min(low, scored.mean());
                    high = Math.max(high, scored.mean());
                    if(Math.abs(scored.mean() - committed.mean()) <= TIE_BAND){
                        tied++;
                    }
                }
            }
            int legalCells = 0;
            for(Position position : DRAFTABLE){
                if(legal(substitute(base, slot, position))){
                    legalCells++;
                }
            }
            double cost = committed.mean() - low;
            String verdict = legalCells == 1
                    ? "FORCED - every alternative is illegal"
                    : cost <= TIE_BAND
                    ? "FREE - the worst legal swap is inside the band"
                    : "CONSTRAINED - costs more than the band";
            System.out.printf("   %10.0f   %s%n", cost, verdict);
        }
        System.out.printf("%n= committed  ! drafts no defence, so illegal: shown, never counted%n");
        System.out.printf("'worst cost' is what the WORST legal position in that slot"
                + " costs against the plan.%nA slot is only CONSTRAINED if even the worst"
                + " thing you could do there is detectable.%n");
        long tiedCells = all.stream().filter(s -> s.legal())
                .filter(s -> Math.abs(s.mean() - committed.mean()) <= TIE_BAND).count();
        long legalCells = all.stream().filter(Scored::legal).count();
        System.out.printf("%nof %d legal single-slot changes, %d tie the committed plan"
                + " and %d are clearly worse or better.%n",
                legalCells, tiedCells, legalCells - tiedCells);
        List<Scored> better = all.stream().filter(Scored::legal)
                .filter(s -> s.mean() > committed.mean())
                .sorted(Comparator.comparingDouble(Scored::mean).reversed()).toList();
        System.out.printf("%d of them score ABOVE the committed plan:%n", better.size());
        for(Scored scored : better){
            System.out.printf("   %s%n", scored.shape());
            row(scored);
        }
        return all;
    }

    // ------------------------------------------------------- 2. cost curves

    /**
     * The three decisions the plan actually argued about.
     *
     * The tight end's round, the defence's round and whether a second quarterback
     * belongs at all. Each is measured as a cost CURVE - move the man to every
     * slot and score - rather than asserted, because a decision that is right
     * only at one slot is a different kind of claim from one that is right over
     * a range.
     */
    static void costCurves(){
        List<Position> base = parse(COMMITTED);
        System.out.printf("%n%n2. COST CURVES - moving one man to every other round%n");
        curve("TIGHT END (committed: round 8, second TE round 11)", base,
                base.indexOf(Position.TE));
        curve("DEFENCE (committed: round 16, the last pick)", base,
                base.lastIndexOf(Position.DEF));
        curve("SECOND QUARTERBACK (committed: round 14; the first is round 10)", base,
                base.lastIndexOf(Position.QB));

        System.out.printf("%n   ...and what dropping each one entirely is worth"
                + " (the man replaced by the%n   best of the other positions in his slot):%n%n");
        dropCost("second TE (round 11)", base, base.lastIndexOf(Position.TE));
        dropCost("the only DEF (round 16)", base, base.lastIndexOf(Position.DEF));
        dropCost("second QB (round 14)", base, base.lastIndexOf(Position.QB));
        dropCost("starting TE (round 8)", base, base.indexOf(Position.TE));
    }

    static void curve(String label, List<Position> base, int from){
        System.out.printf("%n   %s%n", label);
        System.out.printf("   %-6s %-6s %8s %8s %9s %9s   %s%n",
                "ROUND", "PICK", "mean", "worst", "vs plan", "paired se", "shape");
        for(int to = 0; to < base.size(); to++){
            List<Position> moved = move(base, from, to);
            Scored scored = score(render(moved));
            String flag = to == from ? "  <- committed"
                    : !scored.legal() ? "  ILLEGAL, no defence"
                    : Math.abs(scored.mean() - committed.mean()) <= TIE_BAND ? "  tie" : "";
            System.out.printf("   %-6d %-6d %8.0f %8.0f %+9.0f %9.0f   %s%s%n",
                    ROUND[to], PlanBacktest.MY_PICKS[to], scored.mean(), scored.worst(),
                    scored.deltaMean(), scored.deltaStandardError(),
                    render(moved), flag);
        }
    }

    static void dropCost(String label, List<Position> base, int slot){
        Scored best = null;
        for(Position position : DRAFTABLE){
            if(position == base.get(slot)){
                continue;
            }
            Scored scored = score(render(substitute(base, slot, position)));
            if(scored.legal() && (best == null || scored.mean() > best.mean())){
                best = scored;
            }
        }
        if(best != null){
            System.out.printf("   %-26s best replacement scores %6.0f  (%+5.0f, paired se %4.0f)%n",
                    label, best.mean(), best.deltaMean(), best.deltaStandardError());
        }
    }

    // ---------------------------------------------------- 3. adjacent swaps

    /**
     * Swap rounds i and i+1.
     *
     * The cheapest possible perturbation, and the one a spike would be most
     * sensitive to: if the exact ORDER of two consecutive picks is worth real
     * points, the plan is reading the board's fine structure, which five seasons
     * cannot support. Many of these are no-ops because the two slots already
     * hold the same position; those are marked and excluded.
     */
    static List<Scored> adjacentSwaps(){
        System.out.printf("%n%n3. ADJACENT SWAPS - exchange rounds i and i+1%n%n");
        System.out.printf("   %-14s %8s %8s %9s %9s %5s   %s%n",
                "SWAP", "mean", "worst", "vs plan", "paired se", "chg", "shape");
        List<Position> base = parse(COMMITTED);
        List<Scored> real = new ArrayList<>();
        for(int i = 0; i + 1 < base.size(); i++){
            if(base.get(i) == base.get(i + 1)){
                System.out.printf("   r%-2d <-> r%-8d %8s   same position, no-op%n",
                        ROUND[i], ROUND[i + 1], "-");
                continue;
            }
            Scored scored = score(render(swap(base, i, i + 1)));
            real.add(scored);
            System.out.printf("   r%-2d <-> r%-8d %8.0f %8.0f %+9.0f %9.0f %5.1f   %s%s%n",
                    ROUND[i], ROUND[i + 1], scored.mean(), scored.worst(),
                    scored.deltaMean(), scored.deltaStandardError(), scored.changed(),
                    scored.shape(),
                    Math.abs(scored.mean() - committed.mean()) <= TIE_BAND ? "  tie" : "");
        }
        long tied = real.stream()
                .filter(s -> Math.abs(s.mean() - committed.mean()) <= TIE_BAND).count();
        System.out.printf("%n   %d of %d real adjacent swaps tie the committed plan.%n",
                tied, real.size());
        return real;
    }

    // ------------------------------------------------- 4. two-slot exhaustive

    /**
     * Every shape that differs from the committed one in at most two slots.
     *
     * Exhaustive, not sampled: 14 choose 2 slots times four alternatives each.
     * This is the real local map. A spike is a peak with a cliff around it; a
     * plateau has hundreds of neighbours at the same height.
     */
    static List<Scored> hammingTwo(){
        System.out.printf("%n%n4. TWO-SLOT SWEEP - exhaustive, every shape within two changes%n");
        List<Position> base = parse(COMMITTED);
        List<Scored> all = new ArrayList<>();
        for(int a = 0; a < base.size(); a++){
            for(int b = a + 1; b < base.size(); b++){
                for(Position first : DRAFTABLE){
                    if(first == base.get(a)){
                        continue;
                    }
                    for(Position second : DRAFTABLE){
                        if(second == base.get(b)){
                            continue;
                        }
                        all.add(score(render(
                                substitute(substitute(base, a, first), b, second))));
                    }
                }
            }
        }
        summarise("two-slot changes", all);
        return all;
    }

    // -------------------------------------------- 5. same budget, new order

    /**
     * Hold the committed plan's position BUDGET and reshuffle the order.
     *
     * Four RB, five WR, two QB, two TE and a defence, dealt into the fourteen
     * picks in every other order. There are 540,540 of them, so this samples.
     *
     * This separates the two things the RUNBOOK could be right about. If most
     * reshuffles score the same, the plan's content is the budget - how many of
     * each - and the sequence is decoration. If only the committed order scores
     * 1998, the plan is claiming to know which round each man belongs in, which
     * is a far stronger claim and a far easier one to make by accident.
     */
    static List<Scored> sameBudgetOrderings(int trials, long seed){
        System.out.printf("%n%n5. SAME BUDGET, DIFFERENT ORDER - %d random reshuffles of"
                + " 4 RB / 5 WR / 2 QB / 2 TE / 1 DEF%n", trials);
        List<Position> base = parse(COMMITTED);
        Random random = new Random(seed);
        Set<String> seen = new LinkedHashSet<>();
        List<Scored> all = new ArrayList<>();
        for(int trial = 0; trial < trials; trial++){
            List<Position> shuffled = new ArrayList<>(base);
            Collections.shuffle(shuffled, random);
            String rendered = render(shuffled);
            if(seen.add(rendered)){
                all.add(score(rendered));
            }
        }
        summarise("distinct reshuffles", all);
        return all;
    }

    // ------------------------------------------------- 6. the budget landscape

    /**
     * Every position budget, not just the committed one.
     *
     * All 560 ways to split the thirteen non-defence picks among QB, RB, WR and
     * TE, each dealt in a few random orders with the defence last. If the tie
     * set spans many budgets, the plan's specific counts are not the finding
     * either, and what is left is something much coarser.
     */
    static List<Scored> budgetLandscape(int ordersPerBudget, long seed){
        System.out.printf("%n%n6. THE BUDGET LANDSCAPE - all 560 QB/RB/WR/TE splits of the"
                + " thirteen non-defence%n   picks, %d random orders each, defence last%n",
                ordersPerBudget);
        Random random = new Random(seed + 1);
        List<Scored> all = new ArrayList<>();
        record Budget(int qb, int rb, int wr, int te, double best, double worstSeason,
                      String shape){}
        List<Budget> budgets = new ArrayList<>();
        for(int qb = 0; qb <= 13; qb++){
            for(int rb = 0; rb + qb <= 13; rb++){
                for(int wr = 0; wr + rb + qb <= 13; wr++){
                    int te = 13 - qb - rb - wr;
                    List<Position> pool = new ArrayList<>();
                    for(int i = 0; i < qb; i++){ pool.add(Position.QB); }
                    for(int i = 0; i < rb; i++){ pool.add(Position.RB); }
                    for(int i = 0; i < wr; i++){ pool.add(Position.WR); }
                    for(int i = 0; i < te; i++){ pool.add(Position.TE); }
                    Scored best = null;
                    for(int order = 0; order < ordersPerBudget; order++){
                        List<Position> shape = new ArrayList<>(pool);
                        Collections.shuffle(shape, random);
                        shape.add(Position.DEF);
                        Scored scored = score(render(shape));
                        all.add(scored);
                        if(best == null || scored.mean() > best.mean()){
                            best = scored;
                        }
                    }
                    budgets.add(new Budget(qb, rb, wr, te, best.mean(), best.worst(),
                            best.shape()));
                }
            }
        }
        budgets.sort(Comparator.comparingDouble(Budget::best).reversed());
        System.out.printf("%n   best twelve budgets, by the best order sampled for each:%n");
        System.out.printf("   %-3s %-3s %-3s %-3s %8s %8s   %s%n",
                "QB", "RB", "WR", "TE", "best", "worst", "that order");
        for(Budget budget : budgets.subList(0, Math.min(12, budgets.size()))){
            System.out.printf("   %-3d %-3d %-3d %-3d %8.0f %8.0f   %s%n",
                    budget.qb(), budget.rb(), budget.wr(), budget.te(),
                    budget.best(), budget.worstSeason(), budget.shape());
        }
        long budgetsTying = budgets.stream()
                .filter(b -> b.best() >= committed.mean() - TIE_BAND).count();
        System.out.printf("%n   %d of %d budgets reach within the tie band on at least one"
                + " sampled order.%n", budgetsTying, budgets.size());
        System.out.printf("   committed budget is QB 2 / RB 4 / WR 5 / TE 2.%n");
        summarise("sampled budget-landscape shapes", all);
        return all;
    }

    static void summarise(String label, List<Scored> all){
        List<Scored> legal = all.stream().filter(Scored::legal).toList();
        long tied = legal.stream()
                .filter(s -> Math.abs(s.mean() - committed.mean()) <= TIE_BAND).count();
        long above = legal.stream().filter(s -> s.mean() > committed.mean()).count();
        long clearlyAbove = legal.stream()
                .filter(s -> s.mean() > committed.mean() + TIE_BAND).count();
        System.out.printf("%n   %d legal %s: %d tie (%.1f%%), %d score above the plan,"
                + " %d above by more than the band.%n",
                legal.size(), label, tied, 100.0 * tied / Math.max(1, legal.size()),
                above, clearlyAbove);
        System.out.printf("   family spread: mean %.0f, sd %.0f, range %.0f - %.0f."
                + " The committed plan sits at the %.1fth percentile.%n",
                familyMean(legal), familySd(legal), familyMin(legal), familyMax(legal),
                percentile(legal, committed.mean()));
        long sweeps = legal.stream().filter(s -> s.wins() == boards.size()).count();
        System.out.printf("   %d beat the committed plan in ALL %d seasons"
                + " (%.2f%% - chance alone would give %.2f%%).%n",
                sweeps, boards.size(), 100.0 * sweeps / Math.max(1, legal.size()),
                100.0 / Math.pow(2, boards.size()));
        List<Scored> top = legal.stream()
                .sorted(Comparator.comparingDouble(Scored::mean).reversed())
                .limit(8).toList();
        System.out.printf("   top eight:%n");
        for(Scored scored : top){
            System.out.printf("   %8.0f  worst %6.0f  %+6.0f (paired se %4.0f)  beats %d/%d"
                    + "  %s%n",
                    scored.mean(), scored.worst(), scored.deltaMean(),
                    scored.deltaStandardError(), scored.wins(), boards.size(),
                    scored.shape());
        }
    }

    // ------------------------------------------- 6b. plans a human would write

    /**
     * Would a competent manager actually submit this shape?
     *
     * The budget landscape above is dominated by nonsense - thirteen quarterbacks,
     * no receivers - so the tie rate against it flatters the committed plan by
     * comparing it to plans nobody would write. This is the honest denominator:
     * enough of every position to field the league's lineup, exactly one defence,
     * and that defence no earlier than round 10, which every manager in this
     * league obeys.
     *
     * The question Justin is actually asking is this one. Not "is the plan better
     * than a random string of positions" - obviously - but "of the plans a
     * thinking person might have written down instead, how many do just as well?"
     */
    public static boolean plausible(List<Position> shape){
        long defences = shape.stream().filter(p -> p == Position.DEF).count();
        if(defences != 1){
            return false;
        }
        if(shape.indexOf(Position.DEF) < 9){
            return false;               // nobody in this league drafts one before round 10
        }
        return shape.stream().filter(p -> p == Position.QB).count() >= 1
                && shape.stream().filter(p -> p == Position.RB).count() >= 2
                && shape.stream().filter(p -> p == Position.WR).count() >= 3
                && shape.stream().filter(p -> p == Position.TE).count() >= 1;
    }

    static List<Scored> plausiblePlans(int trials, long seed){
        System.out.printf("%n%n6b. PLANS A HUMAN WOULD ACTUALLY WRITE - %d sampled uniformly%n"
                + "    (>=1 QB, >=2 RB, >=3 WR, >=1 TE, exactly one DEF, DEF in round 10"
                + " or later)%n", trials);
        List<int[]> budgets = new ArrayList<>();
        for(int qb = 1; qb <= 13; qb++){
            for(int rb = 2; qb + rb <= 13; rb++){
                for(int wr = 3; qb + rb + wr <= 13; wr++){
                    int te = 13 - qb - rb - wr;
                    if(te >= 1){
                        budgets.add(new int[]{qb, rb, wr, te});
                    }
                }
            }
        }
        Random random = new Random(seed + 2);
        Set<String> seen = new LinkedHashSet<>();
        List<Scored> all = new ArrayList<>();
        for(int trial = 0; trial < trials; trial++){
            int[] budget = budgets.get(random.nextInt(budgets.size()));
            List<Position> shape = new ArrayList<>();
            for(int i = 0; i < budget[0]; i++){ shape.add(Position.QB); }
            for(int i = 0; i < budget[1]; i++){ shape.add(Position.RB); }
            for(int i = 0; i < budget[2]; i++){ shape.add(Position.WR); }
            for(int i = 0; i < budget[3]; i++){ shape.add(Position.TE); }
            Collections.shuffle(shape, random);
            shape.add(9 + random.nextInt(5), Position.DEF);
            String rendered = render(shape);
            if(!plausible(shape)){
                throw new IllegalStateException("sampler built an implausible shape: " + rendered);
            }
            if(seen.add(rendered)){
                all.add(score(rendered));
            }
        }
        System.out.printf("    drawn from %d position budgets%n", budgets.size());
        summarise("distinct plausible plans", all);
        return all;
    }

    // ---------------------------------------------------------- 7. verdict

    static void verdict(List<Scored> grid, List<Scored> swaps, List<Scored> hamming2,
                        List<Scored> orderings, List<Scored> budgets, List<Scored> human){
        System.out.printf("%n%n7. VERDICT%n%n");
        System.out.printf("   %-46s %6s %6s %8s %10s%n",
                "FAMILY", "legal", "tie", "tie rate", "plan's %ile");
        family("single-slot changes (exhaustive)", grid);
        family("adjacent swaps (exhaustive)", swaps);
        family("two-slot changes (exhaustive)", hamming2);
        family("reshuffles of the committed budget (sampled)", orderings);
        family("all budgets (sampled)", budgets);
        family("plans a human would write (sampled)", human);
        System.out.printf("%n   'plan's %%ile' is where the committed plan's %.0f falls inside"
                + " each family.%n   A shape that were genuinely a peak would sit near 100"
                + " everywhere.%n", committed.mean());

        List<Scored> everything = CACHE.values().stream().filter(Scored::legal)
                .filter(s -> !s.shape().equals(COMMITTED)).toList();
        List<Scored> tied = everything.stream()
                .filter(s -> Math.abs(s.mean() - committed.mean()) <= TIE_BAND).toList();
        System.out.printf("%nACROSS EVERY DISTINCT LEGAL SHAPE SCORED HERE (%d of them):%n",
                everything.size());
        System.out.printf("   %d tie the committed plan's %.0f within +/- %.0f.%n",
                tied.size(), committed.mean(), TIE_BAND);
        System.out.printf("   %d are clearly worse, %d clearly better.%n",
                everything.stream().filter(s -> s.mean() < committed.mean() - TIE_BAND).count(),
                everything.stream().filter(s -> s.mean() > committed.mean() + TIE_BAND).count());
        System.out.printf("   (this pooled denominator is NOT a uniform sample of shape"
                + " space - the sweeps%n   above deliberately crowd around the committed"
                + " plan. Read the per-family%n   percentages, not this count, as the"
                + " frequency of a tie.)%n");

        characterise("every shape scored here - a biased, plan-crowded sample", tied);
        characterise("plans a human would write - the unbiased sample",
                human.stream().filter(Scored::legal)
                        .filter(s -> Math.abs(s.mean() - committed.mean()) <= TIE_BAND)
                        .toList());
        aboveThePlan(everything);
        steadier(everything);
    }

    static void family(String label, List<Scored> all){
        List<Scored> legal = all.stream().filter(Scored::legal).toList();
        long tied = legal.stream()
                .filter(s -> Math.abs(s.mean() - committed.mean()) <= TIE_BAND).count();
        System.out.printf("   %-46s %6d %6d %7.1f%% %9.1f%n",
                label, legal.size(), tied, 100.0 * tied / Math.max(1, legal.size()),
                percentile(legal, committed.mean()));
    }

    // ------------------------------------------------------ family statistics

    static double familyMean(List<Scored> all){
        return mean(all.stream().mapToDouble(Scored::mean).toArray());
    }

    static double familySd(List<Scored> all){
        double[] means = all.stream().mapToDouble(Scored::mean).toArray();
        return means.length < 2 ? 0
                : standardError(means) * Math.sqrt(means.length);
    }

    static double familyMin(List<Scored> all){
        return all.stream().mapToDouble(Scored::mean).min().orElse(0);
    }

    static double familyMax(List<Scored> all){
        return all.stream().mapToDouble(Scored::mean).max().orElse(0);
    }

    /**
     * Where a score falls inside a family, 0 to 100.
     *
     * This is the number that decides the question. If the committed plan is at
     * the 99.9th percentile of the plans a human might have written instead, it
     * is a peak and worth defending. If it is at the 70th, it is an ordinary
     * member of a large good set and its win over the models is not a discovery.
     */
    public static double percentile(List<Scored> family, double value){
        if(family.isEmpty()){
            return 0;
        }
        long below = family.stream().filter(s -> s.mean() < value).count();
        return 100.0 * below / family.size();
    }

    /**
     * What do the shapes that tie have in common?
     *
     * If a large tie set shares one structure, that structure is the finding and
     * the exact sequence is not. This prints the structure directly rather than
     * leaving it to be eyeballed off a list of hundreds of shapes.
     */
    static void characterise(String label, List<Scored> tied){
        if(tied.isEmpty()){
            System.out.printf("%nNothing ties in %s. The committed shape stands alone there.%n",
                    label);
            return;
        }
        System.out.printf("%nWHAT THE TIE SET HAS IN COMMON - %s (%d shapes):%n%n",
                label, tied.size());
        Map<Position, double[]> counts = new EnumMap<>(Position.class);
        Map<Position, double[]> firstRound = new EnumMap<>(Position.class);
        for(Position position : DRAFTABLE){
            counts.put(position, new double[]{Double.MAX_VALUE, -Double.MAX_VALUE, 0});
            firstRound.put(position, new double[]{Double.MAX_VALUE, -Double.MAX_VALUE, 0, 0});
        }
        int rbOrWrFirst = 0;
        int defenceLast = 0;
        int rbFirstThreeAtLeastTwo = 0;
        double earlyRbWr = 0;
        for(Scored scored : tied){
            List<Position> shape = parse(scored.shape());
            for(Position position : DRAFTABLE){
                double count = shape.stream().filter(p -> p == position).count();
                double[] stat = counts.get(position);
                stat[0] = Math.min(stat[0], count);
                stat[1] = Math.max(stat[1], count);
                stat[2] += count;
                int index = shape.indexOf(position);
                if(index >= 0){
                    double[] round = firstRound.get(position);
                    round[0] = Math.min(round[0], ROUND[index]);
                    round[1] = Math.max(round[1], ROUND[index]);
                    round[2] += ROUND[index];
                    round[3]++;
                }
            }
            if(shape.get(0) == Position.RB || shape.get(0) == Position.WR){
                rbOrWrFirst++;
            }
            if(shape.get(shape.size() - 1) == Position.DEF){
                defenceLast++;
            }
            long rbEarly = shape.subList(0, 3).stream().filter(p -> p == Position.RB).count();
            if(rbEarly >= 2){
                rbFirstThreeAtLeastTwo++;
            }
            earlyRbWr += shape.subList(0, 6).stream()
                    .filter(p -> p == Position.RB || p == Position.WR).count();
        }
        System.out.printf("   %-5s %10s %10s %14s%n", "POS", "count avg", "count range",
                "first taken in round (avg, range)");
        for(Position position : DRAFTABLE){
            double[] count = counts.get(position);
            double[] round = firstRound.get(position);
            System.out.printf("   %-5s %10.2f %5.0f - %-4.0f", position,
                    count[2] / tied.size(), count[0], count[1]);
            if(round[3] > 0){
                System.out.printf("   r%-5.1f  r%.0f - r%.0f   (drafted at all in %d of %d)%n",
                        round[2] / round[3], round[0], round[1],
                        (long) round[3], tied.size());
            }
            else {
                System.out.printf("   never drafted%n");
            }
        }
        System.out.printf("%n   round 1 is RB or WR ............... %5.1f%%%n",
                100.0 * rbOrWrFirst / tied.size());
        System.out.printf("   at least two RB in rounds 1-3 ..... %5.1f%%%n",
                100.0 * rbFirstThreeAtLeastTwo / tied.size());
        System.out.printf("   defence is the very last pick ..... %5.1f%%%n",
                100.0 * defenceLast / tied.size());
        System.out.printf("   RB+WR among the first six picks ... %5.2f of 6 on average%n",
                earlyRbWr / tied.size());
    }

    /**
     * Anything that beat the plan - reported, and immediately discounted.
     *
     * A shape can clear 1998 on five seasons by winning one of them on a bounce,
     * so the leave-one-season-out column matters more than the mean: if dropping
     * a single season kills the lead, the lead was that season.
     */
    static void aboveThePlan(List<Scored> everything){
        List<Scored> better = everything.stream()
                .filter(s -> s.mean() > committed.mean() + TIE_BAND)
                .sorted(Comparator.comparingDouble(Scored::mean).reversed())
                .limit(15).toList();
        System.out.printf("%nSHAPES SCORING CLEARLY ABOVE THE COMMITTED PLAN%n");
        if(better.isEmpty()){
            System.out.printf("   none.%n");
            return;
        }
        System.out.printf("   CANDIDATES ONLY - not wins. Each is the best of a sweep that"
                + " scored thousands%n   of shapes on five seasons, so the top of that list"
                + " is selected for luck by%n   construction. 'worst LOO' is the weakest"
                + " four-season mean after dropping one%n   season: if it falls back to the"
                + " plan, the lead was one season's bounce.%n%n");
        System.out.printf("   %8s %8s %9s %9s %7s   %s%n",
                "mean", "worst", "worst LOO", "paired se", "beats", "shape");
        for(Scored scored : better){
            System.out.printf("   %8.0f %8.0f %9.0f %9.0f %5d/%d   %s%n",
                    scored.mean(), scored.worst(), worstLeaveOneOut(scored),
                    scored.deltaStandardError(), scored.wins(), boards.size(),
                    scored.shape());
        }
        System.out.printf("%n   'beats' is the paired sign test - how many of the %d seasons"
                + " this shape actually%n   won. A shape that beat the plan in every season"
                + " would be worth a second look;%n   one that won three and lost two is a"
                + " mean pushed around by one big season.%n", boards.size());
    }

    /** The weakest mean obtainable by dropping one season. */
    static double worstLeaveOneOut(Scored scored){
        double worst = Double.MAX_VALUE;
        for(int drop = 0; drop < scored.seasons().length; drop++){
            double sum = 0;
            for(int i = 0; i < scored.seasons().length; i++){
                if(i != drop){
                    sum += scored.seasons()[i];
                }
            }
            worst = Math.min(worst, sum / (scored.seasons().length - 1));
        }
        return worst;
    }

    /**
     * Shapes that never have the committed plan's bad season.
     *
     * Justin plays one season, not the average of five. A shape that gives up a
     * little mean but lifts the floor is the better bet for a single season, and
     * the mean-ranked lists above hide it completely.
     */
    static void steadier(List<Scored> everything){
        List<Scored> steady = everything.stream()
                .filter(s -> Math.abs(s.mean() - committed.mean()) <= TIE_BAND
                        || s.mean() > committed.mean())
                .filter(s -> s.worst() > committed.worst())
                .sorted(Comparator.comparingDouble(Scored::worst).reversed())
                .limit(15).toList();
        System.out.printf("%nSHAPES WITH A HIGHER FLOOR THAN THE COMMITTED PLAN%n");
        System.out.printf("   the plan's worst season is %.0f (%s). These tie or beat it on"
                + " the mean AND%n   never fall that far. One season is what actually gets"
                + " played.%n%n", committed.worst(), worstSeasonLabel());
        if(steady.isEmpty()){
            System.out.printf("   none.%n");
            return;
        }
        System.out.printf("   %-9s", "");
        for(PlanBacktest.Board board : boards){
            System.out.printf(" %7s", board.season());
        }
        System.out.printf(" %7s %7s %8s %6s %6s   %s%n",
                "mean", "worst", "paired se", "beats", "chg", "shape");
        for(Scored scored : steady){
            System.out.printf("   %-9s", "");
            for(double season : scored.seasons()){
                System.out.printf(" %7.0f", season);
            }
            System.out.printf(" %7.0f %7.0f %8.0f %4d/%d %6.1f   %s%n",
                    scored.mean(), scored.worst(), scored.deltaStandardError(),
                    scored.wins(), boards.size(), scored.changed(), scored.shape());
        }
        System.out.printf("%n   Read 'paired se' before believing any of these. A shape whose"
                + " gain over the%n   plan is smaller than its own paired error bar has not"
                + " raised the floor; it has%n   drawn a different set of players and landed"
                + " a better %s.%n", worstSeasonLabel());
    }

    static String worstSeasonLabel(){
        int worst = 0;
        for(int i = 1; i < committed.seasons().length; i++){
            if(committed.seasons()[i] < committed.seasons()[worst]){
                worst = i;
            }
        }
        return boards.get(worst).season();
    }

    // ----------------------------------------------------- shape arithmetic

    public static List<Position> parse(String shape){
        List<Position> positions = new ArrayList<>();
        for(String token : shape.trim().split("\\s+")){
            positions.add(Position.valueOf(token));
        }
        return positions;
    }

    public static String render(List<Position> shape){
        StringBuilder rendered = new StringBuilder();
        for(Position position : shape){
            if(rendered.length() > 0){
                rendered.append(' ');
            }
            rendered.append(position.name());
        }
        return rendered.toString();
    }

    public static List<Position> substitute(List<Position> shape, int slot, Position position){
        List<Position> changed = new ArrayList<>(shape);
        changed.set(slot, position);
        return changed;
    }

    public static List<Position> swap(List<Position> shape, int a, int b){
        List<Position> changed = new ArrayList<>(shape);
        Collections.swap(changed, a, b);
        return changed;
    }

    /**
     * Take the man at slot `from` and draft him at slot `to` instead, everyone
     * else keeping their order. This is what "move the tight end back a round"
     * means at the draft table - not a swap, which would also move whoever was
     * standing there.
     */
    public static List<Position> move(List<Position> shape, int from, int to){
        List<Position> changed = new ArrayList<>(shape);
        Position moved = changed.remove(from);
        changed.add(Math.min(to, changed.size()), moved);
        return changed;
    }

    /**
     * The league requires a defence on the roster, and a streamed one still takes
     * one of the sixteen spots. A shape that drafts none is not a plan Justin can
     * submit, whatever it scores.
     */
    public static boolean legal(List<Position> shape){
        // Legality depends on what you already hold. Justin keeps Purdy and
        // Tuten, so for him a plan drafting no quarterback fields a full lineup
        // - Purdy is in it. In a fourteen-pick search with no keepers the same
        // plan takes a zero at QB every week and is illegal, which is why no
        // search here had ever proposed one and 1,918 of them sat unscored.
        // PlanBacktest.requiredPicks() subtracts what the keepers cover, so the
        // two halves - what counts as legal, and who is on the roster being
        // scored - move together. Letting them diverge would be worse than the
        // bug: nought-QB plans would become legal and then be scored with an
        // empty quarterback slot.
        Map<Position, Integer> have = new EnumMap<>(Position.class);
        for(Position position : shape){
            have.merge(position, 1, Integer::sum);
        }
        for(Map.Entry<Position, Integer> need : PlanBacktest.requiredPicks().entrySet()){
            if(have.getOrDefault(need.getKey(), 0) < need.getValue()){
                return false;
            }
        }
        return true;
    }

    public static double mean(double[] values){
        double sum = 0;
        for(double value : values){
            sum += value;
        }
        return values.length == 0 ? 0 : sum / values.length;
    }

    public static double min(double[] values){
        double worst = Double.MAX_VALUE;
        for(double value : values){
            worst = Math.min(worst, value);
        }
        return values.length == 0 ? 0 : worst;
    }

    /** Standard error of the mean: the sample sd over root n. */
    public static double standardError(double[] values){
        if(values.length < 2){
            return 0;
        }
        double mean = mean(values);
        double sum = 0;
        for(double value : values){
            sum += (value - mean) * (value - mean);
        }
        return Math.sqrt(sum / (values.length - 1)) / Math.sqrt(values.length);
    }
}
