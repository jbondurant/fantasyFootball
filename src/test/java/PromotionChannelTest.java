import PlayerImportAndSetup.Position;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The arithmetic behind the bust/boom measurements, offline.
 *
 * Two properties carry most of the weight and both are tested by construction
 * rather than by inspection, because both have burned this repo before:
 *
 *   NO HINDSIGHT. A rate over weeks 1..k must not see week k+1, and a lineup
 *   rule must order men by its own key rather than by what they scored. The
 *   tests below build cases where a rule that peeked would give a different,
 *   detectable answer.
 *
 *   LEAVE-ONE-SEASON-OUT. The preseason curve a season is graded against must
 *   not contain that season. The test plants an absurd season and checks it
 *   cannot reach its own curve.
 */
class PromotionChannelTest {

    private static final Player QB1 = TestPlayers.player("Quin", "One", "AAA",
            Position.QB, 101);
    private static final Player RB1 = TestPlayers.player("Rex", "One", "AAA",
            Position.RB, 201);
    private static final Player RB2 = TestPlayers.player("Rod", "Two", "BBB",
            Position.RB, 202);
    private static final Player RB3 = TestPlayers.player("Ray", "Three", "CCC",
            Position.RB, 203);
    private static final Player WR1 = TestPlayers.player("Walt", "One", "AAA",
            Position.WR, 301);
    private static final Player WR2 = TestPlayers.player("Wes", "Two", "BBB",
            Position.WR, 302);
    private static final Player WR3 = TestPlayers.player("Wyn", "Three", "CCC",
            Position.WR, 303);
    private static final Player TE1 = TestPlayers.player("Ted", "One", "AAA",
            Position.TE, 401);
    private static final Player DEF = TestPlayers.defense("Anytown", "Aces", "AAA");

    @BeforeEach
    void indexPlayers(){
        Player.indexForTest(TestPlayers.listOf(QB1, RB1, RB2, RB3, WR1, WR2, WR3,
                TE1, DEF));
    }

    // ------------------------------------------------------------------
    // Rates: the window, and what a missed week means.
    // ------------------------------------------------------------------

    private static DetectionLag.Man man(double... weekly){
        return new DetectionLag.Man("2020", "201", Position.RB, 10.0, 1, weekly);
    }

    @Test
    void aRateReadsOnlyItsOwnWindow(){
        DetectionLag.Man rb = man(10, 10, 10, 30, 30, 30);
        Assertions.assertEquals(10.0, rb.rate(1, 3, false), 1e-9,
                "weeks 1-3 must not see the weeks after them");
        Assertions.assertEquals(30.0, rb.rate(4, 6, false), 1e-9);
        Assertions.assertEquals(20.0, rb.rate(1, 6, false), 1e-9);
    }

    @Test
    void aMissedWeekIsSkippedPerGameAndCountedPerWeek(){
        DetectionLag.Man rb = man(20, Double.NaN, 20, Double.NaN);
        Assertions.assertEquals(20.0, rb.rate(1, 4, false), 1e-9,
                "per game played, the two missed weeks are not in the denominator");
        Assertions.assertEquals(10.0, rb.rate(1, 4, true), 1e-9,
                "per week, a missed week is a zero he cost you");
        Assertions.assertEquals(2, rb.games(1, 4));
    }

    @Test
    void aManWhoNeverPlayedHasNoRateRatherThanZero(){
        DetectionLag.Man rb = man(Double.NaN, Double.NaN);
        Assertions.assertTrue(Double.isNaN(rb.rate(1, 2, false)),
                "no evidence is not the same as evidence of nothing");
        Assertions.assertEquals(0.0, rb.rate(1, 2, true), 1e-9);
    }

    // ------------------------------------------------------------------
    // The ADP curve.
    // ------------------------------------------------------------------

    @Test
    void theCurveRecoversALogLinearTruth(){
        // rate = 30 - 5 * ln(rank), sampled exactly
        List<double[]> sample = new java.util.ArrayList<>();
        for(int rank = 1; rank <= 40; rank++){
            sample.add(new double[]{rank, 30 - 5 * Math.log(rank)});
        }
        DetectionLag.Curve curve = DetectionLag.fit(sample);
        Assertions.assertEquals(30.0, curve.intercept(), 1e-6);
        Assertions.assertEquals(-5.0, curve.slope(), 1e-6);
        Assertions.assertEquals(30 - 5 * Math.log(12), curve.predict(12), 1e-6);
    }

    @Test
    void theCurveTreatsRankZeroAsRankOneRatherThanDivergingToInfinity(){
        DetectionLag.Curve curve = new DetectionLag.Curve(20, -4);
        Assertions.assertEquals(20.0, curve.predict(0), 1e-9);
        Assertions.assertEquals(20.0, curve.predict(1), 1e-9);
    }

    @Test
    void aHeldOutSeasonCannotReachItsOwnCurve(){
        Map<String, List<DetectionLag.Man>> bySeason = new TreeMap<>();
        // Two ordinary seasons, and one where every back scores absurdly.
        bySeason.put("2001", List.of(rankedMan("2001", 1, 20), rankedMan("2001", 10, 10),
                rankedMan("2001", 30, 5)));
        bySeason.put("2002", List.of(rankedMan("2002", 1, 20), rankedMan("2002", 10, 10),
                rankedMan("2002", 30, 5)));
        bySeason.put("2003", List.of(rankedMan("2003", 1, 500),
                rankedMan("2003", 10, 500), rankedMan("2003", 30, 500)));

        Map<String, Map<Position, DetectionLag.Curve>> curves =
                DetectionLag.leaveOneSeasonOut(bySeason, false);

        double predicted = curves.get("2003").get(Position.RB).predict(1);
        Assertions.assertTrue(predicted < 40,
                "the absurd season must not inflate the curve it is graded against,"
                        + " but predicted " + predicted);
        Assertions.assertTrue(curves.get("2001").get(Position.RB).predict(1) > 100,
                "the absurd season SHOULD inflate everyone else's curve - which is"
                        + " how we know it was in the fit at all");
    }

    private static DetectionLag.Man rankedMan(String season, int rank, double perGame){
        double[] weekly = new double[8];
        java.util.Arrays.fill(weekly, perGame);
        return new DetectionLag.Man(season, season + "-" + rank, Position.RB,
                rank * 3.0, rank, weekly);
    }

    // ------------------------------------------------------------------
    // The blend weight and the crossover.
    // ------------------------------------------------------------------

    @Test
    void theBlendLeansEntirelyOnWhicheverPredictorIsRight(){
        List<DetectionLag.Contest> toDateIsTruth = List.of(
                new DetectionLag.Contest("2020", Position.RB, 10, 5, 5),
                new DetectionLag.Contest("2020", Position.RB, 10, 20, 20),
                new DetectionLag.Contest("2020", Position.RB, 4, 9, 9));
        Assertions.assertEquals(1.0, DetectionLag.blendWeight(toDateIsTruth), 1e-9);

        List<DetectionLag.Contest> preseasonIsTruth = List.of(
                new DetectionLag.Contest("2020", Position.RB, 10, 5, 10),
                new DetectionLag.Contest("2020", Position.RB, 10, 20, 10),
                new DetectionLag.Contest("2020", Position.RB, 4, 9, 4));
        Assertions.assertEquals(0.0, DetectionLag.blendWeight(preseasonIsTruth), 1e-9);
    }

    @Test
    void theBlendedRuleIsNeverWorseThanEitherPredictorAlone(){
        List<DetectionLag.Contest> mixed = List.of(
                new DetectionLag.Contest("2020", Position.WR, 12, 6, 9),
                new DetectionLag.Contest("2020", Position.WR, 8, 14, 11),
                new DetectionLag.Contest("2020", Position.WR, 10, 10, 13),
                new DetectionLag.Contest("2020", Position.WR, 15, 5, 8));
        DetectionLag.Verdict verdict = DetectionLag.judge(6, mixed);
        Assertions.assertTrue(verdict.rmseBlend() <= verdict.rmsePreseason() + 1e-9);
        Assertions.assertTrue(verdict.rmseBlend() <= verdict.rmseToDate() + 1e-9);
    }

    @Test
    void theCrossoverIsTheFirstWeekSeasonToDateWins(){
        List<DetectionLag.Verdict> verdicts = List.of(
                new DetectionLag.Verdict(1, 100, 3.0, 5.0, 0.1, 2.9),
                new DetectionLag.Verdict(2, 100, 3.0, 4.0, 0.3, 2.8),
                new DetectionLag.Verdict(3, 100, 3.0, 2.5, 0.7, 2.4),
                new DetectionLag.Verdict(4, 100, 3.0, 2.0, 0.9, 1.9));
        Assertions.assertEquals(3, DetectionLag.crossover(verdicts));
        Assertions.assertEquals(3, DetectionLag.halfWeightWeek(verdicts));
    }

    @Test
    void aChannelThatNeverFiresReportsMinusOneRatherThanAWeek(){
        List<DetectionLag.Verdict> never = List.of(
                new DetectionLag.Verdict(1, 50, 3.0, 9.0, 0.05, 3.0),
                new DetectionLag.Verdict(2, 50, 3.0, 8.0, 0.05, 3.0));
        Assertions.assertEquals(-1, DetectionLag.crossover(never));
        Assertions.assertEquals(-1, DetectionLag.halfWeightWeek(never));
    }

    // ------------------------------------------------------------------
    // The lineup filler.
    // ------------------------------------------------------------------

    @Test
    void theSlotsBindSoARuleCannotStartFiveBacks(){
        List<String> roster = List.of("201", "202", "203");
        Map<String, Double> points = Map.of("201", 30.0, "202", 30.0, "203", 30.0);
        // Three backs: two fill RB, the third takes a flex. Ninety, not ninety
        // plus the empty slots.
        Assertions.assertEquals(90.0,
                LineupPromotion.fill(roster, points, id -> points.get(id), false), 1e-9);
    }

    @Test
    void unfilledSlotsScoreZeroRatherThanBorrowingFromElsewhere(){
        List<String> roster = List.of("101");
        Map<String, Double> points = Map.of("101", 25.0);
        Assertions.assertEquals(25.0,
                LineupPromotion.fill(roster, points, id -> 1.0, false), 1e-9);
    }

    @Test
    void theRuleOrdersByItsOwnKeyAndNotByWhatTheyScored(){
        List<String> roster = List.of("201", "202", "203");
        Map<String, Double> points = Map.of("201", 1.0, "202", 1.0, "203", 40.0);
        // A key that ranks 203 LAST must leave his forty points on the bench:
        // two RB slots and two flexes would seat all three, so squeeze the
        // roster to exactly the two fixed slots by making him a third back
        // behind two others and removing the flex reach - done here by giving
        // the rule only the fixed slots' worth of men at other positions.
        Map<String, Double> onlyTwoSeats = Map.of("201", 1.0, "202", 1.0, "203", 40.0);
        double byKey = LineupPromotion.fill(roster, onlyTwoSeats,
                id -> "203".equals(id) ? -100.0 : 1.0, false);
        double byPoints = LineupPromotion.fill(roster, onlyTwoSeats,
                id -> onlyTwoSeats.get(id), false);
        Assertions.assertEquals(42.0, byPoints, 1e-9,
                "hindsight seats him and collects the forty");
        Assertions.assertEquals(42.0, byKey, 1e-9,
                "with two RB slots and two flexes all three play either way");

        // Now make the choice bite. Two RB slots plus two flexes seat FOUR
        // backs, so four is still not a choice - it takes five.
        Player rb4 = TestPlayers.player("Ron", "Four", "DDD", Position.RB, 204);
        Player rb5 = TestPlayers.player("Rip", "Five", "EEE", Position.RB, 205);
        Player.indexForTest(TestPlayers.listOf(QB1, RB1, RB2, RB3, rb4, rb5, WR1,
                WR2, WR3, TE1, DEF));
        List<String> five = List.of("201", "202", "203", "204", "205");
        Map<String, Double> fivePoints = Map.of("201", 1.0, "202", 1.0, "203", 1.0,
                "204", 1.0, "205", 40.0);
        Assertions.assertEquals(4.0, LineupPromotion.fill(five, fivePoints,
                        id -> "205".equals(id) ? -100.0 : 1.0, false), 1e-9,
                "a rule that ranks the forty-point man last must not collect him");
        Assertions.assertEquals(43.0, LineupPromotion.fill(five, fivePoints,
                        id -> fivePoints.get(id), false), 1e-9,
                "and the hindsight ceiling must, leaving one of the ones out");
    }

    @Test
    void availabilityBenchesAManWhoDidNotPlay(){
        List<String> roster = List.of("201", "202", "203");
        // 203 is absent from the map entirely - he did not take the field.
        Map<String, Double> points = Map.of("201", 5.0, "202", 5.0);
        java.util.function.ToDoubleFunction<String> preferThird =
                id -> "203".equals(id) ? 100.0 : 1.0;
        Assertions.assertEquals(10.0,
                LineupPromotion.fill(roster, points, preferThird, false), 1e-9,
                "without the injury channel he is started and scores nothing");
        Assertions.assertEquals(10.0,
                LineupPromotion.fill(roster, points, preferThird, true), 1e-9,
                "with it he is benched - same total here, but he is out of the way");

        // Where it bites: a fourth back who DID play is promoted in his place.
        Player rb4 = TestPlayers.player("Ron", "Four", "DDD", Position.RB, 204);
        Player.indexForTest(TestPlayers.listOf(QB1, RB1, RB2, RB3, rb4, WR1, WR2,
                WR3, TE1, DEF));
        List<String> four = List.of("201", "202", "203", "204");
        Map<String, Double> played = Map.of("201", 5.0, "202", 5.0, "204", 7.0);
        Assertions.assertEquals(17.0,
                LineupPromotion.fill(four, played, preferThird, true), 1e-9,
                "the two RB slots and one flex fill from the men who played");
    }

    @Test
    void theStartedLineupDropsTheDefenceSlot(){
        Map<String, Double> points = Map.of("201", 12.0, "AAA", 9.0);
        Assertions.assertEquals(12.0,
                LineupPromotion.startedSkill(List.of("201", "AAA"), points), 1e-9,
                "the defence is excluded from the nine on purpose");
    }

    @Test
    void theFormKeyHoldsAugustUntilItHasSeenAGame(){
        Map<String, Integer> ranks = Map.of("201", 1);
        Map<Position, DetectionLag.Curve> curves =
                Map.of(Position.RB, new DetectionLag.Curve(10, 0));
        Map<String, double[]> toDate = new java.util.HashMap<>();

        Assertions.assertEquals(10.0,
                LineupPromotion.formKey(1.0, toDate, ranks, curves)
                        .applyAsDouble("201"), 1e-9,
                "no games seen: the board still speaks");

        toDate.put("201", new double[]{40.0, 2});     // 20 a game
        Assertions.assertEquals(20.0,
                LineupPromotion.formKey(1.0, toDate, ranks, curves)
                        .applyAsDouble("201"), 1e-9);
        Assertions.assertEquals(15.0,
                LineupPromotion.formKey(0.5, toDate, ranks, curves)
                        .applyAsDouble("201"), 1e-9);
        Assertions.assertEquals(10.0,
                LineupPromotion.formKey(0.0, toDate, ranks, curves)
                        .applyAsDouble("201"), 1e-9,
                "weight zero must reproduce preseason exactly - the sweep's control");
    }

    // ------------------------------------------------------------------
    // Transaction parsing and bucketing.
    // ------------------------------------------------------------------

    @Test
    void aTransactionRowYieldsItsAddsDropsAndBid(){
        JsonObject row = JsonParser.parseString("""
                {"status":"complete","type":"waiver","leg":3,
                 "adds":{"1234":5},"drops":{"5678":5},
                 "settings":{"waiver_bid":4,"seq":10}}
                """).getAsJsonObject();
        Assertions.assertEquals(Map.of("1234", 5),
                LeagueTransactions.idToRoster(row, "adds"));
        Assertions.assertEquals(Map.of("5678", 5),
                LeagueTransactions.idToRoster(row, "drops"));
        Assertions.assertEquals(4, LeagueTransactions.bid(row));
    }

    @Test
    void aFreeAgentPickupHasNoBidAndAFailedClaimIsStillARow(){
        JsonObject row = JsonParser.parseString("""
                {"status":"failed","type":"free_agent","leg":7,
                 "adds":null,"drops":null,"settings":null}
                """).getAsJsonObject();
        Assertions.assertEquals(0, LeagueTransactions.bid(row));
        Assertions.assertTrue(LeagueTransactions.idToRoster(row, "adds").isEmpty(),
                "a null adds map must read as empty, not throw");
        Assertions.assertFalse(new LeagueTransactions.Move("2024", 7, "free_agent",
                "failed", Map.of(), Map.of(), 0).complete());
    }

    @Test
    void theDraftBucketsFollowTheRoundsOfATwelveTeamDraft(){
        Assertions.assertEquals(0, PromotionBehaviour.bucket(1));
        Assertions.assertEquals(0, PromotionBehaviour.bucket(48));
        Assertions.assertEquals(1, PromotionBehaviour.bucket(49));
        Assertions.assertEquals(1, PromotionBehaviour.bucket(108),
                "round 9 is the last starter round");
        Assertions.assertEquals(2, PromotionBehaviour.bucket(109));

        Assertions.assertEquals(LineupPromotion.Origin.UNDRAFTED,
                LineupPromotion.origin(null));
        Assertions.assertEquals(LineupPromotion.Origin.EARLY,
                LineupPromotion.origin(48));
        Assertions.assertEquals(LineupPromotion.Origin.MIDDLE,
                LineupPromotion.origin(108));
        Assertions.assertEquals(LineupPromotion.Origin.LATE,
                LineupPromotion.origin(109));
    }

    @Test
    void theRateTiersCoverTheBoardAndStopAtItsEnd(){
        Assertions.assertEquals(0, BustBoomRates.bucket(24));
        Assertions.assertEquals(1, BustBoomRates.bucket(25));
        Assertions.assertEquals(3, BustBoomRates.bucket(108));
        Assertions.assertEquals(4, BustBoomRates.bucket(109));
        Assertions.assertEquals(5, BustBoomRates.bucket(192));
        Assertions.assertEquals(-1, BustBoomRates.bucket(193),
                "past round 16 there is no bucket - the draft is over");
    }

    @Test
    void startableDepthMatchesTheLineupTheLeagueActuallyStarts(){
        // 12 teams x (1 QB, 1 TE) with no flex reaching them; 2 RB and 3 WR plus
        // the two flexes shared out.
        Assertions.assertEquals(12, BustBoomRates.startableDepth(Position.QB));
        Assertions.assertEquals(12, BustBoomRates.startableDepth(Position.TE));
        Assertions.assertTrue(BustBoomRates.startableDepth(Position.RB) > 24);
        Assertions.assertTrue(BustBoomRates.startableDepth(Position.WR) > 36);
        Assertions.assertEquals(9 * 12,
                BustBoomRates.startableDepth(Position.QB)
                        + BustBoomRates.startableDepth(Position.RB)
                        + BustBoomRates.startableDepth(Position.WR)
                        + BustBoomRates.startableDepth(Position.TE),
                "the four depths must add up to the league's 108 skill slots");
    }

    // ------------------------------------------------------------------

    @Test
    void aSeasonIsTheBootstrapUnitSoOneSeasonGivesNoSpread(){
        Map<String, double[]> onlyOne = Map.of("2024", new double[]{100, 120, 0, 0});
        double[] draws = LineupPromotion.bootstrap(new TreeMap<>(onlyOne), 1, 0, 7L);
        for(double draw : draws){
            Assertions.assertEquals(20.0, draw, 1e-9,
                    "with one season every resample is that season");
        }
    }

    // ------------------------------------------------------------------
    // The nflverse CSV: the parser, and the scoring it feeds.
    // ------------------------------------------------------------------

    @Test
    void quotedFieldsDoNotShiftTheColumns(){
        // A display name with a comma inside quotes is the real case, and a
        // naive split would read rushing yards out of the receiving column for
        // every row after it.
        List<String> cells = NflverseWeekly.split(
                "00-0012345,\"Griffin, Robert III\",QB,2012,3,\"a \"\"quoted\"\" id\",7");
        Assertions.assertEquals(7, cells.size());
        Assertions.assertEquals("00-0012345", cells.get(0));
        Assertions.assertEquals("Griffin, Robert III", cells.get(1));
        Assertions.assertEquals("QB", cells.get(2));
        Assertions.assertEquals("a \"quoted\" id", cells.get(5));
        Assertions.assertEquals("7", cells.get(6));
    }

    @Test
    void anEmptyTrailingFieldIsStillAField(){
        List<String> cells = NflverseWeekly.split("a,,c,");
        Assertions.assertEquals(List.of("a", "", "c", ""), cells);
    }

    @Test
    void naAndBlankReadAsZeroRatherThanThrowing(){
        List<String> cells = List.of("NA", "", "12.5", "oops");
        Map<String, Integer> column = Map.of("na", 0, "blank", 1, "real", 2,
                "junk", 3);
        Assertions.assertEquals(0.0, NflverseWeekly.number(cells, column, "na"), 1e-9);
        Assertions.assertEquals(0.0, NflverseWeekly.number(cells, column, "blank"), 1e-9);
        Assertions.assertEquals(12.5, NflverseWeekly.number(cells, column, "real"), 1e-9);
        Assertions.assertEquals(0.0, NflverseWeekly.number(cells, column, "junk"), 1e-9);
        Assertions.assertEquals(0.0, NflverseWeekly.number(cells, column, "absent"), 1e-9);
    }

    @Test
    void aStatLineIsScoredFromComponentsAndNotFromTheFilesOwnFantasyPoints(){
        // 300 passing yards, 3 passing touchdowns, 1 interception, under the
        // generic 4-point-touchdown fallback: 12 + 12 - 1 = 23.
        List<String> cells = new java.util.ArrayList<>(
                java.util.Collections.nCopies(6, "0"));
        Map<String, Integer> column = Map.of("passing_yards", 0, "passing_tds", 1,
                "passing_interceptions", 2, "receptions", 3,
                "rushing_fumbles", 4, "rushing_fumbles_lost", 5);
        cells.set(0, "300");
        cells.set(1, "3");
        cells.set(2, "1");
        LeagueScoringSettings fourPointTouchdowns =
                LeagueScoringSettings.defaultScoringSettings();
        Assertions.assertEquals(23.0,
                NflverseWeekly.score(cells, column, fourPointTouchdowns), 1e-9);
    }

    @Test
    void aLostFumbleIsChargedTwiceBecauseItIsAlsoAFumble(){
        List<String> cells = new java.util.ArrayList<>(
                java.util.Collections.nCopies(6, "0"));
        Map<String, Integer> column = Map.of("passing_yards", 0, "passing_tds", 1,
                "passing_interceptions", 2, "receptions", 3,
                "rushing_fumbles", 4, "rushing_fumbles_lost", 5);
        cells.set(4, "1");
        cells.set(5, "1");
        LeagueScoringSettings settings = LeagueScoringSettings.defaultScoringSettings();
        double both = NflverseWeekly.score(cells, column, settings);
        cells.set(5, "0");
        double onlyLoose = NflverseWeekly.score(cells, column, settings);
        Assertions.assertEquals(settings.fumbleLost, both - onlyLoose, 1e-9,
                "losing it must cost the lost-fumble charge ON TOP of the fumble"
                        + " charge, which is how LeagueActuals.scoreSkill reads it");
    }

    @Test
    void halfAPointAReceptionReachesTheTotal(){
        List<String> cells = new java.util.ArrayList<>(
                java.util.Collections.nCopies(6, "0"));
        Map<String, Integer> column = Map.of("passing_yards", 0, "passing_tds", 1,
                "passing_interceptions", 2, "receptions", 3,
                "rushing_fumbles", 4, "rushing_fumbles_lost", 5);
        cells.set(3, "8");
        LeagueScoringSettings settings = LeagueScoringSettings.defaultScoringSettings();
        Assertions.assertEquals(8 * settings.reception,
                NflverseWeekly.score(cells, column, settings), 1e-9);
    }
}
