import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exercises the live-draft path against a board that actually has picks on it.
 *
 * The real draft is empty until draft day, so the code that reads picks, works
 * out which round we are in and strips drafted players out of the available
 * pool cannot be checked against it. Sleeper mock drafts fill that gap: start
 * one, let it run a few rounds, then point this at it.
 *
 *   ./gradlew smokeTest -PdraftId=<mock draft id>
 *
 * The id is the last path segment of the mock draft's URL. Without it these
 * skip.
 */
@Tag("smoke")
class MockDraftSmokeTest {

    private static String draftID(){
        return System.getProperty("draftId");
    }

    private static void requireDraft(){
        Assumptions.assumeTrue(draftID() != null && !draftID().isBlank(),
                "no draft id given - rerun with ./gradlew smokeTest -PdraftId=<sleeper mock draft id>");
    }

    private static JsonObject draftMeta(){
        return JsonParser.parseString(
                WebUrlUtility.urlToString(AAAConfiguration.draftWebURL(draftID()))).getAsJsonObject();
    }

    private static JsonArray picks(){
        return JsonParser.parseString(
                WebUrlUtility.urlToString(AAAConfiguration.draftPicksWebURL(draftID()))).getAsJsonArray();
    }

    @Test
    void theDraftExistsAndSaysHowBigItIs(){
        requireDraft();
        JsonObject meta = draftMeta();
        JsonObject settings = meta.getAsJsonObject("settings");

        int teams = settings.get("teams").getAsInt();
        int rounds = settings.get("rounds").getAsInt();
        System.out.println("draft " + draftID() + ": " + teams + " teams, " + rounds
                + " rounds, status " + meta.get("status").getAsString());

        Assertions.assertTrue(teams > 0);
        Assertions.assertTrue(rounds > 0);
    }

    @Test
    void everyPickResolvesToAPlayer(){
        requireDraft();
        JsonArray picks = picks();
        Assumptions.assumeTrue(picks.size() > 0, "no picks made yet in this draft");

        List<String> unresolved = new ArrayList<>();
        for(JsonElement pickElement : picks){
            String playerID = pickElement.getAsJsonObject().get("player_id").getAsString();
            if(Player.getPlayerFromSIDV2(playerID) == null){
                unresolved.add(playerID);
            }
        }

        Assertions.assertTrue(unresolved.isEmpty(),
                "picks that resolved to no player, so they would not be removed from the board: " + unresolved);
    }

    @Test
    void ourRoundMathAgreesWithSleepers(){
        requireDraft();
        JsonArray picks = picks();
        Assumptions.assumeTrue(picks.size() > 0, "no picks made yet in this draft");

        int teams = draftMeta().getAsJsonObject("settings").get("teams").getAsInt();

        // Sleeper stamps each pick with its round; ours is derived from the
        // count. They have to agree, or the simulator plans the wrong rounds.
        //
        // Keepers are NOT sequential progress. This league pre-places them at
        // their cost rounds - the 2026-08-29 mock held twelve of them at rounds
        // 10 to 14, pick numbers up to 159, while the draft itself had only
        // reached round 8. Counting those made the raw pick total say round 9
        // and the highest stamped round say 14, and the test failed on a draft
        // that was behaving correctly. LiveDraft.livePicks filters them for the
        // same reason; so must this.
        java.util.Set<Integer> filled = new java.util.HashSet<>();
        int lastSelectionRound = 0;
        int selections = 0;
        for(JsonElement pickElement : picks){
            JsonObject pick = pickElement.getAsJsonObject();
            filled.add(pick.get("pick_no").getAsInt());
            JsonElement keeper = pick.get("is_keeper");
            if(keeper == null || keeper.isJsonNull() || !keeper.getAsBoolean()){
                selections++;
                lastSelectionRound = Math.max(lastSelectionRound,
                        pick.get("round").getAsInt());
            }
        }
        Assumptions.assumeTrue(selections > 0, "only keepers are in so far");

        int ourRound = DraftProgress.currentRoundOfKeeperDraft(filled, teams);
        int expected = lastSelectionRound;

        System.out.println(selections + " selections plus " + (picks.size() - selections)
                + " keepers; sleeper's last selection is round " + lastSelectionRound
                + ", we are on round " + ourRound);
        Assertions.assertEquals(expected, ourRound);
    }

    @Test
    void everyPickIsStampedWithTheRoundItsPickNumberImplies(){
        requireDraft();
        JsonArray picks = picks();
        Assumptions.assumeTrue(picks.size() > 0, "no picks made yet in this draft");

        int teams = draftMeta().getAsJsonObject("settings").get("teams").getAsInt();
        for(JsonElement pickElement : picks){
            JsonObject pick = pickElement.getAsJsonObject();
            int pickNo = pick.get("pick_no").getAsInt();
            int round = pick.get("round").getAsInt();
            Assertions.assertEquals(DraftProgress.currentRound(pickNo - 1, teams), round,
                    "pick " + pickNo + " is stamped round " + round);
        }
    }

    @Test
    void draftedPlayersLeaveTheAvailablePool(){
        requireDraft();
        JsonArray picks = picks();
        Assumptions.assumeTrue(picks.size() > 0, "no picks made yet in this draft");

        LiveDraftInfo liveDraft = SleeperLiveDraft.getDraftedPlayersMock(draftID(), false);
        Assertions.assertEquals(picks.size(), liveDraft.draftedPlayers.size(),
                "every pick should have come through as a drafted player");

        Set<String> drafted = new HashSet<>();
        for(Player player : liveDraft.draftedPlayers){
            drafted.add(player.sleeperIDString);
        }

        BestAvailablePlayers available = liveDraft.bestAvailablePlayers;
        for(Player bestAtPosition : bestOfEach(available)){
            if(bestAtPosition == null){
                continue;
            }
            Assertions.assertFalse(drafted.contains(bestAtPosition.sleeperIDString),
                    bestAtPosition.firstName + " " + bestAtPosition.lastName
                            + " is already drafted but is still being offered as best available");
        }
    }

    @Test
    void theBoardStillHasSomebodyToRecommend(){
        requireDraft();
        LiveDraftInfo liveDraft = SleeperLiveDraft.getDraftedPlayersMock(draftID(), false);

        for(Player bestAtPosition : bestOfEach(liveDraft.bestAvailablePlayers)){
            Assertions.assertNotNull(bestAtPosition,
                    "a position ran out of available players, which would break the draft simulator");
        }
        LiveDraftInfo.LiveDraftPotentialMoveAnalyzer(liveDraft.bestAvailablePlayers);
    }

    @Test
    void myPicksAreAttributedToMe(){
        requireDraft();
        JsonArray picks = picks();
        Assumptions.assumeTrue(picks.size() > 0, "no picks made yet in this draft");

        String myID = HumanOfInterest.humanID();
        int minePerSleeper = 0;
        for(JsonElement pickElement : picks){
            JsonElement pickedBy = pickElement.getAsJsonObject().get("picked_by");
            if(pickedBy != null && !pickedBy.isJsonNull() && pickedBy.getAsString().equals(myID)){
                minePerSleeper++;
            }
        }

        LiveDraftInfo liveDraft = SleeperLiveDraft.getDraftedPlayersMock(draftID(), false);
        System.out.println("picks attributed to me: " + liveDraft.rosterPlayers.size()
                + " (sleeper says " + minePerSleeper + ")");
        Assertions.assertEquals(minePerSleeper, liveDraft.rosterPlayers.size());
    }

    private static List<Player> bestOfEach(BestAvailablePlayers available){
        List<Player> best = new ArrayList<>();
        best.add(available.quarterbackRT1 == null ? null : available.quarterbackRT1.player);
        best.add(available.runningBackRT1 == null ? null : available.runningBackRT1.player);
        best.add(available.wideReceiverRT1 == null ? null : available.wideReceiverRT1.player);
        best.add(available.tightEndRT1 == null ? null : available.tightEndRT1.player);
        best.add(available.defenseRT1 == null ? null : available.defenseRT1.player);
        return best;
    }
}
