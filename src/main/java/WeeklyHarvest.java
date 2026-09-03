import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 0: pull every week of every season, then prove the pull is sound.
 *
 * The harvest itself is trivial - five seasons of eighteen weeks, cached
 * forever. The gate is the point. If the weekly points do not sum to the
 * season totals the repo has been using all along, then one of the two feeds
 * is wrong and everything built on the weekly data would inherit it. Nothing
 * downstream should run until this reconciles.
 *
 *   ./gradlew run -Pmain=WeeklyHarvest
 */
public class WeeklyHarvest {

    static final String[] SEASONS = {"2021", "2022", "2023", "2024", "2025"};

    public static void main(String[] args){
        System.out.printf("%nHARVEST: %d seasons x %d weeks%n%n", SEASONS.length,
                WeeklyActuals.WEEKS);
        System.out.printf("%-8s %8s %10s %10s %12s%n", "SEASON", "weeks", "scorers",
                "played", "empty weeks");
        for(String season : SEASONS){
            int scorers = 0;
            int played = 0;
            int empty = 0;
            for(int week = 1; week <= WeeklyActuals.WEEKS; week++){
                Map<String, Double> points = WeeklyActuals.pointsBySleeperID(season, week);
                Set<String> up = WeeklyActuals.playedBySleeperID(season, week);
                scorers += points.size();
                played += up.size();
                if(points.isEmpty()){
                    empty++;
                }
            }
            System.out.printf("%-8s %8d %10d %10d %12d%n", season, WeeklyActuals.WEEKS,
                    scorers, played, empty);
        }

        System.out.printf("%n%nTHE GATE: do the weeks sum to the season?%n%n");
        System.out.printf("%-8s %9s %12s %12s %12s   %s%n", "SEASON", "matched",
                "mean |diff|", "worst diff", "over 1 pt", "verdict");
        boolean allClear = true;
        for(String season : SEASONS){
            Map<String, Double> weekly = WeeklyActuals.seasonSumBySleeperID(season);
            Map<String, Double> total = HistoricalActuals.pointsBySleeperID(season);
            List<Double> diffs = new ArrayList<>();
            double worst = 0;
            String worstName = "";
            int over = 0;
            for(Map.Entry<String, Double> entry : total.entrySet()){
                Double sum = weekly.get(entry.getKey());
                if(sum == null){
                    continue;
                }
                double diff = Math.abs(sum - entry.getValue());
                diffs.add(diff);
                if(diff > 1.0){
                    over++;
                }
                if(diff > worst){
                    worst = diff;
                    Player player = Player.getPlayerFromSIDV2(entry.getKey());
                    worstName = player == null ? entry.getKey()
                            : player.firstName + " " + player.lastName;
                }
            }
            double mean = diffs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            boolean clear = mean < 0.5 && over < diffs.size() * 0.02;
            allClear &= clear;
            System.out.printf("%-8s %9d %12.3f %12.1f %12d   %s%n", season, diffs.size(),
                    mean, worst, over, clear ? "RECONCILES" : "MISMATCH - " + worstName);
        }

        System.out.println(allClear
                ? "\nGATE PASSED. The weekly feed and the season feed agree, so the"
                  + " weekly\ndata can be trusted as the basis for the starter-sum"
                  + " objective."
                : "\nGATE FAILED. Do not build on this until it is understood - a"
                  + " weekly feed\nthat disagrees with the season totals would poison"
                  + " everything downstream.");
    }
}
