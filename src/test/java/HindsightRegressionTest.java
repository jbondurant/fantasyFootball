import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TRAPS.md section C: hindsight. The three ways this repo has graded a decision
 * with information the decider could not have had.
 *
 *   C12  a lineup filled by REALISED points instead of expected ones
 *   C13  a waiver-wire rate set by sorting realised outcomes and keeping the best
 *   C14  the same fault louder - a MAX over undrafted players
 *
 * Every one of them was caught by a person noticing an odd number days later.
 * These fail the moment the mistake comes back.
 *
 * The fixtures are built so honesty and hindsight give DIFFERENT answers and the
 * honest one is exact, because a test that both paths pass proves nothing. Each
 * assertion below also carries its own non-vacuity check: a scenario in which
 * the hindsight answer really would have been larger.
 */
class HindsightRegressionTest {

    // =====================================================================
    // C12. Fill by EXPECTED, score on REALISED.
    // =====================================================================

    /**
     * PlanBacktest.seasonPoints sorts the week's candidates by PRESEASON board
     * rank and then counts what they actually scored.
     *
     * The board here is rigged against it: the higher-ranked quarterback scores
     * 10 a week and the lower-ranked one scores 40. A manager choosing on
     * preseason rank collects 10 every week; only a manager reading the box
     * score before setting his lineup collects 40. If this test ever returns
     * 720 instead of 180, the start/sit hindsight is back - the same bug that
     * produced a third-round quarterback and three defences.
     */
    @Test
    void aWeeklyLineupIsSetOnPreseasonRankAndNotOnTheWeeksResult(){
        PlanBacktest.Board board = board(
                Map.of("qbA", Position.QB, "qbB", Position.QB, "def", Position.DEF),
                List.of("qbA", "qbB", "def"),
                Map.of("qbA", 10.0, "qbB", 40.0, "def", 0.0));

        double scored = PlanBacktest.seasonPoints(board, List.of("qbA", "qbB", "def"));

        assertEquals(18 * 10.0, scored, 1e-9,
                "the lineup started the higher-ranked quarterback, so it must collect"
                        + " his 10 a week - 720 means it read the future");
        // and the fixture really does punish honesty, or the assertion is empty
        assertEquals(18 * 40.0,
                PlanBacktest.seasonPoints(board, List.of("qbB", "def")), 1e-9,
                "qbB alone scores 720, so 180 above is a choice and not an accident");
    }

    /**
     * The hindsight fill EXISTS, is reachable, and is not what the default does.
     *
     * Added 2026-09-01 with the three-argument seasonPoints. The test above
     * proves the shipped scorer collects 180 on a board rigged against it; this
     * proves 720 was actually available, from the same code, on the same board,
     * by flipping one argument. Without it, "the honest fill scores 180" is
     * consistent with a scorer that simply cannot count higher.
     */
    @Test
    void theShippedScorerIsTheHonestArmAndTheHindsightArmIsReachable(){
        PlanBacktest.Board board = board(
                Map.of("qbA", Position.QB, "qbB", Position.QB, "def", Position.DEF),
                List.of("qbA", "qbB", "def"),
                Map.of("qbA", 10.0, "qbB", 40.0, "def", 0.0));
        List<String> roster = List.of("qbA", "qbB", "def");

        assertEquals(PlanBacktest.seasonPoints(board, roster),
                PlanBacktest.seasonPoints(board, roster, false), 1e-9,
                "the two-argument scorer must BE the honest arm - if this ever fails,"
                        + " every published number was computed by the other one");
        assertEquals(18 * 10.0, PlanBacktest.seasonPoints(board, roster, false), 1e-9);
        assertEquals(18 * 40.0, PlanBacktest.seasonPoints(board, roster, true), 1e-9,
                "the hindsight arm must reach 720, or ScorerHonestyAudit is"
                        + " measuring a premium against a scorer that cannot cheat");
    }

    /**
     * HINDSIGHT CAN NEVER BE WORTH LESS THAN HONESTY, on any board.
     *
     * This is the property that makes the audit's number readable. The honest
     * fill picks one feasible lineup; the hindsight fill picks the best-scoring
     * feasible lineup, so the gap is bounded below by zero by construction. A
     * negative premium would mean the two arms are not scoring the same roster
     * under the same lineup rules and the audit's verdict means nothing.
     *
     * Randomised over 200 boards rather than asserted on one, because the flex
     * slots are where a per-position greedy could go wrong and a single fixture
     * would not find it. The non-vacuity check at the end is the usual one: a
     * run where the premium was zero everywhere would pass trivially.
     */
    @Test
    void theHindsightFillIsNeverWorseThanTheHonestOneOnAnyBoard(){
        java.util.Random random = new java.util.Random(20260901L);
        int strictlyBetter = 0;
        for(int trial = 0; trial < 200; trial++){
            Map<String, Position> positionOf = new HashMap<>();
            List<String> byRank = new ArrayList<>();
            List<String> roster = new ArrayList<>();
            Position[] spread = {Position.QB, Position.QB, Position.RB, Position.RB,
                    Position.RB, Position.RB, Position.WR, Position.WR, Position.WR,
                    Position.WR, Position.WR, Position.TE, Position.TE, Position.DEF};
            for(int man = 0; man < spread.length; man++){
                String id = "m" + man;
                positionOf.put(id, spread[man]);
                byRank.add(id);
                roster.add(id);
            }
            List<Map<String, Double>> weekly = new ArrayList<>();
            for(int week = 0; week < WeeklyActuals.WEEKS; week++){
                Map<String, Double> points = new HashMap<>();
                for(String id : byRank){
                    // a fifth of the league misses any given week, so the
                    // availability channel is exercised too
                    if(random.nextInt(5) > 0){
                        points.put(id, random.nextDouble() * 40);
                    }
                }
                weekly.add(points);
            }
            PlanBacktest.Board board =
                    new PlanBacktest.Board("fixture", byRank, positionOf, weekly);

            double honest = PlanBacktest.seasonPoints(board, roster, false);
            double cheating = PlanBacktest.seasonPoints(board, roster, true);
            assertTrue(cheating >= honest - 1e-9,
                    "trial " + trial + ": reading the future scored " + cheating
                            + " against " + honest + " for not reading it, so the two"
                            + " arms are not scoring the same roster");
            if(cheating > honest + 1e-9){
                strictlyBetter++;
            }
        }
        assertTrue(strictlyBetter > 150,
                "the fixtures must be ones where hindsight actually pays or the"
                        + " assertion above is vacuous; it paid in " + strictlyBetter
                        + " of 200");
    }

    /**
     * The same property one layer down, in the objective LiveLateRounds runs.
     *
     * WeeklyStarterValue.oneWeek sorts the men who are up by Draw::expected and
     * counts Draw::points. A roster holding a backup quarterback must therefore
     * score EXACTLY what the same roster without him scores, in every drawn
     * scenario - because there is one quarterback slot and the starter is always
     * the one expected to be better.
     *
     * A fill that sorted on points would take max(starter, backup) instead, and
     * the second assertion proves that is a real difference on this fixture:
     * there are scenarios where the backup outscored the starter (through the
     * season draw - his drawn season a boom, the starter's a bust).
     */
    @Test
    void aBackupQuarterbackNeverStartsOnAWeekHeHappenedToWin(){
        int scenarios = 400;
        WeeklyStarterValue value = twoQuarterbacks(scenarios, 20260831L);

        List<String> alone = List.of("starter");
        List<String> both = List.of("starter", "backup");

        int backupWouldHaveWon = 0;
        for(int s = 0; s < scenarios; s++){
            assertEquals(value.oneWeek(alone, s), value.oneWeek(both, s), 1e-9,
                    "scenario " + s + ": holding a backup changed the score, so the"
                            + " fill is choosing with the week already played");
            if(value.oneWeek(List.of("backup"), s) > value.oneWeek(alone, s)){
                backupWouldHaveWon++;
            }
        }
        assertTrue(backupWouldHaveWon > scenarios / 20,
                "the fixture must contain weeks the backup won or the test is"
                        + " vacuous; it contained " + backupWouldHaveWon);
    }

    /**
     * And the sort key is EXPECTED, not merely "the first one added".
     *
     * Same two men, roster order reversed. A fill that took whoever came first
     * would change its answer; one that sorts on expected cannot.
     */
    @Test
    void theFillIsOrderedByExpectationNotByRosterOrder(){
        int scenarios = 200;
        WeeklyStarterValue value = twoQuarterbacks(scenarios, 7L);
        for(int s = 0; s < scenarios; s++){
            assertEquals(value.oneWeek(List.of("starter", "backup"), s),
                    value.oneWeek(List.of("backup", "starter"), s), 1e-9,
                    "scenario " + s + ": the lineup depends on roster order");
        }
    }

    // =====================================================================
    // C13. The waiver-wire rate.
    // =====================================================================

    /**
     * WHY THE SHIPPED ESTIMATOR CANNOT BE A POLICY, stated so it survives any
     * change of data: it does not depend on WHEN the points arrived.
     *
     * Reverse every defence's weekly series. Nobody's season total moves, so the
     * top quartile of realised rates is unchanged to the last decimal. Any
     * policy a manager could run must move, because it commits before each week
     * and a reversed season rewards the opposite commitments.
     *
     * An estimator invariant to the order of the season is not something anyone
     * did during it. That is the whole of C13 in one assertion, and unlike a
     * numeric gap it cannot be argued with.
     */
    @Test
    void theShippedWireEstimatorCannotTellTheSeasonsOrderAndSoIsNotAPolicy(){
        List<WireRateStress.DefSeason> forwards = swapHalves(false);
        List<WireRateStress.DefSeason> backwards = swapHalves(true);

        assertEquals(topQuartileOf(forwards), topQuartileOf(backwards), 1e-9,
                "the shipped estimator noticed the order of the season, which"
                        + " would make it a policy - it is not one");
        assertNotEquals(WireRateStress.form(forwards, 2), WireRateStress.form(backwards, 2),
                "an honest policy MUST notice: it commits before each week");
    }

    /**
     * And the premium is real on a board where preseason rank is uninformative:
     * the estimator that sorts on the finished season beats the manager who held
     * the best defence by ADP all year.
     *
     * That is the measured comparison - 8.75 against 6.98 a week over five real
     * seasons - reproduced here as a property rather than as two numbers.
     */
    @Test
    void sortingOnTheFinishedSeasonBeatsHoldingTheBestOneByAdp(){
        List<WireRateStress.DefSeason> free = new ArrayList<>();
        free.add(flat("adpFavourite", 0, 6.0));      // ranked first, turns out dull
        free.add(flat("quietGood", 1, 14.0));
        free.add(flat("quietBetter", 2, 16.0));
        for(int other = 3; other < 8; other++){
            free.add(flat("dull" + other, other, 5.0));
        }

        double hindsight = topQuartileOf(free);
        double heldByAdp = free.get(0).total() / 18.0;

        assertEquals(15.0, hindsight, 1e-9, "the best two of eight, after the fact");
        assertEquals(6.0, heldByAdp, 1e-9, "what the preseason favourite returned");
        assertTrue(hindsight > heldByAdp,
                "choosing with the season already run must beat choosing before it");
    }

    /**
     * Two halves that swap: the four candidates who lead early collapse late,
     * and the four who trail take over. Season totals are unaffected by
     * reversing time; any policy that chases form is not.
     */
    private static List<WireRateStress.DefSeason> swapHalves(boolean reversed){
        double[][] shape = {{24, 2}, {22, 4}, {2, 24}, {4, 22},
                {12, 12}, {11, 13}, {13, 11}, {10, 14}};
        List<WireRateStress.DefSeason> free = new ArrayList<>();
        for(int man = 0; man < shape.length; man++){
            Double[] series = new Double[WireRateStress.WEEKS];
            for(int week = 0; week < WireRateStress.WEEKS; week++){
                int half = week < WireRateStress.WEEKS / 2 ? 0 : 1;
                series[reversed ? WireRateStress.WEEKS - 1 - week : week] = shape[man][half];
            }
            free.add(new WireRateStress.DefSeason("fixture", "d" + man, "d" + man,
                    man, series));
        }
        return free;
    }

    private static double topQuartileOf(List<WireRateStress.DefSeason> free){
        List<Double> rates = new ArrayList<>();
        for(WireRateStress.DefSeason def : free){
            rates.add(def.total() / 18.0);
        }
        rates.sort(Comparator.reverseOrder());
        return WireRateStress.topQuartile(rates);
    }

    /**
     * The override exists, and it is the DEFENCE that carries it.
     *
     * WireRateStress has only measured the defence honestly; the other positions
     * keep the shipped figure. If this map ever empties, the hindsight-free path
     * has been deleted and -PhonestWire silently becomes a no-op.
     */
    @Test
    void theHindsightFreeWireRateStillExists(){
        assertNotNull(WeeklyStarterValue.HONEST_WIRE.get(Position.DEF),
                "-PhonestWire has nothing to supply: the measured hindsight-free"
                        + " defence rate has been removed from WeeklyStarterValue");
    }

    /**
     * THE UNITS CHECK, and the reason this file exists.
     *
     * WireRateStress measured the honest rate at 7.73 a week over 18 real weeks
     * (7.69 before every loader ranked defences by the source's order, TRAPS
     * #80). The constant must BE that rate. The estimator table in the tool's
     * output prints a "season" column as rate x 17, so the rate appears there as
     * 131 - and a constant written 131 / 18.0 divides a seventeen-week total by
     * an eighteen-week denominator and lands at 7.3. That is 0.4 a week, about
     * 8 points a season, in the number that decides whether a drafted defence
     * beats a streamed one.
     *
     * The figure is not typed here: it is read out of the committed output of
     * the tool that measured it, so the constant and the measurement cannot
     * drift apart without this failing.
     */
    @Test
    void theHonestWireRateIsTheRateThatWasMeasuredNotItsSeventeenWeekTotal(){
        double measured = measuredHonestRatePerWeek();

        assertEquals(measured, WeeklyStarterValue.HONEST_WIRE.get(Position.DEF), 0.01,
                "WeeklyStarterValue.HONEST_WIRE disagrees with data/"
                        + WIRE_STRESS + ", which is where the number came from."
                        + " Check the denominator: the tool's 'season' column is"
                        + " rate x 17, and the wire is priced per week over 18.");
    }

    /**
     * The gap between the two is the finding, so it is asserted rather than
     * described. 8.75 hindsight against 7.73 honest is 1.02 a week - about 17
     * points a season, which is what reversed the defence conclusion (1.06
     * before the source-order ranks of TRAPS #80).
     */
    @Test
    void theHindsightPremiumIsTheOnePointOneAWeekThatReversedTheDefenceCall(){
        double shipped = measuredShippedRatePerWeek();
        double honest = WeeklyStarterValue.HONEST_WIRE.get(Position.DEF);

        assertEquals(1.02, shipped - honest, 0.02,
                "the measured hindsight premium has moved; if that is real, the"
                        + " defence conclusion in MODEL.md needs rereading");
        assertTrue(shipped > honest, "hindsight cannot be worth less than honesty");
    }

    /**
     * The override is OFF by default, because LiveLateRounds is frozen for the
     * draft and every number on record was computed at the shipped rate.
     *
     * This is the operational constraint, asserted so a later run cannot move
     * the units under a table that is half-built.
     */
    @Test
    void theHonestWireIsOptInSoTheFrozenToolsDoNotMove(){
        assertFalse(Boolean.getBoolean("honestWire"),
                "honestWire must default off: LiveLateRounds and DraftNight are"
                        + " frozen and were verified at the shipped rate");
    }

    // =====================================================================
    // C14. The same fault louder: a MAX over undrafted players.
    // =====================================================================

    /**
     * A max is a top quartile of one. Both are hindsight; the max is simply the
     * loudest version, and it is what an earlier wire actually shipped.
     *
     * The ordering is asserted so the three estimators can never be confused for
     * one another in a table: max >= top quartile > mean, strictly, on any pool
     * with spread in it.
     */
    @Test
    void aMaxOverUndraftedPlayersIsTheSameFaultTurnedUp(){
        List<Double> rates = new ArrayList<>(List.of(19.0, 12.0, 9.0, 8.0, 7.5, 7.0, 6.0, 5.0));
        rates.sort(Comparator.reverseOrder());

        double max = rates.get(0);
        double quartile = WireRateStress.topQuartile(rates);
        double mean = rates.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        assertTrue(max > quartile, "a max must exceed the quartile it is the head of");
        assertTrue(quartile > mean, "and the quartile must exceed the band mean");
        assertEquals(15.5, quartile, 1e-9, "the best two of eight");
        assertEquals(19.0, max, 1e-9);
    }

    // =====================================================================
    // fixtures
    // =====================================================================

    static final String WIRE_STRESS = "wire-rate-stress-2026-09-04.txt";

    /** "stream on form, react after week 2   7.73   131" -> 7.73. */
    private static double measuredHonestRatePerWeek(){
        return rateFrom("stream on form, react after week 2");
    }

    /** "SHIPPED pooled top quartile   8.75   149" -> 8.75. */
    private static double measuredShippedRatePerWeek(){
        return rateFrom("SHIPPED pooled top quartile");
    }

    /**
     * The per-week column of one estimator row in the committed WireRateStress
     * output. Reading it rather than retyping it is the point: a constant copied
     * out of a tool's output becomes a lie the moment the tool is rerun, which
     * is the drift this repo keeps rediscovering.
     */
    private static double rateFrom(String label){
        Path path = Path.of("data", WIRE_STRESS);
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        }
        catch(Exception missing){
            throw new AssertionError("cannot read " + path + " - the committed"
                    + " measurement behind the wire rates is gone", missing);
        }
        Pattern number = Pattern.compile("(-?\\d+\\.\\d+)");
        for(String line : lines){
            if(line.startsWith(label)){
                Matcher matcher = number.matcher(line.substring(label.length()));
                if(matcher.find()){
                    return Double.parseDouble(matcher.group(1));
                }
            }
        }
        throw new AssertionError("no row '" + label + "' in " + path
                + " - the estimator table has been renamed or removed");
    }

    /** An 18-week board with one weekly map repeated, and preseason rank = list order. */
    private static PlanBacktest.Board board(Map<String, Position> positionOf,
                                            List<String> byRank,
                                            Map<String, Double> everyWeek){
        List<Map<String, Double>> weekly = new ArrayList<>();
        for(int week = 0; week < WeeklyActuals.WEEKS; week++){
            weekly.add(new HashMap<>(everyWeek));
        }
        return new PlanBacktest.Board("fixture", byRank, positionOf, weekly);
    }

    /**
     * Two quarterbacks off one outcome pool, always available, with enough
     * spread that the backup wins some weeks outright.
     *
     * games = 18 makes up() certain, so nothing here turns on availability - the
     * only thing under test is which of two present men the fill starts.
     */
    private static WeeklyStarterValue twoQuarterbacks(int scenarios, long seed){
        Map<String, Position> positionOf =
                Map.of("starter", Position.QB, "backup", Position.QB);
        Map<String, Integer> tierOf = Map.of("starter", 0, "backup", 0);
        List<OutcomeDistributions.Season> cell = new ArrayList<>();
        for(int k = 0; k < 12; k++){
            double mean = 12.0 + k;                 // per game
            cell.add(new OutcomeDistributions.Season("qb" + k, Position.QB, k, 18,
                    mean, mean * 0.6, mean * 18));
        }
        Map<String, List<OutcomeDistributions.Season>> pool = Map.of("QB:0", cell);
        Map<Position, Double> wire = new EnumMap<>(Position.class);
        wire.put(Position.QB, 1.0);
        // 200 against 170, not 340 against 170: a week is scored at the drawn
        // season's rate with no noise around it, so the backup can only outscore
        // the starter when his drawn season beats the starter's by more than
        // their projection gap - possible at 200/170 (about a quarter of the
        // pairs of this cell), impossible at 2:1
        Map<String, Double> expected = Map.of("starter", 200.0, "backup", 170.0);
        return new WeeklyStarterValue(positionOf, tierOf, pool, wire, expected,
                scenarios, seed);
    }

    private static WireRateStress.DefSeason flat(String name, int rank, double weekly){
        Double[] series = new Double[WireRateStress.WEEKS];
        java.util.Arrays.fill(series, weekly);
        return new WireRateStress.DefSeason("fixture", name, name, rank, series);
    }

    /** Nothing at all for six weeks, then 100 a week: invisible until it happens. */
    private static WireRateStress.DefSeason lateBloomer(String name, int rank){
        Double[] series = new Double[WireRateStress.WEEKS];
        for(int week = 0; week < WireRateStress.WEEKS; week++){
            series[week] = week < 6 ? 0.0 : 100.0;
        }
        return new WireRateStress.DefSeason("fixture", name, name, rank, series);
    }
}
