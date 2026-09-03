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
 * `next` minus the expected number gone by `pick`. As of 2026-09-01 drain uses
 * that prior ALONE: the room-observed term it used to blend in was there to
 * rescue a poor ADP-counting prior, and against a fitted one it adds noise.
 * Measured both here and, decisively, on real drafts by RealMidDraft.
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
        double retiredError = 0;
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
                    // A: what ships.
                    double shipped = LiveBoard.drain(planner, taken, position, pick, next);
                    // A0: the RETIRED rule, room blended with the ADP-count
                    // prior, reconstructed here so the baseline stays
                    // reproducible after drain itself changed. Same integer
                    // rounding as drain, which matters - the truth is an
                    // integer, so rounding is worth about 0.07 men.
                    int goneA0 = 0;
                    for(String id : taken){
                        Player playerA0 = Player.getPlayerFromSIDV2(id);
                        if(playerA0 != null && playerA0.position == position){
                            goneA0++;
                        }
                    }
                    double observedA0 = taken.isEmpty() ? 0
                            : (double) goneA0 / taken.size();
                    double trustA0 = taken.size() / (taken.size() + 30.0);
                    double retired = Math.max(0, Math.round(
                            (trustA0 * observedA0 + (1 - trustA0)
                                    * LiveBoard.adpRate(planner, position, pick, next))
                                    * (next - pick)));
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
                    retiredError += Math.abs(retired - trueDrain);
                    survivalError += Math.abs(pure - trueDrain);
                    blendError += Math.abs(blended - trueDrain);
                    cells++;
                }
            }
        }
        System.out.printf("%n%d drafts, %d (seat, position) cells.%n%n", sims, cells);
        System.out.printf("mean absolute error, men per window:%n");
        System.out.printf("   RETIRED: room blended with ADP counts   %.2f%n",
                retiredError / cells);
        System.out.printf("   SHIPPED: survival prior alone, rounded  %.2f%n",
                shippedError / cells);
        System.out.printf("   the same prior, NOT rounded             %.2f%n",
                survivalError / cells);
        System.out.printf("   room blended with the prior (retired)   %.2f%n",
                blendError / cells);
        System.out.printf("%nshipped is %.2f men per window better than the retired rule.%n",
                (retiredError - shippedError) / cells);
        System.out.printf("%nthe room term is GONE from drain as of 2026-09-01. i kept it%n"
                + "at first because this comparison is model-consistent - the draws%n"
                + "come from the opponent model the prior is fitted on, so it could%n"
                + "not separate a right prior from one predicting itself. RealMidDraft%n"
                + "settles it on the league's own 2024 and 2025 drafts, where the%n"
                + "prior alone also wins (1.56 against 1.72). both kinds of test agree,%n"
                + "so the argument for keeping it is spent.%n");
    }
}
