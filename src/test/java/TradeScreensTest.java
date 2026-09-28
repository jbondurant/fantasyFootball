import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the man across the table sees (TradeScreens), and the two fixes that
 * came with it: pricing on the games left (NflverseGames.kickedOff) and the
 * tier calibration (ProjectionTiers).
 */
public class TradeScreensTest {

    /** The fit never rises with ADP, and a clean falling curve comes back as it went in. */
    @Test
    public void theChartFallsWithAdp(){
        List<double[]> pairs = new ArrayList<>();
        // noisy but falling: every third point is out of order
        double[][] raw = {{1, 120}, {2, 130}, {3, 110}, {10, 90}, {11, 95}, {20, 60}, {30, 40}, {31, 45}, {60, 20}, {120, 5}};
        for(double[] p : raw){
            pairs.add(p);
        }
        TradeScreens.Chart chart = TradeScreens.fit(pairs);
        for(int i = 1; i < chart.value().length; i++){
            assertTrue(chart.value()[i] <= chart.value()[i - 1], "the chart rose at block " + i);
        }
        assertEquals(125.0, chart.at(1.5), 1e-9, "the first two pool: they were out of order");
        assertEquals(5.0, chart.at(500), 1e-9, "past the last ADP it holds the last value");
        assertEquals(chart.value()[0], chart.at(0.2), 1e-9, "before the first it holds the first");

        TradeScreens.Chart clean = TradeScreens.fit(List.of(new double[]{1, 100}, new double[]{2, 80}, new double[]{3, 60}));
        assertEquals(90.0, clean.at(1.5), 1e-9, "a falling curve is its own fit, read between the points");
    }

    /**
     * THE CASE THAT STARTED IT. On a chart shaped like the one history gives
     * (pick 4 about 107, pick 29 about 54, pick 104 about 20, pick 129 about 13),
     * Nabers (28.6) and Stevenson (69.2) for Ja'Marr Chase (3.9) is a clear loss
     * for the man giving Chase, where the straight-line check read 24.7 picks as
     * "even" - and the same 25 picks at the back of the draft is nearly nothing.
     */
    @Test
    public void twentyFivePicksAtTheTopAreNotTwentyFiveAtTheBack(){
        TradeScreens.Chart chart = new TradeScreens.Chart(new double[]{1, 4, 29, 69, 104, 129, 180},
                new double[]{114, 107, 54, 26, 20, 13, 7});
        double top = chart.at(3.9) - chart.at(28.6);
        double back = chart.at(104) - chart.at(129);
        assertTrue(top > 5 * back, "the same 25 picks: " + top + " at the top against " + back + " at the back");

        Map<String, Double> adp = Map.of("nabers", 28.6, "stevenson", 69.2, "chase", 3.9);
        Map<String, Double> points = Map.of("nabers", 0.0, "stevenson", 0.0, "chase", 131.5);
        TradeScreens.Screens seen = TradeScreens.screen(List.of("nabers", "stevenson"), List.of("chase"),
                id -> chart.at(adp.get(id)), points::get, List.of(), "Renteez");
        assertTrue(seen.draftRatio() < 1, "he gives the most draft value: " + seen.draftRatio());
        assertEquals(0.0, seen.pointsRatio(), 1e-9, "and gets none of the season's points he gives");
        assertFalse(seen.fairToHim());
        assertTrue(seen.noChance(), "with no accepted trade as lopsided, it is no chance: " + seen.verdict());
        assertTrue(seen.verdict().startsWith("NO CHANCE"), seen.verdict());
    }

    /** Precedents must be at least as lopsided on BOTH screens, and a screen he sends nothing on cannot be lost. */
    @Test
    public void aPrecedentHasToBeAsLopsidedOnBothScreens(){
        List<TradeScreens.Precedent> history = List.of(
                new TradeScreens.Precedent("2025", 6, "Hamrliks", "a", "b", 38, 100, 0, 50),    // 38%, 0%
                new TradeScreens.Precedent("2024", 4, "BHier", "c", "d", 30, 100, 90, 60),      // 30%, 150%
                new TradeScreens.Precedent("2022", 1, "KevinDA", "e", "f", 50, 100, 0, 0));     // 50%, nothing sent
        // 78% of the draft value, 0% of the points: only the first took as little on both
        List<String> took = TradeScreens.precedents(0.78, 0.0, history);
        assertEquals(List.of("Hamrliks"), took);
        // a trade in which he sends no points at all is constrained by draft value alone
        assertEquals(3, TradeScreens.precedents(0.78, Double.POSITIVE_INFINITY, history).size());

        TradeScreens.Screens seen = new TradeScreens.Screens(78, 100, 0, 50, took, 60, "Renteez");
        assertFalse(seen.noChance(), "one accepted side took as little, so there is a precedent");
        assertTrue(seen.losesOnBoth(), "but he is behind on both numbers he can see");
        assertFalse(seen.worthAsking(), "and another manager's lopsided yes is not a reason for his: " + seen.verdict());
        assertTrue(seen.verdict().startsWith("NO") && seen.verdict().contains("never Renteez"),
                "the verdict says so, and that the one who took it was somebody else: " + seen.verdict());

        // ahead on one of his numbers, with a precedent: a long shot worth asking
        TradeScreens.Screens mixed = new TradeScreens.Screens(78, 100, 60, 50,
                TradeScreens.precedents(0.78, 1.2, history), 60, "Renteez");
        assertFalse(mixed.losesOnBoth());
        assertTrue(mixed.worthAsking(), mixed.verdict());
        assertTrue(mixed.verdict().startsWith("a long shot"), mixed.verdict());
    }

    /** Fair means not losing on either screen; sending nothing on a screen is not a loss on it. */
    @Test
    public void fairMeansHeLosesOnNeither(){
        assertTrue(new TradeScreens.Screens(60, 50, 20, 20, List.of(), 60, "x").fairToHim());
        assertFalse(new TradeScreens.Screens(60, 50, 19, 20, List.of(), 60, "x").fairToHim());
        assertTrue(new TradeScreens.Screens(60, 50, 0, 0, List.of(), 60, "x").fairToHim(),
                "no points either way: the points screen has nothing to say");
        assertEquals("120%/-", new TradeScreens.Screens(60, 50, 0, 0, List.of(), 60, "x").ratios());
    }

    /** The points screen: per-game points above the last starter's, at a season's pace; zero below the line or sidelined. */
    @Test
    public void thePointsScreenIsPerGameAboveTheLine(){
        // one team, no flex: three receivers start, so the line is the third-best per-game receiver
        Map<String, double[]> soFar = Map.of(
                "star", new double[]{45, 3},        // 15 a game
                "starter", new double[]{30, 3},     // 10 a game: the line
                "hurt", new double[]{60, 3},        // 20 a game, on IR
                "once", new double[]{25, 1});       // one game: below half the weeks, so not in the line
        Map<String, PlayerImportAndSetup.Position> positionOf = Map.of("star", PlayerImportAndSetup.Position.WR,
                "starter", PlayerImportAndSetup.Position.WR, "hurt", PlayerImportAndSetup.Position.WR,
                "once", PlayerImportAndSetup.Position.WR);
        // among men with 2+ games (half the 3 weeks, rounded up): 20, 15, 10 - the line is 10
        Map<String, Double> screen = TradeScreens.production(soFar, positionOf, 3, 1, 0, Set.of("hurt"));
        assertEquals(0.0, screen.get("starter"), 1e-9, "the line itself is worth nothing above it");
        assertEquals(17 * (15 - 10), screen.get("star"), 1e-9);
        assertFalse(screen.containsKey("hurt"), "a man on IR shows nothing");
        assertEquals(17 * (25 - 10), screen.get("once"), 1e-9, "a one-game man is priced, just not allowed to set the line");
    }

    /** A game kicks off at its listed Eastern time; a club that has played is out of the games-left sum. */
    @Test
    public void kickedOffIsByEachGamesOwnKickoff(){
        List<NflverseGames.Game> games = List.of(
                game("2026", 3, "2026-09-27", "13:00", "NYG", "TEN"),
                game("2026", 3, "2026-09-28", "20:15", "CHI", "PHI"),
                game("2026", 3, "2026-09-27", "20:20", "DEN", "LA"),
                game("2026", 4, "2026-10-01", "20:15", "GB", "ATL"));
        Set<String> started = NflverseGames.kickedOff(games, "2026", 3, LocalDateTime.parse("2026-09-27T22:56"));
        assertEquals(Set.of("NYG", "TEN", "DEN", "LAR"), started, "Monday's game has not kicked off; LA is Sleeper's LAR");
        assertTrue(NflverseGames.kickedOff(games, "2026", 3, LocalDateTime.parse("2026-09-27T12:59")).isEmpty());
        assertTrue(NflverseGames.kickedOff(games, "2025", 3, LocalDateTime.parse("2026-09-28T23:00")).isEmpty(),
                "a season the file does not schedule: nobody has kicked off");
    }

    static NflverseGames.Game game(String season, int week, String day, String time, String home, String away){
        return new NflverseGames.Game(season + "_" + week + "_" + away + "_" + home, season, week, day, time, home, away,
                null, null, false, false, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Calibration by tier: the gap and its season-week clustered error, by hand. */
    @Test
    public void aTierCellIsTheMeanGapWithAClusteredError(){
        List<double[]> rows = List.of(new double[]{10, 12}, new double[]{10, 8}, new double[]{20, 25}, new double[]{20, 19});
        List<String> clusters = List.of("w1", "w1", "w2", "w2");
        ProjectionTiers.Cell c = ProjectionTiers.cell(rows, clusters);
        assertEquals(15.0, c.projected(), 1e-9);
        assertEquals(16.0, c.actual(), 1e-9);
        assertEquals(1.0, c.gap(), 1e-9);
        // cluster sums of (y - p): w1 = 0, w2 = 4; minus n_g * gap: -2, +2; sqrt(8 * 2/1) / 4
        assertEquals(Math.sqrt(16) / 4, c.se(), 1e-9);
        assertEquals(4, c.n());
    }
}
