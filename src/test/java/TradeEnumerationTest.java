import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The finder walks every opponent and proposes every swap against each. If it
 * skips a team, nothing complains - that manager's trades simply never appear,
 * and you would have no way of noticing.
 */
class TradeEnumerationTest {

    private static ArrayList<ScoredRoster> league(int teams){
        ArrayList<ScoredRoster> rosters = new ArrayList<>();
        for(int team = 0; team < teams; team++){
            ArrayList<Score> scores = new ArrayList<>();
            int base = team * 100;
            scores.add(new Score(100 + team, TestPlayers.player("Qb", "T" + team, "BUF", Position.QB, base + 1)));
            scores.add(new Score(90 + team, TestPlayers.player("Rb", "T" + team, "BUF", Position.RB, base + 2)));
            scores.add(new Score(80 + team, TestPlayers.player("Wr", "T" + team, "BUF", Position.WR, base + 3)));
            rosters.add(new ScoredRoster("user" + team, scores));
        }
        return rosters;
    }

    @Test
    void everyOpponentGetsConsidered(){
        ArrayList<ScoredRoster> rosters = league(12);

        PriorityQueue<TradePreviewSerious> trades =
                TradeFinder.singleSwapTradeFinderAll(rosters, "user0");

        Set<String> opponents = new HashSet<>();
        for(TradePreviewSerious trade : trades){
            opponents.add(trade.t2p1Score.player.lastName);
        }

        Assertions.assertEquals(11, opponents.size(),
                "all eleven other managers should show up in the proposals, got " + opponents);
        Assertions.assertFalse(opponents.contains("T0"), "I should not be trading with myself");
    }

    @Test
    void everyPairingOfPlayersIsProposed(){
        // 3 of mine against 3 of theirs, across 11 opponents.
        ArrayList<ScoredRoster> rosters = league(12);

        PriorityQueue<TradePreviewSerious> trades =
                TradeFinder.singleSwapTradeFinderAll(rosters, "user0");

        Assertions.assertEquals(3 * 3 * 11, trades.size());
    }

    @Test
    void theLeagueSizeIsFollowedRatherThanAssumed(){
        ArrayList<ScoredRoster> rosters = league(4);

        PriorityQueue<TradePreviewSerious> trades =
                TradeFinder.singleSwapTradeFinderAll(rosters, "user0");

        Set<String> opponents = new HashSet<>();
        for(TradePreviewSerious trade : trades){
            opponents.add(trade.t2p1Score.player.lastName);
        }
        Assertions.assertEquals(3, opponents.size());
    }

    @Test
    void aUserWhoIsNotInTheLeagueSaysSo(){
        // Used to be a NullPointerException several frames away from the cause.
        ArrayList<ScoredRoster> rosters = league(12);

        IllegalArgumentException thrown = Assertions.assertThrows(IllegalArgumentException.class,
                () -> TradeFinder.singleSwapTradeFinderAll(rosters, "nobody"));

        Assertions.assertTrue(thrown.getMessage().contains("nobody"), thrown.getMessage());
    }

    @Test
    void enumeratingDoesNotDisturbTheRostersItWasGiven(){
        ArrayList<ScoredRoster> rosters = league(12);
        int before = rosters.size();
        double myScoreBefore = rosters.get(0).scoreBestROSStartingLineup();

        TradeFinder.singleSwapTradeFinderAll(rosters, "user0");

        Assertions.assertEquals(before, rosters.size(), "my roster was removed from the caller's list");
        Assertions.assertEquals(myScoreBefore, rosters.get(0).scoreBestROSStartingLineup(), 0.0001);
    }

    @Test
    void theBestTradeComesOffTheQueueFirst(){
        ArrayList<ScoredRoster> rosters = league(12);

        PriorityQueue<TradePreviewSerious> trades =
                TradeFinder.singleSwapTradeFinderAll(rosters, "user0");

        double previous = Double.MAX_VALUE;
        while(!trades.isEmpty()){
            double improvement = trades.poll().improvementT1;
            Assertions.assertTrue(improvement <= previous + 0.0001,
                    "the queue handed back " + improvement + " after " + previous);
            previous = improvement;
        }
    }

    @Test
    void doubleAndTripleSwapsCoverEveryOpponentToo(){
        ArrayList<ScoredRoster> rosters = league(12);

        for(PriorityQueue<TradePreviewSerious> trades : java.util.List.of(
                TradeFinder.doubleSwapTradeFinderAll(rosters, "user0"),
                TradeFinder.tripleSwapTradeFinderAll(rosters, "user0"))){
            Set<String> opponents = new HashSet<>();
            for(TradePreviewSerious trade : trades){
                opponents.add(trade.t2p1Score.player.lastName);
            }
            Assertions.assertEquals(11, opponents.size(), "got " + opponents);
        }
    }
}
