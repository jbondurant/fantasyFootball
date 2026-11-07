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
 *  - "r2p_pts" (rest-of-season projected points) went missing for a while; the
 *    rest-of-season pages carry it again on 2026-10-29 (Nabers 145.2, Jefferson
 *    135.3), falling with rank. It is FantasyPros' own rank-to-points scale on
 *    its standard scoring, not this league's six-point passing touchdowns, so
 *    nothing reads it: points come from Sleeper ({@link SleeperProjections}), and
 *    WeeklyLineupValue reads FantasyPros' ranks onto Sleeper's league-scored
 *    scale.
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

    /**
     * WHOSE CONSENSUS A PAGE IS. Justin, 2026-10-29: "the experts they pull the
     * rankings from change depending on which day you pull, and some days include
     * experts with stale projections". The page says which: ecrData names the
     * experts in its consensus (experts_available.included, out of .total) and
     * expertGroupsData gives each expert's last update. On 2026-10-29 the
     * rest-of-season pages averaged 6 of 37 experts (three of them FantasyPros'
     * own), the stalest updated 3 days before the page. The public page ignores a
     * ?filters= list of experts (tested that day), so the panel cannot be widened
     * from here; it can be watched. Null when the page does not carry it.
     */
    public record Panel(int experts, int available, List<String> names, double stalestDays){}

    public static Panel panel(String entireHTML){
        try {
            JsonObject ecr = extractEcrData(entireHTML);
            JsonObject available = ecr.getAsJsonObject("experts_available");
            long pageUpdated = ecr.get("last_updated_ts").getAsLong();
            java.util.Set<Integer> included = new java.util.HashSet<>();
            available.getAsJsonArray("included").forEach(id -> included.add(id.getAsInt()));
            JsonObject groups = EcrDataExtractor.extract(entireHTML, "var expertGroupsData");
            List<String> names = new ArrayList<>();
            double stalest = 0;
            for(JsonElement e : groups.getAsJsonArray("expert_data")){
                JsonObject expert = e.getAsJsonObject();
                if(included.contains(expert.get("id").getAsInt())){
                    names.add(optionalString(expert, "name") + " (" + optionalString(expert, "site") + ")");
                    if(expert.has("last_updated") && !expert.get("last_updated").isJsonNull()){
                        stalest = Math.max(stalest, (pageUpdated - expert.get("last_updated").getAsLong()) / 86_400.0);
                    }
                }
            }
            return new Panel(ecr.get("total_experts").getAsInt(), available.get("total").getAsInt(), names, stalest);
        }
        catch(RuntimeException missing){
            return null;
        }
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
