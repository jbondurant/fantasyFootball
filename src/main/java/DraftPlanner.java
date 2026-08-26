import PlayerImportAndSetup.Position;
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
 * Step C: the expectimax the repo always wanted, on the fitted selection
 * model. At each of my live picks, in order, it branches over the four
 * positions - take the best projected player at that position now - and
 * values each branch by rolling the rest of the draft out under
 * DraftSimulator, with my later picks played greedily by marginal best-nine
 * value. The chosen position is fixed and the search moves to my next pick,
 * so every decision looks all the way to round 9 through its rollouts.
 *
 * Scoring rule (Justin's formalization): my roster is keepers UNION picks and
 * the score is the best legal nine - so an out-of-game keeper "costs a round
 * 9" only emergently, by benching my weakest pick.
 *
 * Risk is availability risk only, and it is tunable: branches are ranked by
 * mean - lambda * (mean - p_q), lambda 0 = pure expectation. The snipe
 * decomposition prints, for every position at every pick, the probability the
 * player I would wait for is gone by my next pick and the drop when he is -
 * the wait-versus-take information, made explicit.
 *
 *     ./gradlew run -Pmain=DraftPlanner [-Ptrials=300] [-Prisk=0.5] [-Pquantile=0.10]
 */
public class DraftPlanner {

    private static final Position[] POSITIONS =
            {Position.QB, Position.RB, Position.WR, Position.TE};

    private final DraftSimulator simulator;
    private final String me;
    private final List<String> myKeeperIDs;
    private final Map<String, Double> points;
    private final int[] myPickNumbers;

    public DraftPlanner(DraftSimulator simulator, String me, List<String> myKeeperIDs,
                        Map<String, Double> points){
        this.simulator = simulator;
        this.me = me;
        this.myKeeperIDs = List.copyOf(myKeeperIDs);
        this.points = points;
        this.myPickNumbers = simulator.pickNumbersOf(me);
    }

    public record PositionValue(Position position, double mean, double p10, double riskAdjusted){}
    public record Stage(int pickNumber, int round, List<PositionValue> options, Position chosen){}
    public record SnipeRow(int pickNumber, Position position, String usualTarget,
                           double probabilityGone, double meanDropWhenGone){}
    /** standardError is the Monte Carlo noise on mean - differences inside
     *  roughly two of these are ties; raise -Ptrials to shrink it. */
    public record Plan(List<Position> positions, List<Stage> stages,
                       double mean, double p10, double riskAdjusted, double standardError,
                       List<SnipeRow> snipes){}

    /** My picks under a plan: fixed positions first, marginal-greedy after. */
    private class PlanPolicy implements DraftSimulator.MyPolicy {
        final List<Position> prefix;
        final List<String> mine = new ArrayList<>(myKeeperIDs);
        final List<Map<Position, String>> bestSeen = new ArrayList<>();
        int index = 0;

        PlanPolicy(List<Position> prefix){
            this.prefix = prefix;
        }

        @Override
        public String choose(List<String> board, DraftSimulator.Slot slot){
            Map<Position, String> best = bestByPosition(board);
            bestSeen.add(best);
            String chosen = index < prefix.size() ? best.get(prefix.get(index)) : null;
            if(chosen == null){
                chosen = greedy(best);
            }
            index++;
            mine.add(chosen);
            return chosen;
        }

        private String greedy(Map<Position, String> best){
            String top = null;
            double topScore = -1;
            for(String candidate : best.values()){
                mine.add(candidate);
                double score = StartingLineup.bestNine(mine, points);
                mine.remove(mine.size() - 1);
                if(score > topScore){
                    topScore = score;
                    top = candidate;
                }
            }
            return top;
        }
    }

    /** Best projected player at each position still on the board. */
    private Map<Position, String> bestByPosition(List<String> board){
        Map<Position, String> best = new EnumMap<>(Position.class);
        Map<Position, Double> bestPoints = new EnumMap<>(Position.class);
        for(String sleeperID : board){
            Position position = Player.getPlayerFromSIDV2(sleeperID).position;
            double projected = points.getOrDefault(sleeperID, 0.0);
            if(projected > bestPoints.getOrDefault(position, -1.0)){
                bestPoints.put(position, projected);
                best.put(position, sleeperID);
            }
        }
        return best;
    }

    static double quantile(double[] sortedAscending, double q){
        int index = (int) Math.floor(q * (sortedAscending.length - 1));
        return sortedAscending[index];
    }

    public Plan plan(int rollouts, double lambda, double q, long seed){
        List<Position> chosenPositions = new ArrayList<>();
        List<Stage> stages = new ArrayList<>();
        double[] finalStats = {0, 0, 0};
        for(int stage = 0; stage < myPickNumbers.length; stage++){
            List<PositionValue> options = new ArrayList<>();
            Position best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            double[] bestStats = null;
            for(Position candidate : POSITIONS){
                List<Position> prefix = new ArrayList<>(chosenPositions);
                prefix.add(candidate);
                double[] outcomes = new double[rollouts];
                // Common random numbers: rollout r uses the same opponent
                // stream for all four candidates, so branches differ only
                // through my choice. Rollouts are independent, so they run
                // across cores; seeds keep the result deterministic.
                java.util.stream.IntStream.range(0, rollouts).parallel().forEach(r -> {
                    PlanPolicy policy = new PlanPolicy(prefix);
                    simulator.simulateOnce(new Random(seed + 7919L * r), me, policy);
                    outcomes[r] = StartingLineup.bestNine(policy.mine, points);
                });
                Arrays.sort(outcomes);
                double mean = Arrays.stream(outcomes).average().orElse(0);
                double p10 = quantile(outcomes, q);
                double adjusted = mean - lambda * (mean - p10);
                options.add(new PositionValue(candidate, mean, p10, adjusted));
                if(adjusted > bestScore){
                    bestScore = adjusted;
                    best = candidate;
                    double variance = Arrays.stream(outcomes)
                            .map(outcome -> (outcome - mean) * (outcome - mean)).sum()
                            / Math.max(rollouts - 1, 1);
                    bestStats = new double[]{mean, p10, adjusted,
                            Math.sqrt(variance / rollouts)};
                }
            }
            chosenPositions.add(best);
            stages.add(new Stage(myPickNumbers[stage],
                    simulator.slotAt(myPickNumbers[stage]).round(), options, best));
            finalStats = bestStats;
        }
        return new Plan(chosenPositions, stages, finalStats[0], finalStats[1], finalStats[2],
                finalStats.length > 3 ? finalStats[3] : 0,
                snipes(chosenPositions, rollouts, seed));
    }

    /**
     * For every position at every pick: how often the player I would wait for
     * is gone by my next pick, and how much worse the position is when he is.
     */
    private List<SnipeRow> snipes(List<Position> plan, int rollouts, long seed){
        int picks = myPickNumbers.length;
        double[][] count = new double[picks][POSITIONS.length];
        double[][] gone = new double[picks][POSITIONS.length];
        double[][] dropWhenGone = new double[picks][POSITIONS.length];
        List<Map<String, Integer>> targetCounts = new ArrayList<>();
        for(int i = 0; i < picks * POSITIONS.length; i++){
            targetCounts.add(new HashMap<>());
        }
        Object merge = new Object();
        java.util.stream.IntStream.range(0, rollouts).parallel().forEach(r -> {
            PlanPolicy policy = new PlanPolicy(plan);
            Map<String, Integer> takenAt = simulator.simulateOnce(
                    new Random(seed + 7919L * r), me, policy);
            synchronized(merge){
            for(int i = 0; i + 1 < policy.bestSeen.size(); i++){
                for(int p = 0; p < POSITIONS.length; p++){
                    Position position = POSITIONS[p];
                    String target = policy.bestSeen.get(i).get(position);
                    if(target == null || takenAt.getOrDefault(target, 0) == myPickNumbers[i]){
                        continue;   // nobody left there, or I took him myself
                    }
                    count[i][p]++;
                    targetCounts.get(i * POSITIONS.length + p).merge(target, 1, Integer::sum);
                    int taken = takenAt.getOrDefault(target, Integer.MAX_VALUE);
                    if(taken < myPickNumbers[i + 1]){
                        gone[i][p]++;
                        String nextBest = policy.bestSeen.get(i + 1).get(position);
                        dropWhenGone[i][p] += points.getOrDefault(target, 0.0)
                                - (nextBest == null ? 0.0 : points.getOrDefault(nextBest, 0.0));
                    }
                }
            }
            }
        });
        List<SnipeRow> rows = new ArrayList<>();
        for(int i = 0; i + 1 < picks; i++){
            for(int p = 0; p < POSITIONS.length; p++){
                if(count[i][p] == 0){
                    continue;
                }
                String usualTarget = targetCounts.get(i * POSITIONS.length + p).entrySet().stream()
                        .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
                rows.add(new SnipeRow(myPickNumbers[i], POSITIONS[p], playerName(usualTarget),
                        gone[i][p] / count[i][p],
                        gone[i][p] == 0 ? 0 : dropWhenGone[i][p] / gone[i][p]));
            }
        }
        return rows;
    }

    private static String playerName(String sleeperID){
        Player player = Player.getPlayerFromSIDV2(sleeperID);
        return player == null ? sleeperID : player.firstName + " " + player.lastName;
    }

    /** Where a plan's points actually come from, and who mans the QB slot. */
    public record Profile(StartingLineup.NineBreakdown slots, Map<String, Integer> lineupQBs,
                          int rollouts){}

    /**
     * Rollouts under a fixed plan, decomposed: mean points per slot group and
     * how often each quarterback ends up the one in my lineup. Uses the same
     * rollout streams as plan(), so the total equals that plan's mean.
     */
    public Profile profile(List<Position> plan, int rollouts, long seed){
        double qb = 0;
        double rb = 0;
        double wr = 0;
        double te = 0;
        double flex = 0;
        Map<String, Integer> lineupQBs = new HashMap<>();
        for(int r = 0; r < rollouts; r++){
            PlanPolicy policy = new PlanPolicy(plan);
            simulator.simulateOnce(new Random(seed + 7919L * r), me, policy);
            StartingLineup.NineBreakdown slots =
                    StartingLineup.bestNineBreakdown(policy.mine, points);
            qb += slots.qb();
            rb += slots.rb();
            wr += slots.wr();
            te += slots.te();
            flex += slots.flex();
            String starter = null;
            double best = -1;
            for(String sleeperID : policy.mine){
                if(Player.getPlayerFromSIDV2(sleeperID).position.equals(Position.QB)
                        && points.getOrDefault(sleeperID, 0.0) > best){
                    best = points.getOrDefault(sleeperID, 0.0);
                    starter = sleeperID;
                }
            }
            if(starter != null){
                lineupQBs.merge(playerName(starter), 1, Integer::sum);
            }
        }
        return new Profile(new StartingLineup.NineBreakdown(qb / rollouts, rb / rollouts,
                wr / rollouts, te / rollouts, flex / rollouts), lineupQBs, rollouts);
    }

    // ---- the 2026 setup ----

    /**
     * This season's draft as it stands today: real draft order, declared
     * keepers occupying their slots, current league-scored projections and
     * ADP, opponents fitted on all history. myKeeper is the scenario knob -
     * null plans the no-keeper branch.
     */
    public static DraftPlanner forCurrentSeason(AAAConfiguration configuration, Keeper myKeeper){
        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, lastCompleted);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, lastCompleted, earliness);
        return forCurrentSeason(configuration, myKeeper, model, earliness);
    }

    /** The same, with the opponent model already fitted - for scenario loops. */
    public static DraftPlanner forCurrentSeason(AAAConfiguration configuration, Keeper myKeeper,
                                                ChoiceModel model, Map<String, Double> earliness){
        return forCurrentSeason(configuration,
                myKeeper == null ? List.of() : List.of(myKeeper), model, earliness);
    }

    /**
     * The pair form - the league allows two keepers. Hypothetical keepers
     * already visible among the declared ones are not double-counted, so the
     * -Pkeepers knob keeps working unchanged after the commissioner enters
     * the declaration into Sleeper.
     */
    public static DraftPlanner forCurrentSeason(AAAConfiguration configuration,
                                                List<Keeper> myKeepers,
                                                ChoiceModel model, Map<String, Double> earliness){
        return forCurrentSeasonAs(configuration, configuration.getMyID(), myKeepers,
                Set.of(), model, earliness);
    }

    /**
     * Any manager's seat: the planner optimizes THEIR picks while everyone
     * else, Justin included, plays by the fitted model. excludeKeeperIDs
     * removes keepers from the world entirely (player back on the board,
     * slot freed, off the roster) - the counterfactual a keeper's value is
     * measured against.
     */
    public static DraftPlanner forCurrentSeasonAs(AAAConfiguration configuration,
                                                  String perspective,
                                                  List<Keeper> extraKeepers,
                                                  Set<String> excludeKeeperIDs,
                                                  ChoiceModel model,
                                                  Map<String, Double> earliness){
        // -Pprojections=<name> swaps MY value feed to a bridged external
        // source (see ProjectionBridge); opponents keep behaving off the
        // consensus market either way, which the information-set test showed
        // is what they actually do.
        Map<String, Double> points = ProjectionSources.resolve(
                System.getProperty("projections", "sleeper"));
        Map<String, Double> adp = new HashMap<>();
        for(String sleeperID : points.keySet()){
            double value = SleeperProjections.adpOf(sleeperID);
            if(value < Double.MAX_VALUE){
                adp.put(sleeperID, value);
            }
        }

        String me = perspective;
        List<Keeper> keepers = new ArrayList<>(configuration.getTodaysKeepers());
        // Exclusions strip DECLARED keepers only; explicit extras always land,
        // so a ledger can re-evaluate a kept player as its own hypothetical.
        keepers.removeIf(keeper -> excludeKeeperIDs.contains(keeper.player.sleeperIDString));
        for(Keeper extra : extraKeepers){
            boolean alreadyPresent = keepers.stream().anyMatch(declared ->
                    declared.player.sleeperIDString.equals(extra.player.sleeperIDString));
            if(!alreadyPresent){
                keepers.add(extra);
            }
        }

        JsonObject draftOrder = configuration.getDraftJson().getAsJsonObject("draft_order");
        int teams = configuration.getLeagueJson().getAsJsonObject("settings")
                .get("num_teams").getAsInt();
        Map<Integer, String> managerAtSlot = new HashMap<>();
        for(Map.Entry<String, JsonElement> entry : draftOrder.entrySet()){
            managerAtSlot.put(entry.getValue().getAsInt(), entry.getKey());
        }

        Set<Integer> occupied = new HashSet<>();
        Set<String> keptIDs = new HashSet<>();
        Map<String, Map<Position, Integer>> rosters = new HashMap<>();
        List<String> myKeeperIDs = new ArrayList<>();
        for(Keeper keeper : keepers){
            keptIDs.add(keeper.player.sleeperIDString);
            rosters.computeIfAbsent(keeper.humanWhoCanKeep, u -> new EnumMap<>(Position.class))
                    .merge(keeper.player.position, 1, Integer::sum);
            if(me.equals(keeper.humanWhoCanKeep)){
                myKeeperIDs.add(keeper.player.sleeperIDString);
            }
            JsonElement slot = keeper.humanWhoCanKeep == null
                    ? null : draftOrder.get(keeper.humanWhoCanKeep);
            if(slot != null && !slot.isJsonNull()
                    && keeper.roundCanBeKept <= SelectionModel.GAME_ROUNDS){
                occupied.add(AAAConfiguration.pickNumber(
                        keeper.roundCanBeKept, slot.getAsInt(), teams));
            }
        }

        List<DraftSimulator.Slot> schedule = new ArrayList<>();
        for(int round = 1; round <= SelectionModel.GAME_ROUNDS; round++){
            for(int slot = 1; slot <= teams; slot++){
                int pickNumber = AAAConfiguration.pickNumber(round, slot, teams);
                schedule.add(new DraftSimulator.Slot(pickNumber, round,
                        managerAtSlot.getOrDefault(slot, ""), occupied.contains(pickNumber)));
            }
        }

        List<String> board = new ArrayList<>();
        for(Map.Entry<String, Double> entry : adp.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null || !StartingLineup.isSkillPosition(player.position)){
                continue;
            }
            if(entry.getValue() > SelectionModel.ADP_LIMIT || keptIDs.contains(entry.getKey())){
                continue;
            }
            board.add(entry.getKey());
        }

        DraftSimulator.Extras base = DraftSimulator.currentSeasonExtras(configuration);
        Map<String, Set<String>> keeperStacks = new HashMap<>();
        for(Keeper keeper : keepers){
            String team = base.teamOf().get(keeper.player.sleeperIDString);
            if(keeper.player.position.equals(Position.QB) && team != null
                    && keeper.humanWhoCanKeep != null){
                keeperStacks.computeIfAbsent(keeper.humanWhoCanKeep, u -> new HashSet<>())
                        .add(team);
            }
        }
        DraftSimulator.Extras extras = new DraftSimulator.Extras(base.teEarliness(),
                base.rbEarliness(), base.teamOf(), base.rookies(), base.adpSpreadCentered(),
                keeperStacks, base.formerPlayersByManager(), base.young(), Map.of());
        DraftSimulator simulator = new DraftSimulator(schedule, board, adp, points, rosters,
                model, earliness, extras);
        return new DraftPlanner(simulator, me, myKeeperIDs, points);
    }

    /** Justin's locked-but-not-yet-entered keepers, from -Pkeepers=A,B. */
    public static List<Keeper> keepersFromProperty(AAAConfiguration configuration){
        List<Keeper> myKeepers = new ArrayList<>();
        String keeperNames = System.getProperty("keepers", "");
        if(!keeperNames.isEmpty()){
            for(String name : keeperNames.split(",")){
                for(Keeper candidate : KeeperChooser.eligibleCandidates(configuration,
                        configuration.getMyID())){
                    if(candidate.player.lastName.equalsIgnoreCase(name.trim())){
                        myKeepers.add(candidate);
                    }
                }
            }
        }
        return myKeepers;
    }

    public void print(Plan plan, double lambda, double q){
        for(Stage stage : plan.stages()){
            System.out.printf("%nround %d (overall pick %d):%n",
                    stage.round(), stage.pickNumber());
            System.out.printf("   %-4s %8s %8s %10s%n", "POS", "mean", "p" + Math.round(q * 100),
                    "risk-adj");
            for(PositionValue option : stage.options()){
                System.out.printf("   %-4s %8.1f %8.1f %10.1f%s%n",
                        option.position(), option.mean(), option.p10(), option.riskAdjusted(),
                        option.position() == stage.chosen() ? "   <- take" : "");
            }
        }
        System.out.printf("%nplan %s%n", plan.positions());
        System.out.printf("expected best-nine %.1f (+/- %.1f), p%.0f %.1f, "
                        + "risk-adjusted (lambda %.2f) %.1f%n",
                plan.mean(), plan.standardError(), q * 100, plan.p10(), lambda,
                plan.riskAdjusted());

        System.out.println("\nwait-or-take, under this plan (per position: the player I'd wait"
                + "\nfor, how often he's gone by my next pick, the drop when he is):\n");
        System.out.printf("   %-6s %-4s %-24s %10s %12s%n",
                "PICK", "POS", "USUAL TARGET", "P(gone)", "drop if gone");
        for(SnipeRow row : plan.snipes()){
            System.out.printf("   %-6d %-4s %-24s %9.0f%% %12.1f%n",
                    row.pickNumber(), row.position(), row.usualTarget(),
                    row.probabilityGone() * 100, row.meanDropWhenGone());
        }
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int rollouts = Integer.getInteger("trials", 300);
        double lambda = Double.parseDouble(System.getProperty("risk", "0"));
        double q = Double.parseDouble(System.getProperty("quantile", "0.10"));

        List<Keeper> myKeepers = keepersFromProperty(configuration);
        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, lastCompleted);
        ChoiceModel model = BoostedSelectionModel.fitShipped(configuration, lastCompleted, earliness);
        DraftPlanner planner = forCurrentSeason(configuration, myKeepers, model, earliness);
        System.out.printf("nine-round plan, keepers %s, %d rollouts, lambda %.2f, quantile %.2f%n",
                myKeepers.isEmpty() ? "as declared on Sleeper"
                        : myKeepers.stream().map(keeper -> keeper.player.lastName
                                + " r" + keeper.roundCanBeKept).toList().toString(),
                rollouts, lambda, q);
        System.out.printf("my picks: %s%n", Arrays.toString(planner.myPickNumbers));
        Plan plan = planner.plan(rollouts, lambda, q, DraftSimulator.SEED);
        planner.print(plan, lambda, q);
    }

}
