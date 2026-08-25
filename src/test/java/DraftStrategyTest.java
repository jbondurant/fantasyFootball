import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The draft simulator runs hundreds of drafts to decide which keeper to hold
 * and who to take next. It is stochastic, so a broken ordering does not fail -
 * it just recommends the wrong player, plausibly. These pin the deterministic
 * parts underneath the randomness.
 */
class DraftStrategyTest {

    private static Rank rank(int position, String lastName, Position pos, int id){
        return new Rank(position, TestPlayers.player("Test", lastName, "BUF", pos, id));
    }

    private static ArrayList<Rank> board(){
        ArrayList<Rank> ranks = new ArrayList<>();
        ranks.add(rank(1, "Rb1", Position.RB, 1));
        ranks.add(rank(2, "Wr1", Position.WR, 2));
        ranks.add(rank(3, "Rb2", Position.RB, 3));
        ranks.add(rank(4, "Qb1", Position.QB, 4));
        ranks.add(rank(5, "Te1", Position.TE, 5));
        ranks.add(rank(6, "Wr2", Position.WR, 6));
        ranks.add(rank(7, "Def1", Position.DEF, 7));
        return ranks;
    }

    @Test
    void theBoardHandsBackTheBestPlayerAtAPositionFirst(){
        RankOrderedPlayers rop = new RankOrderedPlayers(board());

        Assertions.assertEquals("Rb1", rop.removeTopPlayerOfPos(Position.RB).lastName);
        Assertions.assertEquals("Rb2", rop.removeTopPlayerOfPos(Position.RB).lastName);
        Assertions.assertEquals("Wr1", rop.removeTopPlayerOfPos(Position.WR).lastName);
    }

    @Test
    void aDraftedPlayerLeavesTheBoard(){
        ArrayList<Rank> ranks = board();
        RankOrderedPlayers rop = new RankOrderedPlayers(ranks);

        Assertions.assertTrue(rop.removePlayer(ranks.get(0).player), "Rb1 should have been on the board");
        Assertions.assertEquals("Rb2", rop.removeTopPlayerOfPos(Position.RB).lastName);
    }

    @Test
    void removingSomeoneWhoWasNeverOnTheBoardIsHarmless(){
        RankOrderedPlayers rop = new RankOrderedPlayers(board());

        Assertions.assertFalse(rop.removePlayer(TestPlayers.player("Not", "Here", "BUF", Position.RB, 99)));
        Assertions.assertFalse(rop.removePlayer(null));
    }

    @Test
    void aPositionThatRunsDryReturnsNullRatherThanThrowing(){
        RankOrderedPlayers rop = new RankOrderedPlayers(board());

        Assertions.assertNotNull(rop.removeTopPlayerOfPos(Position.QB));
        Assertions.assertNull(rop.removeTopPlayerOfPos(Position.QB), "only one quarterback was on the board");
    }

    @Test
    void aHumanDraftsTheShapeTheyWereGiven(){
        HumanStrategy human = new HumanStrategy(new RankOrderedPlayers(board()),
                HumanStrategy.nonPermutedPositions(1, 2, 1, 0));

        List<String> taken = new ArrayList<>();
        for(int pick = 0; pick < 4; pick++){
            taken.add(human.selectPlayer().lastName);
        }

        // One QB, two RB, one WR - best available at each, in that order.
        Assertions.assertEquals(List.of("Qb1", "Rb1", "Rb2", "Wr1"), taken);
    }

    @Test
    void theRequestedRosterShapeIsTheShapeYouGet(){
        ArrayList<Position> shape = HumanStrategy.nonPermutedPositions(1, 3, 5, 1);

        Assertions.assertEquals(10, shape.size());
        Assertions.assertEquals(1, java.util.Collections.frequency(shape, Position.QB));
        Assertions.assertEquals(3, java.util.Collections.frequency(shape, Position.RB));
        Assertions.assertEquals(5, java.util.Collections.frequency(shape, Position.WR));
        Assertions.assertEquals(1, java.util.Collections.frequency(shape, Position.TE));
    }

    @Test
    void aBotWithNoVarianceDraftsStraightDownTheBoard(){
        PriorityQueue<Rank> deviated = DecimalRank.makeDeviatedRanking(decimalBoard(), noVariance(), 0);
        StrategyBot bot = new StrategyBot(deviated);

        List<String> taken = new ArrayList<>();
        for(int pick = 0; pick < 4; pick++){
            taken.add(bot.selectPlayer().lastName);
        }

        Assertions.assertEquals(List.of("Rb1", "Wr1", "Rb2", "Qb1"), taken);
    }

    @Test
    void pushingQuarterbacksBackMovesThemDownTheBoard(){
        // The league is one QB, so bots reach for them later than raw ADP says.
        PriorityQueue<Rank> deviated = DecimalRank.makeDeviatedRanking(decimalBoard(), noVariance(), 3);
        StrategyBot bot = new StrategyBot(deviated);

        List<String> taken = new ArrayList<>();
        for(int pick = 0; pick < board().size(); pick++){
            taken.add(bot.selectPlayer().lastName);
        }

        // Ranked 4th, pushed to an effective 7th: behind the tight end and the
        // second receiver, who were 5th and 6th.
        Assertions.assertTrue(taken.indexOf("Qb1") > taken.indexOf("Te1"), "got " + taken);
        Assertions.assertTrue(taken.indexOf("Qb1") > taken.indexOf("Wr2"), "got " + taken);

        int unpushed = new StrategyBotOrder(0).order().indexOf("Qb1");
        Assertions.assertTrue(taken.indexOf("Qb1") > unpushed,
                "pushing quarterbacks back has to move them later, not earlier");
    }

    @Test
    void aDraftedPlayerLeavesTheBotsBoardEvenWithoutASportRadarId(){
        // Regression: removeDraftedPlayer matched on sportRadarID and skipped
        // players whose id was null - every defense among them - so a bot could
        // draft the same player twice in one simulated draft.
        Player defense = new Player("Chicago", "Bears", "CHI", Position.DEF, -1, -1, null, -1, "CHI");
        ArrayList<DecimalRank> withDefense = decimalBoard();
        withDefense.add(new DecimalRank(0.5, defense));

        StrategyBot bot = new StrategyBot(
                DecimalRank.makeDeviatedRanking(withDefense, noVariance(), 0));
        bot.removeDraftedPlayer(defense);

        List<String> taken = new ArrayList<>();
        for(int pick = 0; pick < withDefense.size() - 1; pick++){
            taken.add(bot.selectPlayer().lastName);
        }
        Assertions.assertFalse(taken.contains("Bears"), "a drafted defense stayed on the board: " + taken);
    }

    @Test
    void aBotWhoseBoardRanDryReturnsNullRatherThanThrowing(){
        StrategyBot bot = new StrategyBot(
                DecimalRank.makeDeviatedRanking(decimalBoard(), noVariance(), 0));
        for(int pick = 0; pick < board().size(); pick++){
            bot.selectPlayer();
        }
        Assertions.assertNull(bot.selectPlayer());
    }

    @Test
void aHumanWhosePlanRunsOutTakesBestAvailable(){
        // Regression: the plan only covers as many picks as it was built with.
        // Simulating more rounds than that threw IndexOutOfBoundsException out
        // of remove(0), which made the keeper chooser unrunnable.
        HumanStrategy human = new HumanStrategy(new RankOrderedPlayers(board()),
                HumanStrategy.nonPermutedPositions(1, 0, 0, 0));

        Assertions.assertEquals("Qb1", human.selectPlayer().lastName);

        Player next = human.selectPlayer();
        Assertions.assertNotNull(next, "should fall back rather than throw");
        Assertions.assertEquals("Rb1", next.lastName, "best left on the board");
    }

    @Test
    void aPlanCallingForAnExhaustedPositionFallsBackToo(){
        RankOrderedPlayers board = new RankOrderedPlayers(board());
        board.removeTopPlayerOfPos(Position.QB);  // the only quarterback

        HumanStrategy human = new HumanStrategy(board,
                HumanStrategy.nonPermutedPositions(1, 0, 0, 0));

        Assertions.assertEquals("Rb1", human.selectPlayer().lastName);
    }

    @Test
    void bestAvailableIsTheLowestRankAcrossEveryPosition(){
        RankOrderedPlayers board = new RankOrderedPlayers(board());

        Assertions.assertEquals("Rb1", board.removeBestAvailable().lastName);
        Assertions.assertEquals("Wr1", board.removeBestAvailable().lastName);
        Assertions.assertEquals("Rb2", board.removeBestAvailable().lastName);
    }

    @Test
    void anEmptyBoardHandsBackNullRatherThanThrowing(){
        RankOrderedPlayers board = new RankOrderedPlayers(new ArrayList<>());
        Assertions.assertNull(board.removeBestAvailable());
    }

    @Test
    void everyPlayerSurvivesTheDeviation(){
        // Losing players here would quietly shrink the pool every simulated draft.
        PriorityQueue<Rank> deviated = DecimalRank.makeDeviatedRanking(decimalBoard(), noVariance(), 0);

        Assertions.assertEquals(board().size(), deviated.size());
    }

    @Test
    void varianceReordersTheBoardWithoutLosingAnyone(){
        HashMap<String, Double> wideVariance = new HashMap<>();
        for(DecimalRank decimalRank : decimalBoard()){
            wideVariance.put(decimalRank.player.sportRadarID, 5.0);
        }

        boolean everDiffered = false;
        for(int attempt = 0; attempt < 50 && !everDiffered; attempt++){
            PriorityQueue<Rank> deviated =
                    DecimalRank.makeDeviatedRanking(decimalBoard(), wideVariance, 0);
            Assertions.assertEquals(board().size(), deviated.size());
            if(!"Rb1".equals(deviated.peek().player.lastName)){
                everDiffered = true;
            }
        }

        Assertions.assertTrue(everDiffered, "with a standard deviation of 5 the board should sometimes reorder");
    }

    /** The order a zero-variance bot drafts in, for a given quarterback push. */
    private static class StrategyBotOrder {
        private final int qbADPChange;

        StrategyBotOrder(int qbADPChange){
            this.qbADPChange = qbADPChange;
        }

        List<String> order(){
            StrategyBot bot = new StrategyBot(
                    DecimalRank.makeDeviatedRanking(decimalBoard(), noVariance(), qbADPChange));
            List<String> taken = new ArrayList<>();
            for(int pick = 0; pick < board().size(); pick++){
                taken.add(bot.selectPlayer().lastName);
            }
            return taken;
        }
    }

    @Test
    void everyManagersKeepersOccupyTheirOwnRounds(){
        // A keeper costs its owner that round's pick, so they draft one fewer
        // player per keeper. Only mine used to be placed, which had the other
        // eleven teams drafting a full sixteen rounds while also holding
        // keepers, and pulling players out of the pool that were never theirs.
        Player mineA = TestPlayers.player("My", "Keeper1", "BUF", Position.RB, 100);
        Player mineB = TestPlayers.player("My", "Keeper2", "BUF", Position.WR, 101);
        Player theirs = TestPlayers.player("Their", "Keeper", "BUF", Position.TE, 200);

        List<Keeper> mine = List.of(new Keeper("me", mineA, 12), new Keeper("me", mineB, 13));
        List<Keeper> league = List.of(new Keeper("them", theirs, 6), new Keeper("me", mineA, 4));

        Map<String, Map<Integer, Player>> placed =
                SimulationDraft.keepersByOwnerAndRound(mine, league, "me");

        Assertions.assertEquals(mineA, placed.get("me").get(12));
        Assertions.assertEquals(mineB, placed.get("me").get(13));
        Assertions.assertEquals(theirs, placed.get("them").get(6));
        Assertions.assertNull(placed.get("me").get(4),
                "the set being evaluated replaces whatever I had already declared");
        Assertions.assertEquals(2, placed.get("me").size());
    }

    @Test
    void aManagerWithNoKeepersDraftsEveryRound(){
        Map<String, Map<Integer, Player>> placed = SimulationDraft.keepersByOwnerAndRound(
                List.of(), List.of(new Keeper("them", TestPlayers.player("A","B","BUF",Position.RB,1), 5)), "me");

        Assertions.assertTrue(placed.getOrDefault("nobody", Map.of()).isEmpty());
        Assertions.assertEquals(1, placed.get("them").size());
    }

    private static ArrayList<DecimalRank> decimalBoard(){
        ArrayList<DecimalRank> decimalRanks = new ArrayList<>();
        for(Rank rank : board()){
            decimalRanks.add(new DecimalRank(rank.rankNum, rank.player));
        }
        return decimalRanks;
    }

    /** A standard deviation of zero makes the deviated ranking deterministic. */
    private static HashMap<String, Double> noVariance(){
        HashMap<String, Double> variance = new HashMap<>();
        for(DecimalRank decimalRank : decimalBoard()){
            variance.put(decimalRank.player.sportRadarID, 0.0);
        }
        return variance;
    }
}
