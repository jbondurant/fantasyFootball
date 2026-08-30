import PlayerImportAndSetup.Position;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Let the data decide whether the folk rules are right, without being told them.
 *
 * Every previous attempt either replaced the committed plan and lost, or was
 * started FROM it - which is not a model converging on a prior, it is a prior
 * with a model bolted on. This is the honest version: learn a small correction
 * to the objective from outcomes alone, knowing nothing about the plan, and see
 * where it lands.
 *
 * The parameter count is the whole design. Fourteen free positions fitted on
 * three seasons memorised them - the search opened with a tight end at pick 7
 * and lost 191 points out of sample. So fit FOUR numbers instead: one
 * multiplier per position on the model's marginal, receivers pinned at 1.0 as
 * the reference. If the objective systematically underprices running backs,
 * that is exactly four numbers' worth of error, and three seasons can carry
 * four numbers.
 *
 * Coordinate ascent on 2021-2023, judged ONCE on 2024-2025. If it converges
 * toward the RB-heavy shape this league's folk rule already uses, that is the
 * data finding the prior on its own, which is worth much more than being handed
 * it.
 *
 *   ./gradlew run -Pmain=PositionWeights [-Pscenarios=250]
 */
public class PositionWeights {

    static final String[] TRAIN = {"2021", "2022", "2023"};
    static final String[] TEST = {"2024", "2025"};
    static final Position[] TUNED = {Position.RB, Position.TE, Position.QB, Position.DEF};

    public static void main(String[] args) throws Exception {
        int scenarios = Integer.getInteger("scenarios", 250);
        Map<String, PlanBacktest.Board> boards = new LinkedHashMap<>();
        Map<String, List<OutcomeDistributions.Season>> bySeason = OutcomeDistributions.all();
        for(File file : new File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                String season = file.getName().split("-")[3];
                PlanBacktest.Board board = PlanBacktest.board(file, season);
                if(board != null && board.ids().size() > 150){
                    boards.put(season, board);
                }
            }
        }

        Map<Position, Double> weights = new EnumMap<>(Position.class);
        for(Position position : Position.values()){
            weights.put(position, 1.0);
        }

        System.out.printf("%nFITTING FOUR NUMBERS ON %s, JUDGED ONCE ON %s%n",
                String.join("+", TRAIN), String.join("+", TEST));
        System.out.println("receivers pinned at 1.00 as the reference;"
                + " the plan is never consulted\n");
        double best = run(boards, TRAIN, bySeason, weights, scenarios);
        System.out.printf("start   RB 1.00  TE 1.00  QB 1.00  DEF 1.00   train %.0f%n", best);

        double[] grid = {0.6, 0.8, 1.0, 1.3, 1.7, 2.2};
        for(int pass = 0; pass < 2; pass++){
            for(Position position : TUNED){
                double bestWeight = weights.get(position);
                for(double candidate : grid){
                    weights.put(position, candidate);
                    double scored = run(boards, TRAIN, bySeason, weights, scenarios);
                    if(scored > best + 0.5){
                        best = scored;
                        bestWeight = candidate;
                    }
                }
                weights.put(position, bestWeight);
            }
            System.out.printf("pass %d  RB %.2f  TE %.2f  QB %.2f  DEF %.2f   train %.0f%n",
                    pass + 1, weights.get(Position.RB), weights.get(Position.TE),
                    weights.get(Position.QB), weights.get(Position.DEF), best);
        }

        double fittedTest = run(boards, TEST, bySeason, weights, scenarios);
        Map<Position, Double> flat = new EnumMap<>(Position.class);
        for(Position position : Position.values()){
            flat.put(position, 1.0);
        }
        double flatTest = run(boards, TEST, bySeason, flat, scenarios);
        double committedTest = 0;
        for(String season : TEST){
            committedTest += PlanBacktest.score(boards.get(season),
                    PlanBacktest.STRATEGIES.get("RUNBOOK committed"));
        }
        committedTest /= TEST.length;

        System.out.printf("%n%-32s %10s%n", "", "TEST");
        System.out.printf("%-32s %10.0f%n", "unweighted model", flatTest);
        System.out.printf("%-32s %10.0f%n", "fitted weights", fittedTest);
        System.out.printf("%-32s %10.0f%n", "RUNBOOK committed (the prior)", committedTest);
        System.out.printf("%n%s%n", fittedTest > committedTest
                ? "IT REACHES THE PRIOR OR BETTER, from outcomes alone."
                : fittedTest > flatTest
                ? "Improved on the raw model out of sample, but has not reached the prior."
                : "The fit did not survive the holdout.");
        System.out.printf("%nwhat it learned: RB %.2f  TE %.2f  QB %.2f  DEF %.2f%n",
                weights.get(Position.RB), weights.get(Position.TE),
                weights.get(Position.QB), weights.get(Position.DEF));
    }

    static double run(Map<String, PlanBacktest.Board> boards, String[] seasons,
                      Map<String, List<OutcomeDistributions.Season>> bySeason,
                      Map<Position, Double> weights, int scenarios){
        double total = 0;
        for(String season : seasons){
            total += draft(boards.get(season),
                    PolicyBacktest.poolWithout(bySeason, season), weights, scenarios);
        }
        return total / seasons.length;
    }

    static double draft(PlanBacktest.Board board,
                        Map<String, List<OutcomeDistributions.Season>> pool,
                        Map<Position, Double> weights, int scenarios){
        Map<String, Position> positionOf = new HashMap<>(board.positionOf());
        Map<String, Integer> tierOf = new HashMap<>();
        Map<Position, Integer> next = new EnumMap<>(Position.class);
        for(String id : board.ids()){
            tierOf.put(id, (next.merge(positionOf.get(id), 1, Integer::sum) - 1)
                    / WeeklyStarterValue.TIER);
        }
        WeeklyStarterValue value = new WeeklyStarterValue(positionOf, tierOf, pool,
                PolicyBacktest.wireFrom(pool), scenarios, 424_242L);

        Set<String> gone = new HashSet<>();
        List<String> mine = new ArrayList<>();
        List<Position> plan = new ArrayList<>();
        Set<Integer> myPicks = new HashSet<>();
        for(int pick : PlanBacktest.MY_PICKS){
            myPicks.add(pick);
        }
        for(int pick = 1; pick <= 200 && mine.size() < PlanBacktest.MY_PICKS.length; pick++){
            if(myPicks.contains(pick)){
                double base = value.of(mine);
                String best = null;
                Position bestPosition = null;
                double bestScore = -Double.MAX_VALUE;
                for(Position position : new Position[]{Position.QB, Position.RB,
                        Position.WR, Position.TE, Position.DEF}){
                    if(!PolicyBacktest.worthTaking(position, plan)){
                        continue;
                    }
                    String candidate = PlanBacktest.bestAvailable(board, gone, position);
                    if(candidate == null){
                        continue;
                    }
                    List<String> trial = new ArrayList<>(mine);
                    trial.add(candidate);
                    double scored = weights.get(position) * (value.of(trial) - base);
                    if(scored > bestScore){
                        bestScore = scored;
                        best = candidate;
                        bestPosition = position;
                    }
                }
                if(best != null){
                    mine.add(best);
                    gone.add(best);
                    plan.add(bestPosition);
                }
            }
            else {
                String other = PlanBacktest.bestAvailableSkill(board, gone);
                if(other != null){
                    gone.add(other);
                }
            }
        }
        return PlanBacktest.seasonPoints(board, mine);
    }
}
