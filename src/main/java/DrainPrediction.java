import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * HOW MANY OF A POSITION REALLY GO BETWEEN MY PICK AND MY NEXT?
 *
 * `drain` answers that, and it sets the rank used for the VS WAIT column - the
 * cost of waiting, which is the whole reason the table exists. It blends what
 * the room has actually done with an ADP prior: `adpRate` counts how many men
 * of that position have an ADP falling in the window. That is the same hard
 * ADP reasoning `expectedRank` used until tonight, in the one place left that
 * still uses it.
 *
 * The survival table gives a fitted prior instead - the expected number gone by
 * `next` minus the expected number gone by `pick` - so this measures whether
 * swapping the prior helps, on simulated drafts held out from the table.
 *
 *   ./gradlew run -Pmain=DrainPrediction -Pkeepers=Tuten,Purdy -Psims=60 -q
 */
public class DrainPrediction {

    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", "16");
        int sims = Integer.getInteger("sims", 60);
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        DraftSimulator simulator = planner.simulator();
        LiveBoard.Survival survival =
                new LiveBoard.Survival(planner, simulator, 200, 31_337L);
        LiveBoard.SURVIVAL = survival;

        List<Integer> myPicks = new ArrayList<>();
        for(int p = 1; p <= 200; p++){
            DraftSimulator.Slot slot = simulator.slotAt(p);
            if(slot != null && planner.me().equals(slot.manager()) && !slot.keeperSlot()){
                myPicks.add(p);
            }
        }
        Position[] positions = {Position.RB, Position.WR, Position.TE, Position.QB};

        double shippedError = 0;
        double survivalError = 0;
        double blendError = 0;
        int cells = 0;

        for(int s = 0; s < sims; s++){
            Map<String, Integer> draw = simulator.simulateOnce(
                    new Random(700_000_000L + 7919L * s));
            for(int i = 0; i + 1 < myPicks.size(); i++){
                int pick = myPicks.get(i);
                int next = myPicks.get(i + 1);
                List<String> taken = new ArrayList<>();
                for(Map.Entry<String, Integer> entry : draw.entrySet()){
                    if(entry.getValue() < pick){
                        taken.add(entry.getKey());
                    }
                }
                for(Position position : positions){
                    int trueDrain = 0;
                    for(Map.Entry<String, Integer> entry : draw.entrySet()){
                        Player player = Player.getPlayerFromSIDV2(entry.getKey());
                        if(player != null && player.position == position
                                && entry.getValue() >= pick && entry.getValue() < next){
                            trueDrain++;
                        }
                    }
                    // A: what ships - room blended with the ADP-count prior.
                    double shipped = LiveBoard.drain(planner, taken, position, pick, next);
                    // B: the survival table alone.
                    double pure = Math.max(0, survival.expectedGone(position, next)
                            - survival.expectedGone(position, pick));
                    // C: the same blend, survival replacing the ADP prior.
                    int gone = 0;
                    for(String id : taken){
                        Player player = Player.getPlayerFromSIDV2(id);
                        if(player != null && player.position == position){
                            gone++;
                        }
                    }
                    double observed = taken.isEmpty() ? 0
                            : (double) gone / taken.size() * (next - pick);
                    double trust = taken.size() / (taken.size() + 30.0);
                    double blended = trust * observed + (1 - trust) * pure;

                    shippedError += Math.abs(shipped - trueDrain);
                    survivalError += Math.abs(pure - trueDrain);
                    blendError += Math.abs(blended - trueDrain);
                    cells++;
                }
            }
        }
        System.out.printf("%n%d drafts, %d (seat, position) cells.%n%n", sims, cells);
        System.out.printf("mean absolute error, men per window:%n");
        System.out.printf("   shipped: room blended with ADP counts   %.2f%n",
                shippedError / cells);
        System.out.printf("   survival table alone                    %.2f%n",
                survivalError / cells);
        System.out.printf("   room blended with survival prior        %.2f%n",
                blendError / cells);
        double best = Math.min(survivalError, blendError);
        System.out.printf("%nbest alternative is %.2f men per window better than shipped.%n",
                (shippedError - best) / cells);
    }
}
