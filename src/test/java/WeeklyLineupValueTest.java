import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
     * The pickups the number takes for granted: a quarterback who outscores the wire
     * starts himself; in his bye the best free one starts, and the week says what that
     * pickup is worth against the men actually held.
     */
    @Test
    public void theFillInsAreTheFreeAgentsTheLineupStarts(){
        List<WeeklyLineupValue.WeekFill> weeks = value(week(15, 18.0), week(15, null)).fillIns(List.of("mine"));
        assertFalse(weeks.get(0).fillIns().containsKey("qbA"), "18 beats the wire's 15");
        assertEquals(15.0, weeks.get(1).fillIns().get("qbA"), 1e-9, "the bye week starts him");
        assertEquals(18 + REST, weeks.get(0).withWire(), 1e-9);
        assertEquals(18.0, weeks.get(0).ownMen(), 1e-9, "without the wire, only the man he holds");
        assertEquals(0.0, weeks.get(1).ownMen(), 1e-9);
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

    /**
     * The search's fast objective is the objective: on hundreds of random rosters, with byes,
     * deep and thin positions and a negative projection, the arrays give what of(roster) gives.
     */
    @Test
    public void theTableIsTheSameObjective(){
        Position[] positions = {Position.QB, Position.RB, Position.WR, Position.TE, Position.DEF, Position.OTHER};
        List<String> men = new ArrayList<>();
        Map<String, Position> at = new HashMap<>();
        java.util.Random random = new java.util.Random(7);
        for(int i = 0; i < 80; i++){
            String id = "m" + i;
            men.add(id);
            at.put(id, positions[i % positions.length]);
        }
        TreeMap<Integer, Map<String, Double>> projected = new TreeMap<>();
        TreeMap<Integer, Map<Position, List<Map.Entry<String, Double>>>> free = new TreeMap<>();
        for(int w = 9; w <= 17; w++){
            Map<String, Double> week = new HashMap<>();
            for(String id : men){
                if(random.nextDouble() < 0.85){
                    week.put(id, random.nextDouble() * 30 - 1);
                }
            }
            Map<Position, List<Map.Entry<String, Double>>> agents = new HashMap<>();
            for(Position p : positions){
                List<Map.Entry<String, Double>> list = new ArrayList<>();
                for(int k = 0; k < (w == 12 ? 1 : 6); k++){
                    String id = p + "free" + k;
                    at.put(id, p);
                    double points = random.nextDouble() * 9;
                    week.put(id, points);
                    list.add(Map.entry(id, points));
                }
                list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
                agents.put(p, list);
            }
            projected.put(w, week);
            free.put(w, agents);
        }
        WeeklyLineupValue value = new WeeklyLineupValue(projected, free, at::get, "test");
        WeeklyLineupValue.Table table = value.table(men);
        double[][] scratch = table.scratch();
        for(int trial = 0; trial < 500; trial++){
            int size = 1 + random.nextInt(19);
            List<String> roster = new ArrayList<>();
            int[] indices = new int[size];
            java.util.Set<Integer> used = new java.util.HashSet<>();
            for(int i = 0; i < size; i++){
                int pick;
                do {
                    pick = random.nextInt(men.size());
                } while(!used.add(pick));
                indices[i] = pick;
                roster.add(men.get(pick));
            }
            assertEquals(value.of(roster), table.of(indices, size, scratch), 1e-9);
        }
    }

    /** A flat rate of 15 for both men: the rostered one takes it, and the free fill-in keeps his one start at 13 and his 2 after. */
    @Test
    public void aSourcePricesRosteredMenAndLeavesTheWireOnSleepersWeeks(){
        TreeMap<Integer, Map<String, Double>> sleeper = new TreeMap<>(Map.of(
                10, Map.of("mine", 12.0, "fillIn", 13.0),
                11, Map.of("mine", 11.0, "fillIn", 2.0)));
        java.util.Set<String> owned = java.util.Set.of("mine");
        TreeMap<Integer, Map<String, Double>> flat = WeeklyLineupValue.priced(sleeper, owned, false,
                Map.of("mine", 15.0, "fillIn", 15.0), Map.of(), Map.of());
        assertEquals(Map.of("mine", 15.0, "fillIn", 13.0), flat.get(10));
        assertEquals(Map.of("mine", 15.0, "fillIn", 2.0), flat.get(11));
        assertEquals(15.0, WeeklyLineupValue.priced(sleeper, owned, true, Map.of("fillIn", 15.0), Map.of(), Map.of()).get(11).get("fillIn"),
                1e-12, "Sleeper's own weeks and the model price every man");
        // FantasyPros' 54.9-point waiver quarterback, 2026-11-09: a free agent's one start times a rank factor of 4.14
        TreeMap<Integer, Map<String, Double>> factor = WeeklyLineupValue.priced(sleeper, owned, false, Map.of(),
                Map.of("mine", 2.0, "fillIn", 4.14), Map.of());
        assertEquals(24.0, factor.get(10).get("mine"), 1e-12);
        assertEquals(13.0, factor.get(10).get("fillIn"), 1e-12);
    }

    /** ESPN has Stevenson empty in week 10 and at 11.3 in week 12, Jeanty in both, and Lamar Jackson (IR) empty throughout. */
    @Test
    public void anEmptyEspnWeekKeepsSleepersNumberAndTheManIsPartlyEspns(){
        Map<Integer, Map<String, Double>> own = WeeklyLineupValue.aboveZero(Map.of(
                10, Map.of("stevenson", 0.0, "jeanty", 12.2, "lamar", 0.0),
                12, Map.of("stevenson", 11.3, "jeanty", 13.9, "lamar", 0.0)));
        assertEquals(Map.of("jeanty", 12.2), own.get(10), "an empty line is not a number");
        TreeMap<Integer, Map<String, Double>> sleeper = new TreeMap<>(Map.of(
                10, Map.of("stevenson", 10.6, "jeanty", 10.5),
                12, Map.of("stevenson", 10.8, "jeanty", 12.6, "lamar", 22.1)));
        java.util.Set<String> owned = java.util.Set.of("stevenson", "jeanty", "lamar");
        TreeMap<Integer, Map<String, Double>> priced = WeeklyLineupValue.priced(sleeper, owned, false, Map.of(), Map.of(), own);
        assertEquals(10.6, priced.get(10).get("stevenson"), 1e-12, "Sleeper's, where ESPN leaves the week empty");
        assertEquals(11.3, priced.get(12).get("stevenson"), 1e-12);
        assertEquals(22.1, priced.get(12).get("lamar"), 1e-12, "ESPN never projects him");
        Map<String, Integer> coverage = WeeklyLineupValue.coverage(sleeper, owned, java.util.Set.of(), own);
        assertEquals(2, coverage.get("jeanty"));
        assertEquals(1, coverage.get("stevenson"));
        assertFalse(coverage.containsKey("lamar"));
        assertEquals(2, WeeklyLineupValue.coverage(sleeper, owned, java.util.Set.of("lamar"), own).get("lamar"), "a season number covers him whole");
    }

    @Test
    public void aNextGameRateIsNotLaidOverADefence(){
        POSITION.put("back", Position.RB);
        POSITION.put("ravens", Position.DEF);
        assertEquals(Map.of("back", 10.0), WeeklyLineupValue.skill(Map.of("back", 10.0, "ravens", 8.4, "nobody", 3.0), POSITION::get));
    }

    @Test
    public void theNextGameIsTheFirstWeekThatCarriesHim(){
        TreeMap<Integer, Map<String, Double>> sleeper = new TreeMap<>(Map.of(
                9, Map.of("a", 10.0),
                10, Map.of("a", 12.0, "b", 7.0),
                11, Map.of("b", 9.0, "c", 5.0)));
        assertEquals(Map.of("a", 10.0, "b", 7.0, "c", 5.0), WeeklyLineupValue.nextGame(sleeper, null));
        // ESPN has a empty in week 9 and at 11.5 in week 10, b never above zero, and c not at all
        Map<Integer, Map<String, Double>> espn = Map.of(9, Map.of("a", 0.0), 10, Map.of("a", 11.5, "b", 0.0), 11, Map.of("b", 0.0));
        assertEquals(Map.of("a", 11.5), WeeklyLineupValue.nextGame(sleeper, espn),
                "an empty line this week is not his rate for the season, and a man ESPN never projects is not ESPN's");
    }

    @Test
    public void aSearchIsRefusedOnAPricingThatOnlyTheCheckTabHas(){
        WeeklyLineupValue.requireFinder("sleeper-remaining");
        WeeklyLineupValue.requireFinder("ros");
        assertThrows(IllegalArgumentException.class, () -> WeeklyLineupValue.requireFinder("pace"));
        assertThrows(IllegalArgumentException.class, () -> WeeklyLineupValue.requireFinder("posterior"));
    }

    @Test
    public void coverageIsFullUnlessThePricingNamesTheMenItHasNumbersFor(){
        assertEquals(2, value(week(20, 15.0)).coverage("anyone"), "Sleeper's own pricings cover every man");
        WeeklyLineupValue partial = new WeeklyLineupValue(new TreeMap<>(), new TreeMap<>(), POSITION::get, "test", Map.of("jeanty", 2, "kalshiOnly", 1));
        assertEquals(2, partial.coverage("jeanty"));
        assertEquals(1, partial.coverage("kalshiOnly"));
        assertEquals(0, partial.coverage("stevenson"), "a man it has no numbers for is on Sleeper's");
    }
}
