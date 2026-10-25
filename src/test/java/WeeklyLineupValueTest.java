import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class WeeklyLineupValueTest {

    static final Map<String, Position> POSITION = new HashMap<>();

    /**
     * One week: free agents at every position, "qbA" the best free quarterback
     * at qb points and "qbB" the next at 10, and a rostered quarterback "mine"
     * projected at `mine` (absent when null: a bye).
     */
    static Object[] week(double qb, Double mine){
        Map<String, Double> projected = new HashMap<>();
        Map<Position, List<Map.Entry<String, Double>>> free = new HashMap<>();
        free.put(Position.QB, new ArrayList<>(List.of(Map.entry("qbA", qb), Map.entry("qbB", 10.0))));
        free.put(Position.RB, new ArrayList<>(List.of(Map.entry("rb1", 6.0), Map.entry("rb2", 5.0), Map.entry("rb3", 4.0), Map.entry("rb4", 3.0))));
        free.put(Position.WR, new ArrayList<>(List.of(Map.entry("wr1", 6.0), Map.entry("wr2", 5.0), Map.entry("wr3", 4.0), Map.entry("wr4", 3.0))));
        free.put(Position.TE, new ArrayList<>(List.of(Map.entry("te1", 5.0), Map.entry("te2", 4.0))));
        free.put(Position.DEF, new ArrayList<>(List.of(Map.entry("d1", 7.0))));
        free.forEach((p, list) -> list.forEach(e -> {
            projected.put(e.getKey(), e.getValue());
            POSITION.put(e.getKey(), p);
        }));
        POSITION.put("mine", Position.QB);
        if(mine != null){
            projected.put("mine", mine);
        }
        return new Object[]{projected, free};
    }

    @SuppressWarnings("unchecked")
    static WeeklyLineupValue value(Object[]... weeks){
        TreeMap<Integer, Map<String, Double>> projected = new TreeMap<>();
        TreeMap<Integer, Map<Position, List<Map.Entry<String, Double>>>> free = new TreeMap<>();
        for(int i = 0; i < weeks.length; i++){
            projected.put(8 + i, (Map<String, Double>) weeks[i][0]);
            free.put(8 + i, (Map<Position, List<Map.Entry<String, Double>>>) weeks[i][1]);
        }
        return new WeeklyLineupValue(projected, free, POSITION::get, "test");
    }

    /** The rest of a lineup filled from these free agents: RB 6+5, WR 6+5+4, TE 5, FLEX 4 (RB) + 4 (WR)... the best two left, DEF 7. */
    static final double REST = 6 + 5 + 6 + 5 + 4 + 5 + 4 + 4 + 7;

    /**
     * Each week is its own lineup: a quarterback at 18 against a wire of 15
     * starts and is worth 3 over it; in his bye week the wire's 15 starts and he
     * is worth nothing that week - the hole costs the gap to the wire, not the
     * slot, and the pickup costs no roster spot.
     */
    @Test
    public void aByeIsFilledFromTheWireThatWeek(){
        WeeklyLineupValue v = value(week(15, 18.0), week(15, null));
        assertEquals((18 + REST) + (15 + REST), v.of(List.of("mine")), 1e-9);
        assertEquals((15 + REST) + (15 + REST), v.of(List.of()), 1e-9);
        assertEquals(List.of(8, 9), v.weeks());
        assertEquals(List.of(15.0), v.wire(8, Position.QB));
    }

    /**
     * A CLAIM is priced against the wire without him. The best free quarterback
     * (20) claimed onto a roster with nobody at quarterback: before, with him
     * gone from the wire, the next man (10) starts; after, he does - worth 10 a
     * week. Priced against a "before" in which every lineup could already start
     * him, the same claim would read as worth nothing.
     */
    @Test
    public void aClaimIsWorthWhatHeAddsOverTheWireHeLeaves(){
        WeeklyLineupValue v = value(week(20, null));
        double after = v.of(List.of("qbA"));
        assertEquals(20 + REST, after, 1e-9, "a man held is not also on the wire: no double count");
        assertEquals(10.0, after - v.of(List.of(), List.of("qbA")), 1e-9);
        assertEquals(0.0, after - v.of(List.of()), 1e-9, "against a wire that still has him, nothing");
    }
}
