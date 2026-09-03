import PlayerImportAndSetup.Position;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** The two estimators the objective audit rests on, offline. */
class ObjectiveAuditTest {

    // ---- the replacement-rank arithmetic ---------------------------------

    private static List<ReplacementRanks.Taken> draft(Position... order){
        List<ReplacementRanks.Taken> picks = new ArrayList<>();
        for(int i = 0; i < order.length; i++){
            picks.add(new ReplacementRanks.Taken(i + 1, order[i]));
        }
        return picks;
    }

    @Test
    void theRankIsOneMoreThanTheNumberDrafted(){
        // three quarterbacks drafted means QB4 is the man left for me
        List<ReplacementRanks.Taken> one = draft(Position.QB, Position.RB, Position.QB,
                Position.WR, Position.QB, Position.RB);

        Map<Position, Integer> ranks = ReplacementRanks.ranksFrom(List.of(one), 1.0);

        Assertions.assertEquals(4, ranks.get(Position.QB));
        Assertions.assertEquals(3, ranks.get(Position.RB));
        Assertions.assertEquals(2, ranks.get(Position.WR));
    }

    @Test
    void aPositionNobodyDraftsFallsToRankOne(){
        Map<Position, Integer> ranks = ReplacementRanks.ranksFrom(
                List.of(draft(Position.RB, Position.RB)), 1.0);

        // nobody took a defence, so the best defence alive is DEF1
        Assertions.assertEquals(1, ranks.get(Position.DEF));
    }

    @Test
    void theFractionCutsTheBoardOffEarly(){
        // defences go last, which is the whole point: counting the whole draft
        // says DEF3, counting only the first half says DEF1
        List<ReplacementRanks.Taken> one = draft(Position.RB, Position.WR, Position.RB,
                Position.WR, Position.DEF, Position.DEF);

        Assertions.assertEquals(3, ReplacementRanks.ranksFrom(List.of(one), 1.0)
                .get(Position.DEF));
        Assertions.assertEquals(1, ReplacementRanks.ranksFrom(List.of(one), 0.5)
                .get(Position.DEF));
    }

    @Test
    void countsAreAveragedAcrossDrafts(){
        // four backs in one year, two in the next, is a mean of three -> RB4
        List<ReplacementRanks.Taken> heavy = draft(Position.RB, Position.RB, Position.RB,
                Position.RB);
        List<ReplacementRanks.Taken> light = draft(Position.RB, Position.RB,
                Position.WR, Position.WR);

        Assertions.assertEquals(4,
                ReplacementRanks.ranksFrom(List.of(heavy, light), 1.0).get(Position.RB));
    }

    // ---- the paired error bar ----------------------------------------------

    @Test
    void aConstantShiftHasNoPairedError(){
        // every season moved by exactly the same amount is a real effect, not
        // noise, and the paired test is the only one that can see that
        double[] baseline = {1758, 1666, 1880, 1920, 2055};
        double[] variant = {1808, 1716, 1930, 1970, 2105};

        Assertions.assertEquals(0.0,
                ObjectiveAudit.pairedStandardError(variant, baseline), 1e-9);
    }

    @Test
    void theHugeSeasonSpreadCancelsOut(){
        // the unpaired spread of the baseline is enormous; an identical variant
        // must still report zero difference and zero error
        double[] baseline = {1758, 1666, 1880, 1920, 2055};

        Assertions.assertEquals(0.0,
                ObjectiveAudit.pairedStandardError(baseline.clone(), baseline), 1e-9);
    }

    @Test
    void scatteredDifferencesWidenTheBar(){
        // the real "no shrinkage" row: +0 +256 +124 -80 +0, mean +60
        double[] baseline = {1758, 1666, 1880, 1920, 2055};
        double[] variant = {1758, 1922, 2004, 1840, 2055};

        double se = ObjectiveAudit.pairedStandardError(variant, baseline);

        Assertions.assertEquals(58.9, se, 1.0);
        Assertions.assertTrue(60 < 2 * se,
                "a +60 gain must not clear two of its own standard errors");
    }

    // ---- the trust-coefficient estimator -----------------------------------

    private static List<TrustCoefficient.Gap> gapsWithSlope(double slope, double noise,
                                                            long seed){
        Random random = new Random(seed);
        List<TrustCoefficient.Gap> gaps = new ArrayList<>();
        for(int i = 0; i < 400; i++){
            double x = random.nextGaussian() * 20;
            double y = slope * x + random.nextGaussian() * noise;
            gaps.add(new TrustCoefficient.Gap(Position.RB, x, y, 0, 0));
        }
        return gaps;
    }

    @Test
    void theSlopeRecoversAKnownShrinkage(){
        // a world where a man projected ten above his neighbours finishes six
        // above them is a world with a trust coefficient of 0.6
        TrustCoefficient.Fit fit =
                TrustCoefficient.fit(gapsWithSlope(0.6, 15, 11L), Position.RB);

        Assertions.assertEquals(0.6, fit.raw(), 0.08);
        Assertions.assertEquals(0.6, fit.clamped(), 0.08);
    }

    @Test
    void noiseWidensTheErrorBarWithoutMovingTheEstimate(){
        TrustCoefficient.Fit tight =
                TrustCoefficient.fit(gapsWithSlope(0.6, 5, 3L), Position.RB);
        TrustCoefficient.Fit loose =
                TrustCoefficient.fit(gapsWithSlope(0.6, 60, 3L), Position.RB);

        Assertions.assertTrue(loose.standardError() > tight.standardError() * 3,
                "a twelve-fold noisier world must report a wider error bar");
        Assertions.assertEquals(0.6, loose.raw(), 0.35);
    }

    @Test
    void theClampHidesTheRawValueButTheRawValueSurvives(){
        // the shipped table prints 1.000 for four positions; that must be
        // readable as "the raw slope ran past one", not as a measurement
        TrustCoefficient.Fit fit =
                TrustCoefficient.fit(gapsWithSlope(1.6, 10, 5L), Position.RB);

        Assertions.assertEquals(1.0, fit.clamped(), 1e-9);
        Assertions.assertTrue(fit.raw() > 1.3,
                "raw slope must be reported unclamped, got " + fit.raw());
    }

    @Test
    void anEmptyPositionIsFullyTrustedRatherThanZeroed(){
        // no data must not silently become "believe none of the projection",
        // which would delete a whole position from the objective
        TrustCoefficient.Fit fit = TrustCoefficient.fit(List.of(), Position.DEF);

        Assertions.assertEquals(1.0, fit.clamped(), 1e-9);
    }

    @Test
    void aNegativeSlopeClampsToZeroNotToNegative(){
        // measured DEF at w=6 comes out at -0.00; the objective has no meaning
        // for a negative trust and must floor it
        TrustCoefficient.Fit fit =
                TrustCoefficient.fit(gapsWithSlope(-0.5, 10, 7L), Position.RB);

        Assertions.assertEquals(0.0, fit.clamped(), 1e-9);
        Assertions.assertTrue(fit.raw() < 0);
    }
}
