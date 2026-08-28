import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Is the Abusing Draft Rankings "Landmine Score" a signal we do not already
 * hold, or a restatement of two feeds we do?
 *
 * MODEL.md recorded it as "an independent, room-specific snipe-risk signal to
 * cross-check snipes()". That was written before anyone read the author's own
 * definition, which is on the sheet's Main tab:
 *
 *   "A 1-10 scale rating how risky a player is in your draft room, based on
 *    how much earlier they're ranked on your platform versus expert consensus
 *    (ADP + FantasyPros) ... 1 = Great value, 10 = Landmine, avoid"
 *
 * That is a function of platform rank minus consensus - and both of those are
 * columns in the same CSV. So the claim is testable: fit landmine on the gap
 * we already have and see how much is left over. A high R-squared means there
 * is no new information in the column and it must NOT be fed to the model as
 * if it were a third opinion; a low one means the author is adding something.
 *
 *     ./gradlew run -Pmain=LandmineCheck
 *     ./gradlew run -Pmain=LandmineCheck -Pas=data/sleeper-defaults-2026-20260820.csv
 */
public class LandmineCheck {

    record Player(String name, double consensus, double fpEcr, double sleeperRank,
                  double landmine) {
        /** the sheet's own stated input: how much earlier the platform ranks him */
        double platformGap(){ return consensus - sleeperRank; }
        double consensusGap(){ return fpEcr - sleeperRank; }
    }

    static List<Player> read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String[] header = lines.get(0).split(",");
        int name = -1, consensus = -1, ecr = -1, rank = -1, landmine = -1;
        for(int column = 0; column < header.length; column++){
            switch(header[column].trim()){
                case "name" -> name = column;
                case "consensus_adp" -> consensus = column;
                case "fp_ecr" -> ecr = column;
                case "sleeper_rank" -> rank = column;
                case "landmine" -> landmine = column;
            }
        }
        if(landmine < 0){
            throw new IllegalStateException(file + " has no landmine column; only the "
                    + "2026 sheet layout carries one");
        }
        List<Player> players = new ArrayList<>();
        for(String line : lines.subList(1, lines.size())){
            String[] cells = line.split(",");
            try {
                players.add(new Player(cells[name],
                        Double.parseDouble(cells[consensus]),
                        Double.parseDouble(cells[ecr]),
                        Double.parseDouble(cells[rank]),
                        Double.parseDouble(cells[landmine])));
            }
            catch(RuntimeException incomplete){ /* blank or short row */ }
        }
        return players;
    }

    /** Ordinary least squares of y on the given predictors plus an intercept. */
    static double[] fit(double[][] predictors, double[] y){
        int terms = predictors[0].length + 1;
        double[][] normal = new double[terms][terms + 1];
        for(int row = 0; row < y.length; row++){
            double[] x = new double[terms];
            x[0] = 1;
            System.arraycopy(predictors[row], 0, x, 1, terms - 1);
            for(int a = 0; a < terms; a++){
                for(int b = 0; b < terms; b++){
                    normal[a][b] += x[a] * x[b];
                }
                normal[a][terms] += x[a] * y[row];
            }
        }
        for(int pivot = 0; pivot < terms; pivot++){
            int best = pivot;
            for(int row = pivot; row < terms; row++){
                if(Math.abs(normal[row][pivot]) > Math.abs(normal[best][pivot])){
                    best = row;
                }
            }
            double[] swap = normal[pivot]; normal[pivot] = normal[best]; normal[best] = swap;
            for(int row = 0; row < terms; row++){
                if(row == pivot || normal[pivot][pivot] == 0){
                    continue;
                }
                double factor = normal[row][pivot] / normal[pivot][pivot];
                for(int column = pivot; column <= terms; column++){
                    normal[row][column] -= factor * normal[pivot][column];
                }
            }
        }
        double[] coefficients = new double[terms];
        for(int term = 0; term < terms; term++){
            coefficients[term] = normal[term][terms] / normal[term][term];
        }
        return coefficients;
    }

    static double rSquared(double[][] predictors, double[] y, double[] coefficients){
        double mean = 0;
        for(double value : y){
            mean += value / y.length;
        }
        double residual = 0;
        double total = 0;
        for(int row = 0; row < y.length; row++){
            double predicted = coefficients[0];
            for(int term = 0; term < predictors[row].length; term++){
                predicted += coefficients[term + 1] * predictors[row][term];
            }
            residual += Math.pow(y[row] - predicted, 2);
            total += Math.pow(y[row] - mean, 2);
        }
        return 1 - residual / total;
    }

    static void report(String label, List<Player> players,
                       java.util.function.Function<Player, double[]> features){
        double[][] predictors = new double[players.size()][];
        double[] y = new double[players.size()];
        for(int row = 0; row < players.size(); row++){
            predictors[row] = features.apply(players.get(row));
            y[row] = players.get(row).landmine();
        }
        double[] coefficients = fit(predictors, y);
        double r2 = rSquared(predictors, y, coefficients);
        System.out.printf("  %-46s R2 = %.3f%n", label, r2);
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(System.getProperty("as",
                "data/sleeper-defaults-2026-20260827.csv"));
        List<Player> players = read(file);
        System.out.printf("Landmine score vs feeds we already hold - %s, %d players%n%n",
                file.getFileName(), players.size());

        System.out.println("How much of the landmine score is explained by columns"
                + " sitting beside it?");
        report("platform gap (consensus ADP - Sleeper rank)", players,
                p -> new double[]{p.platformGap()});
        report("+ FantasyPros gap (ECR - Sleeper rank)", players,
                p -> new double[]{p.platformGap(), p.consensusGap()});
        report("+ where on the board he sits (log rank)", players,
                p -> new double[]{p.platformGap(), p.consensusGap(),
                        Math.log(p.sleeperRank())});

        // Whatever the fit misses is the only part that could be new. Show the
        // players the two gaps cannot account for.
        double[][] predictors = new double[players.size()][];
        double[] y = new double[players.size()];
        for(int row = 0; row < players.size(); row++){
            Player player = players.get(row);
            predictors[row] = new double[]{player.platformGap(), player.consensusGap(),
                    Math.log(player.sleeperRank())};
            y[row] = player.landmine();
        }
        double[] coefficients = fit(predictors, y);
        record Residual(String name, double rank, double actual, double predicted) {}
        List<Residual> residuals = new ArrayList<>();
        for(int row = 0; row < players.size(); row++){
            double predicted = coefficients[0];
            for(int term = 0; term < 3; term++){
                predicted += coefficients[term + 1] * predictors[row][term];
            }
            residuals.add(new Residual(players.get(row).name(),
                    players.get(row).sleeperRank(), y[row], predicted));
        }
        residuals.sort(Comparator.comparingDouble(r -> -Math.abs(r.actual() - r.predicted())));
        System.out.println("\nPlayers the gaps do NOT explain - the only place new"
                + " information could be:");
        System.out.printf("  %-26s %6s %8s %10s %8s%n",
                "PLAYER", "RANK", "LANDMINE", "PREDICTED", "MISS");
        for(Residual residual : residuals.subList(0, Math.min(12, residuals.size()))){
            System.out.printf("  %-26s %6.0f %8.1f %10.1f %+8.1f%n", residual.name(),
                    residual.rank(), residual.actual(), residual.predicted(),
                    residual.actual() - residual.predicted());
        }
    }
}
