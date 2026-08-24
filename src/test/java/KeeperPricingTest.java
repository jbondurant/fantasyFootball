import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The league ruleset's keeper rules.
 *
 * Declarations arrive over the weeks before the draft, so the pricing has to
 * hold up at every stage; and the cost of a keeper is not simply last year's
 * round once a player has been held more than one season.
 */
class KeeperPricingTest {

    private static final Player CHASE = TestPlayers.player("Ja'Marr", "Chase", "CIN", Position.WR, 7564);
    private static final Player GIBBS = TestPlayers.player("Jahmyr", "Gibbs", "DET", Position.RB, 9226);
    private static final Player ALLEN = TestPlayers.player("Josh", "Allen", "BUF", Position.QB, 4984);
    private static final Player NABERS = TestPlayers.player("Malik", "Nabers", "NYG", Position.WR, 11565);
    private static final Player BEARS = TestPlayers.defense("Chicago", "Bears", "CHI");

    private static final KeeperPricing.PlayerLookup LOOKUP = buildLookup();

    private static KeeperPricing.PlayerLookup buildLookup(){
        Map<String, Player> byID = new HashMap<>();
        byID.put("7564", CHASE);
        byID.put("9226", GIBBS);
        byID.put("4984", ALLEN);
        byID.put("11565", NABERS);
        byID.put("CHI", BEARS);
        return byID::get;
    }

    /** Lower is drafted earlier. Chase is the most valuable of these. */
    private static final KeeperPricing.AdpLookup ADP = sleeperID -> {
        switch(sleeperID){
            case "7564": return 5.0;
            case "9226": return 12.0;
            case "11565": return 30.0;
            case "4984": return 60.0;
            default: return Double.MAX_VALUE;
        }
    };

    private static JsonArray json(String text){
        return JsonParser.parseString(text).getAsJsonArray();
    }

    private static ArrayList<Keeper> price(JsonArray rosters, JsonArray... previousDrafts){
        return KeeperPricing.priceKeepers(rosters, List.of(previousDrafts), LOOKUP, ADP);
    }

    private static KeeperPricing.PricedKeepers priceDetailed(JsonArray rosters, JsonArray... previousDrafts){
        return KeeperPricing.price(rosters, List.of(previousDrafts), LOOKUP, ADP);
    }

    /** Chase round 3, Gibbs round 6, neither previously kept. */
    private static JsonArray lastSeason(){
        return json("["
                + "{\"player_id\":\"7564\",\"round\":3,\"is_keeper\":null},"
                + "{\"player_id\":\"9226\",\"round\":6,\"is_keeper\":null},"
                + "{\"player_id\":\"11565\",\"round\":6,\"is_keeper\":null},"
                + "{\"player_id\":\"1111\",\"round\":12,\"is_keeper\":null}"
                + "]");
    }

    private static JsonArray oneKeeper(String ownerID, String playerID){
        return json("[{\"owner_id\":\"" + ownerID + "\",\"keepers\":[\"" + playerID + "\"],\"players\":[\"" + playerID + "\"]}]");
    }

    // ---- declaring, or not ----

    @Test
    void noOneHasDeclaredYet_soThereAreNoKeepers(){
        JsonArray rosters = json("["
                + "{\"owner_id\":\"u1\",\"keepers\":null,\"players\":[\"7564\"]},"
                + "{\"owner_id\":\"u2\",\"keepers\":null,\"players\":[\"9226\"]}"
                + "]");
        Assertions.assertTrue(price(rosters, lastSeason()).isEmpty());
    }

    @Test
    void declaringNobodyIsNotTheSameAsNotDeclaring(){
        JsonArray rosters = json("[{\"owner_id\":\"u1\",\"keepers\":[],\"players\":[\"7564\"]}]");
        Assertions.assertTrue(price(rosters, lastSeason()).isEmpty());
    }

    @Test
    void someRostersDeclaredAndSomeHaveNot(){
        JsonArray rosters = json("["
                + "{\"owner_id\":\"u1\",\"keepers\":[\"7564\"],\"players\":[\"7564\"]},"
                + "{\"owner_id\":\"u2\",\"keepers\":null,\"players\":[\"9226\"]},"
                + "{\"owner_id\":\"u3\",\"keepers\":[\"9226\"],\"players\":[\"9226\"]}"
                + "]");
        ArrayList<Keeper> keepers = price(rosters, lastSeason());
        Assertions.assertEquals(2, keepers.size());
        Assertions.assertEquals("u1", keeperFor(keepers, CHASE).humanWhoCanKeep);
        Assertions.assertEquals("u3", keeperFor(keepers, GIBBS).humanWhoCanKeep);
    }

    @Test
    void theKeeperGoesToTheRosterThatDeclaredThem(){
        JsonArray rosters = json("["
                + "{\"owner_id\":\"u1\",\"keepers\":[\"7564\"],\"players\":[]},"
                + "{\"owner_id\":\"u2\",\"keepers\":null,\"players\":[\"7564\"]}"
                + "]");
        ArrayList<Keeper> keepers = price(rosters, lastSeason());
        Assertions.assertEquals(1, keepers.size());
        Assertions.assertEquals("u1", keepers.get(0).humanWhoCanKeep);
    }

    @Test
    void anAbandonedTeamKeepsNobody(){
        JsonArray rosters = json("[{\"owner_id\":null,\"keepers\":[\"7564\"],\"players\":[\"7564\"]}]");
        Assertions.assertTrue(price(rosters, lastSeason()).isEmpty());
    }

    @Test
    void anUnknownPlayerIsSkippedRatherThanKeptAsNull(){
        Assertions.assertTrue(price(oneKeeper("u1", "999999"), lastSeason()).isEmpty());
    }

    // ---- base cost ----

    @Test
    void aKeeperCostsTheRoundTheyWentInLastSeason(){
        Assertions.assertEquals(3, price(oneKeeper("u1", "7564"), lastSeason()).get(0).roundCanBeKept);
        Assertions.assertEquals(6, price(oneKeeper("u1", "9226"), lastSeason()).get(0).roundCanBeKept);
    }

    @Test
    void aWaiverPickupCostsALastRoundPick(){
        // Josh Allen is not in last season's draft: picked up in-season.
        Assertions.assertEquals(Keeper.UNDRAFTED_ROUND_COST,
                price(oneKeeper("u1", "4984"), lastSeason()).get(0).roundCanBeKept);
    }

    @Test
    void aLateRoundKeeperIsCappedRatherThanCostingRound12(){
        JsonArray previous = json("[{\"player_id\":\"7564\",\"round\":12}]");
        Assertions.assertEquals(Keeper.MAX_ROUND_COST,
                price(oneKeeper("u1", "7564"), previous).get(0).roundCanBeKept);
    }

    @Test
    void aDefenseKeeperDoesNotBlowUpOnItsLetteredId(){
        ArrayList<Keeper> keepers = price(oneKeeper("u1", "CHI"), lastSeason());
        Assertions.assertEquals(1, keepers.size());
        Assertions.assertEquals(BEARS, keepers.get(0).player);
        Assertions.assertEquals(Keeper.UNDRAFTED_ROUND_COST, keepers.get(0).roundCanBeKept);
    }

    @Test
    void withNoPreviousSeasonEveryKeeperCostsALastRoundPick(){
        JsonArray rosters = json("[{\"owner_id\":\"u1\",\"keepers\":[\"7564\"],\"players\":[\"7564\"]}]");
        Assertions.assertEquals(Keeper.UNDRAFTED_ROUND_COST,
                KeeperPricing.priceKeepers(rosters, List.of(), LOOKUP, ADP).get(0).roundCanBeKept);
    }

    // ---- consecutive years ----

    @Test
    void keepingSomeoneASecondYearRunningCostsARoundMore(){
        // Last season's round already is the escalated cost, so it moves one more.
        JsonArray previous = json("[{\"player_id\":\"7564\",\"round\":6,\"is_keeper\":true}]");
        Assertions.assertEquals(5, price(oneKeeper("u1", "7564"), previous).get(0).roundCanBeKept);
    }

    @Test
    void aThirdConsecutiveYearCostsAnotherRound(){
        JsonArray previous = json("[{\"player_id\":\"7564\",\"round\":5,\"is_keeper\":true}]");
        JsonArray twoSeasonsAgo = json("[{\"player_id\":\"7564\",\"round\":6,\"is_keeper\":true}]");
        JsonArray threeSeasonsAgo = json("[{\"player_id\":\"7564\",\"round\":6,\"is_keeper\":null}]");
        Assertions.assertEquals(4,
                price(oneKeeper("u1", "7564"), previous, twoSeasonsAgo, threeSeasonsAgo).get(0).roundCanBeKept);
    }

    @Test
    void aFourthConsecutiveYearIsNotAllowed(){
        JsonArray previous = json("[{\"player_id\":\"7564\",\"round\":4,\"is_keeper\":true}]");
        JsonArray twoAgo = json("[{\"player_id\":\"7564\",\"round\":5,\"is_keeper\":true}]");
        JsonArray threeAgo = json("[{\"player_id\":\"7564\",\"round\":6,\"is_keeper\":true}]");
        JsonArray fourAgo = json("[{\"player_id\":\"7564\",\"round\":6,\"is_keeper\":null}]");

        KeeperPricing.PricedKeepers priced =
                priceDetailed(oneKeeper("u1", "7564"), previous, twoAgo, threeAgo, fourAgo);

        Assertions.assertTrue(priced.keepers.isEmpty());
        Assertions.assertEquals(1, priced.rejected.size());
        Assertions.assertTrue(priced.rejected.get(0).contains("3"), priced.rejected.get(0));
    }

    // ---- eligibility ----

    @Test
    void nobodyTakenInTheFirstTwoRoundsCanBeKept(){
        for(int round = 1; round <= 2; round++){
            JsonArray previous = json("[{\"player_id\":\"7564\",\"round\":" + round + "}]");
            KeeperPricing.PricedKeepers priced = priceDetailed(oneKeeper("u1", "7564"), previous);
            Assertions.assertTrue(priced.keepers.isEmpty(), "round " + round + " should not be keepable");
            Assertions.assertTrue(priced.rejected.get(0).contains("round " + round), priced.rejected.get(0));
        }
    }

    @Test
    void theThirdRoundIsKeepable(){
        JsonArray previous = json("[{\"player_id\":\"7564\",\"round\":3}]");
        Assertions.assertEquals(3, price(oneKeeper("u1", "7564"), previous).get(0).roundCanBeKept);
    }

    @Test
    void theFirstTwoRoundsRuleFollowsTheOriginalDraftNotTheKeeperCost(){
        // Drafted in the 5th, kept twice, now costing a 3rd. Still legal: the
        // rule is about where they were drafted.
        JsonArray previous = json("[{\"player_id\":\"7564\",\"round\":4,\"is_keeper\":true}]");
        JsonArray twoAgo = json("[{\"player_id\":\"7564\",\"round\":5,\"is_keeper\":null}]");
        Assertions.assertEquals(3, price(oneKeeper("u1", "7564"), previous, twoAgo).get(0).roundCanBeKept);
    }

    @Test
    void aCostCannotClimbAboveAFirstRoundPick(){
        JsonArray previous = json("[{\"player_id\":\"7564\",\"round\":1,\"is_keeper\":true}]");
        JsonArray twoAgo = json("[{\"player_id\":\"7564\",\"round\":3,\"is_keeper\":null}]");
        KeeperPricing.PricedKeepers priced = priceDetailed(oneKeeper("u1", "7564"), previous, twoAgo);
        Assertions.assertTrue(priced.keepers.isEmpty());
        Assertions.assertTrue(priced.rejected.get(0).contains("first-round"), priced.rejected.get(0));
    }

    // ---- two keepers landing on the same round ----

    @Test
    void twoKeepersOnTheSameRoundCannotBothPayIt(){
        // Gibbs and Nabers both went in the 6th; one has to move up.
        JsonArray rosters = json("[{\"owner_id\":\"u1\",\"keepers\":[\"9226\",\"11565\"],"
                + "\"players\":[\"9226\",\"11565\"]}]");
        ArrayList<Keeper> keepers = price(rosters, lastSeason());

        Assertions.assertEquals(2, keepers.size());
        Assertions.assertEquals(6, keeperFor(keepers, GIBBS).roundCanBeKept, "earlier ADP keeps its round");
        Assertions.assertEquals(5, keeperFor(keepers, NABERS).roundCanBeKept, "later ADP goes up a round");
    }

    @Test
    void aConsecutiveYearKeeperHoldsItsRoundAndTheOtherMoves(){
        // Nabers is on the 6th only because he was kept last year, so he keeps
        // it even though Gibbs has the earlier ADP.
        JsonArray previous = json("["
                + "{\"player_id\":\"9226\",\"round\":6,\"is_keeper\":null},"
                + "{\"player_id\":\"11565\",\"round\":7,\"is_keeper\":true}"
                + "]");
        JsonArray rosters = json("[{\"owner_id\":\"u1\",\"keepers\":[\"9226\",\"11565\"],"
                + "\"players\":[\"9226\",\"11565\"]}]");

        ArrayList<Keeper> keepers = price(rosters, previous);

        Assertions.assertEquals(6, keeperFor(keepers, NABERS).roundCanBeKept,
                "kept two years running, so it holds the 6th");
        Assertions.assertEquals(5, keeperFor(keepers, GIBBS).roundCanBeKept,
                "the other one moves, despite the better ADP");
    }

    @Test
    void twoKeepersOnDifferentRoundsAreLeftAlone(){
        JsonArray rosters = json("[{\"owner_id\":\"u1\",\"keepers\":[\"7564\",\"9226\"],"
                + "\"players\":[\"7564\",\"9226\"]}]");
        ArrayList<Keeper> keepers = price(rosters, lastSeason());
        Assertions.assertEquals(3, keeperFor(keepers, CHASE).roundCanBeKept);
        Assertions.assertEquals(6, keeperFor(keepers, GIBBS).roundCanBeKept);
    }

    @Test
    void aClashOnOneRosterDoesNotDisturbAnother(){
        JsonArray rosters = json("["
                + "{\"owner_id\":\"u1\",\"keepers\":[\"9226\",\"11565\"],\"players\":[\"9226\",\"11565\"]},"
                + "{\"owner_id\":\"u2\",\"keepers\":[\"7564\"],\"players\":[\"7564\"]}"
                + "]");
        ArrayList<Keeper> keepers = price(rosters, lastSeason());
        Assertions.assertEquals(3, keeperFor(keepers, CHASE).roundCanBeKept);
    }

    // ---- pick parsing ----

    @Test
    void aPickWithNoPlayerIsIgnoredWhenReadingRounds(){
        JsonArray previous = json("["
                + "{\"player_id\":null,\"round\":1},"
                + "{\"player_id\":\"7564\",\"round\":4}"
                + "]");
        Map<String, Integer> rounds = KeeperPricing.roundsByPlayerID(previous);
        Assertions.assertEquals(1, rounds.size());
        Assertions.assertEquals(4, rounds.get("7564"));
    }

    private static Keeper keeperFor(ArrayList<Keeper> keepers, Player player){
        for(Keeper keeper : keepers){
            if(keeper.player == player){
                return keeper;
            }
        }
        throw new AssertionError("no keeper for " + player.firstName + " " + player.lastName);
    }
}
