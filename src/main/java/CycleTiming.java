import PlayerImportAndSetup.Position;
import java.io.*;
import java.util.*;

/**
 * HOW LONG DOES ONE DRAFT2026 CYCLE ACTUALLY TAKE?
 *
 * Justin has sixty seconds a pick. DRAFT-READY says the cycle "runs in 4-11s",
 * which was measured on the bare nine-round DraftNight - not on Draft2026,
 * which forces scheduleRounds=16 and runs the board model first. Underquoting
 * this is the dangerous direction: if he waits until forty seconds are gone
 * before pressing enter, a twenty-three second cycle misses the pick.
 *
 * Timed with output swallowed, so this measures engine time and not the
 * terminal.
 *
 *   ./gradlew run -Pmain=CycleTiming -Pkeepers=Tuten,Purdy -q
 *
 * Times a FIXED board: the first six picks of the real 2026 draft, so Justin's
 * pick 7 is on the clock with Model A live. Before 2026-09-02 it froze whatever
 * the live draft held, which measured a pick-1 board before the draft and a
 * finished draft (Model A silent) after it - the same tool, three different
 * questions. That real pick-7 board took the committee 42s on draft night.
 */
public class CycleTiming {
    /** Gibbs, Robinson, Chase, McCaffrey, St. Brown, Cook - picks 1-6 of the real 2026 draft. */
    static final List<String> PICK_SEVEN_BOARD =
            List.of("9221", "9509", "7564", "4034", "7547", "8138");

    public static void main(String[] args) throws Exception {
                // ONE WARM-UP, SHARED. See LiveSetup: five separate copies of this
        // block had drifted apart, three of them measuring a configuration
        // nobody runs.
        LiveSetup setup = LiveSetup.forTonight();
        AAAConfiguration configuration = setup.configuration;
        DraftPlanner planner = setup.planner;
        DraftSimulator simulator = setup.simulator;
        Set<String> kept = setup.kept;
        Map<Position, double[]> curve = setup.curve;
        Map<Position, List<List<Double>>> pools = setup.pools;
        List<String> order = setup.order;
        List<PairwiseOdds.Man> men = setup.men;
        TimingPlanner timing = new TimingPlanner(planner);
        timing.fillWaitingTable(200);
        Map<String, Double> points = planner.points();
        Map<Position, Double> benchBaseRate = BenchValue.overWireByPosition(configuration);
        // Build the survival table, as Draft2026 does - otherwise this times a
        // path that falls back to the ADP cutoff and is not what he runs.
        double survivalCost = setup.survivalSeconds;
        String draftID = configuration.getDraftID();

        PrintStream real = System.out;
        PrintStream quiet = new PrintStream(OutputStream.nullOutputStream());

        real.printf("%nsurvival table %.0fs at warm, paid once%n", survivalCost);
        real.printf("%n%-10s %10s %10s %10s%n", "", "BOARD", "MODEL A", "CYCLE");
        double worst = 0;
        for(int run = 1; run <= 3; run++){
            LiveDraft.freezeWith(PICK_SEVEN_BOARD);
            System.setOut(quiet);
            long a = System.nanoTime();
            LiveBoard.answer(configuration, planner, simulator, draftID, curve, pools,
                    order, men, kept);
            long b = System.nanoTime();
            DraftNight.answer(configuration, planner, timing, simulator, points,
                    benchBaseRate, draftID, 150, 60, 200);
            long c = System.nanoTime();
            System.setOut(real);
            LiveDraft.thaw();
            double board = (b - a) / 1e9;
            double modelA = (c - b) / 1e9;
            worst = Math.max(worst, board + modelA);
            real.printf("run %-6d %10.1f %10.1f %10.1f%n", run, board, modelA,
                    board + modelA);
        }
        real.printf("%nworst cycle measured: %.1fs against a 60 second clock.%n", worst);
        real.printf("that leaves %.0f seconds of slack - press enter EARLY, not at :40.%n",
                60 - worst);
        real.printf("%nthe board model alone answers in about a second, and it is the%n"
                + "one that knows the 24 keepers. if the clock is short, read it and%n"
                + "let Model A arrive while you decide.%n");
    }
}
