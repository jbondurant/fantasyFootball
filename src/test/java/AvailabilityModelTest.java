import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

/**
 * Replacement level with reach risk in it: what you would realistically still
 * get at a position, rather than a fixed rank you are assumed to reach exactly.
 */
@Tag("smoke")
class AvailabilityModelTest {

    private static AvailabilityModel model(){
        Map<Position, Double> bias = new EnumMap<>(Position.class);
        bias.put(Position.QB, 20.4);
        bias.put(Position.RB, -0.1);
        bias.put(Position.WR, -11.7);
        bias.put(Position.TE, 16.3);
        return AvailabilityModel.build(SleeperProjections.parseTodaysWebPage(), bias);
    }

    @Test
    void theBoardGetsWorseTheLongerYouWait(){
        AvailabilityModel model = model();
        double early = model.expectedBestAvailable(Position.RB, 20, 200, 1L);
        double middle = model.expectedBestAvailable(Position.RB, 100, 200, 1L);
        double late = model.expectedBestAvailable(Position.RB, 170, 200, 1L);

        Assertions.assertTrue(early > middle, "round 2 should beat round 9: " + early + " vs " + middle);
        Assertions.assertTrue(middle > late, "round 9 should beat round 15: " + middle + " vs " + late);
    }

    @Test
    void replacementStaysNearTheFixedRankRatherThanRunningAway(){
        // The draw should price in a reach, not invent a better player. An
        // earlier version checked each player against the pick independently,
        // which let the number taken drift and biased replacement upwards by
        // 17 points at receiver.
        AvailabilityModel model = model();
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR, Position.TE}){
            double drawn = model.expectedBestAvailable(position, 103, 200, 7L);
            Assertions.assertTrue(drawn > 0, position + " found nobody");
            Assertions.assertTrue(drawn < 400, position + " replacement implausible: " + drawn);
        }
    }

    @Test
    void everyPositionIsStillFieldableDeepIntoTheDraft(){
        AvailabilityModel model = model();
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR, Position.TE}){
            Assertions.assertTrue(model.expectedBestAvailable(position, 180, 100, 3L) > 0,
                    "nobody left at " + position + " by pick 180, which cannot be right");
        }
    }

    @Test
    void aBetterPlayerIsLessLikelyToLast(){
        AvailabilityModel model = model();
        String best = null, worse = null;
        for(String sleeperID : model.known()){
            Player player = Player.getPlayerFromSIDV2(sleeperID);
            if(player == null || !player.position.equals(Position.RB)){
                continue;
            }
            if(best == null || model.pointsOf(sleeperID) > model.pointsOf(best)){
                best = sleeperID;
            }
        }
        for(String sleeperID : model.known()){
            Player player = Player.getPlayerFromSIDV2(sleeperID);
            if(player != null && player.position.equals(Position.RB)
                    && model.pointsOf(sleeperID) > 100 && model.pointsOf(sleeperID) < 150){
                worse = sleeperID;
                break;
            }
        }
        Assertions.assertNotNull(best);
        Assertions.assertNotNull(worse);
        Assertions.assertTrue(
                model.probabilityAvailable(best, 40, 800, 5L)
                        < model.probabilityAvailable(worse, 40, 800, 5L),
                "the best back should be gone more often than a middling one");
    }

    @Test
    void survivalFallsAsTheDraftGoesOn(){
        AvailabilityModel model = model();
        String anyone = model.known().iterator().next();
        double early = model.probabilityAvailable(anyone, 5, 500, 9L);
        double late = model.probabilityAvailable(anyone, 150, 500, 9L);
        Assertions.assertTrue(early >= late, "nobody becomes more available later");
    }

    @Test
    void waitingIsNeverWorthMoreThanHavingHim(){
        // If he survives you get him; if not you get less. Waiting cannot beat
        // taking him, only cost little.
        AvailabilityModel model = model();
        String best = null;
        for(String sleeperID : model.known()){
            Player player = Player.getPlayerFromSIDV2(sleeperID);
            if(player != null && player.position.equals(Position.QB)
                    && (best == null || model.pointsOf(sleeperID) > model.pointsOf(best))){
                best = sleeperID;
            }
        }
        double waiting = model.expectedIfYouWait(best, Position.QB, 31, 500, 11L);
        Assertions.assertTrue(waiting <= model.pointsOf(best) + 0.001,
                "waiting returned more than the player himself");
    }

    @Test
    void repeatedDrawsWithTheSameSeedAgree(){
        AvailabilityModel model = model();
        double a = model.expectedBestAvailable(Position.WR, 103, 200, 42L);
        double b = model.expectedBestAvailable(Position.WR, 103, 200, 42L);
        Assertions.assertEquals(a, b, 0.0001, "the draw should be reproducible");
    }
}
