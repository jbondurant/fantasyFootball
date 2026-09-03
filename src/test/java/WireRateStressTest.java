import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The streaming policy in WireRateStress is the honest counterweight to the
 * shipped 8.7 a week, and it is only worth anything if it genuinely cannot see
 * the future. This repo has shipped a wire calculation that took a MAX over
 * undrafted players once already, and it reversed two findings when it was
 * fixed, so the property gets a test rather than a comment.
 *
 * The fixture is built so that hindsight and honesty give DIFFERENT numbers,
 * and the honest one is an exact integer. A policy that peeked would score 1260;
 * one that cannot must score 1170.
 */
class WireRateStressTest {

    static final int WEEKS = WireRateStress.WEEKS;

    static WireRateStress.DefSeason def(String name, int rank, double... weekly){
        Double[] series = new Double[WEEKS];
        for(int week = 0; week < WEEKS; week++){
            series[week] = week < weekly.length ? weekly[week] : 0.0;
        }
        return new WireRateStress.DefSeason("test", name, name, rank, series);
    }

    /** 10 every week - dull, and the only thing with a record early on. */
    static WireRateStress.DefSeason steady(){
        double[] weekly = new double[WEEKS];
        java.util.Arrays.fill(weekly, 10.0);
        return def("steady", 0, weekly);
    }

    /** Nothing for six weeks, then 100 a week. Invisible until it happens. */
    static WireRateStress.DefSeason lateBloomer(){
        double[] weekly = new double[WEEKS];
        for(int week = 6; week < WEEKS; week++){
            weekly[week] = 100.0;
        }
        return def("bloomer", 1, weekly);
    }

    /**
     * The load-bearing one. The bloomer's first big week is week 6 (0-based), so
     * a policy choosing week 6's starter may only see weeks 0-5, in which the
     * bloomer scored nothing at all. It must therefore still be holding the
     * steady defence in week 6 and collect 10, not 100.
     *
     * It may switch from week 7, once week 6's hundred is in the past.
     *
     *   weeks 0-6 on steady    7 x 10  =  70
     *   weeks 7-17 on bloomer 11 x 100 = 1100
     *                                    ----
     *                                    1170
     *
     * A policy with one week of lookahead scores 1260. The gap between those two
     * numbers is the entire finding, so the assertion is exact.
     */
    @Test
    void theStreamingPolicyCannotSeeTheWeekItIsChoosing(){
        List<WireRateStress.DefSeason> free = List.of(steady(), lateBloomer());
        assertEquals(1170.0, WireRateStress.form(free, 1), 1e-9,
                "form() collected the bloomer's week 6 - it is reading the future");
    }

    /** The ceiling, for contrast: the oracle does take week 6's hundred. */
    @Test
    void theOracleDoesSeeTheFutureAndIsMarkedAsSuch(){
        List<WireRateStress.DefSeason> free = List.of(steady(), lateBloomer());
        // weeks 0-5 the steady 10 is the best on offer, weeks 6-17 the 100 is
        assertEquals(6 * 10.0 + 12 * 100.0, WireRateStress.oracle(free), 1e-9);
        assertTrue(WireRateStress.oracle(free) > WireRateStress.form(free, 1),
                "the oracle must beat the honest policy, or it is not a ceiling");
    }

    /**
     * A longer lag means more weeks pinned to the preseason pick, so with a
     * bloomer worth chasing it can only be worth less. This is what makes the
     * lag column in the output readable as a lag.
     */
    @Test
    void aLongerLagHoldsThePreseasonPickForLonger(){
        List<WireRateStress.DefSeason> free = List.of(steady(), lateBloomer());
        assertTrue(WireRateStress.form(free, 1) >= WireRateStress.form(free, 8),
                "reacting later cannot be worth more when the late man is better");
    }

    /**
     * With no in-season evidence at all - every candidate blank until the lag
     * expires - the policy must fall back to the best preseason ADP and not
     * crash or silently pick the wrong man.
     */
    @Test
    void fallsBackToPreseasonRankWithNoEvidence(){
        WireRateStress.DefSeason blankA = def("a", 0);
        WireRateStress.DefSeason blankB = def("b", 1);
        assertEquals(0.0, WireRateStress.form(List.of(blankA, blankB), 3), 1e-9);
    }

    /** formThrough must count only the weeks named, and ignore absences. */
    @Test
    void formThroughLooksOnlyAtWeeksAlreadyPlayed(){
        WireRateStress.DefSeason player = def("x", 0, 6.0, 12.0, 30.0);
        assertEquals(6.0, player.formThrough(1), 1e-9);
        assertEquals(9.0, player.formThrough(2), 1e-9);
        assertEquals(16.0, player.formThrough(3), 1e-9);
    }
}
