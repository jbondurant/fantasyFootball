import PlayerImportAndSetup.Position;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * expectedRank must weight survival, not cut off at ADP.
 *
 * The shipped rule was `adpOf(id) < pick`: gone if his ADP beats the seat,
 * there otherwise. That is the rule this repo's own wait-table comment rejects
 * as "false in both directions". RankPrediction measures the two against
 * simulated drafts the survival rule was not fitted to: 2.69 men of error per
 * position-seat for the cutoff, 0.08 for survival.
 *
 * The backtest cannot see this change - it replays historical boards where who
 * went is known rather than predicted - so this is the test that guards it.
 */
public class SurvivalRankTest {

    @AfterEach
    public void clearTheTable(){
        LiveBoard.SURVIVAL = null;
    }

    private static DraftPlanner planner() throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        return DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
    }

    @Test
    public void withNoTableItFallsBackToTheCutoff() throws Exception {
        DraftPlanner planner = planner();
        LiveBoard.SURVIVAL = null;
        for(Position position : List.of(Position.RB, Position.WR, Position.TE)){
            assertEquals(LiveBoard.adpCutoffRank(planner, List.of(), position, 42),
                    LiveBoard.expectedRank(planner, List.of(), position, 42),
                    "with no survival table the old rule must be exactly what runs");
        }
    }

    @Test
    public void theSurvivalRuleIsCloserToTheTruthThanTheCutoff() throws Exception {
        DraftPlanner planner = planner();
        DraftSimulator simulator = planner.simulator();
        LiveBoard.SURVIVAL = new LiveBoard.Survival(planner, simulator, 40, 555L);

        // Truth from draws the table was NOT built from.
        List<Map<String, Integer>> held = new ArrayList<>();
        for(int d = 0; d < 40; d++){
            held.add(simulator.simulateOnce(new Random(9_000_000L + 7919L * d)));
        }
        double cutoffError = 0;
        double survivalError = 0;
        int cells = 0;
        for(int pick : new int[]{18, 42, 79, 114}){
            for(Position position : List.of(Position.RB, Position.WR, Position.TE)){
                double trueGone = 0;
                for(Map<String, Integer> draw : held){
                    int gone = 0;
                    for(Map.Entry<String, Integer> entry : draw.entrySet()){
                        Player player = Player.getPlayerFromSIDV2(entry.getKey());
                        if(player != null && player.position == position
                                && entry.getValue() < pick){
                            gone++;
                        }
                    }
                    trueGone += gone;
                }
                trueGone /= held.size();
                cutoffError += Math.abs(
                        LiveBoard.adpCutoffRank(planner, List.of(), position, pick)
                                - 1 - trueGone);
                survivalError += Math.abs(
                        LiveBoard.expectedRank(planner, List.of(), position, pick)
                                - 1 - trueGone);
                cells++;
            }
        }
        assertTrue(survivalError < cutoffError,
                "survival must predict the arriving rank better than the ADP"
                        + " cutoff: survival " + (survivalError / cells)
                        + " men vs cutoff " + (cutoffError / cells));
    }

    @Test
    public void aManAlreadyTakenCountsAsCertainlyGone() throws Exception {
        DraftPlanner planner = planner();
        DraftSimulator simulator = planner.simulator();
        LiveBoard.SURVIVAL = new LiveBoard.Survival(planner, simulator, 20, 777L);
        // A deep back nobody expects to be gone by pick 18. Taking him must
        // push the count up, never leave it unchanged.
        String deep = null;
        for(String id : planner.points().keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null && player.position == Position.RB
                    && LiveBoard.SURVIVAL.probabilityGone(id, 18) < 0.05){
                deep = id;
                break;
            }
        }
        assertNotNull(deep, "expected some back who almost never goes by pick 18");
        int without = LiveBoard.expectedRank(planner, List.of(), Position.RB, 18);
        int with = LiveBoard.expectedRank(planner, List.of(deep), Position.RB, 18);
        assertTrue(with > without,
                "a man really taken must count as gone even when the prior says"
                        + " he would still be there");
    }
}
