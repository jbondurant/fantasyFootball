import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * The audit is the thing that would have caught Joe Burrow being entered at a
 * 13th in 2023 when the rules called for a 12th - while the board could still
 * be fixed. These check it works, and report where this season stands.
 */
@Tag("smoke")
class KeeperAuditSmokeTest {

    @Test
    void theAuditRunsAgainstThisSeasonsBoard(){
        KeeperAudit.Report report = KeeperAudit.audit(AAAConfiguration.getInstance());

        if(report.boardIsEmpty()){
            System.out.println("keepers are not on the 2026 board yet; "
                    + report.notOnTheBoard.size() + " declared and waiting");
        }
        else {
            System.out.println("2026 board: " + report.agreed + " match the rules, "
                    + report.disagreements.size() + " do not");
            for(KeeperAudit.Finding finding : report.disagreements){
                System.out.println("   " + finding);
            }
        }
        // Nothing is asserted about the count: the commissioner may not have set
        // the board yet, and a disagreement is a thing to look at rather than a
        // test failure.
        Assertions.assertNotNull(report.disagreements);
    }

    @Test
    void theAuditCanReadKeeperRoundsOffACompletedBoard(){
        // 2025 is done, so its keepers are all placed. If this comes back empty
        // the audit would silently pass on a board it could not read.
        String picks = InOutUtilities.getTodaysWebPage(
                AAAConfiguration.draftPicksWebURL("1249220546336411648"), "auditCheck2025");

        Map<String, Integer> entered = KeeperAudit.enteredKeeperRounds(picks);

        Assertions.assertEquals(24, entered.size(), "2025 had 24 keepers on the board");
        for(Map.Entry<String, Integer> keeper : entered.entrySet()){
            Assertions.assertTrue(keeper.getValue() >= 1 && keeper.getValue() <= 16,
                    keeper.getKey() + " placed in round " + keeper.getValue());
        }
    }
}
