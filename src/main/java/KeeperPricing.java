import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns declared keepers into keepers with a price, per the league ruleset.
 *
 *   - A keeper costs the round they went in last season's draft.
 *   - Every consecutive year a player is kept, that cost goes up a round.
 *     Three consecutive years is the limit.
 *   - Nobody drafted in the first two rounds can be kept, and no cost can go
 *     above a first-round pick.
 *   - An undrafted player costs a 10th-round pick.
 *   - If two keepers on one roster land on the same round, one of them goes up
 *     a round: normally the later-ADP player, except when the other one is
 *     there by consecutive-year escalation, which takes priority.
 *
 * Sleeper marks a keeper's pick in the draft with is_keeper, and the round on
 * that pick is what they cost that year - so last season's round already has
 * every earlier escalation baked into it, and this season only has to add one
 * more.
 *
 * Pure on purpose: it takes the API responses and two lookups, so the rules can
 * be tested without the network.
 */
public class KeeperPricing {

    /** Sleeper player id -> Player. */
    public interface PlayerLookup {
        Player find(String sleeperID);
    }

    /**
     * Sleeper player id -> average draft position, lower being drafted earlier.
     *
     * Only consulted when two keepers cost the same round and at least one of
     * them went undrafted, so there is no real draft position to compare.
     */
    public interface AdpLookup {
        double adpOf(String sleeperID);
    }

    /** How many consecutive years the ruleset lets you hold the same player. */
    public static final int MAX_CONSECUTIVE_YEARS = 3;

    /** Nobody taken this early last season can be kept. */
    public static final int HIGHEST_KEEPABLE_DRAFT_ROUND = 2;

    public static class PricedKeepers {
        public final ArrayList<Keeper> keepers;
        /** Declared keepers the rules do not allow, and why. */
        public final List<String> rejected;

        PricedKeepers(ArrayList<Keeper> keepers, List<String> rejected){
            this.keepers = keepers;
            this.rejected = rejected;
        }
    }

    /** One declared keeper, mid-calculation. */
    private static class Candidate {
        final String playerID;
        final Player player;
        final String ownerID;
        int round;
        boolean viaConsecutiveYear;
        /** Where they went in last season's draft; -1 if undrafted. */
        int draftPickNumber = -1;
        double adp;

        Candidate(String playerID, Player player, String ownerID){
            this.playerID = playerID;
            this.player = player;
            this.ownerID = ownerID;
        }

        String name(){
            return player.firstName + " " + player.lastName;
        }
    }

    /**
     * @param rosters               /league/{id}/rosters for this season
     * @param previousDrafts        picks from each earlier draft, most recent first
     */
    public static PricedKeepers price(JsonArray rosters,
                                      List<JsonArray> previousDrafts,
                                      PlayerLookup lookup,
                                      AdpLookup adp){
        List<Map<String, JsonObject>> history = new ArrayList<>();
        for(JsonArray draft : previousDrafts){
            history.add(picksByPlayerID(draft));
        }

        ArrayList<Keeper> keepers = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        for(JsonElement rosterElement : rosters){
            JsonObject roster = rosterElement.getAsJsonObject();

            JsonElement declared = roster.get("keepers");
            // null before anyone declares, [] once someone declares nobody.
            if(declared == null || declared.isJsonNull()){
                continue;
            }
            String ownerID = optionalString(roster, "owner_id");
            if(ownerID == null){
                // An abandoned team has no owner to keep anybody.
                continue;
            }

            List<Candidate> candidates = new ArrayList<>();
            for(JsonElement keeperElement : declared.getAsJsonArray()){
                if(keeperElement.isJsonNull()){
                    continue;
                }
                // Read as a string: a defense's player id is its team ("CHI").
                String playerID = keeperElement.getAsString();
                Player player = lookup.find(playerID);
                if(player == null){
                    continue;
                }
                Candidate candidate = new Candidate(playerID, player, ownerID);
                String problem = cost(candidate, history);
                if(problem != null){
                    rejected.add(candidate.name() + ": " + problem);
                    continue;
                }
                candidate.adp = adp.adpOf(playerID);
                candidates.add(candidate);
            }

            rejected.addAll(resolveSameRoundCosts(candidates));

            for(Candidate candidate : candidates){
                keepers.add(new Keeper(candidate.ownerID, candidate.player, candidate.round));
            }
        }
        return new PricedKeepers(keepers, rejected);
    }

    /** Keepers only, for callers that do not care why anything was rejected. */
    public static ArrayList<Keeper> priceKeepers(JsonArray rosters,
                                                 List<JsonArray> previousDrafts,
                                                 PlayerLookup lookup,
                                                 AdpLookup adp){
        return price(rosters, previousDrafts, lookup, adp).keepers;
    }

    /**
     * Fills in the candidate's cost. Returns null when it is a legal keeper, or
     * the reason it is not.
     */
    private static String cost(Candidate candidate, List<Map<String, JsonObject>> history){
        if(history.isEmpty()){
            // No previous season to price from; everyone is a waiver pickup.
            candidate.round = Keeper.UNDRAFTED_ROUND_COST;
            return null;
        }

        JsonObject lastSeason = history.get(0).get(candidate.playerID);
        if(lastSeason == null){
            // Never drafted last season: a waiver pickup, at a fixed price.
            candidate.round = Keeper.UNDRAFTED_ROUND_COST;
            return null;
        }

        int priorConsecutiveYears = countConsecutiveKeeperYears(candidate.playerID, history);
        if(priorConsecutiveYears >= MAX_CONSECUTIVE_YEARS){
            return "already kept " + priorConsecutiveYears + " years running, the limit is "
                    + MAX_CONSECUTIVE_YEARS;
        }

        int draftedRound = originalDraftRound(candidate.playerID, history);
        if(draftedRound > 0 && draftedRound <= HIGHEST_KEEPABLE_DRAFT_ROUND){
            return "drafted in round " + draftedRound + "; the first "
                    + HIGHEST_KEEPABLE_DRAFT_ROUND + " rounds cannot be kept";
        }

        candidate.draftPickNumber = lastSeason.get("pick_no").getAsInt();

        // Last season's round already carries every earlier escalation, so a
        // player kept again only moves one more round.
        int round = lastSeason.get("round").getAsInt();
        if(priorConsecutiveYears > 0){
            round -= 1;
            candidate.viaConsecutiveYear = true;
        }

        if(round < 1){
            return "would cost better than a first-round pick";
        }
        candidate.round = round;
        return null;
    }

    /**
     * How many seasons in a row, ending with last season, this player was
     * already somebody's keeper.
     */
    private static int countConsecutiveKeeperYears(String playerID, List<Map<String, JsonObject>> history){
        int years = 0;
        for(Map<String, JsonObject> season : history){
            JsonObject pick = season.get(playerID);
            if(pick == null || !isKeeper(pick)){
                break;
            }
            years++;
        }
        return years;
    }

    /**
     * The round this player was actually drafted in, walking back past the
     * years they were kept. Returns -1 when the history does not reach it.
     */
    private static int originalDraftRound(String playerID, List<Map<String, JsonObject>> history){
        for(Map<String, JsonObject> season : history){
            JsonObject pick = season.get(playerID);
            if(pick == null){
                return -1;
            }
            if(!isKeeper(pick)){
                return pick.get("round").getAsInt();
            }
        }
        return -1;
    }

    /**
     * Two keepers cannot both cost the same round, so one goes up.
     *
     * The ruleset sends "the player with the lower ADP" up a round, which means
     * the one taken earlier - the more valuable of the two pays the dearer
     * pick. The exception is when the other player is only on that round
     * because of consecutive-year escalation; that player has priority to keep
     * their cost rather than being moved twice.
     *
     * Compared on where they actually went in last season's draft rather than
     * on preseason ADP. The one clash in six seasons that turned on this settles
     * it: in 2025 Jerry Jeudy and Jayden Daniels both cost an 8th, ADP had
     * Daniels well ahead (35.6 to 75.2), but Jeudy went one pick earlier in the
     * 2024 draft - 85 to 86 - and Jeudy is the one who moved to a 7th.
     * ADP is only the fallback for an undrafted player, who has no pick number.
     */
    private static List<String> resolveSameRoundCosts(List<Candidate> candidates){
        List<String> rejected = new ArrayList<>();
        Map<Integer, List<Candidate>> byRound = new HashMap<>();
        for(Candidate candidate : candidates){
            byRound.computeIfAbsent(candidate.round, round -> new ArrayList<>()).add(candidate);
        }

        for(List<Candidate> clashing : byRound.values()){
            if(clashing.size() < 2){
                continue;
            }
            boolean everyoneWasDrafted = clashing.stream().allMatch(c -> c.draftPickNumber > 0);

            // Most entitled to keep the round comes first: a consecutive-year
            // keeper, then whoever was taken later.
            clashing.sort(Comparator
                    .comparing((Candidate candidate) -> !candidate.viaConsecutiveYear)
                    .thenComparing(Comparator.comparingDouble(
                            (Candidate candidate) -> everyoneWasDrafted
                                    ? candidate.draftPickNumber
                                    : candidate.adp).reversed()));

            for(int i = 1; i < clashing.size(); i++){
                Candidate bumped = clashing.get(i);
                bumped.round -= i;
                if(bumped.round < 1){
                    rejected.add(bumped.name() + ": would cost better than a first-round pick "
                            + "once moved up off a shared round");
                }
            }
        }
        candidates.removeIf(candidate -> candidate.round < 1);
        return rejected;
    }

    private static boolean isKeeper(JsonObject pick){
        JsonElement flag = pick.get("is_keeper");
        return flag != null && !flag.isJsonNull() && flag.getAsBoolean();
    }

    private static Map<String, JsonObject> picksByPlayerID(JsonArray draftPicks){
        Map<String, JsonObject> picks = new HashMap<>();
        for(JsonElement pickElement : draftPicks){
            JsonObject pick = pickElement.getAsJsonObject();
            String playerID = optionalString(pick, "player_id");
            if(playerID == null || pick.get("round") == null || pick.get("round").isJsonNull()){
                continue;
            }
            picks.put(playerID, pick);
        }
        return picks;
    }

    /** Which round each player went in, from a draft's picks. */
    public static Map<String, Integer> roundsByPlayerID(JsonArray draftPicks){
        Map<String, Integer> rounds = new HashMap<>();
        for(Map.Entry<String, JsonObject> entry : picksByPlayerID(draftPicks).entrySet()){
            rounds.put(entry.getKey(), entry.getValue().get("round").getAsInt());
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
