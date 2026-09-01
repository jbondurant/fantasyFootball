import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Full drafts simulated from the fitted selection model: every pick sampled
 * from P(manager takes player | board, roster), walked through the league's
 * real serpentine slot order with keeper-occupied slots selecting nobody.
 *
 * This is gates 2 and 3 for the step-B model, on held-out 2025:
 *   gate 2   per-player availability calibration on the same metric the
 *            incumbent gaussian is scored on, restricted to the nine-round
 *            game - plus the same check at my own nine actual pick slots
 *   gate 3   per-manager QB timing: does simulated tommyrads wait on a QB
 *            the way real tommyrads does? The gaussian has no managers, so
 *            any skill here is information it cannot express.
 *
 *     ./gradlew run -Pmain=DraftSimulator [-Ptrials=600]
 */
public class DraftSimulator {

    public static final long SEED = 20260825L;
    /** Censoring value when a position is never taken inside the game. */
    public static final int NEVER_ROUND = SelectionModel.GAME_ROUNDS + 1;

    /** One slot in the real pick order. Keeper slots select nobody. */
    public record Slot(int pickNumber, int round, String manager, boolean keeperSlot) {}

    /**
     * Season-level inputs for the FeatureLab candidate features. Everything
     * defaults empty: an inactive feature's column reads zero either way.
     */
    public record Extras(Map<String, Double> teEarliness, Map<String, Double> rbEarliness,
                         Map<String, String> teamOf, Set<String> rookies,
                         Map<String, Double> adpSpreadCentered,
                         Map<String, Set<String>> keeperStackTeams,
                         Map<String, Set<String>> formerPlayersByManager,
                         Set<String> young,
                         Map<String, Double> pointsOverride){
        public static Extras none(){
            return new Extras(Map.of(), Map.of(), Map.of(), Set.of(), Map.of(), Map.of(),
                    Map.of(), Set.of(), Map.of());
        }
    }

    private final List<Slot> schedule;
    private final Map<Integer, Slot> slotByPickNumber = new HashMap<>();
    private final List<String> initialBoard;   // ADP order, best first
    private final Map<String, Double> adp;
    private final Map<String, Double> points;
    private final Map<String, Map<Position, Integer>> initialRosters;
    private final ChoiceModel model;
    /** Per-manager brains override the shared model - sniper mixtures with
     *  manager-specific reach rates, autodraft drones, mismatch worlds. */
    private final Map<String, ChoiceModel> managerModels = new HashMap<>();
    private final Map<String, Double> qbEarliness;
    private final Extras extras;
    /** pickNumber -> {firstOfPair, secondOfPair, waitFraction}, from the schedule. */
    private final Map<Integer, double[]> turnShape = new HashMap<>();
    private final double teams;

    public DraftSimulator(List<Slot> schedule, List<String> board,
                          Map<String, Double> adp, Map<String, Double> points,
                          Map<String, Map<Position, Integer>> keeperRosters,
                          ChoiceModel model, Map<String, Double> qbEarliness){
        this(schedule, board, adp, points, keeperRosters, model, qbEarliness, Extras.none());
    }

    public DraftSimulator(List<Slot> schedule, List<String> board,
                          Map<String, Double> adp, Map<String, Double> points,
                          Map<String, Map<Position, Integer>> keeperRosters,
                          ChoiceModel model, Map<String, Double> qbEarliness,
                          Extras extras){
        this.schedule = new ArrayList<>(schedule);
        this.schedule.sort(Comparator.comparingInt(Slot::pickNumber));
        for(Slot slot : this.schedule){
            slotByPickNumber.put(slot.pickNumber(), slot);
        }
        // THE LEAGUE'S DISAGREEMENT WITH THE MARKET IS IN THE MODEL, AS
        // PREFERENCES RATHER THAN AS AN OFFSET TO ADP.
        //
        // Justin: "my league historically disagrees with that market and that
        // should be part of the model." It does disagree, and MarketDrift now
        // measures it properly - keeper-corrected and as a ratio: RB 0.97,
        // WR 0.89, TE 1.15, QB 1.22, DEF 1.16. Backs and receivers follow the
        // national board; this room waits on the other three.
        //
        // I tried correcting ADP by exactly that, TWICE. The first attempt used
        // a broken measurement - absolute picks against a baseline that still
        // counted the twenty-four kept men, which reported the whole league
        // reaching by up to nineteen picks when it was two dozen absent players.
        // Justin caught both faults. Corrected and re-run, the offset STILL
        // loses: held-out 2025 goes 0.70% to 1.28%, at his own slots 0.45% to
        // 0.60%, QB-timing MAE 1.95 to 2.47.
        //
        // The reason is double-counting. f5-f7's own comment says the league
        // bias won its contest "expressed as preferences rather than pick
        // offsets" - the positional intercepts, and now their depth
        // interactions, already absorb a position going later here than the
        // market says. Shifting the ruler underneath them corrects twice.
        //
        // So the disagreement lives in f23 and f25-f28, which is where it does
        // work, and MarketDrift stays as the measurement that shows how big it
        // is. -PnoDrift is honoured by MarketDrift for anyone re-running this.
        this.initialBoard = new ArrayList<>(board);
        this.initialBoard.sort(Comparator.comparingDouble(id -> adp.getOrDefault(id, 999.0)));
        this.adp = adp;
        this.points = points;
        this.initialRosters = keeperRosters;
        this.model = model;
        this.qbEarliness = qbEarliness;
        this.extras = extras;

        Map<String, List<Integer>> livePicks = new HashMap<>();
        for(Slot slot : this.schedule){
            if(!slot.keeperSlot() && !slot.manager().isEmpty()){
                livePicks.computeIfAbsent(slot.manager(), u -> new ArrayList<>())
                        .add(slot.pickNumber());
            }
        }
        this.teams = Math.max(livePicks.size(), 1);
        for(List<Integer> mine : livePicks.values()){
            for(int i = 0; i < mine.size(); i++){
                int pickNumber = mine.get(i);
                int round = slotByPickNumber.get(pickNumber).round();
                int next = i + 1 < mine.size() ? mine.get(i + 1) : Integer.MAX_VALUE;
                int previous = i > 0 ? mine.get(i - 1) : Integer.MIN_VALUE;
                turnShape.put(pickNumber, new double[]{
                        next - pickNumber == 1 && round >= 2 ? 1 : 0,
                        pickNumber - previous == 1 && round >= 3 ? 1 : 0,
                        next == Integer.MAX_VALUE ? 1.0 : Math.min(next - pickNumber, 24) / 24.0});
            }
        }
    }

    /** A held-out season's setup: its real slots, keepers and market. */
    public static DraftSimulator forSeason(DraftBacktest.Season season, ChoiceModel model,
                                           Map<String, Double> qbEarliness){
        return forSeason(season, model, qbEarliness, Extras.none());
    }

    /** Season-level extras from the frozen archives, for the lab features. */
    /**
     * NO POSITION GOES EARLIER THAN THIS LEAGUE HAS EVER TAKEN IT.
     *
     * One rule, derived from the league's own five drafts, applied to every
     * position. For quarterbacks, backs, receivers and tight ends the earliest
     * observed round is 1, so it does nothing at all. For defences it is round
     * 10, over 58 observations with none earlier - and that is the whole of its
     * effect.
     *
     * It exists because the feature work could not close the gap on its own.
     * DefenceReality measured the simulated room taking 19% of its defences
     * inside round 9 against a real 0%; the DEF intercept and the depth
     * interactions took that to 10%, all of it in rounds 8-9, with the whole
     * distribution still sitting about two rounds early. The 2026 market is the
     * reason it will not close further - the earliest defence ADP is 98, which
     * is round 9, so the national board keeps offering one a round before this
     * league would ever take it.
     *
     * That is a fact about the room, not a preference of Justin's, and the room
     * model exists to reproduce the room. -PnoFloor=true removes it.
     */
    static Map<Position, Integer> floors;

    static synchronized Map<Position, Integer> floors(){
        if(floors != null){
            return floors;
        }
        Map<Position, Integer> earliest = new EnumMap<>(Position.class);
        if(Boolean.getBoolean("noFloor")){
            floors = earliest;
            return floors;
        }
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        for(String season : configuration.getPreviousSeasons()){
            DraftBacktest.Season past;
            try {
                past = new DraftBacktest.Season(configuration, season);
            }
            catch(RuntimeException unavailable){
                continue;
            }
            for(com.google.gson.JsonElement element : past.picks){
                com.google.gson.JsonObject pick = element.getAsJsonObject();
                if(!pick.has("player_id") || pick.get("player_id").isJsonNull()
                        || !pick.has("round") || pick.get("round").isJsonNull()){
                    continue;
                }
                // A KEEPER'S ROUND IS NOT EVIDENCE ABOUT WHEN A ROOM DRAFTS.
                // The commissioner assigned it by the keeper rules; nobody chose
                // that player there over the rest of the board. Trey McBride is
                // kept at round 3 this year, so counting keepers would put the
                // tight-end floor at 3 on a fact about a rule rather than about
                // the room. It happens not to bind - tight ends really do go in
                // round 1 - but the population was wrong.
                com.google.gson.JsonElement keeper = pick.get("is_keeper");
                if(keeper != null && !keeper.isJsonNull() && keeper.getAsBoolean()){
                    continue;
                }
                Player player = Player.getPlayerFromSIDV2(
                        pick.get("player_id").getAsString());
                if(player != null){
                    earliest.merge(player.position, pick.get("round").getAsInt(),
                            Math::min);
                }
            }
        }
        floors = earliest;
        return floors;
    }

    /** Drop candidates whose position has never gone this early in this league. */
    static List<String> notBeforeThisLeagueEverHas(List<String> choiceSet, int round){
        Map<Position, Integer> earliest = floors();
        if(earliest.isEmpty()){
            return choiceSet;
        }
        List<String> allowed = new ArrayList<>();
        for(String id : choiceSet){
            Player player = Player.getPlayerFromSIDV2(id);
            Integer floor = player == null ? null : earliest.get(player.position);
            if(floor == null || round >= floor){
                allowed.add(id);
            }
        }
        // Never hand the model an empty choice set - if the floors would empty
        // it, the floors are wrong about this board and the board wins.
        return allowed.isEmpty() ? choiceSet : allowed;
    }

    public static Extras extrasFor(AAAConfiguration configuration, String season,
                                   int earlinessCutoff){
        return new Extras(
                SelectionModel.positionEarliness(configuration, earlinessCutoff, Position.TE),
                SelectionModel.positionEarliness(configuration, earlinessCutoff, Position.RB),
                HistoricalProjections.teamBySleeperID(configuration, season),
                HistoricalProjections.rookiesForSeason(configuration, season),
                FFCalculatorSD.centeredSpreadBySleeperID(season),
                Map.of(),
                SelectionModel.formerPlayersBefore(configuration, season),
                HistoricalProjections.youngForSeason(configuration, season, 2),
                Map.of());
    }

    /**
     * Season-level extras for the CURRENT season, from today's feeds - the
     * production twin of extrasFor, so the shipped model sees the same
     * feature columns in 2026 that it trained on historically.
     */
    public static Extras currentSeasonExtras(AAAConfiguration configuration){
        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        return new Extras(
                SelectionModel.positionEarliness(configuration, lastCompleted, Position.TE),
                SelectionModel.positionEarliness(configuration, lastCompleted, Position.RB),
                SleeperProjections.teamBySleeperID(),
                SleeperProjections.youngPlayers(0),
                FFCalculatorSD.centeredSpreadBySleeperID(configuration.getSeason()),
                Map.of(),
                SelectionModel.formerPlayersBefore(configuration, configuration.getSeason()),
                SleeperProjections.youngPlayers(2),
                Map.of());
    }

    /** The same, with the extras' kept-QB stack teams derived from the picks. */
    public static DraftSimulator forSeason(DraftBacktest.Season season, ChoiceModel model,
                                           Map<String, Double> qbEarliness, Extras extras){
        List<Slot> schedule = new ArrayList<>();
        Map<String, Map<Position, Integer>> rosters = new HashMap<>();
        for(JsonElement pickElement : season.picks){
            JsonObject pick = pickElement.getAsJsonObject();
            JsonElement isKeeperElement = pick.get("is_keeper");
            boolean isKeeper = isKeeperElement != null && !isKeeperElement.isJsonNull()
                    && isKeeperElement.getAsBoolean();
            JsonElement pickedBy = pick.get("picked_by");
            String manager = pickedBy == null || pickedBy.isJsonNull() ? "" : pickedBy.getAsString();
            if(isKeeper && !manager.isEmpty()){
                Player player = Player.getPlayerFromSIDV2(pick.get("player_id").getAsString());
                if(player != null){
                    rosters.computeIfAbsent(manager, u -> new EnumMap<>(Position.class))
                            .merge(player.position, 1, Integer::sum);
                }
            }
            int round = pick.get("round").getAsInt();
            // HISTORICAL SCHEDULES STOPPED AT NINE ROUNDS, ALWAYS.
            //
            // Every backtest and every calibration gate in this repo replays a
            // past season nine rounds deep, so nothing has ever validated what
            // the room does in rounds 10-16 against a season it did not see -
            // which is exactly where defences and keeper stashes live. It is
            // why my first attempt to check the room model on held-out seasons
            // produced 0% in every late band and no defences at all: the rounds
            // were not in the schedule.
            //
            // -PfullRounds=true builds the whole draft. Off by default, so
            // every number those gates have ever produced still reproduces.
            int deepest = Boolean.getBoolean("fullRounds")
                    ? Integer.MAX_VALUE : SelectionModel.GAME_ROUNDS;
            if(round <= deepest){
                schedule.add(new Slot(pick.get("pick_no").getAsInt(), round, manager, isKeeper));
            }
        }

        List<String> board = new ArrayList<>();
        for(Map.Entry<String, Double> entry : season.adp.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null || !StartingLineup.isSkillPosition(player.position)){
                continue;
            }
            if(entry.getValue() > SelectionModel.ADP_LIMIT || season.keptIDs.contains(entry.getKey())){
                continue;
            }
            board.add(entry.getKey());
        }
        Map<String, Set<String>> keeperStacks = new HashMap<>();
        for(JsonElement pickElement : season.picks){
            JsonObject pick = pickElement.getAsJsonObject();
            JsonElement isKeeperElement = pick.get("is_keeper");
            JsonElement pickedBy = pick.get("picked_by");
            if(isKeeperElement == null || isKeeperElement.isJsonNull()
                    || !isKeeperElement.getAsBoolean()
                    || pickedBy == null || pickedBy.isJsonNull()){
                continue;
            }
            String sleeperID = pick.get("player_id").getAsString();
            Player player = Player.getPlayerFromSIDV2(sleeperID);
            String team = extras.teamOf().get(sleeperID);
            if(player != null && player.position.equals(Position.QB) && team != null){
                keeperStacks.computeIfAbsent(pickedBy.getAsString(), u -> new HashSet<>())
                        .add(team);
            }
        }
        Extras withStacks = new Extras(extras.teEarliness(), extras.rbEarliness(),
                extras.teamOf(), extras.rookies(), extras.adpSpreadCentered(), keeperStacks,
                extras.formerPlayersByManager(), extras.young(), extras.pointsOverride());
        Map<String, Double> simulatorPoints = extras.pointsOverride().isEmpty()
                ? season.rawPoints : extras.pointsOverride();
        return new DraftSimulator(schedule, board, season.adp, simulatorPoints, rosters,
                model, qbEarliness, withStacks);
    }

    /** How my own slots pick when a planner drives them instead of the model. */
    public interface MyPolicy {
        /** The remaining board in ADP order; returns the sleeper id to take. */
        String choose(List<String> board, Slot slot);

        /**
         * The same choice with the full mid-draft state visible, for policies
         * that branch hypotheticals (copy the state before mutating it - the
         * one passed in is the live rollout).
         */
        default String choose(List<String> board, Slot slot, SimState state){
            return choose(board, slot);
        }
    }

    /**
     * A draft in progress: everything simulateFrom needs to play it to the
     * end. copy() is a deep copy, so a policy can branch "what if I took X
     * here" without disturbing the rollout it lives in.
     */
    public static final class SimState {
        int scheduleIndex;
        String lastTaken;
        final List<String> board;
        final Map<String, Integer> takenAt;
        final Map<String, Map<Position, Integer>> rosters;
        final List<Position> recentPicks;
        final Map<String, Set<String>> stackTeams;

        SimState(int scheduleIndex, List<String> board, Map<String, Integer> takenAt,
                 Map<String, Map<Position, Integer>> rosters, List<Position> recentPicks,
                 Map<String, Set<String>> stackTeams){
            this.scheduleIndex = scheduleIndex;
            this.board = board;
            this.takenAt = takenAt;
            this.rosters = rosters;
            this.recentPicks = recentPicks;
            this.stackTeams = stackTeams;
        }

        /** The most recent player this state took. */
        public String lastTaken(){
            return lastTaken;
        }

        /** The pick number that took a player in this state, or null. */
        public Integer takenAtOf(String sleeperID){
            return takenAt.get(sleeperID);
        }

        /** The remaining board, for engines that plan from a live state. */
        public List<String> boardView(){
            return java.util.Collections.unmodifiableList(board);
        }

        public SimState copy(){
            Map<String, Map<Position, Integer>> rosterCopy = new HashMap<>();
            for(Map.Entry<String, Map<Position, Integer>> entry : rosters.entrySet()){
                rosterCopy.put(entry.getKey(), new EnumMap<>(entry.getValue()));
            }
            Map<String, Set<String>> stackCopy = new HashMap<>();
            for(Map.Entry<String, Set<String>> entry : stackTeams.entrySet()){
                stackCopy.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
            return new SimState(scheduleIndex, new ArrayList<>(board), new HashMap<>(takenAt),
                    rosterCopy, new ArrayList<>(recentPicks), stackCopy);
        }
    }

    /**
     * Draft night's entry point: replay the picks that have really happened
     * into a SimState, so the live board becomes the root the engine plans
     * from. Picks arrive in pick_no order.
     *
     * ANYTHING NOT ON OUR BOARD CONSUMES NOTHING. The increment sits inside the
     * board.contains guard below, so an id we do not carry advances neither the
     * schedule nor the pool. Until 2026-09-01 this paragraph said the opposite -
     * "advances the schedule without touching the pool" - which is prose drift
     * (TRAPS F27), and wrong in the more dangerous direction: it describes a
     * simulator that stays in step when the code's does not.
     *
     * The behaviour is RIGHT for a keeper. The loop below skips his keeper slot
     * first, so a keeper consumes exactly one thing whether Sleeper flags the
     * pick is_keeper (LiveDraft.livePicks drops it, the while-loop eats the
     * slot) or the commissioner hand-entered it as an ordinary pick (the id
     * arrives, finds no board entry, and the while-loop has already eaten the
     * slot). Both orders align, and a keeper entered at the WRONG round drifts
     * only between the real pick and the scheduled slot, then re-syncs.
     *
     * It is WRONG for a real pick of a man the board does not carry - a kicker,
     * someone past the ADP cut, an id we do not know. He spends a live pick, the
     * schedule does not move, and from there every pick is priced one seat early
     * and attributed to the wrong manager. Not changed the night before a draft;
     * DraftNight.scheduleDrift DETECTS it and both live tools print it.
     */
    public SimState stateAfter(List<String> takenInOrder){
        SimState state = initialState();
        for(String sleeperID : takenInOrder){
            while(state.scheduleIndex < schedule.size()
                    && schedule.get(state.scheduleIndex).keeperSlot()){
                state.scheduleIndex++;
            }
            if(state.scheduleIndex >= schedule.size()){
                break;
            }
            // A player already off the board is a keeper the schedule has
            // accounted for; consuming a live slot for him would shift every
            // later pick by one and corrupt the state.
            if(state.board.contains(sleeperID)){
                applyPick(state, schedule.get(state.scheduleIndex), sleeperID);
                state.scheduleIndex++;
            }
        }
        return state;
    }

    /** The slot the given state is about to fill, or null past the game. */
    public Slot slotOf(SimState state){
        int index = state.scheduleIndex;
        while(index < schedule.size() && schedule.get(index).keeperSlot()){
            index++;
        }
        return index < schedule.size() ? schedule.get(index) : null;
    }

    /** The state a rollout starts from. */
    public SimState initialState(){
        Map<String, Map<Position, Integer>> rosters = new HashMap<>();
        for(Map.Entry<String, Map<Position, Integer>> entry : initialRosters.entrySet()){
            rosters.put(entry.getKey(), new EnumMap<>(entry.getValue()));
        }
        Map<String, Set<String>> stackTeams = new HashMap<>();
        for(Map.Entry<String, Set<String>> entry : extras.keeperStackTeams().entrySet()){
            stackTeams.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        return new SimState(0, new ArrayList<>(initialBoard), new HashMap<>(),
                rosters, new ArrayList<>(), stackTeams);
    }

    /**
     * A copy of the state with `chosen` taken at the state's current slot -
     * the branch point for a policy pricing one of its own candidate picks.
     *
     * "The state's current slot" has to mean the same thing here as it does in
     * slotOf(), and it did not. slotOf() scans FORWARD past keeper slots and
     * does not write the index back, so a state whose scheduleIndex is resting
     * on a keeper slot reports one pick and branched into another: the man went
     * into a slot that selects nobody, was credited to the keeper's owner, cost
     * no live pick, and left one extra real pick to be simulated before the
     * brancher's next turn. WaitCheck - Model A's own wait-or-take table -
     * branches straight off a state from stateAfter, and this league has
     * twenty-four keeper slots. Pinned by ScheduleDriftTest.
     */
    public SimState branchWith(SimState state, String chosen){
        SimState branch = state.copy();
        while(branch.scheduleIndex < schedule.size()
                && schedule.get(branch.scheduleIndex).keeperSlot()){
            branch.scheduleIndex++;
        }
        if(branch.scheduleIndex >= schedule.size()){
            throw new IllegalStateException("branchWith past the end of the schedule -"
                    + " there is no slot left to spend on " + chosen);
        }
        applyPick(branch, schedule.get(branch.scheduleIndex), chosen);
        branch.scheduleIndex++;
        return branch;
    }

    /** Advances exactly one slot of the given state, model-driven. */
    public void simulateOneFrom(SimState state, Random random){
        int before = state.takenAt.size();
        while(state.scheduleIndex < schedule.size() && state.takenAt.size() == before){
            Slot slot = schedule.get(state.scheduleIndex);
            if(slot.keeperSlot() || state.board.isEmpty()){
                state.scheduleIndex++;
                continue;
            }
            List<String> choiceSet = notBeforeThisLeagueEverHas(
                    new ArrayList<>(state.board.subList(0, Math.min(state.board.size(),
                            SelectionModel.CHOICE_SET))), slot.round());
            double[] shape = turnShape.getOrDefault(slot.pickNumber(),
                    new double[]{0, 0, 1.0});
            long qbHolders = state.rosters.values().stream()
                    .filter(counts -> counts.getOrDefault(Position.QB, 0) > 0).count();
            Map<Position, Integer> roster = state.rosters.computeIfAbsent(
                    slot.manager(), u -> new EnumMap<>(Position.class));
            double[] probabilities = managerModels.getOrDefault(slot.manager(), model)
                    .choiceProbabilities(SelectionModel.featuresWithBoard(initialBoard,
                            choiceSet, adp, points,
                            new SelectionModel.Context(roster,
                                    qbEarliness.getOrDefault(slot.manager(), 0.0),
                                    state.recentPicks, slot.pickNumber(),
                                    shape[0] > 0, shape[1] > 0, shape[2],
                                    qbHolders / teams,
                                    extras.teEarliness().getOrDefault(slot.manager(), 0.0),
                                    extras.rbEarliness().getOrDefault(slot.manager(), 0.0),
                                    state.stackTeams.getOrDefault(slot.manager(), Set.of()),
                                    extras.teamOf(), extras.rookies(),
                                    extras.adpSpreadCentered(),
                                    extras.formerPlayersByManager()
                                            .getOrDefault(slot.manager(), Set.of()),
                                    extras.young())));
            String chosen = choiceSet.get(sample(probabilities, random));
            applyPick(state, slot, chosen);
            state.lastTaken = chosen;
            state.scheduleIndex++;
        }
    }

    /** One simulated draft: sleeper id -> the pick number that took the player. */
    public Map<String, Integer> simulateOnce(Random random){
        return simulateOnce(random, null, null);
    }

    /** The same, with the given manager's picks driven by a policy. */
    public Map<String, Integer> simulateOnce(Random random, String me, MyPolicy myPolicy){
        return simulateFrom(initialState(), random, me, myPolicy).takenAt;
    }

    /**
     * Plays a draft from wherever `state` stands to the end, mutating and
     * returning it. The resumable core: simulateOnce is this from the start,
     * and an adaptive policy prices candidates by branching mid-draft states
     * and completing them (the same primitive a live draft-day mode needs).
     */
    public SimState simulateFrom(SimState state, Random random, String me, MyPolicy myPolicy){
        while(state.scheduleIndex < schedule.size()){
            Slot slot = schedule.get(state.scheduleIndex);
            if(slot.keeperSlot() || state.board.isEmpty()){
                state.scheduleIndex++;
                continue;
            }
            List<String> board = state.board;
            Map<String, Map<Position, Integer>> rosters = state.rosters;
            Map<String, Set<String>> stackTeams = state.stackTeams;
            List<Position> recentPicks = state.recentPicks;
            Map<Position, Integer> roster = rosters.computeIfAbsent(
                    slot.manager(), u -> new EnumMap<>(Position.class));
            String chosen;
            if(myPolicy != null && slot.manager().equals(me)){
                chosen = myPolicy.choose(java.util.Collections.unmodifiableList(board), slot,
                        state);
            }
            else {
                List<String> choiceSet = notBeforeThisLeagueEverHas(
                        new ArrayList<>(board.subList(0,
                                Math.min(board.size(), SelectionModel.CHOICE_SET))),
                        slot.round());
                double[] shape = turnShape.getOrDefault(slot.pickNumber(),
                        new double[]{0, 0, 1.0});
                long qbHolders = rosters.values().stream()
                        .filter(counts -> counts.getOrDefault(Position.QB, 0) > 0).count();
                double[] probabilities = managerModels.getOrDefault(slot.manager(), model)
                        .choiceProbabilities(SelectionModel.featuresWithBoard(initialBoard,
                        choiceSet, adp, points, new SelectionModel.Context(
                                roster,
                                qbEarliness.getOrDefault(slot.manager(), 0.0), recentPicks,
                                slot.pickNumber(), shape[0] > 0, shape[1] > 0, shape[2],
                                qbHolders / teams,
                                extras.teEarliness().getOrDefault(slot.manager(), 0.0),
                                extras.rbEarliness().getOrDefault(slot.manager(), 0.0),
                                stackTeams.getOrDefault(slot.manager(), Set.of()),
                                extras.teamOf(), extras.rookies(),
                                extras.adpSpreadCentered(),
                                extras.formerPlayersByManager()
                                        .getOrDefault(slot.manager(), Set.of()),
                                extras.young())));
                chosen = choiceSet.get(sample(probabilities, random));
            }
            applyPick(state, slot, chosen);
            state.scheduleIndex++;
        }
        return state;
    }

    // My policy picks feed the run window too - a deliberate early QB
    // can start the very run the feature measures.
    private void applyPick(SimState state, Slot slot, String chosen){
        state.takenAt.put(chosen, slot.pickNumber());
        state.board.remove(chosen);
        Position position = Player.getPlayerFromSIDV2(chosen).position;
        state.recentPicks.add(position);
        state.rosters.computeIfAbsent(slot.manager(), u -> new EnumMap<>(Position.class))
                .merge(position, 1, Integer::sum);
        if(position.equals(Position.QB) && extras.teamOf().containsKey(chosen)){
            state.stackTeams.computeIfAbsent(slot.manager(), u -> new HashSet<>())
                    .add(extras.teamOf().get(chosen));
        }
    }

    /** The same world with per-manager brains layered over the shared model. */
    public DraftSimulator withManagerModels(Map<String, ChoiceModel> overrides){
        DraftSimulator copy = new DraftSimulator(schedule, initialBoard, adp, points,
                initialRosters, model, qbEarliness, extras);
        copy.managerModels.putAll(managerModels);
        copy.managerModels.putAll(overrides);
        return copy;
    }

    /**
     * The same world with the given manager's slots in `rounds` flipped to
     * keeper slots (they select nobody). PolicyTournament pins Justin's
     * out-of-game keepers onto his rounds 8-9 with this, per his spec: seven
     * live picks plus the two kept players is exactly a starting nine.
     */
    public DraftSimulator withKeeperSlots(String manager, Set<Integer> rounds){
        List<Slot> adjusted = new ArrayList<>();
        for(Slot slot : schedule){
            boolean flip = rounds.contains(slot.round()) && slot.manager().equals(manager);
            adjusted.add(flip
                    ? new Slot(slot.pickNumber(), slot.round(), slot.manager(), true) : slot);
        }
        return new DraftSimulator(adjusted, initialBoard, adp, points, initialRosters,
                model, qbEarliness, extras);
    }

    public Slot slotAt(int pickNumber){
        return slotByPickNumber.get(pickNumber);
    }

    private static int sample(double[] probabilities, Random random){
        double u = random.nextDouble();
        double cumulative = 0;
        for(int a = 0; a < probabilities.length; a++){
            cumulative += probabilities[a];
            if(u < cumulative){
                return a;
            }
        }
        return probabilities.length - 1;
    }

    /** Same contract as AvailabilityModel.survivalMatrix, from simulated drafts. */
    public Map<String, double[]> survivalMatrix(int[] checkpointPicks, int trials, long seed){
        Random random = new Random(seed);
        Map<String, int[]> available = new HashMap<>();
        for(String id : initialBoard){
            available.put(id, new int[checkpointPicks.length]);
        }
        for(int trial = 0; trial < trials; trial++){
            Map<String, Integer> takenAt = simulateOnce(random);
            for(String id : initialBoard){
                int taken = takenAt.getOrDefault(id, Integer.MAX_VALUE);
                int[] row = available.get(id);
                for(int c = 0; c < checkpointPicks.length; c++){
                    if(taken >= checkpointPicks[c]){
                        row[c]++;
                    }
                }
            }
        }
        Map<String, double[]> matrix = new HashMap<>();
        for(Map.Entry<String, int[]> entry : available.entrySet()){
            double[] row = new double[checkpointPicks.length];
            for(int c = 0; c < row.length; c++){
                row[c] = entry.getValue()[c] / (double) trials;
            }
            matrix.put(entry.getKey(), row);
        }
        return matrix;
    }

    /** Mean simulated round of the first in-draft pick at a position, per manager. */
    public Map<String, Double> meanFirstRound(Position position, int trials, long seed){
        Random random = new Random(seed);
        Map<String, Double> totals = new HashMap<>();
        List<String> managers = managers();
        for(int trial = 0; trial < trials; trial++){
            Map<String, Integer> first = firstRounds(simulateOnce(random), position);
            for(String manager : managers){
                totals.merge(manager, (double) first.getOrDefault(manager, NEVER_ROUND), Double::sum);
            }
        }
        for(String manager : managers){
            totals.computeIfPresent(manager, (m, total) -> total / trials);
        }
        return totals;
    }

    private Map<String, Integer> firstRounds(Map<String, Integer> takenAt, Position position){
        Map<String, Integer> first = new HashMap<>();
        for(Map.Entry<String, Integer> entry : takenAt.entrySet()){
            if(Player.getPlayerFromSIDV2(entry.getKey()).position.equals(position)){
                Slot slot = slotByPickNumber.get(entry.getValue());
                first.merge(slot.manager(), slot.round(), Math::min);
            }
        }
        return first;
    }

    /** The real draft's answer to the same question, censored the same way. */
    public static Map<String, Integer> realFirstRound(JsonArray picks, Position position){
        Map<String, Integer> first = new HashMap<>();
        for(JsonElement pickElement : picks){
            JsonObject pick = pickElement.getAsJsonObject();
            JsonElement isKeeper = pick.get("is_keeper");
            if(isKeeper != null && !isKeeper.isJsonNull() && isKeeper.getAsBoolean()){
                continue;
            }
            if(pick.get("round").getAsInt() > SelectionModel.GAME_ROUNDS){
                continue;
            }
            JsonElement pickedBy = pick.get("picked_by");
            Player player = Player.getPlayerFromSIDV2(pick.get("player_id").getAsString());
            if(pickedBy == null || pickedBy.isJsonNull() || player == null
                    || !player.position.equals(position)){
                continue;
            }
            first.merge(pickedBy.getAsString(), pick.get("round").getAsInt(), Math::min);
        }
        return first;
    }

    /** In-draft managers, in first-pick order. */
    public List<String> managers(){
        List<String> managers = new ArrayList<>();
        for(Slot slot : schedule){
            if(!slot.keeperSlot() && !slot.manager().isEmpty() && !managers.contains(slot.manager())){
                managers.add(slot.manager());
            }
        }
        return managers;
    }

    /** A manager's non-keeper pick numbers, in order. */
    public int[] pickNumbersOf(String manager){
        return schedule.stream()
                .filter(slot -> !slot.keeperSlot() && slot.manager().equals(manager))
                .mapToInt(Slot::pickNumber).toArray();
    }

    public Set<String> players(){
        return new HashSet<>(initialBoard);
    }

    // ---- the gates ----

    private static double[] headToHead(String title, DraftSimulator simulator,
                                       AvailabilityModel incumbent, DraftBacktest.Season season,
                                       int[] checkpoints, int trials, boolean printSimulatedBuckets){
        Map<String, double[]> simulated = simulator.survivalMatrix(checkpoints, trials, SEED);
        Map<String, double[]> gaussian = incumbent.survivalMatrix(checkpoints, trials, SEED);
        gaussian.keySet().retainAll(simulated.keySet());
        simulated.keySet().retainAll(gaussian.keySet());

        double[][] simulatedBuckets = new double[10][3];
        double[][] gaussianBuckets = new double[10][3];
        double simulatedError = DraftBacktest.calibrationOfMatrix(
                simulated, checkpoints, season, simulatedBuckets);
        double gaussianError = DraftBacktest.calibrationOfMatrix(
                gaussian, checkpoints, season, gaussianBuckets);

        System.out.println("\n" + title + ":\n");
        if(printSimulatedBuckets){
            DraftBacktest.printBuckets(simulatedBuckets);
            System.out.println();
        }
        System.out.printf("   %-24s %10s %14s%n", "MODEL", "WEIGHTED", "MID-BUCKETS");
        System.out.printf("   %-24s %9.2f%% %13.1f%%%n", "selection-model drafts",
                simulatedError * 100, DraftBacktest.midBucketGap(simulatedBuckets) * 100);
        System.out.printf("   %-24s %9.2f%% %13.1f%%%n", "gaussian (incumbent)",
                gaussianError * 100, DraftBacktest.midBucketGap(gaussianBuckets) * 100);
        return new double[]{simulatedError, gaussianError};
    }

    /** League-mean first round at a position over the training seasons. */
    static double trainingMeanFirstRound(AAAConfiguration configuration, Position position,
                                         int lastSeason){
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();
        double total = 0;
        int count = 0;
        for(int i = 0; i < drafts.size() && i < seasons.size(); i++){
            if(seasons.get(i) == null || Integer.parseInt(seasons.get(i)) > lastSeason){
                continue;
            }
            for(int round : realFirstRound(drafts.get(i), position).values()){
                total += round;
                count++;
            }
        }
        return count == 0 ? 7.0 : total / count;
    }

    static int[] gameCheckpoints(){
        int[] checkpoints = new int[SelectionModel.GAME_ROUNDS];
        for(int c = 0; c < checkpoints.length; c++){
            checkpoints[c] = 13 + 12 * c;
        }
        return checkpoints;
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 600);

        // ---- choose features and temperature on 2024, model fitted through 2023 ----
        Map<String, Double> tuneEarliness = SelectionModel.qbEarliness(configuration, 2023);
        List<SelectionModel.Observation> tuneObservations =
                SelectionModel.loadObservations(configuration, 2021, 2023, tuneEarliness);
        DraftBacktest.Season tuneSeason = new DraftBacktest.Season(configuration, "2024");

        boolean[] withoutIntercepts = new boolean[SelectionModel.FEATURES];
        for(int f = 0; f < 5; f++){
            withoutIntercepts[f] = true;
        }
        boolean[] withIntercepts = withoutIntercepts.clone();
        withIntercepts[5] = withIntercepts[6] = withIntercepts[7] = true;
        boolean[] withRun = withIntercepts.clone();
        withRun[8] = true;
        boolean[] withCliff = new boolean[SelectionModel.FEATURES];
        Arrays.fill(withCliff, true);
        Map<boolean[], String> labels = new java.util.LinkedHashMap<>();
        labels.put(withoutIntercepts, "no intercepts");
        labels.put(withIntercepts, "with intercepts");
        labels.put(withRun, "intercepts + QB run");
        labels.put(withCliff, "QB run + cliff");

        System.out.println("Choosing features and temperature on 2024 (model fitted 2021-2023):\n");
        boolean[] bestFeatures = withoutIntercepts;
        double bestTemperature = 1.0;
        double bestError = 1.0;
        for(Map.Entry<boolean[], String> variant : labels.entrySet()){
            SelectionModel candidate = SelectionModel.fit(tuneObservations, variant.getKey());
            for(double temperature : new double[]{1.0, 1.5, 2.0}){
                DraftSimulator tuner = forSeason(tuneSeason, candidate.scaled(temperature),
                        tuneEarliness);
                double error = DraftBacktest.calibrationOfMatrix(
                        tuner.survivalMatrix(gameCheckpoints(), trials / 3, SEED),
                        gameCheckpoints(), tuneSeason, null);
                System.out.printf("   %-17s temperature %.1f  calib error %5.2f%%%n",
                        variant.getValue(), temperature, error * 100);
                if(error < bestError){
                    bestError = error;
                    bestFeatures = variant.getKey();
                    bestTemperature = temperature;
                }
            }
        }
        System.out.printf("   chosen: %s, temperature %.1f%n",
                labels.get(bestFeatures), bestTemperature);

        // ---- report on 2025, model fitted through 2024, chosen setup ----
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, 2024);
        SelectionModel model = SelectionModel.fit(
                SelectionModel.loadObservations(configuration, 2021, 2024, earliness), bestFeatures)
                .scaled(bestTemperature);
        System.out.printf("%nselection model fitted through 2024, %d simulated drafts of 2025%n", trials);

        DraftBacktest.Season season = new DraftBacktest.Season(configuration, "2025");
        DraftSimulator simulator = forSeason(season, model, earliness);
        ManagerProfiles profiles = ManagerProfiles.fitThroughSeason(configuration, 2024);
        AvailabilityModel incumbent = season.model(profiles,
                AvailabilityModel.PICK_STANDARD_DEVIATION, AvailabilityModel.VALUE_WEIGHT);

        double[] game = headToHead("gate 2 - survival calibration inside the nine-round game, 2025",
                simulator, incumbent, season, gameCheckpoints(), trials, true);

        int[] myPicks = simulator.pickNumbersOf(configuration.getMyID());
        double[] mine = headToHead("gate 2b - the same, at my " + myPicks.length
                        + " actual 2025 in-draft slots " + Arrays.toString(myPicks),
                simulator, incumbent, season, myPicks, trials, false);

        Map<String, Integer> real = realFirstRound(season.picks, Position.QB);
        Map<String, Double> simulatedTiming = simulator.meanFirstRound(Position.QB, trials, SEED);
        double leagueMean = trainingMeanFirstRound(configuration, Position.QB, 2024);

        System.out.printf("%ngate 3 - first in-draft QB round per manager, 2025 (%d = none in nine rounds):%n%n",
                NEVER_ROUND);
        System.out.printf("   %-22s %6s %10s %13s%n", "MANAGER", "REAL", "SIMULATED", "LEAGUE-MEAN");
        double modelError = 0;
        double baselineError = 0;
        List<String> managers = simulator.managers();
        for(String manager : managers){
            double actual = Math.min(real.getOrDefault(manager, NEVER_ROUND), NEVER_ROUND);
            double predicted = simulatedTiming.getOrDefault(manager, (double) NEVER_ROUND);
            System.out.printf("   %-22s %6.0f %10.1f %13.1f%n",
                    HumanOfInterest.getHumanFromID(manager), actual, predicted, leagueMean);
            modelError += Math.abs(actual - predicted);
            baselineError += Math.abs(actual - leagueMean);
        }
        modelError /= managers.size();
        baselineError /= managers.size();
        System.out.printf("%n   mean abs error: simulated %.2f rounds, league-mean constant %.2f rounds%n",
                modelError, baselineError);
        System.out.println("   (the gaussian has no managers, so it cannot make this prediction at all)");

        System.out.println("\nverdict:");
        System.out.printf("   gate 2   %s  (%.2f%% vs incumbent %.2f%%)%n",
                game[0] <= game[1] ? "PASS" : "FAIL", game[0] * 100, game[1] * 100);
        System.out.printf("   gate 2b  %s  (%.2f%% vs incumbent %.2f%%)%n",
                mine[0] <= mine[1] ? "PASS" : "FAIL", mine[0] * 100, mine[1] * 100);
        System.out.printf("   gate 3   %s  (MAE %.2f vs constant %.2f)%n",
                modelError <= baselineError ? "PASS" : "FAIL", modelError, baselineError);
    }

}
