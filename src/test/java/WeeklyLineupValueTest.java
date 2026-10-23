import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class WeeklyLineupValueTest {

    /** A week whose only men are the wire: QB, two backs, three receivers, a tight end, two flexes and a defence. */
    static Map<String, Double> wireWeek(double qb, double rb, double wr, double te, double def){
        Map<String, Double> week = new HashMap<>();
        Map<Position, List<Double>> free = Map.of(
                Position.QB, new java.util.ArrayList<>(List.of(qb)),
                Position.RB, new java.util.ArrayList<>(List.of(rb, rb - 1, rb - 2, rb - 3)),
                Position.WR, new java.util.ArrayList<>(List.of(wr, wr - 1, wr - 2, wr - 3)),
                Position.TE, new java.util.ArrayList<>(List.of(te, te - 1)),
                Position.DEF, new java.util.ArrayList<>(List.of(def)));
        TitleOdds.withWire(week, free);
        return week;
    }

    /**
     * Every week is its own lineup, and the wire fills what the roster cannot:
     * a roster of nobody is worth the wire's best lineup in each week, summed -
     * a pickup costs no roster spot, so nothing is charged for using it.
     */
    @Test
    public void eachWeekIsItsOwnLineupWithTheWireFree(){
        TreeMap<Integer, Map<String, Double>> weeks = new TreeMap<>();
        weeks.put(8, wireWeek(17, 9, 8, 6, 7));
        weeks.put(9, wireWeek(15, 10, 7, 5, 8));
        WeeklyLineupValue value = new WeeklyLineupValue(weeks, "test");
        double week8 = TitleOdds.weekMean(List.of(), weeks.get(8));
        double week9 = TitleOdds.weekMean(List.of(), weeks.get(9));
        assertEquals(week8 + week9, value.of(List.of()), 1e-9);
        // QB 17, RB 9+8, WR 8+7+6, TE 6, FLEX the best two left (RB 7, RB 6 against WR 5), DEF 7
        assertEquals(17 + 9 + 8 + 8 + 7 + 6 + 6 + 7 + 6 + 7, week8, 1e-9);
        assertEquals(List.of(8, 9), value.weeks());
        assertEquals(List.of(17.0), value.wire(8, Position.QB));
        assertEquals(List.of(9.0, 8.0, 7.0, 6.0), value.wire(8, Position.RB));
    }
}
