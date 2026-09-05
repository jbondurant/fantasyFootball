import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import PlayerImportAndSetup.Position;

import java.util.List;
import java.util.Map;

/** The market half of the trade tools: both sides must gain, the roster must stay whole, and a chip is only a chip if it is scarce. */
public class TradeMarketTest {

    private static double worth(List<String> ids){
        Map<String, Double> value = Map.of("mineA", 50.0, "mineB", 10.0,
                "theirsA", 40.0, "theirsB", 12.0);
        return ids.stream().mapToDouble(id -> value.getOrDefault(id, 0.0)).sum();
    }

    @Test
    public void onlyTradesBothSidesGainFromSurvive(){
        List<TradeMarket.Trade> all = List.of(
                new TradeMarket.Trade("him", List.of("a"), List.of("b"), 10, 5),
                new TradeMarket.Trade("him", List.of("c"), List.of("d"), 30, -2),
                new TradeMarket.Trade("him", List.of("e"), List.of("f"), -1, 40),
                new TradeMarket.Trade("him", List.of("g"), List.of("h"), 2, 2));
        List<TradeMarket.Trade> good = TradeMarket.mutual(all);
        assertEquals(2, good.size(), "the two one-sided ones are offers nobody accepts");
        assertEquals(10, good.get(0).myGain(), 1e-9, "best for us leads");
        assertEquals(5, good.get(0).weaker(), 1e-9, "a trade is only as good as its weaker half");
    }

    @Test
    public void aSwapKeepsBothRostersTheSameSize(){
        List<String> mine = List.of("mineA", "mineB", "keep");
        List<String> after = TradeMarket.swap(mine, List.of("mineB"), List.of("theirsA"));
        assertEquals(3, after.size());
        assertTrue(after.contains("theirsA") && !after.contains("mineB"));
        assertEquals(4, TradeMarket.swap(mine, List.of("mineB"), List.of("x", "y")).size(),
                "an unbalanced swap changes the size, which is why only balanced ones are searched");
    }

    @Test
    public void everyOneForOneIsSearchedAndBothSidesArePriced(){
        List<TradeMarket.Trade> trades = TradeMarket.between("me", "him",
                List.of("mineA", "mineB"), List.of("theirsA", "theirsB"),
                TradeMarketTest::worth, TradeMarketTest::worth, 0);
        assertEquals(4, trades.size(), "two of mine against two of his");
        TradeMarket.Trade upgrade = trades.stream()
                .filter(t -> t.give().equals(List.of("mineB")) && t.get().equals(List.of("theirsA")))
                .findFirst().orElseThrow();
        assertEquals(30.0, upgrade.myGain(), 1e-9, "40 in for 10 out");
        assertEquals(-30.0, upgrade.theirGain(), 1e-9, "and it costs him exactly that, so it is not an offer");
    }

    @Test
    public void aPieceIsOnlyAChipIfTheOtherTeamsCannotSupplyIt(){
        Map<String, List<String>> rosters = Map.of(
                "me", List.of("q1", "q2"),
                "rich1", List.of("q3", "q4"),
                "rich2", List.of("q5", "q6"),
                "thin", List.of("q7"));
        Map<String, Position> positions = Map.of("q1", Position.QB, "q2", Position.QB,
                "q3", Position.QB, "q4", Position.QB, "q5", Position.QB, "q6", Position.QB,
                "q7", Position.QB);
        assertEquals(2, TradeMarket.alternativeSellers(rosters, "me", positions, Position.QB, 1),
                "two rivals also carry a spare quarterback; the thin one does not, and I am not a seller to myself");
        assertEquals(0, TradeMarket.alternativeSellers(rosters, "me", positions, Position.RB, 2),
                "nobody has a spare back because nobody has a back");
    }

    /**
     * A chain must stop, and it must stop for the right reason. Every trade in
     * it improves both rosters on one objective, so the league's total only
     * rises against a fixed ceiling - but long before that ceiling the gains
     * fall under the yardstick's own noise, and a chain that keeps taking them
     * churns rather than improves.
     */
    @Test
    public void aChainStopsAtTheFloorRatherThanWalkingAroundInsideTheNoise(){
        Map<String, List<String>> board = Map.of(
                "me", List.of("mineA", "mineB"),
                "him", List.of("theirsA", "theirsB"));
        // with no floor the chain takes anything positive; with one it takes nothing tiny
        List<TradeMarket.Step> loose = TradeMarket.chain("me", board, TradeMarketTest::worth, TradeMarketTest::worth, 10, 0, 0.0);
        List<TradeMarket.Step> strict = TradeMarket.chain("me", board, TradeMarketTest::worth, TradeMarketTest::worth, 10, 0, 100.0);
        assertTrue(strict.size() <= loose.size(), "a floor can only shorten a chain");
        assertTrue(strict.isEmpty(), "nothing here is worth 100 points, so nothing is taken");
        assertTrue(loose.size() < 10, "and even unfloored it terminates rather than running to the cap");
        for(int i = 1; i < loose.size(); i++){
            assertTrue(loose.get(i).cumulative() >= loose.get(i - 1).cumulative(),
                    "the running total never goes backwards");
        }
    }

    /**
     * A keeper is worth the pick it saves, and the pick is only worth what it
     * would have bought AT HIS POSITION. Measured against the whole board it
     * made every quarterback a franchise keeper - this league pays 6 for a
     * passing touchdown while its ADP is calibrated for 4, so Purdy read a
     * surplus of 272 and the board even ran backwards (pick 175 "projecting"
     * 314.9 against 225.5 at pick 18).
     */
    @Test
    public void aKeeperIsPricedAgainstHisOwnPositionAndNotTheWholeBoard(){
        Map<String, Double> points = Map.of("qbEarly", 400.0, "qbLate", 330.0,
                "rbEarly", 226.0, "rbLate", 100.0);
        Map<String, Position> positions = Map.of("qbEarly", Position.QB, "qbLate", Position.QB,
                "rbEarly", Position.RB, "rbLate", Position.RB);
        Map<Position, java.util.TreeMap<Double, Double>> best = new java.util.EnumMap<>(Position.class);
        // keys must reach past the pick being asked about, or the lookup falls
        // off the end and every man reads as though nothing replaced him - which
        // is what the first version of this test did, and it inverted the answer
        java.util.TreeMap<Double, Double> qb = new java.util.TreeMap<>();
        qb.put(18.0, 400.0); qb.put(160.0, 330.0);
        java.util.TreeMap<Double, Double> rb = new java.util.TreeMap<>();
        rb.put(18.0, 226.0); rb.put(160.0, 100.0);
        best.put(Position.QB, qb); best.put(Position.RB, rb);

        AAAConfiguration configuration = AAAConfiguration.getInstance();
        // a late quarterback is barely a keeper: the pick would have bought one nearly as good
        double qbSurplus = TradeMarket.keeperPoints(Map.of("qbEarly", 13), points, best, positions,
                configuration, "qbEarly");
        double rbSurplus = TradeMarket.keeperPoints(Map.of("rbEarly", 13), points, best, positions,
                configuration, "rbEarly");
        assertEquals(70.0, qbSurplus, 1e-9, "400 against the 330 the pick would have bought");
        assertEquals(126.0, rbSurplus, 1e-9, "226 against a 100 back");
        assertTrue(rbSurplus > qbSurplus,
                "a back kept at the same round is worth far more than a quarterback, because the pick"
                        + " buys a much worse back than it does a quarterback: " + rbSurplus + " vs " + qbSurplus);
        assertEquals(0.0, TradeMarket.keeperPoints(Map.of(), points, best, positions, configuration, "qbEarly"), 1e-9,
                "a man the rules will not let you keep is worth nothing to keep");
        // and past the end of the board there is no replacement at all
        java.util.TreeMap<Double, Double> shallow = new java.util.TreeMap<>();
        shallow.put(18.0, 400.0);
        assertEquals(400.0, TradeMarket.keeperPoints(Map.of("qbEarly", 13), points,
                Map.of(Position.QB, shallow), positions, configuration, "qbEarly"), 1e-9,
                "nobody at his position is left that late, so keeping him saves the whole projection");
    }

    @Test
    public void aRosterKeepsTwoMenAndNoMore(){
        Map<String, Double> surplus = Map.of("a", 58.0, "b", 43.0, "c", 31.0, "d", 17.0);
        assertEquals(101.0, TradeMarket.keeperValue(List.of("a", "b", "c", "d"), surplus), 1e-9,
                "the best two only - a third good keeper is worth nothing next March");
        assertEquals(58.0, TradeMarket.keeperValue(List.of("a"), surplus), 1e-9);
        assertEquals(0.0, TradeMarket.keeperValue(List.of(), surplus), 1e-9);
    }

    /**
     * Keeper value is a tiebreak on a trade that already helps this year, never
     * a reason to get worse at it. Pricing the two sides differently - rivals do
     * not count keepers, so they sell them cheap - made the whole board
     * keeper-buying, and the best offer on it gave up Derrick Henry for a
     * seventeen-point-worse 2026.
     */
    @Test
    public void aTradeMustStandUpWithoutTheKeeperValueToo(){
        List<TradeMarket.Trade> mutual = List.of(
                new TradeMarket.Trade("him", List.of("henry"), List.of("brown"), 69.4, 19.5),
                new TradeMarket.Trade("him", List.of("evans"), List.of("lamb"), 28.7, 2.6));
        Map<String, Double> seasonGain = Map.of("henry", -17.0, "evans", 41.2);
        List<TradeMarket.Trade> kept = TradeMarket.alsoGoodThisSeason(mutual,
                t -> seasonGain.get(t.give().get(0)));
        assertEquals(1, kept.size(), "the keeper-only trade is dropped however good it looks with keepers");
        assertEquals("evans", kept.get(0).give().get(0));
        assertEquals(2, TradeMarket.alsoGoodThisSeason(mutual, t -> 1.0).size(),
                "and when both help this season, both stay");
    }

    /**
     * The two sides are priced by their own objectives - a rival who does not
     * count keeper value will part with one cheaply, and takes no credit for
     * receiving one.
     */
    @Test
    public void eachSideIsPricedByItsOwnLights(){
        // to me "theirsA" is worth double, to him it is worth its face value
        java.util.function.ToDoubleFunction<List<String>> mine = ids ->
                ids.stream().mapToDouble(id -> id.equals("theirsA") ? 80.0 : worth(List.of(id))).sum();
        List<TradeMarket.Trade> trades = TradeMarket.between("me", "him",
                List.of("mineA", "mineB"), List.of("theirsA", "theirsB"), mine, TradeMarketTest::worth, 0);
        TradeMarket.Trade buy = trades.stream()
                .filter(t -> t.give().equals(List.of("mineB")) && t.get().equals(List.of("theirsA")))
                .findFirst().orElseThrow();
        assertEquals(70.0, buy.myGain(), 1e-9, "80 in for 10 out, by my lights");
        assertEquals(-30.0, buy.theirGain(), 1e-9, "but by his it is 10 in for 40 out, so he still says no");
    }
}
