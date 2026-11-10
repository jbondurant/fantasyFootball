import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** This season's forecast check: each source against Sleeper's next game on the men both cover, error over men. */
public class ForwardAccuracyTest {

    private static ForwardAccuracy.Case man(Position p, double target, Double base, Double other){
        Map<String, Double> f = new java.util.HashMap<>();
        if(base != null){
            f.put("sleeper-next", base);
        }
        if(other != null){
            f.put("cbs", other);
        }
        return new ForwardAccuracy.Case("x", p, f, target);
    }

    @Test
    public void aSourceIsScoredOnlyOnTheMenItAndTheBaselineBothCover(){
        List<ForwardAccuracy.Case> cases = new ArrayList<>();
        for(int i = 0; i < 12; i++){
            cases.add(man(Position.RB, 10, 12.0, 11.0));     // baseline off by 2, cbs by 1
        }
        cases.add(man(Position.RB, 10, 12.0, null));         // cbs has no number: left out
        cases.add(man(Position.WR, 10, 12.0, 11.0));         // too few WRs to score
        List<Object[]> rows = ForwardAccuracy.score(cases, List.of("cbs"), "sleeper-next");
        assertEquals(1, rows.size());
        Object[] r = rows.get(0);
        assertEquals(Position.RB, r[1]);
        assertEquals(12, r[2]);
        assertEquals(1.0, (double) r[3], 1e-12);
        assertEquals(2.0, (double) r[4], 1e-12);
        assertEquals(0.5, (double) r[5], 1e-12);
        assertEquals(1 - 4, (double) r[6], 1e-12, "squared error 1 against 4");
        assertEquals(0.0, (double) r[7], 1e-12, "the same on every man");
    }

    @Test
    public void aReadingNeedsTheDifferencePastItsBar(){
        assertEquals("= Sleeper's next game", ForwardAccuracy.reading(-1.0, 1.0, 50));
        assertEquals("better than Sleeper's next game", ForwardAccuracy.reading(-3.0, 1.0, 50));
        assertEquals("worse than Sleeper's next game", ForwardAccuracy.reading(3.0, 1.0, 50));
        assertNull(ForwardAccuracy.mean(List.of()));
        assertEquals(2.0, ForwardAccuracy.mean(List.of(1.0, 3.0)), 1e-12);
    }
}
