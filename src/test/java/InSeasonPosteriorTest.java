import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** The season number after played weeks is the rule's posterior rate over the games the objective counts. */
public class InSeasonPosteriorTest {

    @Test
    public void oneBigGameMovesASeasonNumberByItsKeptShare(){
        // 238 over 17 = 14.0 a game; one game at 34.8 with kappa 5.87 keeps 1/6.87 of the 20.8 surprise
        double after = InSeasonPosterior.season(238, 34.8, 1, 5.87, 17);
        double expectedRate = (5.87 * 14.0 + 34.8) / 6.87;
        assertEquals(expectedRate * 17, after, 1e-9);
        assertTrue(after > 238 && after < 34.8 * 17, "between the prior and the box score, far nearer the prior");
        assertEquals(WeekReaction.posterior(14.0, 34.8, 1, 5.87) * 17, after, 1e-9, "one home for the rule");
    }

    @Test
    public void noGamesLeavesTheNumberAlone(){
        assertEquals(238.0, InSeasonPosterior.season(238, 0, 0, 5.87, 17), 1e-9);
        assertEquals(238.0, InSeasonPosterior.season(238, 0, 0, 5.87, 13), 1e-9, "the anchor holds at any availability");
    }

    @Test
    public void thePriorRateIsTheSeasonOverTheGamesSleeperExpectsNotOverSeventeen(){
        // 208 over 13 projected games is 16.0 a played game; he plays one at 16.0: nothing to update
        assertEquals(208.0, InSeasonPosterior.season(208, 16.0, 1, 5.87, 13), 1e-9,
                "a man scoring exactly his per-game rate stays where he was");
        // over seventeen the same man would read as a 12.2 prior and his 16.0 would drag him up
        assertTrue(InSeasonPosterior.season(208, 16.0, 1, 5.87, 17) > 208.0,
                "dividing by seventeen mixes units and inflates him - the bug this replaced");
    }
}
