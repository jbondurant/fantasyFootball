import PlayerImportAndSetup.Position;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * A man on Justin's roster must be priced at his OWN projection.
 *
 * projectionRanks ranked the whole pool while thisYear builds the curve from
 * the draftable pool, so every held man indexed a list he was being counted
 * against by the twenty-four league keepers, who are not in it. Every one of
 * them came out low - Ja'Marr Chase priced 29.1 points under his own
 * projection, which is to say the moment Justin drafted the best receiver on
 * the board the model priced him as WR2.
 */
public class HeldManCurveIndexTest {

    @Test
    public void aDraftableManIsPricedAtHisOwnProjection() throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        Set<String> kept = LiveBoard.kept(configuration);
        Map<Position, double[]> curve = LiveBoard.thisYear(planner, kept);
        Map<String, Integer> rankOf = LiveBoard.projectionRanks(planner, kept);

        int checked = 0;
        double worst = 0;
        String worstMan = "";
        for(Map.Entry<String, Double> entry : planner.points().entrySet()){
            String id = entry.getKey();
            if(kept.contains(id)){
                continue;   // not in the curve's pool at all
            }
            Player player = Player.getPlayerFromSIDV2(id);
            Integer rank = rankOf.get(id);
            if(player == null || rank == null){
                continue;
            }
            double[] mean = curve.get(player.position);
            if(mean == null || rank >= mean.length || mean[rank] == 0){
                continue;
            }
            double error = Math.abs(mean[rank] - entry.getValue());
            if(error > worst){
                worst = error;
                worstMan = player.firstName + " " + player.lastName;
            }
            checked++;
        }
        assertTrue(checked > 100, "expected to check a real pool, checked " + checked);
        assertEquals(0.0, worst, 0.01,
                "a draftable man must index his own projection in the curve;"
                        + " worst was " + worstMan + " off by " + worst);
    }

    @Test
    public void justinsOwnKeepersStayInTheRanking() throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        Set<String> kept = LiveBoard.kept(configuration);
        Map<String, Integer> rankOf = LiveBoard.projectionRanks(planner, kept);
        // They occupy slots on HIS roster, so they have to be priced. Everyone
        // else's keeper never occupies a slot of his and is removed.
        for(String id : planner.myKeeperIDs()){
            assertNotNull(rankOf.get(id),
                    "Justin's own keeper must still have a rank - he is on the"
                            + " roster being scored");
        }
        int others = 0;
        for(String id : kept){
            if(!planner.myKeeperIDs().contains(id) && rankOf.containsKey(id)){
                others++;
            }
        }
        assertEquals(0, others,
                "another manager's keeper must not sit in the ranking - he is"
                        + " what pushed every held man's rank out of line with"
                        + " the curve");
    }
}
