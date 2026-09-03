import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Is it worth filling the tight end slot before adding another back or
 * receiver?
 *
 * The question sounds like starter-versus-bench and is not. This league starts
 * QB, RB, RB, WR, WR, WR, TE, FLEX, FLEX, DEF - so with two flex slots, a
 * "backup" receiver walks straight into the lineup. Both candidates fill a
 * STARTING slot. The only question is which slot is worth more to fill, and the
 * answer changes as the roster fills up.
 *
 * Measured as the marginal starter-sum value of the best available man at each
 * position, against a roster built by taking backs and receivers and never a
 * tight end - so each row asks "at THIS pick, with THIS roster, what would the
 * tight end have been worth instead?"
 *
 * Lineups inside the objective are set on expected points and scored on
 * realised ones; sorting on realised points is hindsight and it is what made an
 * earlier version stack quarterbacks and defences.
 *
 *   ./gradlew run -Pmain=TeOrDepth [-Pscenarios=1500]
 */
public class TeOrDepth {

    /** Slot 7, keepers at r12 and r13. */
    static final int[] MY_PICKS = {7, 18, 31, 42, 55, 66, 79, 90, 103, 114, 127};

    public static void main(String[] args) throws Exception {
        int scenarios = Integer.getInteger("scenarios", 1500);
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Map<String, Double> projections = SleeperProjections.parseTodaysWebPage();

        // the board in ADP order - who is actually gone by each pick
        List<String> board = new ArrayList<>();
        Map<String, Position> positionOf = new HashMap<>();
        for(String id : projections.keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null && (StartingLineup.isSkillPosition(player.position)
                    || player.position == Position.DEF)){
                positionOf.put(id, player.position);
                board.add(id);
            }
        }
        board.sort(Comparator.comparingDouble(SleeperProjections::adpOf));

        Map<String, Integer> tierOf = new HashMap<>();
        Map<Position, Integer> next = new EnumMap<>(Position.class);
        for(String id : board){
            tierOf.put(id, (next.merge(positionOf.get(id), 1, Integer::sum) - 1)
                    / WeeklyStarterValue.TIER);
        }
        WeeklyStarterValue value = WeeklyStarterValue.forCurrentBoard(configuration,
                projections, scenarios, 424_242L);

        // my keepers are already on the roster and cost no early pick
        List<String> mine = new ArrayList<>();
        for(Keeper keeper : DraftPlanner.keepersFromProperty(configuration)){
            mine.add(keeper.player.sleeperIDString);
        }
        if(mine.isEmpty()){
            for(Keeper keeper : configuration.getTodaysKeepers()){
                if("justinb314".equals(HumanOfInterest.getHumanFromID(
                        keeper.humanWhoCanKeep))){
                    mine.add(keeper.player.sleeperIDString);
                }
            }
        }
        System.out.printf("%nstarting from %d keepers: %s%n", mine.size(), names(mine));
        System.out.println("roster then built by taking the best back or receiver each"
                + " pick, never a tight end,\nso every row asks what a tight end would"
                + " have been worth INSTEAD, right there.\n");

        System.out.printf("%-6s %-6s %-16s %12s %12s %12s   %s%n", "PICK", "ROUND",
                "ROSTER SO FAR", "best TE", "best RB", "best WR", "worth taking TE?");

        Set<String> gone = new HashSet<>();
        int taken = 0;
        for(int pick = 1; pick <= 130 && taken < MY_PICKS.length; pick++){
            boolean isMine = false;
            for(int mineAt : MY_PICKS){
                if(mineAt == pick){
                    isMine = true;
                }
            }
            if(!isMine){
                String other = best(board, gone, positionOf, null);
                if(other != null){
                    gone.add(other);
                }
                continue;
            }
            double base = value.of(mine);
            Map<Position, Double> marginal = new EnumMap<>(Position.class);
            Map<Position, String> who = new EnumMap<>(Position.class);
            for(Position position : new Position[]{Position.TE, Position.RB,
                    Position.WR}){
                String candidate = best(board, gone, positionOf, position);
                if(candidate == null){
                    continue;
                }
                List<String> trial = new ArrayList<>(mine);
                trial.add(candidate);
                marginal.put(position, value.of(trial) - base);
                who.put(position, candidate);
            }
            double te = marginal.getOrDefault(Position.TE, 0.0);
            double depth = Math.max(marginal.getOrDefault(Position.RB, 0.0),
                    marginal.getOrDefault(Position.WR, 0.0));
            System.out.printf("%-6d %-6d %-16s %12.1f %12.1f %12.1f   %s%n", pick,
                    1 + (pick - 1) / 12, shape(mine, positionOf), te,
                    marginal.getOrDefault(Position.RB, 0.0),
                    marginal.getOrDefault(Position.WR, 0.0),
                    te > depth ? "YES - by " + String.format("%.0f", te - depth)
                            : "no - depth wins by " + String.format("%.0f", depth - te));

            // take the depth pick and move on, so the roster keeps filling
            Position chosen = marginal.getOrDefault(Position.RB, 0.0)
                    >= marginal.getOrDefault(Position.WR, 0.0) ? Position.RB : Position.WR;
            String choice = who.get(chosen);
            if(choice != null){
                mine.add(choice);
                gone.add(choice);
            }
            taken++;
        }

        System.out.println("\nRead the crossover, not the levels. While depth wins, a"
                + " tight end is the worse\nuse of the pick; from the row where the"
                + " tight end wins onward, the empty slot\nhas become the most valuable"
                + " thing left to fill.");
    }

    static String best(List<String> board, Set<String> gone,
                       Map<String, Position> positionOf, Position position){
        for(String id : board){
            if(!gone.contains(id)
                    && (position == null || positionOf.get(id) == position)){
                return id;
            }
        }
        return null;
    }

    static String shape(List<String> roster, Map<String, Position> positionOf){
        Map<Position, Integer> counts = new EnumMap<>(Position.class);
        for(String id : roster){
            counts.merge(positionOf.get(id), 1, Integer::sum);
        }
        StringBuilder text = new StringBuilder();
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR,
                Position.TE}){
            if(counts.containsKey(position)){
                text.append(counts.get(position)).append(position).append(' ');
            }
        }
        return text.toString().trim();
    }

    static String names(List<String> ids){
        List<String> out = new ArrayList<>();
        for(String id : ids){
            Player player = Player.getPlayerFromSIDV2(id);
            out.add(player == null ? id : player.lastName);
        }
        return String.join(", ", out);
    }
}
