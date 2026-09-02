import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A man whose projection collapsed is re-slotted to the price of his
 * projection; everyone else keeps his ADP. Jacobs on draft night: ADP 35,
 * projection rank 209, taken 40th by the fitted room, 105th by the real one.
 */
public class CollapsedMenTest {

    private static Map<String, Double> tenMen(){
        Map<String, Double> adp = new LinkedHashMap<>();
        for(int i = 1; i <= 10; i++){ adp.put("m" + i, 10.0 * i); }   // m1 earliest
        return adp;
    }

    private static Map<String, Double> pointsFallingWithAdpExcept(String collapsed){
        Map<String, Double> points = new LinkedHashMap<>();
        for(int i = 1; i <= 10; i++){ points.put("m" + i, 300.0 - 20 * i); }   // m1 best
        points.put(collapsed, 1.0);   // now the worst man in the pool
        return points;
    }

    @Test
    public void theCollapsedManTakesThePriceOfHisProjection(){
        Map<String, Double> out = DraftSimulator.effectiveAdp(tenMen(), pointsFallingWithAdpExcept("m2"), 5, Set.of("m2"));
        assertEquals(100.0, out.get("m2"), 1e-9, "projection rank 10 of 10: the ADP at rank 10");
        assertEquals(10.0, out.get("m1"), 1e-9, "untouched");
        assertEquals(30.0, out.get("m3"), 1e-9, "m3's projection rank 2 vs ADP rank 3: a gain, untouched");
    }

    @Test
    public void smallDisagreementsAreLeftToTheModel(){
        Map<String, Double> points = pointsFallingWithAdpExcept("nobody");
        points.put("m2", 195.0);   // now ranks 6th of 10 by points, 2nd by ADP: gap 4
        Map<String, Double> out = DraftSimulator.effectiveAdp(tenMen(), points, 5, Set.of("m2"));
        assertEquals(20.0, out.get("m2"), 1e-9, "a four-place gap is under the threshold");
    }

    @Test
    public void zeroThresholdDisablesIt(){
        Map<String, Double> adp = tenMen();
        assertSame(adp, DraftSimulator.effectiveAdp(adp, pointsFallingWithAdpExcept("m2"), 0, Set.of("m2")));
    }

    @Test
    public void aManWhoseProjectionMerelyDisagreesWithTheMarketIsLeftAlone(){
        Map<String, Double> adp = tenMen();
        Map<String, Double> out = DraftSimulator.effectiveAdp(adp, pointsFallingWithAdpExcept("m2"), 5, Set.of("somebodyElse"));
        assertEquals(20.0, out.get("m2"), 1e-9, "not in the recently-collapsed set: the market's disagreement stays with the model");
    }
}
