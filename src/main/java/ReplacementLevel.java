import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What a position is worth for free.
 *
 * Replacement level is the last player at a position who starts anywhere in
 * the league. The fixed slots are counted directly; the flex slots are filled
 * the way flex actually works - the best remaining RB/WR/TE by points,
 * league-wide - instead of an assumed 50/42/8 split. So the flex share is an
 * output of the projection pool each year, not a hand-set constant.
 *
 * Per-position comparison is the part that matters. Quarterbacks outscore
 * receivers by 150 points a season in absolute terms, but QB12 already
 * projects near the top of the position in a twelve-team one-QB league, so a
 * big raw projection at quarterback is worth far less than it looks.
 */
public class ReplacementLevel {

    private final Map<Position, Double> byPosition = new EnumMap<>(Position.class);
    private final Map<Position, Integer> startersAt = new EnumMap<>(Position.class);

    /**
     * Pure greedy fill, testable offline: fixed slots per position, then the
     * flex slots go to the best remaining RB/WR/TE regardless of position.
     */
    public static ReplacementLevel greedy(Map<Position, List<Double>> pointsByPosition,
                                          int teams, int flexSlotsPerTeam){
        ReplacementLevel level = new ReplacementLevel();
        Map<Position, Integer> fixed = new EnumMap<>(Position.class);
        fixed.put(Position.QB, 1 * teams);
        fixed.put(Position.RB, 2 * teams);
        fixed.put(Position.WR, 3 * teams);
        fixed.put(Position.TE, 1 * teams);

        Map<Position, List<Double>> sorted = new EnumMap<>(Position.class);
        Map<Position, Integer> taken = new EnumMap<>(Position.class);
        for(Map.Entry<Position, Integer> entry : fixed.entrySet()){
            List<Double> pool = new ArrayList<>(pointsByPosition.getOrDefault(entry.getKey(), List.of()));
            pool.sort((a, b) -> Double.compare(b, a));
            sorted.put(entry.getKey(), pool);
            taken.put(entry.getKey(), Math.min(entry.getValue(), pool.size()));
        }

        // Flex: repeatedly take the best next player among RB/WR/TE.
        int flexToFill = flexSlotsPerTeam * teams;
        for(int slot = 0; slot < flexToFill; slot++){
            Position best = null;
            double bestPoints = -1;
            for(Position position : List.of(Position.RB, Position.WR, Position.TE)){
                int index = taken.get(position);
                List<Double> pool = sorted.get(position);
                if(index < pool.size() && pool.get(index) > bestPoints){
                    bestPoints = pool.get(index);
                    best = position;
                }
            }
            if(best == null){
                break;
            }
            taken.put(best, taken.get(best) + 1);
        }

        for(Position position : fixed.keySet()){
            int count = taken.get(position);
            List<Double> pool = sorted.get(position);
            level.startersAt.put(position, count);
            level.byPosition.put(position, count > 0 && count <= pool.size()
                    ? pool.get(count - 1) : 0.0);
        }
        return level;
    }

    public static ReplacementLevel forLeague(AAAConfiguration configuration,
                                             Map<String, Double> projectedPoints){
        int teams = configuration.getLeagueJson().getAsJsonObject("settings").get("num_teams").getAsInt();
        int flex = StartingLineup.flexSlotsPerTeam(configuration);

        Map<Position, List<Double>> pointsByPosition = new EnumMap<>(Position.class);
        for(Map.Entry<String, Double> entry : projectedPoints.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null || !StartingLineup.isSkillPosition(player.position) || entry.getValue() <= 0){
                continue;
            }
            pointsByPosition.computeIfAbsent(player.position, p -> new ArrayList<>()).add(entry.getValue());
        }
        return greedy(pointsByPosition, teams, flex);
    }

    public double of(Position position){
        return byPosition.getOrDefault(position, 0.0);
    }

    /** Starters at this position league-wide, fixed plus the flex it won. */
    public int rankOf(Position position){
        return startersAt.getOrDefault(position, 0);
    }

    /** Points this player is worth above what the position gives away free. */
    public double valueOver(Player player, double projectedPoints){
        return projectedPoints - of(player.position);
    }

    public Map<Position, Double> all(){
        return byPosition;
    }

}
