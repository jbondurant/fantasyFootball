import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;

import java.util.EnumMap;
import java.util.Map;

/**
 * The lineup being optimised: the skill slots only.
 *
 * The league starts QB, RB, RB, WR, WR, WR, TE, FLEX, FLEX, DEF. The defense is
 * deliberately left out of this. It is filled from a late pick every year - the
 * league's average first defense goes in round 15 - it never competes for the
 * picks that decide a season, and the whole position spans 19 points from best
 * to twelfth. Optimising the nine skill slots is the actual problem; the
 * defense is a rounding error attached to it.
 *
 * That also means a defense can never be worth a keeper slot here, because it
 * cannot fill any of the nine.
 *
 * The flex split is not assumed here; ReplacementLevel fills flex greedily
 * from the projection pool, so the split is an output, not a constant.
 */
public class StartingLineup {

    public static final int SKILL_SLOTS = 9;

    /** Starters at each position, before the flex slots are handed out. */
    private static final Map<Position, Integer> FIXED = new EnumMap<>(Position.class);
    static {
        FIXED.put(Position.QB, 1);
        FIXED.put(Position.RB, 2);
        FIXED.put(Position.WR, 3);
        FIXED.put(Position.TE, 1);
    }

    public static boolean isSkillPosition(Position position){
        return FIXED.containsKey(position);
    }

    /** Counts the FLEX slots the league actually rosters. */
    public static int flexSlotsPerTeam(AAAConfiguration configuration){
        int flex = 0;
        for(JsonElement slot : configuration.getLeagueJson().getAsJsonArray("roster_positions")){
            if(slot.getAsString().equals("FLEX")){
                flex++;
            }
        }
        return flex;
    }

    /**
     * The round by which the skill starters are filled, and therefore the pick
     * a keeper really costs. Nine skill slots means nine picks; the defense
     * comes later and out of a pick nobody wants.
     */
    public static int lastStarterRound(){
        return SKILL_SLOTS;
    }

    /** FLEX slots in the nine (RB/WR/TE; a QB can never flex). */
    public static final int FLEX_SLOTS = 2;

    /**
     * The scoring rule of the nine-round game: the best legal fill of
     * QB, RB, RB, WR, WR, WR, TE, FLEX, FLEX from these players. Greedy is
     * optimal here - fixed slots take the best at their position, the flexes
     * take the best two left over - and unfilled slots score zero. A round-13
     * keeper "costing a round 9" is emergent: he only displaces the weakest
     * of the nine, never a specific pick.
     */
    public static double bestNine(java.util.Collection<String> sleeperIDs,
                                  Map<String, Double> points){
        Map<Position, java.util.List<Double>> byPosition = new EnumMap<>(Position.class);
        for(Position position : FIXED.keySet()){
            byPosition.put(position, new java.util.ArrayList<>());
        }
        for(String sleeperID : sleeperIDs){
            Player player = Player.getPlayerFromSIDV2(sleeperID);
            if(player == null || !isSkillPosition(player.position)){
                continue;
            }
            byPosition.get(player.position).add(points.getOrDefault(sleeperID, 0.0));
        }
        double total = 0;
        java.util.List<Double> flexPool = new java.util.ArrayList<>();
        for(Map.Entry<Position, Integer> slot : FIXED.entrySet()){
            java.util.List<Double> have = byPosition.get(slot.getKey());
            have.sort(java.util.Comparator.reverseOrder());
            for(int s = 0; s < slot.getValue() && s < have.size(); s++){
                total += have.get(s);
            }
            if(!slot.getKey().equals(Position.QB)){
                for(int s = slot.getValue(); s < have.size(); s++){
                    flexPool.add(have.get(s));
                }
            }
        }
        flexPool.sort(java.util.Comparator.reverseOrder());
        for(int s = 0; s < FLEX_SLOTS && s < flexPool.size(); s++){
            total += flexPool.get(s);
        }
        return total;
    }

}
