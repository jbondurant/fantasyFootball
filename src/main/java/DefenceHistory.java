import PlayerImportAndSetup.Position;
import java.util.*;

/** Can a defence's ADP rank be joined to its season points, year by year? */
public class DefenceHistory {
    public static void main(String[] args) throws Exception {
        Map<String, EraBoards.Board> boards = EraBoards.usable("ppr",
                EraIngest.MIN_RATE, EraIngest.minDepth());
        System.out.printf("%nDEFENCES ON THE HISTORICAL BOARDS%n%n");
        System.out.printf("%-8s %8s %10s %12s%n", "SEASON", "DEF ADP", "SCORED", "TOP DEF PTS");
        for(Map.Entry<String, EraBoards.Board> entry : new TreeMap<>(boards).entrySet()){
            int onBoard = 0;
            for(String id : entry.getValue().ids()){
                if(entry.getValue().positionOf().get(id) == Position.DEF){
                    onBoard++;
                }
            }
            Map<String, Double> points = LeagueActuals.seasonDefencePoints(entry.getKey());
            double top = points.values().stream().mapToDouble(Double::doubleValue)
                    .max().orElse(0);
            System.out.printf("%-8s %8d %10d %12.1f%n", entry.getKey(), onBoard,
                    points.size(), top);
        }
    }
}
