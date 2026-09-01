import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * ARE THE ABSOLUTE-SCALED FEATURES ALIVE AT EVERY POSITION?
 *
 * f9 (the cliff from the best remaining man at a position to the second) and
 * f29 (surplus over replacement) are both divided by CLIFF_CAP, a flat 100
 * points. The positions do not live on the same scale: QB1 is projected for
 * 415 points this year, DEF1 for 106. A five-point gap between TE1 and TE2 is
 * proportionally what a fifteen-point gap is at quarterback, and the feature
 * reports 0.05 against 0.15.
 *
 * That is the same fault Justin named on the market drift - "they can't be
 * absolute values" - one level down, and it would make a feature informative
 * for the big-scale positions and dead for the small ones without anything
 * failing. This measures whether that is actually happening.
 *
 *   ./gradlew run -Pmain=FeatureScales -Pkeepers=Tuten,Purdy -q
 */
public class FeatureScales {
    public static void main(String[] args) throws Exception {
        LiveSetup setup = LiveSetup.forTonight();
        DraftPlanner planner = setup.planner;
        Set<String> kept = setup.kept;

        Map<Position, List<Double>> byPosition = new EnumMap<>(Position.class);
        for(Map.Entry<String, Double> entry : planner.points().entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player != null && !kept.contains(entry.getKey()) && entry.getValue() > 0
                    && SleeperProjections.adpOf(entry.getKey()) > 0){
                byPosition.computeIfAbsent(player.position, u -> new ArrayList<>())
                        .add(entry.getValue());
            }
        }

        System.out.printf("%nthe absolute-scaled features, per position, on the 2026 board.%n"
                + "CLIFF_CAP is %.0f points for every position.%n%n",
                SelectionModel.CLIFF_CAP);
        System.out.printf("%-5s %8s %10s %12s %12s %s%n", "POS", "rank1",
                "1 to 2 gap", "f9 reads", "top-12 span", "verdict");
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR,
                Position.TE, Position.DEF}){
            List<Double> values = byPosition.get(position);
            if(values == null || values.size() < 13){
                continue;
            }
            values.sort(Comparator.reverseOrder());
            double gap = values.get(0) - values.get(1);
            double span = values.get(0) - values.get(11);
            double f9 = Math.min(Math.max(gap, 0), SelectionModel.CLIFF_CAP)
                    / SelectionModel.CLIFF_CAP;
            System.out.printf("%-5s %8.0f %10.1f %12.3f %12.0f %s%n", position,
                    values.get(0), gap, f9, span,
                    f9 < 0.05 ? "DEAD - never moves a tree split"
                            : f9 < 0.12 ? "weak" : "usable");
        }

        System.out.printf("%n%nSAME QUESTION FOR THE SURPLUS OVER REPLACEMENT (f29)%n%n");
        System.out.printf("%-5s %10s %12s %12s %s%n", "POS", "replacement",
                "best surplus", "f29 reads", "verdict");
        Map<Position, Integer> slots = Map.of(Position.QB, 1, Position.RB, 2,
                Position.WR, 3, Position.TE, 1, Position.DEF, 1);
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR,
                Position.TE, Position.DEF}){
            List<Double> values = byPosition.get(position);
            if(values == null){
                continue;
            }
            int index = Math.min(values.size() - 1, slots.get(position) * 12 - 1);
            double replacement = values.get(index);
            double surplus = values.get(0) - replacement;
            double f29 = Math.min(Math.max(surplus, 0), SelectionModel.CLIFF_CAP)
                    / SelectionModel.CLIFF_CAP;
            System.out.printf("%-5s %10.0f %12.0f %12.3f %s%n", position, replacement,
                    surplus, f29,
                    f29 >= 0.99 ? "SATURATED - every elite man reads the same"
                            : f29 < 0.10 ? "DEAD" : "usable");
        }
        System.out.printf("%na feature that reads 0.02 for one position and saturates at%n"
                + "1.00 for another is not measuring the same thing at both.%n");
    }
}
