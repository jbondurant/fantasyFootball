import PlayerImportAndSetup.Position;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sweep in BustBoomSweep reports that the detection channel never reorders
 * a pick. A null result is only worth something if the channel WORKS, so these
 * tests exist to rule out the alternative explanation - that it reorders
 * nothing because it does nothing.
 *
 * The pool is degenerate on purpose: one observed season, eighteen games, zero
 * within-season spread. That makes every draw deterministic - a player's weekly
 * points are exactly his projection over eighteen - so the assertions can be
 * exact numbers rather than tolerances, and any promotion that happens has to
 * have come from the channel rather than from noise.
 */
class BustBoomValueTest {

    static final String POOL_KEY = "QB:0";

    /** One season, 18 games, no spread: draws become deterministic. */
    static Map<String, List<OutcomeDistributions.Season>> flatPool(){
        Map<String, List<OutcomeDistributions.Season>> pool = new HashMap<>();
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR,
                Position.TE, Position.DEF}){
            pool.put(position + ":0", List.of(new OutcomeDistributions.Season(
                    "flat", position, 0, 18, 10.0, 0.0, 180.0)));
        }
        return pool;
    }

    static BustBoomValue build(Map<String, Double> expected, int scenarios){
        Map<String, Position> positionOf = new HashMap<>();
        Map<String, Integer> tierOf = new HashMap<>();
        for(String id : expected.keySet()){
            positionOf.put(id, Position.QB);
            tierOf.put(id, 0);
        }
        Map<Position, Double> wire = new EnumMap<>(Position.class);
        for(Position position : Position.values()){
            wire.put(position, 0.0);
        }
        return new BustBoomValue(positionOf, tierOf, flatPool(), wire, expected,
                scenarios, 99L);
    }

    /**
     * With no busts and no booms, the lag must not matter at all. If it does,
     * the two regimes differ for some reason other than the channel, and every
     * cell in the sweep is contaminated.
     */
    @Test
    void lagDoesNothingWhenNobodyBustsOrBooms(){
        BustBoomValue value = build(Map.of("a", 180.0, "b", 90.0), 200);
        List<String> roster = List.of("a", "b");
        value.set(new BustBoomValue.Knobs(0, 0, 17, 0.6, 1.6));
        double never = value.of(roster);
        value.set(new BustBoomValue.Knobs(0, 0, 0, 0.6, 1.6));
        double instant = value.of(roster);
        assertEquals(never, instant, 1e-9,
                "with no flags drawn the learned ordering IS the preseason ordering");
    }

    /**
     * The channel fires. One quarterback slot and two quarterbacks, so only the
     * man the lineup ranks first ever scores. Learning who actually boomed can
     * only help, and with flags drawn it must strictly help.
     */
    @Test
    void learningPromotesTheBetterManAndIsWorthSomething(){
        BustBoomValue value = build(Map.of("a", 180.0, "b", 150.0), 4000);
        List<String> roster = List.of("a", "b");
        value.set(new BustBoomValue.Knobs(0.3, 0.3, 17, 0.6, 1.6));
        double blind = value.of(roster);
        value.set(new BustBoomValue.Knobs(0.3, 0.3, 0, 0.6, 1.6));
        double learned = value.of(roster);
        assertTrue(learned > blind + 1.0, "a lineup that learns must beat one that"
                + " cannot, by more than rounding: blind " + blind + " learned " + learned);
    }

    /**
     * The lag is monotone: reacting sooner is worth at least as much as
     * reacting later, and never reacting is the floor. This is the property the
     * sweep's whole "lag is the hinge" reading depends on.
     */
    @Test
    void soonerDetectionIsNeverWorthLess(){
        BustBoomValue value = build(Map.of("a", 180.0, "b", 150.0), 4000);
        List<String> roster = List.of("a", "b");
        double previous = Double.MAX_VALUE;
        for(int lag : new int[]{0, 3, 6, 12, 17}){
            value.set(new BustBoomValue.Knobs(0.3, 0.3, lag, 0.6, 1.6));
            double now = value.of(roster);
            assertTrue(now <= previous + 1e-9, "a longer lag must not be worth MORE:"
                    + " lag " + lag + " gave " + now + " against " + previous);
            previous = now;
        }
    }

    /**
     * Nothing in the promotion may use the week being scored.
     *
     * The flag is a property of the player's SEASON, so the two regimes differ
     * only in the ORDER they start people, never in what anybody scores. Summing
     * the same roster's points both ways when the roster cannot bench anybody -
     * one quarterback, one slot - must give exactly the same answer. If the
     * learned regime could see the week, it would score more here too.
     */
    @Test
    void aRosterWithNoChoiceScoresTheSameEitherWay(){
        BustBoomValue value = build(Map.of("only", 180.0), 500);
        List<String> roster = List.of("only");
        value.set(new BustBoomValue.Knobs(0.3, 0.3, 17, 0.6, 1.6));
        double blind = value.of(roster);
        value.set(new BustBoomValue.Knobs(0.3, 0.3, 0, 0.6, 1.6));
        double learned = value.of(roster);
        assertEquals(blind, learned, 1e-9, "with nobody to promote, learning cannot"
                + " change the score - if it does, the channel is reading the outcome");
    }

    /**
     * At zero rates this must BE WeeklyStarterValue, not merely resemble it.
     * Both are handed the same inputs and draw in the same order; the bust/boom
     * uniforms come from a separate generator precisely so this holds.
     */
    @Test
    void reproducesTheShippedObjectiveWhenTheChannelIsOff(){
        Map<String, Double> expected = new HashMap<>();
        expected.put("a", 180.0);
        expected.put("b", 150.0);
        expected.put("c", 120.0);
        Map<String, Position> positionOf = new HashMap<>();
        Map<String, Integer> tierOf = new HashMap<>();
        for(String id : expected.keySet()){
            positionOf.put(id, Position.QB);
            tierOf.put(id, 0);
        }
        Map<Position, Double> wire = new EnumMap<>(Position.class);
        for(Position position : Position.values()){
            wire.put(position, 0.0);
        }
        BustBoomValue mine = new BustBoomValue(positionOf, tierOf, flatPool(), wire,
                expected, 300, 7L);
        WeeklyStarterValue shipped = new WeeklyStarterValue(positionOf, tierOf,
                flatPool(), wire, expected, 300, 7L);
        mine.set(BustBoomValue.Knobs.off());
        List<String> roster = new ArrayList<>(expected.keySet());
        assertEquals(shipped.of(roster), mine.of(roster), 1e-9,
                "the channel-off model must be the shipped model exactly");
    }
}
