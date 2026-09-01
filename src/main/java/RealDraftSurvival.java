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

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int draws = Integer.getInteger("survivalDraws", 200);
        String[] targets = {"2024", "2025"};

        System.out.printf("%nthe survival table against the league's OWN drafts.%n"
                + "the choice model never sees the season it is predicting.%n");

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
            DraftSimulator simulator = DraftSimulator.forSeason(season, model,
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
