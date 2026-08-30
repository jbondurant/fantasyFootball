import PlayerImportAndSetup.Position;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A last honest attempt to beat the folk rules: optimise the SEQUENCE itself.
 *
 * Every model so far has scored candidates one pick at a time and lost. A draft
 * plan is one object - the committed plan wins partly because its halves were
 * fitted to each other - so search over whole sequences instead.
 *
 * Hill-climbing from a starting plan: try changing each pick to each position,
 * keep whatever improves, repeat until nothing does. Scored on REAL outcomes,
 * so no model opinion enters.
 *
 * The discipline is the point. Fitting a fourteen-position sequence on five
 * seasons would memorise them - there are more free parameters than seasons. So
 * it fits on 2021-2023 and is judged ONCE on 2024-2025, and the honest number is
 * the held-out one. If the training score improves and the test score does not,
 * that is overfitting and the answer is still no.
 *
 *   ./gradlew run -Pmain=SequenceSearch
 */
public class SequenceSearch {

    static final String[] TRAIN = {"2021", "2022", "2023"};
    static final String[] TEST = {"2024", "2025"};
    static final Position[] CHOICES = {Position.QB, Position.RB, Position.WR,
            Position.TE, Position.DEF};

    public static void main(String[] args) throws Exception {
        Map<String, PlanBacktest.Board> boards = new LinkedHashMap<>();
        for(File file : new File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                String season = file.getName().split("-")[3];
                PlanBacktest.Board board = PlanBacktest.board(file, season);
                if(board != null && board.ids().size() > 150){
                    boards.put(season, board);
                }
            }
        }

        String committed = PlanBacktest.STRATEGIES.get("RUNBOOK committed");
        List<Position> best = parse(committed);
        double bestTrain = score(boards, TRAIN, best);
        System.out.printf("%nstarting from the committed plan%n   %s%n   train %.0f,"
                + " test %.0f%n%n", shape(best), bestTrain, score(boards, TEST, best));

        boolean improved = true;
        int pass = 0;
        while(improved && pass++ < 6){
            improved = false;
            for(int slot = 0; slot < best.size(); slot++){
                for(Position candidate : CHOICES){
                    if(best.get(slot) == candidate){
                        continue;
                    }
                    List<Position> trial = new ArrayList<>(best);
                    trial.set(slot, candidate);
                    if(!legal(trial)){
                        continue;
                    }
                    double scored = score(boards, TRAIN, trial);
                    if(scored > bestTrain + 0.5){
                        bestTrain = scored;
                        best = trial;
                        improved = true;
                    }
                }
            }
            System.out.printf("pass %d: train %.0f   %s%n", pass, bestTrain, shape(best));
        }

        double testScore = score(boards, TEST, best);
        double committedTrain = score(boards, TRAIN, parse(committed));
        double committedTest = score(boards, TEST, parse(committed));

        System.out.printf("%n%-28s %10s %10s%n", "", "train", "TEST");
        System.out.printf("%-28s %10.0f %10.0f%n", "RUNBOOK committed", committedTrain,
                committedTest);
        System.out.printf("%-28s %10.0f %10.0f%n", "hill-climbed sequence", bestTrain,
                testScore);
        System.out.printf("%-28s %+10.0f %+10.0f%n", "difference",
                bestTrain - committedTrain, testScore - committedTest);

        System.out.printf("%nfitted plan: %s%n", shape(best));
        System.out.println(testScore > committedTest
                ? "\nIT BEATS THE RULES OUT OF SAMPLE. Worth taking seriously."
                : "\nIt gained on training and lost on test - that is overfitting,"
                  + " and the\nanswer is still no. Fourteen positions fitted on three"
                  + " seasons was always\ngoing to memorise them.");
    }

    /** A roster that can actually be fielded: one QB-ish, a TE, a defence. */
    static boolean legal(List<Position> plan){
        Map<Position, Integer> counts = new java.util.EnumMap<>(Position.class);
        for(Position position : plan){
            counts.merge(position, 1, Integer::sum);
        }
        return counts.getOrDefault(Position.QB, 0) >= 1
                && counts.getOrDefault(Position.QB, 0) <= 2
                && counts.getOrDefault(Position.RB, 0) >= 2
                && counts.getOrDefault(Position.WR, 0) >= 3
                && counts.getOrDefault(Position.TE, 0) >= 1
                && counts.getOrDefault(Position.DEF, 0) == 1;
    }

    static double score(Map<String, PlanBacktest.Board> boards, String[] seasons,
                        List<Position> plan){
        double total = 0;
        for(String season : seasons){
            total += PlanBacktest.score(boards.get(season), shape(plan));
        }
        return total / seasons.length;
    }

    static List<Position> parse(String sequence){
        List<Position> out = new ArrayList<>();
        for(String token : sequence.trim().split("\\s+")){
            out.add(Position.valueOf(token));
        }
        return out;
    }

    static String shape(List<Position> plan){
        StringBuilder text = new StringBuilder();
        for(Position position : plan){
            text.append(text.length() == 0 ? "" : " ").append(position);
        }
        return text.toString();
    }
}
