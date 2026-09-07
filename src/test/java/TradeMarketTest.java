import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import PlayerImportAndSetup.Position;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
                TradeMarket.scoring(TradeMarketTest::worth), TradeMarket.scoring(TradeMarketTest::worth), 0);
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
        List<TradeMarket.Step> loose = TradeMarket.chain("me", board, TradeMarket.scoring(TradeMarketTest::worth), TradeMarket.scoring(TradeMarketTest::worth), 10, 0, 0.0);
        List<TradeMarket.Step> strict = TradeMarket.chain("me", board, TradeMarket.scoring(TradeMarketTest::worth), TradeMarket.scoring(TradeMarketTest::worth), 10, 0, 100.0);
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
                List.of("mineA", "mineB"), List.of("theirsA", "theirsB"), TradeMarket.scoring(mine), TradeMarket.scoring(TradeMarketTest::worth), 0);
        TradeMarket.Trade buy = trades.stream()
                .filter(t -> t.give().equals(List.of("mineB")) && t.get().equals(List.of("theirsA")))
                .findFirst().orElseThrow();
        assertEquals(70.0, buy.myGain(), 1e-9, "80 in for 10 out, by my lights");
        assertEquals(-30.0, buy.theirGain(), 1e-9, "but by his it is 10 in for 40 out, so he still says no");
    }

    /**
     * Justin, correcting the model: "people don't care about gaining keeper
     * value, but they care deeply about losing it." That asymmetry is a ratchet,
     * and it points the OPPOSITE way from treating a rival as merely indifferent
     * to keepers - which made him a cheap seller of them. He is not a cheap
     * seller; he is somebody who will not feel what he receives and will feel
     * every point of what he gives up.
     */
    @Test
    public void theOtherManagerFeelsWhatHeLosesAndNotWhatHeGains(){
        Map<String, Double> surplus = Map.of("hisKeeper", 60.0, "myKeeper", 55.0);
        java.util.function.ToDoubleFunction<List<String>> flatSeason = ids -> ids.size() * 10.0;
        TradeMarket.Side him = TradeMarket.lossAverseOnKeepers(flatSeason, surplus);

        // he gives up his keeper and receives mine: season is a wash, and the
        // keeper he receives buys him nothing while the one he loses costs him all of it
        double givingHisUp = him.gain(List.of("hisKeeper", "spare"), List.of("hisKeeper"), List.of("myKeeper"));
        assertEquals(-60.0, givingHisUp, 1e-9,
                "the keeper he hands over costs him 60 and the one he gets back is worth nothing to him");

        // and a trade that touches no keeper of his is priced on the season alone
        double ordinary = him.gain(List.of("spare", "other"), List.of("spare"), List.of("plain"));
        assertEquals(0.0, ordinary, 1e-9, "no keeper of his moves, so nothing is felt");

        // the same trade under the OLD indifferent model looked free to him
        TradeMarket.Side indifferent = TradeMarket.scoring(flatSeason);
        assertEquals(0.0, indifferent.gain(List.of("hisKeeper", "spare"),
                List.of("hisKeeper"), List.of("myKeeper")), 1e-9,
                "which is why the first version happily proposed buying other people's keepers");
        assertTrue(givingHisUp < indifferent.gain(List.of("hisKeeper", "spare"),
                List.of("hisKeeper"), List.of("myKeeper")),
                "asking for his keeper must be HARDER than the indifferent model said, not easier");
    }

    /**
     * Justin: before the season, a trade is judged on draft position first.
     * "People will not like to trade their round n pick for my round m pick if
     * my m is significantly > n" - even when both sides gain on the objective.
     */
    @Test
    public void howATradeReadsOnDraftPositionIsItsOwnQuestion(){
        Map<String, Double> adp = Map.of("myEarly", 27.0, "myLate", 150.0,
                "hisEarly", 30.0, "hisLate", 145.0);
        java.util.function.ToDoubleFunction<String> adpOf = adp::get;

        // A LOWER ADP IS AN EARLIER PICK. Sending a man drafted at 150 to get one
        // drafted at 30 means asking for the better pick, which is the hard
        // direction - the first version of this had the sign backwards and told
        // him that giving Stevenson (73) for Josh Allen (20) was easy to get agreed.
        TradeMarket.Optics grab = TradeMarket.optics(List.of("myLate"), List.of("hisEarly"), adpOf);
        assertEquals(120.0, grab.gap(), 1e-9, "150 out against 30 in");
        assertTrue(grab.verdict().contains("expect a no on sight"),
                "asking for a pick 120 places earlier is the hardest ask there is, not the easiest: "
                        + grab.verdict());

        TradeMarket.Optics give = TradeMarket.optics(List.of("myEarly"), List.of("hisLate"), adpOf);
        assertEquals(-118.0, give.gap(), 1e-9);
        assertTrue(give.verdict().contains("you hand over the earlier pick"),
                "sending the earlier pick is what he says yes to: " + give.verdict());

        // and a fair-looking one
        TradeMarket.Optics even = TradeMarket.optics(List.of("myEarly"), List.of("hisEarly"), adpOf);
        assertEquals(-3.0, even.gap(), 1e-9);
        assertTrue(even.verdict().contains("reads even"));

        // PAIRS ANCHOR ON THE BEST MAN, because ADPs do not add: two men at 90
        // are not one man at 45, and a trade is named after its headline player
        TradeMarket.Optics pair = TradeMarket.optics(List.of("myEarly", "myLate"),
                List.of("hisLate", "hisEarly"), adpOf);
        assertEquals(27.0, pair.mine(), 1e-9, "the earliest man I send");
        assertEquals(30.0, pair.theirs(), 1e-9, "against the earliest man I receive");
        assertEquals(2, pair.menEachWay());
    }

    /**
     * The outside-option market, on input small enough to reason about by hand.
     *
     * Justin's point: a rival's fallback is not his best trade, because that
     * trade needs the manager across from him to prefer it to HIS options. The
     * first attempt expressed that as an iteration and it oscillated with period
     * two - see TradeMarket.outsideOption - so the model is a pairing instead.
     */
    @Test
    public void aFallbackIsWhatHisActualPartnerWouldGiveHim(){
        // A and C are each other's best deal by a distance, so they pair first.
        // B's biggest number is the +30 he could get from A - but A is taken, so
        // what B actually ends up with is the +8 from C... who is also taken.
        // B is left unmatched, with no fallback at all.
        List<TradeMarket.Alternative> board = List.of(
                new TradeMarket.Alternative("A", "B", List.of("a1"), List.of("b1"), 5, 30),
                new TradeMarket.Alternative("A", "C", List.of("a2"), List.of("c1"), 50, 40),
                new TradeMarket.Alternative("B", "C", List.of("b2"), List.of("c2"), 8, 3));
        TradeMarket.Market market = TradeMarket.match(board, Set.of("me", "A", "B", "C"), "me");

        assertEquals(50.0, market.naive().get("A"), 1e-9, "A's best over all partners is +50");
        assertEquals(30.0, market.naive().get("B"), 1e-9, "B's best over all partners is +30");

        assertEquals("C", market.partner().get("A"), "A and C are the richest pair, so they form");
        assertEquals("A", market.partner().get("C"));
        assertFalse(market.partner().containsKey("B"), "B is left over");

        assertEquals(50.0, market.fallback().get("A"), 1e-9);
        assertEquals(40.0, market.fallback().get("C"), 1e-9);
        assertEquals(0.0, market.fallback().get("B"), 1e-9,
                "B cannot count the +30 from A: A is doing better elsewhere, and neither A"
                        + " nor C would break their pair for what B offers");

        for(String manager : market.fallback().keySet()){
            assertTrue(market.fallback().get(manager) <= market.naive().get(manager) + 1e-9,
                    manager + " has a fallback above his own best trade, which is impossible");
        }
    }

    /**
     * A manager left out of the pairing is not barred from trading. If somebody
     * who IS paired would rather deal with him, that deal is really available and
     * has to count - otherwise an odd number of rivals hands one of them a
     * fallback of zero purely by arithmetic, and every offer to him looks good.
     */
    @Test
    public void theLeftoverManagerCanStillBreakUpAPair(){
        // A+C pair on joint surplus 90. B offers C a deal worth 45 to C - better
        // than the 40 C is getting from A - so C would leave, and B's fallback is
        // the 20 that deal gives B, not zero.
        List<TradeMarket.Alternative> board = List.of(
                new TradeMarket.Alternative("A", "C", List.of("a2"), List.of("c1"), 50, 40),
                new TradeMarket.Alternative("B", "C", List.of("b3"), List.of("c3"), 20, 45));
        TradeMarket.Market market = TradeMarket.match(board, Set.of("me", "A", "B", "C"), "me");

        assertEquals("C", market.partner().get("A"), "A and C pair first on joint surplus");
        assertFalse(market.partner().containsKey("B"), "B is the leftover");
        assertEquals(20.0, market.fallback().get("B"), 1e-9,
                "but C would rather have B's 45 than A's 40, so B's fallback is that deal");
        assertTrue(market.fallback().get("B") <= market.naive().get("B") + 1e-9,
                "and it still cannot exceed his best trade anywhere");
    }

    /** Justin is excluded: his own offers are not somebody's alternative to him. */
    @Test
    public void theOwnerIsNotHisOwnRivalsFallback(){
        TradeMarket.Market market = TradeMarket.match(
                List.of(new TradeMarket.Alternative("A", "B", List.of("a3"), List.of("b4"), 9, 9)),
                Set.of("me", "A", "B"), "me");
        assertFalse(market.fallback().containsKey("me"),
                "the owner must not appear among the fallbacks, or his own offer would be"
                        + " counted as the thing it has to beat");
    }

    /**
     * What a single man fetches: the best his OWNER gets from a straight
     * one-for-one that sends him elsewhere. Only one-for-ones count, because in
     * a bundle the gain belongs to the pair and splitting it between the men
     * would be a choice rather than a measurement.
     */
    @Test
    public void aManIsPricedByTheOneForOnesThatSendHimAway(){
        List<TradeMarket.Alternative> board = List.of(
                new TradeMarket.Alternative("A", "B", List.of("henry"), List.of("nabers"), 12, 4),
                new TradeMarket.Alternative("A", "C", List.of("henry"), List.of("kelce"), 20, 6),
                // a two-for-two: its gain belongs to the pair, so neither man is priced from it
                new TradeMarket.Alternative("A", "C", List.of("henry", "purdy"),
                        List.of("allen", "olave"), 99, 99));
        Map<String, Double> price = TradeMarket.sellPrice(board);

        assertEquals(20.0, price.get("henry"), 1e-9,
                "Henry's price is the best one-for-one his owner can get for him, not the"
                        + " ninety-nine from a bundle he happens to be in");
        assertEquals(6.0, price.get("kelce"), 1e-9, "and each man arriving is priced for HIS owner");
        assertEquals(4.0, price.get("nabers"), 1e-9);
        assertFalse(price.containsKey("purdy"),
                "a man who only ever appears inside a bundle has no isolated price, and"
                        + " must not be given one");
    }
}
