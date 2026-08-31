import PlayerImportAndSetup.Position;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * The measuring instrument, not another strategy.
 *
 * PlanBacktest and PolicyBacktest score every strategy on five seasons at draft
 * slot 7 and print the mean. The per-season spread is around 200 points, so the
 * standard error on a five-season mean is about 90 and nothing smaller than
 * ~180 points is distinguishable from luck - yet strategies have been ranked on
 * gaps of 30 to 150. A search over model variants judged that way selects the
 * luckiest variant, not the best one. This tool exists to say how big a gap has
 * to be before it means anything.
 *
 * Four changes:
 *
 *   DRAFT SLOT. Everything ran at slot 7. The same season drafted from twelve
 *   different seats produces twelve largely-distinct rosters, so twelve slots
 *   times five seasons times several opponent worlds replaces five numbers with
 *   hundreds. Slot 7 stays reportable on its own, because it is Justin's seat.
 *
 *   OPPONENT VARIATION. The other eleven teams took strictly the best available
 *   skill player by ADP, one fixed sequence of picks forever. Here they draft
 *   from a board perturbed by PickDisplacement - this league's OWN measured
 *   residuals, fitted on 814 real picks, which is where the league's habits
 *   (quarterbacks fall 16 selections, receivers go 17 early) come from. A
 *   strategy is judged against many opponent worlds instead of one.
 *
 *   COMMON RANDOM NUMBERS. Every strategy faces the identical set of
 *   (season, slot, opponent-world) draws, and the opponent board for a world is
 *   drawn once and handed to all of them. Comparisons are therefore PAIRED, and
 *   the paired difference cancels most of the variance the two strategies share.
 *
 *   CLUSTERED UNCERTAINTY. This is the part that matters most, and it is the
 *   part that cuts against the tool's own headline. Twelve slots and eight
 *   worlds do NOT buy 480 independent observations: every draw inside a season
 *   is scored on the SAME realised football, so the season is the unit of
 *   independent randomness and there are still only five of them. Extra slots
 *   and worlds shrink the WITHIN-season noise and can do nothing at all about
 *   the BETWEEN-season noise. The standard errors here are clustered on season,
 *   with four degrees of freedom, and the variance is decomposed so the floor -
 *   what the error would be with infinitely many slots and worlds on these same
 *   five seasons - is printed explicitly.
 *
 * There is no -Pdeviate here and the committed plan is never consulted as a
 * prior by anything this tool runs. That flag defaulted to "never deviate" once
 * and made a backtest silently replay the plan it was supposed to be testing.
 *
 * Scoring is unchanged and un-hindsighted: PlanBacktest.seasonPoints fills each
 * week's lineup by PRESEASON board rank and counts REALISED points.
 *
 *   ./gradlew run -Pmain=PowerBacktest -Pkeepers=Tuten,Purdy -q
 *   ./gradlew run -Pmain=PowerBacktest -Pseeds=16 -q     more opponent worlds
 *   ./gradlew run -Pmain=PowerBacktest -Pjitter=0 -q     opponents back to fixed ADP
 *   ./gradlew run -Pmain=PowerBacktest -PnoPolicy -q     skip the adaptive policy
 */
public class PowerBacktest {

    static final int TEAMS = 12;
    static final int ROUNDS = 16;

    /** Justin's two keepers cost his round 12 and round 13 picks. */
    static final Set<Integer> KEEPER_ROUNDS = Set.of(12, 13);

    /** Justin's own seat, reported separately from the twelve-slot average. */
    static final int MY_SLOT = 7;

    /** Everything is measured as a difference against this row. */
    static final String BASELINE = "RUNBOOK committed";

    /** How many opponent worlds per season (-Pseeds). */
    static final int SEEDS = Integer.getInteger("seeds", 8);

    /**
     * How hard to perturb the opponents' board (-Pjitter), as a multiple of the
     * measured PickDisplacement residual. 1 is as this league actually drafts.
     * 0 turns the opponents back into the old deterministic best-available-by-
     * ADP robots, which is what makes the PlanBacktest reproduction check
     * possible - see {@link #reproductionCheck}.
     */
    static final double JITTER = Double.parseDouble(System.getProperty("jitter", "1"));

    /** Scenario draws inside the starter-sum objective (-Pscenarios). */
    static final int SCENARIOS = Integer.getInteger("scenarios", 300);

    /** -PnoPolicy skips the adaptive starter-sum policy, which dominates runtime. */
    static final boolean NO_POLICY = Boolean.getBoolean("noPolicy");

    /**
     * Rows that are not fair entrants and must not anchor a conclusion.
     *
     * best-nine (Model A) is Model A run past round 7, which is outside its
     * domain. Its objective is the best legal NINE - the nine non-defence
     * starting slots - and Justin holds two keepers, so two keepers plus seven
     * picks fill those slots exactly. From round 8 the objective is indifferent
     * and everything it emits is an artifact; DraftNight prints "THE STARTING
     * NINE IS FULL" at precisely that point, and a DraftPlanner run shows the
     * value pinned at 1812.8 for every pick from the sixth onward. The
     * legitimate mixed row is ModelA front + SS back, which uses Model A for
     * the front seven only.
     *
     * The row is still scored, because its standard error is a real measurement
     * of this instrument, but it is marked everywhere it appears.
     */
    static final Set<String> OUT_OF_DOMAIN = Set.of("best-nine (Model A)");

    /**
     * How a season's realised football is turned into weekly points - THE SEAM
     * FOR SWAPPING THE OUTCOME MEASURE, which is one line below.
     *
     * Sleeper's pts_half_ppr pays FOUR points for a passing touchdown and this
     * league pays six, so grading outcomes off the raw feed understates every
     * starting quarterback by roughly 55-66 points a season. That is a
     * SYSTEMATIC error, not a random one, and no amount of sample size fixes
     * it: this instrument measures how big a gap has to be to beat noise, and
     * is blind by construction to a bias that moves every row the same way.
     *
     * The live switch is LeagueActuals, which PlanBacktest.board already grades
     * through - so this tool follows it automatically and GRADER stays NULL.
     * A non-null grader here would silently overrule that toggle and re-impose
     * whatever it names, which is precisely the -Pdeviate fault in a new hat:
     * a default that quietly decides the thing under test. The seam exists for
     * an explicit, deliberate override and for nothing else.
     */
    public interface Grader {
        Map<String, Double> pointsBySleeperID(String season, int week);
    }

    static final Grader GRADER = null;

    /**
     * The same board, regraded. A Board carries its weekly outcomes, and both
     * PlanBacktest.score and PlanBacktest.seasonPoints read them off the board
     * they are handed, so replacing them here replaces the outcome measure for
     * everything this tool runs - including the reproduction check, which then
     * checks the harness against PlanBacktest under the new grader too.
     */
    static PlanBacktest.Board regraded(PlanBacktest.Board board, Grader grader){
        if(grader == null){
            return board;
        }
        List<Map<String, Double>> weekly = new ArrayList<>();
        for(int week = 1; week <= WeeklyActuals.WEEKS; week++){
            weekly.add(grader.pointsBySleeperID(board.season(), week));
        }
        return new PlanBacktest.Board(board.season(), board.ids(), board.positionOf(),
                weekly);
    }

    /** One evaluation: a season, a seat, and an opponent world. */
    public record Draw(String season, int slot, int world){}

    // ---------------------------------------------------------------- schedule

    /**
     * The pick numbers a seat owns in a snake draft, with the keeper rounds
     * removed.
     *
     * Slot 7 with keepers at rounds 12 and 13 must come out as
     * 7 18 31 42 55 66 79 90 103 114 127 162 175 186 - note the 35-pick hole
     * from 127 to 162 where the two kept players sit.
     */
    public static int[] picksFor(int slot, int teams, int rounds, Set<Integer> keeperRounds){
        if(slot < 1 || slot > teams){
            throw new IllegalArgumentException("slot " + slot + " outside 1.." + teams);
        }
        List<Integer> picks = new ArrayList<>();
        for(int round = 1; round <= rounds; round++){
            if(keeperRounds.contains(round)){
                continue;
            }
            int seat = round % 2 == 1 ? slot : teams - slot + 1;
            picks.add((round - 1) * teams + seat);
        }
        int[] numbers = new int[picks.size()];
        for(int i = 0; i < numbers.length; i++){
            numbers[i] = picks.get(i);
        }
        return numbers;
    }

    // ------------------------------------------------------------- opponents

    /**
     * The order the other eleven teams will draft in, for one opponent world.
     *
     * A player's key is his ADP depth plus one draw from the league's measured
     * displacement: the position offset (how far this league lets that position
     * fall) plus a bootstrapped residual from the right depth bin. Sorting on
     * that key is invariant to any constant shared by every player, so only the
     * SPREAD and the BETWEEN-POSITION offsets move the board - which is exactly
     * what a different room full of drafters changes.
     *
     * Defences are dropped: the other eleven never take one before the last
     * rounds, which is the rule PlanBacktest already used and the reason a
     * drafted defence is worth so little in this league.
     *
     * At jitter 0 this returns the plain ADP order and the harness reduces to
     * the old deterministic one, which is what the reproduction check needs.
     */
    public static List<String> opponentOrder(List<String> ids,
                                             Map<String, Position> positionOf,
                                             DisplacementModel displacement,
                                             double jitter, long seed){
        List<String> skill = new ArrayList<>();
        for(String id : ids){
            if(positionOf.get(id) != Position.DEF){
                skill.add(id);
            }
        }
        if(jitter == 0 || displacement == null){
            return skill;
        }
        Random random = new Random(seed);
        Map<String, Double> key = new HashMap<>();
        for(int depth = 0; depth < skill.size(); depth++){
            String id = skill.get(depth);
            key.put(id, (depth + 1)
                    + jitter * displacement.sample(random, depth + 1, positionOf.get(id)));
        }
        List<String> order = new ArrayList<>(skill);
        order.sort(Comparator.comparingDouble(key::get));
        return order;
    }

    /** A world is a season plus an opponent draw; every seat sees the same one. */
    static long worldSeed(String season, int world){
        return 0x9E3779B97F4A7C15L * (world + 1L) ^ (Long.parseLong(season) * 7_919L);
    }

    // ----------------------------------------------------------------- drafts

    /** Per-season objects that do not depend on the seat or the opponent world. */
    static final class SeasonModel {
        final PlanBacktest.Board board;
        final Map<String, Position> positionOf;
        final RosterValue value;

        SeasonModel(PlanBacktest.Board board, RosterValue value){
            this.board = board;
            this.positionOf = board.positionOf();
            this.value = value;
        }
    }

    /**
     * One draft. Mirrors PlanBacktest.score exactly, with two things lifted out
     * of the constants: which picks are mine, and what order the opponents work
     * down. My own picks still read the TRUE ADP board - the perturbation is the
     * opponents' idiosyncrasy, not mine.
     */
    static List<String> draftSequence(PlanBacktest.Board board, int[] myPicks,
                                      List<String> opponentOrder, String sequence){
        List<Position> wanted = new ArrayList<>();
        if(sequence != null){
            for(String token : sequence.split("\\s+")){
                wanted.add(Position.valueOf(token));
            }
        }
        Set<String> gone = new HashSet<>();
        List<String> mine = new ArrayList<>();
        Set<Integer> mineAt = new HashSet<>();
        for(int pick : myPicks){
            mineAt.add(pick);
        }
        int taken = 0;
        int cursor = 0;
        for(int pick = 1; pick <= TEAMS * ROUNDS && taken < myPicks.length; pick++){
            if(mineAt.contains(pick)){
                String choice = wanted.isEmpty()
                        ? PlanBacktest.bestAvailable(board, gone, null)
                        : PlanBacktest.bestAvailable(board, gone, wanted.get(taken));
                if(choice == null){
                    choice = PlanBacktest.bestAvailable(board, gone, null);
                }
                if(choice != null){
                    mine.add(choice);
                    gone.add(choice);
                }
                taken++;
            }
            else {
                while(cursor < opponentOrder.size()
                        && gone.contains(opponentOrder.get(cursor))){
                    cursor++;
                }
                if(cursor < opponentOrder.size()){
                    gone.add(opponentOrder.get(cursor));
                }
            }
        }
        return mine;
    }

    /**
     * The adaptive starter-sum policy: at each of my picks take the position
     * whose best available man adds most to the starter sum, given the roster so
     * far. Same rule as PolicyBacktest.runPolicy, minus every flag - no prior,
     * no scarcity term, no pinned front, no defence reservation - because this
     * tool is measuring the model, not a model wearing the plan's clothes.
     */
    static List<String> draftPolicy(SeasonModel model, int[] myPicks,
                                    List<String> opponentOrder){
        PlanBacktest.Board board = model.board;
        Set<String> gone = new HashSet<>();
        List<String> mine = new ArrayList<>();
        List<Position> plan = new ArrayList<>();
        Set<Integer> mineAt = new HashSet<>();
        for(int pick : myPicks){
            mineAt.add(pick);
        }
        int cursor = 0;
        for(int pick = 1; pick <= TEAMS * ROUNDS && mine.size() < myPicks.length; pick++){
            if(mineAt.contains(pick)){
                String best = null;
                Position bestPosition = null;
                double bestValue = -Double.MAX_VALUE;
                for(Position position : new Position[]{Position.QB, Position.RB,
                        Position.WR, Position.TE, Position.DEF}){
                    if(!worthTaking(position, plan, myPicks.length)){
                        continue;
                    }
                    String candidate = PlanBacktest.bestAvailable(board, gone, position);
                    if(candidate == null){
                        continue;
                    }
                    List<String> trial = new ArrayList<>(mine);
                    trial.add(candidate);
                    double now = model.value.of(trial);
                    if(now > bestValue){
                        bestValue = now;
                        best = candidate;
                        bestPosition = position;
                    }
                }
                if(best != null){
                    mine.add(best);
                    gone.add(best);
                    plan.add(bestPosition);
                }
            }
            else {
                while(cursor < opponentOrder.size()
                        && gone.contains(opponentOrder.get(cursor))){
                    cursor++;
                }
                if(cursor < opponentOrder.size()){
                    gone.add(opponentOrder.get(cursor));
                }
            }
        }
        return mine;
    }

    /**
     * The roster-legality floor, restated here rather than borrowed from
     * PolicyBacktest.worthTaking because that one reads four system properties
     * and would let an unrelated flag change what this tool measures.
     *
     * You start one defence and one quarterback, so a second defence can never
     * play and a third quarterback is a wasted spot.
     */
    static boolean worthTaking(Position candidate, List<Position> chosen, int picks){
        if(candidate != Position.DEF && candidate != Position.QB){
            return true;
        }
        int held = 0;
        for(Position position : chosen){
            if(position == candidate){
                held++;
            }
        }
        return candidate == Position.DEF ? held < 1 : held < 2;
    }

    // ------------------------------------------------------------ statistics

    /**
     * The paired comparison of one strategy against the baseline.
     *
     * seNaive treats all N draws as independent, which they are not, and is
     * printed only so the size of that lie is visible. seSeason clusters on
     * season and is the number to use.
     */
    public record Paired(String name, double mean, double diff, double seNaive,
                         double seSeason, int wins, int draws, int clusters,
                         double varBetween, double varWithin, double perCluster){

        /** How big an observed gap has to be before it clears 95% two-sided. */
        public double bar(){
            return t975(clusters - 1) * seSeason;
        }

        /** Significant at 95%, clustered. */
        public boolean real(){
            return Math.abs(diff) > bar();
        }

        /** The error that would remain with infinitely many slots and worlds. */
        public double seFloor(){
            return Math.sqrt(Math.max(0, varBetween) / clusters);
        }

        /** What the standard error would be with this many seasons and draws each. */
        public double seAt(int seasons, int drawsEach){
            return Math.sqrt(Math.max(0, varBetween) / seasons
                    + varWithin / ((double) seasons * drawsEach));
        }
    }

    /**
     * Paired statistics for one strategy's per-draw differences.
     *
     * @param diff       difference against the baseline, one per draw
     * @param clusterOf  which season each draw belongs to, 0-based
     * @param clusters   how many seasons there are
     */
    public static Paired paired(String name, double[] score, double[] diff,
                                int[] clusterOf, int clusters){
        int n = diff.length;
        double mean = Arrays.stream(score).average().orElse(0);
        double bar = Arrays.stream(diff).average().orElse(0);
        double sumSquares = 0;
        int wins = 0;
        for(double d : diff){
            sumSquares += (d - bar) * (d - bar);
            if(d > 0){
                wins++;
            }
        }
        double seNaive = n > 1 ? Math.sqrt(sumSquares / (n - 1) / n) : 0;

        double[] clusterSum = new double[clusters];
        int[] clusterCount = new int[clusters];
        for(int k = 0; k < n; k++){
            clusterSum[clusterOf[k]] += diff[k];
            clusterCount[clusterOf[k]]++;
        }
        double[] clusterMean = new double[clusters];
        double meanOfMeans = 0;
        for(int c = 0; c < clusters; c++){
            clusterMean[c] = clusterCount[c] == 0 ? 0 : clusterSum[c] / clusterCount[c];
            meanOfMeans += clusterMean[c] / clusters;
        }
        double betweenObserved = 0;
        for(int c = 0; c < clusters; c++){
            betweenObserved += (clusterMean[c] - meanOfMeans) * (clusterMean[c] - meanOfMeans);
        }
        betweenObserved = clusters > 1 ? betweenObserved / (clusters - 1) : 0;
        double seSeason = clusters > 0 ? Math.sqrt(betweenObserved / clusters) : 0;

        // pooled variance of draws around their own season's mean
        double within = 0;
        for(int k = 0; k < n; k++){
            double centred = diff[k] - clusterMean[clusterOf[k]];
            within += centred * centred;
        }
        double varWithin = n > clusters ? within / (n - clusters) : 0;
        double perCluster = (double) n / clusters;
        // the observed spread of season means already contains within-season
        // noise, averaged down by the draws per season; strip it out to get the
        // component no amount of extra slots or worlds can touch
        double varBetween = Math.max(0, betweenObserved - varWithin / perCluster);

        return new Paired(name, mean, bar, seNaive, seSeason, wins, n, clusters,
                varBetween, varWithin, perCluster);
    }

    /**
     * Student-t quantiles, computed rather than typed out of a table.
     *
     * A table would be fine for the two quantiles the headline uses, but the
     * pairwise matrix needs an arbitrary one - a Bonferroni correction over
     * thirty-six pairs asks for the 99.93rd percentile, which no printed table
     * carries - and a table cannot be asked. The density is elementary, so this
     * integrates it under t = tan(theta), which maps the infinite tail onto a
     * finite interval, and bisects for the quantile.
     *
     * Five seasons means FOUR degrees of freedom and a two-sided 95% multiplier
     * of 2.78, not the 1.96 a normal approximation would use. That is a 42%
     * wider bar, which at this sample size is not a rounding detail.
     */
    private static final Map<String, Double> QUANTILES = new HashMap<>();

    public static synchronized double tQuantile(double p, int df){
        if(df < 1){
            return Double.POSITIVE_INFINITY;
        }
        String key = p + "/" + df;
        Double cached = QUANTILES.get(key);
        if(cached != null){
            return cached;
        }
        double low = 0;
        double high = 1;
        while(tCdf(high, df) < p && high < 1e9){
            high *= 2;
        }
        // 80 halvings takes an interval of order 100 below 1e-22; more is waste
        for(int step = 0; step < 80; step++){
            double middle = (low + high) / 2;
            if(tCdf(middle, df) < p){
                low = middle;
            }
            else {
                high = middle;
            }
        }
        double answer = (low + high) / 2;
        QUANTILES.put(key, answer);
        return answer;
    }

    /** P(T <= t) for t >= 0, by Simpson's rule under the tangent substitution. */
    static double tCdf(double t, int df){
        return 0.5 + halfMass(Math.atan(t), df) / (2 * total(df));
    }

    /**
     * The whole right half's mass, which normalises every CDF call at this df -
     * memoised because the bisection asks for it thousands of times and it is
     * the same integral every time.
     */
    private static final Map<Integer, Double> TOTAL = new HashMap<>();

    private static synchronized double total(int df){
        return TOTAL.computeIfAbsent(df, d -> halfMass(Math.PI / 2, d));
    }

    /** The unnormalised density integrated from 0 to tan(upper). */
    private static double halfMass(double upper, int df){
        int steps = 4_000;
        double width = upper / steps;
        double total = 0;
        for(int i = 0; i <= steps; i++){
            double theta = i * width;
            double secant = 1 / Math.cos(theta);
            double t = Math.tan(theta);
            double value = Math.pow(1 + t * t / df, -(df + 1) / 2.0) * secant * secant;
            if(!Double.isFinite(value)){
                value = 0;                       // theta -> pi/2, the tail is dead
            }
            total += value * (i == 0 || i == steps ? 1 : (i % 2 == 1 ? 4 : 2));
        }
        return total * width / 3;
    }

    /** Two-sided 95%: four degrees of freedom asks 2.78, not 1.96. */
    public static double t975(int df){
        return tQuantile(0.975, df);
    }

    /** Student-t 80% quantile, the power term in a minimum detectable effect. */
    public static double t80(int df){
        return tQuantile(0.80, df);
    }

    /**
     * The bar a challenger has to clear: an effect this size is caught by this
     * design 80% of the time and called significant at 95%. Bigger than the
     * significance bar on purpose - a gap that only just clears significance is
     * a gap this design usually misses.
     */
    public static double minimumDetectable(double seSeason, int clusters){
        return (t975(clusters - 1) + t80(clusters - 1)) * seSeason;
    }

    // ----------------------------------------------------------------- runner

    public static void main(String[] args) throws Exception {
        long started = System.currentTimeMillis();

        Map<String, PlanBacktest.Board> boards = new LinkedHashMap<>();
        for(File file : new File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                String season = file.getName().split("-")[3];
                PlanBacktest.Board board = PlanBacktest.board(file, season);
                if(board != null && board.ids().size() > 150){
                    // seasons are DISCOVERED, never listed: a harvest that adds
                    // 2018-2020 or reaches back to 2009 widens this loop and
                    // every degree of freedom below follows it
                    boards.put(season, regraded(board, GRADER));
                }
            }
        }
        List<String> seasons = new ArrayList<>(boards.keySet());
        seasons.sort(Comparator.naturalOrder());
        if(seasons.isEmpty()){
            System.out.println("no seasons to backtest");
            return;
        }

        PlanBacktest.streamedDefencePerWeek();   // warm the memo before threads
        DisplacementModel displacement = PickDisplacement.fitThroughSeason(
                AAAConfiguration.getInstance(),
                Integer.parseInt(AAAConfiguration.getInstance().getSeason()) - 1);

        // per-season objects, built once and shared by every slot and world
        Map<String, List<OutcomeDistributions.Season>> bySeason = OutcomeDistributions.all();
        Map<String, SeasonModel> models = new LinkedHashMap<>();
        for(String season : seasons){
            PlanBacktest.Board board = boards.get(season);
            RosterValue value = null;
            if(!NO_POLICY){
                // LEAVE ONE OUT: judging 2023 uses distributions built from the
                // other four seasons only, so the policy never sees the season
                // it is being scored on.
                Map<String, List<OutcomeDistributions.Season>> pool =
                        PolicyBacktest.poolWithout(bySeason, season);
                Map<String, Integer> tierOf = new HashMap<>();
                Map<Position, Integer> next = new EnumMap<>(Position.class);
                for(String id : board.ids()){
                    tierOf.put(id, (next.merge(board.positionOf().get(id), 1, Integer::sum) - 1)
                            / WeeklyStarterValue.TIER);
                }
                Map<String, Double> expected = WeeklyStarterValue.expectedFromRank(
                        board.ids(), board.positionOf(), pool);
                value = new WeeklyStarterValue(board.positionOf(), tierOf, pool,
                        PolicyBacktest.wireFrom(pool), expected, SCENARIOS, 424_242L);
            }
            models.put(season, new SeasonModel(board, value));
        }

        reproductionCheck(models, seasons, bySeason);

        // the draws, and the opponent world each one lives in - drawn ONCE and
        // shared by every strategy, which is what makes the comparison paired
        List<Draw> draws = new ArrayList<>();
        Map<String, List<String>> worlds = new LinkedHashMap<>();
        for(String season : seasons){
            for(int world = 0; world < SEEDS; world++){
                worlds.put(season + "#" + world, opponentOrder(boards.get(season).ids(),
                        boards.get(season).positionOf(), displacement, JITTER,
                        worldSeed(season, world)));
                for(int slot = 1; slot <= TEAMS; slot++){
                    draws.add(new Draw(season, slot, world));
                }
            }
        }
        opponentRealism(worlds, models, seasons);

        Map<Integer, int[]> picksBySlot = new HashMap<>();
        for(int slot = 1; slot <= TEAMS; slot++){
            picksBySlot.put(slot, picksFor(slot, TEAMS, ROUNDS, KEEPER_ROUNDS));
        }

        List<String> names = new ArrayList<>(PlanBacktest.STRATEGIES.keySet());
        if(!NO_POLICY){
            names.add("starter-sum POLICY");
        }
        double[][] scores = new double[names.size()][draws.size()];
        IntStream.range(0, draws.size()).parallel().forEach(k -> {
            Draw draw = draws.get(k);
            SeasonModel model = models.get(draw.season());
            int[] picks = picksBySlot.get(draw.slot());
            List<String> order = worlds.get(draw.season() + "#" + draw.world());
            for(int s = 0; s < names.size(); s++){
                String name = names.get(s);
                List<String> roster = name.equals("starter-sum POLICY")
                        ? draftPolicy(model, picks, order)
                        : draftSequence(model.board, picks, order,
                                PlanBacktest.STRATEGIES.get(name));
                scores[s][k] = PlanBacktest.seasonPoints(model.board, roster);
            }
        });

        int[] clusterOf = new int[draws.size()];
        for(int k = 0; k < draws.size(); k++){
            clusterOf[k] = seasons.indexOf(draws.get(k).season());
        }
        int baseline = names.indexOf(BASELINE);
        report(names, scores, draws, clusterOf, seasons, baseline,
                oldInstrument(names, models, seasons, baseline),
                System.currentTimeMillis() - started);
    }

    /**
     * The anti-footgun check, run every time.
     *
     * With the opponents' jitter switched off and the seat set to 7, this
     * harness IS PlanBacktest, so every strategy must score exactly what
     * PlanBacktest scores. If it does not, the generalisation of the schedule or
     * the opponent loop has drifted and every number below is worthless. A
     * -Pdeviate default of 1e9 once let a backtest quietly replay the plan it
     * was testing; a check that fails loudly is the cheapest defence there is.
     */
    static void reproductionCheck(Map<String, SeasonModel> models, List<String> seasons,
                                  Map<String, List<OutcomeDistributions.Season>> bySeason){
        int[] slotSeven = picksFor(MY_SLOT, TEAMS, ROUNDS, KEEPER_ROUNDS);
        if(!Arrays.equals(slotSeven, PlanBacktest.MY_PICKS)){
            throw new IllegalStateException("slot 7 schedule drifted: "
                    + Arrays.toString(slotSeven) + " vs "
                    + Arrays.toString(PlanBacktest.MY_PICKS));
        }
        for(String season : seasons){
            SeasonModel model = models.get(season);
            PlanBacktest.Board board = model.board;
            List<String> plain = opponentOrder(board.ids(), board.positionOf(), null, 0, 0);
            for(Map.Entry<String, String> entry : PlanBacktest.STRATEGIES.entrySet()){
                double here = PlanBacktest.seasonPoints(board,
                        draftSequence(board, slotSeven, plain, entry.getValue()));
                double there = PlanBacktest.score(board, entry.getValue());
                if(Math.abs(here - there) > 1e-6){
                    throw new IllegalStateException("harness disagrees with PlanBacktest on "
                            + season + " / " + entry.getKey() + ": " + here + " vs " + there);
                }
            }
            if(model.value != null){
                double here = PlanBacktest.seasonPoints(board,
                        draftPolicy(model, slotSeven, plain));
                double there = PolicyBacktest.runPolicy(board,
                        PolicyBacktest.poolWithout(bySeason, season), SCENARIOS,
                        new ArrayList<>());
                if(Math.abs(here - there) > 1e-6){
                    throw new IllegalStateException("policy disagrees with PolicyBacktest on "
                            + season + ": " + here + " vs " + there);
                }
            }
        }
        System.out.printf("harness check: at jitter 0, slot 7, every strategy%s reproduces"
                + " its old%nsingle-slot score exactly (%d seasons x %d strategies).%n",
                NO_POLICY ? "" : " and the policy", seasons.size(),
                PlanBacktest.STRATEGIES.size() + (NO_POLICY ? 0 : 1));
    }

    /**
     * What the perturbation actually does to a board, so nobody has to take the
     * opponent model on faith.
     *
     * The two numbers to look at are how far players move (against a real
     * draft, where a mid-board player routinely swings twenty selections) and
     * when the first quarterback goes. This league lets quarterbacks fall about
     * sixteen selections past par, and if the jittered worlds do not show that,
     * the displacement model is not being applied the way it was fitted.
     */
    static void opponentRealism(Map<String, List<String>> worlds,
                                Map<String, SeasonModel> models, List<String> seasons){
        double moved = 0;
        double firstQb = 0;
        double firstQbFlat = 0;
        int counted = 0;
        for(String season : seasons){
            PlanBacktest.Board board = models.get(season).board;
            List<String> flat = opponentOrder(board.ids(), board.positionOf(), null, 0, 0);
            Map<String, Integer> par = new HashMap<>();
            for(int i = 0; i < flat.size(); i++){
                par.put(flat.get(i), i);
            }
            for(int i = 0; i < flat.size(); i++){
                if(board.positionOf().get(flat.get(i)) == Position.QB){
                    firstQbFlat += i + 1;
                    break;
                }
            }
            for(int world = 0; world < SEEDS; world++){
                List<String> order = worlds.get(season + "#" + world);
                double sum = 0;
                int top = Math.min(100, order.size());
                for(int i = 0; i < order.size(); i++){
                    if(par.get(order.get(i)) < top){
                        sum += Math.abs(i - par.get(order.get(i)));
                    }
                }
                moved += sum / top;
                for(int i = 0; i < order.size(); i++){
                    if(board.positionOf().get(order.get(i)) == Position.QB){
                        firstQb += i + 1;
                        break;
                    }
                }
                counted++;
            }
        }
        System.out.printf("opponent worlds: a top-100 player moves %.1f selections on"
                + " average;%nthe first quarterback goes at %.0f against %.0f on the flat"
                + " ADP board.%n", moved / counted, firstQb / counted,
                firstQbFlat / seasons.size());
    }

    /**
     * The bar the OLD design could see: five seasons, seat 7, one fixed
     * sequence of opponent picks. Exactly the five numbers PlanBacktest and
     * PolicyBacktest print, run through the same clustered arithmetic, so the
     * two instruments can be compared on one line instead of by eye.
     *
     * @return the median 95% bar across the challengers, under the old design
     */
    static double oldInstrument(List<String> names, Map<String, SeasonModel> models,
                                List<String> seasons, int baseline){
        int[] slotSeven = picksFor(MY_SLOT, TEAMS, ROUNDS, KEEPER_ROUNDS);
        double[][] scores = new double[names.size()][seasons.size()];
        for(int s = 0; s < seasons.size(); s++){
            SeasonModel model = models.get(seasons.get(s));
            List<String> plain = opponentOrder(model.board.ids(), model.positionOf,
                    null, 0, 0);
            for(int g = 0; g < names.size(); g++){
                List<String> roster = names.get(g).equals("starter-sum POLICY")
                        ? draftPolicy(model, slotSeven, plain)
                        : draftSequence(model.board, slotSeven, plain,
                                PlanBacktest.STRATEGIES.get(names.get(g)));
                scores[g][s] = PlanBacktest.seasonPoints(model.board, roster);
            }
        }
        int[] clusterOf = new int[seasons.size()];
        for(int s = 0; s < seasons.size(); s++){
            clusterOf[s] = s;
        }
        List<Double> bars = new ArrayList<>();
        for(int g = 0; g < names.size(); g++){
            if(g == baseline || OUT_OF_DOMAIN.contains(names.get(g))){
                continue;                        // like for like with the new bar
            }
            double[] diff = new double[seasons.size()];
            for(int s = 0; s < seasons.size(); s++){
                diff[s] = scores[g][s] - scores[baseline][s];
            }
            bars.add(paired(names.get(g), scores[g], diff, clusterOf,
                    seasons.size()).bar());
        }
        bars.sort(Comparator.naturalOrder());
        return bars.get(bars.size() / 2);
    }

    /**
     * Which orderings survive, and which were noise.
     *
     * A ranking is a claim about every pair, not just each row against the
     * baseline, so this prints the whole matrix. Every cell is the row's paired
     * advantage over the column, marked only where it clears that pair's OWN
     * clustered 95% bar - the bar differs by pair, because two strategies that
     * move together across seasons are far easier to separate than two that do
     * not.
     *
     * With this many rows there are dozens of pairs and about one in twenty
     * would clear 95% by chance alone, so a Bonferroni bar is printed beside
     * it: that is the honest threshold for reading the table as a whole rather
     * than for one pair chosen in advance.
     */
    static void pairwise(List<String> names, double[][] scores, int[] clusterOf,
                         int clusters){
        int rows = names.size();
        int comparisons = rows * (rows - 1) / 2;
        System.out.printf("%n%nWHICH ORDERINGS SURVIVE%n");
        System.out.printf("row minus column, in points. + / - marks a gap past that"
                + " pair's own 95%%%nclustered bar; upper case marks one that also"
                + " survives Bonferroni over all %d pairs.%n%n", comparisons);
        System.out.printf("%-24s", "");
        for(int c = 0; c < rows; c++){
            System.out.printf(" %5d", c + 1);
        }
        System.out.println();
        for(int r = 0; r < rows; r++){
            String label = (OUT_OF_DOMAIN.contains(names.get(r)) ? "! " : "")
                    + names.get(r);
            System.out.printf("%2d %-21s", r + 1,
                    label.length() > 21 ? label.substring(0, 21) : label);
            for(int c = 0; c < rows; c++){
                if(r == c){
                    System.out.printf(" %5s", ".");
                    continue;
                }
                double[] diff = new double[scores[r].length];
                for(int k = 0; k < diff.length; k++){
                    diff[k] = scores[r][k] - scores[c][k];
                }
                Paired cell = paired("", scores[r], diff, clusterOf, clusters);
                // Bonferroni splits the 5% across every pair, which moves the
                // QUANTILE, not the standard error
                double strict = cell.seSeason()
                        * tQuantile(1 - 0.05 / (2.0 * comparisons), clusters - 1);
                String mark = Math.abs(cell.diff()) <= cell.bar() ? " "
                        : Math.abs(cell.diff()) > strict
                                ? (cell.diff() > 0 ? "+" : "-")
                                : (cell.diff() > 0 ? "*" : "~");
                System.out.printf(" %4.0f%s", cell.diff(), mark);
            }
            System.out.println();
        }
        System.out.printf("%n   + / -  past this pair's 95%% bar AND past the"
                + " all-pairs bar%n   * / ~  past its own 95%% bar only - one of"
                + " these is expected by chance%n   blank  indistinguishable at this"
                + " sample size%n   !      out of domain: not a fair entrant, read"
                + " neither its row nor its column%n");
    }

    /** Neither the baseline itself nor a row that is outside its own domain. */
    static boolean fairEntrant(Paired row){
        return !row.name().equals(BASELINE) && !OUT_OF_DOMAIN.contains(row.name());
    }

    static void report(List<String> names, double[][] scores, List<Draw> draws,
                       int[] clusterOf, List<String> seasons, int baseline,
                       double oldBar, long millis){
        int n = draws.size();
        double[] base = scores[baseline];

        System.out.printf("%nPOWER BACKTEST - how big does a gap have to be to mean"
                + " anything?%n");
        System.out.printf("%d seasons x %d slots x %d opponent worlds = %d paired draws,"
                + " jitter %.2f%n", seasons.size(), TEAMS, SEEDS, n, JITTER);
        System.out.printf("baseline: %s. every strategy sees the identical draws"
                + " (common random numbers).%n%n", BASELINE);

        System.out.printf("%-24s %7s %8s %8s %8s %9s %8s%n", "STRATEGY", "mean",
                "vs RUN", "SE(iid)", "SE(seas)", "95% bar", "wins");

        List<Paired> rows = new ArrayList<>();
        for(int s = 0; s < names.size(); s++){
            double[] diff = new double[n];
            for(int k = 0; k < n; k++){
                diff[k] = scores[s][k] - base[k];
            }
            Paired row = paired(names.get(s), scores[s], diff, clusterOf, seasons.size());
            rows.add(row);
            System.out.printf("%-24s %7.0f %+8.0f %8.1f %8.1f %9.0f %7.0f%%%s%s%n",
                    row.name(), row.mean(), row.diff(), row.seNaive(), row.seSeason(),
                    row.bar(), 100.0 * row.wins() / row.draws(),
                    s == baseline ? "   <- baseline" : (row.real() ? "   REAL" : ""),
                    OUT_OF_DOMAIN.contains(row.name()) ? "   [out of domain]" : "");
        }
        System.out.printf("%n[out of domain] Model A's objective is the best legal NINE,"
                + " and two keepers plus%nseven picks fill those slots exactly - from"
                + " round 8 it is indifferent and its%noutput is an artifact. The row is"
                + " scored because its error bar is a real reading%non this instrument,"
                + " but it is not a fair entrant and must not anchor anything.%nThe"
                + " legitimate mixed row is 'ModelA front + SS back', Model A for the"
                + " front seven.%n");

        // Justin's own seat, on its own, because that is the draft he sits in
        System.out.printf("%nSLOT %d ONLY (Justin's seat), %d draws:%n", MY_SLOT,
                (int) draws.stream().filter(d -> d.slot() == MY_SLOT).count());
        System.out.printf("%-24s %7s %8s %8s %9s%n", "STRATEGY", "mean", "vs RUN",
                "SE(seas)", "95% bar");
        List<Integer> mine = new ArrayList<>();
        for(int k = 0; k < n; k++){
            if(draws.get(k).slot() == MY_SLOT){
                mine.add(k);
            }
        }
        for(int s = 0; s < names.size(); s++){
            double[] score = new double[mine.size()];
            double[] diff = new double[mine.size()];
            int[] cluster = new int[mine.size()];
            for(int i = 0; i < mine.size(); i++){
                score[i] = scores[s][mine.get(i)];
                diff[i] = scores[s][mine.get(i)] - base[mine.get(i)];
                cluster[i] = clusterOf[mine.get(i)];
            }
            Paired row = paired(names.get(s), score, diff, cluster, seasons.size());
            System.out.printf("%-24s %7.0f %+8.0f %8.1f %9.0f%s%s%n", row.name(),
                    row.mean(), row.diff(), row.seSeason(), row.bar(),
                    s == baseline ? "   <- baseline" : (row.real() ? "   REAL" : ""),
                    OUT_OF_DOMAIN.contains(row.name()) ? "   [out of domain]" : "");
        }

        // the headline: the typical bar across the FAIR challengers
        double[] bars = rows.stream().filter(PowerBacktest::fairEntrant)
                .mapToDouble(Paired::bar).sorted().toArray();
        double[] mde = rows.stream().filter(PowerBacktest::fairEntrant)
                .mapToDouble(r -> minimumDetectable(r.seSeason(), r.clusters()))
                .sorted().toArray();
        double medianBar = bars[bars.length / 2];
        double medianMde = mde[mde.length / 2];

        System.out.printf("%n%nMINIMUM DETECTABLE DIFFERENCE%n");
        System.out.printf("The number a challenger must beat the RUNBOOK by before we"
                + " are entitled%nto believe it. Clustered on season, %d degrees of"
                + " freedom.%n%n", seasons.size() - 1);
        System.out.printf("   significance bar (95%%, two-sided)   %6.0f points"
                + "   [range %.0f - %.0f]%n", medianBar, bars[0], bars[bars.length - 1]);
        System.out.printf("   detectable at 80%% power             %6.0f points"
                + "   [range %.0f - %.0f]%n", medianMde, mde[0], mde[mde.length - 1]);
        System.out.printf("%n   the OLD instrument's bar            %6.0f points"
                + "   (5 seasons, seat 7, fixed opponents)%n", oldBar);
        System.out.printf("   this design is %.1fx sharper.%n", oldBar / medianBar);
        System.out.printf("%nEvery gap the repo has ranked strategies on - 30 to 150"
                + " points - sat inside the%nold bar. Nothing smaller than the new one"
                + " is evidence either.%n");

        System.out.printf("%nWHERE THE UNCERTAINTY LIVES%n");
        System.out.printf("%-24s %10s %10s %10s %10s%n", "STRATEGY", "sd(season)",
                "sd(draw)", "SE now", "SE floor");
        for(Paired row : rows){
            if(row.name().equals(BASELINE)){
                continue;
            }
            System.out.printf("%-24s %10.0f %10.0f %10.1f %10.1f%n", row.name(),
                    Math.sqrt(row.varBetween()), Math.sqrt(row.varWithin()),
                    row.seSeason(), row.seFloor());
        }
        System.out.printf("%nsd(season) is the part of a paired difference that is a"
                + " property of the%nfootball year itself; sd(draw) is seat and"
                + " opponent luck. Slots and worlds%naverage the second down and cannot"
                + " touch the first, so 'SE floor' is what this%ndesign would still have"
                + " with infinitely many slots and opponent worlds on%nthese five"
                + " seasons.%n");

        System.out.printf("%nHOW MANY SEASONS WOULD IT TAKE?%n");
        System.out.printf("bar to call a gap real, at %d slots x %d worlds a season:%n",
                TEAMS, SEEDS);
        // anchored on what is actually loaded, not on a typed list, so a
        // harvest that widens the data widens this row with it
        int have = seasons.size();
        int[] counts = {have, have + 3, 2 * have, 2 * have + 4, 3 * have + 2};
        long fair = rows.stream().filter(PowerBacktest::fairEntrant).count();
        System.out.printf("   %-10s", "seasons");
        for(int seasonCount : counts){
            System.out.printf(" %8d", seasonCount);
        }
        System.out.println();
        System.out.printf("   %-10s", "95% bar");
        for(int seasonCount : counts){
            double se = 0;
            for(Paired row : rows){
                if(fairEntrant(row)){
                    se += row.seAt(seasonCount, (int) row.perCluster()) / fair;
                }
            }
            System.out.printf(" %8.0f", t975(seasonCount - 1) * se);
        }
        System.out.println();
        System.out.printf("%n(FantasyPros half-PPR ADP starts in 2018 and Sleeper actuals"
                + " reach 2009, so%neight clean seasons and up to seventeen with a"
                + " measured format proxy are%nreachable - that column is the return on"
                + " the harvest.)%n");

        pairwise(names, scores, clusterOf, seasons.size());

        System.out.printf("%nran in %.1f s on %d cores.%n", millis / 1000.0,
                Runtime.getRuntime().availableProcessors());
    }
}
