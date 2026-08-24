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

    /**
     * Rounds still to come, including the one in progress. Never negative: a
     * draft that has run past its scheduled rounds has none left.
     */
    public static int roundsLeft(int picksMade, int numTeams, int totalRounds){
        int remaining = totalRounds - currentRound(picksMade, numTeams) + 1;
        return Math.max(remaining, 0);
    }

}
