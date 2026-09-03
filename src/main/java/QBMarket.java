import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The quarterback market, empirically: who entered each draft already holding
 * a kept QB, when each manager took their first in-draft QB, and whether the
 * league actually herds - a position going "trendy" after somebody opens the
 * run. The herding numbers here are raw co-occurrence; the controlled test is
 * the run feature gated in SelectionModel/DraftSimulator.
 *
 *     ./gradlew run -Pmain=QBMarket
 */
public class QBMarket {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();

        // seasons come newest first; walk oldest first for display
        List<Integer> order = new ArrayList<>();
        for(int i = drafts.size() - 1; i >= 0; i--){
            if(i < seasons.size() && seasons.get(i) != null){
                order.add(i);
            }
        }

        Set<String> managers = new LinkedHashSet<>();
        Map<String, Map<String, String>> cells = new HashMap<>();
        for(int i : order){
            String season = seasons.get(i);
            Set<String> keptQB = new LinkedHashSet<>();
            for(JsonElement pickElement : drafts.get(i)){
                JsonObject pick = pickElement.getAsJsonObject();
                JsonElement isKeeper = pick.get("is_keeper");
                JsonElement pickedBy = pick.get("picked_by");
                if(isKeeper == null || isKeeper.isJsonNull() || !isKeeper.getAsBoolean()
                        || pickedBy == null || pickedBy.isJsonNull()){
                    continue;
                }
                Player player = Player.getPlayerFromSIDV2(pick.get("player_id").getAsString());
                if(player != null && player.position.equals(Position.QB)){
                    keptQB.add(pickedBy.getAsString());
                }
            }
            Map<String, Integer> firstRound =
                    DraftSimulator.realFirstRound(drafts.get(i), Position.QB);
            Set<String> everyone = new LinkedHashSet<>(keptQB);
            everyone.addAll(firstRound.keySet());
            for(String manager : everyone){
                managers.add(manager);
                String cell = (keptQB.contains(manager) ? "K" : "")
                        + (firstRound.containsKey(manager)
                                ? String.valueOf(firstRound.get(manager))
                                : (keptQB.contains(manager) ? "" : "-"));
                cells.computeIfAbsent(manager, m -> new HashMap<>()).put(season, cell);
            }
        }

        System.out.println("first in-draft QB round by season (K = entered holding a kept QB,");
        System.out.println("- = no QB drafted inside the nine-round game):\n");
        System.out.printf("   %-22s", "MANAGER");
        for(int i : order){
            System.out.printf(" %6s", seasons.get(i));
        }
        System.out.printf(" %6s%n", "2026");

        Map<String, List<Keeper>> declared = new HashMap<>();
        for(Keeper keeper : configuration.getTodaysKeepers()){
            declared.computeIfAbsent(keeper.humanWhoCanKeep, m -> new ArrayList<>()).add(keeper);
        }
        int keptQBs2026 = 0;
        for(String manager : managers){
            System.out.printf("   %-22s", HumanOfInterest.getHumanFromID(manager));
            for(int i : order){
                System.out.printf(" %6s",
                        cells.getOrDefault(manager, Map.of()).getOrDefault(seasons.get(i), "-"));
            }
            boolean hasKeptQB = declared.getOrDefault(manager, List.of()).stream()
                    .anyMatch(keeper -> keeper.player.position.equals(Position.QB));
            if(hasKeptQB){
                keptQBs2026++;
            }
            System.out.printf(" %6s%n", hasKeptQB ? "K" : "?");
        }
        System.out.printf("%n   declared kept QBs for 2026 so far: %d "
                + "(? = keepers not declared yet)%n", keptQBs2026);

        // ---- raw herding evidence, pooled over seasons and positions ----
        System.out.println("\nherding, raw: P(position taken again within the next 3 picks),");
        System.out.println("after a pick at that position versus after a pick elsewhere:\n");
        System.out.printf("   %-4s %14s %14s %8s%n", "POS", "after same-pos", "otherwise", "N runs");
        for(Position position : List.of(Position.QB, Position.RB, Position.WR, Position.TE)){
            double[] afterSame = {0, 0};
            double[] otherwise = {0, 0};
            for(int i : order){
                List<Position> sequence = inDraftSequence(drafts.get(i));
                for(int p = 0; p + 1 < sequence.size(); p++){
                    boolean repeat = false;
                    for(int n = p + 1; n <= p + 3 && n < sequence.size(); n++){
                        if(sequence.get(n).equals(position)){
                            repeat = true;
                        }
                    }
                    if(sequence.get(p).equals(position)){
                        afterSame[0] += repeat ? 1 : 0;
                        afterSame[1]++;
                    }
                    else {
                        otherwise[0] += repeat ? 1 : 0;
                        otherwise[1]++;
                    }
                }
            }
            System.out.printf("   %-4s %13.1f%% %13.1f%% %8.0f%n", position,
                    100 * afterSame[0] / Math.max(afterSame[1], 1),
                    100 * otherwise[0] / Math.max(otherwise[1], 1), afterSame[1]);
        }
        System.out.println("\n   raw co-occurrence only - ADP clusters positions on its own. The");
        System.out.println("   controlled test is the run feature in the selection model's gates.");
    }

    /** Positions of the in-draft picks, rounds 1-9, in pick order. */
    static List<Position> inDraftSequence(JsonArray draft){
        List<JsonObject> picks = new ArrayList<>();
        for(JsonElement pickElement : draft){
            JsonObject pick = pickElement.getAsJsonObject();
            JsonElement isKeeper = pick.get("is_keeper");
            if(isKeeper != null && !isKeeper.isJsonNull() && isKeeper.getAsBoolean()){
                continue;
            }
            if(pick.get("round").getAsInt() > SelectionModel.GAME_ROUNDS){
                continue;
            }
            picks.add(pick);
        }
        picks.sort(java.util.Comparator.comparingInt(pick -> pick.get("pick_no").getAsInt()));
        List<Position> sequence = new ArrayList<>();
        for(JsonObject pick : picks){
            Player player = Player.getPlayerFromSIDV2(pick.get("player_id").getAsString());
            if(player != null && StartingLineup.isSkillPosition(player.position)){
                sequence.add(player.position);
            }
        }
        return Collections.unmodifiableList(sequence);
    }

}
