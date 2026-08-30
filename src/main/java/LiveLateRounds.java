import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Draft night, rounds 8 to 16: what is each position worth RIGHT NOW, and will
 * he last?
 *
 * Adaptive rather than planned. It reads the live board, takes the roster you
 * actually have - not the one a plan assumed - and prices the best available man
 * at every position by what he adds to the points your STARTERS will score:
 *
 *     marginal = V(roster + him) - V(roster)
 *
 * then asks how often he survives to your next pick, and multiplies. That is
 * the tight end question, the backup quarterback question and the defence
 * question all at once, answered by the same number instead of by three rules.
 *
 * SCOPE, and it is deliberate. This is for rounds 8+ only. The same objective
 * driving the whole draft LOST a five-season backtest to the committed plan by
 * 98 points a season, and the decomposition put 91 of those 98 in rounds 1-7.
 * Its back half was within 7 points - noise - of the committed plan's. So Model
 * A keeps rounds 1-7, where it is proven and where this loses, and this takes
 * the rounds where it is competitive. Use DraftNight until round 8, then this.
 *
 *   ./gradlew run -Pmain=LiveLateRounds -PdraftId=<id> [-Pscenarios=1200]
 */
public class LiveLateRounds {

    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", System.getProperty("scheduleRounds", "16"));
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int scenarios = Integer.getInteger("scenarios", 1200);
        int trials = Integer.getInteger("trials", 200);
        String draftID = System.getProperty("draftId", configuration.getDraftID());

        List<Keeper> myKeepers = DraftPlanner.keepersFromProperty(configuration);
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, myKeepers,
                model, earliness);
        DraftSimulator simulator = planner.simulator();
        WeeklyStarterValue value = WeeklyStarterValue.forCurrentBoard(configuration,
                planner.points(), scenarios, 424_242L);

        List<String> taken = LiveDraft.livePicks(draftID);
        DraftSimulator.SimState state = simulator.stateAfter(taken);
        DraftSimulator.Slot slot = simulator.slotOf(state);
        if(slot == null){
            System.out.println("no picks of mine left on the board");
            return;
        }
        List<String> mine = new ArrayList<>(planner.myKeeperIDs());
        for(String id : taken){
            Integer at = state.takenAtOf(id);
            if(at != null && simulator.slotAt(at) != null
                    && planner.me().equals(simulator.slotAt(at).manager())){
                mine.add(id);
            }
        }
        int nextPick = nextPickAfter(simulator, state, planner, slot.pickNumber());

        System.out.printf("%npick %d (round %d), %d gone, my roster: %s%n",
                slot.pickNumber(), slot.round(), taken.size(), shape(mine));
        if(slot.round() < 8){
            System.out.printf("%nRound %d is Model A's game - use DraftNight."
                    + " This tool is for rounds 8+.%n", slot.round());
        }
        System.out.printf("my next pick: %s%n", nextPick < 0 ? "NONE - this is my last"
                : nextPick + " (" + (nextPick - slot.pickNumber()) + " picks away)");

        double base = value.of(mine);
        System.out.printf("%n%-4s %-24s %11s %10s %13s   %s%n", "POS", "BEST AVAILABLE",
                "ADDS", "SURVIVES", "LOSS IF WAIT", "verdict");

        Map<Position, String> best = new EnumMap<>(Position.class);
        Map<Position, Double> adds = new EnumMap<>(Position.class);
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE,
                Position.QB, Position.DEF}){
            // Defences are not on the simulator's board - it is built from skill
            // positions - so they come off the projections directly, minus
            // whoever has already been taken. Their survival is not simulated
            // for the same reason; the column says so rather than guessing.
            String candidate = position == Position.DEF
                    ? bestDefence(planner, taken)
                    : bestAvailable(simulator, state, planner, position);
            if(candidate == null){
                continue;
            }
            List<String> trial = new ArrayList<>(mine);
            trial.add(candidate);
            best.put(position, candidate);
            adds.put(position, value.of(trial) - base);
        }
        List<Position> order = new ArrayList<>(adds.keySet());
        order.sort(Comparator.comparingDouble(p -> -adds.get(p)));

        for(Position position : order){
            String id = best.get(position);
            Player player = Player.getPlayerFromSIDV2(id);
            boolean simulated = position != Position.DEF;
            double survives = !simulated || nextPick < 0 ? 0
                    : survival(simulator, state, planner, id, nextPick, trials);
            // what is left at that position if he is gone, valued the same way
            double loss = nextPick < 0 ? 0 : (1 - survives) * Math.max(0,
                    adds.get(position) - fallback(simulator, state, planner, value,
                            mine, base, position, id));
            String verdict = !simulated
                    ? "survival not simulated - they go in the last two rounds"
                    : nextPick < 0 ? "last pick - just take the top row"
                    : loss < 3 ? "wait - he keeps"
                    : loss < 10 ? String.format("lean take (%.0f)", loss)
                    : String.format("TAKE NOW (%.0f)", loss);
            System.out.printf("%-4s %-24s %11.1f %9s %13s   %s%n", position,
                    player == null ? id : player.firstName + " " + player.lastName,
                    adds.get(position),
                    simulated ? String.format("%.0f%%", survives * 100) : "-",
                    simulated ? String.format("%.1f", loss) : "-", verdict);
        }

        System.out.println("\nADDS = points your STARTERS gain over the season by"
                + " rostering him, counting the\nweeks he actually beats whoever else"
                + " you would have started, with the waiver\nwire filling anything you"
                + " cannot. It is the same number for a tight end, a\nbackup quarterback"
                + " and a defence, so they can be compared directly.");
        System.out.println("\nLOSS IF WAIT = P(gone) x (what he adds minus what the next"
                + " man at his position\nwould add instead). A high ADDS with a high"
                + " SURVIVES is not urgent.");
    }

    static String bestAvailable(DraftSimulator simulator, DraftSimulator.SimState state,
                                DraftPlanner planner, Position position){
        String best = null;
        double bestPoints = -1;
        for(String id : simulator.players()){
            if(state.takenAtOf(id) != null){
                continue;
            }
            Player player = Player.getPlayerFromSIDV2(id);
            if(player == null || player.position != position){
                continue;
            }
            double points = planner.points().getOrDefault(id, 0.0);
            if(points > bestPoints){
                bestPoints = points;
                best = id;
            }
        }
        return best;
    }

    /** Best defence still on the board, straight from the projections. */
    static String bestDefence(DraftPlanner planner, List<String> taken){
        String best = null;
        double bestPoints = -1;
        for(Map.Entry<String, Double> entry : planner.points().entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null || player.position != Position.DEF
                    || taken.contains(entry.getKey())){
                continue;
            }
            if(entry.getValue() > bestPoints){
                bestPoints = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    /** The next man at that position, valued the same way. */
    static double fallback(DraftSimulator simulator, DraftSimulator.SimState state,
                           DraftPlanner planner, WeeklyStarterValue value,
                           List<String> mine, double base, Position position,
                           String exclude){
        String second = null;
        double bestPoints = -1;
        for(String id : simulator.players()){
            if(state.takenAtOf(id) != null || id.equals(exclude)){
                continue;
            }
            Player player = Player.getPlayerFromSIDV2(id);
            if(player == null || player.position != position){
                continue;
            }
            double points = planner.points().getOrDefault(id, 0.0);
            if(points > bestPoints){
                bestPoints = points;
                second = id;
            }
        }
        if(second == null){
            return 0;
        }
        List<String> trial = new ArrayList<>(mine);
        trial.add(second);
        return value.of(trial) - base;
    }

    /** How often he is still there at my next pick, if I spend this one elsewhere. */
    static double survival(DraftSimulator simulator, DraftSimulator.SimState state,
                           DraftPlanner planner, String id, int nextPick, int trials){
        int survived = 0;
        for(int trial = 0; trial < trials; trial++){
            Random random = new Random(661_000L + 7919L * trial);
            DraftSimulator.SimState branch = state.copy();
            while(simulator.slotOf(branch) != null
                    && simulator.slotOf(branch).pickNumber() < nextPick){
                simulator.simulateOneFrom(branch, random);
            }
            if(branch.takenAtOf(id) == null){
                survived++;
            }
        }
        return (double) survived / trials;
    }

    static int nextPickAfter(DraftSimulator simulator, DraftSimulator.SimState state,
                             DraftPlanner planner, int after){
        DraftSimulator.SimState probe = state.copy();
        Random random = new Random(DraftSimulator.SEED);
        while(simulator.slotOf(probe) != null){
            DraftSimulator.Slot slot = simulator.slotOf(probe);
            if(planner.me().equals(slot.manager()) && slot.pickNumber() > after){
                return slot.pickNumber();
            }
            simulator.simulateOneFrom(probe, random);
        }
        return -1;
    }

    static String shape(List<String> roster){
        Map<Position, Integer> counts = new EnumMap<>(Position.class);
        for(String id : roster){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null){
                counts.merge(player.position, 1, Integer::sum);
            }
        }
        StringBuilder text = new StringBuilder();
        for(Map.Entry<Position, Integer> entry : counts.entrySet()){
            text.append(entry.getValue()).append(entry.getKey()).append(' ');
        }
        return text.toString().trim();
    }
}
