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
 * Justin's question: rounds 8-9 are pure insurance (the starting nine is
 * already full after seven picks plus two keepers), so which position should
 * they buy? A backup QB only starts if Purdy collapses - measured 12% - but
 * replaces a huge hole. A fourth/fifth WR starts far more often, because
 * three WR slots plus flexes give many more chances that SOMEONE busts, but
 * each promotion is worth less. Frequency versus severity, decided by data.
 *
 * Rounds 1-7 are held at the shipped plan's starting-nine fill; only the two
 * insurance rounds vary. Decisions use projections, scoring uses each sampled
 * truth, paired across identical fog draws.
 *
 *   ./gradlew run -Pmain=InsuranceTest [-Pdraws=40] [-Ptrials=300]
 */
public class InsuranceTest {

    static final List<Position> BASE = List.of(Position.RB, Position.WR, Position.RB,
            Position.WR, Position.WR, Position.WR, Position.TE);

    static final Position[][] TAILS = {
            {Position.QB, Position.RB}, {Position.QB, Position.WR},
            {Position.WR, Position.WR}, {Position.WR, Position.RB},
            {Position.RB, Position.RB}, {Position.TE, Position.WR},
            {Position.QB, Position.QB}};

    /**
     * Where the waiver wire actually starts, per position: one past the
     * average number of that position drafted across the league's FULL
     * 16-round history. Shared with LiveInsurance so the two never disagree
     * about how rich the wire is - counting only rounds 10+ once put the wire
     * at QB8 and made insurance look absurdly generous.
     */
    public static Map<Position, Integer> replacementRanks(AAAConfiguration configuration){
        Map<Position, Integer> drafted = new EnumMap<>(Position.class);
        int seasonsCounted = 0;
        for(com.google.gson.JsonArray picks : configuration.getPreviousDraftPicks()){
            seasonsCounted++;
            for(com.google.gson.JsonElement element : picks){
                Player player = Player.getPlayerFromSIDV2(element.getAsJsonObject()
                        .get("player_id").getAsString());
                if(player != null && StartingLineup.isSkillPosition(player.position)){
                    drafted.merge(player.position, 1, Integer::sum);
                }
            }
        }
        Map<Position, Integer> ranks = new EnumMap<>(Position.class);
        for(Map.Entry<Position, Integer> entry : drafted.entrySet()){
            ranks.put(entry.getKey(),
                    entry.getValue() / Math.max(seasonsCounted, 1) + 1);
        }
        return ranks;
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int draws = Integer.getInteger("draws", 40);
        int rollouts = Integer.getInteger("trials", 300);

        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                model, earliness);
        TimingPlanner timing = new TimingPlanner(planner);
        timing.fillWaitingTable(200);
        Map<Position, double[][]> fog = FogFit.fit(configuration);
        Map<String, Double> projections = planner.points();

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

        // Waiver replacement, measured from the league's FULL historical
        // drafts (16 rounds), not the nine-round window: by kickoff every
        // team has drafted its backups, so the wire is far shallower than
        // the nine-round board suggests. Counting only rounds 10+ picks in
        // the earlier version left the wire absurdly rich (QB8, TE5).
        Map<Position, Integer> replacementRank = replacementRanks(configuration);
        System.out.println("replacement ranks from the full historical drafts"
                + " (next man up off the wire): " + replacementRank);

        double[] totals = new double[TAILS.length];
        double[] withWire = new double[TAILS.length];
        double[] noFog = new double[TAILS.length];
        for(int t = 0; t < TAILS.length; t++){
            noFog[t] = value(timing, planner, sequence(t), rollouts);
        }
        for(int draw = 0; draw < draws; draw++){
            Random random = new Random(DraftSimulator.SEED + 93_000_000L + 7919L * draw);
            Map<String, Double> truth = new HashMap<>();
            for(Map.Entry<String, Double> entry : projections.entrySet()){
                Player player = Player.getPlayerFromSIDV2(entry.getKey());
                if(player == null || !positionRank.containsKey(entry.getKey())){
                    truth.put(entry.getKey(), entry.getValue());
                    continue;
                }
                double[] c = fog.get(player.position)
                        [FogFit.tier(positionRank.get(entry.getKey()))];
                double ratio = random.nextDouble() < c[2]
                        ? 0.1 + 0.5 * random.nextDouble()
                        : Math.max(0.2, c[0] + c[1] * random.nextGaussian());
                truth.put(entry.getKey(), entry.getValue() * ratio);
            }
            timing.scoreUnder(truth);
            for(int t = 0; t < TAILS.length; t++){
                totals[t] += value(timing, planner, sequence(t), rollouts);
            }
            // the same truth, now with a waiver wire available
            Map<Position, Double> wire = new EnumMap<>(Position.class);
            for(Map.Entry<Position, Integer> e : replacementRank.entrySet()){
                List<String> pool = byPosition.get(e.getKey());
                int index = Math.min(e.getValue(), pool.size() - 1);
                wire.put(e.getKey(), truth.getOrDefault(pool.get(index), 0.0));
            }
            timing.replacementLevel(wire);
            for(int t = 0; t < TAILS.length; t++){
                withWire[t] += value(timing, planner, sequence(t), rollouts);
            }
            timing.replacementLevel(null);
        }
        timing.scoreUnder(null);

        System.out.printf("rounds 1-7 fixed at the starting-nine fill %s;%n"
                + "only the two insurance rounds vary. %d fog draws, %d rollouts, "
                + "paired.%n%n", BASE, draws, rollouts);
        System.out.printf("   %-14s %10s %12s %10s %12s %10s%n", "r8 + r9", "no fog",
                "no wire", "insurance", "with wire", "insurance");
        int best = 0;
        for(int t = 1; t < TAILS.length; t++){
            if(totals[t] > totals[best]){
                best = t;
            }
        }
        for(int t = 0; t < TAILS.length; t++){
            System.out.printf("   %-14s %10.1f %12.1f %+10.1f %12.1f %+10.1f%s%n",
                    TAILS[t][0] + "+" + TAILS[t][1], noFog[t], totals[t] / draws,
                    totals[t] / draws - totals[0] / draws, withWire[t] / draws,
                    withWire[t] / draws - withWire[0] / draws,
                    t == best ? "   <- best, no wire" : "");
        }
        System.out.println("\n'insurance' is relative to the shipped QB+RB tail. The"
                + "\nno-fog column shows what the exact-projection model saw: nearly"
                + "\nnothing, which is why it could not choose between these.");
    }

    static List<Position> sequence(int tail){
        List<Position> full = new ArrayList<>(BASE);
        full.add(TAILS[tail][0]);
        full.add(TAILS[tail][1]);
        return full;
    }

    static double value(TimingPlanner timing, DraftPlanner planner,
                        List<Position> sequence, int rollouts){
        return IntStream.range(0, rollouts).parallel().mapToDouble(r -> {
            TimingPlanner.CommittedPolicy policy = timing.new CommittedPolicy(sequence);
            planner.simulator().simulateOnce(
                    new Random(TimingPlanner.EVAL_SEED + 7919L * r), planner.me(), policy);
            return timing.scoreMine(policy.mine);
        }).sum() / rollouts;
    }
}
