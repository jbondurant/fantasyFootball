import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the twelve humans in THIS league actually did, week by week, once the
 * draft was over.
 *
 * The repo has five completed seasons of this league and has only ever looked
 * at the DRAFTS. Every in-season claim the model makes - when a bust gets
 * benched, how fast a breakout gets picked up, whether a manager reacts at all
 * - has therefore been an assumption. Sleeper keeps the transaction log, one
 * request per league per week, and it is the ground truth for all of it:
 *
 *     GET /v1/league/{id}/transactions/{week}
 *
 * Each row carries `adds` and `drops` as player-id -> roster-id, a `leg` that
 * is the week, a `type` (waiver / free_agent / trade), a `status`, and for a
 * waiver the `waiver_bid`. FAILED CLAIMS ARE IN THERE TOO, and they are half
 * the rows - in one sampled week, 42 of 71. They are kept rather than filtered
 * because a failed claim is evidence of INTENT, which is the thing being
 * measured, while a completed claim is evidence of intent plus winning the
 * priority order. The two questions want different denominators, so
 * {@link Move#complete()} keeps them apart instead of choosing for the caller.
 *
 * Cached forever: a finished season's log does not change. The live season is
 * not fetched at all - {@link #chain} stops at the pre-draft league.
 */
public class LeagueTransactions {

    /** Sleeper's regular season: 17 weeks through 2020, 18 from 2021. */
    static int weeks(String season){
        return EraActuals.weeks(season);
    }

    /** One league in the keeper chain: its id, its season, and its draft. */
    public record Year(String season, String leagueID, String draftID,
                       String status){
        public boolean complete(){
            return "complete".equals(status);
        }
    }

    /** One add/drop. `week` is Sleeper's leg; `bid` is 0 outside FAAB waivers. */
    public record Move(String season, int week, String type, String status,
                       Map<String, Integer> adds, Map<String, Integer> drops,
                       int bid){
        public boolean complete(){
            return "complete".equals(status);
        }
    }

    static String leagueRaw(String leagueID){
        return InOutUtilities.getCachedForever(
                "https://api.sleeper.app/v1/league/" + leagueID,
                "sleeperLeagueMeta" + leagueID);
    }

    // A quiet week really has no transactions, and week 18 of a finished season
    // never will - so an empty answer here is an answer, not a question asked
    // too early (TRAPS #85).
    static String transactionsRaw(String leagueID, int week){
        return InOutUtilities.getCachedForeverAllowingEmpty(
                "https://api.sleeper.app/v1/league/" + leagueID + "/transactions/" + week,
                "sleeperTxns" + leagueID + "w" + week);
    }

    static String picksRaw(String draftID){
        return InOutUtilities.getCachedForever(
                "https://api.sleeper.app/v1/draft/" + draftID + "/picks",
                "sleeperDraftPicks" + draftID);
    }

    /**
     * Every season of this league, newest first, walked back through
     * previous_league_id.
     *
     * Includes the CURRENT pre-draft league so callers can see where the chain
     * starts; {@link Year#complete} is how a caller drops it. Guarded at twenty
     * hops so a cycle in the feed cannot spin forever.
     */
    public static List<Year> chain(String leagueID){
        List<Year> years = new ArrayList<>();
        String id = leagueID;
        int guard = 0;
        while(id != null && !id.isBlank() && guard++ < 20){
            JsonObject league = JsonParser.parseString(leagueRaw(id)).getAsJsonObject();
            years.add(new Year(text(league, "season"), id, text(league, "draft_id"),
                    text(league, "status")));
            String previous = text(league, "previous_league_id");
            id = previous.isBlank() ? null : previous;
        }
        return years;
    }

    /** Every completed season of the chain, oldest first. */
    public static List<Year> completedSeasons(String leagueID){
        List<Year> years = new ArrayList<>();
        for(Year year : chain(leagueID)){
            if(year.complete()){
                years.add(year);
            }
        }
        years.sort((a, b) -> a.season().compareTo(b.season()));
        return years;
    }

    /** One season's whole log, every week, in week order. */
    public static List<Move> moves(Year year){
        List<Move> moves = new ArrayList<>();
        for(int week = 1; week <= weeks(year.season()); week++){
            JsonArray rows = JsonParser.parseString(
                    transactionsRaw(year.leagueID(), week)).getAsJsonArray();
            for(JsonElement element : rows){
                JsonObject row = element.getAsJsonObject();
                moves.add(new Move(year.season(),
                        row.has("leg") && !row.get("leg").isJsonNull()
                                ? row.get("leg").getAsInt() : week,
                        text(row, "type"), text(row, "status"),
                        idToRoster(row, "adds"), idToRoster(row, "drops"),
                        bid(row)));
            }
        }
        return moves;
    }

    /** Where each player was taken: sleeper id -> overall pick number. */
    public static Map<String, Integer> draftPicks(Year year){
        Map<String, Integer> picks = new LinkedHashMap<>();
        if(year.draftID() == null || year.draftID().isBlank()){
            return picks;
        }
        for(JsonElement element : JsonParser.parseString(picksRaw(year.draftID()))
                .getAsJsonArray()){
            JsonObject pick = element.getAsJsonObject();
            String player = text(pick, "player_id");
            if(player.isBlank() || !pick.has("pick_no")
                    || pick.get("pick_no").isJsonNull()){
                continue;
            }
            picks.put(player, pick.get("pick_no").getAsInt());
        }
        return picks;
    }

    /** Which roster drafted each player, so a drop can be told from a churn. */
    public static Map<String, Integer> draftedBy(Year year){
        Map<String, Integer> owner = new HashMap<>();
        if(year.draftID() == null || year.draftID().isBlank()){
            return owner;
        }
        for(JsonElement element : JsonParser.parseString(picksRaw(year.draftID()))
                .getAsJsonArray()){
            JsonObject pick = element.getAsJsonObject();
            String player = text(pick, "player_id");
            if(!player.isBlank() && pick.has("roster_id")
                    && !pick.get("roster_id").isJsonNull()){
                owner.put(player, pick.get("roster_id").getAsInt());
            }
        }
        return owner;
    }

    /** Season -> its log, for every completed season. */
    public static Map<String, List<Move>> everySeason(String leagueID){
        Map<String, List<Move>> all = new TreeMap<>();
        for(Year year : completedSeasons(leagueID)){
            all.put(year.season(), moves(year));
        }
        return all;
    }

    static Map<String, Integer> idToRoster(JsonObject row, String key){
        Map<String, Integer> map = new LinkedHashMap<>();
        JsonElement element = row.get(key);
        if(element == null || !element.isJsonObject()){
            return map;
        }
        for(Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()){
            if(!entry.getValue().isJsonNull()){
                map.put(entry.getKey(), entry.getValue().getAsInt());
            }
        }
        return map;
    }

    static int bid(JsonObject row){
        JsonElement settings = row.get("settings");
        if(settings == null || !settings.isJsonObject()){
            return 0;
        }
        JsonElement amount = settings.getAsJsonObject().get("waiver_bid");
        return amount == null || amount.isJsonNull() ? 0 : amount.getAsInt();
    }

    static String text(JsonObject object, String key){
        JsonElement element = object == null ? null : object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }
}
