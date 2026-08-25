import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Does the league draft rookies ahead of national ADP, the way a keeper
 * league is supposed to? Measured directly: pick number minus Sleeper ADP
 * (negative = taken earlier than the market), rookies versus veterans, split
 * at the nine-round boundary because the keeper-stash motive should live on
 * the benches. The veteran column is the baseline that absorbs any
 * league-wide drift; the DIFF column is the answer.
 *
 *     ./gradlew run -Pmain=RookieMarket
 */
public class RookieMarket {

    private record Reach(String season, String name, int pick, double adp, boolean rookie){}

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();

        System.out.println("pick minus ADP (negative = drafted before the market), non-keeper");
        System.out.println("picks with ADP <= 250. DIFF = rookies relative to veterans:\n");
        System.out.printf("   %-8s %18s %18s %8s%n", "", "ROOKIES", "VETERANS", "");
        System.out.printf("   %-8s %8s %9s %8s %9s %8s%n",
                "SEASON", "N", "mean", "N", "mean", "DIFF");

        double[][] pooled = new double[2][2];        // rounds 1-9: [rookie/vet][sum,n]
        double[][] pooledLate = new double[2][2];    // rounds 10+
        List<Reach> rookieReaches = new ArrayList<>();
        for(int i = drafts.size() - 1; i >= 0; i--){
            if(i >= seasons.size() || seasons.get(i) == null){
                continue;
            }
            String season = seasons.get(i);
            Map<String, Double> adp = HistoricalProjections.adpBySleeperID(configuration, season);
            Set<String> rookies = HistoricalProjections.rookiesForSeason(configuration, season);

            double[][] byGroup = new double[2][2];
            for(JsonElement pickElement : drafts.get(i)){
                JsonObject pick = pickElement.getAsJsonObject();
                JsonElement isKeeper = pick.get("is_keeper");
                if(isKeeper != null && !isKeeper.isJsonNull() && isKeeper.getAsBoolean()){
                    continue;
                }
                String sleeperID = pick.get("player_id").getAsString();
                Player player = Player.getPlayerFromSIDV2(sleeperID);
                Double marketed = adp.get(sleeperID);
                if(player == null || !StartingLineup.isSkillPosition(player.position)
                        || marketed == null || marketed > SelectionModel.ADP_LIMIT){
                    continue;
                }
                int pickNumber = pick.get("pick_no").getAsInt();
                double residual = pickNumber - marketed;
                int group = rookies.contains(sleeperID) ? 0 : 1;
                boolean inGame = pick.get("round").getAsInt() <= SelectionModel.GAME_ROUNDS;
                byGroup[group][0] += residual;
                byGroup[group][1]++;
                double[][] pool = inGame ? pooled : pooledLate;
                pool[group][0] += residual;
                pool[group][1]++;
                if(group == 0){
                    rookieReaches.add(new Reach(season,
                            player.firstName + " " + player.lastName, pickNumber, marketed, true));
                }
            }
            System.out.printf("   %-8s %8.0f %+9.1f %8.0f %+9.1f %+8.1f%n", season,
                    byGroup[0][1], mean(byGroup[0]), byGroup[1][1], mean(byGroup[1]),
                    mean(byGroup[0]) - mean(byGroup[1]));
        }

        System.out.printf("%n   %-22s rookies %+6.1f (N %.0f)   veterans %+6.1f (N %.0f)   DIFF %+5.1f%n",
                "rounds 1-9 pooled:", mean(pooled[0]), pooled[0][1],
                mean(pooled[1]), pooled[1][1], mean(pooled[0]) - mean(pooled[1]));
        System.out.printf("   %-22s rookies %+6.1f (N %.0f)   veterans %+6.1f (N %.0f)   DIFF %+5.1f%n",
                "rounds 10+ pooled:", mean(pooledLate[0]), pooledLate[0][1],
                mean(pooledLate[1]), pooledLate[1][1],
                mean(pooledLate[0]) - mean(pooledLate[1]));

        rookieReaches.sort(Comparator.comparingDouble(reach -> reach.pick() - reach.adp()));
        System.out.println("\nbiggest rookie reaches (picked furthest ahead of ADP):\n");
        for(int i = 0; i < 8 && i < rookieReaches.size(); i++){
            Reach reach = rookieReaches.get(i);
            System.out.printf("   %s  %-24s pick %3d, adp %5.1f  (%+.0f)%n", reach.season(),
                    reach.name(), reach.pick(), reach.adp(), reach.pick() - reach.adp());
        }

        // ---- the question that decides whether any of this belongs in the
        // model: does the SHIPPED model misprice rookie survival inside the
        // nine-round game? Signed gap (observed minus predicted availability)
        // per subgroup on the tuning season; near-zero means the premium is
        // already absorbed through ADP and there is nothing left to encode.
        subgroupBias(configuration);
    }

    private static void subgroupBias(AAAConfiguration configuration){
        int trials = Integer.getInteger("trials", 400);
        Map<String, Double> qbE = SelectionModel.qbEarliness(configuration, 2023);
        DraftSimulator.Extras extras = DraftSimulator.extrasFor(configuration, "2024", 2023);
        SelectionModel model = SelectionModel.fit(
                SelectionModel.loadObservations(configuration, 2021, 2023, qbE,
                        extras.teEarliness(), extras.rbEarliness()),
                SelectionModel.shippedFeatures());
        DraftBacktest.Season season = new DraftBacktest.Season(configuration, "2024");
        DraftSimulator simulator = DraftSimulator.forSeason(season, model, qbE, extras);
        Map<String, double[]> predicted = simulator.survivalMatrix(
                DraftSimulator.gameCheckpoints(), trials, DraftSimulator.SEED);
        Set<String> rookies = HistoricalProjections.rookiesForSeason(configuration, "2024");

        Map<String, Integer> actualPick = new java.util.HashMap<>();
        for(JsonElement pickElement : season.picks){
            JsonObject pick = pickElement.getAsJsonObject();
            JsonElement isKeeper = pick.get("is_keeper");
            if(isKeeper != null && !isKeeper.isJsonNull() && isKeeper.getAsBoolean()){
                continue;
            }
            actualPick.put(pick.get("player_id").getAsString(), pick.get("pick_no").getAsInt());
        }

        double[][] gap = new double[2][2];   // [rookie/vet][sum of (observed-predicted), n]
        int[] checkpoints = DraftSimulator.gameCheckpoints();
        for(Map.Entry<String, double[]> entry : predicted.entrySet()){
            int group = rookies.contains(entry.getKey()) ? 0 : 1;
            int actual = actualPick.getOrDefault(entry.getKey(), Integer.MAX_VALUE);
            for(int c = 0; c < checkpoints.length; c++){
                gap[group][0] += (actual >= checkpoints[c] ? 1 : 0) - entry.getValue()[c];
                gap[group][1]++;
            }
        }
        System.out.println("\ndoes the shipped model misprice rookie survival in rounds 1-9?");
        System.out.println("(2024, fit through 2023; observed minus predicted availability)\n");
        System.out.printf("   rookies   %+6.2f%% signed bias  (N %.0f predictions)%n",
                100 * gap[0][0] / gap[0][1], gap[0][1]);
        System.out.printf("   veterans  %+6.2f%% signed bias  (N %.0f predictions)%n",
                100 * gap[1][0] / gap[1][1], gap[1][1]);
    }

    private static double mean(double[] sumAndCount){
        return sumAndCount[1] == 0 ? 0 : sumAndCount[0] / sumAndCount[1];
    }

}
