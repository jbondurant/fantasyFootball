import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Archives and analyzes a mock draft from a pasted link, before Sleeper
 * deletes it.
 *
 * Mocks are not enumerable - no public endpoint lists a user's mocks, even
 * for the creator while the mock is live (settled by experiment 2026-08-25).
 * But a mock whose LINK is shared is fully readable at /v1/draft/{id}, its
 * `creators` field says whose it is, and it 404s once Sleeper prunes it. So
 * the pipeline is: whenever a leaguemate shares a mock link, feed it here;
 * the picks are archived under data/mocks/ permanently and the reaches versus
 * current ADP are printed.
 *
 *     ./gradlew run -Pmain=MockDraftReader -PdraftId=<id or pasted URL>
 */
public class MockDraftReader {

    /** Accepts a bare id or a pasted sleeper.com URL with query noise. */
    static String extractDraftId(String input){
        String cleaned = input.trim();
        int query = cleaned.indexOf('?');
        if(query >= 0){
            cleaned = cleaned.substring(0, query);
        }
        String[] segments = cleaned.split("/");
        for(int i = segments.length - 1; i >= 0; i--){
            if(segments[i].matches("\\d{15,}")){
                return segments[i];
            }
        }
        throw new IllegalArgumentException("no draft id in: " + input);
    }

    public static void main(String[] args) throws IOException {
        String raw = System.getProperty("draftId", "");
        if(raw.isBlank()){
            System.out.println("usage: ./gradlew run -Pmain=MockDraftReader -PdraftId=<id or URL>");
            return;
        }
        String draftId = extractDraftId(raw);

        String draftJson = WebUrlUtility.urlToString(AAAConfiguration.draftWebURL(draftId));
        String picksJson = WebUrlUtility.urlToString(AAAConfiguration.draftPicksWebURL(draftId));
        Files.createDirectories(Path.of("data", "mocks"));
        Files.writeString(Path.of("data", "mocks", draftId + "-draft.json"), draftJson, StandardCharsets.UTF_8);
        Files.writeString(Path.of("data", "mocks", draftId + "-picks.json"), picksJson, StandardCharsets.UTF_8);

        JsonObject draft = JsonParser.parseString(draftJson).getAsJsonObject();
        JsonArray picks = JsonParser.parseString(picksJson).getAsJsonArray();

        StringBuilder creators = new StringBuilder();
        JsonElement creatorsElement = draft.get("creators");
        if(creatorsElement != null && !creatorsElement.isJsonNull()){
            for(JsonElement creator : creatorsElement.getAsJsonArray()){
                if(creators.length() > 0){
                    creators.append(", ");
                }
                creators.append(HumanOfInterest.getHumanFromID(creator.getAsString()));
            }
        }
        System.out.println("mock " + draftId + "  season " + draft.get("season").getAsString()
                + "  status " + draft.get("status").getAsString()
                + "  creator: " + (creators.length() == 0 ? "unknown" : creators));
        System.out.println("archived to data/mocks/ (" + picks.size() + " picks)\n");

        record Reach(int pickNo, String name, String position, double adp, double delta) {}
        List<Reach> reaches = new ArrayList<>();
        for(JsonElement pickElement : picks){
            JsonObject pick = pickElement.getAsJsonObject();
            String sleeperID = pick.get("player_id").getAsString();
            Player player = Player.getPlayerFromSIDV2(sleeperID);
            double adp = SleeperProjections.adpOf(sleeperID);
            if(player == null || adp > 900){
                continue;
            }
            int pickNo = pick.get("pick_no").getAsInt();
            reaches.add(new Reach(pickNo, player.firstName + " " + player.lastName,
                    player.position.name(), adp, pickNo - adp));
        }
        reaches.sort(Comparator.comparingDouble(Reach::delta));
        System.out.println("biggest reaches in this mock (taken furthest ahead of ADP):");
        for(Reach reach : reaches.subList(0, Math.min(10, reaches.size()))){
            System.out.printf("   pick %-4d %-24s %-3s adp %5.1f  (%+.0f)%n",
                    reach.pickNo(), reach.name(), reach.position(), reach.adp(), reach.delta());
        }
    }

}
