import PlayerImportAndSetup.Position;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fair version of Phase 4.
 *
 * Phase 4 tested a fixed position sequence - one order derived from the 2026
 * board and replayed on five other seasons - and it lost to the committed plan.
 * That is a weaker claim than the model actually makes. The model is a POLICY:
 * at each pick, take the position whose best available man adds most to the
 * starter sum, given the roster so far. That is the thing to judge.
 *
 * Two changes make this honest where Phase 4 was flattered:
 *
 *   the policy drafts on each season's OWN board, choosing as it would have at
 *   the time rather than replaying a 2026 answer;
 *
 *   the outcome pool is LEAVE-ONE-OUT - judging 2023 uses distributions built
 *   from 2021, 2022, 2024 and 2025 only. Phase 4's model had seen every season
 *   it was tested on, which is why that row carried a caveat.
 *
 * Scoring uses no model at all: what those players really did, week by week, in
 * the league's real ten-man lineup.
 *
 *   ./gradlew run -Pmain=PolicyBacktest [-Pscenarios=300]
 */
public class PolicyBacktest {

    static final int[] MY_PICKS = PlanBacktest.MY_PICKS;

    public static void main(String[] args) throws Exception {
        int scenarios = Integer.getInteger("scenarios", 300);

        Map<String, PlanBacktest.Board> boards = new LinkedHashMap<>();
        Map<String, List<OutcomeDistributions.Season>> bySeason =
                OutcomeDistributions.all();
        for(File file : new File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                String season = file.getName().split("-")[3];
                PlanBacktest.Board board = PlanBacktest.board(file, season);
                if(board != null && board.ids().size() > 150){
                    boards.put(season, board);
                }
            }
        }
        List<String> seasons = new ArrayList<>(boards.keySet());
        seasons.sort(Comparator.naturalOrder());

        System.out.printf("%nPER-SEASON POLICY, LEAVE-ONE-OUT POOL%n");
        System.out.printf("drafts on each season's own board using distributions from"
                + " the OTHER four%nseasons, then is scored on what really"
                + " happened%n%n");
        System.out.printf("%-24s", "STRATEGY");
        for(String season : seasons){
            System.out.printf(" %8s", season);
        }
        System.out.printf(" %9s %9s %7s%n", "mean", "vs ADP", "wins");

        double[] policyScores = new double[seasons.size()];
        List<String> chosen = new ArrayList<>();
        for(int i = 0; i < seasons.size(); i++){
            String season = seasons.get(i);
            List<Position> plan = new ArrayList<>();
            policyScores[i] = runPolicy(boards.get(season),
                    poolWithout(bySeason, season), scenarios, plan);
            chosen.add(season + ": " + shape(plan));
        }

        Map<String, double[]> rows = new LinkedHashMap<>();
        rows.put("starter-sum POLICY", policyScores);
        for(Map.Entry<String, String> entry : PlanBacktest.STRATEGIES.entrySet()){
            double[] scores = new double[seasons.size()];
            for(int i = 0; i < seasons.size(); i++){
                scores[i] = PlanBacktest.score(boards.get(seasons.get(i)),
                        entry.getValue());
            }
            rows.put(entry.getKey(), scores);
        }
        double[] baseline = rows.get("best available by ADP");
        double baselineMean = java.util.Arrays.stream(baseline).average().orElse(0);
        for(Map.Entry<String, double[]> entry : rows.entrySet()){
            System.out.printf("%-24s", entry.getKey());
            int wins = 0;
            for(int i = 0; i < entry.getValue().length; i++){
                System.out.printf(" %8.0f", entry.getValue()[i]);
                if(entry.getValue()[i] > baseline[i]){
                    wins++;
                }
            }
            double mean = java.util.Arrays.stream(entry.getValue()).average().orElse(0);
            System.out.printf(" %9.0f %+9.0f %5d/%d%n", mean, mean - baselineMean,
                    wins, entry.getValue().length);
        }

        System.out.println("\nwhat the policy chose, season by season:");
        chosen.forEach(line -> System.out.println("   " + line));

        double[] committed = rows.get("RUNBOOK committed");
        double policyMean = java.util.Arrays.stream(policyScores).average().orElse(0);
        double committedMean = java.util.Arrays.stream(committed).average().orElse(0);
        int ahead = 0;
        for(int i = 0; i < policyScores.length; i++){
            if(policyScores[i] > committed[i]){
                ahead++;
            }
        }
        System.out.printf("%nagainst the committed plan: %+.0f points a season,"
                + " policy ahead in %d of %d.%n", policyMean - committedMean, ahead,
                policyScores.length);
    }

    /** Every season's outcomes except the one being judged. */
    public static Map<String, List<OutcomeDistributions.Season>> poolWithout(
            Map<String, List<OutcomeDistributions.Season>> bySeason, String excluded){
        Map<String, List<OutcomeDistributions.Season>> pool = new HashMap<>();
        for(Map.Entry<String, List<OutcomeDistributions.Season>> entry
                : bySeason.entrySet()){
            if(entry.getKey().equals(excluded)){
                continue;
            }
            for(OutcomeDistributions.Season season : entry.getValue()){
                pool.computeIfAbsent(season.position() + ":"
                        + (season.rank() / WeeklyStarterValue.TIER),
                        u -> new ArrayList<>()).add(season);
            }
        }
        return pool;
    }

    /** Draft greedily by marginal starter-sum value, then score on reality. */
    static double runPolicy(PlanBacktest.Board board,
                            Map<String, List<OutcomeDistributions.Season>> pool,
                            int scenarios, List<Position> plan){
        Map<String, Position> positionOf = new HashMap<>(board.positionOf());
        Map<String, Integer> tierOf = new HashMap<>();
        Map<Position, Integer> next = new EnumMap<>(Position.class);
        for(String id : board.ids()){
            tierOf.put(id, (next.merge(positionOf.get(id), 1, Integer::sum) - 1)
                    / WeeklyStarterValue.TIER);
        }
        WeeklyStarterValue value = new WeeklyStarterValue(positionOf, tierOf, pool,
                wireFrom(pool),
                WeeklyStarterValue.expectedFromRank(board.ids(), positionOf, pool),
                scenarios, 424_242L);

        Set<String> gone = new HashSet<>();
        List<String> mine = new ArrayList<>();
        Set<Integer> myPicks = new HashSet<>();
        for(int pick : MY_PICKS){
            myPicks.add(pick);
        }
        for(int pick = 1; pick <= 200 && mine.size() < MY_PICKS.length; pick++){
            if(myPicks.contains(pick)){
                final int at = mine.size();
                int taken = mine.size();
                int nextGap = taken + 1 < MY_PICKS.length
                        ? MY_PICKS[taken + 1] - MY_PICKS[taken] - 1 : 0;
                String best = null;
                Position bestPosition = null;
                double bestValue = -Double.MAX_VALUE;
                for(Position position : new Position[]{Position.QB, Position.RB,
                        Position.WR, Position.TE, Position.DEF}){
                    // same roster-legality floor the search uses: you start one
                    // defence and one quarterback, so a second defence can never
                    // play and a third quarterback is a wasted spot
                    if(!worthTaking(position, plan)){
                        continue;
                    }
                    String candidate = PlanBacktest.bestAvailable(board, gone, position);
                    if(candidate == null){
                        continue;
                    }
                    List<String> trial = new ArrayList<>(mine);
                    trial.add(candidate);
                    double now = value.of(trial);

                    // SCARCITY. Greedy takes whatever is worth most right now,
                    // which ignores that backs disappear faster than receivers -
                    // and that is precisely the edge the committed plan's
                    // RB-heavy opening exploits. What matters is not what a
                    // position is worth, but what it will have LOST by my next
                    // pick. Score each position by its decay: the best man there
                    // now, against the best man there after the board has been
                    // picked over until I choose again.
                    // Pure decay scored 1777 against greedy's 1859: it ignores
                    // the LEVEL, so a position that decays little but is worth
                    // much never gets taken. Level plus scarcity, weighted.
                    // the plan's pick starts ahead by DEVIATE; the model has to
                    // beat that margin to be allowed to disagree
                    double scored = now + (at < PRIOR.size() && PRIOR.get(at) == position
                            ? DEVIATE : 0.0);
                    if(SCARCITY > 0 && nextGap > 0){
                        String later = afterGap(board, gone, position, nextGap);
                        if(later != null){
                            List<String> deferred = new ArrayList<>(mine);
                            deferred.add(later);
                            scored = now + SCARCITY * (now - value.of(deferred));
                        }
                    }
                    if(scored > bestValue){
                        bestValue = scored;
                        best = candidate;
                        bestPosition = position;
                    }
                }
                if(best != null){
                    mine.add(best);
                    gone.add(best);
                    plan.add(bestPosition);
                }
            }
            else {
                String other = PlanBacktest.bestAvailableSkill(board, gone);
                if(other != null){
                    gone.add(other);
                }
            }
        }
        return PlanBacktest.seasonPoints(board, mine);
    }

    // A completion heuristic was tried here on 2026-08-29 and removed: filling
    // each trial roster's empty mandatory slots with the best men left made
    // every candidate's roster nearly identical, which masked the marginal it
    // was meant to measure. The policy collapsed to QB QB then twelve running
    // backs and scored 1055, below drafting at random. The greedy evaluation
    // needs real lookahead, not a stand-in.

    /**
     * How many points of model advantage it takes to leave the committed plan
     * (-Pdeviate). The plan is a PRIOR built on far more football than five
     * seasons, and five seasons cannot out-argue it - every attempt today to
     * replace it lost. So follow it, and deviate only where the model shows an
     * edge big enough to pay for the deviation.
     *
     * Huge means never deviate, which reproduces the committed plan exactly.
     * Zero means ignore the plan, which is the free-choice policy. The question
     * is whether anything in between beats both.
     */
    static final double DEVIATE =
            Double.parseDouble(System.getProperty("deviate", "1e9"));

    static final List<Position> PRIOR = new ArrayList<>();
    static {
        for(String token : PlanBacktest.STRATEGIES.get("RUNBOOK committed")
                .trim().split("\\s+")){
            PRIOR.add(Position.valueOf(token));
        }
    }

    /**
     * Pin the opening picks to a fixed shape (-PfrontShape="RB RB RB WR ...").
     *
     * The back-half decomposition put 91 of the model's 98-point deficit in
     * rounds 1-7, so this tests that directly: give it the committed plan's
     * front and let it choose everything after.
     */
    static final String FRONT_SHAPE = System.getProperty("frontShape", "");

    /**
     * How heavily to weight scarcity - what a position will have LOST by my
     * next pick - against its value now. 0 is pure greed, large is pure decay.
     */
    static final double SCARCITY =
            Double.parseDouble(System.getProperty("scarcity", "0"));

    /**
     * The best man at this position once the board has been picked over for the
     * gap between my picks - who I would be settling for if I wait.
     */
    static String afterGap(PlanBacktest.Board board, Set<String> gone, Position position,
                           int gap){
        Set<String> later = new HashSet<>(gone);
        for(int i = 0; i < gap; i++){
            String next = PlanBacktest.bestAvailableSkill(board, later);
            if(next == null){
                break;
            }
            later.add(next);
        }
        return PlanBacktest.bestAvailable(board, later, position);
    }

    /** Earliest pick index at which a quarterback may be taken (-PqbFrom). */
    static final int QB_FROM = Integer.getInteger("qbFrom", 0);

    /** Reserve the last pick for the defence that must be fielded (-PdefLast). */
    static final boolean DEF_LAST = Boolean.getBoolean("defLast");

    public static boolean worthTaking(Position candidate, List<Position> chosen){
        int at = chosen.size();
        if(!FRONT_SHAPE.isBlank()){
            String[] pinned = FRONT_SHAPE.trim().split("\\s+");
            if(at < pinned.length){
                return candidate == Position.valueOf(pinned[at]);
            }
        }
        if(DEF_LAST){
            if(at == MY_PICKS.length - 1){
                return candidate == Position.DEF;
            }
            if(candidate == Position.DEF){
                return false;
            }
        }
        if(candidate == Position.QB && at < QB_FROM){
            return false;
        }
        if(candidate != Position.DEF && candidate != Position.QB){
            return true;
        }
        int held = 0;
        for(Position position : chosen){
            if(position == candidate){
                held++;
            }
        }
        return candidate == Position.DEF ? held < 1 : held < 2;
    }

    public static Map<Position, Double> wireFrom(
            Map<String, List<OutcomeDistributions.Season>> pool){
        Map<Position, Integer> replacement =
                InsuranceTest.replacementRanks(AAAConfiguration.getInstance());
        Map<Position, Double> wire = new EnumMap<>(Position.class);
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR,
                Position.TE, Position.DEF}){
            int from = replacement.getOrDefault(position,
                    position == Position.DEF ? 13 : 24);
            List<Double> rates = new ArrayList<>();
            for(List<OutcomeDistributions.Season> cell : pool.values()){
                for(OutcomeDistributions.Season s : cell){
                    if(s.position() == position && s.rank() >= from - 1
                            && s.rank() < from - 1 + 24){
                        rates.add(s.meanWhenPlaying() * s.games() / 18.0);
                    }
                }
            }
            if(rates.isEmpty()){
                wire.put(position, 0.0);
                continue;
            }
            rates.sort(Comparator.reverseOrder());
            wire.put(position, rates.subList(0, Math.max(1, rates.size() / 4)).stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0));
        }
        return wire;
    }

    static String shape(List<Position> plan){
        StringBuilder text = new StringBuilder();
        for(Position position : plan){
            text.append(text.length() == 0 ? "" : " ").append(position);
        }
        return text.toString();
    }
}
