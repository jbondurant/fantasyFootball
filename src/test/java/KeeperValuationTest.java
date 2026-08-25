import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The valuation rule: a keeper fills one of the nine skill slots, which frees
 * the pick that would have filled it. He is worth a slot only if he beats what
 * that pick returns.
 */
class KeeperValuationTest {

    @Test
    void theNineSlotsAreTheSkillSlots(){
        Assertions.assertEquals(9, StartingLineup.SKILL_SLOTS);
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR, Position.TE}){
            Assertions.assertTrue(StartingLineup.isSkillPosition(position), position + " starts");
        }
    }

    @Test
    void aDefenseIsNotOneOfTheNine(){
        // It cannot fill any slot being optimised, so it can never be worth a
        // keeper slot here however good the defense is.
        Assertions.assertFalse(StartingLineup.isSkillPosition(Position.DEF));
    }

    @Test
    void aKeeperCostsTheRoundNinePickHoweverLateHeNominallyCosts(){
        // Nine skill slots means nine picks fill them. Keeping frees the last
        // of those, not the round the keeper nominally costs.
        Assertions.assertEquals(9, StartingLineup.lastStarterRound());
    }

    @Test
    void replacementIsTheLastStarterAtThatPosition(){
        // Twelve teams, two flex: 1 QB each, 2 RB each plus half the flex slots.
        Assertions.assertEquals(12, StartingLineup.startedLeagueWide(Position.QB, 12, 2));
        Assertions.assertEquals(36, StartingLineup.startedLeagueWide(Position.RB, 12, 2));
        Assertions.assertEquals(46, StartingLineup.startedLeagueWide(Position.WR, 12, 2));
        Assertions.assertEquals(14, StartingLineup.startedLeagueWide(Position.TE, 12, 2));
    }

    @Test
    void aLeagueWithoutFlexSlotsStartsFewerOfEveryone(){
        Assertions.assertEquals(24, StartingLineup.startedLeagueWide(Position.RB, 12, 0));
        Assertions.assertEquals(36, StartingLineup.startedLeagueWide(Position.WR, 12, 0));
    }

    @Test
    void aBiggerLeagueRaisesReplacementLevel(){
        Assertions.assertTrue(StartingLineup.startedLeagueWide(Position.RB, 14, 2)
                > StartingLineup.startedLeagueWide(Position.RB, 12, 2));
    }
}
