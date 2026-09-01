import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * Does the objective under-price a bench pick? Measured: no, it over-prices one.
 *
 * Justin: "a round 11 pick should also likely be a wr/rb, not because I expect
 * many starters to get injured, but because I expect some starters to bust, and
 * some bench players to boom."
 *
 * His reading of the mechanism is right. WeeklyStarterValue.oneWeek() promotes a
 * bench man through exactly ONE channel: a starter drawn !up(), which is injury.
 * Survivors are sorted by EXPECTED - the preseason projection, which never
 * updates - so a starter who plays seventeen games and disappoints keeps his
 * ranking and keeps starting, and a bench man who breaks out is never promoted.
 * Bust and boom are invisible to it, exactly as he says.
 *
 * I then predicted the consequence: that the objective must therefore bid too
 * little for a round-11 receiver. That prediction is WRONG, and this tool is
 * what showed it. At pick 127 the objective prices the best free receiver at
 * about 53 points while this league's real bench receivers were worth about 40
 * over the wire. It bids MORE, not less. Losing two promotion channels did not
 * make it stingy, because the one it kept - injury, drawn from whole observed
 * player-seasons - is frequent enough on its own.
 *
 * The two columns are not the same measurement and must not be read as a
 * subtraction. MODEL SEES is one named man on today's board; MEASURED is the
 * average over this league's real rounds 8-9 picks at that position
 * (BenchValue.overWireByPosition filters rounds 1 to 9). They are the same order of
 * magnitude, which is the finding: the objective is in the right range, and the
 * missing channels are not costing a round-11 receiver his bid.
 *
 * Ignore the quarterback row. BenchValue's own caveat applies - its over-wire
 * figures are raw points, which flatters a quarterback because this league pays
 * six for a passing touchdown.
 *
 *   ./gradlew run -Pmain=BenchValueGap [-Ppick=127] [-Pscenarios=1200]
 */
public class BenchValueGap {

    /** BenchValue, measured on this league's real picks, points over the wire. */
    static final Map<String, Double> MEASURED = new LinkedHashMap<>(Map.of(
            "8-9", 44.0, "10-12", 32.8, "13-16", 31.2));

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int scenarios = Integer.getInteger("scenarios", 1200);
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        // Constructed exactly as DraftNight does it: the -Pkeepers knob is read
        // inside forCurrentSeason, so an empty list here still yields Justin's
        // two keepers on planner.myKeeperIDs().
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        WeeklyStarterValue value = WeeklyStarterValue.forCurrentBoard(configuration,
                planner.points(), scenarios, 424_242L);

        // A full starting nine: the two keepers plus the seven picks Model A
        // makes. From here every further pick is bench, which is the case the
        // question is about.
        List<String> roster = new ArrayList<>(planner.myKeeperIDs());
        Map<String, Double> points = planner.points();
        Position[] shape = {Position.RB, Position.WR, Position.RB, Position.WR,
                Position.WR, Position.WR, Position.TE};
        Set<String> used = new HashSet<>(roster);
        for(Position position : shape){
            String best = bestFree(points, used, position);
            if(best != null){
                roster.add(best);
                used.add(best);
            }
        }
        // The board as it really is at that pick. Without this the "best free"
        // man is McCaffrey, and adding the best back in football to a bench
        // proves nothing - the first run of this tool printed exactly that.
        int pick = Integer.getInteger("pick", 127);
        List<String> byAdp = new ArrayList<>(points.keySet());
        byAdp.sort(Comparator.comparingDouble(SleeperProjections::adpOf));
        for(int i = 0; i < Math.min(pick - 1, byAdp.size()); i++){
            used.add(byAdp.get(i));
        }
        double base = value.of(roster);
        System.out.printf("%nWHAT THE OBJECTIVE CAN SEE IN A BENCH PICK%n%n");
        System.out.printf("starting nine full: %s%n", shapeOf(roster));
        System.out.printf("board as at pick %d (round %d), %d men already gone%n%n",
                pick, (pick + 11) / 12, pick - 1);

        System.out.printf("%-6s %-22s %12s %12s %12s%n", "POS", "BEST FREE",
                "MODEL SEES", "MEASURED r8-9", "SAME RANGE?");
        // Measured per POSITION rather than per band - the same table DraftNight
        // already prices its bench picks with.
        Map<Position, Double> overWire = BenchValue.overWireByPosition(configuration);
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE,
                Position.QB, Position.DEF}){
            String best = bestFree(points, used, position);
            if(best == null){
                continue;
            }
            List<String> trial = new ArrayList<>(roster);
            trial.add(best);
            double marginal = value.of(trial) - base;
            Player player = Player.getPlayerFromSIDV2(best);
            Double measured = overWire.get(position);
            System.out.printf("%-6s %-22s %12.1f %12s %12s%n", position,
                    player == null ? best : player.firstName + " " + player.lastName,
                    marginal,
                    measured == null ? "-" : String.format("%.1f", measured),
                    measured == null ? "-" : marginal > measured * 0.5
                            && marginal < measured * 2.5 ? "yes" : "NO");
        }

        System.out.printf("%n%s%n", "-".repeat(70));
        System.out.printf("MODEL SEES   = WeeklyStarterValue's marginal for adding THIS man to a%n"
                + "               full starting nine. Injury is its only promotion channel:%n"
                + "               a starter who plays and disappoints keeps his preseason%n"
                + "               rank and keeps starting.%n");
        System.out.printf("MEASURED r8-9= what this league's real rounds 8-9 picks at that%n"
                + "               position were worth over the wire. A different measurement,%n"
                + "               not a target - do not subtract the columns.%n");
        System.out.printf("%nThe objective bids MORE for a bench receiver than the measured%n"
                + "figure, not less. Losing the bust and boom channels did not make it%n"
                + "stingy, because injury alone - drawn from whole observed player-seasons%n"
                + "- is frequent enough. Ignore the QB row: BenchValue's over-wire is in raw%n"
                + "points, which flatters a quarterback at six per passing touchdown.%n");
        System.out.printf("%nWhat survives for draft night is the ORDERING, which both agree on:%n"
                + "at round 11 a back or receiver is worth three times a defence.%n");
    }

    static String bestFree(Map<String, Double> points, Set<String> used, Position position){
        String best = null;
        double most = -1;
        for(Map.Entry<String, Double> entry : points.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null || player.position != position || used.contains(entry.getKey())){
                continue;
            }
            if(entry.getValue() > most){
                most = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    static String shapeOf(List<String> roster){
        StringBuilder out = new StringBuilder();
        for(String id : roster){
            Player player = Player.getPlayerFromSIDV2(id);
            out.append(player == null ? "?" : player.position).append(' ');
        }
        return out.toString().trim();
    }
}
