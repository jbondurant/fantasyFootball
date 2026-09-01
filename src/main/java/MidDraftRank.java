import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * DOES THE SURVIVAL RULE STILL HOLD UP IN THE MIDDLE OF A DRAFT?
 *
 * RankPrediction scored the survival rule with NO picks known - and that is
 * exactly the regime where its approximation is EXACT, so it could not see the
 * approximation at all. The live tool runs it at pick 42 with forty-one men
 * gone.
 *
 * expectedRank counts a man really taken as 1 and everyone else at his
 * UNCONDITIONAL P(gone by p). The exact quantity, given he has visibly survived
 * to the current pick k, is
 *
 *     P(gone by p | survived to k) = (P(p) - P(k)) / (1 - P(k))
 *
 * which is smaller. So the shipped rule over-counts, and most for men who have
 * fallen past their ADP - precisely the men a late-round pick is about.
 *
 * This measures all three against the truth of the same simulated draft:
 * the ADP cutoff, the shipped unconditional rule, and the exact conditional.
 *
 *   ./gradlew run -Pmain=MidDraftRank -Pkeepers=Tuten,Purdy -Psims=120 -q
 */
public class MidDraftRank {

    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", "16");
        int sims = Integer.getInteger("sims", 120);
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        DraftSimulator simulator = planner.simulator();
        LiveBoard.Survival survival = new LiveBoard.Survival(planner, simulator, 200, 31_337L);
        LiveBoard.SURVIVAL = survival;

        List<Integer> myPicks = new ArrayList<>();
        for(int p = 1; p <= 200; p++){
            DraftSimulator.Slot slot = simulator.slotAt(p);
            if(slot != null && planner.me().equals(slot.manager()) && !slot.keeperSlot()){
                myPicks.add(p);
            }
        }
        Position[] positions = {Position.RB, Position.WR, Position.TE, Position.QB};
        Map<Position, List<String>> byPosition = new EnumMap<>(Position.class);
        for(String id : planner.points().keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null){
                byPosition.computeIfAbsent(player.position, u -> new ArrayList<>()).add(id);
            }
        }

        double cutoffError = 0;
        double shippedError = 0;
        double exactError = 0;
        int cells = 0;

        for(int s = 0; s < sims; s++){
            // Held out from the table's own draws by seed offset.
            Map<String, Integer> draw = simulator.simulateOnce(
                    new Random(500_000_000L + 7919L * s));
            for(int k : myPicks){
                // The board as it really stands at seat k.
                List<String> taken = new ArrayList<>();
                for(Map.Entry<String, Integer> entry : draw.entrySet()){
                    if(entry.getValue() < k){
                        taken.add(entry.getKey());
                    }
                }
                for(int p : myPicks){
                    if(p <= k){
                        continue;
                    }
                    for(Position position : positions){
                        List<String> men = byPosition.getOrDefault(position, List.of());
                        int trueGone = 0;
                        for(String id : men){
                            Integer at = draw.get(id);
                            if(at != null && at < p){
                                trueGone++;
                            }
                        }
                        Set<String> takenSet = new HashSet<>(taken);
                        // A: the retired hard cutoff.
                        double cutoff = LiveBoard.adpCutoffRank(planner, taken,
                                position, p) - 1;
                        // B: what ships - taken count 1, the rest unconditional.
                        double shipped = LiveBoard.expectedRank(planner, taken,
                                position, p) - 1;
                        // C: the exact conditional, given survival to k.
                        double exact = 0;
                        for(String id : men){
                            if(takenSet.contains(id)){
                                exact += 1;
                                continue;
                            }
                            double atK = survival.probabilityGone(id, k);
                            double atP = survival.probabilityGone(id, p);
                            exact += atK >= 1.0 ? 1.0
                                    : Math.max(0, (atP - atK) / (1 - atK));
                        }
                        cutoffError += Math.abs(cutoff - trueGone);
                        shippedError += Math.abs(shipped - trueGone);
                        exactError += Math.abs(exact - trueGone);
                        cells++;
                    }
                }
            }
        }
        System.out.printf("%n%d simulated drafts, %d (seat now, seat later, position)"
                + " cells.%n%n", sims, cells);
        System.out.printf("mean absolute error, men per cell:%n");
        System.out.printf("   hard ADP cutoff (retired)      %.2f%n", cutoffError / cells);
        System.out.printf("   survival, unconditional (ships) %.2f%n", shippedError / cells);
        System.out.printf("   survival, exact conditional     %.2f%n", exactError / cells);
        double gain = (shippedError - exactError) / cells;
        System.out.printf("%nthe conditional is %.2f men per cell %s than what ships.%n",
                Math.abs(gain), gain > 0 ? "BETTER" : "worse");
        if(gain > 0.25){
            System.out.printf("%nTHAT IS WORTH TAKING. The approximation i shipped is%n"
                    + "costing real accuracy mid-draft, which is where the tool%n"
                    + "actually runs.%n");
        }
        else {
            System.out.printf("%nSO THE APPROXIMATION IS CHEAP. Both survival forms sit%n"
                    + "far below the cutoff, and the exact conditional buys little%n"
                    + "over the unconditional one - the men it corrects are mostly%n"
                    + "men nobody was going to draft anyway.%n");
        }
    }
}
