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
 */
public class CycleTiming {
    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", "16");
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        DraftSimulator simulator = planner.simulator();
        TimingPlanner timing = new TimingPlanner(planner);
        timing.fillWaitingTable(200);
        Map<String, Double> points = planner.points();
        Map<Position, Double> benchBaseRate = BenchValue.overWireByPosition(configuration);
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Set<String> kept = LiveBoard.kept(configuration);
        Map<Position, double[]> curve = LiveBoard.thisYear(planner, kept);
        Map<Position, List<List<Double>>> pools =
                new EnumMap<>(BoardValue.pools(men, curve));
        List<List<Double>> defence = LiveBoard.defenceScatter();
        if(!defence.isEmpty()){
            pools.put(Position.DEF, defence);
        }
        // Build the survival table, as Draft2026 does - otherwise this times a
        // path that falls back to the ADP cutoff and is not what he runs.
        double survivalCost = LiveBoard.warmSurvival(planner, simulator);
        String draftID = configuration.getDraftID();

        PrintStream real = System.out;
        PrintStream quiet = new PrintStream(OutputStream.nullOutputStream());

        real.printf("%nsurvival table %.0fs at warm, paid once%n", survivalCost);
        real.printf("%n%-10s %10s %10s %10s%n", "", "BOARD", "MODEL A", "CYCLE");
        double worst = 0;
        for(int run = 1; run <= 3; run++){
            LiveDraft.freeze(draftID);
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
