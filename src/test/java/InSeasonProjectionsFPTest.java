import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InSeasonProjectionsFPTest {

    /**
     * A rank is the median of the days' pulls: one day's panel of experts cannot
     * move it alone, and with two days it is the better of the two.
     */
    @Test
    public void aRankIsTheMedianOfTheDaysPulls(){
        assertEquals(7, InSeasonProjectionsFP.median(List.of(7)));
        assertEquals(7, InSeasonProjectionsFP.median(List.of(12, 7)));
        assertEquals(9, InSeasonProjectionsFP.median(List.of(7, 30, 9)));
    }
}
