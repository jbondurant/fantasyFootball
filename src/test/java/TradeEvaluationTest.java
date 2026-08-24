import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

/**
 * A trade is judged by what it does to each side's best lineup. Everything the
 * trade finder ranks comes out of these two numbers, and getting the sign or
 * the roster copying wrong produces confident, plausible-looking nonsense.
 */
class TradeEvaluationTest {

    private static Score score(double points, String lastName, Position position, int id){
        return new Score(points, TestPlayers.player("Test", lastName, "BUF", position, id));
    }

    private static ArrayList<Score> scores(Score... values){
        ArrayList<Score> list = new ArrayList<>();
        for(Score value : values){
            list.add(value);
        }
        return list;
    }

    /** A roster with a startable player at every slot, so swaps show up in the lineup. */
    private static ArrayList<Score> baseRoster(int idOffset, double quality){
        return scores(
                score(quality, "Qb", Position.QB, idOffset + 1),
                score(quality, "Rb1", Position.RB, idOffset + 2),
                score(quality, "Rb2", Position.RB, idOffset + 3),
                score(quality, "Wr1", Position.WR, idOffset + 4),
                score(quality, "Wr2", Position.WR, idOffset + 5),
                score(quality, "Wr3", Position.WR, idOffset + 6),
                score(quality, "Te", Position.TE, idOffset + 7),
                score(quality, "Flex1", Position.RB, idOffset + 8),
                score(quality, "Flex2", Position.WR, idOffset + 9),
                score(quality, "Def", Position.DEF, idOffset + 10));
    }

    @Test
    void givingUpAStarterForABetterOneHelpsAndTheOtherSideItHurts(){
        ArrayList<Score> mine = baseRoster(0, 100);
        Score myWeakWr = score(100, "Wr1", Position.WR, 4);
        ArrayList<Score> theirs = baseRoster(100, 100);
        Score theirStrongWr = score(200, "Star", Position.WR, 999);
        theirs.add(theirStrongWr);

        ScoredRoster me = new ScoredRoster("me", mine);
        ScoredRoster them = new ScoredRoster("them", theirs);

        TradePreviewSerious trade = new TradePreviewSerious(me, them, findScore(mine, 4), theirStrongWr);

        Assertions.assertTrue(trade.improvementT1 > 0, "getting a better starter has to help me");
        Assertions.assertTrue(trade.improvementT2 < 0, "giving up their best receiver has to hurt them");
    }

    @Test
    void aSwapOfEqualsMovesNeither(){
        ArrayList<Score> mine = baseRoster(0, 100);
        ArrayList<Score> theirs = baseRoster(100, 100);

        TradePreviewSerious trade = new TradePreviewSerious(
                new ScoredRoster("me", mine), new ScoredRoster("them", theirs),
                findScore(mine, 4), findScore(theirs, 104));

        Assertions.assertEquals(0.0, trade.improvementT1, 0.0001);
        Assertions.assertEquals(0.0, trade.improvementT2, 0.0001);
    }

    @Test
    void tradingAwayADeepBenchPlayerCostsNothing(){
        // He was not starting, so the lineup does not notice.
        ArrayList<Score> mine = baseRoster(0, 100);
        Score benchWarmer = score(1, "Bench", Position.WR, 50);
        mine.add(benchWarmer);
        ArrayList<Score> theirs = baseRoster(100, 100);
        Score theirBench = score(2, "TheirBench", Position.WR, 150);
        theirs.add(theirBench);

        TradePreviewSerious trade = new TradePreviewSerious(
                new ScoredRoster("me", mine), new ScoredRoster("them", theirs),
                benchWarmer, theirBench);

        Assertions.assertEquals(0.0, trade.improvementT1, 0.0001);
        Assertions.assertEquals(0.0, trade.improvementT2, 0.0001);
    }

    @Test
    void evaluatingATradeLeavesTheRealRostersAlone(){
        // The finder evaluates thousands of these against the same rosters; a
        // preview that mutated its inputs would poison every one after it.
        ArrayList<Score> mine = baseRoster(0, 100);
        ArrayList<Score> theirs = baseRoster(100, 100);
        ScoredRoster me = new ScoredRoster("me", mine);
        ScoredRoster them = new ScoredRoster("them", theirs);

        double myScoreBefore = me.scoreBestROSStartingLineup();
        int mySizeBefore = me.draftedPlayersWithProj.size();

        new TradePreviewSerious(me, them, findScore(mine, 4), findScore(theirs, 104));

        Assertions.assertEquals(mySizeBefore, me.draftedPlayersWithProj.size());
        Assertions.assertEquals(myScoreBefore, me.scoreBestROSStartingLineup(), 0.0001);
    }

    @Test
    void aTwoForTwoIsWorthMoreThanEitherHalfAlone(){
        ArrayList<Score> mine = baseRoster(0, 100);
        ArrayList<Score> theirs = baseRoster(100, 100);
        Score theirStar1 = score(300, "Star1", Position.WR, 900);
        Score theirStar2 = score(300, "Star2", Position.RB, 901);
        theirs.add(theirStar1);
        theirs.add(theirStar2);

        ScoredRoster me = new ScoredRoster("me", mine);
        ScoredRoster them = new ScoredRoster("them", theirs);

        double one = new TradePreviewSerious(me, them, findScore(mine, 4), theirStar1).improvementT1;
        double two = new TradePreviewSerious(me, them,
                findScore(mine, 4), findScore(mine, 2), theirStar1, theirStar2).improvementT1;

        Assertions.assertTrue(two > one, "two stars in should beat one star in");
    }

    @Test
    void aRosterCopyIsIndependentOfTheOriginal(){
        ArrayList<Score> mine = baseRoster(0, 100);
        ScoredRoster original = new ScoredRoster("me", mine);
        ScoredRoster copy = ScoredRoster.makeCopy(original);

        copy.removeScore(findScore(mine, 4));

        Assertions.assertEquals(10, original.draftedPlayersWithProj.size(),
                "removing from a copy must not touch the original");
        Assertions.assertEquals(9, copy.draftedPlayersWithProj.size());
    }

    @Test
    void aCopyKeepsTheScoresItWasGivenRatherThanRederivingThem(){
        // Regression: makeCopy used to rebuild scores from the projection feed,
        // which threw away anything a trade preview had put there by hand and
        // could not copy a roster built from scores alone.
        ArrayList<Score> mine = baseRoster(0, 100);
        ScoredRoster original = new ScoredRoster("me", mine);

        ScoredRoster copy = ScoredRoster.makeCopy(original);

        Assertions.assertEquals(original.scoreBestROSStartingLineup(),
                copy.scoreBestROSStartingLineup(), 0.0001);
    }

    @Test
    void tradingAwayNobodyOnTheRosterIsRefused(){
        // Silently removing nothing while adding somebody scored the roster as
        // if it had an extra player.
        ArrayList<Score> mine = baseRoster(0, 100);
        ScoredRoster me = new ScoredRoster("me", mine);
        Score stranger = score(100, "Stranger", Position.WR, 777);

        Assertions.assertThrows(IllegalArgumentException.class, () -> me.removeScore(stranger));
    }

    @Test
    void aPlayerWithNoSportRadarIdCanStillBeTradedAway(){
        // Defenses and a fair few others have no sportRadarID; matching on it
        // meant removeScore silently did nothing.
        Player noSrid = new Player("Chicago", "Bears", "CHI", Position.DEF, -1, -1, null, -1, "CHI");
        ArrayList<Score> mine = baseRoster(0, 100);
        mine.add(new Score(90, noSrid));
        ScoredRoster me = new ScoredRoster("me", mine);

        me.removeScore(new Score(90, noSrid));

        Assertions.assertEquals(10, me.draftedPlayersWithProj.size());
    }

    private static Score findScore(ArrayList<Score> roster, int sleeperID){
        for(Score score : roster){
            if(score.player.sleeperIDString.equals(String.valueOf(sleeperID))){
                return score;
            }
        }
        throw new AssertionError("no player " + sleeperID + " on this roster");
    }
}
