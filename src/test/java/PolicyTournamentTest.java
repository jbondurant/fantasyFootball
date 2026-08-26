import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The composition ledger and the committed-sequence enumeration. */
public class PolicyTournamentTest {

    /** Seven picks owing 1 RB, 3 WR, 1 TE and two flexes - the tournament game. */
    private static PolicyTournament.Needs tournamentNeeds(){
        EnumMap<Position, Integer> dedicated = new EnumMap<>(Position.class);
        dedicated.put(Position.QB, 0);
        dedicated.put(Position.RB, 1);
        dedicated.put(Position.WR, 3);
        dedicated.put(Position.TE, 1);
        return new PolicyTournament.Needs(dedicated, 2);
    }

    @Test
    void quarterbackIsNeverFeasibleOnceTheKeeperFillsTheSlot(){
        PolicyTournament.Needs needs = tournamentNeeds();
        assertFalse(needs.feasible(Position.QB), "QB is not flex-eligible");
        assertTrue(needs.feasible(Position.RB));
        assertTrue(needs.feasible(Position.TE));
    }

    @Test
    void dedicatedSlotsAreConsumedBeforeFlex(){
        PolicyTournament.Needs needs = tournamentNeeds();
        needs.consume(Position.TE);   // the dedicated TE
        assertTrue(needs.feasible(Position.TE), "a second TE rides the flex");
        needs.consume(Position.TE);
        needs.consume(Position.TE);
        assertFalse(needs.feasible(Position.TE), "dedicated and both flexes gone");
        assertThrows(IllegalStateException.class, () -> needs.consume(Position.TE));
    }

    @Test
    void theCommittedSequenceSpaceIsExactlySevenHundredFortyTwo(){
        // Multisets {RB 1+a, WR 3+b, TE 1+c} with a+b+c=2; orderings sum to
        // 140+105+210+42+105+140 = 742.
        List<List<Position>> sequences = PolicyTournament.allSequences(tournamentNeeds(), 7);
        assertEquals(742, sequences.size());
        for(List<Position> sequence : sequences){
            PolicyTournament.Needs replay = tournamentNeeds();
            for(Position position : sequence){
                assertTrue(replay.feasible(position), "infeasible step in " + sequence);
                replay.consume(position);
            }
        }
    }

    @Test
    void headsAreFeasiblePrefixesAtAnyDepth(){
        List<List<Position>> heads = PolicyTournament.allSequences(tournamentNeeds(), 2);
        assertEquals(9, heads.size(), "3 positions x 3 positions, all feasible early");
        assertTrue(heads.contains(List.of(Position.TE, Position.TE)),
                "TE-TE is legal: dedicated then flex");
    }
}
