import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Which ids are defences is a static fact the id already carries, and the
 * weekly path must not ask a season feed that does not exist until games are
 * played. "Not a number" is not the test: a week's stats also carry TEAM_SEA
 * rows, 28 of them in 2024 week 5, and those are team lines.
 */
public class DefenceIdTest {

    @Test
    public void aDefenceIsABareTeamAbbreviation(){
        assertTrue(LeagueActuals.isDefence("BAL"));
        assertTrue(LeagueActuals.isDefence("SEA"));
        assertTrue(LeagueActuals.isDefence("NE"), "two letters counts");
        assertFalse(LeagueActuals.isDefence("4034"), "a skill player is a number");
        assertFalse(LeagueActuals.isDefence("TEAM_SEA"), "a team line is not a defence");
        assertFalse(LeagueActuals.isDefence("TEAM_BAL"));
        assertFalse(LeagueActuals.isDefence(null));
        assertFalse(LeagueActuals.isDefence(""));
    }
}
