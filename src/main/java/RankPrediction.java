import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * HOW WELL DOES expectedRank PREDICT THE RANK THAT ACTUALLY ARRIVES?
 *
 * expectedRank feeds every rollout in the board model, so it sets every END
 * TEAM number on the table. It decides who is gone with a HARD ADP CUTOFF -
 * `adpOf(id) < pick` - which is exactly the rule this repo's own wait-table
 * comment rejects: "a hard cutoff and false in both directions: a man at ADP
 * 6.9 is not certainly gone and one at 7.1 is not certainly there." The wait
 * table simulates survival instead. The rollout never got the same treatment.
 *
 * This measures the gap on a bounded, decisive quantity rather than arguing
 * about it: simulate drafts with the fitted opponent model, and at each of
 * Justin's fourteen seats compare each rule's PREDICTED number-gone against
 * the number that really went.
 *
 * The survival rule is fitted on one half of the simulations and scored on the
 * other, so it is not being graded on its own training draws.
 *
 *   ./gradlew run -Pmain=RankPrediction -Pkeepers=Tuten,Purdy -Psims=400 -q
 */
public class RankPrediction {

    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", "16");
        int sims = Integer.getInteger("sims", 400);
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        DraftSimulator simulator = planner.simulator();

        // Justin's seats.
        List<Integer> myPicks = new ArrayList<>();
        for(int p = 1; p <= 200; p++){
            DraftSimulator.Slot slot = simulator.slotAt(p);
            if(slot != null && planner.me().equals(slot.manager()) && !slot.keeperSlot()){
                myPicks.add(p);
            }
        }

        Position[] positions = {Position.QB, Position.RB, Position.WR,
                Position.TE, Position.DEF};
        Map<Position, List<String>> byPosition = new EnumMap<>(Position.class);
        for(String id : planner.points().keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null){
                byPosition.computeIfAbsent(player.position, u -> new ArrayList<>()).add(id);
            }
        }

        // ---- draw the simulations once, split into fit and score halves ----
        List<Map<String, Integer>> draws = new ArrayList<>();
        for(int s = 0; s < sims; s++){
            draws.add(simulator.simulateOnce(new Random(31_337L + 7919L * s)));
        }
        int half = draws.size() / 2;

        // Survival rule, fitted on the FIRST half: P(gone by pick p) per man.
        Map<String, Map<Integer, Double>> survival = new HashMap<>();
        for(String id : planner.points().keySet()){
            Map<Integer, Double> byPick = new HashMap<>();
            for(int pick : myPicks){
                int goneIn = 0;
                for(int s = 0; s < half; s++){
                    Integer at = draws.get(s).get(id);
                    if(at != null && at < pick){
                        goneIn++;
                    }
                }
                byPick.put(pick, goneIn / (double) half);
            }
            survival.put(id, byPick);
        }

        System.out.printf("%n%d simulated drafts: %d to fit the survival rule, %d to score"
                + " both.%n%n", draws.size(), half, draws.size() - half);
        System.out.printf("%-6s %-5s %10s %10s %10s%n", "PICK", "POS", "TRUE GONE",
                "ADP CUT", "SURVIVAL");

        double adpError = 0;
        double survivalError = 0;
        int cells = 0;
        for(int pick : myPicks){
            for(Position position : positions){
                List<String> men = byPosition.getOrDefault(position, List.of());
                if(men.isEmpty()){
                    continue;
                }
                // What the HARD CUTOFF predicts - the shipped rule, with no
                // picks yet known, which is what a rollout faces for a future
                // seat.
                double adpPredicted = LiveBoard.expectedRank(planner,
                        new ArrayList<>(), position, pick) - 1;
                // What the SURVIVAL rule predicts: the expected count.
                double survivalPredicted = 0;
                for(String id : men){
                    survivalPredicted += survival.get(id).getOrDefault(pick, 0.0);
                }
                // What actually happened, averaged over the SCORING half.
                double trueGone = 0;
                for(int s = half; s < draws.size(); s++){
                    int gone = 0;
                    for(String id : men){
                        Integer at = draws.get(s).get(id);
                        if(at != null && at < pick){
                            gone++;
                        }
                    }
                    trueGone += gone;
                }
                trueGone /= (draws.size() - half);

                adpError += Math.abs(adpPredicted - trueGone);
                survivalError += Math.abs(survivalPredicted - trueGone);
                cells++;
                if(pick <= 42 || position == Position.RB){
                    System.out.printf("%-6d %-5s %10.1f %10.1f %10.1f%n", pick, position,
                            trueGone, adpPredicted, survivalPredicted);
                }
            }
        }
        System.out.printf("%nmean absolute error over %d position-seats:%n", cells);
        System.out.printf("   hard ADP cutoff   %.2f men%n", adpError / cells);
        System.out.printf("   survival weighted %.2f men%n", survivalError / cells);
        double better = (adpError - survivalError) / cells;
        System.out.printf("%nsurvival is %.2f men closer per seat%s.%n", Math.abs(better),
                better > 0 ? "" : " WORSE - the hard cutoff wins");
    }
}
