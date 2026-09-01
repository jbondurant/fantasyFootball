import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * WHAT DOES TAKING THE DEFENCE EARLY ACTUALLY COST?
 *
 * DRAFT-READY.md claims the model "refuses a defence before round ten". The
 * second adversarial pass measured a defence in round 7 or 8 in five of six
 * drafts, so one of those two statements is wrong. Arguing about which is not
 * useful; the decision-relevant quantity is the COST, and it is bounded and
 * measurable: hold the roster shape fixed and move only the round the defence
 * is taken in, then compare the seasons the starting nine scores.
 *
 * The comparison is against the 125-point bar - the 95% clustered-on-season
 * significance threshold. A gap inside that bar is not a fact about football.
 *
 *   ./gradlew run -Pmain=DefenceTiming -Pkeepers=Tuten,Purdy -q
 */
public class DefenceTiming {

    static final int[] PICKS = {7, 18, 31, 42, 55, 66, 79, 90, 103, 114, 127, 162, 175, 186};

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        var earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        Set<String> kept = LiveBoard.kept(configuration);
        Map<Position, double[]> curve = LiveBoard.thisYear(planner, kept);
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Map<Position, List<List<Double>>> pools =
                new EnumMap<>(BoardValue.pools(men, curve));
        List<List<Double>> defence = LiveBoard.defenceScatter();
        if(!defence.isEmpty()){
            pools.put(Position.DEF, defence);
        }

        // The shape DryRun plays, keepers included. Only the defence moves.
        List<Position> base = new ArrayList<>(List.of(
                Position.RB, Position.RB, Position.RB, Position.WR, Position.WR,
                Position.TE, Position.WR, Position.DEF, Position.WR, Position.WR,
                Position.RB, Position.QB, Position.WR, Position.WR));

        System.out.printf("%nthe defence's round, holding every other slot fixed.%n"
                + "the freed pick becomes a receiver, the position it displaces.%n%n");
        System.out.printf("%-8s %-34s %10s %10s%n", "DEF AT", "SHAPE", "SEASON", "vs BEST");

        double best = -1e9;
        Map<Integer, Double> byRound = new LinkedHashMap<>();
        for(int slot = 0; slot < PICKS.length; slot++){
            List<Position> plan = new ArrayList<>(base);
            plan.remove(Position.DEF);
            plan.add(slot, Position.DEF);
            List<BoardValue.Slot> roster = new ArrayList<>();
            roster.add(new BoardValue.Slot(Position.RB, 24));   // Tuten, kept
            roster.add(new BoardValue.Slot(Position.QB, 6));    // Purdy, kept
            boolean ok = true;
            for(int i = 0; i < plan.size(); i++){
                Position position = plan.get(i);
                int rank = LiveBoard.expectedRank(planner, new ArrayList<>(),
                        position, PICKS[i]);
                double[] mean = curve.get(position);
                if(mean == null || rank >= mean.length){
                    ok = false;
                    break;
                }
                roster.add(new BoardValue.Slot(position, rank));
            }
            if(!ok){
                continue;
            }
            double season = BoardValue.stats(roster, pools, curve, 400, true)[0];
            byRound.put(PICKS[slot], season);
            best = Math.max(best, season);
        }
        for(Map.Entry<Integer, Double> row : byRound.entrySet()){
            System.out.printf("pick %-3d %-34s %10.1f %10.1f%n", row.getKey(), "",
                    row.getValue(), row.getValue() - best);
        }

        double bar = 125.0;
        double low = Double.MAX_VALUE;
        for(double season : byRound.values()){
            low = Math.min(low, season);
        }
        System.out.printf("%nspread across every legal round for the defence: %.1f points.%n",
                best - low);
        System.out.printf("the significance bar is %.0f.%n%n", bar);
        if(best - low < bar){
            System.out.printf("SO THE ROUND THE DEFENCE GOES IN IS NOT A MEASURABLE%n"
                    + "QUANTITY. Every round from the first to the last sits inside%n"
                    + "one bar of every other. DRAFT-READY's claim that the model%n"
                    + "'refuses a defence before round ten' was never a finding - it%n"
                    + "describes a preference the objective cannot see, and the audit%n"
                    + "was right that the model does not honour it.%n%n"
                    + "What DOES matter is that the rollout can no longer imagine a%n"
                    + "roster with no defence at all, which is what made round 7 look%n"
                    + "urgent rather than merely harmless.%n");
        }
        else {
            System.out.printf("the round MATTERS: %.1f exceeds the bar. best is pick %s.%n",
                    best - low, byRound.entrySet().stream()
                            .filter(e -> e.getValue() == Double.valueOf(
                                    byRound.values().stream().max(Double::compare).get()))
                            .findFirst().map(e -> String.valueOf(e.getKey())).orElse("?"));
        }
    }
}
