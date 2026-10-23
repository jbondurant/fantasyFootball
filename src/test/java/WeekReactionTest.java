import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import PlayerImportAndSetup.Position;

import java.util.List;
import java.util.Map;

/** The after-the-week arithmetic, and the verdict rule on its stated assumption. */
public class WeekReactionTest {

    @Test
    public void oneGameMovesTheEstimateByItsShareOfTheSurprise(){
        // prior 10 a game, he scored 30, kappa 9 games: the rule keeps a tenth of the twenty
        assertEquals(12.0, WeekReaction.posterior(10, 30, 1, 9), 1e-9);
        assertEquals(0.1, WeekReaction.keptShare(1, 9), 1e-9);
        assertEquals(10.0, WeekReaction.posterior(10, 30, 0, 9), 1e-9, "no games: the prior stands");
        // four games at 30 keep 4/13 of it
        assertEquals(10 + 20 * 4.0 / 13, WeekReaction.posterior(10, 30, 4, 9), 1e-9);
        assertEquals(4.0 / 13, WeekReaction.keptShare(4, 9), 1e-9);
    }

    @Test
    public void zIsTheSurpriseOverTheWeeklyScatterAndTightensWithGames(){
        assertEquals(4.0, WeekReaction.z(10, 30, 1, 5), 1e-9);
        assertEquals(8.0, WeekReaction.z(10, 30, 4, 5), 1e-9, "the mean of four games has half the scatter");
        assertEquals(0.0, WeekReaction.z(10, 30, 0, 5), 1e-9, "no games, no surprise");
        assertEquals(0.0, WeekReaction.z(10, 30, 1, 0), 1e-9, "a degenerate scatter never divides by zero");
    }

    @Test
    public void thePriorIsTheLastSnapshotBeforeTheSeasonStarted(){
        List<String> days = List.of("2026-08-24", "2026-09-08", "2026-09-11", "2026-09-14");
        assertEquals("2026-09-08", WeekReaction.priorDay(days, "2026-09-09"));
        assertEquals("2026-08-24", WeekReaction.priorDay(days, "2026-09-08"), "strictly before: the start day itself is not a prior");
        assertNull(WeekReaction.priorDay(days, "2026-08-01"), "nothing before the start: no prior, and the tool must say so");
    }

    @Test
    public void theLevelIsTheMeanOfTheMenARosterCouldReach(){
        Map<String, Double> ppg = Map.of("a", 20.0, "b", 10.0, "c", 4.0, "q", 25.0);
        Map<String, Position> pos = Map.of("a", Position.RB, "b", Position.RB, "c", Position.RB, "q", Position.QB);
        assertEquals(15.0, WeekReaction.level(ppg, pos, Position.RB, 2), 1e-9, "the top two backs, not the third");
        assertEquals(25.0, WeekReaction.level(ppg, pos, Position.QB, 24), 1e-9);
        assertEquals(1.0, WeekReaction.level(ppg, pos, Position.TE, 24), 1e-9, "no men: a unit level, never zero");
    }

    @Test
    public void aBigWeekIsSoldUnlessSleeperRaisedHimAndABadWeekIsBoughtUnlessSleeperCutHim(){
        // mine
        assertTrue(WeekReaction.verdict(true, false, 2.0, 0, 3, false).startsWith("SELL HIGH"));
        assertTrue(WeekReaction.verdict(true, false, 2.0, 5, 3, false).startsWith("KEEP"));
        assertTrue(WeekReaction.verdict(true, false, -2.0, 0, 3, false).startsWith("HOLD"));
        assertTrue(WeekReaction.verdict(true, false, -2.0, -5, 3, false).startsWith("SELL OR DROP"));
        // theirs
        assertTrue(WeekReaction.verdict(false, false, 2.0, 0, 3, false).startsWith("DON'T CHASE"));
        assertTrue(WeekReaction.verdict(false, false, 2.0, 5, 3, false).startsWith("PAY UP OR PASS"));
        assertTrue(WeekReaction.verdict(false, false, -2.0, 0, 3, false).startsWith("BUY LOW"));
        assertTrue(WeekReaction.verdict(false, false, -2.0, -5, 3, false).startsWith("LEAVE HIM"));
        // the wire
        assertTrue(WeekReaction.verdict(false, true, 2.0, 0, 3, false).startsWith("DON'T CHASE"));
        assertTrue(WeekReaction.verdict(false, true, 2.0, 5, 3, false).startsWith("WORTH A CLAIM"));
        assertEquals("", WeekReaction.verdict(false, true, -2.0, 0, 3, false), "a free man's bad week is nobody's decision");
    }

    @Test
    public void anOrdinaryWeekSaysNothingAndAnInjuryOverridesEverything(){
        assertEquals("", WeekReaction.verdict(true, false, 0.9, 0, 3, false));
        assertEquals("", WeekReaction.verdict(false, false, -0.9, 20, 3, false), "inside his own scatter: no verdict even if Sleeper moved");
        assertEquals("HURT", WeekReaction.verdict(true, false, -3.0, 0, 3, true), "a bad week from a man now Out is the injury, not a buy");
        assertEquals("HURT", WeekReaction.verdict(false, false, 3.0, 0, 3, true));
    }

    @Test
    public void onlyTheFourFittedPositionsAreRead(){
        assertEquals(Position.RB, WeekReaction.skill("RB"));
        assertEquals(Position.TE, WeekReaction.skill("TE"));
        assertNull(WeekReaction.skill("DEF"), "kappa was never fitted for a defence");
        assertNull(WeekReaction.skill("K"));
        assertNull(WeekReaction.skill(null));
    }
}
