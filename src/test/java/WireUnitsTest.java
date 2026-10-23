import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a wire gain is denominated in (TRAPS #151): the season feed's
 * seventeen-week units, or the games left - and the floor each is judged by.
 */
public class WireUnitsTest {

    /** Tuesday of week 4, nothing kicked off: weeks 4-18 span, 4-14 are the regular season. */
    @Test
    public void theHorizonIsTheWeeksTheGamesLeftTotalsSpan(){
        ProjectionSources.Horizon tuesday = ProjectionSources.Horizon.of(4, 1.0, 18, 14);
        assertEquals(15, tuesday.weeks(), 1e-12);
        assertEquals(11, tuesday.regularWeeks(), 1e-12);
        // Monday night of week 3: two of 28 clubs still to play, the rest of week 3 gone
        ProjectionSources.Horizon monday = ProjectionSources.Horizon.of(3, 2.0 / 28, 18, 14);
        assertEquals(15 + 2.0 / 28, monday.weeks(), 1e-12);
        assertEquals(11 + 2.0 / 28, monday.regularWeeks(), 1e-12);
        // the fantasy playoffs: games left, none of them in the regular season
        ProjectionSources.Horizon playoffs = ProjectionSources.Horizon.of(16, 1.0, 18, 14);
        assertEquals(3, playoffs.weeks(), 1e-12);
        assertEquals(0, playoffs.regularWeeks(), 1e-12);
        assertEquals(0, ProjectionSources.Horizon.of(19, 1.0, 18, 14).weeks(), 1e-12);
    }

    /** The season feed's scaling is the one the report and page always used. */
    @Test
    public void aSeasonFeedGainIsScaledToTheWeeksLeftAsBefore(){
        for(int week = 1; week <= 18; week++){
            TuesdaySwap.Units units = TuesdaySwap.Units.season("sleeper", week);
            assertEquals(20.0 * Math.max(1, 15 - week) / 17.0, units.fromHere(20.0), 1e-12, "week " + week);
        }
    }

    /**
     * A games-left gain is already over the games left. Scaling it by
     * (15 - week) / 17 as well is the double discount: at week 4 a man worth
     * 15 over weeks 4-18 is worth 11 in weeks 4-14, not 9.7.
     */
    @Test
    public void aGamesLeftGainIsNotDiscountedTwice(){
        TuesdaySwap.Units units = TuesdaySwap.Units.gamesLeft("sleeper-remaining",
                ProjectionSources.Horizon.of(4, 1.0, 18, 14));
        assertEquals(11.0, units.fromHere(15.0), 1e-12);
        assertTrue(units.fromHere(15.0) > TuesdaySwap.Units.season("sleeper", 4).fromHere(15.0) + 1,
                "the season feed's scaling applied to a games-left gain is the double discount");
        assertTrue(units.fromHere(15.0) <= 15.0, "from here can only shrink a worth");
        assertEquals(0, TuesdaySwap.Units.gamesLeft("sleeper-remaining",
                ProjectionSources.Horizon.of(16, 1.0, 18, 14)).fromHere(12.0), 1e-12,
                "nothing is left of the regular season in the playoffs");
        assertEquals(0, TuesdaySwap.Units.gamesLeft("sleeper-remaining",
                ProjectionSources.Horizon.of(19, 1.0, 18, 14)).fromHere(12.0), 1e-12,
                "a horizon of no weeks is worth nothing, not a division by zero");
    }

    /**
     * A floor is read off the report measured on the pricing it judges, never
     * converted from another's: the season feed's report has no suffix, every
     * other source's carries its name, and the newest of a source's wins.
     */
    @Test
    public void theFloorIsTheOneMeasuredOnItsOwnSource() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("floors");
        String report = "OBJECTIVE STABILITY  %s  (480 scenarios, 3 seeds, roster of 13 projected men)%n%n"
                + "worst seed-to-seed spread of a marginal: %s points - two men closer than this are not separated%n";
        java.nio.file.Files.writeString(dir.resolve("objective-stability-2026-09-04.txt"), String.format(report, "2026-09-04", "6.8"));
        java.nio.file.Files.writeString(dir.resolve("objective-stability-2026-09-20-sleeper-remaining.txt"), String.format(report, "2026-09-20", "9.0"));
        java.nio.file.Files.writeString(dir.resolve("objective-stability-2026-09-29-sleeper-remaining.txt"), String.format(report, "2026-09-29", "11.3"));
        ObjectiveStability.Floor gamesLeft = ObjectiveStability.measured(dir, "sleeper-remaining");
        assertEquals(11.3, gamesLeft.points(), 1e-12);
        assertEquals("2026-09-29", gamesLeft.date());
        assertEquals(480, gamesLeft.scenarios());
        assertEquals(6.8, ObjectiveStability.measured(dir, "sleeper").points(), 1e-12,
                "the season feed's floor is not the games-left one however new that is");
        assertNull(ObjectiveStability.measured(dir, "ros"), "a source never measured has no floor of its own");
        assertNull(ObjectiveStability.parse("not a report", "2026-09-29", "sleeper"));
    }

    /** The wire prices on the games left, with the rest-of-season model beside it, and never on itself. */
    @Test
    public void theWireIsPricedOnTheGamesLeftWithRosBesideIt(){
        assertEquals("sleeper-remaining", TuesdaySwap.DEFAULT_SOURCE);
        assertEquals("ros", TuesdaySwap.altOf(TuesdaySwap.DEFAULT_SOURCE));
        assertEquals(TuesdaySwap.DEFAULT_SOURCE, TuesdaySwap.altOf("ros"));
        assertEquals("ros", TuesdaySwap.altOf("sleeper"));
        assertEquals(WeeklyActuals.WEEKS, 18, "the floor's denominator is the weeks a season total spans");
    }

    /**
     * THE CLAIM THE FLOOR RESCALING RESTS ON, tested on the real objective:
     * scale every total by k and the roster's value and every marginal scale by
     * exactly k (the expected score that sets the lineup, the drawn rate and
     * the wire are all proportional to the totals; tiers are ranks). So the
     * seed-to-seed wobble of a marginal scales by k too, and a floor measured
     * on season totals reads k times as large on totals k times as small.
     */
    @Test
    public void theObjectiveIsProportionalToTheTotals() throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Map<String, Double> board = SleeperProjections.parseTodaysWebPage();
        double k = 0.6;
        Map<String, Double> scaled = new LinkedHashMap<>();
        board.forEach((id, points) -> scaled.put(id, points * k));
        WeeklyStarterValue season = WeeklyStarterValue.forCurrentBoard(configuration, board, 48, 424_242L);
        WeeklyStarterValue shrunk = WeeklyStarterValue.forCurrentBoard(configuration, scaled, 48, 424_242L);
        List<String> roster = new ArrayList<>(board.keySet());
        roster.sort(Comparator.comparingDouble((String id) -> -board.get(id)));
        roster = new ArrayList<>(roster.subList(0, 40)).subList(20, 36);
        double whole = season.of(roster);
        assertTrue(whole > 100, "a sixteen-man roster from the board is worth something: " + whole);
        assertEquals(k * whole, shrunk.of(roster), 1e-9 * whole);
        List<String> without = new ArrayList<>(roster);
        without.remove(0);
        double marginal = whole - season.of(without);
        assertEquals(k * marginal, shrunk.of(roster) - shrunk.of(without), 1e-9 * whole,
                "a marginal, and so its seed-to-seed spread, scales with the totals");
    }
}
