import PlayerImportAndSetup.Position;
import java.io.*;
import java.util.*;

/**
 * AT HOW MANY OF HIS FOURTEEN SEATS DOES THE MODEL ACTUALLY HAVE AN OPINION?
 *
 * The board model prints a verdict at every seat. Now that the top two rows are
 * compared PAIRED, it can also say whether that verdict is separated from the
 * runner-up or is a coin flip dressed as a decision - and the honest summary of
 * this whole project is that it will be a coin flip at a lot of them.
 *
 * Knowing WHICH ones is worth more than any single recommendation: those are
 * the seats where Justin's own read on the players is the better instrument,
 * and where he should not defer to the screen.
 *
 *   ./gradlew run -Pmain=OpinionCount -Pkeepers=Tuten,Purdy -q
 */
public class OpinionCount {
    public static void main(String[] args) throws Exception {
        LiveSetup setup = LiveSetup.forTonight();
        DraftPlanner planner = setup.planner;
        DraftSimulator simulator = setup.simulator;
        Set<String> kept = setup.kept;

        boolean[] dumped = {false};
        PrintStream real = System.out;
        PrintStream quiet = new PrintStream(OutputStream.nullOutputStream());
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();

        int seedCount = Integer.getInteger("seeds", 5);
        real.printf("%nwhere the model has an opinion, and where it is guessing.%n"
                + "%s%n%n", setup.rule());
        real.printf("%d simulated rooms. The margin at a seat depends on who the room%n"
                + "actually took, so one draw is not an answer.%n%n", seedCount);
        real.printf("%-6s %-6s %-34s %s%n", "PICK", "", "MARGIN (first room)",
                "ACROSS ROOMS");

        Map<Integer, Integer> separatedAt = new TreeMap<>();
        Map<Integer, Integer> seenAt = new TreeMap<>();
        Map<Integer, String> exampleAt = new TreeMap<>();
        for(int seed = 0; seed < seedCount; seed++){
        List<String> taken = new ArrayList<>();
        DraftSimulator.SimState state = simulator.initialState();
        Random random = new Random(20260901L + 7919L * seed);
        int separated = 0;
        int seats = 0;
        while(simulator.slotOf(state) != null){
            DraftSimulator.Slot slot = simulator.slotOf(state);
            boolean mine = planner.me().equals(slot.manager());
            Position advised = null;
            String line = "";
            if(mine){
                LiveDraft.freezeWith(taken);
                captured.reset();
                // FLUSH BEFORE READING. A PrintStream buffers, so without this
                // the text parsed below can be incomplete or left over from the
                // previous seat - which is exactly what happened: pick 7
                // reported a margin of 53.3 here against 25.0 from LiveBoard
                // standalone, on an identical empty board, and the model was
                // not the thing that differed.
                PrintStream sink = new PrintStream(captured, true);
                try {
                    System.setOut(sink);
                    advised = LiveBoard.answer(setup.configuration, planner, simulator,
                            setup.draftID, setup.curve, setup.pools, setup.order,
                            setup.men, kept);
                }
                finally {
                    sink.flush();
                    System.setOut(real);
                    LiveDraft.thaw();
                }
                if(System.getProperty("dump") != null
                        && captured.toString().contains("INSIDE THE NOISE")
                        && !dumped[0]){
                    dumped[0] = true;
                    String all = captured.toString();
                    int at = all.indexOf("the model takes");
                    real.printf("%n---- FIRST COIN-FLIP SEAT ----%n%s%n"
                            + "---- END ----%n%n",
                            at < 0 ? all : all.substring(at));
                }
                for(String printed : captured.toString().split("\n")){
                    if(printed.contains("SEPARATED from every other")
                            || printed.contains("INSIDE THE NOISE of each other")){
                        line = printed.trim();
                    }
                }
                seats++;
                boolean isSeparated = line.contains("SEPARATED from every other");
                if(isSeparated){
                    separated++;
                }
                String gap = line.isEmpty() ? "(only one legal position)"
                        : line.length() > 46 ? line.substring(0, 46) : line;
                seenAt.merge(slot.pickNumber(), 1, Integer::sum);
                if(isSeparated){
                    separatedAt.merge(slot.pickNumber(), 1, Integer::sum);
                }
                exampleAt.putIfAbsent(slot.pickNumber(), gap);
            }
            String pickedID = null;
            if(mine && advised != null){
                pickedID = LiveBoard.bestAvailable(planner, taken, advised);
            }
            if(pickedID == null){
                List<String> live = new ArrayList<>();
                for(Map.Entry<String, Double> entry : planner.points().entrySet()){
                    if(state.takenAtOf(entry.getKey()) == null
                            && !taken.contains(entry.getKey())
                            && !kept.contains(entry.getKey())){
                        live.add(entry.getKey());
                    }
                }
                if(live.isEmpty()){
                    break;
                }
                live.sort(Comparator.comparingDouble(
                        (String id) -> planner.points().getOrDefault(id, 0.0)).reversed());
                pickedID = live.get(random.nextInt(Math.min(6, live.size())));
            }
            state = simulator.branchWith(state, pickedID);
            taken.add(pickedID);
        }
        }
        int alwaysReal = 0;
        int alwaysFlip = 0;
        for(Map.Entry<Integer, Integer> entry : seenAt.entrySet()){
            int pick = entry.getKey();
            int hits = separatedAt.getOrDefault(pick, 0);
            int runs = entry.getValue();
            String verdict = hits == runs ? "real in all " + runs
                    : hits == 0 ? "COIN FLIP in all " + runs
                            : hits + " of " + runs;
            if(hits == runs){
                alwaysReal++;
            }
            if(hits == 0){
                alwaysFlip++;
            }
            real.printf("%-6d %-6s %-34s %s%n", pick, "", exampleAt.get(pick), verdict);
        }
        real.printf("%n%d of %d seats separated in EVERY simulated room, %d are a coin"
                + " flip in every one.%n", alwaysReal, seenAt.size(), alwaysFlip);
        real.printf("%nAt a coin-flip seat the model is not being modest, it is being%n"
                + "accurate: the two positions really are worth the same from that%n"
                + "board, and your own read on the men is the better instrument.%n");
    }
}
