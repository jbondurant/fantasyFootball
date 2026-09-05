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
}
