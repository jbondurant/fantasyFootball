import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import PlayerImportAndSetup.Position;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A simulated roster ends legal. The fitted room left a defence slot empty in
 * 12% of simulated 2026 rosters; no real roster ever did.
 */
public class RosterNeedTest {

    private static Position pos(String id){
        return switch(id.charAt(0)){
            case 'q' -> Position.QB; case 'r' -> Position.RB; case 'w' -> Position.WR;
            case 't' -> Position.TE; case 'd' -> Position.DEF; default -> null;
        };
    }

    private static Map<Position, Integer> roster(int qb, int rb, int wr, int te, int def){
        Map<Position, Integer> r = new EnumMap<>(Position.class);
        r.put(Position.QB, qb); r.put(Position.RB, rb); r.put(Position.WR, wr); r.put(Position.TE, te); r.put(Position.DEF, def);
        return r;
    }

    @Test
    public void withSlackTheChoiceSetIsUntouched(){
        List<String> set = List.of("w1", "r1", "d1");
        assertSame(set, DraftSimulator.mustFill(set, set, roster(1, 2, 3, 1, 0), 5, RosterNeedTest::pos),
                "one hole, five picks left: anything goes");
    }

    @Test
    public void lastPickWithNoDefenceIsADefence(){
        List<String> set = List.of("w1", "r1", "d1", "w2", "d2");
        assertEquals(List.of("d1", "d2"),
                DraftSimulator.mustFill(set, set, roster(1, 2, 3, 1, 0), 1, RosterNeedTest::pos));
    }

    @Test
    public void twoHolesTwoPicksConfinesToBoth(){
        List<String> set = List.of("w1", "q1", "r1", "d1");
        assertEquals(List.of("q1", "d1"),
                DraftSimulator.mustFill(set, set, roster(0, 2, 3, 1, 0), 2, RosterNeedTest::pos));
    }

    @Test
    public void widensToTheBoardWhenTheWindowHasNone(){
        List<String> window = List.of("w1", "r1", "w2");
        List<String> board = List.of("w1", "r1", "w2", "w3", "d1", "r2", "d2");
        assertEquals(List.of("d1", "d2"),
                DraftSimulator.mustFill(window, board, roster(1, 2, 3, 1, 0), 1, RosterNeedTest::pos));
    }

    @Test
    public void theKnobTurnsItOff(){
        List<String> set = List.of("w1", "d1");
        System.setProperty("noNeed", "true");
        try {
            assertSame(set, DraftSimulator.mustFill(set, set, roster(1, 2, 3, 1, 0), 1, RosterNeedTest::pos));
        }
        finally {
            System.clearProperty("noNeed");
        }
    }
}
