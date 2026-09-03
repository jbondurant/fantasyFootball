import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The fitting math, on synthetic rows where the right answer is known.
 */
class ManagerProfilesTest {

    private static ManagerProfiles.PickRow row(String season, String user, Position pos,
                                               int pick, double adp){
        return new ManagerProfiles.PickRow(season, user, pos, pick, adp);
    }

    @Test
    void seasonCenteringRemovesTheKeeperDepthEffect(){
        // Everyone in season B drafts 10 picks 'early' because keepers thinned
        // the board. That is not bias, and centering removes it: both seasons
        // say WRs go exactly at ADP.
        List<ManagerProfiles.PickRow> rows = new ArrayList<>();
        for(int i = 1; i <= 6; i++){
            rows.add(row("A", "u" + i, Position.WR, 10 * i, 10 * i));
            rows.add(row("B", "u" + i, Position.WR, 10 * i, 10 * i + 10));
        }
        ManagerProfiles profiles = ManagerProfiles.fitFromRows(rows, 12.0);

        Assertions.assertEquals(0.0, profiles.leagueBias(Position.WR), 0.0001);
    }

    @Test
    void leagueBiasIsThePositionsMeanCenteredResidual(){
        // QBs consistently go 20 later than ADP, WRs 20 earlier, same count -
        // season mean is 0 and the biases split cleanly.
        List<ManagerProfiles.PickRow> rows = new ArrayList<>();
        for(int i = 1; i <= 8; i++){
            rows.add(row("A", "u" + (i % 4), Position.QB, 50 + 20, 50));
            rows.add(row("A", "u" + (i % 4), Position.WR, 50 - 20, 50));
        }
        ManagerProfiles profiles = ManagerProfiles.fitFromRows(rows, 12.0);

        Assertions.assertEquals(+20.0, profiles.leagueBias(Position.QB), 0.0001);
        Assertions.assertEquals(-20.0, profiles.leagueBias(Position.WR), 0.0001);
    }

    @Test
    void aManagerOffsetIsShrunkTowardTheLeague(){
        // One manager takes RBs 10 later than everyone else, on n=4 of the 16
        // picks. Season centering absorbs the overall mean (+2.5), so league
        // bias is 0, his centered residual is +7.5, and shrinkage n/(n+12)
        // brings the offset to 7.5 * 4/16.
        List<ManagerProfiles.PickRow> rows = new ArrayList<>();
        for(int i = 0; i < 12; i++){
            rows.add(row("A", "crowd" + (i % 3), Position.RB, 60, 60));
        }
        for(int i = 0; i < 4; i++){
            rows.add(row("A", "patient", Position.RB, 70, 60));
        }
        ManagerProfiles profiles = ManagerProfiles.fitFromRows(rows, 12.0);

        Assertions.assertEquals(0.0, profiles.leagueBias(Position.RB), 0.0001,
                "one position only: centering absorbs the whole mean");
        Assertions.assertEquals(7.5 * 4 / 16.0,
                profiles.managerOffset("patient", Position.RB), 0.0001);
        Assertions.assertEquals(-2.5 * 4 / 16.0,
                profiles.managerOffset("crowd0", Position.RB), 0.0001,
                "the crowd sits below the mean his reaching pulled up");
    }

    @Test
    void anUnknownManagerGetsTheLeagueBiasAndNothingElse(){
        List<ManagerProfiles.PickRow> rows = new ArrayList<>();
        for(int i = 1; i <= 5; i++){
            rows.add(row("A", "u1", Position.TE, 90 + 12, 90));
        }
        ManagerProfiles profiles = ManagerProfiles.fitFromRows(rows, 12.0);

        Assertions.assertEquals(0.0, profiles.managerOffset("stranger", Position.TE), 0.0001);
        Assertions.assertEquals(profiles.leagueBias(Position.TE),
                profiles.adjustmentFor("stranger", Position.TE), 0.0001);
    }

    @Test
    void positionsNeverSeenCarryNoBias(){
        ManagerProfiles profiles = ManagerProfiles.fitFromRows(
                List.of(row("A", "u1", Position.WR, 30, 30)), 12.0);
        Assertions.assertEquals(0.0, profiles.leagueBias(Position.QB), 0.0001);
    }
}
