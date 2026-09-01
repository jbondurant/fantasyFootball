import PlayerImportAndSetup.Position;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A man the rules decline is still on the roster.
 *
 * The live board reads Justin's picks back from Sleeper, which is the
 * authority on what he owns. A pick these rules would have refused used to go
 * into a print list and nowhere else, so the quarterback ceiling of two was
 * counted against one - a route to a third quarterback through the type's own
 * refusal becoming amnesia - and `full()` read fifteen on a roster of sixteen,
 * which let the tool price a seventeenth man past the end of the draft.
 */
public class DeclinedManStillCountsTest {

    /**
     * Every named starting slot filled, one quarterback, legally.
     *
     * It has to be a FULL lineup: on an empty roster the rules refuse a second
     * quarterback at round 10 for an unrelated reason - it would strand the
     * slots still unfilled - and that refusal would hide the one being tested.
     */
    private static RosterRules.Roster withStartersAndOneQuarterback(){
        RosterRules.Roster roster = RosterRules.live().empty();
        int round = 1;
        for(Map.Entry<Position, Integer> need
                : new java.util.LinkedHashMap<>(roster.stillNeeds()).entrySet()){
            for(int i = 0; i < need.getValue(); i++){
                roster = roster.draft(need.getKey().name().toLowerCase() + i,
                        need.getKey(), round++);
            }
        }
        return roster;
    }

    @Test
    public void aDeclinedManOccupiesHisSeat(){
        RosterRules.Roster roster = withStartersAndOneQuarterback();
        // Round 2 is exactly what the rules refuse: a second quarterback before
        // round 10 is only ever a keeper stash. Sleeper says he is on the
        // roster regardless.
        assertNotNull(roster.whyNotDraft(Position.QB, 2),
                "the fixture must be a pick the rules actually decline");
        RosterRules.Roster after = roster.holdAnyway("qb2", Position.QB, 2);
        assertEquals(2, after.count(Position.QB),
                "a man the rules refused is still on the roster");
        assertEquals(roster.size() + 1, after.size(),
                "he occupies a seat, which is what makes full() honest");
    }

    @Test
    public void draftStillRefusesWhatTheRulesRefuse(){
        RosterRules.Roster roster = withStartersAndOneQuarterback();
        assertThrows(RosterRules.IllegalRoster.class,
                () -> roster.draft("qb2", Position.QB, 2),
                "holdAnyway must not weaken draft() - no model may PLAN an"
                        + " illegal roster, it may only be TOLD about one");
    }

    @Test
    public void countingHimClosesTheThirdQuarterbackRoute(){
        RosterRules.Roster forgotten = withStartersAndOneQuarterback();
        RosterRules.Roster remembered = forgotten.holdAnyway("qb2", Position.QB, 2);
        // Round 10 is where a second quarterback becomes legal. With the
        // refused man forgotten the rules allow one there; with him counted
        // they must not. That difference is the whole fault.
        assertTrue(forgotten.canDraft(Position.QB, 10),
                "one quarterback held leaves room for a second at round 10");
        assertFalse(remembered.canDraft(Position.QB, 10),
                "two held - one of them refused - must leave room for none");
    }
}
