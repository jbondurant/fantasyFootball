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
    void flexGoesToTheBestRemainingPlayersNotAnAssumedSplit(){
        // 12 teams, 2 flex. RBs 25-48 all outscore WRs 37+, so flex should go
        // entirely to RBs and RB replacement should be the 48th RB.
        java.util.Map<Position, java.util.List<Double>> pool = new java.util.EnumMap<>(Position.class);
        pool.put(Position.QB, descending(400, 12 + 5, 5));
        pool.put(Position.RB, descending(300, 60, 2));      // 300, 298, ... deep and strong
        pool.put(Position.WR, descending(200, 60, 2));      // weaker at the flex margin
        pool.put(Position.TE, descending(150, 20, 2));

        ReplacementLevel level = ReplacementLevel.greedy(pool, 12, 2);

        Assertions.assertEquals(24 + 24, level.rankOf(Position.RB), "both flex slots per team go RB");
        Assertions.assertEquals(36, level.rankOf(Position.WR), "WR keeps only its fixed slots");
        Assertions.assertEquals(12, level.rankOf(Position.TE));
        Assertions.assertEquals(300 - 47 * 2, level.of(Position.RB), 0.0001);
        Assertions.assertEquals(200 - 35 * 2, level.of(Position.WR), 0.0001);
    }

    @Test
    void aMixedPoolSplitsFlexWhereverThePointsAre(){
        java.util.Map<Position, java.util.List<Double>> pool = new java.util.EnumMap<>(Position.class);
        pool.put(Position.QB, descending(400, 15, 5));
        pool.put(Position.RB, descending(250, 40, 3));
        pool.put(Position.WR, descending(250, 50, 3));
        pool.put(Position.TE, descending(240, 20, 3));

        ReplacementLevel level = ReplacementLevel.greedy(pool, 12, 2);

        int flexWon = (level.rankOf(Position.RB) - 24)
                + (level.rankOf(Position.WR) - 36)
                + (level.rankOf(Position.TE) - 12);
        Assertions.assertEquals(24, flexWon, "exactly the league's 24 flex slots get filled");
        Assertions.assertTrue(level.rankOf(Position.TE) > 12, "a strong TE pool wins some flex");
    }

    @Test
    void aThinPositionDoesNotOverflowThePool(){
        java.util.Map<Position, java.util.List<Double>> pool = new java.util.EnumMap<>(Position.class);
        pool.put(Position.QB, descending(300, 8, 5));       // fewer QBs than teams
        pool.put(Position.RB, descending(250, 60, 2));
        pool.put(Position.WR, descending(240, 60, 2));
        pool.put(Position.TE, descending(150, 20, 2));

        ReplacementLevel level = ReplacementLevel.greedy(pool, 12, 2);

        Assertions.assertEquals(8, level.rankOf(Position.QB));
        Assertions.assertTrue(level.of(Position.QB) > 0);
    }

    private static java.util.List<Double> descending(double top, int howMany, double step){
        java.util.List<Double> points = new java.util.ArrayList<>();
        for(int i = 0; i < howMany; i++){
            points.add(top - i * step);
        }
        return points;
    }
}
