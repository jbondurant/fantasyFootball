import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import PlayerImportAndSetup.Position;

/** The verdict lines name what the screen decided, and name a split when the engines disagree. */
public class VerdictLineTest {

    @Test
    public void separatedNamesTheMargin(){
        String line = VerdictLine.board(new VerdictLine.Board(Position.RB, List.of(), 24.1, 6.4, Position.WR));
        assertEquals("VERDICT: RB - SEPARATED from WR by 24.1 (+/- 6.4, paired 2 s.e.)", line);
    }

    @Test
    public void aTieNamesEveryPositionInIt(){
        String line = VerdictLine.board(new VerdictLine.Board(Position.QB, List.of(Position.WR, Position.DEF), 0.4, 3.1, Position.WR));
        assertTrue(line.startsWith("VERDICT: TIE - QB / WR / DEF inside the noise"), line);
    }

    @Test
    public void pickEighteenWouldHaveReadSplit(){
        // 2026-09-01, pick 18: board model Hall (RB) by 10.2 +/- 4.4; all four engines WR by 5.3
        VerdictLine.Board b = new VerdictLine.Board(Position.RB, List.of(), 10.2, 4.4, Position.WR);
        VerdictLine.Vote v = new VerdictLine.Vote(Position.WR, true, 1795.6, 1790.3, Position.RB);
        String line = VerdictLine.together(b, v);
        assertTrue(line.startsWith("SPLIT: board model RB (+10.2 END TEAM over WR), Model A WR (every engine; +5.3 skill-nine over RB)"), line);
        assertTrue(line.contains("the written rule ranks on the board model"), line);
    }

    @Test
    public void agreementAndALeanInsideTheTieReadAsSuch(){
        VerdictLine.Board separated = new VerdictLine.Board(Position.RB, List.of(), 24.1, 6.4, Position.WR);
        assertTrue(VerdictLine.together(separated, new VerdictLine.Vote(Position.RB, true, 1804.6, 1796.5, Position.WR))
                .startsWith("MODEL A AGREES: RB (every engine; lookahead-2 +8.1 over WR)"));
        VerdictLine.Board tie = new VerdictLine.Board(Position.QB, List.of(Position.WR, Position.DEF), 0.4, 3.1, Position.WR);
        assertTrue(VerdictLine.together(tie, new VerdictLine.Vote(Position.WR, false, 1900.0, 1898.0, Position.QB))
                .startsWith("MODEL A LEANS WR inside the board model's tie (the committee; +2.0 over QB)"));
    }

    @Test
    public void nothingToSayPrintsNothing(){
        assertEquals("", VerdictLine.board(null));
        assertEquals("", VerdictLine.together(new VerdictLine.Board(Position.RB, List.of(), 1, 1, Position.WR), null));
    }
}
