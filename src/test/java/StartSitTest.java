import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import PlayerImportAndSetup.Position;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The lineup bar is a measurement, not a number somebody liked.
 *
 * The first run of the curve read 0.166 in its TIGHTEST bin against 0.364 in
 * the next - a gap of under a point looking more decisive than a gap of two -
 * because it paired every projected man at a position (the 90th receiver
 * against the 91st, both scoring nothing) and counted a 0-0 tie as the
 * projection being right. Both faults are pinned here.
 */
public class StartSitTest {

    private static Map<String, Double> week(Object... pairs){
        java.util.Map<String, Double> map = new java.util.HashMap<>();
        for(int i = 0; i < pairs.length; i += 2){ map.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue()); }
        return map;
    }

    @Test
    public void aTieIsNotAWinForEitherReading(){
        Map<String, Position> positions = Map.of("a", Position.WR, "b", Position.WR);
        int[] ties = new int[1];
        // both projected apart, both scored exactly 0: not evidence the projection was right
        List<StartSit.Flip> curve = StartSit.flipCurve(
                List.of(week("a", 12.0, "b", 3.0)), List.of(week("a", 0.0, "b", 0.0)),
                positions, Map.of(Position.WR, 24), ties);
        assertEquals(1, ties[0], "the tied pair must be excluded, not scored");
        assertTrue(curve.isEmpty(), "with the only pair tied there is nothing to report");
    }

    @Test
    public void onlyMenThisLeagueWouldRosterAreAPairing(){
        // c is the third receiver; a depth of 2 must leave him out of every pair
        Map<String, Position> positions = Map.of("a", Position.WR, "b", Position.WR, "c", Position.WR);
        int[] ties = new int[1];
        List<StartSit.Flip> shallow = StartSit.flipCurve(
                List.of(week("a", 20.0, "b", 18.0, "c", 1.0)),
                List.of(week("a", 5.0, "b", 9.0, "c", 30.0)),
                positions, Map.of(Position.WR, 2), ties);
        assertEquals(1, shallow.stream().mapToInt(StartSit.Flip::pairs).sum(),
                "depth 2 leaves exactly one pair, a-b");
        List<StartSit.Flip> deep = StartSit.flipCurve(
                List.of(week("a", 20.0, "b", 18.0, "c", 1.0)),
                List.of(week("a", 5.0, "b", 9.0, "c", 30.0)),
                positions, Map.of(Position.WR, 3), ties);
        assertEquals(3, deep.stream().mapToInt(StartSit.Flip::pairs).sum(), "depth 3 gives all three pairs");
    }

    @Test
    public void theCurveIsReadBackOutOfItsOwnReportAndFallsAsTheGapGrows() throws Exception {
        Path newest;
        try(var files = Files.list(Path.of("data"))){
            newest = files.filter(p -> p.getFileName().toString().matches("start-sit-flip-\\d{4}-\\d{2}-\\d{2}\\.txt"))
                    .max(Comparator.comparing(p -> p.getFileName().toString())).orElseThrow();
        }
        List<StartSit.Flip> curve = StartSit.readCurve(Files.readAllLines(newest));
        assertTrue(curve.size() >= 6, "the committed curve should carry most bins: " + curve.size());
        assertTrue(curve.get(0).flipRate() > 0.40,
                "a sub-point gap must be near a coin flip, not a confident call: " + curve.get(0).flipRate());
        for(int i = 1; i < curve.size(); i++){
            assertTrue(curve.get(i).flipRate() <= curve.get(i - 1).flipRate() + 0.02,
                    "the flip rate must fall as the projected gap grows; bin " + i + " broke it");
        }
        assertEquals(curve.get(0).flipRate(), StartSit.flipRate(curve, 0.5), 1e-9);
    }

    /**
     * The lineup plays two FLEX slots, so a benched receiver competes with a
     * starting back as well as a starting receiver. Measuring him against his
     * own position only compared him with the wrong man, or with nobody.
     */
    @Test
    public void aBenchedFlexManIsMeasuredAgainstTheStarterHeCouldActuallyReplace(){
        // A REAL ten-slot lineup: QB, 2RB, 3WR, TE, 2FLEX, DEF. The constraint only
        // exists when the slots do - with two starters there is no tight-end slot to
        // leave unfilled, which is why the first version of this test passed on a
        // lineup too small to express the rule it was checking.
        List<StartSit.Man> roster = new java.util.ArrayList<>(List.of(
                new StartSit.Man("qb", "QB", Position.QB, 22.0, true),
                new StartSit.Man("rb1", "RB1", Position.RB, 15.1, true),
                new StartSit.Man("rb2", "RB2", Position.RB, 12.4, true),
                new StartSit.Man("wr1", "WR1", Position.WR, 11.2, true),
                new StartSit.Man("wr2", "WR2", Position.WR, 10.8, true),
                new StartSit.Man("wr3", "WR3", Position.WR, 10.2, true),
                new StartSit.Man("te", "Only TE", Position.TE, 9.3, true),
                new StartSit.Man("flexRb", "Flex RB", Position.RB, 10.4, true),
                new StartSit.Man("flexRb2", "Flex RB2", Position.RB, 10.9, true),
                new StartSit.Man("def", "DEF", Position.DEF, 7.6, true)));
        List<String> starters = List.of("qb", "rb1", "rb2", "wr1", "wr2", "wr3", "te", "flexRb", "flexRb2", "def");
        StartSit.Man benchedWr = new StartSit.Man("wr9", "Bench WR", Position.WR, 9.5, true);
        roster.add(benchedWr);

        double gap = StartSit.closestStarter(benchedWr, roster, starters);
        assertEquals(0.7, gap, 1e-9,
                "the nearest starter he can legally replace is WR3 at 10.2 - not the lone TE at 9.3,"
                        + " whose slot would then go unfilled");

        // and a back on the bench reaches a receiver's slot through FLEX
        StartSit.Man benchedRb = new StartSit.Man("rb9", "Bench RB", Position.RB, 10.3, true);
        roster.add(benchedRb);
        assertEquals(0.1, StartSit.closestStarter(benchedRb, roster,
                List.of("qb", "rb1", "rb2", "wr1", "wr2", "wr3", "te", "flexRb", "flexRb2", "def")), 1e-9,
                "he takes the weakest flex-eligible starter he can legally displace, RB 10.4");

        assertFalse(StartSit.flexEligible(Position.QB));
        assertFalse(StartSit.flexEligible(Position.DEF));
        assertTrue(StartSit.flexEligible(Position.RB) && StartSit.flexEligible(Position.WR)
                && StartSit.flexEligible(Position.TE));
    }
}
