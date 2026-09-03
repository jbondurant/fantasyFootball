import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * CAN HE WAIT ON A TIGHT END THIS YEAR?
 *
 * Justin, 2026-09-01: "if there are 7 te kept this year, doesn't it mean it
 * should be less expensive for me to draft the 5th te on the board? Also Te
 * like brenton strange have very high projections relative to their adp, so it
 * looks like I could wait a very long time and take him in many strategies,
 * unless like say harrold fannin slips a lot which can happen given less people
 * are likely to pick a te? is that a measurable effect, or can you refute that"
 *
 * Every clause is measurable and this measures them:
 *
 *   1  how many teams already hold a tight end, so how many can still want one
 *   2  what each tight end is projected for against what the market charges
 *   3  the survival curve of each at every one of his fourteen seats
 *   4  what waiting actually costs - the drop from the best tight end likely
 *      there at one seat to the best likely there at a later one
 *
 *   ./gradlew run -Pmain=TightEndWait -Pkeepers=Tuten,Purdy -q
 */
public class TightEndWait {

    public static void main(String[] args) throws Exception {
        LiveSetup setup = LiveSetup.forTonight();
        DraftPlanner planner = setup.planner;
        Set<String> kept = setup.kept;

        // ---- 1. how much demand is left ----
        AAAConfiguration configuration = setup.configuration;
        int teamsHoldingTE = 0;
        Map<String, Integer> byManager = new HashMap<>();
        for(String id : kept){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null && player.position == Position.TE){
                teamsHoldingTE++;
            }
        }
        System.out.printf("%n=== 1. HOW MANY TEAMS STILL WANT A TIGHT END ===%n%n");
        System.out.printf("   twelve teams, each starts one tight end.%n");
        System.out.printf("   tight ends already kept: %d%n", teamsHoldingTE);
        System.out.printf("   so teams that still need one: about %d%n",
                Math.max(0, 12 - teamsHoldingTE));
        System.out.printf("%n   for comparison, the same count at every position:%n");
        Map<Position, Integer> keptBy = new EnumMap<>(Position.class);
        for(String id : kept){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null){
                keptBy.merge(player.position, 1, Integer::sum);
            }
        }
        for(Position position : new Position[]{Position.RB, Position.WR,
                Position.TE, Position.QB, Position.DEF}){
            System.out.printf("   %-4s kept %2d%n", position, keptBy.getOrDefault(position, 0));
        }

        // ---- 2 & 3. the board, and how long each man lasts ----
        List<String> tightEnds = new ArrayList<>();
        for(Map.Entry<String, Double> entry : planner.points().entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player != null && player.position == Position.TE
                    && !kept.contains(entry.getKey())
                    && SleeperProjections.adpOf(entry.getKey()) > 0){
                tightEnds.add(entry.getKey());
            }
        }
        tightEnds.sort(Comparator.comparingDouble(
                (String id) -> planner.points().getOrDefault(id, 0.0)).reversed());

        int[] seats = {7, 18, 31, 42, 55, 66, 79, 90, 103, 114, 127, 162, 175, 186};
        System.out.printf("%n%n=== 2 & 3. THE TIGHT ENDS, AND HOW LONG THEY LAST ===%n%n");
        System.out.printf("%-22s %6s %6s %7s   survival at my seats%n",
                "MAN", "PROJ", "ADP", "value");
        System.out.printf("%-22s %6s %6s %7s   %s%n", "", "", "", "",
                "  31   42   55   66   79   90  103  114");
        for(int i = 0; i < Math.min(9, tightEnds.size()); i++){
            String id = tightEnds.get(i);
            Player player = Player.getPlayerFromSIDV2(id);
            double proj = planner.points().getOrDefault(id, 0.0);
            double adp = SleeperProjections.adpOf(id);
            StringBuilder survival = new StringBuilder();
            for(int seat : new int[]{31, 42, 55, 66, 79, 90, 103, 114}){
                double alive = LiveBoard.SURVIVAL == null ? Double.NaN
                        : 1.0 - LiveBoard.SURVIVAL.probabilityGone(id, seat);
                survival.append(String.format("%5.0f", 100 * alive));
            }
            // "value" = projected points per unit of ADP cost, scaled so the
            // best tight end reads 1.00 - the "high projection relative to ADP"
            // Justin is pointing at.
            System.out.printf("%-22s %6.0f %6.0f %7.2f   %s%n",
                    player.firstName + " " + player.lastName, proj, adp,
                    adp <= 0 ? 0 : proj / adp, survival);
        }

        // ---- 4. what waiting actually costs ----
        System.out.printf("%n%n=== 4. WHAT WAITING COSTS ===%n%n");
        System.out.printf("the best tight end still expected on the board at each seat,%n"
                + "and what he is projected for.%n%n");
        System.out.printf("%-6s %-22s %8s %10s%n", "SEAT", "BEST TE LIKELY THERE",
                "PROJ", "vs seat 31");
        double atFirst = -1;
        for(int seat : seats){
            String best = null;
            double bestPoints = -1;
            for(String id : tightEnds){
                double alive = LiveBoard.SURVIVAL == null ? 1.0
                        : 1.0 - LiveBoard.SURVIVAL.probabilityGone(id, seat);
                double proj = planner.points().getOrDefault(id, 0.0);
                if(alive >= 0.5 && proj > bestPoints){
                    bestPoints = proj;
                    best = id;
                }
            }
            if(best == null){
                continue;
            }
            if(atFirst < 0){
                atFirst = bestPoints;
            }
            Player player = Player.getPlayerFromSIDV2(best);
            System.out.printf("%-6d %-22s %8.0f %10.0f%n", seat,
                    player.firstName + " " + player.lastName, bestPoints,
                    bestPoints - atFirst);
        }
        System.out.printf("%n'likely there' means at least a 50%% chance of surviving to%n"
                + "that seat, from the same survival table the board model uses.%n");
    }
}
