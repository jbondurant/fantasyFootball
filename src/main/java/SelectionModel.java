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
 *   f9     the cliff: for the best remaining player at his position, the
 *          projected-points drop to the next one (capped, scaled) - Justin's
 *          tier observation ("the drop between many TEs was substantial").
 *          Rank features cannot see gap magnitudes; this one is the
 *          fall-off itself
 *   f10    value fall: how far past his ADP the player has slipped - the
 *          "he's a steal at this pick" pull that rank cannot see
 *   f11    first pick of a back-to-back turn pair (rounds 2+), interacted
 *          with ADP rank - Justin's keeper-cost swap: strategic managers
 *          take the WORSE of their two targets first so the better one
 *          lands in the later round and keeps cheaper
 *   f12    second pick of the pair, same interaction - the other half of
 *          the swap (ADP adherence should RISE here)
 *   f13    wait until the manager's next pick, interacted with ADP rank -
 *          long waits should loosen board discipline
 *   f14    flex need: RB/WR/TE with fixed starters full but FLEX slots open
 *   f15    QB depletion: for QB candidates, the share of teams already
 *          holding a QB - demand left in the room
 *   f16    isTE x the manager's TE-timing earliness (like f4 for QBs)
 *   f17    isRB x the manager's RB-timing earliness
 *   f18    stack: WR/TE on the same NFL team as a QB the manager holds
 *   f19    rookie in that season (from Sleeper's rookie_year metadata)
 *   f20    per-player ADP spread (FantasyFootballCalculator stdev, centered)
 *   f21    loyalty: the manager rostered this player in a previous season -
 *          the home-league "my guy" pull
 *   f22    keeper stash: lateness x young (first two seasons) - in a keeper
 *          league a late pick doubles as a cheap future keeper option
 *
 * Fitting is concave maximum likelihood; plain gradient ascent converges in
 * seconds on ~500 in-game selections.
 *
 *     ./gradlew run -Pmain=SelectionModel
 */
public class SelectionModel implements ChoiceModel {

    public static final int FEATURES = 23;
    public static final int GAME_ROUNDS = 9;
    static final int CHOICE_SET = 60;
    static final double ADP_LIMIT = 250.0;
    /** Selections that count as "recent" for the run feature. */
    public static final int RUN_WINDOW = 6;
    /** Point drop that counts as a full cliff for f9. */
    public static final double CLIFF_CAP = 100.0;
    /**
     * Production fits train on rounds 1-13 even though the simulated game
     * ends at round 9: deeper picks express the same ADP/value/need weighing
     * and adding them improved held-out survival calibration on BOTH judged
     * seasons (2024: 1.19% -> 1.04%, 2025: 1.86% -> 1.52%) at a 0.15-round
     * QB-timing cost that stays far inside gate 3's beats-the-constant bar
     * (2.08 vs 3.19). Chosen by FeatureLab; rounds 14+ stay out (DEF/K noise
     * and the round-11 point looked worse, so the window is not "everything").
     */
    public static final int TRAIN_ROUNDS = 13;

    /**
     * Everything about the moment of a selection that the candidate-level
     * features need. Season-level maps (teamOf, rookies, adpSpreadCentered)
     * may be empty when a feature is inactive - its column just reads zero.
     */
    public record Context(Map<Position, Integer> roster,
                          double qbEarliness,
                          List<Position> recentPicks,
                          int pickNumber,
                          boolean firstOfPair,
                          boolean secondOfPair,
                          double waitFraction,
                          double leagueQBShare,
                          double teEarliness,
                          double rbEarliness,
                          Set<String> stackTeams,
                          Map<String, String> teamOf,
                          Set<String> rookies,
                          Map<String, Double> adpSpreadCentered,
                          Set<String> formerPlayers,
                          Set<String> young){

        /** The pre-f10 world: roster, QB timing and the run window only. */
        public static Context simple(Map<Position, Integer> roster, double qbEarliness,
                                     List<Position> recentPicks){
            return new Context(roster, qbEarliness, recentPicks, 1, false, false, 0.0,
                    0.0, 0.0, 0.0, Set.of(), Map.of(), Set.of(), Map.of(), Set.of(), Set.of());
        }
    }

    /**
     * The feature set that won the leakage-safe 2024 chooser in
     * DraftSimulator.main; production fits (DraftPlanner, KeeperPlan) use
     * exactly this. Update it only when the chooser's verdict changes.
     * Current verdict: positional intercepts AND the QB-run feature in
     * (2024 calibration 1.19% with it versus 1.40% without, 400 trials per
     * cell). The fitted run coefficient is NEGATIVE (-0.57): a recent QB run
     * suppresses the next QB pick once ADP, need and saturation are held
     * fixed - the opposite of the herding story the raw counts suggest.
     * The cliff feature f9 is OUT: it fit to -0.12 (a full cliff moves
     * utility a tenth of what one ADP rank does) and the chooser scored
     * 1.25% with it versus 1.19% without. National ADP already prices the
     * tiers, so the local gap adds nothing about OPPONENT behavior - my own
     * decisions still feel cliffs fully, through the planner's point values
     * and the snipe decomposition's drop-if-gone.
     */
    public static boolean[] shippedFeatures(){
        // f0-f8 shipped; f9 rejected; f10-f20 are FeatureLab candidates and
        // stay out until a chooser verdict says otherwise.
        boolean[] active = new boolean[FEATURES];
        for(int f = 0; f <= 8; f++){
            active[f] = true;
        }
        return active;
    }

    /** One historical selection: the chosen index within its choice set. */
    public record Observation(double[][] features, int chosen) {}

    /**
     * THE shipped model, in one place: shipped features, training window
     * rounds 1-TRAIN_ROUNDS, seasons 2021-lastSeason. Every production fit
     * (planner, keeper tools, smoke gates) goes through here.
     */
    public static SelectionModel fitShipped(AAAConfiguration configuration, int lastSeason,
                                            Map<String, Double> qbEarliness){
        return fit(loadObservations(configuration, 2021, lastSeason, qbEarliness,
                Map.of(), Map.of(), false, TRAIN_ROUNDS), shippedFeatures());
    }

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

    @Override
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
        return positionEarliness(configuration, lastSeason, Position.QB);
    }

    /** The same construction for any position. */
    /**
     * The same, with recent seasons weighted more heavily. Justin's appetite
     * audit found the league's QB timing was a 2022-23 ERA (league mean first
     * QB round 6.1 -> 4.1 -> 3.7 -> 5.4 -> 5.6), not a trend, so pooling all
     * seasons equally overstates today's QB appetite. halfLife is in seasons:
     * a season h back gets weight 0.5^(age/h). M3 on the docket.
     */
    public static Map<String, Double> positionEarlinessWeighted(
            AAAConfiguration configuration, int lastSeason, Position position,
            double halfLife){
        Map<String, List<double[]>> observations = new HashMap<>();
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();
        double leagueTotal = 0;
        double leagueWeight = 0;
        for(int i = 0; i < drafts.size() && i < seasons.size(); i++){
            if(seasons.get(i) == null || Integer.parseInt(seasons.get(i)) > lastSeason){
                continue;
            }
            double age = lastSeason - Integer.parseInt(seasons.get(i));
            double weight = Math.pow(0.5, age / halfLife);
            for(Map.Entry<String, Integer> entry
                    : DraftSimulator.realFirstRound(drafts.get(i), position).entrySet()){
                observations.computeIfAbsent(entry.getKey(), u -> new ArrayList<>())
                        .add(new double[]{entry.getValue(), weight});
                leagueTotal += entry.getValue() * weight;
                leagueWeight += weight;
            }
        }
        double leagueMean = leagueWeight == 0 ? 7.0 : leagueTotal / leagueWeight;
        Map<String, Double> earliness = new HashMap<>();
        for(Map.Entry<String, List<double[]>> entry : observations.entrySet()){
            double total = 0;
            double weight = 0;
            for(double[] row : entry.getValue()){
                total += row[0] * row[1];
                weight += row[1];
            }
            double mean = weight == 0 ? leagueMean : total / weight;
            earliness.put(entry.getKey(),
                    (leagueMean - mean) * weight / (weight + 2.0));
        }
        return earliness;
    }

    public static Map<String, Double> positionEarliness(AAAConfiguration configuration,
                                                        int lastSeason, Position position){
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
                if(player == null || !player.position.equals(position)){
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
        return loadObservations(configuration, firstSeason, lastSeason, qbEarliness,
                Map.of(), Map.of());
    }

    /** The same, with TE/RB timing populated for the FeatureLab candidates. */
    public static List<Observation> loadObservations(AAAConfiguration configuration,
                                                     int firstSeason, int lastSeason,
                                                     Map<String, Double> qbEarliness,
                                                     Map<String, Double> teEarliness,
                                                     Map<String, Double> rbEarliness){
        return loadObservations(configuration, firstSeason, lastSeason, qbEarliness,
                teEarliness, rbEarliness, false);
    }

    /** leagueScoredValue swaps the value signal to what the draft room shows. */
    public static List<Observation> loadObservations(AAAConfiguration configuration,
                                                     int firstSeason, int lastSeason,
                                                     Map<String, Double> qbEarliness,
                                                     Map<String, Double> teEarliness,
                                                     Map<String, Double> rbEarliness,
                                                     boolean leagueScoredValue){
        return loadObservations(configuration, firstSeason, lastSeason, qbEarliness,
                teEarliness, rbEarliness, leagueScoredValue, GAME_ROUNDS);
    }

    /**
     * maxRound widens TRAINING beyond the nine-round game: deeper picks also
     * express how managers weigh ADP, value and need, and the simulation
     * window stays rounds 1-9 regardless. Skill positions only either way.
     */
    public static List<Observation> loadObservations(AAAConfiguration configuration,
                                                     int firstSeason, int lastSeason,
                                                     Map<String, Double> qbEarliness,
                                                     Map<String, Double> teEarliness,
                                                     Map<String, Double> rbEarliness,
                                                     boolean leagueScoredValue,
                                                     int maxRound){
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
            observations.addAll(seasonObservations(configuration, drafts.get(i), season,
                    qbEarliness, teEarliness, rbEarliness, leagueScoredValue, maxRound));
        }
        return observations;
    }

    private static List<Observation> seasonObservations(AAAConfiguration configuration,
                                                        JsonArray draft, String season,
                                                        Map<String, Double> qbEarliness,
                                                        Map<String, Double> teEarliness,
                                                        Map<String, Double> rbEarliness,
                                                        boolean leagueScoredValue,
                                                        int maxRound){
        Map<String, Double> adp = HistoricalProjections.adpBySleeperID(configuration, season);
        Map<String, Double> points = leagueScoredValue
                ? HistoricalProjections.leaguePointsBySleeperID(configuration, season)
                : HistoricalProjections.rawPointsBySleeperID(configuration, season);
        Map<String, String> teamOf = HistoricalProjections.teamBySleeperID(configuration, season);
        Set<String> rookies = HistoricalProjections.rookiesForSeason(configuration, season);
        Set<String> young = HistoricalProjections.youngForSeason(configuration, season, 2);
        Map<String, Double> spread = FFCalculatorSD.centeredSpreadBySleeperID(season);
        Map<String, Set<String>> formerPlayers = formerPlayersBefore(configuration, season);

        // Keepers are off the board and on their owner's roster from the start.
        Set<String> kept = new HashSet<>();
        Map<String, Map<Position, Integer>> rosters = new HashMap<>();
        Map<String, Set<String>> stackTeams = new HashMap<>();
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
                    if(player.position.equals(Position.QB) && teamOf.containsKey(sleeperID)){
                        stackTeams.computeIfAbsent(owner.getAsString(), u -> new HashSet<>())
                                .add(teamOf.get(sleeperID));
                    }
                }
            }
            else {
                picks.add(pick);
            }
        }
        picks.sort(Comparator.comparingInt(p -> p.get("pick_no").getAsInt()));

        Map<String, List<Integer>> livePickNumbers = new HashMap<>();
        Set<String> allManagers = new HashSet<>();
        for(JsonObject pick : picks){
            JsonElement by = pick.get("picked_by");
            if(by != null && !by.isJsonNull() && pick.get("round").getAsInt() <= GAME_ROUNDS){
                livePickNumbers.computeIfAbsent(by.getAsString(), u -> new ArrayList<>())
                        .add(pick.get("pick_no").getAsInt());
                allManagers.add(by.getAsString());
            }
        }
        double teams = Math.max(allManagers.size(), 1);

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
            if(pick.get("round").getAsInt() > maxRound){
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
                int pickNumber = pick.get("pick_no").getAsInt();
                int round = pick.get("round").getAsInt();
                List<Integer> mine = livePickNumbers.getOrDefault(manager, List.of());
                int slot = mine.indexOf(pickNumber);
                int next = slot >= 0 && slot + 1 < mine.size() ? mine.get(slot + 1) : Integer.MAX_VALUE;
                int previous = slot > 0 ? mine.get(slot - 1) : Integer.MIN_VALUE;
                boolean firstOfPair = next - pickNumber == 1 && round >= 2;
                boolean secondOfPair = pickNumber - previous == 1 && round >= 3;
                double waitFraction = next == Integer.MAX_VALUE
                        ? 1.0 : Math.min(next - pickNumber, 24) / 24.0;
                long qbHolders = rosters.values().stream()
                        .filter(counts -> counts.getOrDefault(Position.QB, 0) > 0).count();
                observations.add(new Observation(
                        features(choiceSet, adp, points, new Context(
                                rosters.computeIfAbsent(manager, u -> new EnumMap<>(Position.class)),
                                qbEarliness.getOrDefault(manager, 0.0), recentPicks,
                                pickNumber, firstOfPair, secondOfPair, waitFraction,
                                qbHolders / teams,
                                teEarliness.getOrDefault(manager, 0.0),
                                rbEarliness.getOrDefault(manager, 0.0),
                                stackTeams.getOrDefault(manager, Set.of()),
                                teamOf, rookies, spread,
                                formerPlayers.getOrDefault(manager, Set.of()), young)),
                        chosen));
            }
            board.remove(chosenID);
            if(player != null){
                rosters.computeIfAbsent(manager, u -> new EnumMap<>(Position.class))
                        .merge(player.position, 1, Integer::sum);
                if(StartingLineup.isSkillPosition(player.position)){
                    recentPicks.add(player.position);
                }
                if(player.position.equals(Position.QB) && teamOf.containsKey(chosenID)){
                    stackTeams.computeIfAbsent(manager, u -> new HashSet<>())
                            .add(teamOf.get(chosenID));
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

    /** Convenience for callers and tests that predate the Context. */
    public static double[][] features(List<String> choiceSet,
                                      Map<String, Double> adp,
                                      Map<String, Double> points,
                                      Map<Position, Integer> roster,
                                      double managerQBEarliness,
                                      List<Position> recentPicks){
        return features(choiceSet, adp, points,
                Context.simple(roster, managerQBEarliness, recentPicks));
    }

    /** Everyone a manager drafted or kept in seasons before this one. */
    static Map<String, Set<String>> formerPlayersBefore(AAAConfiguration configuration,
                                                        String season){
        Map<String, Set<String>> former = new HashMap<>();
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();
        int cutoff = Integer.parseInt(season);
        for(int i = 0; i < drafts.size() && i < seasons.size(); i++){
            if(seasons.get(i) == null || Integer.parseInt(seasons.get(i)) >= cutoff){
                continue;
            }
            for(JsonElement pickElement : drafts.get(i)){
                JsonObject pick = pickElement.getAsJsonObject();
                JsonElement by = pick.get("picked_by");
                if(by != null && !by.isJsonNull()){
                    former.computeIfAbsent(by.getAsString(), u -> new HashSet<>())
                            .add(pick.get("player_id").getAsString());
                }
            }
        }
        return former;
    }

    /** The feature matrix for one choice set - also used by the simulator. */
    public static double[][] features(List<String> choiceSet,
                                      Map<String, Double> adp,
                                      Map<String, Double> points,
                                      Context context){
        Map<Position, Integer> roster = context.roster();
        double managerQBEarliness = context.qbEarliness();
        List<Position> recentPicks = context.recentPicks();
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

            double fall = context.pickNumber() - adp.getOrDefault(choiceSet.get(a), 999.0);
            features[a][10] = Math.min(Math.max(fall, 0), 48) / 24.0;
            features[a][11] = context.firstOfPair() ? features[a][0] : 0.0;
            features[a][12] = context.secondOfPair() ? features[a][0] : 0.0;
            features[a][13] = context.waitFraction() * features[a][0];
            int flexRemaining = Math.max(0, StartingLineup.FLEX_SLOTS
                    - Math.max(0, roster.getOrDefault(Position.RB, 0) - 2)
                    - Math.max(0, roster.getOrDefault(Position.WR, 0) - 3)
                    - Math.max(0, roster.getOrDefault(Position.TE, 0) - 1));
            features[a][14] = !position.equals(Position.QB) && need == 0
                    ? flexRemaining / (double) StartingLineup.FLEX_SLOTS : 0.0;
            features[a][15] = position.equals(Position.QB) ? context.leagueQBShare() : 0.0;
            features[a][16] = position.equals(Position.TE) ? context.teEarliness() : 0.0;
            features[a][17] = position.equals(Position.RB) ? context.rbEarliness() : 0.0;
            String team = context.teamOf().get(choiceSet.get(a));
            features[a][18] = (position.equals(Position.WR) || position.equals(Position.TE))
                    && team != null && context.stackTeams().contains(team) ? 1.0 : 0.0;
            features[a][19] = context.rookies().contains(choiceSet.get(a)) ? 1.0 : 0.0;
            features[a][20] = context.adpSpreadCentered().getOrDefault(choiceSet.get(a), 0.0) / 10.0;
            features[a][21] = context.formerPlayers().contains(choiceSet.get(a)) ? 1.0 : 0.0;
            features[a][22] = context.young().contains(choiceSet.get(a))
                    ? context.pickNumber() / 108.0 : 0.0;
        }
        // The cliff: only the best remaining player at each position carries
        // it - he is the one a fall-off makes urgent.
        Map<Position, Integer> bestAt = new EnumMap<>(Position.class);
        Map<Position, Double> bestPoints = new EnumMap<>(Position.class);
        Map<Position, Double> secondPoints = new EnumMap<>(Position.class);
        for(int a = 0; a < n; a++){
            Position position = Player.getPlayerFromSIDV2(choiceSet.get(a)).position;
            double value = points.getOrDefault(choiceSet.get(a), 0.0);
            if(value > bestPoints.getOrDefault(position, Double.NEGATIVE_INFINITY)){
                secondPoints.put(position, bestPoints.getOrDefault(position, Double.NEGATIVE_INFINITY));
                bestPoints.put(position, value);
                bestAt.put(position, a);
            }
            else if(value > secondPoints.getOrDefault(position, Double.NEGATIVE_INFINITY)){
                secondPoints.put(position, value);
            }
        }
        for(Map.Entry<Position, Integer> entry : bestAt.entrySet()){
            double second = secondPoints.getOrDefault(entry.getKey(), Double.NEGATIVE_INFINITY);
            double drop = second == Double.NEGATIVE_INFINITY
                    ? CLIFF_CAP : bestPoints.get(entry.getKey()) - second;
            features[entry.getValue()][9] = Math.min(Math.max(drop, 0), CLIFF_CAP) / CLIFF_CAP;
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
        boolean[] shipped = shippedFeatures();
        SelectionModel market = fit(train, marketOnly);
        SelectionModel model = fit(train, shipped);

        System.out.println("fitted coefficients (shipped model; candidates live in FeatureLab):");
        String[] names = {"log ADP rank", "log points rank", "starter need", "saturated",
                "QB x earliness", "QB intercept", "RB intercept", "TE intercept",
                "QB run", "cliff", "value fall", "pair 1st x adp", "pair 2nd x adp",
                "wait x adp", "flex need", "QB depletion", "TE x earliness",
                "RB x earliness", "QB stack", "rookie", "ADP spread", "loyalty", "keeper stash"};
        for(int f = 0; f < FEATURES; f++){
            if(shipped[f]){
                System.out.printf("   %-16s %+7.3f%n", names[f], model.beta()[f]);
            }
        }

        System.out.println("\ngate 1 - held-out 2025, per selection:");
        System.out.printf("   %-22s %10s %8s %8s%n", "MODEL", "log-loss", "top-1", "top-5");
        System.out.printf("   %-22s %10.3f %7.1f%% %7.1f%%%n", "market only",
                meanLogLoss(market, test), topK(market, test, 1) * 100, topK(market, test, 5) * 100);
        System.out.printf("   %-22s %10.3f %7.1f%% %7.1f%%%n", "shipped selection model",
                meanLogLoss(model, test), topK(model, test, 1) * 100, topK(model, test, 5) * 100);
    }

}
