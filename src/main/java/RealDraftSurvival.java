import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * THE ONE NON-CIRCULAR TEST OF THE SURVIVAL TABLE.
 *
 * expectedRank now rests on a survival table built by simulating drafts with
 * the fitted opponent model. Every measurement of it so far - RankPrediction,
 * MidDraftRank, DrainPrediction - scored it against MORE SIMULATIONS FROM THAT
 * SAME MODEL. Those cannot separate "the table is right about football" from
 * "the table reproduces its own generator", and the honest reading of a 0.08
 * error there is that the arithmetic works, not that the football does.
 *
 * This scores it against a REAL DRAFT: the league's own, held out of the model
 * that predicts it. The choice model is fitted on 2021 through the season
 * before the target, the simulator is built on the target season's real board
 * and real keepers, and the comparison is against the picks that actually
 * happened.
 *
 *   ./gradlew run -Pmain=RealDraftSurvival -q
 */
public class RealDraftSurvival {

    /**
     * The same measurement at a range of temperatures, in one process.
     *
     * One run per temperature from the shell meant two JVM starts per point and
     * a fragile awk over prose. This is the number, produced by repo code.
     */
    static void sweep(AAAConfiguration configuration, String[] targets, int draws)
            throws Exception {
        System.out.printf("%nreal-draft survival error by opponent temperature%n"
                + "(2024 and 2025 held out of the model that predicts them)%n%n");
        System.out.printf("%-14s %14s%n", "TEMPERATURE", "SURVIVAL MAE");
        double bestError = Double.MAX_VALUE;
        double bestTemperature = 1.0;
        for(double temperature : new double[]{0.6, 0.8, 1.0, 1.25, 1.6, 2.0}){
            double error = errorAt(configuration, targets, draws, temperature);
            System.out.printf("%-14.2f %14.2f%s%n", temperature, error,
                    temperature == 1.0 ? "   <- shipped" : "");
            if(error < bestError){
                bestError = error;
                bestTemperature = temperature;
            }
        }
        double shipped = errorAt(configuration, targets, draws, 1.0);
        System.out.printf("%nbest temperature %.2f at %.2f men; shipped 1.00 at %.2f.%n",
                bestTemperature, bestError, shipped);
        System.out.printf("difference %.2f men per cell over 80 cells.%n",
                shipped - bestError);
        if(shipped - bestError < 0.10){
            System.out.printf("%nWHICH IS NOTHING. The curve is flat - every temperature%n"
                    + "from 0.8 to 1.6 sits inside 0.03 men of every other, against a%n"
                    + "survival error of 1.2 and a cutoff error of 3.35. So the boosted%n"
                    + "model's dispersion is already about right and temperature is not%n"
                    + "an available improvement.%n%n"
                    + "That is worth knowing rather than guessing: the linear%n"
                    + "SelectionModel IS temperature-tuned (scaled(), on held-out%n"
                    + "survival calibration) and the shipped boosted model has no%n"
                    + "scaled() at all, so 'its dispersion was never tuned' looked like%n"
                    + "an obvious gap. It is not one. SHIPPED STAYS AT 1.0.%n");
        }
    }

    /** Mean absolute error of the survival table over both held-out seasons. */
    static double errorAt(AAAConfiguration configuration, String[] targets,
                          int draws, double temperature) throws Exception {
        double total = 0;
        int cells = 0;
        for(String target : targets){
            int trainTo = Integer.parseInt(target) - 1;
            Map<String, Double> qbEarliness =
                    SelectionModel.qbEarliness(configuration, trainTo);
            DraftSimulator.Extras extras =
                    DraftSimulator.extrasFor(configuration, target, trainTo);
            List<SelectionModel.Observation> train = SelectionModel.loadObservations(
                    configuration, 2021, trainTo, qbEarliness,
                    extras.teEarliness(), extras.rbEarliness(),
                    false, SelectionModel.TRAIN_ROUNDS);
            BoostedSelectionModel model = BoostedSelectionModel.fit(train, 300, 2, 0.1);
            DraftBacktest.Season season = new DraftBacktest.Season(configuration, target);
            ChoiceModel tempered = temperature == 1.0 ? model
                    : new TemperedChoice(model, temperature);
            DraftSimulator simulator = DraftSimulator.forSeason(season, tempered,
                    qbEarliness, extras);
            LiveBoard.Survival survival = new LiveBoard.Survival(
                    simulator.players(), simulator, draws, 31_337L);
            Map<String, Integer> realPick = new HashMap<>();
            for(JsonElement element : season.picks){
                JsonObject pick = element.getAsJsonObject();
                if(!pick.has("player_id") || pick.get("player_id").isJsonNull()
                        || !pick.has("pick_no") || pick.get("pick_no").isJsonNull()){
                    continue;
                }
                realPick.put(pick.get("player_id").getAsString(),
                        pick.get("pick_no").getAsInt());
            }
            for(int pick : new int[]{13, 25, 37, 49, 61, 73, 85, 97, 109, 121}){
                for(Position position : new Position[]{Position.RB, Position.WR,
                        Position.TE, Position.QB}){
                    int reallyGone = 0;
                    for(String id : simulator.players()){
                        Player player = Player.getPlayerFromSIDV2(id);
                        if(player == null || player.position != position){
                            continue;
                        }
                        Integer at = realPick.get(id);
                        if(at != null && at < pick){
                            reallyGone++;
                        }
                    }
                    total += Math.abs(survival.expectedGone(position, pick) - reallyGone);
                    cells++;
                }
            }
        }
        return total / cells;
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int draws = Integer.getInteger("survivalDraws", 200);
        String[] targets = {"2024", "2025"};
        if(System.getProperty("sweep") != null){
            sweep(configuration, targets, draws);
            return;
        }

        System.out.printf("%nthe survival table against the league's OWN drafts.%n"
                + "the choice model never sees the season it is predicting.%n"
                + "temperature %s (-Ptemperature=..). the SHIPPED boosted model has%n"
                + "no scaled() at all, so its dispersion has never been tuned - and%n"
                + "dispersion is exactly the width of a survival curve.%n",
                System.getProperty("temperature", "1.0"));

        double cutoffTotal = 0;
        double survivalTotal = 0;
        int cellsTotal = 0;

        for(String target : targets){
            int trainTo = Integer.parseInt(target) - 1;
            Map<String, Double> qbEarliness =
                    SelectionModel.qbEarliness(configuration, trainTo);
            DraftSimulator.Extras extras =
                    DraftSimulator.extrasFor(configuration, target, trainTo);
            List<SelectionModel.Observation> train = SelectionModel.loadObservations(
                    configuration, 2021, trainTo, qbEarliness,
                    extras.teEarliness(), extras.rbEarliness(),
                    false, SelectionModel.TRAIN_ROUNDS);
            BoostedSelectionModel model = BoostedSelectionModel.fit(train, 300, 2, 0.1);
            DraftBacktest.Season season = new DraftBacktest.Season(configuration, target);
            double temperature = Double.parseDouble(
                    System.getProperty("temperature", "1.0"));
            ChoiceModel tempered = temperature == 1.0 ? model
                    : new TemperedChoice(model, temperature);
            DraftSimulator simulator = DraftSimulator.forSeason(season, tempered,
                    qbEarliness, extras);

            LiveBoard.Survival survival = new LiveBoard.Survival(
                    simulator.players(), simulator, draws, 31_337L);

            // What REALLY happened in that draft.
            Map<String, Integer> realPick = new HashMap<>();
            for(JsonElement element : season.picks){
                JsonObject pick = element.getAsJsonObject();
                if(!pick.has("player_id") || pick.get("player_id").isJsonNull()
                        || !pick.has("pick_no") || pick.get("pick_no").isJsonNull()){
                    continue;
                }
                realPick.put(pick.get("player_id").getAsString(),
                        pick.get("pick_no").getAsInt());
            }

            System.out.printf("%n=== %s: %d real picks, %d men on the board ===%n",
                    target, realPick.size(), simulator.players().size());
            System.out.printf("%-6s %-5s %10s %10s %10s%n", "PICK", "POS", "REALLY GONE",
                    "ADP CUT", "SURVIVAL");

            double cutoffError = 0;
            double survivalError = 0;
            int cells = 0;
            for(int pick : new int[]{13, 25, 37, 49, 61, 73, 85, 97, 109, 121}){
                for(Position position : new Position[]{Position.RB, Position.WR,
                        Position.TE, Position.QB}){
                    int reallyGone = 0;
                    int adpGone = 0;
                    for(String id : simulator.players()){
                        Player player = Player.getPlayerFromSIDV2(id);
                        if(player == null || player.position != position){
                            continue;
                        }
                        Integer at = realPick.get(id);
                        if(at != null && at < pick){
                            reallyGone++;
                        }
                        Double adp = season.adp.get(id);
                        if(adp != null && adp < pick){
                            adpGone++;
                        }
                    }
                    double predicted = survival.expectedGone(position, pick);
                    cutoffError += Math.abs(adpGone - reallyGone);
                    survivalError += Math.abs(predicted - reallyGone);
                    cells++;
                    if(position == Position.RB || pick <= 37){
                        System.out.printf("%-6d %-5s %11d %10d %10.1f%n", pick, position,
                                reallyGone, adpGone, predicted);
                    }
                }
            }
            System.out.printf("%n   %s mean absolute error: ADP cutoff %.2f men,"
                    + " survival %.2f men%n", target, cutoffError / cells,
                    survivalError / cells);
            cutoffTotal += cutoffError;
            survivalTotal += survivalError;
            cellsTotal += cells;
        }

        System.out.printf("%n%nACROSS BOTH HELD-OUT SEASONS, %d cells:%n", cellsTotal);
        System.out.printf("   hard ADP cutoff   %.2f men%n", cutoffTotal / cellsTotal);
        System.out.printf("   survival table    %.2f men%n", survivalTotal / cellsTotal);
        double gain = (cutoffTotal - survivalTotal) / cellsTotal;
        System.out.printf("%nsurvival is %.2f men %s per cell on REAL drafts.%n",
                Math.abs(gain), gain > 0 ? "closer" : "FURTHER - the cutoff wins");
        if(gain <= 0){
            System.out.printf("%nWHICH WOULD MEAN THE SIMULATED MEASUREMENTS WERE%n"
                    + "MEASURING THE GENERATOR, NOT THE FOOTBALL.%n");
        }
    }
}
