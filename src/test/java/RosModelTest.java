import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;

/** The least-squares blend weight, its clipping, and the blend itself. */
public class RosModelTest {

    @Test
    public void theWeightFollowsWhicheverEstimateTheOutcomeTracks(){
        List<double[]> tracksA = List.of(new double[]{10, 6, 10}, new double[]{8, 12, 8}, new double[]{15, 9, 15});
        assertEquals(1.0, RosModel.blendWeight(tracksA), 1e-9, "the outcome is the first estimate: all weight on it");
        List<double[]> tracksB = List.of(new double[]{10, 6, 6}, new double[]{8, 12, 12}, new double[]{15, 9, 9});
        assertEquals(0.0, RosModel.blendWeight(tracksB), 1e-9, "the outcome is the second estimate: none on the first");
        List<double[]> halfway = List.of(new double[]{10, 6, 8}, new double[]{8, 12, 10}, new double[]{15, 9, 12});
        assertEquals(0.5, RosModel.blendWeight(halfway), 1e-9, "the outcome sits between them");
    }

    @Test
    public void theWeightIsClippedAndIdenticalEstimatesGiveAHalf(){
        List<double[]> beyond = List.of(new double[]{10, 6, 14}, new double[]{8, 12, 4});
        assertEquals(1.0, RosModel.blendWeight(beyond), 1e-9, "an outcome past the first estimate does not extrapolate past it");
        List<double[]> behind = List.of(new double[]{10, 6, 2}, new double[]{8, 12, 16});
        assertEquals(0.0, RosModel.blendWeight(behind), 1e-9);
        List<double[]> same = List.of(new double[]{10, 10, 12}, new double[]{7, 7, 5});
        assertEquals(0.5, RosModel.blendWeight(same), 1e-9, "two identical estimates: every weight predicts the same");
    }

    @Test
    public void aWeightIsNeverBorrowedFromLaterInTheSeason(){
        assertEquals(1, RosModel.seenFor(1));
        assertEquals(2, RosModel.seenFor(2));
        assertEquals(4, RosModel.seenFor(5), "five weeks seen uses the four-week weight, not the six");
        assertEquals(8, RosModel.seenFor(14));
        assertEquals(1, RosModel.seenFor(0), "before any week the earliest weight, which the caller does not use");
    }

    @Test
    public void thisSeasonsMenAreRankedByAdpWithinPositionAndUnplayedWeeksAreNaN(){
        java.util.Map<String, Double> adp = java.util.Map.of("a", 30.0, "b", 10.0, "c", 20.0);
        java.util.Map<String, PlayerImportAndSetup.Position> pos = java.util.Map.of(
                "a", PlayerImportAndSetup.Position.WR, "b", PlayerImportAndSetup.Position.WR, "c", PlayerImportAndSetup.Position.RB);
        List<java.util.Map<String, Double>> played = List.of(java.util.Map.of("a", 12.0, "c", 7.0), java.util.Map.of("a", 3.0));
        List<InSeasonLearning.Man> men = RosModel.seasonMen("2026", adp, pos, played, 17);
        assertEquals("b", men.get(0).id(), "earliest ADP first");
        assertEquals(1, men.get(0).rank(), "b is the first receiver");
        InSeasonLearning.Man a = men.stream().filter(m -> m.id().equals("a")).findFirst().orElseThrow();
        assertEquals(2, a.rank(), "a is the second receiver");
        assertEquals(12.0, a.week()[0], 1e-9);
        assertEquals(3.0, a.week()[1], 1e-9);
        assertTrue(Double.isNaN(a.week()[2]), "a week not yet played is NaN, not zero");
        InSeasonLearning.Man c = men.stream().filter(m -> m.id().equals("c")).findFirst().orElseThrow();
        assertEquals(1, c.rank(), "c is the first back");
        assertTrue(Double.isNaN(c.week()[1]), "c did not play week 2");
        assertEquals(17, c.week().length);
    }

    @Test
    public void theSeasonClusterBarIsStudentsT(){
        assertEquals(12.706, RosBands.tCritical95(1), 1e-9);
        assertEquals(2.365, RosBands.tCritical95(7), 1e-9, "eight seasons");
        assertEquals(2.042, RosBands.tCritical95(30), 1e-9);
        assertEquals(1.96, RosBands.tCritical95(500), 1e-9);
        assertTrue(Double.isInfinite(RosBands.tCritical95(0)), "one season has no spread to judge by");
        assertTrue(RosBands.belowZero(new double[]{-2.5, 1.0, 8}), "2.5 se below at eight seasons clears 2.365");
        assertFalse(RosBands.belowZero(new double[]{-2.2, 1.0, 8}), "2.2 se does not - the reading a flat two se called earned");
        assertTrue(RosBands.separated(new double[]{2.5, 1.0, 8}));
        assertFalse(RosBands.separated(new double[]{-2.0, Double.NaN, 1}));
    }

    @Test
    public void weeksAreCountedPerManAcrossTheSeason(){
        java.util.Map<String, Integer> n = LeagueWeek.countWeeks(List.of(
                java.util.Map.of("a", 10.0, "b", 8.0), java.util.Map.of("a", 11.0), java.util.Map.of("a", 9.0, "b", 7.0)));
        assertEquals(3, n.get("a"));
        assertEquals(2, n.get("b"), "a week with no row is a week he is not projected to play");
        assertNull(n.get("c"));
    }

    @Test
    public void theBlendIsTheWeightedMean(){
        assertEquals(8.5, RosModel.blend(0.25, 10, 8), 1e-9);
        assertEquals(10, RosModel.blend(1, 10, 8), 1e-9);
        assertEquals(8, RosModel.blend(0, 10, 8), 1e-9);
    }
}
