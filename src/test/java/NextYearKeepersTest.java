import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * The keeper price for next season, off this season's board.
 *
 * The panel this replaces priced 2027 keepers against the 2025 draft, so a man
 * drafted in 2026 had no round at all and silently took the undrafted default.
 * Justin found it by asking how a rookie he drafted this year and who has not
 * played could have a keeper cost.
 */
public class NextYearKeepersTest {

    static String pick(String id, String first, String last, int round, boolean kept){
        return "{\"player_id\":\"" + id + "\",\"round\":" + round
                + ",\"is_keeper\":" + kept
                + ",\"metadata\":{\"first_name\":\"" + first + "\",\"last_name\":\"" + last + "\"}}";
    }

    @Test
    void aManDraftedThisYearCostsTheRoundHeWentIn(){
        Map<String, NextYearKeepers.Cost> costs = NextYearKeepers.from(
                "[" + pick("1", "Cam", "Skattebo", 3, false) + "]");
        NextYearKeepers.Cost skattebo = costs.get("1");
        assertTrue(skattebo.keepable());
        assertEquals(3, skattebo.round(),
                "he went in the third round, so keeping him costs a third-round pick -"
                        + " not the tenth the old panel gave a man it had never seen");
    }

    /** A man kept this year costs one round MORE next year, which is one round earlier. */
    @Test
    void aRepeatKeeperEscalatesByExactlyOneRound(){
        Map<String, NextYearKeepers.Cost> costs = NextYearKeepers.from(
                "[" + pick("2", "Bhayshul", "Tuten", 12, true) + ","
                    + pick("3", "Brock", "Purdy", 13, true) + "]");
        assertEquals(11, costs.get("2").round(), "kept at r12 this year, so r11 next");
        assertEquals(12, costs.get("3").round(), "kept at r13 this year, so r12 next");
    }

    /** The first two rounds are unkeepable at any price, and must say so. */
    @Test
    void theFirstTwoRoundsCannotBeKept(){
        Map<String, NextYearKeepers.Cost> costs = NextYearKeepers.from(
                "[" + pick("4", "Derrick", "Henry", 1, false) + ","
                    + pick("5", "Malik", "Nabers", 2, false) + ","
                    + pick("6", "Cam", "Skattebo", 3, false) + "]");
        assertFalse(costs.get("4").keepable(), "a first-rounder is never a keeper");
        assertFalse(costs.get("5").keepable(), "nor a second-rounder");
        assertTrue(costs.get("6").keepable(), "the third round is where it starts");
        assertTrue(costs.get("4").refusal().contains("first"),
                "and the refusal must say why rather than showing a blank: "
                        + costs.get("4").refusal());
    }

    /** Escalation cannot push a cost above a first-round pick. */
    @Test
    void nothingCostsBetterThanTheFirstPick(){
        Map<String, NextYearKeepers.Cost> costs = NextYearKeepers.from(
                "[" + pick("7", "Someone", "Kept", 3, true) + "]");
        assertEquals(2, costs.get("7").round(),
                "r3 kept once escalates to r2 - still a legal price, however unwise");
        Map<String, NextYearKeepers.Cost> edge = NextYearKeepers.from(
                "[" + pick("8", "Edge", "Case", 3, true) + "]");
        assertTrue(edge.get("8").round() >= 1, "a round below one is not a pick");
    }

    /** A man picked up off waivers was in no draft, and the ruleset prices him at a tenth. */
    @Test
    void anUndraftedManCostsATenth(){
        NextYearKeepers.Cost cost = NextYearKeepers.undrafted("9", "Wire Pickup");
        assertTrue(cost.keepable());
        assertEquals(Keeper.UNDRAFTED_ROUND_COST, cost.round());
    }
}
