import PlayerImportAndSetup.Position;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * A defence on the board must not kill the Kim-Nelson arbiter.
 *
 * Two loops in PolicyTournament bucket the board into a map keyed by SKILL
 * positions and index it with the player's own position, so the first defence
 * they met threw a NullPointerException. Defences joined the board when DEF was
 * added to PairwiseOdds.CAP, and Draft2026 forces scheduleRounds=16, so they
 * are always there - the arbiter never ran once in the tool Justin uses, and
 * the catch reported it as an ordinary fallback.
 */
public class ArbiterSurvivesDefencesTest {

    @Test
    public void theWaitingTableFillsOnABoardWithDefences() throws Exception {
        String was = System.getProperty("scheduleRounds");
        System.setProperty("scheduleRounds", "16");
        try {
            AAAConfiguration configuration = AAAConfiguration.getInstance();
            int last = Integer.parseInt(configuration.getSeason()) - 1;
            Map<String, Double> earliness =
                    SelectionModel.qbEarliness(configuration, last);
            ChoiceModel choice =
                    BoostedSelectionModel.fitShipped(configuration, last, earliness);
            DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                    DraftPlanner.keepersFromProperty(configuration), choice, earliness);

            // The board must actually contain a defence, or this test proves
            // nothing about the fault it exists for.
            boolean anyDefence = false;
            for(String id : planner.simulator().players()){
                Player player = Player.getPlayerFromSIDV2(id);
                if(player != null && player.position == Position.DEF){
                    anyDefence = true;
                    break;
                }
            }
            assertTrue(anyDefence,
                    "at scheduleRounds=16 the board must carry defences - if it"
                            + " does not, this test cannot see the fault");

            PolicyTournament tournament =
                    PolicyTournament.forLiveArbitration(planner, List.of());
            assertDoesNotThrow(() -> tournament.fillWaitingTable(2),
                    "a defence on the board must not throw - this is what made"
                            + " the KN arbiter dead in Draft2026");
        }
        finally {
            if(was == null){
                System.clearProperty("scheduleRounds");
            }
            else {
                System.setProperty("scheduleRounds", was);
            }
        }
    }
}
