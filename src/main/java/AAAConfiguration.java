import com.google.gson.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for "which league am I playing in".
 *
 * Only the league id and the sleeper username are configured (see
 * {@link AAAConfigurationSleeperLeague}); the draft id, the previous season's
 * league/draft, the roster of humans and the season are all read back from the
 * Sleeper API. Before 2026 those were pasted in as literals all over the
 * codebase and went stale every August.
 */
public class AAAConfiguration {

    private String leagueID;
    private String myUsername;
    private String myNameForLeague;

    private static AAAConfiguration instance;

    /**
     * The configured league. Everything that used to reach for a hardcoded
     * league/draft id now goes through here.
     */
    public static synchronized AAAConfiguration getInstance(){
        if(instance == null){
            instance = new AAAConfigurationSleeperLeague();
        }
        return instance;
    }

    public AAAConfiguration(String leagueID, String myUsername, String myNameForLeague){
        this.leagueID = leagueID;
        this.myUsername = myUsername;
        this.myNameForLeague = myNameForLeague;
    }

    public String getLeagueID() {
        return leagueID;
    }

    public String getMyID(){
        return InOutUtilities.getThisMonthsMyID(myUsername);
    }

    public String getMyNameForLeague(){
        return myNameForLeague + leagueID;
    }

    public String getRosterWebURL(){
        return leagueWebURL(this.leagueID) + "/rosters";
    }

    public String getUsersWebURL(){
        return leagueWebURL(this.leagueID) + "/users";
    }

    private static String leagueWebURL(String leagueID){
        return "https://api.sleeper.app/v1/league/" + leagueID;
    }

    private String getDraftsWebURL(){
        return leagueWebURL(this.leagueID) + "/drafts";
    }

    private String getDraftPicksWebURL(){
        return draftPicksWebURL(getDraftID());
    }

    public static String draftPicksWebURL(String draftID){
        return "https://api.sleeper.app/v1/draft/" + draftID + "/picks";
    }

    public static String draftWebURL(String draftID){
        return "https://api.sleeper.app/v1/draft/" + draftID;
    }

    public static String filepathStartSeriousRosters = "seriousRostersForKeepers";
    public static String filepathStartUsers = "leagueUsers";
    public static String filepathStartLeague = "leagueSettings";
    public static String filepathStartPreviousLeague = "previousLeagueSettings";
    public static String filepathStartDrafts = "draftsData";
    public static String filepathStartDraft = "draftData";
    public static String filepathStartPreviousDraftPicks = "previousSeasonDraftPicks";

    public String getTodaysRosterWebPageSerious(){
        return InOutUtilities.getTodaysWebPage(this.getRosterWebURL(), filepathStartSeriousRosters + leagueID);
    }

    public String getTodaysDrafts(){
        return InOutUtilities.getTodaysWebPage(this.getDraftsWebURL(), filepathStartDrafts + leagueID);
    }

    public String getTodaysDraftPicks(){
        return InOutUtilities.getTodaysWebPage(getDraftPicksWebURL(), filepathStartDraft + leagueID);
    }

    private JsonObject leagueJson;

    /** The league object itself: scoring settings, season, draft id, previous league id. */
    public JsonObject getLeagueJson(){
        if(leagueJson == null){
            String data = InOutUtilities.getTodaysWebPage(leagueWebURL(this.leagueID), filepathStartLeague + leagueID);
            leagueJson = JsonParser.parseString(data).getAsJsonObject();
        }
        return leagueJson;
    }

    private static String optionalString(JsonObject object, String key){
        JsonElement element = object.get(key);
        if(element == null || element.isJsonNull()){
            return null;
        }
        return element.getAsString();
    }

    /** e.g. "2026". Read from the league rather than the system clock. */
    public String getSeason(){
        return optionalString(getLeagueJson(), "season");
    }

    public String getDraftID(){
        String draftID = optionalString(getLeagueJson(), "draft_id");
        if(draftID == null){
            // Older leagues only expose the draft through the /drafts collection.
            draftID = getDraftFromLeagueIfOnlyOneDraft();
        }
        return draftID;
    }

    /** Null in the league's very first season. */
    public String getPreviousLeagueID(){
        return optionalString(getLeagueJson(), "previous_league_id");
    }

    /**
     * The draft that sets keeper cost: a keeper's price is the round they were
     * taken in last season, so this is last season's draft, not this one's
     * (which is still empty until draft day).
     */
    public String getPreviousDraftID(){
        String previousLeagueID = getPreviousLeagueID();
        if(previousLeagueID == null){
            return null;
        }
        String data = InOutUtilities.getTodaysWebPage(leagueWebURL(previousLeagueID),
                filepathStartPreviousLeague + leagueID);
        JsonObject previousLeague = JsonParser.parseString(data).getAsJsonObject();
        return optionalString(previousLeague, "draft_id");
    }

    public String getPreviousSeasonDraftPicks(){
        List<JsonArray> history = getPreviousDraftPicks();
        return history.isEmpty() ? "[]" : history.get(0).toString();
    }

    private List<JsonArray> previousDraftPicks;

    /**
     * Picks from every earlier draft in this league's history, most recent
     * first.
     *
     * Keeper pricing needs more than last season: a player kept two years
     * running costs a round more than one kept once, and the ruleset caps that
     * at three consecutive years, so the chain has to be walked back far enough
     * to count them. Sleeper links each season to the one before it through
     * previous_league_id.
     */
    public synchronized List<JsonArray> getPreviousDraftPicks(){
        if(previousDraftPicks != null){
            return previousDraftPicks;
        }
        List<JsonArray> history = new ArrayList<>();
        String previousLeagueID = getPreviousLeagueID();
        int guard = 0;
        while(previousLeagueID != null && guard++ < MAX_SEASONS_OF_HISTORY){
            String leagueData = InOutUtilities.getTodaysWebPage(leagueWebURL(previousLeagueID),
                    filepathStartPreviousLeague + previousLeagueID);
            JsonObject previousLeague = JsonParser.parseString(leagueData).getAsJsonObject();

            String draftID = optionalString(previousLeague, "draft_id");
            if(draftID != null){
                String picks = InOutUtilities.getTodaysWebPage(draftPicksWebURL(draftID),
                        filepathStartPreviousDraftPicks + draftID);
                history.add(JsonParser.parseString(picks).getAsJsonArray());
            }
            previousLeagueID = optionalString(previousLeague, "previous_league_id");
        }
        previousDraftPicks = history;
        return previousDraftPicks;
    }

    /** Far enough back to settle a three-consecutive-year keeper, with room to spare. */
    private static final int MAX_SEASONS_OF_HISTORY = 8;

    public ArrayList<JsonElement> getTodaysRoster() {
        String websiteData = getTodaysRosterWebPageSerious();
        JsonArray unparsedRosters = JsonParser.parseString(websiteData).getAsJsonArray();
        ArrayList<JsonElement> jsonRosters = new ArrayList<>();
        for (JsonElement jsonRoster : unparsedRosters) {
            jsonRosters.add(jsonRoster);
        }
        return jsonRosters;
    }

    private Map<String, String> userIDToDisplayName;

    /** Sleeper user id -> display name, straight from the league. */
    public synchronized Map<String, String> getUserIDToDisplayName(){
        if(userIDToDisplayName == null){
            Map<String, String> names = new LinkedHashMap<>();
            String data = InOutUtilities.getTodaysWebPage(getUsersWebURL(), filepathStartUsers + leagueID);
            for(JsonElement jsonUser : JsonParser.parseString(data).getAsJsonArray()){
                JsonObject user = jsonUser.getAsJsonObject();
                String userID = optionalString(user, "user_id");
                String displayName = optionalString(user, "display_name");
                if(userID != null){
                    names.put(userID, displayName == null ? userID : displayName);
                }
            }
            userIDToDisplayName = names;
        }
        return userIDToDisplayName;
    }

    /**
     * The keepers everyone has declared, priced at the round they were drafted
     * in last season. Empty until somebody declares one.
     */
    public ArrayList<Keeper> getTodaysKeepers(){
        return priceTodaysKeepers().keepers;
    }

    /** Keepers plus anything the rules disallow, for reporting. */
    public KeeperPricing.PricedKeepers priceTodaysKeepers(){
        JsonArray rosters = JsonParser.parseString(getTodaysRosterWebPageSerious()).getAsJsonArray();
        return KeeperPricing.price(rosters, getPreviousDraftPicks(),
                Player::getPlayerFromSIDV2, SleeperProjections::adpOf);
    }

    public String getDraftFromLeagueIfOnlyOneDraft(){
        String apiData = getTodaysDrafts();
        JsonArray unparsedDrafts = JsonParser.parseString(apiData).getAsJsonArray();
        ArrayList<JsonElement> jsonDrafts = new ArrayList<>();
        for (JsonElement jsonRoster : unparsedDrafts) {
            jsonDrafts.add(jsonRoster);
        }
        if(jsonDrafts.size()!=1){
            throw new RuntimeException("league has more than 1 draft");
        }
        return ((JsonObject) jsonDrafts.get(0)).get("draft_id").getAsString();
    }

    public static void main(String[] args){
        AAAConfiguration aaaConfiguration = AAAConfiguration.getInstance();
        System.out.println("league:\t" + aaaConfiguration.getLeagueID() + "\tseason:\t" + aaaConfiguration.getSeason());
        System.out.println("draft:\t" + aaaConfiguration.getDraftID());
        System.out.println("previous league:\t" + aaaConfiguration.getPreviousLeagueID()
                + "\tprevious draft:\t" + aaaConfiguration.getPreviousDraftID());
        System.out.println("me:\t" + aaaConfiguration.getMyID());
        KeeperPricing.PricedKeepers priced = aaaConfiguration.priceTodaysKeepers();
        for(Keeper keeper : priced.keepers){
            System.out.println("keeper:\t"
                    + HumanOfInterest.getHumanFromID(keeper.humanWhoCanKeep) + "\t"
                    + keeper.player.firstName + " " + keeper.player.lastName
                    + "\tround " + keeper.roundCanBeKept);
        }
        for(String rejection : priced.rejected){
            System.out.println("not a legal keeper:\t" + rejection);
        }
    }

}
