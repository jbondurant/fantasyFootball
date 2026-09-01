import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * The 2026 curve at each position, and where the valley detector says it falls.
 *
 * Justin, on being told the running back cliff is after RB1: "isn't the cliff
 * after gibbs and robinson". A threshold I chose should not get to answer that
 * on its own, so this prints the projections, the isotonic fit and the detected
 * steps beside each other and lets the board settle it.
 *
 *   ./gradlew run -Pmain=CurveCheck -Pkeepers=Tuten,Purdy -q [-Ppos=RB]
 */
public class CurveCheck {

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);

        Position want = Position.valueOf(System.getProperty("pos", "RB"));
        // Kept men are on somebody's roster, so they are not on the curve.
        Set<String> kept = LiveBoard.kept(configuration);
        Map<Position, double[]> curve = LiveBoard.thisYear(planner, kept);
        double[] raw = curve.get(want);

        // Names in projection order, so a rank has a face.
        List<Map.Entry<String, Double>> byPoints = new ArrayList<>();
        for(Map.Entry<String, Double> entry : planner.points().entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player != null && player.position == want && !kept.contains(entry.getKey())){
                byPoints.add(entry);
            }
        }
        byPoints.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Map<Position, List<List<Double>>> pools = BoardValue.pools(men, curve);
        List<LiveBoard.Valley> valleys = LiveBoard.tiers(curve, pools, want,
                Double.parseDouble(System.getProperty("tierBar", "0.40")));
        Set<Integer> cliffs = new HashSet<>();
        for(LiveBoard.Valley valley : valleys){
            cliffs.add(valley.afterRank());
        }

        System.out.printf("%nTHE 2026 %s CURVE%n%n", want);
        System.out.printf("%-5s %-24s %9s %7s %9s %6s  %s%n",
                "RANK", "PLAYER", "PROJECTED", "DROP", "BEATS LDR", "TIER", "");
        int leader = 1;
        int tier = 1;
        int from = Integer.getInteger("fromRank", 1);
        for(int rank = from; rank <= Math.min(from + 19, byPoints.size()); rank++){
            Player player = Player.getPlayerFromSIDV2(byPoints.get(rank - 1).getKey());
            double points = raw[rank];
            double drop = rank + 1 < raw.length && raw[rank + 1] > 0
                    ? points - raw[rank + 1] : 0;
            int below = 0;
            for(int world = 0; world < BoardValue.WORLDS; world++){
                double best = BoardValue.drawn(pools, want, leader, world, curve, true);
                double him = BoardValue.drawn(pools, want, rank, world, curve, true);
                if(him > best){
                    below++;
                }
            }
            double beats = (double) below / BoardValue.WORLDS;
            System.out.printf("%-5s %-24s %9.1f %7.1f %8.0f%% %6d  %s%n", want + "" + rank,
                    player == null ? "?" : player.firstName + " " + player.lastName,
                    points, drop, 100 * beats, tier,
                    cliffs.contains(rank) ? "<<< TIER ENDS HERE" : "");
            if(cliffs.contains(rank)){
                leader = rank + 1;
                tier++;
            }
        }

        System.out.printf("%ndetected valleys: ");
        for(LiveBoard.Valley valley : valleys){
            System.out.printf("after %s%d (drop %.1f)  ", want, valley.afterRank(),
                    valley.drop());
        }
        System.out.printf("%n%nDROP is to the next rank on the RAW projections. The detector%n"
                + "runs isotonic first, so it fires on the step left after noise is%n"
                + "pooled - which is not always the biggest raw drop, and is the point.%n");
    }
}
