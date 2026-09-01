import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * DOES THE FRAGILITY BAR EVER REFUSE ANYTHING?
 *
 * DRAFT-READY calls the 15% bar "still chosen, still unfitted", which reads as
 * though it is doing work. The second adversarial pass measured swings of
 * 12-13% at all 84 picks it priced, which would mean the bar never binds - a
 * knob that cannot fire is not a safeguard, and saying it is one overstates
 * what the model checks.
 *
 * This walks Justin's seats on a simulated draft and asks the SHIPPED
 * predicate, BoardValue.tooFragile, how often it refuses.
 *
 *   ./gradlew run -Pmain=FragilityBinding -Pkeepers=Tuten,Purdy -q
 */
public class FragilityBinding {
    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", "16");
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        DraftSimulator simulator = planner.simulator();
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

        Random random = new Random(90125L);
        List<String> taken = new ArrayList<>();
        DraftSimulator.SimState state = simulator.initialState();
        List<BoardValue.Slot> held = new ArrayList<>();
        held.add(new BoardValue.Slot(Position.RB, 24));
        held.add(new BoardValue.Slot(Position.QB, 6));

        int priced = 0;
        int refused = 0;
        double worstSwing = 0;
        List<Double> swings = new ArrayList<>();
        double bar = BoardValue.fragilityBar();
        System.out.printf("%nthe shipped bar is %.0f%%.%n%n", 100 * bar);
        System.out.printf("%-6s %-6s %10s %10s%n", "PICK", "POS", "SWING", "REFUSED?");
        while(true){
            DraftSimulator.Slot slot = simulator.slotOf(state);
            if(slot == null){
                break;
            }
            if(planner.me().equals(slot.manager())){
                // HIS ROSTER HAS TO GROW. The first version of this held it at
                // the two keepers for the whole draft, so by pick 186 it was
                // pricing a three-man roster - whose swing is enormous for
                // reasons that have nothing to do with the pick - and reported
                // the bar refusing 50 of 66. That number was an artefact of my
                // harness, not a fact about the model.
                Position bestHere = null;
                int bestRank = 1;
                double bestEnd = -1e9;
                for(Position position : new Position[]{Position.RB, Position.WR,
                        Position.TE, Position.QB, Position.DEF}){
                    int rank = LiveBoard.expectedRank(planner, taken, position,
                            slot.pickNumber());
                    double[] mean = curve.get(position);
                    if(mean == null || rank >= mean.length){
                        continue;
                    }
                    double[] stats = LiveBoard.rolloutStats(planner, taken, curve, pools,
                            200, held, position, rank, slot.pickNumber());
                    boolean tooFragile = BoardValue.tooFragile(stats);
                    double swing = stats[0] <= 0 ? 0 : (stats[0] - stats[1]) / stats[0];
                    worstSwing = Math.max(worstSwing, swing);
                    swings.add(swing);
                    priced++;
                    if(tooFragile){
                        refused++;
                        System.out.printf("%-6d %-6s %9.1f%% %10s%n", slot.pickNumber(),
                                position, 100 * swing, "YES");
                    }
                    if(!tooFragile && stats[0] > bestEnd){
                        bestEnd = stats[0];
                        bestHere = position;
                        bestRank = rank;
                    }
                }
                if(bestHere != null){
                    held.add(new BoardValue.Slot(bestHere, bestRank));
                }
            }
            String pick = null;
            List<String> live = new ArrayList<>();
            for(String id : planner.points().keySet()){
                if(state.takenAtOf(id) == null && !taken.contains(id) && !kept.contains(id)){
                    live.add(id);
                }
            }
            live.sort(Comparator.comparingDouble(
                    (String id) -> planner.points().getOrDefault(id, 0.0)).reversed());
            if(live.isEmpty()){
                break;
            }
            pick = live.get(random.nextInt(Math.min(6, live.size())));
            state = simulator.branchWith(state, pick);
            taken.add(pick);
        }
        System.out.printf("%n%d position-picks priced, %d refused by the bar.%n",
                priced, refused);
        System.out.printf("worst swing seen anywhere: %.1f%% against a %.0f%% bar.%n",
                100 * worstSwing, 100 * bar);
        swings.sort(Comparator.reverseOrder());
        double widest = swings.get(0);
        double narrowest = swings.get(swings.size() - 1);
        double median = swings.get(swings.size() / 2);
        System.out.printf("%nthe swing statistic across all %d position-picks:%n", priced);
        System.out.printf("   widest    %.1f%%%n   median    %.1f%%%n   narrowest %.1f%%%n",
                100 * widest, 100 * median, 100 * narrowest);
        System.out.printf("   full range %.1f points%n", 100 * (widest - narrowest));
        if(widest - narrowest < 0.04){
            System.out.printf("%nAND THE STATISTIC BARELY VARIES: %.1f points from the%n"
                    + "safest pick in the draft to the most fragile. A LOWER bar%n"
                    + "would fire - this is not a quantity with no variation - but%n"
                    + "it would be sorting picks on a %.1f-point spread, and the%n"
                    + "board-to-board noise in this repo is measured in tens of%n"
                    + "points. So the honest reading is that the bar is placed%n"
                    + "above the data AND that there is little signal underneath%n"
                    + "it to place it more usefully.%n",
                    100 * (widest - narrowest), 100 * (widest - narrowest));
        }
        int near = 0;
        for(double swing : swings){
            if(swing > bar - 0.02){
                near++;
            }
        }
        if(refused == 0){
            System.out.printf("%nTHE BAR NEVER FIRES - but it is not clear of the data,%n"
                    + "it sits ON the edge of it: the widest swing is %.1f%% against%n"
                    + "a %.0f%% bar, and %d of %d position-picks come within two points%n"
                    + "of it. So it is not dead code that could never fire; it is a%n"
                    + "threshold placed just above the distribution, which is a%n"
                    + "different claim from 'the model refuses fragile picks'.%n"
                    + "DRAFT-READY lists it among the things the model checks. On%n"
                    + "this board it checks nothing.%n",
                    100 * worstSwing, 100 * bar, near, priced);
        }
    }
}
