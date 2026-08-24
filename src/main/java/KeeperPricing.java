import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns declared keepers into keepers with a price.
 *
 * A keeper costs the round they went in last season's draft, or a last-round
 * pick if they were never drafted. Declarations trickle in over the weeks
 * before the draft, so every stage of that has to work: nobody has picked yet,
 * some people have, some rosters have one and some have two.
 *
 * Pure on purpose - it takes the two API responses and a lookup, so the pricing
 * rules can be tested without the network.
 */
public class KeeperPricing {

    /** Sleeper player id -> Player. */
    public interface PlayerLookup {
        Player find(String sleeperID);
    }

    /**
     * @param rosters             /league/{id}/rosters
     * @param previousDraftPicks  /draft/{previous draft id}/picks
     */
    public static ArrayList<Keeper> priceKeepers(JsonArray rosters,
                                                 JsonArray previousDraftPicks,
                                                 PlayerLookup lookup){
        Map<String, Integer> roundsByPlayerID = roundsByPlayerID(previousDraftPicks);

        ArrayList<Keeper> keepers = new ArrayList<>();
        for(JsonElement rosterElement : rosters){
            JsonObject roster = rosterElement.getAsJsonObject();

            JsonElement keepersElement = roster.get("keepers");
            // null before anyone declares, [] once someone declares nobody.
            if(keepersElement == null || keepersElement.isJsonNull()){
                continue;
            }
            String ownerID = optionalString(roster, "owner_id");
            if(ownerID == null){
                // An abandoned team has no owner to keep anybody.
                continue;
            }

            for(JsonElement keeperElement : keepersElement.getAsJsonArray()){
                if(keeperElement.isJsonNull()){
                    continue;
                }
                // Read as a string: a defense's player id is its team ("CHI"),
                // and getAsInt would throw on it.
                String playerID = keeperElement.getAsString();
                Player player = lookup.find(playerID);
                if(player == null){
                    continue;
                }
                int round = roundsByPlayerID.getOrDefault(playerID, Keeper.UNDRAFTED_ROUND_COST);
                keepers.add(new Keeper(ownerID, player, round));
            }
        }
        return keepers;
    }

    /** Which round each player went in, from a draft's picks. */
    public static Map<String, Integer> roundsByPlayerID(JsonArray draftPicks){
        Map<String, Integer> rounds = new HashMap<>();
        for(JsonElement pickElement : draftPicks){
            JsonObject pick = pickElement.getAsJsonObject();
            String playerID = optionalString(pick, "player_id");
            JsonElement round = pick.get("round");
            if(playerID == null || round == null || round.isJsonNull()){
                continue;
            }
            rounds.put(playerID, round.getAsInt());
        }
        return rounds;
    }

    private static String optionalString(JsonObject object, String key){
        JsonElement element = object.get(key);
        if(element == null || element.isJsonNull()){
            return null;
        }
        return element.getAsString();
    }

}
