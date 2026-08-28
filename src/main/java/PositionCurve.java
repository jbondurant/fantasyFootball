import PlayerImportAndSetup.Position;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Justin's challenge: at similar ADP, WRs are supposed to out-project RBs, so
 * why did four RBs beat four WRs? This prints what is actually on the board:
 * the mean best-available projection at each position, at each of my picks,
 * and how much the SECOND and THIRD best at that position fall off - which is
 * what a flex slot really buys.
 *
 *   ./gradlew run -Pmain=PositionCurve [-Ptrials=400]
 */
public class PositionCurve {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 400);
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        PolicyTournament tournament = PolicyTournament.forCurrentGame(configuration, trials);

        int[] picks = planner.simulator().pickNumbersOf(planner.me());
        System.out.println("mean best-available projection at each of my picks");
        System.out.println("(1st / 2nd / 3rd best remaining at that position)\n");
        System.out.printf("%-6s %-22s %-22s %-22s%n", "pick", "RB", "WR", "TE");
        for(int p = 0; p < picks.length; p++){
            StringBuilder row = new StringBuilder(String.format("%-6d ", picks[p]));
            for(Position position : new Position[]{Position.RB, Position.WR,
                    Position.TE}){
                double[] depth = tournament.depthAt(picks[p], position);
                row.append(String.format("%5.0f /%5.0f /%5.0f    ",
                        depth[0], depth[1], depth[2]));
            }
            System.out.println(row);
        }
        System.out.println("\nThe flex question is not 'which position projects higher'"
                + "\nbut 'how far does the SECOND one fall' - a flex slot is filled by"
                + "\nyour surplus at a position, not by its best player.");
    }
}
