import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * FIT THE BENCH CONSTANT TO A MEASURED OUTCOME, or find out that it cannot be.
 *
 * `BoardValue.oneSeason` owns exactly one free parameter and it is the only
 * reason that model has a bench at all. A man whose drawn season falls below
 * `lostBelow` times what his rank normally returns counts as LOST, is benched,
 * and the lineup fills by expectation from whoever is left. It works - it
 * stopped the model drafting sixteen receivers - and 0.55 was CHOSEN, in one
 * sitting, by me. That is the same class of number as the 15% fragility bar and
 * the 300-point units bug: unchecked, load-bearing, and never once asked to
 * reproduce anything.
 *
 * A target has existed the whole time and has never been used.
 * {@link BenchValue} measured what this league's real bench picks actually
 * returned over the waiver wire - 434 picks, five seasons, 44.0 points in
 * rounds 8-9, 32.8 in 10-12, 31.2 in 13-16. So:
 *
 *     for a realistic roster, what does the model say a bench man taken in
 *     each band adds - and is there a parameter that lands those three
 *     numbers on 44.0 / 32.8 / 31.2?
 *
 * Two forms are fitted against the same three targets:
 *
 *   THRESHOLD  the shipped rule, sweeping lostBelow.
 *   BLEND      Justin's proposal: order the lineup on
 *              expected + lambda * (drawn - expected), sweeping lambda. No
 *              threshold anywhere, lambda 0 is a useless bench and lambda 1 is
 *              best ball rather than this league.
 *
 * THE ANSWER MOST WORTH HAVING IS A REFUSAL. If no value of either parameter
 * reproduces the three numbers, that is a statement about the model's SHAPE and
 * is worth more than a fitted digit. If instead everything from 0.3 to 0.8
 * reproduces them equally, the parameter is not identified and must not be
 * tuned - which is what happened to the trust coefficient, whose bar covered
 * both 0.578 and 1.0. Both outcomes are printed as loudly as a fit would be.
 *
 * AND THE TWO QUANTITIES ARE NOT THE SAME QUANTITY. This is the finding the
 * fit is really about, so it is stated before any number is printed rather than
 * discovered in the last paragraph. BenchValue measures a man's OWN season over
 * the wire line, floored at zero, whether or not he ever started; the model
 * computes the LINEUP MARGINAL, what the roster's best legal lineup gains by
 * owning him. A bench receiver who scores 150 behind three better receivers is
 * worth 150-over-wire to the first measure and zero to the second. So the third
 * column below prices the model's own men on the TARGET's estimand, and the gap
 * between that column and the marginal column is the structural difference,
 * separated from any question of whether a parameter is set right.
 *
 *   ./gradlew run -Pmain=BenchCalibration -PholdKeepers=true
 *   ./gradlew run -Pmain=BenchCalibration -PholdKeepers=true -PleagueScoredActuals=true
 */
public class BenchCalibration {

    /**
     * Justin's bench picks, by the round they buy.
     *
     * Rounds 12 and 13 are his keepers, so his own bands are thinner than the
     * league-wide ones the target was measured from: he has rounds 8 and 9,
     * then 10 and 11, then 14 and 15 - round 16 goes to the defence, which
     * every manager in this league takes last and which BenchValue's join
     * excludes anyway because it counts skill positions only.
     */
    static final int[] BENCH_PICKS = {90, 103, 114, 127, 162, 175};
    static final int[] BENCH_ROUNDS = {8, 9, 10, 11, 14, 15};
    static final int DEFENCE_PICK = 186;

    /**
     * What the starting nine is, before a single bench pick is made.
     *
     * Purdy and Tuten fill the quarterback and one back; Model A's seven
     * rounds - RB WR RB WR WR WR TE - fill QB/RB2/WR3/TE/FLEX2 exactly. This is
     * the shape DRAFT-READY.md pins and the brief forbids breaking, and it is
     * the right roster to ask the bench question against: every starting slot is
     * covered, so anything after it can only pay through availability.
     */
    static final Position[] STARTERS = {Position.RB, Position.WR, Position.RB,
            Position.WR, Position.WR, Position.WR, Position.TE};
    static final int[] STARTER_PICKS = {7, 18, 31, 42, 55, 66, 79};

    /** Positions a bench pick may be. The target counts skill positions only. */
    static final Position[] CANDIDATES = {Position.RB, Position.WR,
            Position.TE, Position.QB};

    /** One band of rounds and what this league's real picks in it returned. */
    record Band(String name, double measured, double twoSE, int n){}

    /** One grid point: the parameter, what it implies per band, and its misfit. */
    record Point(double parameter, double[] implied, double chiSquare){}

    public static void main(String[] args) throws Exception {
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Map<Position, double[]> curve = RankDraft.pointsByRank(men);
        Map<Position, List<List<Double>>> pools = BoardValue.pools(men);

        List<PlanBacktest.Board> boards = new ArrayList<>();
        for(java.io.File file : new java.io.File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                boards.add(PlanBacktest.board(file, file.getName().split("-")[3]));
            }
        }
        boards.sort(Comparator.comparing(PlanBacktest.Board::season));

        Map<String, double[]> measured =
                BenchValue.overWireByBand(AAAConfiguration.getInstance());
        List<Band> targets = new ArrayList<>();
        for(Map.Entry<String, double[]> entry : measured.entrySet()){
            targets.add(new Band(entry.getKey(), entry.getValue()[0],
                    entry.getValue()[1], (int) entry.getValue()[2]));
        }

        System.out.printf("%nFITTING THE BENCH CONSTANT TO SOMETHING IT COULD BE WRONG"
                + " ABOUT%n%n");
        System.out.printf("%d seasons of outcome pool, %d real boards, lineup rule swept"
                + " in-process.%n", order.size(), boards.size());
        System.out.printf("outcomes graded %s.%n%n",
                LeagueActuals.enabled() ? "under THIS LEAGUE's rules (6 a passing TD)"
                        : "with Sleeper's pts_half_ppr (4 a passing TD)"
                          + " - -PleagueScoredActuals=true for the league's own");

        System.out.printf("THE TARGET, asked for rather than retyped%n%n");
        System.out.printf("%-14s %6s %10s %10s%n", "BAND", "n", "over wire", "+/-2se");
        for(Band band : targets){
            System.out.printf("%-14s %6d %10.1f %10.1f%n", band.name(), band.n(),
                    band.measured(), band.twoSE());
        }

        System.out.printf("%n%s%nTHE ROSTER THE QUESTION IS ASKED AGAINST%n%s%n",
                "=".repeat(72), "=".repeat(72));
        System.out.printf("%nPurdy (QB%d) and Tuten (RB%d) held, then Model A's seven:"
                + " RB WR RB WR WR WR TE.%nEvery starting slot is covered before the"
                + " first bench pick, so a bench man can%nonly pay through"
                + " availability - which is the mechanism being fitted.%n%n",
                EraKeepers.ranks()[0], EraKeepers.ranks()[1]);
        for(PlanBacktest.Board board : boards){
            System.out.printf("  %s  %s%n", board.season(), render(starters(board)));
        }

        // ------------------------------------------------------------------
        // The estimand gap, before any fitting. Two columns, same men, same
        // pools: what the model says owning him ADDS TO A LINEUP, and what the
        // model says his own season is worth OVER THE WIRE - which is the
        // quantity BenchValue measured. If the second reproduces 44.0 and the
        // first does not, the parameter is not the problem.
        // ------------------------------------------------------------------
        System.out.printf("%n%s%nTWO QUANTITIES, AND ONLY ONE OF THEM IS WHAT THE MODEL"
                + " OPTIMISES%n%s%n", "=".repeat(72), "=".repeat(72));
        System.out.printf("%nboth from the same pools, at the shipped lostBelow=%.2f.%n"
                + "  MARGINAL   what the roster's lineup gains by owning him%n"
                + "  OWN/WIRE   his own drawn season over the wire line, floored at"
                + " zero -%n             the estimand BenchValue measured, computed"
                + " inside the model%n%n", 0.55);
        BoardValue.Selection was = BoardValue.SELECTION;
        BoardValue.SELECTION = BoardValue.Selection.threshold(0.55);
        double[] shippedMarginal = bandValues(boards, curve, pools, false);
        double[] shippedOwn = bandValues(boards, curve, pools, true);
        BoardValue.SELECTION = was;
        System.out.printf("%-14s %10s %10s %10s %10s%n", "BAND", "MARGINAL", "OWN/WIRE",
                "MEASURED", "+/-2se");
        for(int band = 0; band < targets.size(); band++){
            System.out.printf("%-14s %10.1f %10.1f %10.1f %10.1f%n",
                    targets.get(band).name(), shippedMarginal[band], shippedOwn[band],
                    targets.get(band).measured(), targets.get(band).twoSE());
        }
        // NOTHING IS FITTED IN THE OWN/WIRE COLUMN. It has no free parameter at
        // all - the pools are sixteen seasons of nflverse outcomes and the wire
        // line is the measured replacement rank - so it gets three degrees of
        // freedom rather than two, and 7.81 rather than 5.99.
        double ownChi = chiSquare(shippedOwn, targets);
        double marginalChi = chiSquare(shippedMarginal, targets);
        System.out.printf("%nchi-square against the measurement:"
                + " MARGINAL %.0f, OWN/WIRE %.1f%n", marginalChi, ownChi);
        System.out.printf("OWN/WIRE has NO fitted parameter, so its bar is 7.81 (3 dof)"
                + " - %s%n", ownChi <= 7.81
                ? "and it is NOT REJECTED. The model's outcome distribution already\n"
                  + "reproduces what this league's real bench picks returned, with"
                  + " nothing tuned."
                : "and it is rejected too, so the outcome pool itself is off.");

        // ------------------------------------------------------------------
        // The two sweeps.
        // ------------------------------------------------------------------
        List<Point> threshold = sweep(boards, curve, pools, targets, true,
                0.0, 1.60, 0.05, true);
        List<Point> fielded = sweep(boards, curve, pools, targets, true,
                0.0, 1.60, 0.05, false);
        // The blend benches nobody, so wireWhenAllLost cannot reach it: there is
        // one blend curve, not two, and that is a property of the form rather
        // than an omission.
        List<Point> blend = sweep(boards, curve, pools, targets, false,
                0.0, 1.00, 0.05, true);

        report("THRESHOLD: lostBelow  (all lost -> the wire, as shipped)",
                "lostBelow", threshold, targets);
        report("THRESHOLD: lostBelow  (all lost -> his best man)",
                "lostBelow", fielded, targets);
        report("BLEND: lambda", "lambda", blend, targets);

        verdict(threshold, fielded, blend, targets);

        // ------------------------------------------------------------------
        // And what the fitted setting does to the backtest. Whatever the fit
        // says, the number that matters to Justin is the one on real outcomes.
        // ------------------------------------------------------------------
        System.out.printf("%n%s%nWHAT EACH SETTING DOES TO THE BACKTEST%n%s%n",
                "=".repeat(72), "=".repeat(72));
        System.out.printf("%nhindsight-free, real outcomes, %d seasons. the bar is 125"
                + " points at five%nseasons, so a column that moves less than that has"
                + " not moved.%n%n", boards.size());
        System.out.printf("%-24s", "LINEUP RULE");
        for(PlanBacktest.Board board : boards){
            System.out.printf(" %7s", board.season());
        }
        System.out.printf(" %9s %9s%n", "mean", "worst");
        for(BoardValue.Selection rule : backtestSettings(threshold, fielded, blend)){
            BoardValue.SELECTION = rule;
            double[] score = backtest(boards, curve, pools, order.size());
            System.out.printf("%-24s", name(rule));
            for(int season = 0; season < boards.size(); season++){
                System.out.printf(" %7.0f", score[2 + season]);
            }
            System.out.printf(" %9.0f %9.0f%n", score[0], score[1]);
        }
        BoardValue.SELECTION = was;
        System.out.printf("%nAND READ THAT TABLE AS FLATTERED. Every row is scored on the"
                + " same five seasons%nthe row was chosen from, which is TRAPS.md D17:"
                + " selection optimism was%nmeasured at +126 for a shape fitted on four"
                + " seasons and met by a fifth.%n");

        heldOut(boards, curve, pools, order.size(),
                backtestSettings(threshold, fielded, blend));
        BoardValue.SELECTION = was;
    }

    /**
     * The same choice, made without seeing the season it is graded on.
     *
     * Pick the lineup rule on four seasons, score it on the fifth, five times.
     * The season is the unit of independent randomness here (TRAPS.md D15), so
     * this is the only honest version of the question "does a different lineup
     * rule draft better" - the in-sample table above answers "which row wins on
     * the seasons it was picked from", which is a different and much easier
     * question.
     */
    static void heldOut(List<PlanBacktest.Board> boards, Map<Position, double[]> curve,
                        Map<Position, List<List<Double>>> pools, int count,
                        List<BoardValue.Selection> candidates){
        System.out.printf("%n%s%nAND WITHOUT SEEING THE SEASON IT IS GRADED ON%n%s%n%n",
                "=".repeat(72), "=".repeat(72));
        Map<BoardValue.Selection, double[]> scores = new LinkedHashMap<>();
        for(BoardValue.Selection rule : candidates){
            BoardValue.SELECTION = rule;
            scores.put(rule, backtest(boards, curve, pools, count));
        }
        System.out.printf("%-8s %-26s %9s%n", "HELD OUT", "CHOSEN ON THE OTHER FOUR",
                "SCORED");
        double total = 0;
        double shipped = 0;
        for(int out = 0; out < boards.size(); out++){
            BoardValue.Selection pick = null;
            double most = -1e9;
            for(Map.Entry<BoardValue.Selection, double[]> entry : scores.entrySet()){
                double mean = 0;
                for(int season = 0; season < boards.size(); season++){
                    if(season != out){
                        mean += entry.getValue()[2 + season];
                    }
                }
                if(mean > most){
                    most = mean;
                    pick = entry.getKey();
                }
            }
            double got = scores.get(pick)[2 + out];
            total += got;
            shipped += scores.get(BoardValue.Selection.threshold(0.55).fielding(true))
                    [2 + out];
            System.out.printf("%-8s %-26s %9.0f%n", boards.get(out).season(),
                    name(pick), got);
        }
        double chosen = total / boards.size();
        double base = shipped / boards.size();
        System.out.printf("%-8s %-26s %9.0f%n", "mean", "", chosen);
        System.out.printf("%-8s %-26s %9.0f%n", "", "shipped lostBelow=0.55", base);
        System.out.printf("%ndifference %+.0f against a %d-point bar - %s%n",
                chosen - base, 125, Math.abs(chosen - base) < 125
                        ? "A TIE. Changing the lineup rule buys nothing measurable."
                        : "outside the bar.");
    }

    /** Which settings are worth a backtest column: the ends, the shipped one, the fits. */
    static List<BoardValue.Selection> backtestSettings(List<Point> threshold,
                                                       List<Point> fielded,
                                                       List<Point> blend){
        List<BoardValue.Selection> settings = new ArrayList<>();
        for(double lostBelow : new double[]{0.0, 0.30, 0.55, 0.70, 0.85, 1.00}){
            settings.add(BoardValue.Selection.threshold(lostBelow).fielding(true));
        }
        settings.add(BoardValue.Selection.threshold(best(threshold).parameter())
                .fielding(true));
        settings.add(BoardValue.Selection.threshold(0.55).fielding(false));
        settings.add(BoardValue.Selection.threshold(best(fielded).parameter())
                .fielding(false));
        for(double lambda : new double[]{0.0, 0.25, 0.50, 0.75, 1.00}){
            settings.add(BoardValue.Selection.blend(lambda));
        }
        settings.add(BoardValue.Selection.blend(best(blend).parameter()));
        List<BoardValue.Selection> unique = new ArrayList<>();
        for(BoardValue.Selection rule : settings){
            if(!unique.contains(rule)){
                unique.add(rule);
            }
        }
        return unique;
    }

    static String name(BoardValue.Selection rule){
        if(rule.blend()){
            return String.format("blend lambda=%.2f", rule.lambda());
        }
        return String.format("lostBelow=%.2f %s", rule.lostBelow(),
                rule.wireWhenAllLost() ? "(wire)" : "(best man)");
    }

    /** {mean, worst, then each season} of BoardValue's own adaptive draft, rule as set. */
    static double[] backtest(List<PlanBacktest.Board> boards, Map<Position, double[]> curve,
                             Map<Position, List<List<Double>>> pools, int count){
        double[] out = new double[2 + boards.size()];
        double total = 0;
        double worst = Double.MAX_VALUE;
        for(int season = 0; season < boards.size(); season++){
            double points = PlanBacktest.seasonPoints(boards.get(season),
                    BoardValue.adaptiveDraft(boards.get(season), curve, pools, count));
            out[2 + season] = points;
            total += points;
            worst = Math.min(worst, points);
        }
        out[0] = boards.isEmpty() ? 0 : total / boards.size();
        out[1] = worst;
        return out;
    }

    // ======================================================================
    // The measurement.
    // ======================================================================

    /**
     * The starting nine on one board: keepers plus Model A's seven rounds, and
     * the defence that every manager here spends round 16 on.
     *
     * The defence is on the roster from the start rather than drafted last, and
     * that is not cosmetic. `oneSeason` drops the weakest man on a FULL roster
     * that holds no defence, to pay for the stream - so leaving the slot empty
     * would make the last bench pick's marginal include the cost of dropping
     * somebody, and the bands would be comparing different things.
     */
    static List<BoardValue.Slot> starters(PlanBacktest.Board board){
        List<BoardValue.Slot> roster = new ArrayList<>();
        int[] keepers = EraKeepers.ranks();
        roster.add(new BoardValue.Slot(Position.QB, keepers[0]));
        roster.add(new BoardValue.Slot(Position.RB, keepers[1]));
        for(int i = 0; i < STARTERS.length; i++){
            roster.add(new BoardValue.Slot(STARTERS[i],
                    BoardValue.adpDepth(board, STARTERS[i], STARTER_PICKS[i]) + 1));
        }
        roster.add(new BoardValue.Slot(Position.DEF,
                BoardValue.adpDepth(board, Position.DEF, DEFENCE_PICK) + 1));
        return roster;
    }

    /**
     * What the model says each bench pick is worth, band by band, averaged over
     * the boards.
     *
     * The bench picks are made SEQUENTIALLY and the roster keeps them, because
     * that is the only honest way to price the fourth body at a position: a
     * marginal computed against the starting nine every time would credit each
     * of six picks with the first one's value.
     *
     * `own` swaps the estimand rather than the parameter - instead of the
     * lineup marginal it returns the man's own drawn season over the wire line,
     * floored at zero, which is precisely what BenchValue measured over 434
     * real picks. Same men, same pools, same picks; only the question differs.
     */
    static double[] bandValues(List<PlanBacktest.Board> boards,
                               Map<Position, double[]> curve,
                               Map<Position, List<List<Double>>> pools, boolean own){
        Map<String, List<Double>> byBand = new LinkedHashMap<>();
        byBand.put(BenchValue.ROUNDS_8_9, new ArrayList<>());
        byBand.put(BenchValue.ROUNDS_10_12, new ArrayList<>());
        byBand.put(BenchValue.ROUNDS_13_16, new ArrayList<>());
        for(PlanBacktest.Board board : boards){
            List<BoardValue.Slot> roster = starters(board);
            Map<Position, Integer> have = new EnumMap<>(Position.class);
            for(BoardValue.Slot slot : roster){
                have.merge(slot.position(), 1, Integer::sum);
            }
            for(int i = 0; i < BENCH_PICKS.length; i++){
                double base = BoardValue.empirical(roster, pools, curve, 0);
                Position take = null;
                double most = -1e9;
                double taken = 0;
                for(Position position : CANDIDATES){
                    if(have.getOrDefault(position, 0)
                            >= BoardValue.MOST.get(position)){
                        continue;
                    }
                    int rank = BoardValue.adpDepth(board, position, BENCH_PICKS[i]) + 1;
                    double[] mean = curve.get(position);
                    if(mean == null || rank >= mean.length){
                        continue;
                    }
                    List<BoardValue.Slot> trial = new ArrayList<>(roster);
                    trial.add(new BoardValue.Slot(position, rank));
                    // The model's own choice at this pick is the argmax of the
                    // marginal, whichever estimand is being reported - so the
                    // two columns are priced on the SAME man and differ only in
                    // what they ask about him.
                    double marginal =
                            BoardValue.empirical(trial, pools, curve, 0) - base;
                    if(marginal > most){
                        most = marginal;
                        take = position;
                        taken = own ? ownOverWire(pools, curve, position, rank)
                                : marginal;
                    }
                }
                if(take == null){
                    continue;
                }
                byBand.get(BenchValue.band(BENCH_ROUNDS[i])).add(taken);
                roster.add(new BoardValue.Slot(take,
                        BoardValue.adpDepth(board, take, BENCH_PICKS[i]) + 1));
                have.merge(take, 1, Integer::sum);
            }
        }
        double[] out = new double[byBand.size()];
        int band = 0;
        for(List<Double> values : byBand.values()){
            out[band++] = values.stream().mapToDouble(Double::doubleValue)
                    .average().orElse(0);
        }
        return out;
    }

    /**
     * BenchValue's estimand, computed from the model's own outcome pool:
     * E[ max(0, what he drew - the wire line) ].
     *
     * The wire line is `BoardValue.replacement`, which is QB21 RB61 WR81 TE19 -
     * the same ranks {@link InsuranceTest#replacementRanks} measures from this
     * league's full sixteen-round histories and the same ones BenchValue floors
     * against. So the two numbers are denominated identically and any gap
     * between them is about the QUESTION, not the units.
     */
    static double ownOverWire(Map<Position, List<List<Double>>> pools,
                              Map<Position, double[]> curve, Position position, int rank){
        double wire = BoardValue.replacement(curve, position);
        double total = 0;
        for(int world = 0; world < BoardValue.WORLDS; world++){
            total += Math.max(0.0,
                    BoardValue.drawn(pools, position, rank, world, curve) - wire);
        }
        return total / BoardValue.WORLDS;
    }

    // ======================================================================
    // The fit.
    // ======================================================================

    static List<Point> sweep(List<PlanBacktest.Board> boards, Map<Position, double[]> curve,
                             Map<Position, List<List<Double>>> pools, List<Band> targets,
                             boolean threshold, double from, double to, double step,
                             boolean wireWhenAllLost){
        BoardValue.Selection was = BoardValue.SELECTION;
        List<Point> curveOut = new ArrayList<>();
        for(double p = from; p <= to + 1e-9; p += step){
            double value = Math.round(p * 1000.0) / 1000.0;
            BoardValue.SELECTION = (threshold ? BoardValue.Selection.threshold(value)
                    : BoardValue.Selection.blend(value)).fielding(wireWhenAllLost);
            double[] implied = bandValues(boards, curve, pools, false);
            curveOut.add(new Point(value, implied, chiSquare(implied, targets)));
        }
        BoardValue.SELECTION = was;
        return curveOut;
    }

    /**
     * Misfit in units of the target's own error bar, summed over the bands.
     *
     * The bar is what makes this a test rather than a ranking. Each band's two
     * standard errors come from BenchValue's own 434 picks, so a chi-square of
     * 6 over three bands and one parameter - two degrees of freedom, 95% at
     * 5.99 - is the line between "this reproduces the measurement" and "it does
     * not". A band with no bar at all cannot constrain anything and is skipped
     * rather than divided by zero.
     */
    static double chiSquare(double[] implied, List<Band> targets){
        double total = 0;
        for(int band = 0; band < targets.size() && band < implied.length; band++){
            double standardError = targets.get(band).twoSE() / 2.0;
            if(standardError <= 0){
                continue;
            }
            double z = (implied[band] - targets.get(band).measured()) / standardError;
            total += z * z;
        }
        return total;
    }

    static Point best(List<Point> curve){
        return curve.stream().min(Comparator.comparingDouble(Point::chiSquare))
                .orElseThrow();
    }

    /**
     * The parameter values that cannot be rejected, as an interval.
     *
     * Not "the best point plus a bar somebody chose": the acceptable set is
     * every grid point whose chi-square is within 3.84 of the minimum, which is
     * the 95% interval for one fitted parameter. If that interval spans most of
     * the swept range the parameter is NOT IDENTIFIED and tuning it is theatre -
     * TRAPS.md D21, and the exact fate of the trust coefficient.
     */
    static double[] interval(List<Point> curve){
        double floor = best(curve).chiSquare();
        double low = Double.NaN;
        double high = Double.NaN;
        for(Point point : curve){
            if(point.chiSquare() <= floor + 3.84){
                low = Double.isNaN(low) ? point.parameter() : Math.min(low, point.parameter());
                high = Double.isNaN(high) ? point.parameter()
                        : Math.max(high, point.parameter());
            }
        }
        return new double[]{low, high};
    }

    /** Is any grid point actually consistent with all three bands at once? */
    static boolean reproducible(List<Point> curve){
        return best(curve).chiSquare() <= 5.99;
    }

    static void report(String title, String knob, List<Point> curve, List<Band> targets){
        System.out.printf("%n%s%n%s%n%s%n", "=".repeat(72), title, "=".repeat(72));
        System.out.printf("%n%-10s", knob);
        for(Band band : targets){
            System.out.printf(" %13s", band.name());
        }
        System.out.printf(" %10s%n", "chi-square");
        for(Point point : curve){
            System.out.printf("%-10.2f", point.parameter());
            for(int band = 0; band < point.implied().length; band++){
                System.out.printf(" %13.1f", point.implied()[band]);
            }
            System.out.printf(" %10.1f%n", point.chiSquare());
        }
        System.out.printf("%-10s", "MEASURED");
        for(Band band : targets){
            System.out.printf(" %13.1f", band.measured());
        }
        System.out.printf("%n%-10s", "+/-2se");
        for(Band band : targets){
            System.out.printf(" %13.1f", band.twoSE());
        }
        // THE CEILING, which is the part a fit cannot argue with.
        //
        // A band whose HIGHEST value anywhere on the grid still sits below
        // measured minus two standard errors is not a band that was mis-tuned.
        // No setting of this parameter reaches it, so the shortfall is the
        // model's shape and the sweep is only measuring how far short.
        System.out.printf("%n%-10s", "CEILING");
        for(int band = 0; band < targets.size(); band++){
            double most = 0;
            for(Point point : curve){
                most = Math.max(most, point.implied()[band]);
            }
            System.out.printf(" %13s", String.format("%.1f%s", most,
                    most < targets.get(band).measured() - targets.get(band).twoSE()
                            ? " X" : ""));
        }
        System.out.printf("%n           (X = out of reach at every setting swept,"
                + " not merely mis-set)%n");

        Point top = best(curve);
        double[] range = interval(curve);
        System.out.printf("%n%nbest %s %.2f, chi-square %.1f over 2 degrees of freedom"
                + " (95%% at 5.99)%n", knob, top.parameter(), top.chiSquare());
        System.out.printf("%s%n", reproducible(curve)
                ? "  -> this form CAN reproduce the measured bands."
                : "  -> NO value of " + knob + " reproduces the measured bands.");
        double span = curve.get(curve.size() - 1).parameter() - curve.get(0).parameter();
        if(reproducible(curve)){
            System.out.printf("  -> not rejected: %s %.2f to %.2f%n", knob,
                    range[0], range[1]);
            if(span > 0 && (range[1] - range[0]) > 0.5 * span){
                System.out.printf("  -> and the interval covers %.0f%% of the swept"
                        + " range, so it is NOT IDENTIFIED.%n",
                        100.0 * (range[1] - range[0]) / span);
            }
        }
        else {
            // A confidence interval around a REJECTED fit is not a confidence
            // interval. It is the neighbourhood of the least-bad miss, and
            // printing it as a range invites somebody to quote it as an answer.
            System.out.printf("  -> so %.2f to %.2f is NOT an error bar. It is where"
                    + " the miss is smallest,%n     which is a different thing and"
                    + " must not be quoted as a fitted value.%n", range[0], range[1]);
        }
        System.out.printf("  -> sensitivity near the shipped 0.55: %s%n",
                sensitivity(curve, 0.55));
    }

    /**
     * How far the first band moves per 0.1 of the parameter, against the
     * target's own bar.
     *
     * This is the number that says whether the target COULD identify the
     * parameter if the model were able to reach it. A parameter that moves the
     * implied value by less than one error bar over its whole plausible range
     * is not identifiable by this measurement however the fit comes out.
     */
    static String sensitivity(List<Point> curve, double about){
        Point below = null;
        Point above = null;
        for(Point point : curve){
            if(point.parameter() <= about){
                below = point;
            }
            if(above == null && point.parameter() > about){
                above = point;
            }
        }
        if(below == null || above == null || above.parameter() == below.parameter()){
            return "not measurable on this grid";
        }
        double slope = (above.implied()[0] - below.implied()[0])
                / (above.parameter() - below.parameter());
        return String.format("%.1f points of rounds 8-9 value per 0.1", slope / 10.0);
    }

    /** The finding, said once, in words, whichever way it came out. */
    static void verdict(List<Point> threshold, List<Point> fielded, List<Point> blend,
                        List<Band> targets){
        System.out.printf("%n%s%nTHE FINDING%n%s%n%n", "=".repeat(72), "=".repeat(72));
        Point bestThreshold = best(threshold);
        Point bestFielded = best(fielded);
        Point bestBlend = best(blend);
        System.out.printf("closest approach, in units of the target's own error bar:%n");
        System.out.printf("  threshold (wire)      chi-square %8.0f at lostBelow %.2f%n",
                bestThreshold.chiSquare(), bestThreshold.parameter());
        System.out.printf("  threshold (best man)  chi-square %8.0f at lostBelow %.2f%n",
                bestFielded.chiSquare(), bestFielded.parameter());
        System.out.printf("  blend                 chi-square %8.0f at lambda %.2f%n%n",
                bestBlend.chiSquare(), bestBlend.parameter());
        boolean either = reproducible(threshold) || reproducible(fielded)
                || reproducible(blend);
        if(!either){
            System.out.printf("NO FORM REPRODUCES THE MEASUREMENT, at any setting"
                    + " swept.%n%n");
            System.out.printf("That is a result about the model's SHAPE, not a knob left"
                    + " to turn. The two%nquantities are not the same quantity: the model"
                    + " prices a LINEUP MARGINAL and%nthe measurement is a man's OWN"
                    + " season over the wire. Read the OWN/WIRE column%nabove against"
                    + " MEASURED - if those agree, the outcome distribution is fine and"
                    + "%nthe difference is entirely the question being asked.%n");
            return;
        }
        String better = bestThreshold.chiSquare() <= bestBlend.chiSquare()
                ? "THRESHOLD" : "BLEND";
        System.out.printf("The %s form fits better: chi-square %.1f against %.1f.%n",
                better, Math.min(bestThreshold.chiSquare(), bestBlend.chiSquare()),
                Math.max(bestThreshold.chiSquare(), bestBlend.chiSquare()));
        System.out.printf("  threshold  lostBelow %.2f, not rejected %.2f to %.2f%n",
                bestThreshold.parameter(), interval(threshold)[0], interval(threshold)[1]);
        System.out.printf("  blend      lambda %.2f, not rejected %.2f to %.2f%n",
                bestBlend.parameter(), interval(blend)[0], interval(blend)[1]);
        // The coordinator's own sweep of the backtest put a collapse above 0.90 -
        // 1789 mean against 1970 at 0.85, which is outside the 125-point bar. A
        // fit landing up there is evidence the fit is wrong, not a discovery,
        // and it says so rather than leaving the reader to notice.
        if(bestThreshold.parameter() > 0.90){
            System.out.printf("%nBUT lostBelow %.2f IS PAST THE COLLAPSE. Above about"
                    + " 0.90 nearly every man%ncounts as lost, the lineup fills from"
                    + " whoever is left and the search stops%ndiscriminating - measured"
                    + " on the backtest as 1789 against 1970 at 0.85.%nTreat a fit up"
                    + " here as the fit being wrong, not as a finding.%n",
                    bestThreshold.parameter());
        }
    }

    static String render(List<BoardValue.Slot> roster){
        StringBuilder out = new StringBuilder();
        for(BoardValue.Slot slot : roster){
            out.append(out.isEmpty() ? "" : " ")
                    .append(slot.position()).append(slot.rank());
        }
        return out.toString();
    }
}
