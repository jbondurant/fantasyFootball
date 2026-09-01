import PlayerImportAndSetup.Position;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Model A's appetite cap must count the men Justin already owns.
 *
 * The cap allows two quarterbacks and counted only the PLAN, so with Purdy
 * held two drafted men made three and Model A's own plan ended QB QB. That
 * contradicted Justin's own description of the tool: it "pretends we take qb
 * Purdy and rb Tuten, at round 8 and 9, just for calculation purposes".
 *
 * Rounds 1-7 - the whole of Model A's proven domain - contain no quarterback,
 * so this cannot move the plan there, and that is what the second test pins.
 */
public class AppetiteSeesKeepersTest {

    private static DraftPlanner planner() throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        return DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
    }

    @Test
    public void aKeptQuarterbackCountsAgainstTheCap() throws Exception {
        DraftPlanner planner = planner();
        boolean holdsQuarterback = false;
        for(String id : planner.myKeeperIDs()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null && player.position == Position.QB){
                holdsQuarterback = true;
            }
        }
        assertTrue(holdsQuarterback,
                "this test is about a kept quarterback; without one it proves"
                        + " nothing. Justin keeps Purdy.");

        List<Position> plan = planner.plan(60, 0.0, 0.10,
                DraftSimulator.SEED).positions();
        int quarterbacks = 0;
        for(Position position : plan){
            if(position == Position.QB){
                quarterbacks++;
            }
        }
        assertTrue(quarterbacks <= 1,
                "the cap allows two quarterbacks in total and he already owns"
                        + " one, so the plan may draft at most one more - got "
                        + quarterbacks + " in " + plan);
    }

    @Test
    public void theProvenDomainIsUnchanged() throws Exception {
        DraftPlanner planner = planner();
        List<Position> plan = planner.plan(300, 0.0, 0.10,
                DraftSimulator.SEED).positions();
        assertEquals(List.of(Position.RB, Position.WR, Position.RB, Position.WR,
                        Position.WR, Position.WR, Position.TE),
                plan.subList(0, 7),
                "rounds 1-7 are Model A's proven domain and must not move");
    }
}
