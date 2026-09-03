import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * SHOULD expectedRank WATCH THE ROOM, THE WAY drain DOES?
 *
 * `drain` blends the fitted survival prior with what the room has ACTUALLY
 * done, on the argument that the room is the thing that catches a draft the
 * model did not expect. `expectedRank` - which sets every END TEAM number -
 * uses the prior alone, plus certainty for men already taken. That asymmetry
 * was not reasoned about; it is just where the two pieces of code came from.
 *
 * The question only means anything on REAL drafts. Against simulations the
 * prior is predicting its own generator, so a room-observed term can only ever
 * look like noise. This scores three rules against the league's own 2024 and
 * 2025 drafts, with the choice model fitted on prior seasons only:
 *
 *   A  the retired hard ADP cutoff
 *   B  what ships - prior, plus certainty for men really taken
 *   C  the same, blended with the room's observed rate the way drain blends
 *
 *   ./gradlew run -Pmain=RealMidDraft -q
 */
public class RealMidDraft {

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int draws = Integer.getInteger("survivalDraws", 200);
        String[] targets = {"2024", "2025"};
        int[] checkpoints = {13, 25, 37, 49, 61, 73, 85, 97, 109, 121};

        double cutoffError = 0;
        double shippedError = 0;
        double roomError = 0;
        int cells = 0;
        // The same question for drain, where I DID keep the room term - on the
        // argument that a simulation cannot exercise model misspecification.
        // Real drafts can, so this is that decision put to the only test that
        // could settle it.
        double drainShipped = 0;
        double drainPriorOnly = 0;
        double drainRetired = 0;
        int drainCells = 0;

        for(String target : targets){
            int trainTo = Integer.parseInt(target) - 1;
            Map<String, Double> qbEarliness =
                    SelectionModel.qbEarliness(configuration, trainTo);
            DraftSimulator.Extras extras =
                    DraftSimulator.extrasFor(configuration, target, trainTo);
            List<SelectionModel.Observation> train = SelectionModel.loadObservations(
                    configuration, 2021, trainTo, qbEarliness,
                    extras.teEarliness(), extras.rbEarliness(),
                    false, SelectionModel.TRAIN_ROUNDS);
            BoostedSelectionModel model = BoostedSelectionModel.fit(train, 300, 2, 0.1);
            DraftBacktest.Season season = new DraftBacktest.Season(configuration, target);
            DraftSimulator simulator = DraftSimulator.forSeason(season, model,
                    qbEarliness, extras);
            LiveBoard.Survival survival = new LiveBoard.Survival(
                    simulator.players(), simulator, draws, 31_337L);

            Map<String, Integer> realPick = new HashMap<>();
            for(JsonElement element : season.picks){
                JsonObject pick = element.getAsJsonObject();
                if(!pick.has("player_id") || pick.get("player_id").isJsonNull()
                        || !pick.has("pick_no") || pick.get("pick_no").isJsonNull()){
                    continue;
                }
                realPick.put(pick.get("player_id").getAsString(),
                        pick.get("pick_no").getAsInt());
            }

            for(int now : checkpoints){
                // The board as it really stood at that point of that draft.
                List<String> taken = new ArrayList<>();
                for(Map.Entry<String, Integer> entry : realPick.entrySet()){
                    if(entry.getValue() < now){
                        taken.add(entry.getKey());
                    }
                }
                if(taken.isEmpty()){
                    continue;
                }
                for(int later : checkpoints){
                    if(later <= now){
                        continue;
                    }
                    for(Position position : new Position[]{Position.RB, Position.WR,
                            Position.TE, Position.QB}){
                        int trueGone = 0;
                        int adpGone = 0;
                        int takenOfPosition = 0;
                        for(String id : simulator.players()){
                            Player player = Player.getPlayerFromSIDV2(id);
                            if(player == null || player.position != position){
                                continue;
                            }
                            Integer at = realPick.get(id);
                            if(at != null && at < later){
                                trueGone++;
                            }
                            if(at != null && at < now){
                                takenOfPosition++;
                            }
                            Double adp = season.adp.get(id);
                            if(adp != null && adp < later){
                                adpGone++;
                            }
                        }
                        // B: what ships.
                        double shipped = survival.expectedGone(position, later);
                        for(String id : taken){
                            Player player = Player.getPlayerFromSIDV2(id);
                            if(player != null && player.position == position){
                                shipped += 1.0 - survival.probabilityGone(id, later);
                            }
                        }
                        // C: blended with the room, exactly as drain blends.
                        double observedRate = (double) takenOfPosition / taken.size();
                        double roomAhead = observedRate * (later - now);
                        double trust = taken.size() / (taken.size() + 30.0);
                        double room = takenOfPosition
                                + trust * roomAhead
                                + (1 - trust) * (shipped - takenOfPosition);

                        cutoffError += Math.abs(adpGone - trueGone);
                        shippedError += Math.abs(shipped - trueGone);
                        roomError += Math.abs(room - trueGone);
                        cells++;

                        // ---- the drain question: how many go in the WINDOW ----
                        int trueWindow = trueGone - takenOfPosition;
                        double priorWindow = Math.max(0,
                                survival.expectedGone(position, later)
                                        - survival.expectedGone(position, now));
                        int adpWindow = 0;
                        for(String id : simulator.players()){
                            Player player = Player.getPlayerFromSIDV2(id);
                            Double adp = player == null ? null : season.adp.get(id);
                            if(player != null && player.position == position
                                    && adp != null && adp >= now && adp < later){
                                adpWindow++;
                            }
                        }
                        double window = later - now;
                        double shippedDrain = Math.max(0, Math.round(
                                (trust * observedRate
                                        + (1 - trust) * priorWindow / window) * window));
                        double retiredDrain = Math.max(0, Math.round(
                                (trust * observedRate
                                        + (1 - trust) * adpWindow / window) * window));
                        drainShipped += Math.abs(shippedDrain - trueWindow);
                        drainPriorOnly += Math.abs(Math.round(priorWindow) - trueWindow);
                        drainRetired += Math.abs(retiredDrain - trueWindow);
                        drainCells++;
                    }
                }
            }
        }
        System.out.printf("%nmid-draft rank prediction on the league's OWN drafts,%n"
                + "%d (season, seat now, seat later, position) cells.%n%n", cells);
        System.out.printf("   A  hard ADP cutoff (retired)        %.2f men%n",
                cutoffError / cells);
        System.out.printf("   B  survival prior only (SHIPS)      %.2f men%n",
                shippedError / cells);
        System.out.printf("   C  prior blended with the room      %.2f men%n",
                roomError / cells);
        double gain = (shippedError - roomError) / cells;
        System.out.printf("%nwatching the room is worth %.2f men per cell%s.%n",
                Math.abs(gain), gain > 0 ? "" : " LESS THAN NOTHING");
        if(gain > 0.15){
            System.out.printf("%nWHICH IS WORTH TAKING. expectedRank should watch the%n"
                    + "room the way drain already does.%n");
        }
        else {
            System.out.printf("%nSO THE ASYMMETRY IS FINE AS IT STANDS. The prior%n"
                    + "already carries what the room would tell it, because every%n"
                    + "man really taken is counted at certainty rather than at his%n"
                    + "prior - which is most of what 'the room is running' means.%n");
        }

        System.out.printf("%n%nAND THE SAME QUESTION FOR drain, on the same real drafts.%n"
                + "how many of a position go BETWEEN two seats, %d cells:%n%n", drainCells);
        System.out.printf("   retired: room blended with ADP counts   %.2f men%n",
                drainRetired / drainCells);
        System.out.printf("   SHIPS:   room blended with the prior    %.2f men%n",
                drainShipped / drainCells);
        System.out.printf("   the prior alone, no room term           %.2f men%n",
                drainPriorOnly / drainCells);
        double drainGain = (drainShipped - drainPriorOnly) / drainCells;
        System.out.printf("%ndropping the room term from drain is worth %.2f men%s.%n",
                Math.abs(drainGain), drainGain > 0 ? "" : " LESS THAN NOTHING");
        System.out.printf("%nI KEPT THE ROOM TERM IN drain on the argument that a%n"
                + "simulation cannot exercise model misspecification and a real%n"
                + "draft can. this is that argument put to its only real test.%n");
    }
}
