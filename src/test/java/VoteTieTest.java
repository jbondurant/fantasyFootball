import PlayerImportAndSetup.Position;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * A tied committee vote must be reported as tied.
 *
 * The tally is an EnumMap scanned with strict >, so a 2-2 split silently
 * resolves to whichever position is declared first in Position - QB, RB, WR,
 * TE. It happened on the real board at round 3, pick 31, and printed
 * "2 of 4 engines say RB" as though it were a decision.
 */
public class VoteTieTest {

    @Test
    public void aTwoTwoSplitIsReportedAsTiedNotAsAWinner(){
        Map<Position, Integer> tally = new EnumMap<>(Position.class);
        tally.put(Position.RB, 2);
        tally.put(Position.WR, 2);
        List<Position> leaders = LiveCommittee.topOf(tally);
        assertEquals(2, leaders.size(),
                "a 2-2 split has two leaders, not one winner");
        assertTrue(leaders.contains(Position.RB) && leaders.contains(Position.WR));
    }

    @Test
    public void theTieIsNotBrokenByEnumOrder(){
        // The exact round-3 pick-31 split: RB from lookahead-2 and vorp-greedy,
        // WR from lookahead-1 and hindsight. RB precedes WR in the enum, so the
        // old scan named RB. Both must survive.
        Map<Position, Integer> tally = new EnumMap<>(Position.class);
        tally.put(Position.WR, 2);
        tally.put(Position.RB, 2);
        assertEquals(2, LiveCommittee.topOf(tally).size(),
                "declaration order must not eliminate a tied position");
    }

    @Test
    public void aClearWinnerIsStillASingleLeader(){
        Map<Position, Integer> tally = new EnumMap<>(Position.class);
        tally.put(Position.RB, 3);
        tally.put(Position.WR, 1);
        assertEquals(List.of(Position.RB), LiveCommittee.topOf(tally));
    }

    @Test
    public void everyPositionTiedIsStillATie(){
        Map<Position, Integer> tally = new EnumMap<>(Position.class);
        for(Position position : List.of(Position.QB, Position.RB,
                Position.WR, Position.TE)){
            tally.put(position, 1);
        }
        assertEquals(4, LiveCommittee.topOf(tally).size(),
                "four engines disagreeing four ways is maximal disagreement,"
                        + " not a QB verdict");
    }
}
