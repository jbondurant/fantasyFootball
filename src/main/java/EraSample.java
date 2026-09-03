import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * How many seasons are there now, and what is a result worth on them?
 *
 * The binding constraint on this whole effort was never the model - it was that
 * five seasons cannot tell a good draft plan from a lucky one. Slot and
 * opponent variation do not help, because every draw inside a season is scored
 * on the same realised football: the SEASON is the unit of independent
 * randomness, and there were five of them.
 *
 * This tool reports the new count, what it does to the error bar, and - the
 * part that is easy to skip and dishonest to skip - what the error bar still
 * is. More seasons narrows the bar; it does not make small differences real.
 *
 *   ./gradlew run -Pmain=EraSample
 */
public class EraSample {

    /** What the backtest had before this harvest: fp-adp CSVs on disk. */
    static final int SEASONS_BEFORE = 5;

    /**
     * Plan pairs sampled to measure the noise. The paired difference between
     * two plans varies season to season; its spread is what sets the bar, and
     * it is measured over many pairs rather than one so no single lucky pairing
     * sets the number.
     */
    static final int PAIRS = 4000;

    public static void main(String[] args){
        int rounds = EraIngest.rounds();
        String format = System.getProperty("format");
        Map<String, EraBoards.Board> boards = EraBoards.usable(format,
                EraIngest.MIN_RATE, EraIngest.minDepth());

        System.out.printf("%nSAMPLE SIZE AFTER THE HARVEST%n%n");
        System.out.printf("%-6s %8s %9s %8s %7s%n", "SEASON", "FORMAT", "MATCH",
                "TOP-100", "DEPTH");
        for(EraBoards.Board board : boards.values()){
            EraBoards.Match match = board.match();
            System.out.printf("%-6s %8s %8.1f%% %7.1f%% %7d%n", board.season(),
                    match.format(), match.rate() * 100, match.topRate() * 100,
                    match.skill());
        }
        System.out.printf("%nusable seasons   %d (was %d)%n", boards.size(),
                SEASONS_BEFORE);
        System.out.printf("gates            match >= %.0f%%, top-100 >= %.0f%%,"
                + " depth >= %d%n", EraIngest.MIN_RATE * 100,
                EraIngest.MIN_TOP_RATE * 100, EraIngest.minDepth());

        EraScores.Table table = EraScores.compute(boards, rounds,
                Integer.getInteger("planSample", 20000));
        System.out.printf("plans scored     %d, %d rounds, %s%n%n",
                table.plans().size(), rounds,
                Boolean.getBoolean("noKeepers") ? "no keepers"
                        : EraKeepers.describe());

        // The spread of a plan-versus-plan difference, season to season. This
        // is the quantity every comparison in the repo is fighting.
        // Two noise numbers, because they answer different questions. Any two
        // legal plans includes QB-heavy nonsense against sensible football, and
        // those disagree wildly season to season. What a decision actually
        // faces is two plans that both look good - a far tighter comparison,
        // and the one to plan against.
        double spreadAny = spread(table, contenders(table, 1.0), PAIRS);
        double spread = spread(table, contenders(table, 0.01), PAIRS);

        System.out.printf("NOISE, MEASURED%n");
        System.out.printf("   any two legal plans differ by +/- %.0f points from"
                + " season to season%n", spreadAny);
        System.out.printf("   two plans from the top 1%% differ by +/- %.0f - the"
                + " comparison a fit really makes%n", spread);
        System.out.printf("   (median across-season standard deviation over %d"
                + " random pairs)%n%n", PAIRS);

        System.out.printf("%-10s %12s %14s %16s%n", "SEASONS", "std error",
                "95% bar", "detectable at 80%");
        for(int n : new int[]{ SEASONS_BEFORE, 8, 10, boards.size(), 17, 25 }){
            double error = spread / Math.sqrt(n);
            System.out.printf("%-10d %12.1f %14.1f %16.1f%s%n", n, error,
                    1.96 * error, 2.80 * error,
                    n == boards.size() ? "   <- where the harvest lands" : "");
        }
        System.out.printf("%n95%% bar is how far apart two plans must score before the"
                + " difference is%nnot chance. The last column is the difference a fair"
                + " test would find eight%ntimes in ten - the honest planning number,"
                + " and always the bigger one.%n");

        System.out.printf("%nEFFECTIVE SAMPLE SIZE UNDER RECENCY WEIGHTING%n");
        System.out.printf("%-16s %10s %14s%n", "WEIGHTING", "n_eff", "95% bar");
        double[] flat = EraScores.flat(table.seasons().size());
        report("flat (pool all)", flat, spread);
        for(double halfLife : new double[]{ 8, 5, 3, 2 }){
            report(String.format("half-life %.0f", halfLife),
                    EraScores.decay(table.seasons(), halfLife), spread);
        }
        System.out.printf("%nDown-weighting the old seasons costs resolution - that is"
                + " what n_eff is%nsaying. It is only worth paying if the old seasons"
                + " are biased, which is%nRegimeShift's question, not this one's.%n");
    }

    /** The plans in the top fraction by mean value over every season. */
    static List<Integer> contenders(EraScores.Table table, double fraction){
        List<Integer> all = new ArrayList<>();
        for(int plan = 0; plan < table.plans().size(); plan++){
            all.add(plan);
        }
        if(fraction >= 1.0){
            return all;
        }
        List<Integer> seasons = new ArrayList<>();
        for(int s = 0; s < table.seasons().size(); s++){
            seasons.add(s);
        }
        all.sort((a, b) -> Double.compare(table.mean(b, seasons), table.mean(a, seasons)));
        return all.subList(0, Math.max(2, (int) (all.size() * fraction)));
    }

    /** Median across-season spread of the difference between two of these plans. */
    static double spread(EraScores.Table table, List<Integer> pool, int pairs){
        Random random = new Random(20260830L);
        double[] spreads = new double[pairs];
        for(int pair = 0; pair < pairs; pair++){
            int a = pool.get(random.nextInt(pool.size()));
            int b = pool.get(random.nextInt(pool.size()));
            double[] difference = new double[table.seasons().size()];
            for(int s = 0; s < difference.length; s++){
                difference[s] = table.value()[a][s] - table.value()[b][s];
            }
            spreads[pair] = standardDeviation(difference);
        }
        return median(spreads);
    }

    static void report(String label, double[] weights, double spread){
        double effective = EraScores.effectiveSampleSize(weights);
        System.out.printf("%-16s %10.1f %14.1f%n", label, effective,
                1.96 * spread / Math.sqrt(effective));
    }

    static double standardDeviation(double[] values){
        double mean = 0;
        for(double value : values){
            mean += value;
        }
        mean /= values.length;
        double sum = 0;
        for(double value : values){
            sum += (value - mean) * (value - mean);
        }
        return Math.sqrt(sum / Math.max(1, values.length - 1));
    }

    static double median(double[] values){
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
