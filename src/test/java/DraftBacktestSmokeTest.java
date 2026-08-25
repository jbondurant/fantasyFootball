import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The gate itself, kept green: the fitted league layer must keep beating raw
 * ADP on the held-out season, and the survival numbers the keeper and
 * wait-or-take decisions rest on must stay calibrated.
 */
@Tag("smoke")
class DraftBacktestSmokeTest {

    @Test
    void theLeagueBiasLayerBeatsRawAdpOnTheHeldOutSeason(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        DraftBacktest.Season season = new DraftBacktest.Season(configuration, "2025");
        ManagerProfiles profiles = ManagerProfiles.fitThroughSeason(configuration, 2024);

        List<Integer> adpOnly = DraftBacktest.pickRanks(season, profiles, 0);
        List<Integer> withBias = DraftBacktest.pickRanks(season, profiles, 1);

        double adpTop5 = adpOnly.stream().filter(r -> r < 5).count() / (double) adpOnly.size();
        double biasTop5 = withBias.stream().filter(r -> r < 5).count() / (double) withBias.size();

        System.out.printf("top-5: adp %.1f%%, +league bias %.1f%%%n", adpTop5 * 100, biasTop5 * 100);
        Assertions.assertTrue(biasTop5 > adpTop5,
                "the fitted league layer stopped earning its keep");
    }

    @Test
    void survivalNumbersStayCalibratedOnTheHeldOutSeason(){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        DraftBacktest.Season season = new DraftBacktest.Season(configuration, "2025");
        ManagerProfiles profiles = ManagerProfiles.fitThroughSeason(configuration, 2024);

        double[][] buckets = new double[10][3];
        double error = DraftBacktest.calibrationError(configuration, season, profiles,
                AvailabilityModel.PICK_STANDARD_DEVIATION, AvailabilityModel.VALUE_WEIGHT,
                500, buckets);

        System.out.printf("weighted calibration error %.2f%%%n", error * 100);
        Assertions.assertTrue(error < 0.03, "weighted error " + error);
        for(int b = 0; b < 10; b++){
            if(buckets[b][2] < 20){
                continue;
            }
            double gap = Math.abs(buckets[b][0] / buckets[b][2] - buckets[b][1] / buckets[b][2]);
            Assertions.assertTrue(gap < 0.12,
                    "bucket " + b * 10 + "-" + (b * 10 + 10) + "% is off by " + Math.round(gap * 100)
                            + " points - the MODEL.md gate is within 10, plus draw noise");
        }
    }
}
