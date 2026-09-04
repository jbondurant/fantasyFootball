import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;

/** The pair-search pool is the top men by stand-alone value, whatever order they arrive in. */
public class Keepers16Test {

    private static Keepers16.Alone alone(double value){ return new Keepers16.Alone("id" + value, null, value, 5.0); }
    private static Keepers16.Alone alone(String id, double value){ return new Keepers16.Alone(id, null, value, 5.0); }

    @Test
    public void thePoolIsTheTopMenByStandaloneValueInDescendingOrder(){
        List<Keepers16.Alone> a = List.of(alone(34.2), alone(85.0), alone(-3.0), alone(74.9), alone(51.6));
        List<Keepers16.Alone> pool = Keepers16.topByAlone(a, 3);
        assertEquals(List.of(85.0, 74.9, 51.6), pool.stream().map(Keepers16.Alone::value).toList());
    }

    @Test
    public void aPoolLargerThanTheFieldIsTheWholeFieldAndTheInputIsUntouched(){
        List<Keepers16.Alone> a = List.of(alone(1.0), alone(2.0));
        assertEquals(2, Keepers16.topByAlone(a, 6).size());
        assertEquals(1.0, a.get(0).value(), "sorting must not reorder the caller's list");
    }

    @Test
    public void theTextReportParsesBackIntoBlocksAndRenders(){
        List<String> lines = List.of(
                "KEEPERS ON THE SIXTEEN-ROUND GAME  2026-09-03  (200 simulated drafts per world)",
                "Values are seventeen-week starter points.",
                "BHier        seat  2013.6   kept Daniels r7 \u00b7 Pitts r13          2012.6 (  -1.0)   best pair Watson r10 \u00b7 Dobbins r9         2099.8 ( +87.2)   10 pairs searched, 328s",
                "      Christian Watson         WR  r10     +68.3  +/-  7.2",
                "      Tampa Bay Buccaneers     DEF r10     -21.4  +/-  6.4",
                "      Kyle Pitts               TE  r13      -1.8  +/-  7.5  kept",
                "",
                "justinb314   seat  1966.6   kept Tuten r12 \u00b7 Purdy r13           2070.0 (+103.3)   best pair Purdy r13 \u00b7 Tuten r12           2070.0 (  +0.0)   10 pairs searched, 244s",
                "      Brock Purdy              QB  r13     +50.1  +/-  7.3  kept");
        List<Keepers16.Block> blocks = Keepers16.parseReport(lines);
        assertEquals(2, blocks.size());
        Keepers16.Block b = blocks.get(0);
        assertEquals("BHier", b.owner());
        assertEquals(2013.6, b.seat(), 1e-9);
        assertEquals("Daniels r7 \u00b7 Pitts r13", b.keptLabel());
        assertEquals(-1.0, b.keptDelta(), 1e-9);
        assertEquals("Watson r10 \u00b7 Dobbins r9", b.pairLabel());
        assertEquals(87.2, b.pairDelta(), 1e-9);
        assertEquals(3, b.rows().size());
        assertEquals("Tampa Bay Buccaneers", b.rows().get(1).name());
        assertEquals("DEF", b.rows().get(1).position());
        assertTrue(b.rows().get(2).kept());
        assertFalse(b.rows().get(0).kept());
        assertEquals(103.3, blocks.get(1).keptDelta(), 1e-9);
        String html = Keepers16.html(lines, "2026-09-03");
        assertTrue(html.contains("Christian Watson") && html.contains("the pair he kept") && html.contains("class='me'"));
    }

    @Test
    public void thePoolIsTheTopMenPlusEveryDeclaredMan(){
        List<Keepers16.Alone> a = List.of(alone("watson", 68.3), alone("dobbins", 25.3), alone("henry", 17.5),
                alone("pitts", -1.8), alone("daniels", -6.6), alone("conner", -80.6));
        List<Keepers16.Alone> pool = Keepers16.pool(a, 3, java.util.Set.of("pitts", "daniels"));
        assertEquals(List.of("watson", "dobbins", "henry", "pitts", "daniels"), pool.stream().map(Keepers16.Alone::id).toList());
        List<Keepers16.Alone> already = Keepers16.pool(a, 3, java.util.Set.of("watson"));
        assertEquals(3, already.size(), "a declared man already in the top is not added twice");
    }

    @Test
    public void pairedDifferencesAreMeasuredTrialByTrial(){
        double[] world = {110, 121, 98, 135};
        double[] seat  = {100, 110, 90, 125};
        double[] d = Keepers16.paired(world, seat);
        assertEquals(9.75, d[0], 1e-9);
        assertTrue(d[1] < 1.0, "the worlds move together, so the paired error is small: " + d[1]);
        assertThrows(IllegalArgumentException.class, () -> Keepers16.paired(world, new double[]{1, 2, 3}));
    }

    @Test
    public void theHeadlineParsesWithOrWithoutErrorBars(){
        List<String> lines = List.of(
                "KEEPERS ON THE SIXTEEN-ROUND GAME  2026-09-04",
                "justinb314   seat  1966.6   kept Tuten r12 \u00b7 Purdy r13           2070.0 (+103.3 +/-  4.1)   best pair Purdy r13 \u00b7 Tuten r12           2070.0 (  +0.0 +/-  0.0)   10 pairs searched, 244s");
        Keepers16.Block b = Keepers16.parseReport(lines).get(0);
        assertEquals(103.3, b.keptDelta(), 1e-9);
        assertEquals(0.0, b.pairDelta(), 1e-9);
        assertEquals("Purdy r13 \u00b7 Tuten r12", b.pairLabel());
    }
}
