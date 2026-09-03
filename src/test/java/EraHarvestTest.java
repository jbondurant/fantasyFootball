import PlayerImportAndSetup.Position;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the season harvest that can be wrong without looking wrong.
 *
 * Offline and deterministic. Every case here is a mistake the join or the
 * replay actually made against real data on 2026-08-30, written down so it
 * cannot come back: a season with the wrong number of weeks, a defence that
 * changed city, a retired player shadowed by a modern namesake, a plan that
 * cannot field a legal lineup.
 */
public class EraHarvestTest {

    // ------------------------------------------------------------------
    // Seasons are not all 18 weeks, and defences are not all numbered.
    // ------------------------------------------------------------------

    @Test
    public void theSeasonWasSeventeenWeeksUntil2021(){
        assertEquals(17, EraActuals.weeks("2013"));
        assertEquals(17, EraActuals.weeks("2020"));
        assertEquals(18, EraActuals.weeks("2021"));
        assertEquals(18, EraActuals.weeks("2025"));
    }

    @Test
    public void teamRowsAreNotPlayersAndNotDefences(){
        assertTrue(EraActuals.isDefence("SD"), "a 2013 defence id");
        assertTrue(EraActuals.isDefence("LAR"));
        assertFalse(EraActuals.isDefence("4034"), "a player id");
        // TEAM_BUF aggregates a club's whole offence - scored as a man it would
        // outscore every real player in the league.
        assertFalse(EraActuals.isDefence("TEAM_BUF"));
    }

    @Test
    public void defencesFollowTheirClubBackToTheEraTheyPlayedIn(){
        Set<String> old = Set.of("SD", "STL", "OAK", "SEA");
        assertEquals("SD", EraBoards.defenceID("LAC", old), "the 2013 Chargers");
        assertEquals("STL", EraBoards.defenceID("LAR", old), "the 2013 Rams");
        assertEquals("OAK", EraBoards.defenceID("LV", old));
        assertEquals("SEA", EraBoards.defenceID("SEA", old));

        Set<String> modern = Set.of("LAC", "LAR", "LV", "SEA");
        assertEquals("LAC", EraBoards.defenceID("LAC", modern));
        assertEquals("LAR", EraBoards.defenceID("STL", modern), "reads both ways");
        assertNull(EraBoards.defenceID("XXX", modern), "no such club, no guess");
    }

    // ------------------------------------------------------------------
    // Names.
    // ------------------------------------------------------------------

    @Test
    public void namesFlattenTheWaysTheTwoFeedsDisagree(){
        assertEquals(EraBoards.normalise("Odell Beckham Jr."),
                EraBoards.normalise("Odell Beckham"));
        assertEquals(EraBoards.normalise("Robert Griffin III"),
                EraBoards.normalise("Robert Griffin"));
        assertEquals(EraBoards.normalise("Ray-Ray McCloud"),
                EraBoards.normalise("Ray Ray McCloud"));
        assertEquals(EraBoards.normalise("D.J. Moore"), EraBoards.normalise("DJ Moore"));
        assertFalse(EraBoards.normalise("Michael Thomas")
                .equals(EraBoards.normalise("Mike Thomas")), "not that flat");
    }

    @Test
    public void aRetiredPlayerIsNotLostToHisModernNamesake(){
        // The real case: Sleeper answers a 2013 request with a row for Frank
        // Gore, who played, and one for Frank Gore Jr., who was ten. Both
        // normalise to "frank gore"; only one has games.
        JsonObject real = row("232", "SF", 16);
        JsonObject stub = row("11573", "BUF", 0);
        assertEquals("232", EraBoards.skillID(List.of(real, stub), "SF"),
                "the team decides it when the team is right");
        // 2010's rows carry no team at all, so participation has to decide.
        JsonObject teamless = row("232", null, 11);
        assertEquals("232", EraBoards.skillID(List.of(teamless, stub), "SF"),
                "the man who played is the man on the board");
    }

    @Test
    public void twoRealPlayersWithOneNameAreReportedNotGuessed(){
        // Steve Smith and Steve Smith, both receivers, both playing.
        JsonObject carolina = row("100", "CAR", 16);
        JsonObject giants = row("200", "NYG", 16);
        assertEquals("100", EraBoards.skillID(List.of(carolina, giants), "CAR"));
        assertEquals("200", EraBoards.skillID(List.of(carolina, giants), "NYG"));
        assertNull(EraBoards.skillID(List.of(carolina, giants), "SEA"),
                "no club match and both played - refuse rather than flip a coin");
    }

    @Test
    public void theLoosenedJoinAnswersOnlyWhenItIsSure(){
        Map<String, List<JsonObject>> byName = new LinkedHashMap<>();
        Map<String, List<JsonObject>> byLastPositionTeam = new LinkedHashMap<>();
        Map<String, List<JsonObject>> byLastTeam = new LinkedHashMap<>();

        // Devin Funchess: a receiver on the board, a tight end to Sleeper.
        byName.put("devin funchess", List.of(row("2333", "CAR", 15)));
        assertEquals("2333", EraBoards.loosened("devin funchess", Position.WR, "CAR",
                byName, byLastPositionTeam, byLastTeam));

        // Hollywood Brown is Marquise Brown - different first name, same club.
        byLastPositionTeam.put("brown|WR|BAL", List.of(row("5849", "BAL", 16)));
        assertEquals("5849", EraBoards.loosened("hollywood brown", Position.WR, "BAL",
                byName, byLastPositionTeam, byLastTeam));

        // Two Browns on one roster: no answer.
        byLastTeam.put("brown|DET", List.of(row("1", "DET", 16), row("2", "DET", 16)));
        assertNull(EraBoards.loosened("anthony brown", Position.RB, "DET",
                byName, byLastPositionTeam, byLastTeam));
    }

    // ------------------------------------------------------------------
    // The replayed draft.
    // ------------------------------------------------------------------

    @Test
    public void myPicksSnakeFromSlotSeven(){
        int[] picks = EraGame.myPicks(11);
        assertArray(new int[]{7, 18, 31, 42, 55, 66, 79, 90, 103, 114, 127}, picks);
        assertEquals(132, EraGame.consumed(11));
    }

    @Test
    public void everyEnumeratedPlanCanFieldALegalLineup(){
        Map<Position, Integer> held = new LinkedHashMap<>();
        held.put(Position.QB, 1);
        held.put(Position.RB, 1);
        List<List<Position>> plans = EraPlans.all(11, held);
        assertFalse(plans.isEmpty());
        for(List<Position> plan : plans){
            assertEquals(11, plan.size());
            assertEquals(Position.DEF, plan.get(plan.size() - 1), "defence goes last");
            Map<Position, Integer> counts = new HashMap<>();
            for(Position position : plan){
                counts.merge(position, 1, Integer::sum);
            }
            assertEquals(1, counts.get(Position.DEF).intValue(), "exactly one defence");
            // keepers cover a QB and a back; the plan must supply the rest
            assertTrue(counts.getOrDefault(Position.RB, 0) >= 1, plan.toString());
            assertTrue(counts.getOrDefault(Position.WR, 0) >= 3, plan.toString());
            assertTrue(counts.getOrDefault(Position.TE, 0) >= 1, plan.toString());
        }
    }

    @Test
    public void planCountMatchesTheMultisetArithmetic(){
        // Four picks: floor of one back and one receiver, then a defence.
        Map<Position, Integer> held = new LinkedHashMap<>();
        held.put(Position.QB, 1);
        held.put(Position.RB, 1);
        Map<Position, Integer> floor = EraPlans.floor(held);
        assertEquals(0, floor.get(Position.QB).intValue(), "the keeper covers it");
        assertEquals(1, floor.get(Position.RB).intValue());
        assertEquals(3, floor.get(Position.WR).intValue());
        assertEquals(1, floor.get(Position.TE).intValue());

        // Six picks: five free above a floor of five, so exactly one free slot,
        // which any of the four positions can take. 6!/(counts) summed over the
        // four compositions = 30+20+30+30 = 110... computed rather than typed:
        List<List<Position>> plans = EraPlans.all(6, held);
        int expected = 0;
        for(Map<Position, Integer> composition : EraPlans.compositions(5, held)){
            expected += permutations(composition);
        }
        assertEquals(expected, plans.size());
        assertTrue(plans.size() > 1);
    }

    /**
     * The lineup filler, on a board and a season built by hand.
     *
     * Two weeks, a roster that is one man short at receiver in week two, and a
     * bench back who has to slide into the flex. Every number below is worked
     * out on paper, so a change in the scoring rule fails here loudly rather
     * than shifting a backtest by a few points nobody notices.
     */
    @Test
    public void theBestLegalLineupIsPickedByPreseasonRankAndNotByHindsight(){
        List<String> ids = List.of("qb1", "rb1", "rb2", "rb3", "wr1", "wr2", "wr3",
                "te1", "def1");
        Map<String, Position> positions = new LinkedHashMap<>();
        positions.put("qb1", Position.QB);
        positions.put("rb1", Position.RB);
        positions.put("rb2", Position.RB);
        positions.put("rb3", Position.RB);
        positions.put("wr1", Position.WR);
        positions.put("wr2", Position.WR);
        positions.put("wr3", Position.WR);
        positions.put("te1", Position.TE);
        positions.put("def1", Position.DEF);

        Map<String, Double> week1 = new HashMap<>();
        for(String id : ids){
            week1.put(id, 10.0);
        }
        // rb3 is the worst man by board rank but the best scorer this week. He
        // must still be started ONLY in the flex, because a manager setting a
        // lineup on Sunday morning cannot know.
        week1.put("rb3", 99.0);

        Map<String, Double> week2 = new HashMap<>(week1);
        week2.put("rb3", 10.0);
        week2.remove("wr3");                    // bye week: cannot be started
        week2.remove("def1");                   // and no defence to field

        List<Map<String, Double>> weekly = List.of(week1, week2);
        EraBoards.Board board = board(ids, positions, weekly);

        double scored = EraGame.seasonPoints(board, new ArrayList<>(ids));
        // week 1: QB 10 + RB 20 + WR 30 + TE 10 + DEF 10 + flex (rb3 99, wr... )
        //   flex candidates by board rank: rb3 (99) and nobody else -> 99
        // week 2: QB 10 + RB 20 + WR 20 (wr3 out) + TE 10 + DEF 0 + flex rb3 10
        assertEquals(179 + 70, scored, 0.0001);
    }

    @Test
    public void aPlanDraftsTheBestManAtThePositionItAsksFor(){
        List<String> ids = new ArrayList<>();
        Map<String, Position> positions = new LinkedHashMap<>();
        Map<String, Double> week = new HashMap<>();
        for(int i = 0; i < 200; i++){
            String id = "p" + i;
            ids.add(id);
            positions.put(id, i % 4 == 0 ? Position.RB : i % 4 == 1 ? Position.WR
                    : i % 4 == 2 ? Position.TE : Position.QB);
            week.put(id, 1.0);
        }
        EraBoards.Board board = board(ids, positions, List.of(week));
        List<Position> plan = new ArrayList<>();
        plan.add(Position.QB);
        for(int round = 2; round <= 10; round++){
            plan.add(Position.WR);
        }
        plan.add(Position.DEF);
        List<String> drafted = EraGame.draft(board, plan, List.of());
        // The eleven opponents take p0..p5 before my pick at 7, so the best man
        // left is p6, a tight end. Asking for a quarterback must get p7 - the
        // point being that the plan chooses the position and the board only
        // chooses the man.
        assertEquals("p7", drafted.get(0), "the best quarterback, not the best player");
        assertEquals(Position.WR, positions.get(drafted.get(1)));
        // No defence exists on this board, so the last pick falls back rather
        // than passing - the roster still fills.
        assertEquals(11, drafted.size());
    }

    // ------------------------------------------------------------------
    // The statistics the verdict rests on.
    // ------------------------------------------------------------------

    @Test
    public void effectiveSampleSizeCountsUnequalSeasonsHonestly(){
        assertEquals(13.0, EraScores.effectiveSampleSize(EraScores.flat(13)), 0.0001);
        // one season carrying all the weight is one season
        assertEquals(1.0, EraScores.effectiveSampleSize(new double[]{1, 0, 0, 0}), 0.0001);
        // half-weighting half of them lands between the two counts
        double half = EraScores.effectiveSampleSize(new double[]{1, 1, 0.5, 0.5});
        assertTrue(half > 3 && half < 4, "was " + half);
    }

    @Test
    public void decayWeightsTheNewestSeasonFullyAndHalvesAtTheHalfLife(){
        List<String> seasons = List.of("2015", "2020", "2025");
        double[] weights = EraScores.decay(seasons, 5.0);
        assertEquals(1.0, weights[2], 0.0001, "the newest season is the reference");
        assertEquals(0.5, weights[1], 0.0001, "five seasons back, half the weight");
        assertEquals(0.25, weights[0], 0.0001);
    }

    @Test
    public void rankCorrelationIsOneForAgreementAndMinusOneForReversal(){
        double[] a = {1, 2, 3, 4};
        double[] b = {10, 20, 30, 40};
        double[] reversed = {40, 30, 20, 10};
        assertEquals(1.0, RegimeShift.pearson(RegimeShift.rank(a), RegimeShift.rank(b)),
                0.0001);
        assertEquals(-1.0,
                RegimeShift.pearson(RegimeShift.rank(a), RegimeShift.rank(reversed)),
                0.0001);
        // ties share an averaged rank rather than an arbitrary order
        double[] tied = {5, 5, 5, 5};
        double[] ranks = RegimeShift.rank(tied);
        for(double rank : ranks){
            assertEquals(2.5, rank, 0.0001);
        }
    }

    @Test
    public void standardErrorShrinksWithTheRootOfTheSeasonCount(){
        double[] five = {100, -100, 100, -100, 0};
        double error = RegimeShift.standardError(five);
        double[] twenty = new double[20];
        for(int i = 0; i < 20; i++){
            twenty[i] = five[i % 5];
        }
        assertTrue(RegimeShift.standardError(twenty) < error * 0.6,
                "four times the seasons should roughly halve the error");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    static JsonObject row(String id, String team, int games){
        JsonObject row = JsonParser.parseString("{}").getAsJsonObject();
        row.addProperty("player_id", id);
        if(team != null){
            row.addProperty("team", team);
        }
        JsonObject stats = new JsonObject();
        if(games > 0){
            stats.addProperty("gp", games);
        }
        row.add("stats", stats);
        return row;
    }

    static EraBoards.Board board(List<String> ids, Map<String, Position> positions,
                                 List<Map<String, Double>> weekly){
        Map<String, Double> adp = new HashMap<>();
        for(int i = 0; i < ids.size(); i++){
            adp.put(ids.get(i), i + 1.0);
        }
        EraBoards.Match match = new EraBoards.Match("test", "ppr", ids.size(),
                ids.size(), 0, 0, 0, ids.size(), 0, List.of(), weekly.size(), 0,
                ids.size(), 0);
        return new EraBoards.Board("test", "ppr", ids, positions, adp, weekly,
                weekly.size(), match);
    }

    static int permutations(Map<Position, Integer> composition){
        int total = 0;
        for(int count : composition.values()){
            total += count;
        }
        long result = factorial(total);
        for(int count : composition.values()){
            result /= factorial(count);
        }
        return (int) result;
    }

    static long factorial(int n){
        long result = 1;
        for(int i = 2; i <= n; i++){
            result *= i;
        }
        return result;
    }

    static void assertArray(int[] expected, int[] actual){
        assertEquals(expected.length, actual.length);
        for(int i = 0; i < expected.length; i++){
            assertEquals(expected[i], actual[i], "at " + i);
        }
    }
}
