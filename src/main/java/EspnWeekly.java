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
import java.util.TreeMap;
import java.util.function.BiFunction;

/**
 * ESPN'S WEEK-BY-WEEK PROJECTIONS, SCORED UNDER THIS LEAGUE'S RULES.
 *
 * Justin, week 3: are there non-Sleeper projections that could augment the
 * rest-of-season model, or would they just be noise? The only honest answer is
 * the backtest that validated the blend, and that needs another shop's
 * projections for past weeks, made before the games. ESPN serves them: its
 * fantasy API returns a projection per man per scoring period for every season
 * from 2018 on (statSourceId 1, statSplitTypeId 1), the same span Sleeper
 * serves. {@link EspnProjections} already reads ESPN's SEASON line; this reads
 * the weekly ones through the same stat mapping and the same league scorer, so
 * an ESPN point and a Sleeper point are the same unit.
 *
 * One fetch per season, filtered to weekly projections only (about five MB). A
 * past season is kept forever - its weeks cannot change - and the season being
 * played is re-read daily, the policy every other feed here follows. Men are
 * matched to Sleeper ids by name and position against Sleeper's player
 * database, which keeps retired men; the match rate is reported by the caller
 * rather than assumed.
 *
 * The same vintage caution as Sleeper's week numbers applies: ESPN serves one
 * number per past week and does not say when it was made. RosModel prints the
 * correlation of these numbers with the outcomes they predicted, which is what
 * separates a forecast from a result.
 */
public class EspnWeekly {

    static final String FILTER =
            "{\"players\":{\"filterSlotIds\":{\"value\":[0,2,4,6]},\"limit\":600,"
            + "\"sortPercOwned\":{\"sortAsc\":false,\"sortPriority\":1},"
            + "\"filterStatsForSourceIds\":{\"value\":[1]},"
            + "\"filterStatsForSplitTypeIds\":{\"value\":[1]}}}";

    /** One season's weekly projections, raw. */
    static String raw(String season){
        boolean past = season.compareTo(LeagueWeek.season()) < 0;
        Path cache = Path.of(past ? "espnWeekly" + season + ".txt"
                : "espnLiveWeekly" + season + DateStuff.DateUtility.getTodaysDate() + ".txt");
        if(Files.exists(cache)){
            try {
                return Files.readString(cache);
            }
            catch(IOException unreadable){
                // fall through and refetch
            }
        }
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(EspnProjections.url(season)))
                            .header("User-Agent", "Mozilla/5.0")
                            .header("X-Fantasy-Filter", FILTER)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if(response.statusCode() != 200){
                throw new IOException("espn returned " + response.statusCode() + " for " + season);
            }
            String body = response.body();
            // never keep an answer with no players in it: that is a question asked
            // at the wrong moment, not a season with nobody projected
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if(!root.has("players") || root.getAsJsonArray("players").isEmpty()){
                throw new IOException("espn returned no players for " + season);
            }
            Files.writeString(cache, body);
            return body;
        }
        catch(IOException | InterruptedException problem){
            throw new RuntimeException("could not fetch espn weekly projections for " + season, problem);
        }
    }

    /** A season's weekly projections parsed, with how many ESPN men found a Sleeper id. */
    record Season(Map<Integer, Map<String, Double>> byWeek, int men, int matched) {}

    /**
     * week -> Sleeper id -> ESPN's projection for that week under {@code scoring}.
     * {@code idOf} maps an ESPN full name and position to a Sleeper id, or null.
     */
    static Season weeklyFrom(String body, String season, LeagueScoringSettings scoring,
                             BiFunction<String, Position, String> idOf){
        Map<Integer, Map<String, Double>> byWeek = new TreeMap<>();
        int men = 0;
        int matched = 0;
        JsonArray players = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("players");
        if(players == null){
            return new Season(byWeek, 0, 0);
        }
        for(JsonElement entry : players){
            JsonObject player = entry.getAsJsonObject().getAsJsonObject("player");
            if(player == null || player.get("defaultPositionId") == null || player.get("fullName") == null){
                continue;
            }
            Position position = EspnProjections.POSITIONS.get(player.get("defaultPositionId").getAsInt());
            if(position == null){
                continue;
            }
            men++;
            String id = idOf.apply(player.get("fullName").getAsString(), position);
            if(id == null){
                continue;
            }
            matched++;
            JsonArray stats = player.getAsJsonArray("stats");
            if(stats == null){
                continue;
            }
            for(JsonElement element : stats){
                JsonObject set = element.getAsJsonObject();
                if(set.get("statSourceId") == null || set.get("statSourceId").getAsInt() != 1
                        || set.get("statSplitTypeId") == null || set.get("statSplitTypeId").getAsInt() != 1
                        || set.get("scoringPeriodId") == null
                        || (set.get("seasonId") != null && !season.equals(set.get("seasonId").getAsString()))
                        || set.get("stats") == null || !set.get("stats").isJsonObject()){
                    continue;
                }
                int week = set.get("scoringPeriodId").getAsInt();
                double points = SleeperProjections.scoreStatLine(
                        EspnProjections.toSleeperKeys(set.getAsJsonObject("stats")), scoring);
                byWeek.computeIfAbsent(week, w -> new HashMap<>()).put(id, points);
            }
        }
        return new Season(byWeek, men, matched);
    }

    /** A season's weekly projections for this league, matched to Sleeper ids by name and position. */
    static Season season(String season){
        LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        return weeklyFrom(raw(season), season, scoring, (name, position) -> {
            Player matched = Player.getPlayerFromNameAndPos(name, position);
            return matched == null ? null : matched.sleeperIDString;
        });
    }
}
