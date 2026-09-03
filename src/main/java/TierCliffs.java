import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The tier cliffs, empirically: for each season and position, the projected
 * points of the top players and the drop from each to the next. Justin's
 * observation is that recent TE boards had substantial drops between few
 * players - the mechanism that would explain the TE anti-herding (once the
 * cliff is taken, nobody chases) and motivate the drop-off feature in the
 * selection model.
 *
 * Historical seasons use the same raw 4-pt-TD projections the model trains
 * on; the current season uses league scoring, the same asymmetry the points
 * features already carry (QB gaps are understated historically, TE/RB/WR
 * are unaffected).
 *
 *     ./gradlew run -Pmain=TierCliffs
 */
public class TierCliffs {

    private static final int SHOW = 8;

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        List<String> seasons = new ArrayList<>();
        for(String season : configuration.getPreviousSeasons()){
            if(season != null){
                seasons.add(season);
            }
        }
        seasons.sort(Comparator.naturalOrder());

        for(Position position : List.of(Position.TE, Position.QB, Position.RB, Position.WR)){
            System.out.printf("%n%s - top %d by projected points, with the drop to the next:%n%n",
                    position, SHOW);
            for(String season : seasons){
                Map<String, Double> points =
                        HistoricalProjections.rawPointsBySleeperID(configuration, season);
                Map<String, Double> adp =
                        HistoricalProjections.adpBySleeperID(configuration, season);
                printRow(season, top(points, adp, position));
            }
            Map<String, Double> today = SleeperProjections.parseTodaysWebPage();
            printRow(configuration.getSeason() + "*", topToday(today, position));
        }
        System.out.println("\n   * current season under league scoring (6-pt passing TDs);");
        System.out.println("     history is raw 4-pt, the same asymmetry the model's points");
        System.out.println("     features carry. Drops in parentheses.");
    }

    private static List<Double> top(Map<String, Double> points, Map<String, Double> adp,
                                    Position position){
        List<Double> best = new ArrayList<>();
        for(Map.Entry<String, Double> entry : points.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            Double marketed = adp.get(entry.getKey());
            if(player != null && player.position.equals(position)
                    && marketed != null && marketed <= SelectionModel.ADP_LIMIT){
                best.add(entry.getValue());
            }
        }
        best.sort(Comparator.reverseOrder());
        return best;
    }

    private static List<Double> topToday(Map<String, Double> points, Position position){
        List<Double> best = new ArrayList<>();
        for(Map.Entry<String, Double> entry : points.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player != null && player.position.equals(position)
                    && SleeperProjections.adpOf(entry.getKey()) <= SelectionModel.ADP_LIMIT){
                best.add(entry.getValue());
            }
        }
        best.sort(Comparator.reverseOrder());
        return best;
    }

    private static void printRow(String label, List<Double> best){
        StringBuilder row = new StringBuilder(String.format("   %-6s", label));
        for(int i = 0; i < SHOW && i < best.size(); i++){
            row.append(String.format(" %5.0f", best.get(i)));
            if(i + 1 < SHOW && i + 1 < best.size()){
                row.append(String.format("(%3.0f)", best.get(i) - best.get(i + 1)));
            }
        }
        System.out.println(row);
    }

}
