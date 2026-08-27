import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Night 2's decisive question: which of MY decisions survive the measured
 * fog? Each fog draw is a possible TRUTH - every player's season resampled
 * from FogFit's position-x-tier constants (bust with the measured
 * probability, else a lognormal-ish ratio around the measured mean/sd). The
 * world keeps drafting off its unchanged sheet (the fog is my outcome
 * uncertainty, not their behavior change); my timing search re-runs under
 * each truth, and three things get counted across draws:
 *
 *   - which timing head wins under truth (does QB@none survive?)
 *   - the REGRET of the projection-chosen plan: truth-value of the plan I
 *     would pick from projections vs truth-value of truth's own best head
 *   - the wait-or-take texture: how often the r8-9 shelf strategy stays
 *     right when reality diverges from the sheet
 *
 *   ./gradlew run -Pmain=DecisionSensitivity [-Pdraws=40] [-Psearch=60]
 */
public class DecisionSensitivity {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int draws = Integer.getInteger("draws", 40);
        int search = Integer.getInteger("search", 60);
        int priceRollouts = Integer.getInteger("trials", 300);

        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration,
                lastCompleted);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, lastCompleted,
                earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        Map<Position, double[][]> fog = FogFit.fit(configuration);
        Map<String, Double> projections = planner.points();

        // position ranks under projections, for tier assignment
        Map<String, Integer> positionRank = new HashMap<>();
        Map<Position, List<String>> byPosition = new EnumMap<>(Position.class);
        for(String sleeperID : projections.keySet()){
            Player player = Player.getPlayerFromSIDV2(sleeperID);
            if(player != null && StartingLineup.isSkillPosition(player.position)){
                byPosition.computeIfAbsent(player.position, u -> new ArrayList<>())
                        .add(sleeperID);
            }
        }
        for(List<String> ids : byPosition.values()){
            ids.sort(Comparator.comparingDouble(id -> -projections.get(id)));
            for(int r = 0; r < ids.size(); r++){
                positionRank.put(ids.get(r), r + 1);
            }
        }

        // the plan projections choose (the baseline being stress-tested)
        TimingPlanner projectionPlanner = new TimingPlanner(planner);
        projectionPlanner.fillWaitingTable(200);

        int[] qbNoneWins = {0};
        int[] qbLateWins = {0};
        double[] regretSum = {0};
        List<String> headLog = new ArrayList<>();
        for(int draw = 0; draw < draws; draw++){
            Random random = new Random(DraftSimulator.SEED + 91_000_000L + 7919L * draw);
            Map<String, Double> truth = new HashMap<>();
            for(Map.Entry<String, Double> entry : projections.entrySet()){
                Player player = Player.getPlayerFromSIDV2(entry.getKey());
                if(player == null || !StartingLineup.isSkillPosition(player.position)
                        || !positionRank.containsKey(entry.getKey())){
                    truth.put(entry.getKey(), entry.getValue());
                    continue;
                }
                double[][] tiers = fog.get(player.position);
                double[] constants = tiers[FogFit.tier(positionRank.get(entry.getKey()))];
                double ratio;
                if(random.nextDouble() < constants[2]){
                    ratio = 0.1 + 0.5 * random.nextDouble();   // the bust mass
                }
                else {
                    ratio = Math.max(0.2, constants[0]
                            + constants[1] * random.nextGaussian());
                }
                truth.put(entry.getKey(), entry.getValue() * ratio);
            }

            // my search under truth: world unchanged, my values = truth
            TimingPlanner truthPlanner = new TimingPlanner(planner, truth);
            truthPlanner.fillWaitingTable(150);
            int picks = 9;
            List<int[]> heads = new ArrayList<>();
            for(int qbAt = -1; qbAt < picks; qbAt++){
                for(int teAt = 0; teAt < picks; teAt++){
                    if(teAt != qbAt){
                        heads.add(new int[]{qbAt, teAt});
                    }
                }
            }
            double[] means = IntStream.range(0, heads.size()).parallel()
                    .mapToDouble(h -> headMean(truthPlanner, planner,
                            heads.get(h), search)).toArray();
            int argmax = 0;
            for(int h = 1; h < means.length; h++){
                if(means[h] > means[argmax]){
                    argmax = h;
                }
            }
            int[] best = heads.get(argmax);
            boolean qbNone = best[0] < 0;
            boolean qbLate = best[0] >= 6;
            if(qbNone){
                qbNoneWins[0]++;
            }
            if(qbNone || qbLate){
                qbLateWins[0]++;
            }
            headLog.add(String.format("QB@%s TE@r%d",
                    best[0] < 0 ? "none" : "r" + (best[0] + 1), best[1] + 1));

            // regret: price the projection plan and truth's best, both under truth
            double truthOfProjectionPlan = headMean(truthPlanner, planner,
                    new int[]{-1, 6}, priceRollouts);
            double truthOfTruthPlan = headMean(truthPlanner, planner, best,
                    priceRollouts);
            regretSum[0] += truthOfTruthPlan - truthOfProjectionPlan;
        }

        System.out.printf("fog draws %d, search %d rollouts per head:%n%n", draws, search);
        System.out.printf("   QB@none best under truth:      %d/%d (%.0f%%)%n",
                qbNoneWins[0], draws, 100.0 * qbNoneWins[0] / draws);
        System.out.printf("   QB none-or-late (r7+) best:    %d/%d (%.0f%%)%n",
                qbLateWins[0], draws, 100.0 * qbLateWins[0] / draws);
        System.out.printf("   mean regret of the projection plan under truth: %.1f "
                + "points%n", regretSum[0] / draws);
        Map<String, Integer> headCounts = new HashMap<>();
        for(String head : headLog){
            headCounts.merge(head, 1, Integer::sum);
        }
        System.out.println("   winning heads across truths:");
        headCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
                .forEach(entry -> System.out.printf("      %-18s %d%n",
                        entry.getKey(), entry.getValue()));
    }

    static double headMean(TimingPlanner truthPlanner, DraftPlanner planner,
                           int[] head, int rollouts){
        double total = 0;
        for(int r = 0; r < rollouts; r++){
            TimingPlanner.TimingPolicy policy = truthPlanner.new TimingPolicy(
                    head[0], head[1]);
            planner.simulator().simulateOnce(
                    new Random(TimingPlanner.SEARCH_SEED + 7919L * r),
                    planner.me(), policy);
            total += truthPlanner.scoreMine(policy.mine);
        }
        return total / rollouts;
    }
}
