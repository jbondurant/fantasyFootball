import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pins the live fit. The 2021-2025 drafts are finished and the historical
 * projections are cached forever, so these values should never move; if they
 * do, either the fit changed or Sleeper rewrote history, and both are worth
 * knowing about.
 */
@Tag("smoke")
class ManagerProfilesSmokeTest {

    @Test
    void theFittedLeagueBiasMatchesTheKnownDraftHistory(){
        ManagerProfiles profiles =
                ManagerProfiles.fitThroughSeason(AAAConfiguration.getInstance(), 2025);

        // Direction is the finding: this league lets QBs and TEs fall and
        // bids WRs up. Tolerances are wide enough for parser-level drift only.
        Assertions.assertEquals(20.0, profiles.leagueBias(Position.QB), 4.0);
        Assertions.assertEquals(0.0, profiles.leagueBias(Position.RB), 4.0);
        Assertions.assertEquals(-10.5, profiles.leagueBias(Position.WR), 4.0);
        Assertions.assertEquals(17.0, profiles.leagueBias(Position.TE), 4.0);
    }

    @Test
    void fittingThroughAnEarlierSeasonUsesLessHistory(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        ManagerProfiles through2023 = ManagerProfiles.fitThroughSeason(configuration, 2023);
        ManagerProfiles through2025 = ManagerProfiles.fitThroughSeason(configuration, 2025);

        Assertions.assertTrue(through2023.seasonsFitted().size() < through2025.seasonsFitted().size());
        Assertions.assertFalse(through2023.seasonsFitted().contains("2024"),
                "a 2023 cutoff must not see 2024 - the backtest depends on this");
        Assertions.assertFalse(through2023.seasonsFitted().contains("2025"));
    }
}
