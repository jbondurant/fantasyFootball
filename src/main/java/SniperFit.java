import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * M1: the sniper mixture, fitted and gated. Human rooms are chalk with
 * occasional real reaches; a single-temperature softmax under-produces the
 * tail. Model: each opponent pick is the base brain with probability
 * 1 - eps_m and a flat grab over the choice set with probability eps_m,
 * where eps_m is that manager's fitted reach rate.
 *
 * Reaches are measured WITHIN POSITION (the chosen player's rank among
 * same-position players still available on the defaults sheet) so that
 * positional need never masquerades as a stray - the choice model keeps
 * owning position selection. Fit on 2022-2024, the mixture scale chosen on
 * the train side; ONE confirmation on held-out 2025:
 *
 *   gate A  the simulated within-position reach distribution must move
 *           toward the real 2025 shape (share>=5, share>=10, median)
 *   gate B  survival calibration at the game checkpoints must not degrade
 *
 *   ./gradlew run -Pmain=SniperFit [-Ptrials=250]
 */
public class SniperFit {

    static final int REACH_THRESHOLD = 5;
    static final double SHRINK = 20;

    record ReachRow(String manager, Position position, int reach){}

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 250);

        // ---- real within-position reaches, per season ----
        Map<String, List<ReachRow>> real = new TreeMap<>();
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();
        for(int i = 0; i < drafts.size() && i < seasons.size(); i++){
            String season = seasons.get(i);
            if(season == null){
                continue;
            }
            Map<String, Integer> feed = ReachAudit.defaultsFeed(season);
            if(feed != null){
                real.put(season, reachRows(drafts.get(i), feed));
            }
        }

        // ---- epsilon fingerprints from the train seasons ----
        Map<String, int[]> counts = new HashMap<>();   // manager -> {reaches, picks}
        int leagueReaches = 0;
        int leaguePicks = 0;
        for(String season : real.keySet()){
            if(season.equals("2025")){
                continue;
            }
            for(ReachRow row : real.get(season)){
                int[] c = counts.computeIfAbsent(row.manager(), u -> new int[2]);
                c[1]++;
                leaguePicks++;
                if(row.reach() >= REACH_THRESHOLD){
                    c[0]++;
                    leagueReaches++;
                }
            }
        }
        double leagueRate = leagueReaches / (double) leaguePicks;
        Map<String, Double> epsilon = new TreeMap<>();
        for(Map.Entry<String, int[]> entry : counts.entrySet()){
            epsilon.put(entry.getKey(),
                    (entry.getValue()[0] + SHRINK * leagueRate)
                            / (entry.getValue()[1] + SHRINK));
        }
        System.out.printf("train 2022-2024: league within-position reach rate "
                + "(>=%d) %.2f over %d picks%n", REACH_THRESHOLD, leagueRate, leaguePicks);
        System.out.printf("%-22s %8s%n", "MANAGER", "epsilon");
        for(Map.Entry<String, Double> entry : epsilon.entrySet()){
            System.out.printf("%-22s %8.2f%n",
                    HumanOfInterest.getHumanFromID(entry.getKey()), entry.getValue());
        }

        // ---- scale chosen on the TRAIN side (2024 world, 2023-fitted brain) ----
        Map<String, Double> earliness2023 = SelectionModel.qbEarliness(configuration, 2023);
        ChoiceModel brain2023 = BoostedSelectionModel.fitShipped(configuration, 2023,
                earliness2023);
        DraftBacktest.Season season2024 = new DraftBacktest.Season(configuration, "2024");
        Map<String, Integer> feed2024 = ReachAudit.defaultsFeed("2024");
        double bestScale = 0;
        double bestDistance = Double.MAX_VALUE;
        for(double scale : new double[]{0, 0.5, 0.75, 1.0}){
            DraftSimulator simulator = DraftSimulator.forSeason(season2024, brain2023,
                    earliness2023, DraftSimulator.extrasFor(configuration, "2024", 2023));
            if(scale > 0){
                simulator = simulator.withManagerModels(wrap(brain2023, epsilon, scale));
            }
            double distance = reachDistance(simulator, season2024.picks, feed2024,
                    real.get("2024"), trials);
            System.out.printf("   scale %.2f: 2024 reach-shape distance %.3f%n",
                    scale, distance);
            if(distance < bestDistance){
                bestDistance = distance;
                bestScale = scale;
            }
        }
        System.out.printf("chosen scale %.2f%n", bestScale);

        // ---- the single 2025 confirmation ----
        Map<String, Double> earliness2024 = SelectionModel.qbEarliness(configuration, 2024);
        ChoiceModel brain2024 = BoostedSelectionModel.fitShipped(configuration, 2024,
                earliness2024);
        DraftBacktest.Season season2025 = new DraftBacktest.Season(configuration, "2025");
        Map<String, Integer> feed2025 = ReachAudit.defaultsFeed("2025");
        DraftSimulator plain = DraftSimulator.forSeason(season2025, brain2024,
                earliness2024, DraftSimulator.extrasFor(configuration, "2025", 2024));
        DraftSimulator sniper = bestScale == 0 ? plain
                : plain.withManagerModels(wrap(brain2024, epsilon, bestScale));

        double plainReach = reachDistance(plain, season2025.picks, feed2025,
                real.get("2025"), trials);
        double sniperReach = reachDistance(sniper, season2025.picks, feed2025,
                real.get("2025"), trials);
        int[] checkpoints = DraftSimulator.gameCheckpoints();
        double plainCalibration = DraftBacktest.calibrationOfMatrix(
                plain.survivalMatrix(checkpoints, trials, DraftSimulator.SEED),
                checkpoints, season2025, null);
        double sniperCalibration = DraftBacktest.calibrationOfMatrix(
                sniper.survivalMatrix(checkpoints, trials, DraftSimulator.SEED),
                checkpoints, season2025, null);

        System.out.printf("%n2025 confirmation:%n");
        System.out.printf("   reach-shape distance: plain %.3f, sniper %.3f  (%s)%n",
                plainReach, sniperReach,
                sniperReach < plainReach ? "gate A PASS" : "gate A FAIL");
        System.out.printf("   survival calibration: plain %.2f%%, sniper %.2f%%  (%s)%n",
                plainCalibration * 100, sniperCalibration * 100,
                sniperCalibration <= plainCalibration + 0.001
                        ? "gate B PASS" : "gate B FAIL");
        System.out.printf("%nverdict: %s%n",
                bestScale > 0 && sniperReach < plainReach
                        && sniperCalibration <= plainCalibration + 0.001
                        ? "SHIP the sniper mixture (scale " + bestScale + ")"
                        : "REJECT - the single-temperature brain stands");
    }

    static Map<String, ChoiceModel> wrap(ChoiceModel base, Map<String, Double> epsilon,
                                         double scale){
        Map<String, ChoiceModel> wrapped = new HashMap<>();
        for(Map.Entry<String, Double> entry : epsilon.entrySet()){
            double eps = Math.min(0.95, entry.getValue() * scale);
            wrapped.put(entry.getKey(), features -> {
                double[] probabilities = base.choiceProbabilities(features);
                double uniform = 1.0 / probabilities.length;
                double[] mixed = new double[probabilities.length];
                for(int a = 0; a < probabilities.length; a++){
                    mixed[a] = (1 - eps) * probabilities[a] + eps * uniform;
                }
                return mixed;
            });
        }
        return wrapped;
    }

    /** Mean abs diff of (share>=5, share>=10, median/30) vs the real rows. */
    static double reachDistance(DraftSimulator simulator, JsonArray realPicks,
                                Map<String, Integer> feed, List<ReachRow> realRows,
                                int trials){
        double[] realShape = shape(realRows);
        double[] totals = new double[3];
        Random random = new Random(DraftSimulator.SEED + 77);
        for(int trial = 0; trial < trials; trial++){
            Map<String, Integer> takenAt = simulator.simulateOnce(random);
            double[] simShape = shape(reachRowsFromSimulation(simulator, takenAt, feed));
            for(int m = 0; m < 3; m++){
                totals[m] += simShape[m];
            }
        }
        double distance = 0;
        for(int m = 0; m < 3; m++){
            distance += Math.abs(totals[m] / trials - realShape[m]);
        }
        return distance;
    }

    static double[] shape(List<ReachRow> rows){
        if(rows == null || rows.isEmpty()){
            return new double[]{0, 0, 0};
        }
        List<Integer> reaches = new ArrayList<>();
        for(ReachRow row : rows){
            reaches.add(row.reach());
        }
        reaches.sort(Integer::compare);
        double over5 = reaches.stream().filter(r -> r >= 5).count()
                / (double) reaches.size();
        double over10 = reaches.stream().filter(r -> r >= 10).count()
                / (double) reaches.size();
        double median = reaches.get(reaches.size() / 2) / 30.0;
        return new double[]{over5, over10, median};
    }

    /** Within-position reach rows from a real draft's pick array. */
    static List<ReachRow> reachRows(JsonArray picks, Map<String, Integer> feed){
        List<ReachRow> rows = new ArrayList<>();
        List<String> ordered = new ArrayList<>(feed.keySet());
        ordered.sort(Comparator.comparingInt(feed::get));
        Set<String> taken = new HashSet<>();
        for(JsonElement pickElement : picks){
            JsonObject pick = pickElement.getAsJsonObject();
            String sleeperID = pick.get("player_id").getAsString();
            JsonElement isKeeper = pick.get("is_keeper");
            JsonElement pickedBy = pick.get("picked_by");
            boolean keeper = isKeeper != null && !isKeeper.isJsonNull()
                    && isKeeper.getAsBoolean();
            Player player = Player.getPlayerFromSIDV2(sleeperID);
            if(!keeper && pickedBy != null && !pickedBy.isJsonNull() && player != null
                    && StartingLineup.isSkillPosition(player.position)
                    && feed.containsKey(sleeperID)){
                rows.add(new ReachRow(pickedBy.getAsString(), player.position,
                        withinPositionRank(sleeperID, player.position, ordered, taken)));
            }
            taken.add(sleeperID);
        }
        return rows;
    }

    /** The same from a simulated draft's takenAt map. */
    static List<ReachRow> reachRowsFromSimulation(DraftSimulator simulator,
            Map<String, Integer> takenAt, Map<String, Integer> feed){
        List<Map.Entry<String, Integer>> order = new ArrayList<>(takenAt.entrySet());
        order.sort(Map.Entry.comparingByValue());
        List<String> ordered = new ArrayList<>(feed.keySet());
        ordered.sort(Comparator.comparingInt(feed::get));
        Set<String> taken = new HashSet<>();
        List<ReachRow> rows = new ArrayList<>();
        for(Map.Entry<String, Integer> entry : order){
            String sleeperID = entry.getKey();
            DraftSimulator.Slot slot = simulator.slotAt(entry.getValue());
            Player player = Player.getPlayerFromSIDV2(sleeperID);
            if(slot != null && player != null && feed.containsKey(sleeperID)){
                rows.add(new ReachRow(slot.manager(), player.position,
                        withinPositionRank(sleeperID, player.position, ordered, taken)));
            }
            taken.add(sleeperID);
        }
        return rows;
    }

    static int withinPositionRank(String sleeperID, Position position,
                                  List<String> orderedFeed, Set<String> taken){
        int rank = 0;
        for(String candidate : orderedFeed){
            if(candidate.equals(sleeperID)){
                return rank;
            }
            if(!taken.contains(candidate)
                    && Player.getPlayerFromSIDV2(candidate).position == position){
                rank++;
            }
        }
        return rank;
    }
}
