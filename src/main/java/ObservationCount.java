import java.util.Map;

/**
 * Why five seasons was never a problem for Model A.
 *
 * The question is not how many SEASONS a model sees, it is how many
 * OBSERVATIONS its fitted parts see - and Model A and the 1-16 attempts differ
 * by three orders of magnitude on exactly that.
 *
 *   ./gradlew run -Pmain=ObservationCount
 */
public class ObservationCount {

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);

        int picks = SelectionModel.loadObservations(configuration, 2021, last, earliness,
                Map.of(), Map.of(), false, SelectionModel.TRAIN_ROUNDS).size();
        int playerSeasons = 0;
        for(var season : OutcomeDistributions.all().values()){
            playerSeasons += season.size();
        }

        System.out.printf("%nWHAT EACH LAYER IS FITTED ON%n%n");
        System.out.printf("%-46s %10s%n", "LAYER", "examples");
        System.out.printf("%-46s %10d%n",
                "Model A choice model (who gets taken)", picks);
        System.out.printf("%-46s %10d%n",
                "outcome distributions (games, scoring)", playerSeasons);
        System.out.printf("%-46s %10d%n",
                "Model A objective: best nine of projections", 0);
        System.out.printf("%-46s %10d%n",
                "1-16 objective: weekly starter sum", 0);
        System.out.printf("%-46s %10d%n",
                "a plan fitted against SEASON outcomes", 5);

        System.out.println("\nModel A never fits anything at the season level. Its"
                + " objective has NO free\nparameters - the best legal nine out of"
                + " projections is a definition, not a fit -\nand the two layers it does"
                + " learn see " + picks + " picks and " + playerSeasons + " player-seasons.");
        System.out.println("\nEverything that overfitted today was fitted against whole"
                + " SEASON outcomes, of\nwhich there are five. A season's roster score is"
                + " ONE example. Fourteen\npositions, or even four position weights,"
                + " against five examples.");
        System.out.println("\nSo five seasons was never the constraint on Model A. It is"
                + " the constraint on\nTUNING A PLAN, which Model A never does.");
    }
}
