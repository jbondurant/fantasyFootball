import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * D4a: the exact date and time of every season's draft, read from the
 * league chain's /drafts collections - the anchor that says how far each
 * dated ADP capture sits from the moment the league was actually on the
 * clock.
 *
 *   ./gradlew run -Pmain=DraftDates
 */
public class DraftDates {

    /**
     * season -> the day that season's draft started, walked from the live
     * league chain. Shared so nothing has to keep a second copy of these
     * dates in a file that could go stale.
     */
    public static java.util.Map<String, java.time.LocalDate> byLeagueSeason(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        java.util.Map<String, java.time.LocalDate> dates = new java.util.TreeMap<>();
        String leagueID = configuration.getLeagueJson().get("league_id").getAsString();
        while(leagueID != null){
            String leagueData = InOutUtilities.getCachedForever(
                    "https://api.sleeper.app/v1/league/" + leagueID,
                    "leagueChain" + leagueID);
            JsonObject league = JsonParser.parseString(leagueData).getAsJsonObject();
            String season = league.get("season").getAsString();
            String draftsData = InOutUtilities.getCachedForever(
                    "https://api.sleeper.app/v1/league/" + leagueID + "/drafts",
                    "draftsChain" + leagueID);
            for(JsonElement draftElement : JsonParser.parseString(draftsData).getAsJsonArray()){
                JsonElement start = draftElement.getAsJsonObject().get("start_time");
                if(start != null && !start.isJsonNull()){
                    dates.put(season, Instant.ofEpochMilli(start.getAsLong())
                            .atZone(ZoneId.systemDefault()).toLocalDate());
                }
            }
            JsonElement previous = league.get("previous_league_id");
            leagueID = previous == null || previous.isJsonNull()
                    || previous.getAsString().equals("0") ? null : previous.getAsString();
        }
        return dates;
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        DateTimeFormatter format = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault());

        String leagueID = configuration.getLeagueJson().get("league_id").getAsString();
        System.out.printf("%-8s %-22s %s%n", "SEASON", "DRAFT START", "status");
        while(leagueID != null){
            String leagueData = InOutUtilities.getCachedForever(
                    "https://api.sleeper.app/v1/league/" + leagueID,
                    "leagueChain" + leagueID);
            JsonObject league = JsonParser.parseString(leagueData).getAsJsonObject();
            String season = league.get("season").getAsString();
            String draftsData = InOutUtilities.getCachedForever(
                    "https://api.sleeper.app/v1/league/" + leagueID + "/drafts",
                    "draftsChain" + leagueID);
            JsonArray drafts = JsonParser.parseString(draftsData).getAsJsonArray();
            for(JsonElement draftElement : drafts){
                JsonObject draft = draftElement.getAsJsonObject();
                JsonElement start = draft.get("start_time");
                String when = start == null || start.isJsonNull() ? "unscheduled"
                        : format.format(Instant.ofEpochMilli(start.getAsLong()));
                System.out.printf("%-8s %-22s %s%n", season, when,
                        draft.get("status").getAsString());
            }
            JsonElement previous = league.get("previous_league_id");
            leagueID = previous == null || previous.isJsonNull()
                    || previous.getAsString().equals("0") ? null : previous.getAsString();
        }
    }
}
