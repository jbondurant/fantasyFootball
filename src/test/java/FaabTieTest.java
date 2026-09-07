import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * A TIE IS NOT A LOSS.
 *
 * `winChance` counted only prices strictly below the bid, so a $0 claim beat
 * nothing and the model reported that bidding zero never wins - in a league
 * where 772 of 1448 claims CLEARED AT ZERO. Justin found it from the other end,
 * with a $0 claim in that he expected to succeed.
 */
public class FaabTieTest {

    @Test
    void aZeroBidTiesTheZeroClearances(){
        // half the market clears at nothing, half at a dollar
        FaabBid.Band band = new FaabBid.Band("t", 0, Double.MAX_VALUE, List.of(0, 0, 1, 1));
        assertEquals(0.25, band.winChance(0), 1e-9,
                "a zero bid ties the two zero clearances and takes half of them");
        assertEquals(0.75, band.winChance(1), 1e-9,
                "a dollar beats both zeroes outright (2/4) and ties both ones (half of 2/4)");
    }

    @Test
    void theTieShareIsSettableBecauseItIsNotAlwaysAHalf(){
        FaabBid.Band band = new FaabBid.Band("t", 0, Double.MAX_VALUE, List.of(0, 0, 0, 0));
        assertEquals(0.0, band.winChance(0, 0.0), 1e-9, "losing every tie wins nothing");
        assertEquals(1.0, band.winChance(0, 1.0), 1e-9,
                "top waiver priority wins every tie, so a zero bid takes them all");
        assertEquals(0.5, band.winChance(0), 1e-9, "and the default is the coin");
    }

    /** Beating a price outright must still count fully. */
    @Test
    void anOutrightWinIsStillAWin(){
        FaabBid.Band band = new FaabBid.Band("t", 0, Double.MAX_VALUE, List.of(0, 1, 2));
        assertEquals(1.0, band.winChance(9), 1e-9, "nine beats every price here");
        assertEquals(0.0, band.winChance(-1), 1e-9, "and a bid below every price wins none");
    }
}
