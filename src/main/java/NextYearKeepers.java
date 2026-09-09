import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.List;
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
        return from(draftPicks, Map.of());
    }

    /**
     * ...and with the CONSECUTIVE-YEAR CAP applied.
     *
     * `KeeperPricing.MAX_CONSECUTIVE_YEARS` is three, and the first version of
     * this ignored it entirely: it read `is_keeper` on the current pick, added
     * one round, and stopped. Seven men in this league have now been kept in
     * 2024, 2025 AND 2026 - Taylor, Achane, Collins, Kyren Williams, LaPorta,
     * Nacua, Chase Brown - and every one of them was priced as keepable for a
     * fourth year they are not allowed. That is 472 points of keeper surplus
     * that cannot exist, and it does not sit idle: `lossAverseOnKeepers` charges
     * a rival that surplus for parting with the man, so the board was pricing
     * other managers' keepers into trades they could not actually keep.
     *
     * `priorYears` is how many consecutive seasons each man has ALREADY been
     * kept, counted from the earlier drafts.
     */
    static Map<String, Cost> from(String draftPicks, Map<String, Integer> priorYears){
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
            // one more year on top of what he has already served
            int served = wasKept ? priorYears.getOrDefault(id, 0) + 1 : 0;
            if(served >= KeeperPricing.MAX_CONSECUTIVE_YEARS){
                costs.put(id, new Cost(id, name, round,
                        "kept " + served + " seasons running; the limit is "
                                + KeeperPricing.MAX_CONSECUTIVE_YEARS));
                continue;
            }
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
     * How many consecutive seasons each man has ALREADY been kept, counted back
     * through the earlier drafts.
     *
     * A man carries `is_keeper` on the pick that kept him, so the chain is just
     * "was he a keeper last season, and the one before that". The walk stops at
     * the first season he was not, because the cap is on CONSECUTIVE years - a
     * man kept, released and kept again starts over.
     */
    static Map<String, Integer> consecutiveYears(List<String> earlierDraftsNewestFirst){
        Map<String, Integer> years = new HashMap<>();
        java.util.Set<String> stillRunning = null;
        for(String picks : earlierDraftsNewestFirst){
            java.util.Set<String> keptThisYear = new java.util.HashSet<>();
            for(JsonElement element : JsonParser.parseString(picks).getAsJsonArray()){
                JsonObject pick = element.getAsJsonObject();
                if(pick.has("player_id") && !pick.get("player_id").isJsonNull()
                        && pick.has("is_keeper") && !pick.get("is_keeper").isJsonNull()
                        && pick.get("is_keeper").getAsBoolean()){
                    keptThisYear.add(pick.get("player_id").getAsString());
                }
            }
            java.util.Set<String> extend = stillRunning == null ? keptThisYear
                    : new java.util.HashSet<>(keptThisYear);
            if(stillRunning != null){
                extend.retainAll(stillRunning);      // the streak has to be unbroken
            }
            if(extend.isEmpty()){
                break;
            }
            for(String id : extend){
                years.merge(id, 1, Integer::sum);
            }
            stillRunning = extend;
        }
        return years;
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
