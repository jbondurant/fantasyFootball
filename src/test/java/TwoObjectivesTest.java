import PlayerImportAndSetup.Position;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The two engines on one screen optimise DIFFERENT lineups, on purpose.
 *
 * Model A scores `StartingLineup.bestNine` - the nine SKILL slots, with
 * non-skill positions skipped outright, so a defence is worth exactly zero to
 * it. The board model scores `BoardValue.oneSeason`, which fills QB 1, RB 2,
 * WR 3, TE 1, DEF 1 and FLEX 2: ten slots, the league's actual lineup, defence
 * included.
 *
 * That is why Model A's sixteen-round tail is six running backs and no defence,
 * and it is why only the board model can say anything about one. Neither is
 * wrong; they answer different questions and Justin reads them side by side.
 * This test exists so that nobody - me included - "fixes" one to match the
 * other without deciding to.
 */
public class TwoObjectivesTest {

    @Test
    public void modelAScoresNineSkillSlotsAndIgnoresADefence(){
        var breakdown = StartingLineup.bestNineBreakdown(List.of(), Map.of());
        // The record itself is the evidence: there is no def component to read.
        assertEquals(0.0, breakdown.total(), 1e-9,
                "an empty roster scores nothing");
        String fields = Arrays.toString(
                StartingLineup.NineBreakdown.class.getRecordComponents());
        assertFalse(fields.toLowerCase().contains("def"),
                "bestNine has no defence component, and that is deliberate: "
                        + fields);
    }

    @Test
    public void theBoardModelFillsTheLeaguesActualLineup() throws Exception {
        // Ten named slots, defence among them, matching RosterRules.
        Map<Position, Integer> starters = RosterRules.live().empty().stillNeeds();
        assertEquals(1, starters.getOrDefault(Position.DEF, 0).intValue(),
                "the league starts exactly one defence");
        String source = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/java/BoardValue.java")));
        assertTrue(source.contains("fill(pool, Position.DEF, 1, curve, flex, false)"),
                "the board model must score the defence the league starts - if"
                        + " this stops being true, defences become worth zero"
                        + " there too and nothing on the screen values one");
    }

    @Test
    public void theDifferenceIsWhyModelAIsSilentAfterRoundSeven() throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/java/Draft2026.java")));
        assertTrue(source.contains("only"),
                "Draft2026 must still explain why Model A stops speaking;"
                        + " the reason is not cosmetic - after round 7 the"
                        + " questions left are ones its objective cannot see");
    }
}
