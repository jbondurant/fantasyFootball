import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * The board arithmetic under the availability draws: serpentine pick numbers
 * and keeper-occupied picks. Offline - an empty model needs no network.
 */
class BoardMathTest {

    @Test
    void serpentinePickNumbers(){
        Assertions.assertEquals(7, AAAConfiguration.pickNumber(1, 7, 12));
        Assertions.assertEquals(18, AAAConfiguration.pickNumber(2, 7, 12), "even rounds reverse");
        Assertions.assertEquals(13, AAAConfiguration.pickNumber(2, 12, 12), "slot 12 opens round 2");
        Assertions.assertEquals(25, AAAConfiguration.pickNumber(3, 1, 12));
        Assertions.assertEquals(31, AAAConfiguration.pickNumber(3, 7, 12));
    }

    @Test
    void keeperOccupiedPicksConsumeNobodyFromTheBoard(){
        AvailabilityModel model = AvailabilityModel
                .build(Map.of(), Map.of(), Map.of(), 20.0, 0.25)
                .withOccupiedPicks(List.of(5, 10));

        Assertions.assertEquals(4, model.effectiveSelectionsBefore(5),
                "the occupied pick itself is not before pick 5");
        Assertions.assertEquals(4, model.effectiveSelectionsBefore(6),
                "pick 5 was a keeper, so only four real selections happened");
        Assertions.assertEquals(9, model.effectiveSelectionsBefore(12));
        Assertions.assertEquals(0, model.effectiveSelectionsBefore(1));
    }

    @Test
    void noOccupiedPicksMeansEverySelectionIsReal(){
        AvailabilityModel model = AvailabilityModel.build(Map.of(), Map.of(), Map.of(), 20.0, 0.25);
        Assertions.assertEquals(102, model.effectiveSelectionsBefore(103));
    }
}
