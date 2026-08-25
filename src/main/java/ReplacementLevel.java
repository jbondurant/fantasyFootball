import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What a position is worth for free.
 *
 * Replacement level is the last player at a position who starts somewhere in
 * the league; anyone below is available to everyone, so points above that line
 * are the only ones that separate you from your leaguemates.
 *
 * Getting this per-position matters more than it looks. Quarterbacks outscore
 * receivers by 150 points a season in absolute terms, but QB12 already projects
 * 296 in a twelve-team one-quarterback league, so a 303 point quarterback is
 * barely above free. Comparing raw projections across positions - or against a
 * blended, position-agnostic curve - measures scoring rules rather than value.
 */
public class ReplacementLevel {

    private final Map<Position, Double> byPosition = new EnumMap<>(Position.class);
    private final Map<Position, Integer> rankUsed = new EnumMap<>(Position.class);

    public static ReplacementLevel forLeague(AAAConfiguration configuration,
                                             Map<String, Double> projectedPoints){
        int teams = configuration.getLeagueJson().getAsJsonObject("settings").get("num_teams").getAsInt();
        int flex = StartingLineup.flexSlotsPerTeam(configuration);

        ReplacementLevel level = new ReplacementLevel();
        for(Position position : List.of(Position.QB, Position.RB, Position.WR, Position.TE)){
            List<Double> ordered = new ArrayList<>();
            for(Map.Entry<String, Double> entry : projectedPoints.entrySet()){
                Player player = Player.getPlayerFromSIDV2(entry.getKey());
                if(player != null && player.position.equals(position)){
                    ordered.add(entry.getValue());
                }
            }
            ordered.sort((a, b) -> Double.compare(b, a));
            int started = StartingLineup.startedLeagueWide(position, teams, flex);
            int index = Math.min(started, ordered.size()) - 1;
            level.byPosition.put(position, index >= 0 ? ordered.get(index) : 0.0);
            level.rankUsed.put(position, started);
        }
        return level;
    }

    public double of(Position position){
        return byPosition.getOrDefault(position, 0.0);
    }

    public int rankOf(Position position){
        return rankUsed.getOrDefault(position, 0);
    }

    /** Points this player is worth above what the position gives away free. */
    public double valueOver(Player player, double projectedPoints){
        return projectedPoints - of(player.position);
    }

    public Map<Position, Double> all(){
        return byPosition;
    }

}
