import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DraftProgressTest {

    @Test
    void anUndraftedBoardIsRoundOne(){
        Assertions.assertEquals(1, DraftProgress.currentRound(0, 12));
    }

    @Test
    void theRoundTurnsOverOnTheLastPickOfTheRound(){
        Assertions.assertEquals(1, DraftProgress.currentRound(11, 12), "eleven picks in, round one is not done");
        Assertions.assertEquals(2, DraftProgress.currentRound(12, 12), "the twelfth pick completes round one");
        Assertions.assertEquals(2, DraftProgress.currentRound(23, 12));
        Assertions.assertEquals(3, DraftProgress.currentRound(24, 12));
    }

    @Test
    void theRoundFollowsTheLeagueSizeRatherThanAssumingTwelve(){
        Assertions.assertEquals(2, DraftProgress.currentRound(10, 10));
        Assertions.assertEquals(1, DraftProgress.currentRound(10, 12));
    }

    @Test
    void roundsLeftCountsTheRoundInProgress(){
        Assertions.assertEquals(16, DraftProgress.roundsLeft(0, 12, 16));
        Assertions.assertEquals(15, DraftProgress.roundsLeft(12, 12, 16));
        Assertions.assertEquals(1, DraftProgress.roundsLeft(15 * 12, 12, 16));
    }

    @Test
    void aFinishedDraftHasNoRoundsLeftRatherThanNegativeOnes(){
        Assertions.assertEquals(0, DraftProgress.roundsLeft(16 * 12, 12, 16));
        Assertions.assertEquals(0, DraftProgress.roundsLeft(40 * 12, 12, 16));
    }

    @Test
    void anEmptyLeagueIsRejectedRatherThanDividingByZero(){
        Assertions.assertThrows(IllegalArgumentException.class, () -> DraftProgress.currentRound(0, 0));
    }
}
