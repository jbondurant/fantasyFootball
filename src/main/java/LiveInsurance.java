import PlayerImportAndSetup.Position;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * MODEL B, live: what to do once the starting nine is full.
 *
 * Model A maximises the starting nine and then goes silent - at pick 89 of
 * the mock rehearsal every engine read an identical 1808.4 because no
 * available player could move its objective. That silence is correct, and it
 * is exactly where Model B takes over: the remaining picks are not starters,
 * they are INSURANCE, and their worth is the chance they get promoted when a
 * starter busts, minus what the waiver wire would have given for free.
 *
 * Three measured ingredients, no guesses:
 *   fog          FogFit's per-position, per-tier ratio of actual to projected,
 *                including the bust rate - so a "truth" draw is a season that
 *                could really have happened.
 *   the wire     one past the average number of each position the league
 *                drafts across FULL 16-round histories (shared with
 *                InsuranceTest so the two cannot disagree). A bench player is
 *                worth only what he beats this by.
 *   injuries     Draft Sharks' projected games missed, applied RELATIVE to
 *                the pool average. The absolute injury level is already
 *                inside the fog constants - they were fitted on real outcomes
 *                that included injuries - so only a player's deviation from
 *                the average is applied here, or it would be counted twice.
 *
 * Positions are compared under COMMON RANDOM NUMBERS: the same sampled
 * seasons for every candidate, so the differences are paired.
 *
 *     ./gradlew run -Pmain=LiveInsurance -PdraftId=<id> [-Pdraws=40]
 */
public class LiveInsurance {

    /** name -> projected games missed, from the Draft Sharks export. */
    static Map<String, Double> gamesMissed(){
        Map<String, Double> missed = new HashMap<>();
        Path file = Path.of("data", "draftsharks-injury-2026-0707.csv");
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for(String line : lines.subList(1, lines.size())){
                String[] cells = line.split(",");
                if(cells.length >= 4){
                    missed.put(cells[0].trim(), Double.parseDouble(cells[3]));
                }
            }
        }
        catch(Exception unreadable){
            System.out.println("no injury file (" + unreadable.getMessage()
                    + ") - continuing on fog alone");
        }
        return missed;
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int draws = Integer.getInteger("draws", 40);
        int rollouts = Integer.getInteger("trials", 3);
        String draftID = System.getProperty("draftId", configuration.getDraftID());

        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        TimingPlanner timing = new TimingPlanner(planner);
        timing.fillWaitingTable(200);
        DraftSimulator simulator = planner.simulator();
        Map<String, Double> projections = planner.points();

        List<String> taken = LiveDraft.livePicks(draftID);
        DraftSimulator.SimState state = simulator.stateAfter(taken);
        DraftSimulator.Slot slot = simulator.slotOf(state);
        if(slot == null){
            System.out.println("The nine-round game is over.");
            return;
        }
        List<String> roster = new ArrayList<>(planner.myKeeperIDs());
        for(String id : taken){
            Integer at = state.takenAtOf(id);
            if(at != null && simulator.slotAt(at) != null
                    && planner.me().equals(simulator.slotAt(at).manager())){
                roster.add(id);
            }
        }
        System.out.printf("%npick %d (round %d), my roster %d deep%n",
                slot.pickNumber(), slot.round(), roster.size());

        // fog, the wire, and the injury deviations
        Map<Position, double[][]> fog = FogFit.fit(configuration);
        Map<Position, Integer> replacementRank = InsuranceTest.replacementRanks(configuration);
        Map<String, Double> missed = gamesMissed();
        double averageMissed = missed.isEmpty() ? 0
                : missed.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);

        Map<String, Integer> positionRank = new HashMap<>();
        Map<Position, List<String>> byPosition = new EnumMap<>(Position.class);
        for(String id : projections.keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null && StartingLineup.isSkillPosition(player.position)){
                byPosition.computeIfAbsent(player.position, u -> new ArrayList<>()).add(id);
            }
        }
        for(List<String> ids : byPosition.values()){
            ids.sort(Comparator.comparingDouble(id -> -projections.get(id)));
            for(int rank = 0; rank < ids.size(); rank++){
                positionRank.put(ids.get(rank), rank + 1);
            }
        }
        System.out.println("wire starts at " + replacementRank + "; injury file covers "
                + missed.size() + " players (avg " + String.format("%.1f", averageMissed)
                + " games missed)");

        Map<Position, String> best = timing.bestAvailable(state.boardView());
        Map<Position, Double> value = new EnumMap<>(Position.class);
        Map<Position, Double> error = new EnumMap<>(Position.class);
        // paired differences against the first candidate: with common random
        // numbers the DIFFERENCE is far better measured than either level, so
        // the error bar that matters is the one on the gap.
        Position reference = best.keySet().iterator().next();
        Map<Position, List<Double>> perDraw = new EnumMap<>(Position.class);

        for(Map.Entry<Position, String> candidate : best.entrySet()){
            double total = 0;
            List<Double> samples = new ArrayList<>();
            for(int draw = 0; draw < draws; draw++){
                // COMMON RANDOM NUMBERS: the season is drawn from the draw
                // index alone, so every position is judged under identical
                // luck and the differences below are paired.
                Random fogRandom = new Random(51_000_000L + 7919L * draw);
                Map<String, Double> truth = new HashMap<>();
                for(Map.Entry<String, Double> entry : projections.entrySet()){
                    Player player = Player.getPlayerFromSIDV2(entry.getKey());
                    Integer rank = positionRank.get(entry.getKey());
                    if(player == null || rank == null){
                        truth.put(entry.getKey(), entry.getValue());
                        continue;
                    }
                    double[] constants = fog.get(player.position)[FogFit.tier(rank)];
                    double ratio = fogRandom.nextDouble() < constants[2]
                            ? 0.1 + 0.5 * fogRandom.nextDouble()
                            : Math.max(0.2, constants[0]
                                    + constants[1] * fogRandom.nextGaussian());
                    // injury, relative to the pool - the absolute level is
                    // already inside the fog constants
                    String name = player.firstName + " " + player.lastName;
                    Double games = missed.get(name);
                    double availability = games == null ? 1.0
                            : (17 - games) / Math.max(1e-6, 17 - averageMissed);
                    truth.put(entry.getKey(), entry.getValue() * ratio * availability);
                }
                // what the wire offers this season, at each position
                List<String> wire = new ArrayList<>();
                for(Map.Entry<Position, Integer> entry : replacementRank.entrySet()){
                    List<String> pool = byPosition.get(entry.getKey());
                    for(int offset = 0; offset < 3 && pool != null; offset++){
                        int index = Math.min(entry.getValue() + offset, pool.size() - 1);
                        wire.add(pool.get(index));
                    }
                }

                for(int rollout = 0; rollout < rollouts; rollout++){
                    Random random = new Random(880_000L + 7919L * draw + 31L * rollout);
                    DraftSimulator.SimState branch = simulator.branchWith(state,
                            candidate.getValue());
                    simulator.simulateFrom(branch, random, planner.me(),
                            (board, futureSlot) -> board.stream()
                                    .max(Comparator.comparingDouble(
                                            id -> projections.getOrDefault(id, 0.0)))
                                    .orElse(board.isEmpty() ? null : board.get(0)));
                    Set<String> mine = new HashSet<>(planner.myKeeperIDs());
                    for(String id : simulator.players()){
                        Integer at = branch.takenAtOf(id);
                        if(at != null && simulator.slotAt(at) != null
                                && planner.me().equals(simulator.slotAt(at).manager())){
                            mine.add(id);
                        }
                    }
                    // the starting nine I could actually field: my roster, or
                    // a replacement-level body off the wire when it is better
                    Set<String> fieldable = new HashSet<>(mine);
                    fieldable.addAll(wire);
                    double score = StartingLineup.bestNine(fieldable, truth);
                    total += score;
                    samples.add(score);
                }
            }
            value.put(candidate.getKey(), total / (draws * rollouts));
            perDraw.put(candidate.getKey(), samples);
        }

        // standard error of each candidate's gap to the reference, computed
        // on the PAIRED samples (same truth, same rollout seed)
        List<Double> base = perDraw.get(reference);
        for(Position position : perDraw.keySet()){
            List<Double> samples = perDraw.get(position);
            double mean = 0;
            for(int i = 0; i < samples.size(); i++){
                mean += (samples.get(i) - base.get(i)) / samples.size();
            }
            double variance = 0;
            for(int i = 0; i < samples.size(); i++){
                variance += Math.pow(samples.get(i) - base.get(i) - mean, 2)
                        / Math.max(1, samples.size() - 1);
            }
            error.put(position, Math.sqrt(variance / samples.size()));
        }

        List<Position> order = new ArrayList<>(value.keySet());
        order.sort(Comparator.comparingDouble(position -> -value.get(position)));
        double top = value.get(order.get(0));
        System.out.printf("%nMODEL B - expected season under %d sampled truths,"
                + " wire available%n%n", draws);
        System.out.printf("   %-5s %-24s %10s %9s %8s%n", "POS", "BEST AVAILABLE",
                "SEASON", "vs best", "+/- 2se");
        for(Position position : order){
            Player player = Player.getPlayerFromSIDV2(best.get(position));
            double gapError = Math.sqrt(Math.pow(error.getOrDefault(position, 0.0), 2)
                    + Math.pow(error.getOrDefault(order.get(0), 0.0), 2));
            System.out.printf("   %-5s %-24s %10.1f %+9.1f %8.1f%n", position,
                    player.firstName + " " + player.lastName, value.get(position),
                    value.get(position) - top, 2 * gapError);
        }
        System.out.printf("%n   Model B says: %s -> %s%n", order.get(0),
                Player.getPlayerFromSIDV2(best.get(order.get(0))).firstName + " "
                        + Player.getPlayerFromSIDV2(best.get(order.get(0))).lastName);
        System.out.println("   (Insurance value only - what the pick is worth when a"
                + " starter busts,\n    over and above streaming a replacement off the"
                + " wire.)");
    }
}
