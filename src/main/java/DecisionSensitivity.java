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

    static final int[][] CANDIDATES = {{-1, 6}, {-1, 7}, {1, 6}, {5, 6}, {7, 6}, {7, 8}};
    static final String[] LABELS = {"QB@none TE@r7", "QB@none TE@r8", "QB@r2 TE@r7",
            "QB@r6 TE@r7", "QB@r8 TE@r7", "QB@r8 TE@r9"};


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

        double[] values = new double[CANDIDATES.length];
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

            // Pre-registered candidates only - no argmax over 90 noisy heads
            // (that inflated the winner and made "regret" meaningless). Each
            // candidate is priced under THIS truth on the SAME seeds, so the
            // comparison is paired and the noise cancels.
            // The policy sees PROJECTIONS (what I have on draft night); only
            // the final best-nine is scored under truth. Scoring under truth
            // while also PICKING under truth would let the policy dodge
            // busts it could not have known about - clairvoyance.
            TimingPlanner truthPlanner = projectionPlanner;
            truthPlanner.scoreUnder(truth);
            for(int c = 0; c < CANDIDATES.length; c++){
                values[c] += headMean(truthPlanner, planner, CANDIDATES[c],
                        priceRollouts);
            }
        }

        System.out.printf("fog draws %d, %d rollouts per candidate, paired on the\n"
                + "same truths - each row is that strategy's MEAN value under fog,\n"
                + "decisions made on projections only:%n%n", draws, priceRollouts);
        int argmax = 0;
        for(int c = 1; c < values.length; c++){
            if(values[c] > values[argmax]){
                argmax = c;
            }
        }
        for(int c = 0; c < CANDIDATES.length; c++){
            System.out.printf("   %-18s %8.1f  %+6.1f vs QB@none TE@r7%s%n", LABELS[c],
                    values[c] / draws, (values[c] - values[0]) / draws,
                    c == argmax ? "   <- best under fog" : "");
        }
        System.out.println("\nA small spread means the timing decision is fog-proof: the"
                + "\nsame plan is right whether or not the projections come true.");
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
