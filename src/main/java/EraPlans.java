import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every draft plan worth considering, enumerated - the space a fit searches
 * over and a regime test compares across eras.
 *
 * A plan is one position per live pick. Two constraints keep the space to the
 * plans a person could actually run:
 *
 *   THE DEFENCE GOES LAST. The league has never taken one earlier, and neither
 *   does any opponent in this replay, so a defence anywhere else is not a
 *   strategy being tested - it is a wasted pick pretending to be one.
 *
 *   THE LINEUP MUST FILL. QB, RB, RB, WR, WR, WR, TE, FLEX, FLEX, DEF. With
 *   the keepers in hand that leaves a floor of one back, three receivers and a
 *   tight end; plans that cannot start a legal ten are not plans.
 *
 * Everything above the floor is free, which is where the interesting
 * disagreements live: a fourth receiver against a third back, a second tight
 * end, a second quarterback.
 */
public class EraPlans {

    /** All legal plans of this length, defence last. */
    public static List<List<Position>> all(int rounds, Map<Position, Integer> held){
        int free = rounds - 1;                  // the last pick is the defence
        List<List<Position>> plans = new ArrayList<>();
        for(Map<Position, Integer> composition : compositions(free, held)){
            permute(new ArrayList<>(), composition, free, plans);
        }
        for(List<Position> plan : plans){
            plan.add(Position.DEF);
        }
        return plans;
    }

    /** What the starting lineup needs beyond what the keepers already cover. */
    public static Map<Position, Integer> floor(Map<Position, Integer> held){
        Map<Position, Integer> need = new LinkedHashMap<>();
        need.put(Position.QB, 1);
        need.put(Position.RB, 2);
        need.put(Position.WR, 3);
        need.put(Position.TE, 1);
        for(Map.Entry<Position, Integer> entry : held.entrySet()){
            need.computeIfPresent(entry.getKey(),
                    (position, count) -> Math.max(0, count - entry.getValue()));
        }
        return need;
    }

    static List<Map<Position, Integer>> compositions(int picks,
                                                     Map<Position, Integer> held){
        Map<Position, Integer> floor = floor(held);
        int minimum = floor.values().stream().mapToInt(Integer::intValue).sum();
        List<Map<Position, Integer>> compositions = new ArrayList<>();
        if(minimum > picks){
            return compositions;
        }
        Position[] positions = {Position.QB, Position.RB, Position.WR, Position.TE};
        for(int qb = floor.get(Position.QB); qb <= picks; qb++){
            for(int rb = floor.get(Position.RB); rb <= picks; rb++){
                for(int wr = floor.get(Position.WR); wr <= picks; wr++){
                    int te = picks - qb - rb - wr;
                    if(te < floor.get(Position.TE)){
                        continue;
                    }
                    Map<Position, Integer> composition = new LinkedHashMap<>();
                    composition.put(Position.QB, qb);
                    composition.put(Position.RB, rb);
                    composition.put(Position.WR, wr);
                    composition.put(Position.TE, te);
                    compositions.add(composition);
                }
            }
        }
        return compositions;
    }

    static void permute(List<Position> sofar, Map<Position, Integer> left, int picks,
                        List<List<Position>> out){
        if(sofar.size() == picks){
            out.add(new ArrayList<>(sofar));
            return;
        }
        for(Map.Entry<Position, Integer> entry : left.entrySet()){
            if(entry.getValue() <= 0){
                continue;
            }
            entry.setValue(entry.getValue() - 1);
            sofar.add(entry.getKey());
            permute(sofar, left, picks, out);
            sofar.remove(sofar.size() - 1);
            entry.setValue(entry.getValue() + 1);
        }
    }

    public static String shape(List<Position> plan){
        StringBuilder text = new StringBuilder();
        for(Position position : plan){
            // Q R W T D - each position's initial is already unique
            text.append(position == null ? "*" : position.name().charAt(0));
        }
        return text.toString();
    }
}
