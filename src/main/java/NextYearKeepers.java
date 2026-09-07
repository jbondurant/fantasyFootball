import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * WHAT EACH MAN WOULD COST TO KEEP NEXT SEASON, priced off THIS season's draft.
 *
 * Justin, 2026-09-07: "how is skattebo getting keeper value if i drafted him
 * this year, and he hasn't yet played." He had not: the console's keeper panel
 * came from `KeeperChooser.eligibleCandidates`, which prices against
 * `getPreviousDraftPicks` - "picks from every EARLIER draft" - so in the 2026
 * season it reads the 2025 board. That is the right basis for deciding 2026
 * keepers, a decision made in August and already history. It is the wrong basis
 * for 2027, and a man who did not exist in the 2025 draft got the undrafted
 * default of a tenth-round pick instead of the third-rounder he actually cost.
 *
 * Ten of sixteen rounds on that panel were wrong, in both directions - Skattebo
 * shown at r9 against a true r3, Bo Nix at r8 against a true r15 - so the two
 * men it named as keepers were chosen on prices that did not exist.
 *
 * The ruleset, from {@link KeeperPricing}, applied to the 2026 board:
 *
 *   - a keeper costs the round he went in the most recent draft
 *   - every consecutive year kept, that cost goes UP a round (earlier pick),
 *     and three consecutive years is the limit
 *   - nobody taken in the first two rounds can be kept at all
 *   - an undrafted man costs a tenth-round pick
 *
 * The escalation reads off `is_keeper`: Sleeper marks a kept man's pick, and the
 * round on that pick already carries every earlier escalation, so this season
 * adds exactly one more.
 */
public class NextYearKeepers {

    /** Why a man cannot be kept, or the round he would cost. */
    public record Cost(String playerID, String name, int round, String refusal) {

        public boolean keepable(){
            return refusal == null;
        }
    }

    /**
     * Every man on every roster, priced for next season off `draftPicks`.
     *
     * Keyed by playerID so a caller can look up his own men or a rival's without
     * knowing which roster anybody is.
     */
    static Map<String, Cost> from(String draftPicks){
        Map<String, Cost> costs = new HashMap<>();
        for(JsonElement element : JsonParser.parseString(draftPicks).getAsJsonArray()){
            JsonObject pick = element.getAsJsonObject();
            if(!pick.has("player_id") || pick.get("player_id").isJsonNull()){
                continue;
            }
            String id = pick.get("player_id").getAsString();
            int round = pick.get("round").getAsInt();
            boolean wasKept = pick.has("is_keeper") && !pick.get("is_keeper").isJsonNull()
                    && pick.get("is_keeper").getAsBoolean();
            JsonObject meta = pick.has("metadata") ? pick.getAsJsonObject("metadata") : null;
            String name = meta == null ? id
                    : (text(meta, "first_name") + " " + text(meta, "last_name")).trim();

            if(round <= KeeperPricing.HIGHEST_KEEPABLE_DRAFT_ROUND){
                costs.put(id, new Cost(id, name, round,
                        "taken in round " + round + "; the first "
                                + KeeperPricing.HIGHEST_KEEPABLE_DRAFT_ROUND
                                + " rounds cannot be kept"));
                continue;
            }
            // last season's round already carries every earlier escalation, so
            // being kept once more moves it exactly one
            int cost = wasKept ? round - 1 : round;
            if(cost < 1){
                costs.put(id, new Cost(id, name, round, "would cost better than a first-round pick"));
                continue;
            }
            costs.put(id, new Cost(id, name, cost, null));
        }
        return costs;
    }

    /**
     * The cost for a man who is on a roster but was not in the draft at all -
     * picked off waivers during the season. The ruleset prices him at a tenth.
     */
    static Cost undrafted(String playerID, String name){
        return new Cost(playerID, name, Keeper.UNDRAFTED_ROUND_COST, null);
    }

    private static String text(JsonObject object, String field){
        return object.has(field) && !object.get(field).isJsonNull()
                ? object.get(field).getAsString() : "";
    }
}
