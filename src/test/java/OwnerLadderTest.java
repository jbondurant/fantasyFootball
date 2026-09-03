import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** The ladder reads the 10k ledger correctly and picks the two highest standalone deltas. */
public class OwnerLadderTest {

    @Test
    public void theLedgerParsesIntoCandidatesPerManager(){
        List<String> lines = List.of(
                "standalone keeper deltas, top 8 per team by VORP prescreen;",
                "BHier (slot 2, keeperless seat 1818.9):",
                "   Christian Watson                   WR  r10    +28.5",
                "   Matthew Stafford                   QB  r10     +9.1",
                "   **Kyle Pitts**                     TE  r13     +2.1",
                "   **Jayden Daniels**                 QB  r7      -5.2",
                "",
                "justinb314 (slot 7, keeperless seat 1785.0):",
                "   **Bhayshul Tuten**                 RB  r12    +16.3",
                "   **Brock Purdy**                    QB  r13    +10.4");
        Map<String, List<OwnerLadder.Candidate>> ledger = OwnerLadder.parseLedger(lines);
        assertEquals(List.of("BHier", "justinb314"), List.copyOf(ledger.keySet()));
        assertEquals(4, ledger.get("BHier").size());
        OwnerLadder.Candidate pitts = ledger.get("BHier").get(2);
        assertEquals("Kyle Pitts", pitts.name());
        assertEquals("TE", pitts.position());
        assertEquals(13, pitts.round());
        assertEquals(2.1, pitts.delta(), 1e-9);
        assertTrue(pitts.kept(), "** marks the kept man");
        assertFalse(ledger.get("BHier").get(0).kept());
    }

    @Test
    public void theBestPairIsTheTwoHighestDeltas(){
        List<OwnerLadder.Candidate> c = List.of(
                new OwnerLadder.Candidate("Watson", "WR", 10, 28.5, false),
                new OwnerLadder.Candidate("Stafford", "QB", 10, 9.1, false),
                new OwnerLadder.Candidate("Pitts", "TE", 13, 2.1, true),
                new OwnerLadder.Candidate("Daniels", "QB", 7, -5.2, true));
        List<OwnerLadder.Candidate> best = OwnerLadder.bestPair(c);
        assertEquals("Watson", best.get(0).name());
        assertEquals("Stafford", best.get(1).name());
        assertFalse(best.stream().allMatch(OwnerLadder.Candidate::kept), "BHier did not keep his best pair");
    }
}
