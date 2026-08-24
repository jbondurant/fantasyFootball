import PlayerImportAndSetup.EcrDataExtractor;
import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the `var ecrData = {...}` blob every FantasyPros rankings page embeds.
 *
 * Two things changed under us for 2025/2026 and both are handled here:
 *
 *  - "sportsdata_id" is gone. It was the only id shared with Sleeper, and every
 *    caller used to join on it. Players are now matched on name + team +
 *    position via {@link Player#getPlayerFromFantasyPros}.
 *  - "r2p_pts" (rest-of-season projected points) is gone too. These pages carry
 *    expert consensus *ranks* and nothing else, so they can no longer be used as
 *    a source of projected points. Points come from Sleeper now
 *    ({@link SleeperStatProjections}).
 */
public class FantasyProsEcrData {

    public static class Entry {
        public final String fantasyProsID;
        public final String playerName;
        public final String team;
        public final Position position;
        public final int rankEcr;
        public final Double rankAverage;

        Entry(String fantasyProsID, String playerName, String team, Position position, int rankEcr, Double rankAverage){
            this.fantasyProsID = fantasyProsID;
            this.playerName = playerName;
            this.team = team;
            this.position = position;
            this.rankEcr = rankEcr;
            this.rankAverage = rankAverage;
        }

        public Player resolvePlayer(){
            return Player.getPlayerFromFantasyPros(playerName, team, position);
        }
    }

    public static JsonObject extractEcrData(String entireHTML){
        return EcrDataExtractor.extract(entireHTML);
    }

    public static List<Entry> parse(String entireHTML){
        JsonObject ecrData = extractEcrData(entireHTML);
        List<Entry> entries = new ArrayList<>();
        for(JsonElement jsonPlayer : ecrData.getAsJsonArray("players")){
            JsonObject apiObject = jsonPlayer.getAsJsonObject();

            String fantasyProsID = optionalString(apiObject, "player_id");
            String playerName = optionalString(apiObject, "player_name");
            String team = optionalString(apiObject, "player_team_id");
            Position position = toPosition(optionalString(apiObject, "player_position_id"));

            JsonElement rankEcrElement = apiObject.get("rank_ecr");
            if(playerName == null || rankEcrElement == null || rankEcrElement.isJsonNull()){
                continue;
            }
            int rankEcr = rankEcrElement.getAsInt();

            Double rankAverage = null;
            JsonElement rankAverageElement = apiObject.get("rank_ave");
            if(rankAverageElement != null && !rankAverageElement.isJsonNull()){
                try {
                    rankAverage = rankAverageElement.getAsDouble();
                } catch (NumberFormatException ignored) {
                    // FantasyPros writes "" for players nobody ranked.
                }
            }

            entries.add(new Entry(fantasyProsID, playerName, team, position, rankEcr, rankAverage));
        }
        if(entries.isEmpty()){
            throw new RuntimeException("ecrData contained no ranked players");
        }
        return entries;
    }

    /** FantasyPros says DST where Sleeper says DEF. */
    public static Position toPosition(String fantasyProsPosition){
        if(fantasyProsPosition == null){
            return Position.OTHER;
        }
        String normalized = fantasyProsPosition.trim().toUpperCase();
        if(normalized.equals("DST")){
            return Position.DEF;
        }
        if(Position.isStandardPosition(normalized)){
            return Position.valueOf(normalized);
        }
        return Position.OTHER;
    }

    private static String optionalString(JsonObject object, String key){
        JsonElement element = object.get(key);
        if(element == null || element.isJsonNull()){
            return null;
        }
        return element.getAsString();
    }

}
