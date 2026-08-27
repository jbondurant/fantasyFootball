import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Is Sleeper's stored per-season ADP a PRESEASON snapshot, or contaminated by
 * the season itself (Justin's concern)? The reference: FFC's per-year ADP,
 * preseason mock-draft aggregation by construction. Per season this prints
 * the Spearman rank correlation between the two, the mean absolute rank gap
 * across the common top 150, and the ten worst disagreements by name - if
 * those names read like "guys who got hurt or broke out mid-season", the
 * snapshot is contaminated; if they read like ordinary market noise, it is
 * preseason and the concern retires.
 *
 *   ./gradlew run -Pmain=AdpProvenanceAudit
 */
public class AdpProvenanceAudit {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        for(String season : configuration.getPreviousSeasons()){
            if(season == null){
                continue;
            }
            Map<String, Double> sleeper;
            try {
                sleeper = HistoricalProjections.adpBySleeperID(configuration, season);
            }
            catch(Exception missing){
                continue;
            }
            Map<String, Double> ffc = FFCalculatorSD.adpBySleeperID(season);
            if(ffc.isEmpty() || sleeper.isEmpty()){
                System.out.printf("%s: feed missing (sleeper %d, ffc %d)%n", season,
                        sleeper.size(), ffc.size());
                continue;
            }

            // common players, ranked within each source
            List<String> common = new ArrayList<>();
            for(String sleeperID : ffc.keySet()){
                if(sleeper.containsKey(sleeperID)){
                    common.add(sleeperID);
                }
            }
            common.sort(Comparator.comparingDouble(ffc::get));
            if(common.size() > 150){
                common = new ArrayList<>(common.subList(0, 150));
            }
            List<String> bySleeper = new ArrayList<>(common);
            bySleeper.sort(Comparator.comparingDouble(sleeper::get));

            int n = common.size();
            double[] ffcRank = new double[n];
            double[] sleeperRank = new double[n];
            double sumAbs = 0;
            double spearman = 0;
            List<double[]> gaps = new ArrayList<>();   // [gap, index]
            for(int i = 0; i < n; i++){
                String sleeperID = common.get(i);
                ffcRank[i] = i + 1;
                sleeperRank[i] = bySleeper.indexOf(sleeperID) + 1;
                double gap = sleeperRank[i] - ffcRank[i];
                sumAbs += Math.abs(gap);
                spearman += gap * gap;
                gaps.add(new double[]{Math.abs(gap), i, gap});
            }
            double rho = 1 - 6 * spearman / ((double) n * (n * n - 1));
            gaps.sort((a, b) -> Double.compare(b[0], a[0]));

            System.out.printf("%n%s: %d common players, spearman %.3f, "
                    + "mean |rank gap| %.1f%n", season, n, rho, sumAbs / n);
            System.out.printf("   worst disagreements (sleeper rank vs ffc rank):%n");
            for(int worst = 0; worst < 10 && worst < gaps.size(); worst++){
                int index = (int) gaps.get(worst)[1];
                Player player = Player.getPlayerFromSIDV2(common.get(index));
                System.out.printf("   %-24s %-3s  sleeper %3.0f  ffc %3.0f  (%+.0f)%n",
                        player.firstName + " " + player.lastName, player.position,
                        sleeperRank[index], ffcRank[index], gaps.get(worst)[2]);
            }
        }
        System.out.println("\nHigh rho + small gaps + noise-looking names = preseason"
                + "\nsnapshot, concern retired. Names that scream mid-season events ="
                + "\ncontamination: switch the historical model input to FFC ADP and"
                + "\nre-run the gates.");
    }
}
