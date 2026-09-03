/**
 * Where a draft has got to, from the picks made so far.
 *
 * Pulled out of SleeperLiveDraft's main so it can be tested without a draft in
 * progress - the simulator is handed "rounds left", and getting this off by one
 * makes it plan for the wrong number of picks.
 */
public class DraftProgress {

    /** 1 before a pick is made, 2 once the first round is complete. */
    public static int currentRound(int picksMade, int numTeams){
        if(numTeams <= 0){
            throw new IllegalArgumentException("a draft needs at least one team, got " + numTeams);
        }
        if(picksMade < 0){
            throw new IllegalArgumentException("picks made cannot be negative, got " + picksMade);
        }
        return (picksMade / numTeams) + 1;
    }

    /** The round a given 1-based pick number falls in. */
    public static int roundOfPick(int pickNumber, int numTeams){
        if(numTeams <= 0){
            throw new IllegalArgumentException("a draft needs at least one team, got "
                    + numTeams);
        }
        if(pickNumber < 1){
            throw new IllegalArgumentException("pick numbers start at 1, got " + pickNumber);
        }
        return ((pickNumber - 1) / numTeams) + 1;
    }

    /**
     * The round in progress in a KEEPER draft, where counting picks does not
     * work.
     *
     * This league pre-places every keeper on the slot he costs, so the pick
     * list is not contiguous: on 2026-08-29 the mock held 103 rows - 79 real
     * selections plus 24 keepers, twelve of them in rounds 3-8 and twelve in
     * rounds 10-14. Counting all the rows said round 9; counting only the
     * selections said round 7; the draft was in round 8. Neither count can be
     * right, because a keeper consumes a slot without being a selection and a
     * keeper in round 14 consumes a slot that has not been reached.
     *
     * The round in progress is simply the round of the first slot nobody has
     * filled yet.
     */
    public static int currentRoundOfKeeperDraft(java.util.Set<Integer> filledPickNumbers,
                                                int numTeams){
        int next = 1;
        while(filledPickNumbers.contains(next)){
            next++;
        }
        return roundOfPick(next, numTeams);
    }

    /**
     * Rounds still to come, including the one in progress. Never negative: a
     * draft that has run past its scheduled rounds has none left.
     */
    public static int roundsLeft(int picksMade, int numTeams, int totalRounds){
        int remaining = totalRounds - currentRound(picksMade, numTeams) + 1;
        return Math.max(remaining, 0);
    }

}
