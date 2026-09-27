import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * The betting-market tools on inputs whose answers are known: odds
 * arithmetic, settlement, the ratings fit, Kalshi's three contract shapes,
 * the static-replication bounds (checked outcome by outcome), the arbitrage
 * test, the prop model's neighbours and the ledger's profit arithmetic.
 */
public class MarketToolsTest {

    @Test
    public void americanOddsPayAndImplyWhatTheyShould(){
        assertEquals(100.0 / 110, MarketEfficiency.payout(-110), 1e-12);
        assertEquals(1.5, MarketEfficiency.payout(150), 1e-12);
        assertEquals(110.0 / 210, MarketEfficiency.implied(-110), 1e-12, "52.38%: the break-even at -110");
        assertEquals(0.4, MarketEfficiency.implied(150), 1e-12);
        assertEquals(1.5, MarketEfficiency.settle(1, 150), 1e-12);
        assertEquals(-1, MarketEfficiency.settle(-1, -110), 1e-12);
        assertEquals(0, MarketEfficiency.settle(0, -110), 1e-12, "a push returns the stake");
    }

    static NflverseGames.Game game(String season, int week, String home, String away, int hs, int as, double spread){
        return new NflverseGames.Game(season + week + home, season, week, season + "-09-10", "13:00", home, away, hs, as, false, false,
                spread, 44.0, -150, 130, -110, -110, -110, -110, "outdoors", 5.0, 60.0, 7, 7);
    }

    @Test
    public void theHomeSideCoversPushesAndMisses(){
        assertEquals(1, MarketEfficiency.homeCover(game("2020", 1, "BUF", "MIA", 24, 17, 3)));
        assertEquals(0, MarketEfficiency.homeCover(game("2020", 1, "BUF", "MIA", 24, 17, 7)), "won by exactly the spread: a push");
        assertEquals(-1, MarketEfficiency.homeCover(game("2020", 1, "BUF", "MIA", 24, 17, 10)));
        assertEquals(1, MarketEfficiency.over(game("2020", 1, "BUF", "MIA", 24, 21, 3)), "45 points over a 44 total");
    }

    @Test
    public void ratingsFromPastGamesRecoverWhoIsBetter(){
        List<NflverseGames.Game> past = new ArrayList<>();
        for(int w = 1; w <= 12; w++){
            past.add(game("2020", w, w % 2 == 0 ? "KC" : "NYJ", w % 2 == 0 ? "NYJ" : "KC",
                    w % 2 == 0 ? 30 : 17, w % 2 == 0 ? 17 : 30, 0));
        }
        var r = MarketEfficiency.ratings(past, MarketEfficiency.weekIndex(past.get(past.size() - 1)) + 1, 10, 1);
        assertTrue(r.get("KC") - r.get("NYJ") > 10, "KC wins by 13 home and away: rated about 13 better, shrunk a little");
        assertEquals(0, r.get("__home"), 0.5, "home and away results the same: no home field");
    }

    static JsonObject json(String s){
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    public void kalshisThreeContractShapesParse(){
        KalshiMarkets.Contract th = KalshiMarkets.parse(json("{\"ticker\":\"T\",\"event_ticker\":\"KXNFLRECYDS-26SEP28PHICHI\","
                + "\"strike_type\":\"greater\",\"floor_strike\":49.5,\"yes_sub_title\":\"Cole Kmet: 50+\",\"yes_bid_dollars\":\"0.0300\","
                + "\"yes_ask_dollars\":\"0.0900\",\"no_ask_dollars\":\"0.9700\",\"custom_strike\":{\"football_player\":\"p\",\"football_team\":\"t\"}}"),
                "KXNFLRECYDS");
        assertEquals(KalshiMarkets.Kind.THRESHOLD, th.kind());
        assertEquals(50, th.strike(), "floor 49.5, strike type greater: 50 yards pays");
        assertEquals("receiving yards", th.stat());
        assertEquals(0.09, th.yesAsk(), 1e-12);
        KalshiMarkets.Contract ladder = KalshiMarkets.parse(json("{\"ticker\":\"L\",\"event_ticker\":\"E-G\",\"strike_type\":\"custom\","
                + "\"custom_strike\":{\"Payout Per Unit\":\"$0.0025\",\"scalar_cap\":\"400\",\"football_player\":\"p\"}}"), "KXNFLLADDERRECYDS");
        assertEquals(KalshiMarkets.Kind.LADDER, ladder.kind());
        assertEquals(0.0025, ladder.perUnit(), 1e-12);
        assertEquals(400, ladder.cap(), 1e-12);
        String schedule = "0–9 yards, $0.0000; 10–19, $0.0001; 20–29, $0.0010; 30–39, $0.0033; 40–49, $0.0080; 50–59, $0.0156; "
                + "60–69, $0.0270; 70–79, $0.0428; 80–89, $0.0640; 90–99, $0.0911; 100–109, $0.1250; 110–119, $0.1663; 120–129, $0.2160; "
                + "130–139, $0.2746; 140–149, $0.3430; 150–159, $0.4218; 160–169, $0.5120; 170–179, $0.6141; 180–189, $0.7290; "
                + "190–199, $0.8573; and 200 or more, $1.0000.";
        KalshiMarkets.Contract esc = KalshiMarkets.parse(json("{\"ticker\":\"X\",\"event_ticker\":\"E-G\",\"strike_type\":\"custom\","
                + "\"custom_strike\":{\"Payout Per Unit\":\"" + schedule + "\",\"Payout formula\":\"floor(10000 x (y / 200)^3) / 10000\","
                + "\"scalar_cap\":\"200\",\"scalar_step\":\"10\",\"football_player\":\"p\"}}"), "KXNFLESCALATORRECYDS");
        assertEquals(KalshiMarkets.Kind.ESCALATOR, esc.kind(), "its Payout Per Unit is a schedule, not a number");
        assertEquals(21, esc.schedule().length);
        assertEquals(0.1250, esc.schedule()[10], 1e-12);
        assertEquals(1.0, esc.schedule()[20], 1e-12, "the trailing full stop is not part of the number");
        assertEquals("touchdowns", KalshiMarkets.statOf("KXNFLTD"));
    }

    @Test
    public void thresholdStripsBoundALadderAndAnEscalatorInEveryOutcome(){
        int[] strikes = {25, 40, 50, 60, 80};
        KalshiArb.Payoff ladder = KalshiArb.ladder(0.0025, 400);
        double[] sched = new double[21];
        for(int k = 0; k <= 20; k++){
            sched[k] = Math.floor(10000 * Math.pow(k * 10 / 200.0, 3)) / 10000;
        }
        KalshiArb.Payoff esc = KalshiArb.escalator(sched, 10);
        for(KalshiArb.Payoff p : List.of(ladder, esc)){
            KalshiArb.Strip up = KalshiArb.bound(p, strikes, true);
            KalshiArb.Strip low = KalshiArb.bound(p, strikes, false);
            for(int y = -5; y <= 450; y++){
                double hi = up.constant();
                double lo = low.constant();
                for(int i = 0; i < strikes.length; i++){
                    if(y >= strikes[i]){
                        hi += up.weights()[i];
                        lo += low.weights()[i];
                    }
                }
                double v = p.value(y);
                assertTrue(lo <= v + 1e-12 && v <= hi + 1e-12, "outcome " + y + ": " + lo + " <= " + v + " <= " + hi);
            }
        }
        assertEquals(24 * 0.0025, KalshiArb.bound(ladder, strikes, true).constant(), 1e-12, "yards 1-24 lie below every strike: bought as cash");
    }

    static KalshiMarkets.Contract threshold(int strike, double bid, double ask){
        return new KalshiMarkets.Contract("T" + strike, "KXNFLRECYDS", "E-G", KalshiMarkets.Kind.THRESHOLD, "p", "A: " + strike + "+", "t",
                "receiving yards", strike, null, null, null, 0, bid, ask, 1 - ask, 1 - bid, 100, 100, "x");
    }

    @Test
    public void thresholdsOutOfOrderAreAnArbitrageAndCoherentOnesAreNot(){
        // 20+ offered at 0.40 while 30+ bids 0.50: YES 20+ and NO 30+ cost 0.90 and pay at least 1
        List<KalshiArb.Hit> hits = KalshiArb.thresholds("G", "A", List.of(threshold(20, 0.38, 0.40), threshold(30, 0.50, 0.52)));
        assertEquals(1, hits.size());
        assertEquals(1 - 0.40 - 0.50 - KalshiArb.fee(0.40) - KalshiArb.fee(0.50), hits.get(0).edge(), 1e-12);
        assertTrue(KalshiArb.thresholds("G", "A", List.of(threshold(20, 0.60, 0.62), threshold(30, 0.40, 0.42))).isEmpty());
    }

    @Test
    public void thePropModelsNeighboursPriceThresholdsMonotonically(){
        List<PropModel.Pair> pairs = new ArrayList<>();
        for(int i = 0; i < 3000; i++){
            double m = 20 + (i % 60);
            double y = m * (0.2 + 1.6 * FeatureScreen.placebo(3, "y" + i) * FeatureScreen.placebo(3, "y" + i));
            pairs.add(new PropModel.Pair(m, y, "20" + (18 + i % 8)));
        }
        PropModel.Fitted f = new PropModel.Fitted(pairs);
        double last = 1;
        for(int k = 0; k <= 150; k += 10){
            double p = f.over(50, k, null);
            assertTrue(p <= last + 1e-12, "P(stat >= k) cannot rise with k");
            assertTrue(p > 0 && p < 1, "the pseudo-count keeps it off 0 and 1");
            last = p;
        }
        assertEquals(f.over(50, 30, null) > f.over(50, 30, "2018") - 0.1, true);
    }

    @Test
    public void aContractAtTheAskPaysItsFeeWhateverHappens(){
        assertEquals(1 - 0.40 - KalshiArb.fee(0.40), KalshiSettle.profit(true, 0.40, true), 1e-12);
        assertEquals(-0.40 - KalshiArb.fee(0.40), KalshiSettle.profit(true, 0.40, false), 1e-12);
        assertEquals(1 - 0.30 - KalshiArb.fee(0.30), KalshiSettle.profit(false, 0.30, false), 1e-12, "NO wins on a miss");
        double[] c = KalshiSettle.clustered(List.of(new double[]{1, 0}, new double[]{1, 0}, new double[]{-1, 1}, new double[]{-1, 1}));
        assertEquals(0, c[0], 1e-12);
        assertEquals(Math.sqrt(2.0 / 1 * 8 / 16), c[1], 1e-12, "two clusters of +2 and -2: G/(G-1) * 8 / n^2");
        assertEquals(0.07 * 0.5 * 0.5, KalshiArb.fee(0.5), 1e-12);
    }

    @Test
    public void propShapeCountsTiesHalfEachWay(){
        double[] s = PropShape.shape(List.of(new double[]{10, 5}, new double[]{10, 10}, new double[]{10, 20}, new double[]{10, 0}));
        assertEquals(0.625, s[0], 1e-12, "two below, one tie");
        assertEquals(0.375, s[1], 1e-12);
        assertEquals(35.0 / 40, s[4], 1e-12, "mean outcome over mean projection");
    }
}
