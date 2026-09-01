import PlayerImportAndSetup.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The challengers to the pairwise odds surface, tested on their construction
 * rather than on football.
 *
 * Two of these are load-bearing for the conclusion and not merely hygiene.
 *
 * The CELL AGGREGATION claims to be exact: 65,855 pairs are collapsed to ~5,300
 * (position, early, late) rows carrying counts, and every family is then fitted
 * on those. If that were an approximation, every number in OddsFamilies would be
 * measuring the approximation rather than the family, so it is checked against
 * the raw-pair estimator PairwiseOdds already ships.
 *
 * The SPREAD FITTER claims a null result: sigma(r) = r^tau comes back at tau = 0
 * at three positions of four. A fitter that cannot find a spread would return
 * that too, and the two look identical from outside. So it is shown recovering a
 * PLANTED tau from data built to contain one.
 */
class OddsFamiliesTest {

    /** A decreasing latent strength, and a spread that grows with rank. */
    private static double planted(int rank){
        return -Math.log(rank);
    }

    /**
     * Expected-count cells from a known (strength, spread). No sampling, so the
     * test asks what the estimator does with a clean signal and not what it does
     * with one particular random draw.
     */
    private static OddsFamilies.Cells plant(int cap, double tau, double perCell){
        List<int[]> keys = new ArrayList<>();
        for(int e = 1; e <= cap; e++){
            for(int l = e + 1; l <= cap; l++){
                keys.add(new int[]{e, l});
            }
        }
        int[] early = new int[keys.size()];
        int[] late = new int[keys.size()];
        double[] count = new double[keys.size()];
        double[] wins = new double[keys.size()];
        for(int i = 0; i < keys.size(); i++){
            early[i] = keys.get(i)[0];
            late[i] = keys.get(i)[1];
            count[i] = perCell;
            double z = (planted(late[i]) - planted(early[i]))
                    / OddsFamilies.scale(early[i], late[i], tau);
            wins[i] = perCell * OddsFamilies.sigmoid(z);
        }
        return new OddsFamilies.Cells(early, late, count, wins);
    }

    // ================================================== the cell aggregation

    /**
     * Collapsing pairs to cells must not change the fit at all. Same calibration
     * slope, to the last place the two Newtons can agree on.
     */
    @Test
    void cellsAreTheSameEvidenceAsPairs(){
        int cap = 24;
        List<PairwiseOdds.Pair> pairs = new ArrayList<>();
        for(int season = 0; season < 6; season++){
            for(int e = 1; e <= cap; e++){
                for(int l = e + 1; l <= cap; l++){
                    // deterministic, and not a function of the model being fitted
                    boolean lateWon = ((season * 31 + e * 7 + l * 13) % 10) < 3;
                    pairs.add(new PairwiseOdds.Pair(season, Position.RB, e, l, lateWon));
                }
            }
        }
        double[] curve = new double[cap + 1];
        for(int r = 1; r <= cap; r++){
            curve[r] = planted(r);
        }
        double[] rawGap = new double[pairs.size()];
        boolean[] rawWon = new boolean[pairs.size()];
        for(int i = 0; i < pairs.size(); i++){
            rawGap[i] = curve[pairs.get(i).late()] - curve[pairs.get(i).early()];
            rawWon[i] = pairs.get(i).lateWon();
        }
        double onPairs = PairwiseOdds.fitSlope(rawGap, rawWon);

        OddsFamilies.Cells cells = OddsFamilies.cellsOf(pairs, Position.RB, cap);
        double onCells = OddsFamilies.fitSlopeWeighted(
                OddsFamilies.spreadGaps(curve, cells, 0), cells.wins(), cells.count());

        assertEquals(onPairs, onCells, 1e-9,
                "the cell aggregation must be exact, or every family below is"
                        + " measuring the aggregation and not itself");
    }

    // ====================================================== the spread fitter

    /**
     * The null result is only worth reading if the fitter could have found
     * something. Plant tau = 0.5 and it has to come back.
     */
    @Test
    void theSpreadFitterRecoversAPlantedSpread(){
        int cap = 60;
        double[] start = new double[cap + 1];
        for(int r = 1; r <= cap; r++){
            start[r] = planted(r);
        }
        OddsFamilies.Joint fit = OddsFamilies.fitJoint(start, plant(cap, 0.5, 400), cap);
        assertEquals(0.5, fit.tau(), 0.12,
                "a planted spread must be recovered, or a fitted tau of zero says"
                        + " nothing about whether a spread is there");
    }

    /** And it must not invent one where none was planted. */
    @Test
    void theSpreadFitterFindsNothingWhenNothingIsThere(){
        int cap = 60;
        double[] start = new double[cap + 1];
        for(int r = 1; r <= cap; r++){
            start[r] = planted(r);
        }
        OddsFamilies.Joint fit = OddsFamilies.fitJoint(start, plant(cap, 0.0, 400), cap);
        assertTrue(fit.tau() < 0.12,
                "no spread was planted and tau came back at " + fit.tau());
    }

    /**
     * The curve is refitted at every tau by local scoring - Newton, then
     * pool-adjacent-violators, then the log-rank smoother, iterated. Twenty
     * rounds is asserted to be a fixed point; if it is not, the tau profile is
     * reading iteration noise.
     */
    @Test
    void theLocalScoringIterationHasSettledByTwentyRounds(){
        int cap = 60;
        double[] start = new double[cap + 1];
        for(int r = 1; r <= cap; r++){
            start[r] = planted(r);
        }
        OddsFamilies.Cells cells = plant(cap, 0.4, 400);
        double[] twenty = OddsFamilies.jointStrength(start, cells, cap, 0.4, 20);
        double[] many = OddsFamilies.jointStrength(start, cells, cap, 0.4, 200);
        double worst = 0;
        for(int r = 1; r <= cap; r++){
            worst = Math.max(worst, Math.abs(twenty[r] - many[r]));
        }
        assertTrue(worst < 1e-3, "twenty rounds and two hundred still differ by "
                + worst + ", so the fit is not at a fixed point");
    }

    // ======================================================= the boosted curve

    /**
     * Boosting is free to hand back a curve that turns back up in the tail. The
     * projection is what keeps P(r, r) = 0.5, antisymmetry and the rank order,
     * so it is tested rather than trusted.
     */
    @Test
    void theBoostedStrengthCurveComesBackNonIncreasing(){
        int cap = 60;
        OddsFamilies.Cells cells = plant(cap, 0.0, 60);
        double[] curve = OddsFamilies.boostedCurve(cells, cap, OddsFamilies.TREES,
                OddsFamilies.DEPTH, OddsFamilies.LEARNING_RATE);
        for(int r = 2; r <= cap; r++){
            assertTrue(curve[r] <= curve[r - 1] + 1e-12,
                    "the boosted curve turned back up at rank " + r + ": "
                            + curve[r - 1] + " then " + curve[r]);
        }
    }

    /** And it has to actually learn the shape, not just be monotone. */
    @Test
    void theBoostedStrengthCurveTracksThePlantedOne(){
        int cap = 60;
        OddsFamilies.Cells cells = plant(cap, 0.0, 400);
        double[] curve = OddsFamilies.boostedCurve(cells, cap, OddsFamilies.TREES,
                OddsFamilies.DEPTH, OddsFamilies.LEARNING_RATE);
        double alpha = OddsFamilies.fitSlopeWeighted(
                OddsFamilies.spreadGaps(curve, cells, 0), cells.wins(), cells.count());
        double worst = 0;
        for(int e : new int[]{1, 6, 12, 24, 48}){
            for(int l : new int[]{6, 12, 24, 48, 60}){
                if(l <= e){
                    continue;
                }
                double planted = OddsFamilies.sigmoid(planted(l) - planted(e));
                double fitted = OddsFamilies.sigmoid(alpha * (curve[l] - curve[e]));
                worst = Math.max(worst, Math.abs(planted - fitted));
            }
        }
        assertTrue(worst < 0.015, "the boosted curve is off the planted one by "
                + worst + " at its worst");
    }

    // ============================================== the structural properties

    /** The audit has to hold for a latent model, or it is measuring nothing. */
    @Test
    void aLatentModelPassesEveryStructuralProperty(){
        int cap = 60;
        double[] start = new double[cap + 1];
        for(int r = 1; r <= cap; r++){
            start[r] = planted(r);
        }
        java.util.Map<Position, double[]> curves =
                new java.util.EnumMap<>(Position.class);
        java.util.Map<Position, Double> alphas =
                new java.util.EnumMap<>(Position.class);
        java.util.Map<Position, Double> taus = new java.util.EnumMap<>(Position.class);
        for(Position position : PairwiseOdds.POSITIONS){
            int limit = PairwiseOdds.CAP.get(position);
            double[] curve = new double[limit + 1];
            for(int r = 1; r <= limit; r++){
                curve[r] = planted(r);
            }
            curves.put(position, curve);
            alphas.put(position, 1.0);
            taus.put(position, 0.0);
        }
        OddsFamilies.Properties property =
                OddsFamilies.properties(OddsFamilies.latentModel(curves, alphas, taus));
        assertEquals(0.0, property.selfPlay(), 1e-12);
        assertEquals(0.0, property.antisymmetry(), 1e-12);
        assertEquals(0, property.orderBreaks());
        assertEquals(0, property.monotoneBreaks());
        assertEquals(0, property.strongBreaks());
    }

    /**
     * And it has to CATCH a model that breaks them, or "the surface family
     * violates every property" is an untested claim about the audit rather than
     * about the surface.
     */
    @Test
    void theAuditCatchesAnIncoherentSurface(){
        OddsFamilies.Properties property = OddsFamilies.properties(
                (position, early, late) -> 0.30 + 0.004 * late);
        assertTrue(property.selfPlay() > 0.05,
                "a model with P(r, r) far from a coin flip was waved through");
        assertTrue(property.antisymmetry() > 0.05,
                "a model that is not antisymmetric was waved through");
        assertTrue(property.orderBreaks() > 0,
                "a model making the deeper man the favourite was waved through");
        assertTrue(property.monotoneBreaks() > 0,
                "a model rating a deeper man higher against the same opponent was"
                        + " waved through");
    }

    /**
     * The spread family's ONE risk: dividing by a scale that grows with rank can
     * turn the surface back up, so a deeper man reads as a better bet against the
     * same opponent than a shallower one. Nothing forbids it, so section 3 has to
     * be able to see it - here it is, provoked.
     */
    @Test
    void aLargeSpreadCanBreakMonotonicityAndTheAuditSeesIt(){
        java.util.Map<Position, double[]> curves =
                new java.util.EnumMap<>(Position.class);
        java.util.Map<Position, Double> alphas =
                new java.util.EnumMap<>(Position.class);
        java.util.Map<Position, Double> taus = new java.util.EnumMap<>(Position.class);
        for(Position position : PairwiseOdds.POSITIONS){
            int limit = PairwiseOdds.CAP.get(position);
            double[] curve = new double[limit + 1];
            for(int r = 1; r <= limit; r++){
                curve[r] = planted(r);
            }
            curves.put(position, curve);
            alphas.put(position, 1.0);
            taus.put(position, 1.0);           // sigma(r) = r, far past anything fitted
        }
        OddsFamilies.Properties property =
                OddsFamilies.properties(OddsFamilies.latentModel(curves, alphas, taus));
        assertEquals(0.0, property.selfPlay(), 1e-12,
                "a spread must still give a man an even chance against himself");
        assertEquals(0.0, property.antisymmetry(), 1e-12,
                "a spread must still be antisymmetric");
        assertEquals(0, property.orderBreaks(),
                "a spread must still favour the better rank");
        assertTrue(property.monotoneBreaks() > 0,
                "a spread this large has to break monotonicity somewhere, and the"
                        + " audit did not notice");
    }
}
