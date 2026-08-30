import PlayerImportAndSetup.Position;

import java.util.List;
import java.util.Map;

/**
 * The 1-16 model: Model A's search, the starter-sum objective, sixteen rounds.
 *
 * Nothing here is a new engine. `DraftPlanner` does the searching exactly as it
 * does for the nine-round game; `scheduleRounds` runs the board out to sixteen
 * with the keepers occupying r12 and r13; and `RosterValue` swaps what a
 * finished roster is worth. Model A keeps its own rule and is not touched.
 *
 * The point of the swap is that the tight end and defence questions stop being
 * rules. A roster's unfillable slots take the waiver wire, so a tight end who
 * beats the wire by little adds little and the search declines him without
 * being told to - and if it instead takes one early, that is the model
 * disagreeing with the 2026-08-29 streaming measurement, which is worth
 * knowing either way.
 *
 *   ./gradlew run -Pmain=Draft16 [-Ptrials=80] [-Pscenarios=150] [-Pkeepers=Tuten,Purdy]
 */
public class Draft16 {

    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", System.getProperty("scheduleRounds", "16"));

        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 80);
        int scenarios = Integer.getInteger("scenarios", 150);
        double lambda = Double.parseDouble(System.getProperty("risk", "0"));
        double q = Double.parseDouble(System.getProperty("quantile", "0.10"));

        List<Keeper> myKeepers = DraftPlanner.keepersFromProperty(configuration);
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, myKeepers,
                model, earliness);

        System.out.printf("%nrounds 1-%d, keepers %s%n", DraftPlanner.scheduleRounds(),
                myKeepers.isEmpty() ? "as declared" : myKeepers.stream()
                        .map(k -> k.player.lastName + " r" + k.roundCanBeKept).toList());
        System.out.printf("my picks: %s%n", planner.myPicks());

        // A: Model A's rule, extended to sixteen rounds - the control
        System.out.printf("%n%-34s ", "objective: " + planner.objectiveLabel());
        DraftPlanner.Plan seasonPlan = planner.plan(rollouts, lambda, q,
                DraftSimulator.SEED);
        System.out.printf("%n   plan %s%n   value %.1f%n", shape(seasonPlan.positions()),
                seasonPlan.mean());

        // B: the starter sum
        planner.scoreWith(WeeklyStarterValue.forCurrentBoard(configuration,
                planner.points(), scenarios, 424_242L));
        System.out.printf("%n%-34s ", "objective: " + planner.objectiveLabel());
        DraftPlanner.Plan starterPlan = planner.plan(rollouts, lambda, q,
                DraftSimulator.SEED);
        System.out.printf("%n   plan %s%n   value %.1f%n", shape(starterPlan.positions()),
                starterPlan.mean());

        System.out.println("\nThe two are not comparable by VALUE - they are different"
                + " units, best-nine\nseason totals against a season of weekly starter"
                + " points. What is comparable\nis the PLAN: where the two rules want"
                + " different positions, the starter sum is\nseeing something season"
                + " totals cannot, and the round it first diverges is\nthe round bench"
                + " value starts to matter.");
    }

    static String shape(List<Position> positions){
        StringBuilder text = new StringBuilder();
        for(int round = 0; round < positions.size(); round++){
            text.append(round == 0 ? "" : " ").append(positions.get(round));
        }
        return text.toString();
    }
}
