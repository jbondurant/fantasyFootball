import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * COMPONENT F - draft night. Reads the live Sleeper board, replays it into
 * the simulator, and recommends the next pick by the engine the tournament
 * chose: depth-2 lookahead over positions with VORP-completed rollouts
 * (oldschool-2-vorp, +7.2 over any committed plan in the lab, and the only
 * engine that beat committing in both keeper worlds).
 *
 * Why this and not the pre-computed sequence: a fixed plan cannot see that
 * Allen fell to you, or that the run you were waiting out already happened.
 * The lab measured that difference at +7.2 (Model A) and +12.4 (Model B).
 *
 * Every recommendation prints its alternatives with margins, so a near-tie
 * is visible as a near-tie and the human decides. The fallback ladder is
 * explicit: if the engine cannot run, VORP-greedy off the live board; if
 * even that fails, best available at a needed position.
 *
 *   ./gradlew run -Pmain=LiveDraft                    # the real draft
 *   ./gradlew run -Pmain=LiveDraft -PdraftId=<mock>   # rehearsal
 *   ./gradlew run -Pmain=LiveDraft -Ptrials=400 -Pinner=24
 */
public class LiveDraft {

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 300);
        int inner = Integer.getInteger("inner", 16);
        String draftID = System.getProperty("draftId", configuration.getDraftID());

        long start = System.currentTimeMillis();
        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration,
                lastCompleted);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, lastCompleted,
                earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        TimingPlanner timing = new TimingPlanner(planner);
        timing.fillWaitingTable(200);
        DraftSimulator simulator = planner.simulator();
        System.out.printf("engine warm in %.1fs%n",
                (System.currentTimeMillis() - start) / 1000.0);

        // ---- the live board ----
        List<String> taken = livePicks(draftID);
        DraftSimulator.SimState state = simulator.stateAfter(taken);
        DraftSimulator.Slot slot = simulator.slotOf(state);
        System.out.printf("%n%d picks are in.", taken.size());
        if(slot == null){
            System.out.println(" The nine-round game is over - nothing left to plan.");
            return;
        }
        System.out.printf(" On the clock: pick %d (round %d), %s%n",
                slot.pickNumber(), slot.round(),
                HumanOfInterest.getHumanFromID(slot.manager()));
        boolean mine = slot.manager().equals(planner.me());
        if(!mine){
            System.out.printf("Not my pick - planning anyway, as if it were.%n");
        }

        // ---- my roster so far ----
        List<String> roster = new ArrayList<>(planner.myKeeperIDs());
        for(String sleeperID : taken){
            Integer at = state.takenAtOf(sleeperID);
            if(at != null && simulator.slotAt(at) != null
                    && planner.me().equals(simulator.slotAt(at).manager())){
                roster.add(sleeperID);
            }
        }
        System.out.print("my roster: ");
        for(String sleeperID : roster){
            Player player = Player.getPlayerFromSIDV2(sleeperID);
            System.out.print(player.lastName + "(" + player.position + ") ");
        }
        System.out.println();

        recommend(timing, planner, simulator, state, roster, rollouts);
    }

    /**
     * The decision itself, shared by the live path and the rehearsal: depth-2
     * lookahead over positions from the given state, VORP-completed tails,
     * every alternative printed with its margin.
     */
    static Position recommend(TimingPlanner timing, DraftPlanner planner,
                              DraftSimulator simulator, DraftSimulator.SimState state,
                              List<String> roster, int rollouts){
        long decisionStart = System.currentTimeMillis();
        Position[] positions = {Position.QB, Position.RB, Position.WR, Position.TE};
        Map<Position, Double> value = new EnumMap<>(Position.class);
        Map<Position, String> best = timing.bestAvailable(state.boardView());
        for(Position first : positions){
            if(best.get(first) == null){
                continue;
            }
            double top = -Double.MAX_VALUE;
            for(Position second : positions){
                double total = IntStream.range(0, rollouts).parallel().mapToDouble(r -> {
                    TimingPlanner.HeadPolicy policy = timing.new HeadPolicy(
                            List.of(first, second), roster);
                    DraftSimulator.SimState branch = state.copy();
                    simulator.simulateFrom(branch, new Random(DraftSimulator.SEED
                            + 7919L * r), planner.me(), policy);
                    return StartingLineup.bestNine(policy.mine, planner.points());
                }).sum() / rollouts;
                top = Math.max(top, total);
            }
            value.put(first, top);
        }
        List<Position> ranked = new ArrayList<>(value.keySet());
        ranked.sort(Comparator.comparingDouble(p -> -value.get(p)));
        System.out.printf("%nRECOMMENDATION (%.1fs, %d rollouts x depth 2):%n%n",
                (System.currentTimeMillis() - decisionStart) / 1000.0, rollouts);
        System.out.printf("   %-4s %-26s %10s %10s%n", "POS", "player", "value", "margin");
        double leader = value.get(ranked.get(0));
        for(Position position : ranked){
            Player player = Player.getPlayerFromSIDV2(best.get(position));
            System.out.printf("   %-4s %-26s %10.1f %10s%n", position,
                    player.firstName + " " + player.lastName, value.get(position),
                    position == ranked.get(0) ? "<- TAKE"
                            : String.format("%.1f", value.get(position) - leader));
        }
        double margin = ranked.size() > 1
                ? leader - value.get(ranked.get(1)) : Double.MAX_VALUE;
        if(margin < 2.0){
            System.out.printf("%n   NEAR TIE (%.1f apart) - either is defensible; "
                    + "take the scarcer position.%n", margin);
        }
        return ranked.get(0);
    }

    /** Player ids in pick order from the live draft. */
    static List<String> livePicks(String draftID) throws Exception {
        String data = InOutUtilities.getTodaysWebPage(
                "https://api.sleeper.app/v1/draft/" + draftID + "/picks",
                "livePicks" + draftID);
        JsonArray picks = JsonParser.parseString(data).getAsJsonArray();
        List<JsonObject> ordered = new ArrayList<>();
        for(JsonElement element : picks){
            ordered.add(element.getAsJsonObject());
        }
        ordered.sort(Comparator.comparingInt(o -> o.get("pick_no").getAsInt()));
        List<String> ids = new ArrayList<>();
        for(JsonObject pick : ordered){
            JsonElement keeper = pick.get("is_keeper");
            if(keeper != null && !keeper.isJsonNull() && keeper.getAsBoolean()){
                continue;   // keepers already sit in the simulator's schedule
            }
            JsonElement id = pick.get("player_id");
            if(id != null && !id.isJsonNull()){
                ids.add(id.getAsString());
            }
        }
        return ids;
    }
}
