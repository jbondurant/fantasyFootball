import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays the pricing rules against every keeper the league has ever declared.
 *
 * Sleeper records what each keeper actually cost - the round on their is_keeper
 * pick - so six seasons of drafts are six seasons of worked answers. If the
 * rules here are right, they reproduce those rounds.
 */
@Tag("smoke")
class KeeperHistorySmokeTest {

    /**
     * Three costs in the league's history do not follow the rules, and all
     * three look like bookkeeping rather than a rule I have missed:
     *
     *   Joe Burrow 2023   kept at a 13th when escalation from his 2022 13th
     *                     called for a 12th - the escalation was skipped
     *   Joe Burrow 2024   kept at an 11th, two rounds up from that 13th,
     *                     which puts him back where he would have been
     *   Josh Jacobs 2023  drafted in the 5th, kept at a 4th, with no clash on
     *                     his roster to explain the extra round
     */
    private static final int KNOWN_HISTORICAL_EXCEPTIONS = 3;

    @Test
    void thePricingRulesReproduceSixSeasonsOfKeeperCosts(){
        List<JsonArray> newestFirst = AAAConfiguration.getInstance().getPreviousDraftPicks();
        Assertions.assertTrue(newestFirst.size() >= 4, "not enough history to check");

        int matched = 0;
        List<String> mismatches = new ArrayList<>();

        // Walk each completed season and price its keepers from what came before.
        for(int season = 0; season < newestFirst.size() - 1; season++){
            JsonArray draft = newestFirst.get(season);
            List<JsonArray> earlier = newestFirst.subList(season + 1, newestFirst.size());

            JsonArray rosters = rostersDeclaring(draft);
            Map<String, Integer> actual = keeperCosts(draft);
            if(actual.isEmpty()){
                continue;
            }

            KeeperPricing.PricedKeepers priced = KeeperPricing.price(
                    rosters, earlier, Player::getPlayerFromSIDV2, SleeperProjections::adpOf);

            Map<String, Integer> predicted = new HashMap<>();
            for(Keeper keeper : priced.keepers){
                predicted.put(keeper.player.sleeperIDString, keeper.roundCanBeKept);
            }

            for(Map.Entry<String, Integer> entry : actual.entrySet()){
                Integer got = predicted.get(entry.getKey());
                if(entry.getValue().equals(got)){
                    matched++;
                }
                else {
                    mismatches.add(nameOf(draft, entry.getKey())
                            + ": league charged round " + entry.getValue()
                            + ", rules give " + got);
                }
            }
        }

        int total = matched + mismatches.size();
        System.out.println("keeper costs reproduced: " + matched + "/" + total);
        for(String mismatch : mismatches){
            System.out.println("   " + mismatch);
        }

        Assertions.assertTrue(total > 60, "expected several seasons of keepers, got " + total);
        Assertions.assertTrue(mismatches.size() <= KNOWN_HISTORICAL_EXCEPTIONS,
                "the rules stopped reproducing history: " + mismatches);
    }

    /** Rebuilds "who declared whom" from a completed draft's keeper picks. */
    private static JsonArray rostersDeclaring(JsonArray draft){
        Map<String, JsonArray> byOwner = new HashMap<>();
        for(JsonElement pickElement : draft){
            JsonObject pick = pickElement.getAsJsonObject();
            if(!isKeeper(pick) || pick.get("picked_by") == null || pick.get("picked_by").isJsonNull()){
                continue;
            }
            byOwner.computeIfAbsent(pick.get("picked_by").getAsString(), owner -> new JsonArray())
                    .add(pick.get("player_id").getAsString());
        }
        JsonArray rosters = new JsonArray();
        for(Map.Entry<String, JsonArray> entry : byOwner.entrySet()){
            JsonObject roster = new JsonObject();
            roster.addProperty("owner_id", entry.getKey());
            roster.add("keepers", entry.getValue());
            roster.add("players", entry.getValue());
            rosters.add(roster);
        }
        return rosters;
    }

    private static Map<String, Integer> keeperCosts(JsonArray draft){
        Map<String, Integer> costs = new HashMap<>();
        for(JsonElement pickElement : draft){
            JsonObject pick = pickElement.getAsJsonObject();
            if(isKeeper(pick)){
                costs.put(pick.get("player_id").getAsString(), pick.get("round").getAsInt());
            }
        }
        return costs;
    }

    private static String nameOf(JsonArray draft, String playerID){
        for(JsonElement pickElement : draft){
            JsonObject pick = pickElement.getAsJsonObject();
            if(pick.get("player_id").getAsString().equals(playerID)){
                JsonObject meta = pick.getAsJsonObject("metadata");
                return meta.get("first_name").getAsString() + " " + meta.get("last_name").getAsString();
            }
        }
        return playerID;
    }

    private static boolean isKeeper(JsonObject pick){
        JsonElement flag = pick.get("is_keeper");
        return flag != null && !flag.isJsonNull() && flag.getAsBoolean();
    }
}
