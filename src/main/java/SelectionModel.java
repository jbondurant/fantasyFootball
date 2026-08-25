import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The draft, one selection at a time: a conditional-logit model of
 * P(this manager takes this player | the board and their roster).
 *
 * This is the step-B architecture. It fits the thing that actually happens -
 * selections - so simulating from it IS the generative model: players are
 * coupled because every pick removes one, and the marginal-versus-simulator
 * mismatch that sank the displacement challengers cannot exist here.
 *
 * Features per (manager, player, state), all computable mid-draft:
 *   f0  log ADP rank on the remaining board (the market)
 *   f1  log projected-points rank on the remaining board (the value signal)
 *   f2  unfilled starter slots at the player's position (roster need)
 *   f3  position already saturated (QB in hand and this is a QB, etc.)
 *   f4  isQB times the manager's fitted QB-timing earliness
 *   f5-f7  positional intercepts (QB, RB, TE; WR is the baseline) - the
 *          league-bias layer that won the location contest, expressed as
 *          preferences rather than pick offsets
 *   f8     the QB run: for QB candidates, how many of the last RUN_WINDOW
 *          selections were QBs - Justin's herding hypothesis ("it's trendy
 *          now and I'm afraid of the fall-off"), made falsifiable. QB-only
 *          because a pooled version fit to +0.01: the raw run rates go
 *          opposite ways by position (QB +5 points, TE -7) and a shared
 *          coefficient cancels itself
 *
 * Fitting is concave maximum likelihood; plain gradient ascent converges in
 * seconds on ~500 in-game selections.
 *
 *     ./gradlew run -Pmain=SelectionModel
 */
public class SelectionModel {

    public static final int FEATURES = 9;
    public static final int GAME_ROUNDS = 9;
    static final int CHOICE_SET = 60;
    static final double ADP_LIMIT = 250.0;
    /** Selections that count as "recent" for the run feature. */
    public static final int RUN_WINDOW = 6;

    /**
     * The feature set that won the leakage-safe 2024 chooser in
     * DraftSimulator.main; production fits (DraftPlanner, KeeperPlan) use
     * exactly this. Update it only when the chooser's verdict changes.
     * Current verdict: positional intercepts AND the QB-run feature in
     * (2024 calibration 1.19% with it versus 1.40% without, 400 trials per
     * cell). The fitted run coefficient is NEGATIVE (-0.57): a recent QB run
     * suppresses the next QB pick once ADP, need and saturation are held
     * fixed - the opposite of the herding story the raw counts suggest.
     */
    public static boolean[] shippedFeatures(){
        boolean[] active = new boolean[FEATURES];
        java.util.Arrays.fill(active, true);
        return active;
    }

    /** One historical selection: the chosen index within its choice set. */
    public record Observation(double[][] features, int chosen) {}

    private final double[] beta;

    public SelectionModel(double[] beta){
        this.beta = beta.clone();
    }

    public double[] beta(){
        return beta.clone();
    }

    /**
     * The same preferences, sharpened. MLE optimizes per-pick log-loss, which
     * can leave the choice distribution flatter than real drafts compound to;
     * utilities are linear, so a temperature is just a scaled beta. Tuned on a
     * held-out season by the simulator, exactly like the gaussian's sigma was.
     */
    public SelectionModel scaled(double temperature){
        double[] scaled = beta.clone();
        for(int f = 0; f < scaled.length; f++){
            scaled[f] *= temperature;
        }
        return new SelectionModel(scaled);
    }

    public double utility(double[] features){
        double total = 0;
        for(int f = 0; f < features.length; f++){
            total += beta[f] * features[f];
        }
        return total;
    }

    public double[] choiceProbabilities(double[][] features){
        double[] utilities = new double[features.length];
        double max = Double.NEGATIVE_INFINITY;
        for(int a = 0; a < features.length; a++){
            utilities[a] = utility(features[a]);
            max = Math.max(max, utilities[a]);
        }
        double sum = 0;
        for(int a = 0; a < features.length; a++){
            utilities[a] = Math.exp(utilities[a] - max);
            sum += utilities[a];
        }
        for(int a = 0; a < features.length; a++){
            utilities[a] /= sum;
        }
        return utilities;
    }

    // ---- fitting ----

    public static SelectionModel fit(List<Observation> observations, boolean[] activeFeatures){
        double[] beta = new double[FEATURES];
        double learningRate = 0.2;
        double previous = Double.NEGATIVE_INFINITY;
        for(int iteration = 0; iteration < 4000; iteration++){
            double[] gradient = new double[FEATURES];
            double logLikelihood = 0;
            SelectionModel current = new SelectionModel(beta);
            for(Observation observation : observations){
                double[] probabilities = current.choiceProbabilities(observation.features());
                logLikelihood += Math.log(Math.max(probabilities[observation.chosen()], 1e-12));
                for(int a = 0; a < observation.features().length; a++){
                    double weight = (a == observation.chosen() ? 1.0 : 0.0) - probabilities[a];
                    for(int f = 0; f < FEATURES; f++){
                        gradient[f] += weight * observation.features()[a][f];
                    }
                }
            }
            for(int f = 0; f < FEATURES; f++){
                if(activeFeatures[f]){
                    beta[f] += learningRate * (gradient[f] / observations.size() - 0.001 * beta[f]);
                }
            }
            if(iteration % 200 == 199){
                if(Math.abs(logLikelihood - previous) < 1e-7 * observations.size()){
                    break;
                }
                previous = logLikelihood;
            }
        }
        return new SelectionModel(beta);
    }

    public static double meanLogLoss(SelectionModel model, List<Observation> observations){
        double total = 0;
        for(Observation observation : observations){
            total -= Math.log(Math.max(
                    model.choiceProbabilities(observation.features())[observation.chosen()], 1e-12));
        }
        return total / observations.size();
    }

    public static double topK(SelectionModel model, List<Observation> observations, int k){
        int hits = 0;
        for(Observation observation : observations){
            double[] probabilities = model.choiceProbabilities(observation.features());
            double chosenProbability = probabilities[observation.chosen()];
            int better = 0;
            for(double probability : probabilities){
                if(probability > chosenProbability){
                    better++;
                }
            }
            if(better < k){
                hits++;
            }
        }
        return hits / (double) observations.size();
    }

    // ---- dataset ----

    /** QB-timing earliness per manager (positive = takes QBs early), leakage-safe. */
    public static Map<String, Double> qbEarliness(AAAConfiguration configuration, int lastSeason){
        Map<String, List<Integer>> firstRounds = new HashMap<>();
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();
        for(int i = 0; i < drafts.size() && i < seasons.size(); i++){
            if(seasons.get(i) == null || Integer.parseInt(seasons.get(i)) > lastSeason){
                continue;
            }
            Map<String, Integer> firstThisSeason = new HashMap<>();
            for(JsonElement pickElement : drafts.get(i)){
                JsonObject pick = pickElement.getAsJsonObject();
                JsonElement isKeeper = pick.get("is_keeper");
                if(isKeeper != null && !isKeeper.isJsonNull() && isKeeper.getAsBoolean()){
                    continue;
                }
                Player player = Player.getPlayerFromSIDV2(pick.get("player_id").getAsString());
                if(player == null || !player.position.equals(Position.QB)){
                    continue;
                }
                JsonElement pickedBy = pick.get("picked_by");
                if(pickedBy == null || pickedBy.isJsonNull()){
                    continue;
                }
                firstThisSeason.merge(pickedBy.getAsString(), pick.get("round").getAsInt(), Math::min);
            }
            for(Map.Entry<String, Integer> entry : firstThisSeason.entrySet()){
                firstRounds.computeIfAbsent(entry.getKey(), u -> new ArrayList<>()).add(entry.getValue());
            }
        }
        double leagueTotal = 0;
        int leagueCount = 0;
        for(List<Integer> rounds : firstRounds.values()){
            for(int round : rounds){
                leagueTotal += round;
                leagueCount++;
            }
        }
        double leagueMean = leagueCount == 0 ? 7.0 : leagueTotal / leagueCount;
        Map<String, Double> earliness = new HashMap<>();
        for(Map.Entry<String, List<Integer>> entry : firstRounds.entrySet()){
            double mean = entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(leagueMean);
            // Shrunk toward the league by the number of seasons observed.
            int n = entry.getValue().size();
            earliness.put(entry.getKey(), (leagueMean - mean) * n / (n + 2.0));
        }
        return earliness;
    }

    /** Rounds 1-9 selections for the given seasons, reconstructed with state. */
    public static List<Observation> loadObservations(AAAConfiguration configuration,
                                                     int firstSeason, int lastSeason,
                                                     Map<String, Double> qbEarliness){
        List<Observation> observations = new ArrayList<>();
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();
        for(int i = 0; i < drafts.size() && i < seasons.size(); i++){
            String season = seasons.get(i);
            if(season == null){
                continue;
            }
            int year = Integer.parseInt(season);
            if(year < firstSeason || year > lastSeason){
                continue;
            }
            observations.addAll(seasonObservations(configuration, drafts.get(i), season, qbEarliness));
        }
        return observations;
    }

    private static List<Observation> seasonObservations(AAAConfiguration configuration,
                                                        JsonArray draft, String season,
                                                        Map<String, Double> qbEarliness){
        Map<String, Double> adp = HistoricalProjections.adpBySleeperID(configuration, season);
        Map<String, Double> points = HistoricalProjections.rawPointsBySleeperID(configuration, season);

        // Keepers are off the board and on their owner's roster from the start.
        Set<String> kept = new HashSet<>();
        Map<String, Map<Position, Integer>> rosters = new HashMap<>();
        List<JsonObject> picks = new ArrayList<>();
        for(JsonElement pickElement : draft){
            JsonObject pick = pickElement.getAsJsonObject();
            JsonElement isKeeper = pick.get("is_keeper");
            String sleeperID = pick.get("player_id").getAsString();
            if(isKeeper != null && !isKeeper.isJsonNull() && isKeeper.getAsBoolean()){
                kept.add(sleeperID);
                Player player = Player.getPlayerFromSIDV2(sleeperID);
                JsonElement owner = pick.get("picked_by");
                if(player != null && owner != null && !owner.isJsonNull()){
                    rosters.computeIfAbsent(owner.getAsString(), u -> new EnumMap<>(Position.class))
                            .merge(player.position, 1, Integer::sum);
                }
            }
            else {
                picks.add(pick);
            }
        }
        picks.sort(Comparator.comparingInt(p -> p.get("pick_no").getAsInt()));

        List<String> board = new ArrayList<>();
        for(Map.Entry<String, Double> entry : adp.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null || !StartingLineup.isSkillPosition(player.position)){
                continue;
            }
            if(entry.getValue() > ADP_LIMIT || kept.contains(entry.getKey())){
                continue;
            }
            board.add(entry.getKey());
        }

        List<Observation> observations = new ArrayList<>();
        List<Position> recentPicks = new ArrayList<>();
        for(JsonObject pick : picks){
            if(pick.get("round").getAsInt() > GAME_ROUNDS){
                break;
            }
            String chosenID = pick.get("player_id").getAsString();
            Player player = Player.getPlayerFromSIDV2(chosenID);
            JsonElement pickedBy = pick.get("picked_by");
            if(pickedBy == null || pickedBy.isJsonNull()){
                board.remove(chosenID);
                if(player != null && StartingLineup.isSkillPosition(player.position)){
                    recentPicks.add(player.position);
                }
                continue;
            }
            String manager = pickedBy.getAsString();

            List<String> choiceSet = new ArrayList<>(board);
            choiceSet.sort(Comparator.comparingDouble(adp::get));
            if(choiceSet.size() > CHOICE_SET){
                choiceSet = new ArrayList<>(choiceSet.subList(0, CHOICE_SET));
            }
            int chosen = choiceSet.indexOf(chosenID);
            if(chosen >= 0){
                observations.add(new Observation(
                        features(choiceSet, adp, points,
                                rosters.computeIfAbsent(manager, u -> new EnumMap<>(Position.class)),
                                qbEarliness.getOrDefault(manager, 0.0), recentPicks),
                        chosen));
            }
            board.remove(chosenID);
            if(player != null){
                rosters.computeIfAbsent(manager, u -> new EnumMap<>(Position.class))
                        .merge(player.position, 1, Integer::sum);
                if(StartingLineup.isSkillPosition(player.position)){
                    recentPicks.add(player.position);
                }
            }
        }
        return observations;
    }

    /** Same-position count within the trailing run window. */
    static int runCount(List<Position> recentPicks, Position position){
        int count = 0;
        for(int r = Math.max(0, recentPicks.size() - RUN_WINDOW); r < recentPicks.size(); r++){
            if(recentPicks.get(r).equals(position)){
                count++;
            }
        }
        return count;
    }

    /** The feature matrix for one choice set - also used by the simulator. */
    public static double[][] features(List<String> choiceSet,
                                      Map<String, Double> adp,
                                      Map<String, Double> points,
                                      Map<Position, Integer> roster,
                                      double managerQBEarliness,
                                      List<Position> recentPicks){
        int n = choiceSet.size();
        Integer[] byAdp = rankOrder(choiceSet, adp, true);
        Integer[] byPoints = rankOrder(choiceSet, points, false);
        int[] adpRank = invert(byAdp);
        int[] pointsRank = invert(byPoints);

        Map<Position, Integer> starterSlots = new EnumMap<>(Position.class);
        starterSlots.put(Position.QB, 1);
        starterSlots.put(Position.RB, 2);
        starterSlots.put(Position.WR, 3);
        starterSlots.put(Position.TE, 1);

        double[][] features = new double[n][FEATURES];
        for(int a = 0; a < n; a++){
            Player player = Player.getPlayerFromSIDV2(choiceSet.get(a));
            Position position = player.position;
            int held = roster.getOrDefault(position, 0);
            int need = Math.max(starterSlots.get(position) - held, 0);
            features[a][0] = -Math.log(1 + adpRank[a]);
            features[a][1] = -Math.log(1 + pointsRank[a]);
            features[a][2] = need;
            features[a][3] = held >= starterSlots.get(position) ? 1.0 : 0.0;
            features[a][4] = position.equals(Position.QB) ? managerQBEarliness : 0.0;
            features[a][5] = position.equals(Position.QB) ? 1.0 : 0.0;
            features[a][6] = position.equals(Position.RB) ? 1.0 : 0.0;
            features[a][7] = position.equals(Position.TE) ? 1.0 : 0.0;
            features[a][8] = position.equals(Position.QB)
                    ? runCount(recentPicks, Position.QB) : 0.0;
        }
        return features;
    }

    private static Integer[] rankOrder(List<String> ids, Map<String, Double> score, boolean ascending){
        Integer[] order = new Integer[ids.size()];
        for(int a = 0; a < order.length; a++){
            order[a] = a;
        }
        java.util.Arrays.sort(order, Comparator.comparingDouble(
                a -> (ascending ? 1 : -1) * score.getOrDefault(ids.get(a), ascending ? 999.0 : 0.0)));
        return order;
    }

    private static int[] invert(Integer[] order){
        int[] rank = new int[order.length];
        for(int r = 0; r < order.length; r++){
            rank[order[r]] = r;
        }
        return rank;
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Map<String, Double> earliness = qbEarliness(configuration, 2024);
        List<Observation> train = loadObservations(configuration, 2021, 2024, earliness);
        List<Observation> test = loadObservations(configuration, 2025, 2025, earliness);
        System.out.printf("train %d selections (2021-2024, rounds 1-%d), test %d (2025)%n%n",
                train.size(), GAME_ROUNDS, test.size());

        boolean[] marketOnly = new boolean[FEATURES];
        marketOnly[0] = true;
        boolean[] full = new boolean[FEATURES];
        java.util.Arrays.fill(full, true);
        SelectionModel market = fit(train, marketOnly);
        SelectionModel model = fit(train, full);

        System.out.println("fitted coefficients (full model):");
        String[] names = {"log ADP rank", "log points rank", "starter need", "saturated",
                "QB x earliness", "QB intercept", "RB intercept", "TE intercept",
                "QB run"};
        for(int f = 0; f < FEATURES; f++){
            System.out.printf("   %-16s %+7.3f%n", names[f], model.beta()[f]);
        }

        System.out.println("\ngate 1 - held-out 2025, per selection:");
        System.out.printf("   %-22s %10s %8s %8s%n", "MODEL", "log-loss", "top-1", "top-5");
        System.out.printf("   %-22s %10.3f %7.1f%% %7.1f%%%n", "market only",
                meanLogLoss(market, test), topK(market, test, 1) * 100, topK(market, test, 5) * 100);
        System.out.printf("   %-22s %10.3f %7.1f%% %7.1f%%%n", "full selection model",
                meanLogLoss(model, test), topK(model, test, 1) * 100, topK(model, test, 5) * 100);
    }

}
