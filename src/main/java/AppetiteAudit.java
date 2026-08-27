import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Justin's question, answered from data instead of assumption: did the
 * league's QB appetite DRIFT across seasons, or was it always low? Prints,
 * per season: each manager's first in-draft QB round (rounds 1-9, 10 = none
 * inside the game), the league mean, and the number of KEPT QBs that season -
 * because kept QBs mechanically suppress in-draft QB demand, and drift in
 * appetite must be separated from drift in keeper composition.
 *
 *   ./gradlew run -Pmain=AppetiteAudit [-Ppos=TE]
 */
public class AppetiteAudit {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Position position = Position.valueOf(System.getProperty("pos", "QB"));

        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();
        Map<String, Map<String, Integer>> bySeason = new TreeMap<>();
        Map<String, Integer> keptBySeason = new TreeMap<>();
        for(int i = 0; i < drafts.size() && i < seasons.size(); i++){
            String season = seasons.get(i);
            if(season == null){
                continue;
            }
            bySeason.put(season, DraftSimulator.realFirstRound(drafts.get(i), position));
            int kept = 0;
            for(JsonElement pickElement : drafts.get(i)){
                JsonObject pick = pickElement.getAsJsonObject();
                JsonElement isKeeper = pick.get("is_keeper");
                if(isKeeper == null || isKeeper.isJsonNull() || !isKeeper.getAsBoolean()){
                    continue;
                }
                Player player = Player.getPlayerFromSIDV2(
                        pick.get("player_id").getAsString());
                if(player != null && player.position == position){
                    kept++;
                }
            }
            keptBySeason.put(season, kept);
        }

        List<String> managers = new ArrayList<>();
        for(Map<String, Integer> rounds : bySeason.values()){
            for(String manager : rounds.keySet()){
                if(!managers.contains(manager)){
                    managers.add(manager);
                }
            }
        }

        System.out.printf("first in-draft %s round per manager per season "
                + "(%d = none inside rounds 1-9):%n%n", position,
                DraftSimulator.NEVER_ROUND);
        System.out.printf("%-22s", "MANAGER");
        for(String season : bySeason.keySet()){
            System.out.printf(" %6s", season);
        }
        System.out.println();
        Map<String, List<Integer>> perManager = new HashMap<>();
        for(String manager : managers){
            System.out.printf("%-22s", HumanOfInterest.getHumanFromID(manager));
            for(Map.Entry<String, Map<String, Integer>> entry : bySeason.entrySet()){
                Integer round = entry.getValue().get(manager);
                int value = round == null ? DraftSimulator.NEVER_ROUND
                        : Math.min(round, DraftSimulator.NEVER_ROUND);
                perManager.computeIfAbsent(manager, u -> new ArrayList<>()).add(value);
                System.out.printf(" %6s", round == null ? "-" : String.valueOf(value));
            }
            System.out.println();
        }
        System.out.printf("%n%-22s", "league mean");
        for(Map.Entry<String, Map<String, Integer>> entry : bySeason.entrySet()){
            double total = 0;
            int count = 0;
            for(int round : entry.getValue().values()){
                total += Math.min(round, DraftSimulator.NEVER_ROUND);
                count++;
            }
            System.out.printf(" %6.1f", count == 0 ? 0 : total / count);
        }
        System.out.printf("%n%-22s", "kept " + position + "s");
        for(String season : bySeason.keySet()){
            System.out.printf(" %6d", keptBySeason.getOrDefault(season, 0));
        }
        System.out.printf("%n%-22s", "drafters (of 12)");
        for(Map.Entry<String, Map<String, Integer>> entry : bySeason.entrySet()){
            System.out.printf(" %6d", entry.getValue().size());
        }
        System.out.println("\n\nA falling league-mean row = appetite drift toward early; a"
                + "\nflat row with rising kept counts = composition, not appetite.");
    }
}
