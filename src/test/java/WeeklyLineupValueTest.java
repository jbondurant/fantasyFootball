import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    /**
     * FantasyPros' order on Sleeper's scale: the receiver it ranks first is given
     * the largest receiver total Sleeper projects, the one it ranks second the
     * second largest, whoever Sleeper gave them to. Ranked deeper than the totals
     * run, a man takes the last one. A man FantasyPros leaves out, or with nothing
     * left to play, keeps his own; a rank is read only against his own position.
     */
    @Test
    public void aRankIsReadOntoTheTotalsAtItsPosition(){
        Map<String, Double> totals = Map.of("a", 120.0, "b", 100.0, "c", 80.0, "gone", 0.0, "q", 300.0, "solo", 90.0);
        Map<String, Position> at = Map.of("a", Position.WR, "b", Position.WR, "c", Position.WR, "gone", Position.WR,
                "q", Position.QB, "solo", Position.WR);
        Map<String, Integer> rank = Map.of("b", 1, "a", 2, "c", 9, "gone", 3, "q", 1);
        Map<String, Double> scale = WeeklyLineupValue.rankScale(totals, rank, at::get);
        assertEquals(120.0, 100.0 * scale.get("b"), 1e-9, "ranked first: the largest total at receiver");
        assertEquals(100.0, 120.0 * scale.get("a"), 1e-9, "ranked second: the second largest");
        assertEquals(80.0, 80.0 * scale.get("c"), 1e-9, "ranked ninth of four: the last total");
        assertEquals(300.0, 300.0 * scale.get("q"), 1e-9, "a quarterback is read against quarterbacks only");
        assertFalse(scale.containsKey("gone"), "nothing left to play: nothing to scale");
        assertFalse(scale.containsKey("solo"), "unranked: Sleeper's own numbers");
    }

    /**
     * Read per game, not per season. A quarterback Sleeper projects to start only
     * two of four weeks, ranked second, gets the second best rate a game (15) in
     * those two weeks: 30, not the second largest season total (60) crammed into
     * them, which is how the first version priced free agent Deshaun Watson at 48
     * a week (2026-10-29).
     */
    @Test
    public void aRankIsReadPerGameInTheWeeksSleeperHasHimPlaying(){
        Map<String, Position> at = Map.of("ace", Position.QB, "vet", Position.QB, "part", Position.QB);
        List<Map<String, Double>> weeks = List.of(
                Map.of("ace", 20.0, "vet", 15.0, "part", 10.0),
                Map.of("ace", 20.0, "vet", 15.0, "part", 10.0),
                Map.of("ace", 20.0, "vet", 15.0),
                Map.of("ace", 20.0, "vet", 15.0));
        Map<String, Double> scale = WeeklyLineupValue.perGameScale(weeks, Map.of("ace", 1, "part", 2, "vet", 3), at::get);
        assertEquals(30.0, 2 * 10.0 * scale.get("part"), 1e-9, "two games at the second best rate a game");
        assertEquals(10.0, 15.0 * scale.get("vet"), 1e-9, "ranked third: the third best rate a game");
        assertEquals(1.0, scale.get("ace"), 1e-9);
    }
}
