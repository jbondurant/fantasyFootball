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

    @org.junit.jupiter.api.Test
    void aKeeperDraftsRoundComesFromSlotsNotCounts(){
        // The 2026-08-29 mock: rounds 1-8 filled through pick 91, plus keepers
        // pre-placed on later slots. 103 rows, 79 selections, truly round 8.
        java.util.Set<Integer> filled = new java.util.HashSet<>();
        for(int pick = 1; pick <= 91; pick++){
            filled.add(pick);
        }
        for(int keeperSlot : new int[]{117, 121, 125, 138, 145, 146, 159}){
            filled.add(keeperSlot);
        }
        Assertions.assertEquals(8,
                DraftProgress.currentRoundOfKeeperDraft(filled, 12),
                "counting rows says 9 and counting selections says 7; the draft is in 8");
    }

    @org.junit.jupiter.api.Test
    void anEmptyKeeperDraftIsInRoundOne(){
        Assertions.assertEquals(1,
                DraftProgress.currentRoundOfKeeperDraft(java.util.Set.of(), 12));
    }

    @org.junit.jupiter.api.Test
    void aKeeperOnPickOneDoesNotHoldTheDraftInRoundOne(){
        Assertions.assertEquals(1,
                DraftProgress.currentRoundOfKeeperDraft(java.util.Set.of(1), 12));
        Assertions.assertEquals(2,
                DraftProgress.currentRoundOfKeeperDraft(
                        java.util.stream.IntStream.rangeClosed(1, 12).boxed()
                                .collect(java.util.stream.Collectors.toSet()), 12));
    }

    @org.junit.jupiter.api.Test
    void roundOfPickIsOneBased(){
        Assertions.assertEquals(1, DraftProgress.roundOfPick(1, 12));
        Assertions.assertEquals(1, DraftProgress.roundOfPick(12, 12));
        Assertions.assertEquals(2, DraftProgress.roundOfPick(13, 12));
        Assertions.assertEquals(8, DraftProgress.roundOfPick(92, 12));
    }
}
