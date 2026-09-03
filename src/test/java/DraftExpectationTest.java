import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** The expectation report rebuilds every seat's roster from a simulation and ranks fairly. */
public class DraftExpectationTest {

    @Test
    public void rostersFollowThePickNumberToTheSeat(){
        Map<String, Integer> takenAt = Map.of("a", 1, "b", 2, "c", 13, "d", 14);
        // picks 1 and 14 belong to seat X (snake), 2 and 13 to seat Y
        Map<String, List<String>> rosters = DraftExpectation.rostersFrom(takenAt,
                pick -> (pick == 1 || pick == 14) ? "X" : (pick == 2 || pick == 13) ? "Y" : null);
        assertEquals(List.of("a", "d"), rosters.get("X"), "in pick order");
        assertEquals(List.of("b", "c"), rosters.get("Y"));
    }

    @Test
    public void meanAndErrorAreWhatTheyClaim(){
        double[] me = DraftExpectation.meanAndError(List.of(10.0, 12.0, 14.0, 16.0));
        assertEquals(13.0, me[0], 1e-9);
        assertEquals(Math.sqrt(20.0 / 3) / 2, me[1], 1e-9, "sd 2.58 over sqrt(4)");
        assertEquals(0, DraftExpectation.meanAndError(List.of(7.0))[1], "one value has no error estimate");
    }

    @Test
    public void ranksShareOnTiesAndOneIsBest(){
        Map<String, Integer> r = DraftExpectation.ranks(Map.of("a", 100.0, "b", 90.0, "c", 90.0, "d", 80.0));
        assertEquals(1, r.get("a"));
        assertEquals(2, r.get("b"));
        assertEquals(2, r.get("c"), "tie shares the better rank");
        assertEquals(4, r.get("d"));
    }
}
