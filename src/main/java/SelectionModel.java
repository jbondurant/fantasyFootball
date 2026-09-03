import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

    public static final int FEATURES = 30;

    /**
     * The last man at each position anybody starts: slots x twelve teams.
     *
     * QB12, TE12, RB24, WR36 - textbook replacement level, and the baseline a
     * surplus has to be measured against. Computed from the AVAILABLE BOARD.
     *
     * Not from the choice set, which is the top sixty by ADP and holds three or
     * four tight ends - its median is about TE2, which is what f9 already says.
     * And not from the full points map either: that still contains the
     * twenty-four kept men, SEVEN of them tight ends this year, so the TE12 it
     * finds is a man nobody can draft and the surplus it reports is far too
     * small at exactly the position where scarcity is doing the most work.
     * Justin caught both, in that order.
     */
    /**
     * Each position's OWN scale: the spread from its best man to replacement.
     *
     * CLIFF_CAP is a flat hundred points, and the positions do not live on the
     * same scale - QB1 is projected for 415 this year and DEF1 for 106.
     * Measured by FeatureScales on the 2026 board, that flat cap makes f9 read
     * 0.355 at QB and 0.020 at DEF, where it can never move a tree split, and
     * makes f29 SATURATE at 1.000 for both RB and WR, where every elite man
     * reads identically and the feature cannot tell RB1 from RB5. That is why
     * scarcity did nothing for the two positions Justin drafts most.
     *
     * Dividing by the position's own spread instead makes the two features mean
     * the same thing everywhere: 1.0 is "the whole distance from replacement to
     * the best man at this position", whatever that distance is worth in points.
     */
    static Map<Position, Double> positionScale(List<String> board,
            Map<String, Double> points, Map<Position, Integer> starterSlots){
        Map<Position, Double> replacement =
                replacementLevel(board, points, starterSlots);
        Map<Position, Double> best = new EnumMap<>(Position.class);
        for(String id : board){
            Player player = Player.getPlayerFromSIDV2(id);
            double value = points.getOrDefault(id, 0.0);
            if(player != null && value > best.getOrDefault(player.position, 0.0)){
                best.put(player.position, value);
            }
        }
        Map<Position, Double> scale = new EnumMap<>(Position.class);
        for(Map.Entry<Position, Double> entry : replacement.entrySet()){
            double spread = best.getOrDefault(entry.getKey(), 0.0) - entry.getValue();
            scale.put(entry.getKey(), Math.max(1.0, spread));
        }
        return scale;
    }

    static Map<Position, Double> replacementLevel(List<String> board,
            Map<String, Double> points, Map<Position, Integer> starterSlots){
        Map<Position, List<Double>> byPosition = new EnumMap<>(Position.class);
        for(String id : board){
            Player player = Player.getPlayerFromSIDV2(id);
            double value = points.getOrDefault(id, 0.0);
            if(player != null && value > 0){
                byPosition.computeIfAbsent(player.position, u -> new ArrayList<>())
                        .add(value);
            }
        }
        Map<Position, Double> out = new EnumMap<>(Position.class);
        for(Map.Entry<Position, List<Double>> entry : byPosition.entrySet()){
            List<Double> values = entry.getValue();
            values.sort(Comparator.reverseOrder());
            int slots = starterSlots.getOrDefault(entry.getKey(), 1);
            int index = Math.min(values.size() - 1, Math.max(0, slots * 12 - 1));
            out.put(entry.getKey(), values.get(index));
        }
        return out;
    }
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
     * Rounds of history the choice model learns from.
     *
     * Thirteen for the nine-round game, tuned and left alone. But this league
     * drafts 41 of its 58 defences in rounds 14-16, so a model trained to round
     * 13 sees only the seventeen EARLIEST defences ever taken - and concludes
     * they go early, which is the opposite of the truth. When the schedule runs
     * to sixteen the training window has to as well, or the model is taught a
     * fact that is backwards.
     */
    public static int trainRounds(){
        return DraftPlanner.scheduleRounds() > GAME_ROUNDS ? 16 : TRAIN_ROUNDS;
    }

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
                Map.of(), Map.of(), false, trainRounds()), shippedFeatures());
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
        // THE CURRENT SEASON JOINS THE TRAINING SET ONCE ITS DRAFT IS DONE AND ITS
        // FEED FROZEN. The chain above is previous leagues only, so 2026 would
        // have waited for the 2027 league to exist. With lastSeason at or past
        // the current year and sleeperProjectionsFinal<year>.txt in place, the
        // league's own completed draft is read like any other season. Under the
        // unit suite's pre-draft fixture that draft holds keepers only, which
        // contribute no selections - the suite's model is unchanged.
        String current = configuration.getSeason();
        int currentYear = Integer.parseInt(current);
        if(currentYear >= firstSeason && currentYear <= lastSeason
                && HistoricalProjections.frozen(current)){
            observations.addAll(seasonObservations(configuration,
                    JsonParser.parseString(configuration.getTodaysDraftPicks()).getAsJsonArray(),
                    current, qbEarliness, teEarliness, rbEarliness, leagueScoredValue, maxRound));
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

        // Defences join the TRAINING board only when the schedule runs past the
        // nine-round game, so Model A's fit is untouched. Without them the
        // choice model has never seen a defence picked and cannot know when
        // they go - which is why the sixteen-round search believed one was
        // about to be taken in round 7 and reached for it. This league's own
        // history is unambiguous: across five drafts, zero defences before
        // round 10 and only 16% before round 13, with the mass in 14-16. That
        // is a fact the model should read off the drafts, not be told.
        boolean withDefences = DraftPlanner.scheduleRounds() > GAME_ROUNDS;
        List<String> board = new ArrayList<>();
        // REPLACEMENT LEVEL IS FIXED AT THE START OF THE DRAFT.
        //
        // Passing the CURRENT board recomputes it every pick, so it drifts down
        // as the pool empties and everyone's surplus inflates late - measured,
        // that made TE fidelity worse than having no feature at all (13.6
        // against 12.4). Real VORP is a property of the pool you started with.
        List<String> startingBoard;
        for(Map.Entry<String, Double> entry : adp.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null || !(StartingLineup.isSkillPosition(player.position)
                    || (withDefences && player.position == Position.DEF))){
                continue;
            }
            if(entry.getValue() > ADP_LIMIT || kept.contains(entry.getKey())){
                continue;
            }
            board.add(entry.getKey());
        }
        // Snapshot before a single pick is made. This list already excludes the
        // kept men and anyone past ADP_LIMIT, so it is the draftable pool.
        startingBoard = new ArrayList<>(board);

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
                if(player != null && (StartingLineup.isSkillPosition(player.position)
                        || (withDefences && player.position == Position.DEF))){
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
                        featuresWithBoard(startingBoard, choiceSet, adp, points, new Context(
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
                if(StartingLineup.isSkillPosition(player.position)
                        || (withDefences && player.position == Position.DEF)){
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
    /** Same as features(choiceSet, adp, points, context, board), board first. */
    static double[][] featuresWithBoard(List<String> board, List<String> choiceSet,
                                        Map<String, Double> adp,
                                        Map<String, Double> points, Context context){
        return features(choiceSet, adp, points, context, board);
    }

    public static double[][] features(List<String> choiceSet,
                                      Map<String, Double> adp,
                                      Map<String, Double> points,
                                      Context context){
        // No board given, so replacement level cannot be computed over the
        // population it has to be computed over. f29 stays ZERO rather than
        // being computed from the wrong one - which is the mistake that made it
        // useless in the first place.
        return features(choiceSet, adp, points, context, null);
    }

    /**
     * @param board every player still AVAILABLE, keepers and drafted men gone.
     *              Replacement level is computed from this and nothing else:
     *              the choice set is the top sixty by ADP and cannot reach TE12,
     *              and the full points map still contains the twenty-four kept
     *              men, seven of whom are tight ends this year. Both give a
     *              baseline that is not replacement.
     */
    public static double[][] features(List<String> choiceSet,
                                      Map<String, Double> adp,
                                      Map<String, Double> points,
                                      Context context,
                                      List<String> board){
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
        // The league starts one defence. Without this entry the feature builder
        // throws the moment a defence reaches it, which is what happened when
        // the sixteen-round board first included them. Model A's board holds no
        // defences, so this line is inert for it.
        starterSlots.put(Position.DEF, 1);

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
            // NO PER-POSITION RUN TERMS. f8 is QB-only and stays that way: I
            // added RB, TE and DEF run counts on the theory that f8's own note
            // ("a shared coefficient cancels itself") argued for one per
            // position rather than none, and measured them on held-out seasons.
            // They do nothing - QB 15.5 to 15.8, TE 11.7 to 12.1, WR 5.4 to 6.0,
            // all inside the noise - and three more parameters fitted to 435
            // observations is a cost, as the nine-round gate showed when the
            // depth features were left switched on there.


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
            // f23  DEFENCE INTERCEPT. Every other position has one - QB, RB
            //      and TE at f5-f7, with WR as the baseline - and until
            //      2026-09-01 a defence was scored as though it were a
            //      receiver. The room could not learn what it plainly does:
            //      DefenceReality measures 58 defences over five real drafts,
            //      NONE before round 10 and a median of round 15, against a
            //      simulated room taking 19% of them in rounds 1-9 and one as
            //      early as round 4. The model was reaching for a defence
            //      because it believed the room would take one.
            //
            //      This is the general form of the fault rather than a rule
            //      about defences: the feature set covered four positions out
            //      of five, so the fifth inherited the baseline's behaviour.
            features[a][23] = position.equals(Position.DEF) ? 1.0 : 0.0;
            // f24  HOW DEEP THE DRAFT IS, exactly.
            //
            //      The feature set had NO measure of depth - only proxies - so
            //      a depth-2 tree could not express "this position AND late",
            //      which is the shape of every positional timing rule a room
            //      follows. My first version summed the manager's roster as a
            //      stand-in; Context has carried the exact pick number all
            //      along and I did not look.
            //
            // f25-f28  EACH POSITION'S OWN TIMING CURVE: the intercept times
            //      the depth, for QB, RB, TE and DEF, with WR the baseline -
            //      the same family as the f5-f7 intercepts and the f4/f16/f17
            //      earliness interactions. Handing the tree the product
            //      directly rather than asking it to discover the split is what
            //      the existing interaction features already do.
            //
            //      This is general on purpose. Defences are where it was
            //      diagnosable - none taken before round 10 in five real drafts
            //      against 19% simulated inside round 9 - but a room has a
            //      timing curve for every position, and four of the five had no
            //      way to express one.
            //      INERT IN THE NINE-ROUND GAME. These exist to model what a
            //      room does LATE, and the nine-round schedule has no late -
            //      it stops before a single defence has ever been taken in
            //      this league. Left switched on there they are four extra
            //      parameters and one dead column fitted to 435 observations,
            //      and they cost real accuracy: BoostLab's 2024 calibration
            //      went 0.60% to 1.00% and the boosted model stopped clearing
            //      its gate over the linear one. Off, that configuration is
            //      byte-identical to before this change; on, the sixteen-round
            //      one keeps the whole gain.
            if(DraftPlanner.scheduleRounds() > GAME_ROUNDS
                    && !Boolean.getBoolean("noDepth")){
                double depth = Math.min(1.0, context.pickNumber() / 192.0);
                features[a][24] = depth;
                features[a][25] = features[a][5] * depth;
                features[a][26] = features[a][6] * depth;
                features[a][27] = features[a][7] * depth;
                features[a][28] = features[a][23] * depth;
            }
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
        // THE ABSOLUTE CAP IS FINE, AND I CHECKED THE WRONG THING FIRST.
        //
        // FeatureScales shows f9 reading 0.355 at quarterback and 0.020 at
        // defence on the 2026 board, and f29 SATURATING at 1.000 for both RB
        // and WR - so the flat hundred-point CLIFF_CAP looked like the same
        // absolute-versus-relative fault Justin named on the market drift.
        //
        // It is not, and the model class is the reason. BOOSTED TREES ARE
        // INVARIANT TO MONOTONE RESCALING: a tree splits on thresholds, so
        // dividing by a hundred or by the position's own spread offers the same
        // splits. Only ORDERING matters, and a cap breaks ordering solely among
        // the men above it. Measured both ways: dividing by each position's
        // spread made things WORSE (TE 12.4 -> 14.5 held-out), and removing the
        // cap entirely changed nothing at all, to the last digit.
        //
        // Worth writing down because the hunt for oversights has to distinguish
        // the two kinds. A POPULATION error changes which men are compared and
        // therefore the ordering - those have all been real here. A SCALE error
        // does not reach a tree at all.
        for(Map.Entry<Position, Integer> entry : bestAt.entrySet()){
            double second = secondPoints.getOrDefault(entry.getKey(), Double.NEGATIVE_INFINITY);
            double drop = second == Double.NEGATIVE_INFINITY
                    ? CLIFF_CAP : bestPoints.get(entry.getKey()) - second;
            features[entry.getValue()][9] = Math.min(Math.max(drop, 0), CLIFF_CAP) / CLIFF_CAP;
        }

        // f29  SCARCITY: surplus over REPLACEMENT at his own position.
        //
        //      f9 is the cliff from the best remaining to the second best - one
        //      step. That is not why an elite tight end goes in round 3. He goes
        //      because the gap to REPLACEMENT is enormous while the gap to TE2
        //      may be nothing, and the rank features are cross-positional, so a
        //      143-point tight end always looks worse than a 300-point back
        //      however scarce he is.
        //
        //      MY FIRST VERSION OF THIS DID NOT MEASURE SCARCITY AT ALL, and it
        //      is worth saying why because the null result looked like evidence.
        //      I used the median of the position WITHIN THE CHOICE SET - and the
        //      choice set is the top sixty by ADP, which holds three or four
        //      tight ends. Its median is about TE2, so the feature was computing
        //      "how much better than TE2", which is nearly what f9 already says.
        //      Redundant by construction. Justin: "isn't it a bad sign that the
        //      scarcity isn't working when it should... it points at a logical
        //      failure". It did.
        //
        //      Replacement is the last man at that position anybody starts:
        //      slots x twelve teams, so QB12, TE12, RB24, WR36. Computed from
        //      the full points map rather than the choice set, because that is
        //      the whole point - the baseline must not move with what is left on
        //      the board sixty deep.
        Map<Position, Double> replacement = board == null ? Map.of()
                : replacementLevel(board, points, starterSlots);
        for(int a = 0; a < n; a++){
            if(!Boolean.getBoolean("scarcity")
                    || DraftPlanner.scheduleRounds() <= GAME_ROUNDS){
                break;
            }
            Player who = Player.getPlayerFromSIDV2(choiceSet.get(a));
            Double floor = who == null ? null : replacement.get(who.position);
            if(floor == null){
                continue;
            }
            double surplus = points.getOrDefault(choiceSet.get(a), 0.0) - floor;
            // ABSOLUTE UNITS, BUT NOT CAPPED AT ONE.
            //
            // Points are fungible - a 25-point cliff at quarterback really is
            // worth more in a lineup than a 5-point cliff at tight end, which
            // is why dividing by each position's own spread made things WORSE
            // (TE 12.4 -> 14.5 held-out) and why the absolute unit stays.
            //
            // The saturation is a separate fault and a real one: capped at
            // CLIFF_CAP, RB and WR BOTH read exactly 1.000 for every elite man,
            // so the feature cannot tell RB1 from RB5 at the two positions
            // Justin drafts most. Scaled but uncapped, it keeps the units and
            // keeps the ordering.
            features[a][29] = Math.min(Math.max(surplus, 0), CLIFF_CAP) / CLIFF_CAP;
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
