import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * DOES THE ROOM MODEL REPRODUCE A SEASON IT HAS NEVER SEEN?
 *
 * DefenceReality compares a simulation of the 2026 board against what happened
 * in 2021-2025. That comparison is unfair to the model in a way I missed: SEVEN
 * of this year's twenty-four keepers are tight ends, so the 2026 tight-end board
 * is stripped bare and a room SHOULD take fewer of them early than history did.
 * A seventeen-point "error" at TE may be the keepers, not the model.
 *
 * The honest test is like-for-like: fit the choice model on seasons BEFORE a
 * target, simulate that target's own board with its own keepers, and compare
 * against what that draft really did. Same board, same keepers, same league -
 * so any gap is the model.
 *
 *   ./gradlew run -Pmain=RoomFidelity -q
 */
public class RoomFidelity {

    private static final int[][] BANDS = {{1, 7}, {8, 9}, {10, 13}, {14, 16}};

    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", "16");
        // The historical schedule is nine rounds unless this is set, and a
        // nine-round replay cannot say anything about rounds 10-16.
        System.setProperty("fullRounds", "true");
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        System.out.printf("%nthe room model against seasons it was not fitted on.%n"
                + "same board, same keepers, same league - so a gap is the model.%n");

        Map<Position, double[]> gapTotals = new EnumMap<>(Position.class);
        int seasons = 0;
        for(String target : new String[]{"2024", "2025"}){
            int trainTo = Integer.parseInt(target) - 1;
            Map<String, Double> qbEarliness =
                    SelectionModel.qbEarliness(configuration, trainTo);
            DraftSimulator.Extras extras =
                    DraftSimulator.extrasFor(configuration, target, trainTo);
            List<SelectionModel.Observation> train = SelectionModel.loadObservations(
                    configuration, 2021, trainTo, qbEarliness,
                    extras.teEarliness(), extras.rbEarliness(),
                    false, SelectionModel.trainRounds());
            BoostedSelectionModel model = BoostedSelectionModel.fit(train, 300, 2, 0.1);
            DraftBacktest.Season season = new DraftBacktest.Season(configuration, target);
            DraftSimulator simulator =
                    DraftSimulator.forSeason(season, model, qbEarliness, extras);

            Map<Position, List<Integer>> sim = new EnumMap<>(Position.class);
            for(int trial = 0; trial < 30; trial++){
                Map<String, Integer> takenAt =
                        simulator.simulateOnce(new Random(31_000L + 7919L * trial));
                for(Map.Entry<String, Integer> entry : takenAt.entrySet()){
                    Player player = Player.getPlayerFromSIDV2(entry.getKey());
                    DraftSimulator.Slot slot = simulator.slotAt(entry.getValue());
                    if(player != null && slot != null){
                        sim.computeIfAbsent(player.position, u -> new ArrayList<>())
                                .add(slot.round());
                    }
                }
            }
            Map<Position, List<Integer>> real = new EnumMap<>(Position.class);
            for(JsonElement element : season.picks){
                JsonObject pick = element.getAsJsonObject();
                if(!pick.has("player_id") || pick.get("player_id").isJsonNull()
                        || !pick.has("round") || pick.get("round").isJsonNull()){
                    continue;
                }
                // A KEEPER IS NOT A DRAFT DECISION. He occupies a round the
                // commissioner assigned him; nobody chose him there against the
                // rest of the board. The SIMULATION never drafts keepers - they
                // are pre-assigned - so counting them on the real side compares
                // a distribution containing twenty-four keepers against one
                // containing none. Seven of this year's are tight ends.
                com.google.gson.JsonElement isKeeper = pick.get("is_keeper");
                if(isKeeper != null && !isKeeper.isJsonNull() && isKeeper.getAsBoolean()){
                    continue;
                }
                Player player = Player.getPlayerFromSIDV2(
                        pick.get("player_id").getAsString());
                if(player != null){
                    real.computeIfAbsent(player.position, u -> new ArrayList<>())
                            .add(pick.get("round").getAsInt());
                }
            }

            System.out.printf("%n=== %s, held out ===%n", target);
            System.out.printf("%-5s %-22s %-22s %s%n", "POS",
                    "REAL  1-7 8-9 10-13 14-16", "SIM   1-7 8-9 10-13 14-16",
                    "worst");
            for(Position position : new Position[]{Position.RB, Position.WR,
                    Position.TE, Position.QB, Position.DEF}){
                List<Integer> was = real.get(position);
                List<Integer> now = sim.get(position);
                if(was == null || now == null || was.isEmpty() || now.isEmpty()){
                    continue;
                }
                StringBuilder realRow = new StringBuilder();
                StringBuilder simRow = new StringBuilder();
                double worst = 0;
                for(int[] band : BANDS){
                    double r = 100.0 * was.stream()
                            .filter(x -> x >= band[0] && x <= band[1]).count() / was.size();
                    double m = 100.0 * now.stream()
                            .filter(x -> x >= band[0] && x <= band[1]).count() / now.size();
                    realRow.append(String.format("%5.0f", r));
                    simRow.append(String.format("%5.0f", m));
                    worst = Math.max(worst, Math.abs(r - m));
                }
                // HOW MANY, not just when. A band table is percentages of the
                // men drafted at that position, so a room that drafts the wrong
                // NUMBER of quarterbacks can still look correctly timed.
                double simPerDraft = now.size() / 30.0;
                System.out.printf("%-5s %-22s %-22s %4.0f pts   n %2d real / %4.1f sim%s%n",
                        position, realRow.toString(), simRow.toString(), worst,
                        was.size(), simPerDraft,
                        worst > 15 ? "  <- WRONG" : worst > 10 ? "  <- off" : "");
                gapTotals.computeIfAbsent(position, u -> new double[2])[0] += worst;
                gapTotals.get(position)[1]++;
            }
            seasons++;
        }
        System.out.printf("%n%nMEAN WORST-BAND GAP OVER %d HELD-OUT SEASONS%n%n", seasons);
        for(Map.Entry<Position, double[]> entry : gapTotals.entrySet()){
            System.out.printf("   %-5s %5.1f points%n", entry.getKey(),
                    entry.getValue()[0] / entry.getValue()[1]);
        }
        System.out.printf("%nthis is the number that answers whether the room model is%n"
                + "credible per position. the 2026 comparison cannot: seven of this%n"
                + "year's keepers are tight ends, so that board is not like any%n"
                + "season the league has actually drafted.%n");
    }
}
