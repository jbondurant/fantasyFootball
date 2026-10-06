import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import PlayerImportAndSetup.Position;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The market-against-Sleeper race on data whose answer is known: Kalshi's
 * event dates, the implied projection recovering the projection that made
 * the prices, and the comparison favouring whichever side is exactly right.
 */
public class MarketVsSleeperTest {

    @Test
    public void eventTickersNameTheirDates(){
        assertEquals(LocalDate.of(2025, 10, 5), KalshiHistory.dateOf("KXNFLRECYDS-25OCT05MINCLE"));
        assertEquals(LocalDate.of(2026, 2, 8), KalshiHistory.dateOf("KXNFLRECYDS-26FEB08SEANE"));
        assertEquals("touchdowns", KalshiHistory.stat("KXNFLANYTD"));
        assertEquals("receiving yards", KalshiHistory.stat("KXNFLRECYDS"));
    }

    /** Outcomes spread around their projection, in seasons 2018-2024 (2025 is what the race leaves out). */
    static PropModel.Fitted fitted(){
        List<PropModel.Pair> pairs = new ArrayList<>();
        for(int i = 0; i < 6000; i++){
            double m = 5 + (i % 120);
            double u = FeatureScreen.placebo(9, "u" + i);        // [-1, 1]
            pairs.add(new PropModel.Pair(m, m * (1 + 0.6 * u), "20" + (18 + i % 7)));
        }
        return new PropModel.Fitted(pairs);
    }

    @Test
    public void theImpliedProjectionRecoversTheOneThatMadeThePrices(){
        PropModel.Fitted f = fitted();
        for(double truth : new double[]{20, 45, 80}){
            List<double[]> quotes = new ArrayList<>();
            for(double mult : new double[]{0.6, 1.0, 1.4}){
                double k = Math.round(truth * mult);
                quotes.add(new double[]{k, f.over(truth, k, "2025")});
            }
            double m = MarketVsSleeper.implied(f, quotes);
            assertEquals(truth, m, truth * 0.06, "prices made at " + truth + " read back as " + m);
        }
    }

    static MarketVsSleeper.Case c(int week, double sleeper, double market, double actual){
        return new MarketVsSleeper.Case(week, "p", Position.WR, "receiving yards", sleeper, sleeper, market, actual, 3);
    }

    @Test
    public void theRaceFavoursWhicheverSideIsRight(){
        List<MarketVsSleeper.Case> marketRight = new ArrayList<>();
        List<MarketVsSleeper.Case> sleeperRight = new ArrayList<>();
        for(int w = 1; w <= 10; w++){
            for(int i = 0; i < 20; i++){
                double actual = 20 + i * 3 + w;
                marketRight.add(c(w, actual + (i % 2 == 0 ? 8 : -8), actual, actual));
                sleeperRight.add(c(w, actual, actual + (i % 2 == 0 ? 8 : -8), actual));
            }
        }
        double[] m = MarketVsSleeper.compare(marketRight);
        assertEquals(0, m[2], 1e-9, "market RMSE zero");
        assertEquals(64, m[6], 1e-9, "Sleeper's squared error exceeds the market's by 64 every time");
        assertEquals(1, m[8], 1e-9, "all weight on the market");
        assertEquals(0, m[9], 1e-9, "and the blend fitted leaving each week out is exact");
        double[] s = MarketVsSleeper.compare(sleeperRight);
        assertEquals(-64, s[6], 1e-9);
        assertEquals(0, s[8], 1e-9, "no weight on a market that only adds noise");
    }
}
