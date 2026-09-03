import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * ESPN's season projections through their fantasy API - a second full
 * stat-line source alongside Sleeper/Rotowire, scored under league settings
 * by the same scorer as everything else. The endpoint needs an
 * X-Fantasy-Filter header, which the shared fetch helper cannot send, so
 * this class fetches itself but keeps the same day-cache convention.
 *
 * Stat ids verified against a known player before mapping: 3 pass yds,
 * 4 pass TD, 20 INT, 24 rush yds, 25 rush TD, 42 rec yds, 43 rec TD,
 * 53 receptions, 72 fumbles lost.
 */
public class EspnProjections {

    static String url(String season){
        return "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/" + season
                + "/segments/0/leaguedefaults/3?view=kona_player_info";
    }

    static final String FILTER =
            "{\"players\":{\"filterSlotIds\":{\"value\":[0,2,4,6]},\"limit\":600,"
            + "\"sortPercOwned\":{\"sortAsc\":false,\"sortPriority\":1}}}";

    private static String fetch(){
        String season = AAAConfiguration.getInstance().getSeason();
        Path cache = Path.of("espnProjections" + season
                + DateStuff.DateUtility.getTodaysDate() + ".txt");
        if(Files.exists(cache)){
            try {
                return Files.readString(cache);
            } catch (IOException ignored){
            }
        }
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url(season)))
                            .header("User-Agent", "Mozilla/5.0")
                            .header("X-Fantasy-Filter", FILTER)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if(response.statusCode() != 200){
                throw new IOException("espn returned " + response.statusCode());
            }
            Files.writeString(cache, response.body());
            return response.body();
        } catch (IOException | InterruptedException problem){
            throw new RuntimeException("could not fetch espn projections", problem);
        }
    }

    private static final Map<Integer, Position> POSITIONS = Map.of(
            1, Position.QB, 2, Position.RB, 3, Position.WR, 4, Position.TE);

    /** ESPN stat id -> the repo's Sleeper stat key. */
    private static final Map<String, String> STAT_KEYS = Map.of(
            "3", "pass_yd", "4", "pass_td", "20", "pass_int",
            "24", "rush_yd", "25", "rush_td",
            "42", "rec_yd", "43", "rec_td", "53", "rec",
            "72", "fum_lost");

    /** Sleeper id -> ESPN's season stat line scored under league settings. */
    public static HashMap<String, Double> leaguePointsBySleeperID(){
        String season = AAAConfiguration.getInstance().getSeason();
        LeagueScoringSettings scoring =
                SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        HashMap<String, Double> out = new HashMap<>();
        JsonObject root = JsonParser.parseString(fetch()).getAsJsonObject();
        JsonArray players = root.getAsJsonArray("players");
        if(players == null){
            return out;
        }
        for(JsonElement entry : players){
            JsonObject player = entry.getAsJsonObject().getAsJsonObject("player");
            if(player == null){
                continue;
            }
            Position position = POSITIONS.get(
                    player.get("defaultPositionId") == null ? -1
                            : player.get("defaultPositionId").getAsInt());
            if(position == null){
                continue;
            }
            JsonObject seasonProjection = seasonProjection(player, season);
            if(seasonProjection == null){
                continue;
            }
            Player matched = Player.getPlayerFromNameAndPos(
                    player.get("fullName").getAsString(), position);
            if(matched == null){
                continue;
            }
            out.put(matched.sleeperIDString,
                    SleeperProjections.scoreStatLine(toSleeperKeys(seasonProjection), scoring));
        }
        return out;
    }

    /** The full-season projection entry (source 1, split 0) for this season. */
    static JsonObject seasonProjection(JsonObject player, String season){
        JsonArray stats = player.getAsJsonArray("stats");
        if(stats == null){
            return null;
        }
        for(JsonElement element : stats){
            JsonObject set = element.getAsJsonObject();
            if(set.get("statSourceId") != null && set.get("statSourceId").getAsInt() == 1
                    && set.get("statSplitTypeId") != null
                    && set.get("statSplitTypeId").getAsInt() == 0
                    && set.get("seasonId") != null
                    && season.equals(set.get("seasonId").getAsString())){
                JsonElement values = set.get("stats");
                return values != null && values.isJsonObject() ? values.getAsJsonObject() : null;
            }
        }
        return null;
    }

    /** ESPN's numeric stat ids to the Sleeper keys the scorer reads. */
    static JsonObject toSleeperKeys(JsonObject espnStats){
        JsonObject stats = new JsonObject();
        for(Map.Entry<String, String> mapping : STAT_KEYS.entrySet()){
            JsonElement value = espnStats.get(mapping.getKey());
            if(value != null && !value.isJsonNull()){
                stats.addProperty(mapping.getValue(), value.getAsDouble());
            }
        }
        return stats;
    }

    public static void main(String[] args){
        Map<String, Double> points = leaguePointsBySleeperID();
        System.out.printf("espn: %d players scored under league settings%n", points.size());
        points.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(10)
                .forEach(entry -> {
                    Player player = Player.getPlayerFromSIDV2(entry.getKey());
                    System.out.printf("   %-24s %-3s %6.1f%n",
                            player.firstName + " " + player.lastName, player.position,
                            entry.getValue());
                });
    }

}
