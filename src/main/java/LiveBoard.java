import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * The model at the table: this board, this roster, right now.
 *
 * Everything before this priced a HYPOTHETICAL board. BoardValue's shape assumed
 * the draft falls at ADP, which it never does; its adaptive arm read a
 * historical board; PairwiseOdds answers about pick numbers rather than about
 * the men actually left. This reads the live draft, takes the roster Justin
 * actually holds, and prices what is actually on the board.
 *
 * WHAT IT WILL NOT DO, and cannot. Every candidate is offered to
 * RosterRules.canDraft before it is priced, so the nonsense in TRAPS.md is not
 * avoided by good behaviour - it is unreachable. A third quarterback, a second
 * one before round 10, a seventeenth man, a pick in a keeper round, a plan that
 * strands the lineup: none of them can be produced, because the roster type
 * refuses to represent them.
 *
 * HOW IT PRICES. Marginal lineup points, the only currency that is at once
 * cross-position comparable and roster-aware:
 *
 *     value = mean over 600 worlds of the best legal lineup,
 *             each man drawn from what men of his POSITIONAL RANK really scored
 *
 * Ranks come from the live board - how many of that position have actually gone
 * - not from ADP. Depth is priced because those worlds are drawn from a pooled
 * distribution rather than a mean, so a deep back who beats a hurt starter in
 * some seasons is worth something in exactly those seasons. No weeks anywhere:
 * two numbers a season, his August rank and his February total.
 *
 * AND THE WAIT. Value alone says take the best man. What matters is the
 * difference between taking him now and taking whoever is left at the next pick,
 * with the drain rate for each position measured from this board rather than
 * assumed equal - assuming it equal is what made the first adaptive arm draft
 * TE TE QB QB.
 *
 *   ./gradlew run -Pmain=LiveBoard -Pkeepers=Tuten,Purdy [-PdraftId=<id>]
 */
public class LiveBoard {

    public static void main(String[] args) throws Exception {
        System.setProperty("scheduleRounds", System.getProperty("scheduleRounds", "16"));
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String draftID = System.getProperty("draftId", configuration.getDraftID());

        List<Keeper> myKeepers = DraftPlanner.keepersFromProperty(configuration);
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, myKeepers,
                choice, earliness);
        DraftSimulator simulator = planner.simulator();

        // Sixteen seasons of what men at each rank really returned.
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        // THIS YEAR'S CURVE. The level and the shape come from the 2026 board
        // being drafted, not from a sixteen-year average of boards that are not
        // it. A year where the backs fall off a cliff at RB8 and a year where
        // they glide to RB30 must not produce the same list, and they did while
        // this read historical mean points by rank.
        Map<Position, double[]> curve = thisYear(planner);
        // LAST SIXTEEN YEARS' UNCERTAINTY, as ratios rather than points, so what
        // is imported is how far outcomes scatter around a rank - never where
        // the rank sits.
        Map<Position, List<List<Double>>> pools = BoardValue.pools(men, curve);

        List<String> taken = LiveDraft.livePicks(draftID);
        DraftSimulator.SimState state = simulator.stateAfter(taken);
        DraftSimulator.Slot slot = simulator.slotOf(state);

        List<String> mine = new ArrayList<>(planner.myKeeperIDs());
        for(String id : taken){
            Integer at = state.takenAtOf(id);
            if(at != null && simulator.slotAt(at) != null
                    && planner.me().equals(simulator.slotAt(at).manager())){
                mine.add(id);
            }
        }

        // Always price MY next pick, never whichever pick the draft happens to
        // be on. Before the draft starts slotOf() returns pick 1, which is not
        // Justin's, and the first version cheerfully priced taking a man at a
        // pick he does not own against waiting until one he does.
        int onPick = slot == null ? 200 : slot.pickNumber();
        int pick = onPick;
        for(int p = onPick; p <= 200; p++){
            DraftSimulator.Slot mineAt = simulator.slotAt(p);
            if(mineAt != null && planner.me().equals(mineAt.manager())){
                pick = p;
                break;
            }
        }
        DraftSimulator.Slot mineSlot = simulator.slotAt(pick);
        int round = mineSlot == null ? 16 : mineSlot.round();
        if(pick != onPick){
            System.out.printf("%n(the draft is on pick %d, which is not mine -"
                    + " pricing my next, pick %d)%n", onPick, pick);
        }
        System.out.printf("%nTHE BOARD AS IT IS%n%n");
        System.out.printf("pick %d, round %d, %d men gone. my roster (%d): %s%n",
                pick, round, taken.size(), mine.size(), shape(mine));

        // What the rules permit here, before anything is priced.
        RosterRules rules = RosterRules.live();
        RosterRules.Roster roster = rules.justins();
        for(String id : mine){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player == null || planner.myKeeperIDs().contains(id)){
                continue;
            }
            Integer at = state.takenAtOf(id);
            int taken_at = at == null ? 1 : simulator.slotAt(at).round();
            if(roster.canDraft(player.position, taken_at)){
                roster = roster.draft(player.firstName + " " + player.lastName,
                        player.position, taken_at);
            }
        }
        List<Position> legal = roster.legalAt(round);
        System.out.printf("the rules allow here: %s%n", legal);
        System.out.printf("still needed for a legal lineup: %s%n", roster.stillNeeds());
        System.out.printf("THE PLAN says: %s%n%n", Tomorrow.PLAN.getOrDefault(round, "-"));

        // THE BOARD AS IT WILL BE, not as it is.
        //
        // Justin: why are we looking at Gibbs and Nacua, those two will almost
        // never be available at my pick 7. Correct, and the tool was showing
        // them because the draft has not started - `taken` is empty, so "best
        // available" was literally the best man in football. At pick 7 six men
        // are gone, and planning against a board that will never exist is
        // worse than not planning.
        //
        // Real picks stay authoritative. Only the picks BETWEEN the live draft
        // and mine are filled in, and they are filled from ADP, which is the
        // same per-position rate the rest of this model uses. Once the draft
        // reaches my seat this does nothing at all.
        List<String> board = new ArrayList<>(taken);
        int assumed = 0;
        if(taken.size() < pick - 1){
            List<Map.Entry<String, Double>> byAdp = new ArrayList<>();
            for(String id : planner.points().keySet()){
                double adp = SleeperProjections.adpOf(id);
                if(adp < Double.MAX_VALUE && !board.contains(id)){
                    byAdp.add(Map.entry(id, adp));
                }
            }
            byAdp.sort(Map.Entry.comparingByValue());
            for(Map.Entry<String, Double> entry : byAdp){
                if(board.size() >= pick - 1){
                    break;
                }
                board.add(entry.getKey());
                assumed++;
            }
            System.out.printf("%d men actually gone, %d more assumed gone by ADP"
                    + " before pick %d%n", taken.size(), assumed, pick);
        }
        taken = board;

        // My roster as (position, rank) on the live board.
        List<BoardValue.Slot> held = new ArrayList<>();
        for(String id : mine){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null){
                held.add(new BoardValue.Slot(player.position,
                        depth(planner, taken, player.position, id)));
            }
        }

        int next = nextPickAfter(simulator, state, planner, pick);
        System.out.printf("%-5s %-24s %6s %9s %8s %7s %7s %14s%n", "POS", "BEST AVAILABLE",
                "RANK", "ADDS NOW", "VS WAIT", "END", "SWING", "NEXT CLIFF");

        Map<Position, Double> urgency = new EnumMap<>(Position.class);
        Map<Position, Double> adds = new EnumMap<>(Position.class);
        Map<Position, String> best = new EnumMap<>(Position.class);
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE,
                Position.QB, Position.DEF}){
            String candidate = bestAvailable(planner, taken, position);
            if(candidate == null){
                continue;
            }
            String why = roster.whyNotDraft(position, round);
            if(why != null){
                Player player = Player.getPlayerFromSIDV2(candidate);
                System.out.printf("%-5s %-24s %8s %10s %10s   REFUSED: %s%n", position,
                        player == null ? candidate
                                : player.firstName + " " + player.lastName,
                        "-", "-", "-", why);
                continue;
            }
            int rank = depth(planner, taken, position, candidate);
            int later = next < 0 ? rank : rank + drain(planner, taken, position, pick, next);
            double base = BoardValue.empirical(held, pools, curve, order.size(), true);
            List<BoardValue.Slot> now = new ArrayList<>(held);
            now.add(new BoardValue.Slot(position, rank));
            double addsNow = BoardValue.empirical(now, pools, curve, order.size(), true) - base;
            List<BoardValue.Slot> then = new ArrayList<>(held);
            then.add(new BoardValue.Slot(position, later));
            double wait = addsNow
                    - (BoardValue.empirical(then, pools, curve, order.size(), true) - base);
            // Rank on the ROSTER I END WITH, not on the drop I avoid. Ranking
            // by the drop scored 1916 in backtest and bought tight ends in
            // round 2; rolling the rest of the draft out and valuing the
            // finished roster scored 2008, which ties the plan. The cliff still
            // prints, because it is what makes the number legible, but it no
            // longer decides on its own.
            // Justin's rule, asked and answered: maximise the mean, but refuse
            // a path whose bad world sits more than the bar below its own
            // average. Measured as a no-op above 250 on everything this model
            // builds, so it is a guard rather than a lever - if it ever starts
            // refusing things, that is worth reading.
            double[] both = rolloutStats(planner, taken, curve, pools, order.size(),
                    held, position, rank, pick);
            boolean fragile = BoardValue.tooFragile(both);
            double finished = fragile ? -1e9 : both[0];
            Valley cliff = nextValley(tiers(curve, pools, position,
                    Double.parseDouble(System.getProperty("tierBar", "0.40"))), rank);
            boolean crosses = cliff != null && later > cliff.afterRank();
            // The cliff, not the odds, is what decides this. Crossing one means
            // the man you come back to is on the far side of a real drop; not
            // crossing one means the board is flat there and waiting is cheap
            // however unlikely the later man is to be better.
            if(crosses){
                wait += cliff.drop() / 2;
            }
            urgency.put(position, finished);
            adds.put(position, addsNow);
            best.put(position, candidate);
            Player player = Player.getPlayerFromSIDV2(candidate);
            String where = cliff == null ? "none ahead"
                    : String.format("after %s%d%s", position, cliff.afterRank(),
                            crosses ? " CROSSED" : "");
            System.out.printf("%-5s %-24s %6d %9.1f %8.1f %7.0f %6.0f%% %14s %s%n",
                    position,
                    player == null ? candidate : player.firstName + " " + player.lastName,
                    rank, addsNow, wait, both[0], 100 * (both[0] - both[1]) / both[0],
                    where, fragile ? "REFUSED fragile" : "");
        }

        // Rank on the cost of waiting, and break ties on raw value - otherwise
        // an empty board, where nothing has drained yet and every wait costs
        // zero, is decided by whichever position the loop reached first.
        String verdict = urgency.entrySet().stream()
                .max(Comparator.<Map.Entry<Position, Double>>comparingDouble(
                                e -> Math.round(e.getValue() * 10) / 10.0)
                        .thenComparingDouble(e -> adds.getOrDefault(e.getKey(), 0.0)))
                .map(e -> e.getKey().toString())
                .orElse("nothing legal");
        System.out.printf("%n   the model takes: %s%n", verdict);
        System.out.printf("%nNEXT CLIFF is the one that decides this. A position's value does not%n"
                + "slide, it steps: the raw rank curve falls off at a few places and is%n"
                + "flat between them. CROSSED means my next pick lands on the far side of%n"
                + "the next step, which is the only version of 'expensive to wait' that%n"
                + "means anything. A smooth odds curve cannot show this - it is monotone%n"
                + "by construction and was measured flattening the real cliff by five%n"
                + "points - which is why the odds are an ingredient here and not the answer.%n");
        System.out.printf("%nADDS NOW is what he adds to the season your STARTERS score, over the%n"
                + "man the wire would give you. VS WAIT is that minus what the best of his%n"
                + "position would add at pick %s - so it is the cost of waiting, and it is%n"
                + "what to rank on. A position the rules refuse is never priced at all.%n",
                next < 0 ? "(none left)" : String.valueOf(next));
    }

    /**
     * Where a position falls off, and whether waiting walks you off it.
     *
     * Justin: the odds that the man at 18 beats the man at 7 are "useless on
     * their own, because positional scarcity and roster composition, valleys,
     * matter so much more". He is right, and the odds curve is the worst
     * possible instrument for this - it is monotone and smoothed by
     * construction, so it CANNOT represent a cliff, and it was measured
     * flattening the real one at the top of the board by five points.
     *
     * So valleys are found on the RAW mean-by-rank curve, unsmoothed, and a
     * drop counts as a valley when it is more than twice the typical drop for
     * that position. What matters is not how big the next drop is but whether
     * MY next pick is on the far side of it.
     */
    record Valley(int afterRank, double drop){}

    /**
     * Tiers the way Boris Chen builds them: groups that are statistically
     * INDISTINGUISHABLE, not groups separated by a big enough gap.
     *
     * Justin asked for cliffs that come out similar to or better than his tiers.
     * His method clusters players whose expert rankings overlap - two men are in
     * one tier when you cannot tell them apart, and a tier boundary is where you
     * can. That is a different question from "is this drop bigger than X", and
     * it is the right one: a 7-point gap between two men who each swing 60
     * points is nothing, and a 20-point gap between two men who barely swing is
     * a wall.
     *
     * A threshold on drops cannot express that, and mine did not: it called the
     * 7-point step after Gibbs a cliff and buried the 36.9-point step after
     * Robinson in a list of fifteen. The isotonic pass that was supposed to save
     * it is a no-op here - thisYear() sorts projections, so the curve is already
     * monotone and there is nothing for pool-adjacent-violators to pool. It
     * earns its keep on historical actuals, which are noisy, and does nothing on
     * a sorted projection curve.
     *
     * So this asks the question directly, using both halves of the model: what
     * are the odds the man one rank BELOW outscores the man above him, given
     * this year's projected gap and sixteen years of measured scatter at those
     * ranks? Near a half means the same tier. Well under a half means a wall.
     * No threshold on points anywhere - the units are probability, so a
     * quarterback and a defence are judged on the same scale.
     */
    static List<Valley> tiers(Map<Position, double[]> curve,
                              Map<Position, List<List<Double>>> pools, Position position,
                              double bar){
        double[] level = curve.get(position);
        if(level == null){
            return List.of();
        }
        // Against the TIER LEADER, not against the next man down.
        //
        // The first version compared each rank to rank+1 and found no tiers at
        // all in backs or receivers, which is correct and useless: adjacent
        // ranks are always near coin-flips once sixteen years of scatter are in
        // the comparison. Boris Chen does not ask whether you can tell RB7 from
        // RB8, he asks whether you can tell RB8 from the best man in RB8's
        // group. Difference accumulates down a tier until it is visible, and
        // where it becomes visible is the wall.
        List<Valley> walls = new ArrayList<>();
        int leader = 1;
        for(int rank = 2; rank < level.length; rank++){
            if(level[rank] <= 0 || level[leader] <= 0){
                continue;
            }
            int below = 0;
            for(int world = 0; world < BoardValue.WORLDS; world++){
                double best = BoardValue.drawn(pools, position, leader, world, curve, true);
                double him = BoardValue.drawn(pools, position, rank, world, curve, true);
                if(him > best){
                    below++;
                }
            }
            double beats = (double) below / BoardValue.WORLDS;
            if(beats < bar){
                // He is distinguishable from his leader, so the tier ended at
                // the man above him and he starts the next one.
                walls.add(new Valley(rank - 1, level[rank - 1] - level[rank]));
                leader = rank;
            }
        }
        return walls;
    }

    /** Valleys in THIS year's curve, isotonic so noise pools away and steps stay. */
    static List<Valley> valleys(Map<Position, double[]> curve, Position position){
        double[] raw = curve.get(position);
        if(raw == null){
            return List.of();
        }
        double[] mean = new double[Math.max(0, raw.length - 1)];
        double[] weight = new double[mean.length];
        for(int i = 0; i < mean.length; i++){
            mean[i] = raw[i + 1];
            weight[i] = raw[i + 1] > 0 ? 1 : 0;
        }
        double[] fitted = PairwiseOdds.isotonicDecreasing(mean, weight);
        List<Double> steps = new ArrayList<>();
        for(int i = 0; i + 1 < fitted.length; i++){
            double drop = fitted[i] - fitted[i + 1];
            if(drop > 0){
                steps.add(drop);
            }
        }
        if(steps.isEmpty()){
            return List.of();
        }
        Collections.sort(steps);
        double median = steps.get(steps.size() / 2);
        // A CLIFF IS BIG RELATIVE TO THE LEVEL, not merely above the 75th
        // percentile of steps.
        //
        // Justin, on being told the running back cliff was after RB1: "isn't
        // the cliff after gibbs and robinson". He was right and the detector
        // was wrong. A percentile bar found FIFTEEN valleys in a sixty-rank
        // curve, at which point the word means nothing, and it fired on the
        // 7-point step after Gibbs while the real 36.9-point step after
        // Robinson was just another entry in the list.
        //
        // 7.0 off 299.9 is two per cent and is rounding. 36.9 off 292.9 is
        // thirteen per cent and is a tier boundary. So a step qualifies only if
        // it is at least a twentieth of what the rank above it is worth AND
        // several times the typical step - the first test is what makes it a
        // cliff, the second stops a flat tail full of tiny numbers producing
        // proportionally large ones.
        List<Valley> found = new ArrayList<>();
        for(int i = 0; i + 1 < fitted.length; i++){
            double drop = fitted[i] - fitted[i + 1];
            if(fitted[i] <= 0){
                continue;
            }
            if(drop >= 0.05 * fitted[i] && drop >= 3 * median){
                found.add(new Valley(i + 1, drop));
            }
        }
        return found;
    }

    static List<Valley> valleysHistorical(List<PairwiseOdds.Man> men, Position position){
        int cap = PairwiseOdds.CAP.getOrDefault(position, 0);
        double[] total = new double[cap + 2];
        double[] seen = new double[cap + 2];
        for(PairwiseOdds.Man man : men){
            if(man.position() == position && man.rank() >= 1 && man.rank() <= cap){
                total[man.rank()] += man.points();
                seen[man.rank()]++;
            }
        }
        double[] mean = new double[cap + 1];
        double[] weight = new double[cap + 1];
        for(int rank = 1; rank <= cap; rank++){
            mean[rank - 1] = seen[rank] == 0 ? 0 : total[rank] / seen[rank];
            weight[rank - 1] = seen[rank];
        }
        // ISOTONIC, which is the smoother that does both jobs at once.
        //
        // Justin: "it should be smoothed to fix granularity of not having 100+
        // sample years, but it should be able to show valleys." Sixteen seasons
        // gives about sixteen men per rank, so the raw curve - which the first
        // version of this read - is mostly noise, and any two adjacent ranks
        // differ by more sampling error than football. But a moving average
        // fixes that by destroying exactly what we are looking for: it was
        // measured smearing the real cliff at the top of the board by five
        // points.
        //
        // Pool-adjacent-violators does neither. It forces the curve
        // non-increasing by merging ranks that disagree into FLAT RUNS, and it
        // leaves the drops between those runs exactly where they are. The flat
        // runs are the tiers and the steps between them are the valleys - so
        // the tier structure is not assumed or hand-drawn, it is what is left
        // after the noise is pooled away. Weighted by how many men each rank
        // actually had, so a thin rank cannot invent a cliff.
        double[] fitted = PairwiseOdds.isotonicDecreasing(mean, weight);

        List<Double> steps = new ArrayList<>();
        for(int i = 0; i + 1 < fitted.length; i++){
            double drop = fitted[i] - fitted[i + 1];
            if(drop > 0){
                steps.add(drop);
            }
        }
        if(steps.isEmpty()){
            return List.of();
        }
        Collections.sort(steps);
        // A step counts as a valley when it is bigger than three quarters of the
        // steps this position has - so "cliff" means steep FOR THIS POSITION
        // rather than steep in points, which would find them all at running back.
        double bar = Math.max(steps.get((int) (steps.size() * 0.75)), 4);
        List<Valley> found = new ArrayList<>();
        for(int i = 0; i + 1 < fitted.length; i++){
            double drop = fitted[i] - fitted[i + 1];
            if(drop >= bar){
                found.add(new Valley(i + 1, drop));
            }
        }
        return found;
    }

    /** The first cliff strictly after my current rank, if there is one. */
    static Valley nextValley(List<Valley> valleys, int rank){
        for(Valley valley : valleys){
            if(valley.afterRank() >= rank){
                return valley;
            }
        }
        return null;
    }

    /**
     * Take him, fill the rest of the draft greedily by value, and score the team.
     *
     * This is the rule that scored 2008 against the plan's 2050 - a tie - where
     * ranking by the drop scored 1916 and spent round 2 on a tight end. Board
     * depth for future picks advances at each position's OWN ADP rate, never a
     * shared one: assuming a shared rate is what once drafted TE TE QB QB.
     */
    static double[] rolloutStats(DraftPlanner planner, List<String> taken,
                                 Map<Position, double[]> curve,
                                 Map<Position, List<List<Double>>> pools, int count,
                                 List<BoardValue.Slot> held, Position first, int firstRank,
                                 int fromPick){
        return BoardValue.stats(rolloutRoster(planner, taken, curve, pools, count,
                held, first, firstRank, fromPick), pools, curve, count, true);
    }

    static double rollout(DraftPlanner planner, List<String> taken,
                          Map<Position, double[]> curve,
                          Map<Position, List<List<Double>>> pools, int count,
                          List<BoardValue.Slot> held, Position first, int firstRank,
                          int fromPick){
        List<BoardValue.Slot> roster = new ArrayList<>(held);
        roster.add(new BoardValue.Slot(first, firstRank));
        Map<Position, Integer> mine = new EnumMap<>(Position.class);
        for(BoardValue.Slot slot : roster){
            mine.merge(slot.position(), 1, Integer::sum);
        }
        int[] picks = {7, 18, 31, 42, 55, 66, 79, 90, 103, 114, 127, 162, 175, 186};
        for(int pick : picks){
            if(pick <= fromPick || roster.size() >= 16){
                continue;
            }
            double base = BoardValue.empirical(roster, pools, curve, count, true);
            Position best = null;
            double most = -1e9;
            int bestRank = 1;
            for(Position position : new Position[]{Position.RB, Position.WR,
                    Position.TE, Position.QB, Position.DEF}){
                if(mine.getOrDefault(position, 0) >= BoardValue.MOST.get(position)){
                    continue;
                }
                int rank = expectedRank(planner, taken, position, pick);
                double[] mean = curve.get(position);
                if(mean == null || rank >= mean.length){
                    continue;
                }
                List<BoardValue.Slot> trial = new ArrayList<>(roster);
                trial.add(new BoardValue.Slot(position, rank));
                double adds = BoardValue.empirical(trial, pools, curve, count, true) - base;
                if(adds > most){
                    most = adds;
                    best = position;
                    bestRank = rank;
                }
            }
            if(best == null){
                break;
            }
            roster.add(new BoardValue.Slot(best, bestRank));
            mine.merge(best, 1, Integer::sum);
        }
        return BoardValue.empirical(roster, pools, curve, count, true);
    }

    /** The same rollout, handing back the roster so it can be judged twice. */
    static List<BoardValue.Slot> rolloutRoster(DraftPlanner planner, List<String> taken,
                                               Map<Position, double[]> curve,
                                               Map<Position, List<List<Double>>> pools,
                                               int count, List<BoardValue.Slot> held,
                                               Position first, int firstRank, int fromPick){
        List<BoardValue.Slot> roster = new ArrayList<>(held);
        roster.add(new BoardValue.Slot(first, firstRank));
        Map<Position, Integer> mine = new EnumMap<>(Position.class);
        for(BoardValue.Slot slot : roster){
            mine.merge(slot.position(), 1, Integer::sum);
        }
        int[] picks = {7, 18, 31, 42, 55, 66, 79, 90, 103, 114, 127, 162, 175, 186};
        for(int pick : picks){
            if(pick <= fromPick || roster.size() >= 16){
                continue;
            }
            double base = BoardValue.empirical(roster, pools, curve, count, true);
            Position best = null;
            double most = -1e9;
            int bestRank = 1;
            for(Position position : new Position[]{Position.RB, Position.WR,
                    Position.TE, Position.QB, Position.DEF}){
                if(mine.getOrDefault(position, 0) >= BoardValue.MOST.get(position)){
                    continue;
                }
                int rank = expectedRank(planner, taken, position, pick);
                double[] mean = curve.get(position);
                if(mean == null || rank >= mean.length){
                    continue;
                }
                List<BoardValue.Slot> trial = new ArrayList<>(roster);
                trial.add(new BoardValue.Slot(position, rank));
                double adds = BoardValue.empirical(trial, pools, curve, count, true) - base;
                if(adds > most){
                    most = adds;
                    best = position;
                    bestRank = rank;
                }
            }
            if(best == null){
                break;
            }
            roster.add(new BoardValue.Slot(best, bestRank));
            mine.merge(best, 1, Integer::sum);
        }
        return roster;
    }

    /** How deep a position will be at a later pick, at its own ADP rate. */
    static int expectedRank(DraftPlanner planner, List<String> taken,
                            Position position, int pick){
        int gone = 0;
        for(String id : planner.points().keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            double adp = SleeperProjections.adpOf(id);
            if(player != null && player.position == position && adp < pick){
                gone++;
            }
        }
        return gone + 1;
    }

    /**
     * The 2026 projection curve: what each positional rank is worth THIS year.
     *
     * Justin: the model "should produce a different average list of positions
     * each season due to the curves of player projection dropoffs being
     * different for each position for each year". This is the input that makes
     * that true. Ranks are by projection within a position, so index r is what
     * the rth best man at that position is projected for on the board in front
     * of him - cliffs, plateaus and all.
     */
    static Map<Position, double[]> thisYear(DraftPlanner planner){
        Map<Position, List<Double>> byPosition = new EnumMap<>(Position.class);
        for(Map.Entry<String, Double> entry : planner.points().entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player != null && PairwiseOdds.CAP.containsKey(player.position)){
                byPosition.computeIfAbsent(player.position, u -> new ArrayList<>())
                        .add(entry.getValue());
            }
        }
        Map<Position, double[]> curve = new EnumMap<>(Position.class);
        for(Map.Entry<Position, List<Double>> entry : byPosition.entrySet()){
            List<Double> values = entry.getValue();
            values.sort(Comparator.reverseOrder());
            int cap = PairwiseOdds.CAP.get(entry.getKey());
            double[] out = new double[cap + 2];
            for(int rank = 1; rank <= cap && rank <= values.size(); rank++){
                out[rank] = values.get(rank - 1);
            }
            curve.put(entry.getKey(), out);
        }
        return curve;
    }

    /** How many of a position have gone, plus one - his rank on THIS board. */
    static int depth(DraftPlanner planner, List<String> taken, Position position, String him){
        int gone = 0;
        for(String id : taken){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null && player.position == position && !id.equals(him)){
                gone++;
            }
        }
        return gone + 1;
    }

    /**
     * How many more of this position go before my next pick.
     *
     * Two sources, blended, because neither survives alone. What the room has
     * ACTUALLY done is the right signal but at pick 7 it rests on six
     * observations, and at pick 1 on none - the first version of this returned
     * zero for every position at an empty board, which says waiting is free and
     * is obviously false. What ADP EXPECTS is available before a pick is made
     * and is the same per-position rate the historical arm uses.
     *
     * So ADP is the prior and the room is the evidence, with the room taking
     * over as the draft supplies it. The one thing that must not happen is a
     * single rate shared across positions: that assumption is what drafted
     * TE TE QB QB, and it is the only signal this model has.
     */
    static int drain(DraftPlanner planner, List<String> taken, Position position,
                     int pick, int next){
        int gone = 0;
        for(String id : taken){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null && player.position == position){
                gone++;
            }
        }
        double observed = taken.isEmpty() ? 0 : (double) gone / taken.size();
        double expected = adpRate(planner, position, pick, next);
        // Half weight to the room after about thirty picks, which is roughly
        // when a run on a position stops being three men in a row.
        double trust = taken.size() / (taken.size() + 30.0);
        double rate = trust * observed + (1 - trust) * expected;
        return Math.max(0, (int) Math.round(rate * (next - pick)));
    }

    /** The share of picks between here and my next that ADP gives this position. */
    static double adpRate(DraftPlanner planner, Position position, int pick, int next){
        int between = 0;
        for(String id : planner.points().keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            double adp = SleeperProjections.adpOf(id);
            if(player != null && player.position == position
                    && adp >= pick && adp < next){
                between++;
            }
        }
        return next <= pick ? 0 : (double) between / (next - pick);
    }

    static String bestAvailable(DraftPlanner planner, List<String> taken, Position position){
        Set<String> gone = new HashSet<>(taken);
        String best = null;
        double most = -1;
        for(Map.Entry<String, Double> entry : planner.points().entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null || player.position != position || gone.contains(entry.getKey())){
                continue;
            }
            if(entry.getValue() > most){
                most = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    static int nextPickAfter(DraftSimulator simulator, DraftSimulator.SimState state,
                             DraftPlanner planner, int pick){
        for(int at = pick + 1; at <= 200; at++){
            DraftSimulator.Slot slot = simulator.slotAt(at);
            if(slot != null && planner.me().equals(slot.manager())){
                return at;
            }
        }
        return -1;
    }

    static String shape(List<String> roster){
        StringBuilder out = new StringBuilder();
        for(String id : roster){
            Player player = Player.getPlayerFromSIDV2(id);
            out.append(out.isEmpty() ? "" : " ").append(player == null ? "?" : player.position);
        }
        return out.toString();
    }
}
