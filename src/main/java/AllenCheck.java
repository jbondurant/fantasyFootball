import java.util.*;

/** Where do the top QBs actually land, per the model? Survival at my picks. */
public class AllenCheck {
    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 400);
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        DraftSimulator simulator = planner.simulator();
        int[] myPicks = simulator.pickNumbersOf(planner.me());

        Map<String, int[]> gone = new HashMap<>();
        Map<String, double[]> landing = new HashMap<>();
        Random random = new Random(DraftSimulator.SEED);
        List<String> watch = new ArrayList<>();
        for(String id : simulator.players()){
            Player p = Player.getPlayerFromSIDV2(id);
            if(p != null && p.position == PlayerImportAndSetup.Position.QB
                    && planner.points().getOrDefault(id, 0.0) > 330){
                watch.add(id);
            }
        }
        watch.sort(Comparator.comparingDouble(id -> -planner.points().get(id)));
        for(String id : watch){
            gone.put(id, new int[myPicks.length]);
            landing.put(id, new double[]{0, 0});
        }
        for(int t = 0; t < trials; t++){
            Map<String, Integer> takenAt = simulator.simulateOnce(random);
            for(String id : watch){
                int at = takenAt.getOrDefault(id, 999);
                for(int i = 0; i < myPicks.length; i++){
                    if(at < myPicks[i]){
                        gone.get(id)[i]++;
                    }
                }
                landing.get(id)[0] += Math.min(at, 110);
                landing.get(id)[1]++;
            }
        }
        System.out.printf("%-22s %6s  %s%n", "QB (proj)", "mean", "P(gone) by my picks "
                + Arrays.toString(myPicks));
        for(String id : watch){
            Player p = Player.getPlayerFromSIDV2(id);
            StringBuilder row = new StringBuilder();
            for(int i = 0; i < myPicks.length; i++){
                row.append(String.format("%5.0f%%", 100.0 * gone.get(id)[i] / trials));
            }
            System.out.printf("%-16s %5.0f %6.1f  %s%n", p.lastName,
                    planner.points().get(id), landing.get(id)[0] / landing.get(id)[1],
                    row);
        }
    }
}
