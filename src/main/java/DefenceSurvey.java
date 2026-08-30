import PlayerImportAndSetup.Position;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where does each model put its defence, drafting all sixteen rounds?
 *
 * The board, the ballot and the objective all carry defences now, so every
 * variant here COULD take one at any pick. What each actually does is a
 * property of its objective, and worth seeing side by side rather than
 * inferred from a single run.
 *
 * The forced-placement sweep already established what is CORRECT - later is
 * better, flat from round 14 - so this asks a different question: do the models
 * find that on their own?
 *
 *   ./gradlew run -Pmain=DefenceSurvey [-Ptrials=40]
 */
public class DefenceSurvey {

    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", "16");
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 40);

        List<Keeper> myKeepers = DraftPlanner.keepersFromProperty(configuration);
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, myKeepers,
                model, earliness);

        System.out.printf("%nWHERE EACH MODEL PUTS ITS DEFENCE, DRAFTING ROUNDS 1-16%n");
        System.out.printf("picks %s%n%n", planner.myPicks());
        System.out.printf("%-32s %-44s %s%n", "MODEL", "PLAN", "defence at");

        Map<String, RosterValue> variants = new LinkedHashMap<>();
        variants.put("best-nine season totals", new SeasonTotalValue(planner.points()));
        for(int scenarios : new int[]{100, 400, 1200}){
            variants.put("starter sum, " + scenarios + " scen",
                    WeeklyStarterValue.forCurrentBoard(configuration, planner.points(),
                            scenarios, 424_242L));
        }
        variants.put("starter sum, 400 (other seed)",
                WeeklyStarterValue.forCurrentBoard(configuration, planner.points(),
                        400, 90_210L));

        for(Map.Entry<String, RosterValue> entry : variants.entrySet()){
            planner.scoreWith(entry.getValue());
            DraftPlanner.Plan plan = planner.plan(rollouts, 0, 0.10, DraftSimulator.SEED);
            List<Position> positions = plan.positions();
            int at = positions.indexOf(Position.DEF);
            System.out.printf("%-32s %-44s %s%n", entry.getKey(), shape(positions),
                    at < 0 ? "NEVER - streams one"
                            : "pick " + (at + 1) + " of " + positions.size()
                              + " (round " + (at + 1 <= 11 ? at + 1 : at + 3) + ")");
        }

        System.out.println("\nThe forced-placement sweep showed what is right: later is"
                + " better, flat from\nround 14. A model that never takes one is not"
                + " disagreeing - it is saying the\npick is worth less than the skill"
                + " player it displaces, which is the 0.277 rank\ncorrelation pushed one"
                + " step further.");
        System.out.println("\nWhat would be alarming is a model taking one EARLY.");
    }

    static String shape(List<Position> positions){
        StringBuilder text = new StringBuilder();
        for(Position position : positions){
            text.append(text.length() == 0 ? "" : " ").append(position);
        }
        return text.toString();
    }
}
