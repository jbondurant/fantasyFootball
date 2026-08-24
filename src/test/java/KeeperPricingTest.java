import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeper declarations arrive over the weeks before the draft, so the pricing
 * has to hold up at every stage: nobody has declared, some have, rosters with
 * one keeper and rosters with two.
 */
class KeeperPricingTest {

    private static final Player CHASE = TestPlayers.player("Ja'Marr", "Chase", "CIN", Position.WR, 7564);
    private static final Player GIBBS = TestPlayers.player("Jahmyr", "Gibbs", "DET", Position.RB, 9226);
    private static final Player ALLEN = TestPlayers.player("Josh", "Allen", "BUF", Position.QB, 4984);
    private static final Player BEARS = TestPlayers.defense("Chicago", "Bears", "CHI");

    private static final KeeperPricing.PlayerLookup LOOKUP = buildLookup();

    private static KeeperPricing.PlayerLookup buildLookup(){
        Map<String, Player> byID = new HashMap<>();
        byID.put("7564", CHASE);
        byID.put("9226", GIBBS);
        byID.put("4984", ALLEN);
        byID.put("CHI", BEARS);
        return byID::get;
    }

    private static JsonArray json(String text){
        return JsonParser.parseString(text).getAsJsonArray();
    }

    private static JsonArray noPreviousDraft(){
        return json("[]");
    }

    /** Chase in round 1, Gibbs in round 6, nobody else. */
    private static JsonArray previousDraft(){
        return json("["
                + "{\"player_id\":\"7564\",\"round\":1,\"pick_no\":3},"
                + "{\"player_id\":\"9226\",\"round\":6,\"pick_no\":68},"
                + "{\"player_id\":\"1111\",\"round\":12,\"pick_no\":140}"
                + "]");
    }

    @Test
    void noOneHasDeclaredYet_soThereAreNoKeepers(){
        JsonArray rosters = json("["
                + "{\"owner_id\":\"u1\",\"keepers\":null,\"players\":[\"7564\"]},"
                + "{\"owner_id\":\"u2\",\"keepers\":null,\"players\":[\"9226\"]}"
                + "]");

        ArrayList<Keeper> keepers = KeeperPricing.priceKeepers(rosters, previousDraft(), LOOKUP);

        Assertions.assertTrue(keepers.isEmpty(), "no declarations means no keepers");
    }

    @Test
    void declaringNobodyIsNotTheSameAsNotDeclaring(){
        // Sleeper sends [] once a manager has actively kept nobody.
        JsonArray rosters = json("[{\"owner_id\":\"u1\",\"keepers\":[],\"players\":[\"7564\"]}]");

        ArrayList<Keeper> keepers = KeeperPricing.priceKeepers(rosters, previousDraft(), LOOKUP);

        Assertions.assertTrue(keepers.isEmpty());
    }

    @Test
    void someRostersDeclaredAndSomeHaveNot(){
        JsonArray rosters = json("["
                + "{\"owner_id\":\"u1\",\"keepers\":[\"7564\"],\"players\":[\"7564\"]},"
                + "{\"owner_id\":\"u2\",\"keepers\":null,\"players\":[\"9226\"]},"
                + "{\"owner_id\":\"u3\",\"keepers\":[\"4984\"],\"players\":[\"4984\"]}"
                + "]");

        ArrayList<Keeper> keepers = KeeperPricing.priceKeepers(rosters, previousDraft(), LOOKUP);

        Assertions.assertEquals(2, keepers.size());
        Assertions.assertEquals("u1", keeperFor(keepers, CHASE).humanWhoCanKeep);
        Assertions.assertEquals("u3", keeperFor(keepers, ALLEN).humanWhoCanKeep);
    }

    @Test
    void aKeeperCostsTheRoundTheyWentInLastSeason(){
        JsonArray rosters = json("[{\"owner_id\":\"u1\",\"keepers\":[\"7564\",\"9226\"],\"players\":[\"7564\",\"9226\"]}]");

        ArrayList<Keeper> keepers = KeeperPricing.priceKeepers(rosters, previousDraft(), LOOKUP);

        Assertions.assertEquals(1, keeperFor(keepers, CHASE).roundCanBeKept);
        Assertions.assertEquals(6, keeperFor(keepers, GIBBS).roundCanBeKept);
    }

    @Test
    void aWaiverPickupCostsALastRoundPick(){
        // Josh Allen is not in last season's draft: picked up in-season.
        JsonArray rosters = json("[{\"owner_id\":\"u1\",\"keepers\":[\"4984\"],\"players\":[\"4984\"]}]");

        ArrayList<Keeper> keepers = KeeperPricing.priceKeepers(rosters, previousDraft(), LOOKUP);

        Assertions.assertEquals(Keeper.UNDRAFTED_ROUND_COST, keeperFor(keepers, ALLEN).roundCanBeKept);
    }

    @Test
    void aLateRoundKeeperIsCappedRatherThanCostingRound12(){
        JsonArray previous = json("[{\"player_id\":\"7564\",\"round\":12,\"pick_no\":140}]");
        JsonArray rosters = json("[{\"owner_id\":\"u1\",\"keepers\":[\"7564\"],\"players\":[\"7564\"]}]");

        ArrayList<Keeper> keepers = KeeperPricing.priceKeepers(rosters, previous, LOOKUP);

        Assertions.assertEquals(Keeper.MAX_ROUND_COST, keeperFor(keepers, CHASE).roundCanBeKept);
    }

    @Test
    void aDefenseKeeperDoesNotBlowUpOnItsLetteredId(){
        // Regression: keepers used to be read with getAsInt, and a defense's
        // player id is its team abbreviation.
        JsonArray rosters = json("[{\"owner_id\":\"u1\",\"keepers\":[\"CHI\"],\"players\":[\"CHI\"]}]");

        ArrayList<Keeper> keepers = KeeperPricing.priceKeepers(rosters, previousDraft(), LOOKUP);

        Assertions.assertEquals(1, keepers.size());
        Assertions.assertEquals(BEARS, keepers.get(0).player);
        Assertions.assertEquals(Keeper.UNDRAFTED_ROUND_COST, keepers.get(0).roundCanBeKept);
    }

    @Test
    void theKeeperGoesToTheRosterThatDeclaredThem(){
        // Even if they have since been dropped from that roster's player list.
        JsonArray rosters = json("["
                + "{\"owner_id\":\"u1\",\"keepers\":[\"7564\"],\"players\":[]},"
                + "{\"owner_id\":\"u2\",\"keepers\":null,\"players\":[\"7564\"]}"
                + "]");

        ArrayList<Keeper> keepers = KeeperPricing.priceKeepers(rosters, previousDraft(), LOOKUP);

        Assertions.assertEquals(1, keepers.size());
        Assertions.assertEquals("u1", keepers.get(0).humanWhoCanKeep);
    }

    @Test
    void anAbandonedTeamKeepsNobody(){
        JsonArray rosters = json("[{\"owner_id\":null,\"keepers\":[\"7564\"],\"players\":[\"7564\"]}]");

        ArrayList<Keeper> keepers = KeeperPricing.priceKeepers(rosters, previousDraft(), LOOKUP);

        Assertions.assertTrue(keepers.isEmpty(), "a keeper with no owner has nobody to belong to");
    }

    @Test
    void anUnknownPlayerIsSkippedRatherThanKeptAsNull(){
        JsonArray rosters = json("[{\"owner_id\":\"u1\",\"keepers\":[\"999999\"],\"players\":[\"999999\"]}]");

        ArrayList<Keeper> keepers = KeeperPricing.priceKeepers(rosters, previousDraft(), LOOKUP);

        Assertions.assertTrue(keepers.isEmpty());
    }

    @Test
    void withNoPreviousSeasonEveryKeeperCostsALastRoundPick(){
        JsonArray rosters = json("[{\"owner_id\":\"u1\",\"keepers\":[\"7564\",\"9226\"],\"players\":[\"7564\",\"9226\"]}]");

        ArrayList<Keeper> keepers = KeeperPricing.priceKeepers(rosters, noPreviousDraft(), LOOKUP);

        Assertions.assertEquals(2, keepers.size());
        for(Keeper keeper : keepers){
            Assertions.assertEquals(Keeper.UNDRAFTED_ROUND_COST, keeper.roundCanBeKept);
        }
    }

    @Test
    void aPickWithNoPlayerIsIgnoredWhenReadingRounds(){
        // Sleeper writes a null player_id for a pick that was never made.
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
