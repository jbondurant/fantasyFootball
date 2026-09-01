import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * The three pieces joined: how fast a position falls away, what it is worth,
 * and what is already on the roster.
 *
 * Justin named the gap precisely - "the matrix is within-position by
 * construction: it knows how fast a position falls away, never how much the
 * position is worth, and nothing about what's already on your roster." Each
 * piece existed separately. This is the join, and it keeps his constraint: two
 * numbers a season, a man's preseason rank and what he scored, no weeks and no
 * injury channel.
 *
 * THE CURRENCY IS MARGINAL LINEUP POINTS, because that is the only unit that is
 * simultaneously cross-position comparable and roster-aware:
 *
 *     marginal(him) = lineup(roster + him) - lineup(roster)
 *
 * where every man is valued at the MEAN SEASON POINTS men of his positional
 * rank have really scored over sixteen seasons - which already contains the
 * seasons they busted or got hurt, so nothing has to model that separately.
 * RankDraft priced the same wait in raw points and got it wrong, scoring 1825
 * against 1893, because a quarterback's rank curve is steeper in absolute
 * points and the rule over-bought quarterbacks. A marginal against a filled
 * lineup fixes that for free: a second quarterback is worth nothing because
 * there is one quarterback slot, and no replacement level has to be chosen by
 * hand.
 *
 * AND THE MATRIX SUPPLIES THE WAIT. Value alone says take the best man; the
 * matrix says how much of him survives to the next pick. So the rule is
 *
 *     take the position maximising  marginal(best now) - marginal(best later)
 *
 * with "later" read off the live ADP the same way the matrix reads it.
 *
 *   ./gradlew run -Pmain=BoardValue [-PholdKeepers=true]
 */
public class BoardValue {

    /** Bench men, priced from what this league's real bench picks returned. */
    static final Map<Position, Integer> MOST = new EnumMap<>(Map.of(
            Position.QB, 2, Position.RB, 7, Position.WR, 8,
            Position.TE, 2, Position.DEF, 1));

    record Slot(Position position, int rank){}

    /**
     * HOW THE BENCH HALF GOT IN, after I wrongly said it could not.
     *
     * I first valued every man at the MEAN season points of his rank. That makes
     * a lineup deterministic, so a bench man is worth exactly zero, and every
     * cell from pick 103 read zero. I then tried a flat measured bench figure,
     * which priced a backup quarterback at 88 at every pick because those
     * figures are raw points and a quarterback's are inflated by six-point
     * passing touchdowns. Both were wrong, and I told Justin the honest fix
     * needed a failure channel he had ruled out.
     *
     * He had not. He ruled out INTRASEASON modelling - week by week, bust versus
     * injured. Whether a man's SEASON went badly is one of the two numbers he
     * allows, and it is already in the data.
     *
     * So the roster is no longer scored against a mean. It is scored against
     * each of the sixteen real seasons in turn, with every man taking what men
     * of his rank actually scored THAT season, and the lineup taking the best of
     * what is held. Depth then pays exactly when it should: a second back is
     * worth the seasons his rank beat the starter's and nothing in the seasons
     * it did not. No weeks, no bust-versus-injury distinction, no free
     * parameter - a man who missed half a year is simply a man who scored little.
     *
     * The one judgement is who fills the slot within a season. Taking the best
     * REALISED man assumes a manager who always ends up playing his better
     * player; taking the best EXPECTED man assumes one who never reacts. The
     * truth is between, so -PlineupByExpected prints the stingy end and the
     * difference is the bracket rather than a claim.
     *
     * DEFAULT IS THE GENEROUS END, and it is hindsight. With the flag off the
     * fill sorts on what each man DREW, so every number this file has ever
     * printed assumes a manager who always ended up starting the right one. That
     * is not a small assumption and it is the direction that flatters depth. The
     * flag is implemented in oneSeason below; between 2026-08-30 and 2026-08-31
     * this paragraph described it and nothing read it.
     */

    public static void main(String[] args) throws Exception {
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Map<Position, double[]> curve = RankDraft.pointsByRank(men);
        Map<Position, List<List<Double>>> pools = pools(men);
        Position[] shown = {Position.RB, Position.WR, Position.TE, Position.QB, Position.DEF};
        Map<Position, List<Double>> adp = RankDraft.board(shown);
        Map<Position, Double> overWire =
                BenchValue.overWireByPosition(AAAConfiguration.getInstance());
        int[] picks = {7, 18, 31, 42, 55, 66, 79, 90, 103, 114, 127, 162, 175, 186};

        System.out.printf("%nWHAT EACH POSITION IS WORTH, GIVEN WHAT I ALREADY HOLD%n%n");
        System.out.printf("%d seasons. mean season points by positional rank, marginal against%n"
                + "the lineup this league starts. no weeks, no injury channel.%n%n",
                order.size());

        List<Slot> roster = new ArrayList<>();
        if(PlanBacktest.holdKeepers()){
            roster.add(new Slot(Position.QB, 9));      // Purdy, QB9 on the board
            roster.add(new Slot(Position.RB, 23));     // Tuten, RB23
            System.out.printf("holding Purdy (QB9) and Tuten (RB23) from the start.%n%n");
        }

        System.out.printf("%-14s", "PICK");
        for(Position position : shown){
            System.out.printf(" %7s", position);
        }
        System.out.printf("   %s%n", "TAKE");

        Map<Position, Integer> have = new EnumMap<>(Position.class);
        List<Position> shape = new ArrayList<>();
        for(int i = 0; i < picks.length; i++){
            int next = i + 1 < picks.length ? picks[i + 1] : -1;
            System.out.printf("%4d -> %-7s", picks[i], next < 0 ? "end" : String.valueOf(next));
            Position take = null;
            double most = -1e9;
            for(Position position : shown){
                double gain = urgency(curve, pools, order.size(), adp, roster,
                        position, picks[i], next);
                System.out.printf(" %7s", Double.isNaN(gain) ? "-"
                        : String.format("%.0f", gain));
                if(have.getOrDefault(position, 0) >= MOST.get(position)){
                    continue;
                }
                double effective = Double.isNaN(gain) ? -1e8 : gain;
                if(RankDraft.mustTake(have, shape.size(), picks.length, position)){
                    effective = 1e9;
                }
                if(effective > most){
                    most = effective;
                    take = position;
                }
            }
            if(take == null){
                take = Position.WR;
            }
            roster.add(new Slot(take, RankDraft.depth(adp.get(take), picks[i])));
            have.merge(take, 1, Integer::sum);
            shape.add(take);
            System.out.printf("   %s%n", take);
        }

        StringBuilder rendered = new StringBuilder();
        for(Position position : shape){
            rendered.append(rendered.isEmpty() ? "" : " ").append(position);
        }
        System.out.printf("%nthe shape this produces: %s%n", rendered);
        System.out.printf("%nEach cell is what taking that position NOW is worth over taking it%n"
                + "at my next pick, in lineup points, given the men I already hold. A%n"
                + "second quarterback prices near zero on his own, without being told to.%n");

        System.out.printf("%n%s%nFIXED SHAPE AGAINST ADAPTIVE%n%s%n",
                "=".repeat(64), "=".repeat(64));
        System.out.printf("%nThe shape above is what this model does if the board falls exactly%n"
                + "at ADP. It never does. The model itself is a function of the roster and%n"
                + "of who is actually left, so it can be asked again at every pick - which%n"
                + "is the thing a fixed shape, the committed plan included, cannot do.%n%n");
        adaptive(curve, pools, order.size());

        System.out.printf("%n%s%nIS IT ANY GOOD?%n%s%n", "=".repeat(64), "=".repeat(64));
        PlanBacktest.STRATEGIES.put("board value", rendered.toString());
        PlanBacktest.main(new String[0]);
    }

    /**
     * The same model, asked again at every pick against the board as it really
     * fell.
     *
     * Positional depth comes from who has actually gone rather than from ADP, so
     * a run on receivers pushes the model onto backs by itself. Nothing here is
     * new - it is urgency() called with a live count instead of an assumed one.
     */
    static void adaptive(Map<Position, double[]> curve,
                         Map<Position, List<List<Double>>> pools, int count) throws Exception {
        List<PlanBacktest.Board> boards = new ArrayList<>();
        for(java.io.File file : new java.io.File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                boards.add(PlanBacktest.board(file, file.getName().split("-")[3]));
            }
        }
        boards.sort(Comparator.comparing(PlanBacktest.Board::season));
        System.out.printf("%-8s %9s   %s%n", "SEASON", "POINTS", "WHAT IT TOOK");
        double total = 0;
        for(PlanBacktest.Board board : boards){
            List<String> roster = adaptiveDraft(board, curve, pools, count);
            double points = PlanBacktest.seasonPoints(board, roster);
            total += points;
            StringBuilder shape = new StringBuilder();
            for(String id : roster){
                Position position = board.positionOf().get(id);
                shape.append(shape.isEmpty() ? "" : " ").append(position);
            }
            System.out.printf("%-8s %9.0f   %s%n", board.season(), points, shape);
        }
        System.out.printf("%-8s %9.0f%n", "mean", boards.isEmpty() ? 0 : total / boards.size());
    }

    /** One adaptive draft on one real board. */
    static List<String> adaptiveDraft(PlanBacktest.Board board, Map<Position, double[]> curve,
                                      Map<Position, List<List<Double>>> pools, int count){
        Set<String> gone = new HashSet<>();
        List<String> mine = new ArrayList<>();
        List<Slot> held = new ArrayList<>();
        if(PlanBacktest.holdKeepers()){
            for(String id : PlanBacktest.keeperIDs(board)){
                gone.add(id);
                mine.add(id);
                Position position = board.positionOf().get(id);
                held.add(new Slot(position, taken(board, gone, position)));
            }
        }
        Map<Position, Integer> have = new EnumMap<>(Position.class);
        // Every candidate goes through RosterRules before it is priced. The
        // unconstrained version of this loop scored 1924 and drafted TE TE QB QB
        // in two of five seasons; the question this answers is whether the
        // nonsense was what cost it.
        RosterRules rules = RosterRules.live();
        RosterRules.Roster legal = PlanBacktest.holdKeepers()
                ? rules.justins() : rules.empty();
        Set<Integer> myPicks = new HashSet<>();
        for(int pick : PlanBacktest.MY_PICKS){
            myPicks.add(pick);
        }
        int made = 0;
        for(int pick = 1; pick <= 200 && made < PlanBacktest.MY_PICKS.length; pick++){
            if(!myPicks.contains(pick)){
                String other = PlanBacktest.bestAvailableSkill(board, gone);
                if(other != null){
                    gone.add(other);
                }
                continue;
            }
            // LOOKAHEAD, because urgency was the wrong objective.
            //
            // The greedy rule took the position whose value was about to drop
            // fastest, and that is not the same as the position that leads to
            // the best team. A tight end's curve falls off a cliff early, so
            // urgency kept buying tight ends at rounds 2 and 4 - premium picks
            // on a slot only one man can start in - while a receiver with a
            // shallower drop and far more value waited. Steepest drop is a
            // property of a curve; best roster is the thing actually wanted.
            //
            // So each legal position is TAKEN, the rest of the draft is rolled
            // out greedily by what each pick ADDS, and the finished roster is
            // valued. The position whose finished roster is best wins. One ply
            // of lookahead with a greedy tail - the same shape Model A uses in
            // the rounds where it beats everything here.
            Position take = null;
            double most = -1e9;
            for(Position position : new Position[]{Position.RB, Position.WR,
                    Position.TE, Position.QB, Position.DEF}){
                if(have.getOrDefault(position, 0) >= MOST.get(position)
                        || PlanBacktest.bestAvailable(board, gone, position) == null
                        || !legal.canDraft(position, round(made))){
                    continue;
                }
                List<Slot> after = new ArrayList<>(held);
                after.add(new Slot(position, taken(board, gone, position)));
                Map<Position, Integer> counts = new EnumMap<>(have);
                counts.merge(position, 1, Integer::sum);
                RosterRules.Roster next = legal.canDraft(position, round(made))
                        ? legal.draft("x", position, round(made)) : legal;
                List<Slot> ended = rolloutRoster(board, curve, pools, count, after,
                        counts, next, made + 1);
                double[] both = stats(ended, pools, curve, count, false);
                // REFUSE THE FRAGILE ONE. A path whose bad world sits more than
                // the bar below its own average is rejected however good its
                // average is - Justin plays one season, not six hundred.
                if(tooFragile(both)){
                    continue;
                }
                if(both[0] > most){
                    most = both[0];
                    take = position;
                }
            }
            if(take == null){
                take = Position.WR;
            }
            String choice = PlanBacktest.bestAvailable(board, gone, take);
            if(choice == null){
                choice = PlanBacktest.bestAvailable(board, gone, null);
            }
            if(choice != null){
                held.add(new Slot(take, taken(board, gone, take)));
                mine.add(choice);
                gone.add(choice);
                have.merge(take, 1, Integer::sum);
                if(legal.canDraft(take, round(made))){
                    legal = legal.draft(choice, take, round(made));
                }
            }
            made++;
        }
        return mine;
    }

    /** How many of a position ADP expects gone by a pick, from the board's own order. */
    static int adpDepth(PlanBacktest.Board board, Position position, int pick){
        int count = 0;
        List<String> ids = board.ids();
        for(int i = 0; i < Math.min(pick, ids.size()); i++){
            if(board.positionOf().get(ids.get(i)) == position){
                count++;
            }
        }
        return count;
    }

    /**
     * Fill the remaining picks greedily by what each adds, and value the result.
     *
     * The tail is greedy on VALUE, not on urgency, because urgency is what put
     * tight ends in rounds 2 and 4. Board depth advances by ADP between picks,
     * which is the same per-position rate the rest of this model uses - a single
     * shared rate is the assumption that once drafted TE TE QB QB.
     */
    static double rollout(PlanBacktest.Board board, Set<String> gone,
                          Map<Position, double[]> curve,
                          Map<Position, List<List<Double>>> pools, int count,
                          List<Slot> held, Map<Position, Integer> have,
                          RosterRules.Roster legal, int from){
        return empirical(rolloutRoster(board, curve, pools, count, held, have, legal, from),
                pools, curve, count);
    }

    /** The same rollout, handing back the finished roster so it can be judged twice. */
    static List<Slot> rolloutRoster(PlanBacktest.Board board,
                                    Map<Position, double[]> curve,
                                    Map<Position, List<List<Double>>> pools, int count,
                                    List<Slot> held, Map<Position, Integer> have,
                                    RosterRules.Roster legal, int from){
        List<Slot> roster = new ArrayList<>(held);
        Map<Position, Integer> mine = new EnumMap<>(have);
        for(int made = from; made < PlanBacktest.MY_PICKS.length; made++){
            int pick = PlanBacktest.MY_PICKS[made];
            Position best = null;
            double most = -1e9;
            double base = empirical(roster, pools, curve, count);
            for(Position position : new Position[]{Position.RB, Position.WR,
                    Position.TE, Position.QB, Position.DEF}){
                if(mine.getOrDefault(position, 0) >= MOST.get(position)
                        || !legal.canDraft(position, round(made))){
                    continue;
                }
                int rank = adpDepth(board, position, pick) + 1;
                double[] mean = curve.get(position);
                if(mean == null || rank >= mean.length){
                    continue;
                }
                List<Slot> trial = new ArrayList<>(roster);
                trial.add(new Slot(position, rank));
                double adds = empirical(trial, pools, curve, count) - base;
                if(adds > most){
                    most = adds;
                    best = position;
                }
            }
            if(best == null){
                break;
            }
            roster.add(new Slot(best, adpDepth(board, best, pick) + 1));
            mine.merge(best, 1, Integer::sum);
            if(legal.canDraft(best, round(made))){
                legal = legal.draft("x", best, round(made));
            }
        }
        return roster;
    }

    /** Which ROUND my nth pick is - 1-11 then 14-16, because 12 and 13 are keepers. */
    static int round(int made){
        return made < 11 ? made + 1 : made + 3;
    }

    /** How many of a position have already left this board, plus one. */
    static int taken(PlanBacktest.Board board, Set<String> gone, Position position){
        int count = 0;
        for(String id : gone){
            if(board.positionOf().get(id) == position){
                count++;
            }
        }
        return count + 1;
    }

    static double marginal(Map<Position, double[]> curve,
                           Map<Position, List<List<Double>>> pools, int count,
                           List<Slot> roster, Position position, int early, int later){
        double[] mean = curve.get(position);
        if(mean == null || early >= mean.length){
            return -1e8;
        }
        double base = empirical(roster, pools, curve, count);
        List<Slot> now = new ArrayList<>(roster);
        now.add(new Slot(position, early));
        double gainNow = empirical(now, pools, curve, count) - base;
        if(later >= mean.length || later <= early){
            return gainNow;
        }
        List<Slot> then = new ArrayList<>(roster);
        then.add(new Slot(position, later));
        return gainNow - (empirical(then, pools, curve, count) - base);
    }

    /** Marginal lineup points from taking him now rather than at my next pick. */
    static double urgency(Map<Position, double[]> curve, Map<Position, List<List<Double>>> pools,
                          int count, Map<Position, List<Double>> adp,
                          List<Slot> roster, Position position, int now, int next){
        List<Double> board = adp.get(position);
        double[] mean = curve.get(position);
        if(board == null || mean == null || board.isEmpty()){
            return Double.NaN;
        }
        int early = RankDraft.depth(board, now);
        if(early < 1 || early >= mean.length){
            return Double.NaN;
        }
        double base = empirical(roster, pools, curve, count);
        List<Slot> withNow = new ArrayList<>(roster);
        withNow.add(new Slot(position, early));
        double gainNow = empirical(withNow, pools, curve, count) - base;
        if(next < 0){
            return gainNow;
        }
        int late = RankDraft.depth(board, next);
        if(late >= mean.length){
            return gainNow;
        }
        List<Slot> withLater = new ArrayList<>(roster);
        withLater.add(new Slot(position, late));
        return gainNow - (empirical(withLater, pools, curve, count) - base);
    }

    /**
     * What men of this rank have actually scored - as a SET, not as an average.
     *
     * Two failed attempts got here. The raw one-man-per-cell table was savage:
     * a difference of differences over single observations priced a back at
     * pick 7 at MINUS 32. Averaging the neighbourhood fixed the noise and put
     * every bench cell back to zero, because the average of a rank's outcomes
     * cannot beat the average of a better rank's - and beating him sometimes is
     * the entire reason a bench man is worth a pick.
     *
     * So the neighbourhood is pooled rather than averaged: every season of every
     * rank within a log-rank window becomes one possible outcome for this rank.
     * That borrows strength for the noise and KEEPS the spread, which is the
     * only part that pays. A rank-40 back holds seasons that beat a rank-4
     * back's bad ones, and that is measured, not assumed.
     */
    static Map<Position, List<List<Double>>> pools(List<PairwiseOdds.Man> men){
        return pools(men, null);
    }

    /**
     * THIS YEAR'S CURVE, LAST SIXTEEN YEARS' UNCERTAINTY.
     *
     * Justin: "the training can learn from before but it shouldn't learn any
     * ordering... it should produce a different average list of positions each
     * season due to the curves of player projection dropoffs being different
     * for each position for each year."
     *
     * The version before this learned historical MEAN POINTS BY RANK and valued
     * a 2026 roster with it, which imports 2010-2025's average dropoff shape and
     * cannot produce a different answer in a year whose board is shaped
     * differently. It was learning the ordering, which is exactly what he says
     * it must not do.
     *
     * Split into the two things they are. History supplies the RELATIONSHIP: at
     * a given positional rank, how far do real outcomes scatter around what that
     * rank is worth - the spread, the busts, the deep men who beat starters.
     * That is stable across years and is what sixteen seasons can honestly
     * teach. The LEVEL and the SHAPE come from the board actually being drafted,
     * so this year's valleys are this year's.
     *
     * So a pool entry is a RATIO, not a point total: what men at this rank
     * really returned, divided by what that rank was centrally worth. Applied to
     * a 2026 projection, a rank whose history is volatile stays volatile and a
     * rank whose 2026 projection has fallen off a cliff shows the cliff.
     *
     * `level` null keeps the old behaviour, which the backtest needs - a
     * historical season must be valued with its own board, not with 2026's.
     */
    static Map<Position, List<List<Double>>> pools(List<PairwiseOdds.Man> men,
                                                   Map<Position, double[]> level){
        Map<Position, Map<Integer, List<Double>>> raw = new EnumMap<>(Position.class);
        Map<Position, double[]> centre = RankDraft.pointsByRank(men);
        for(PairwiseOdds.Man man : men){
            int cap = PairwiseOdds.CAP.getOrDefault(man.position(), 0);
            if(man.rank() > cap || man.rank() < 1){
                continue;
            }
            double points = man.points();
            if(level != null){
                // Store the ratio to what this rank was centrally worth, so the
                // pool carries SCATTER and not level.
                double[] mean = centre.get(man.position());
                double middle = mean != null && man.rank() < mean.length
                        ? mean[man.rank()] : 0;
                points = middle <= 0 ? 1.0 : points / middle;
            }
            raw.computeIfAbsent(man.position(), u -> new HashMap<>())
                    .computeIfAbsent(man.rank(), u -> new ArrayList<>()).add(points);
        }
        Map<Position, List<List<Double>>> out = new EnumMap<>(Position.class);
        for(Map.Entry<Position, Map<Integer, List<Double>>> entry : raw.entrySet()){
            int cap = PairwiseOdds.CAP.getOrDefault(entry.getKey(), 0);
            List<List<Double>> byRank = new ArrayList<>();
            for(int rank = 0; rank <= cap; rank++){
                List<Double> pool = new ArrayList<>();
                // A MINIMUM ABSOLUTE WIDTH, because a log window is far too
                // narrow at the top of the board.
                //
                // Justin: Robinson's odds against Gibbs should be much higher
                // than McCaffrey's, because Robinson is 2.3% below him and
                // McCaffrey is 14.6% below. They were not - Robinson came out
                // at 55%, ABOVE even money, despite projecting lower, which is
                // impossible from the projections alone.
                //
                // The cause was here. A +/-25% log window makes rank 1's pool
                // ranks 1-2 and rank 2's pool ranks 2-3, so adjacent men were
                // drawing their scatter from DIFFERENT distributions, and that
                // difference swamped a 2.3% gap. The pool's idiosyncrasy was
                // deciding, not the projections.
                //
                // Scatter does vary down a board, but not between RB1 and RB2.
                // Six ranks either side at the top means the whole first tier
                // shares one scatter estimate, so what separates two men there
                // is their projected points - which is the input Justin wants
                // driving this, and the thing Boris Chen's ADP-only method
                // cannot see at all.
                int half = Math.max(6, (int) Math.round(rank * 0.25));
                int from = Math.max(1, rank - half);
                int to = Math.min(cap, rank + half);
                for(int r = from; r <= to; r++){
                    pool.addAll(entry.getValue().getOrDefault(r, List.of()));
                }
                byRank.add(pool);
            }
            out.put(entry.getKey(), byRank);
        }
        return out;
    }

    /** Scenarios drawn once and held, so two rosters always meet the same worlds. */
    static final int WORLDS = 600;

    /**
     * One man's outcome in one world.
     *
     * Keyed on his position and rank rather than on where he sits in the roster,
     * so adding a man to a roster never changes what anybody else drew. That is
     * what makes a marginal a marginal rather than sampling noise - the same
     * common-random-numbers discipline the rest of this repo uses.
     */
    static double drawn(Map<Position, List<List<Double>>> pools, Position position,
                        int rank, int world, Map<Position, double[]> curve){
        return drawn(pools, position, rank, world, curve, false);
    }

    /**
     * One man's outcome in one world.
     *
     * With `ratios` the pool holds scatter rather than points, so the draw is
     * multiplied by what THIS year's board says the rank is worth. That is the
     * whole point of the split: the uncertainty is sixteen years old and the
     * curve is this morning's.
     */
    static double drawn(Map<Position, List<List<Double>>> pools, Position position,
                        int rank, int world, Map<Position, double[]> curve, boolean ratios){
        double[] mean = curve.get(position);
        double level = mean != null && rank < mean.length ? mean[rank] : 0;
        List<List<Double>> byRank = pools.get(position);
        if(byRank == null || rank >= byRank.size() || byRank.get(rank).isEmpty()){
            return level;
        }
        List<Double> pool = byRank.get(rank);
        int index = Math.floorMod(world * 2654435761L
                + position.ordinal() * 40503L + rank * 2246822519L, pool.size());
        double draw = pool.get(index);
        return ratios ? draw * level : draw;
    }

    /**
     * The roster against every real season, not against an average of them.
     *
     * This is where depth earns its keep. In a season the man at rank 4 fell
     * over, the man at rank 40 fills the slot and the roster keeps its points;
     * in a season he did not, the deep man contributes nothing. Averaging those
     * sixteen answers prices a bench pick correctly without ever asking WHY the
     * starter failed, or in which week.
     */
    static double empirical(List<Slot> roster, Map<Position, List<List<Double>>> pools,
                            Map<Position, double[]> curve, int count){
        return empirical(roster, pools, curve, count, false);
    }

    static double empirical(List<Slot> roster, Map<Position, List<List<Double>>> pools,
                            Map<Position, double[]> curve, int count, boolean ratios){
        return stats(roster, pools, curve, count, ratios)[0];
    }

    /**
     * How fragile a roster is: the gap between its average world and a bad one.
     *
     * Justin's choice, asked directly - maximise the mean, but refuse a plan
     * whose worst season falls far below its own average. So the mean alone is
     * no longer enough to rank on, and a roster now reports {mean, tenth
     * percentile}. The tenth rather than the true minimum because a minimum over
     * six hundred resampled worlds is one unlucky draw, and optimising against
     * one draw is how a model starts chasing noise.
     */
    static double[] stats(List<Slot> roster, Map<Position, List<List<Double>>> pools,
                          Map<Position, double[]> curve, int count, boolean ratios){
        double[] worlds = new double[WORLDS];
        double total = 0;
        for(int world = 0; world < WORLDS; world++){
            worlds[world] = oneSeason(roster, pools, curve, world, ratios);
            total += worlds[world];
        }
        double[] sorted = worlds.clone();
        java.util.Arrays.sort(sorted);
        return new double[]{ total / WORLDS, sorted[Math.max(0, WORLDS / 10)] };
    }

    /**
     * How far a plan's bad world may sit below its own average before it is
     * refused. 300, and the number was measured rather than chosen.
     *
     * Justin asked for "mean, but reject fragile plans" and I offered ~150 in
     * the asking. That number was mine and it was wrong by a factor of two: the
     * committed plan's own gap between its mean, 2033, and its worst season,
     * 1817, is over 200, so a 150 bar refuses the plan itself. It refuses every
     * real roster, and what survives is the degenerate one - fourteen receivers,
     * flat because they are interchangeable, scoring 1638.
     *
     * Swept: the bar is a no-op from 250 upward and the mean holds at 2050. So
     * this is a guard against a genuinely fragile plan, not a lever, and it is
     * set where it does not bind on anything the model currently builds. If it
     * ever starts binding, that is a signal worth reading rather than a number
     * worth lowering.
     */
    static double fragilityBar(){
        return Double.parseDouble(System.getProperty("fragile", "0.15"));
    }

    /**
     * Is this roster too fragile to take, as a FRACTION of its own mean?
     *
     * The bar was absolute points and that was a units bug of the exact kind
     * this repo keeps finding. The backtest values a roster in historical
     * actuals, about 130 a man; LiveBoard values it in 2026 projections, about
     * 300 a man. A 300-point bar was a harmless no-op in the first and refused
     * the best back on the board in the second, which is how it was caught -
     * the same guard behaving differently in two places is always a unit
     * disagreement, never a finding.
     *
     * A fraction of the roster's own mean is scale-free, so it means the same
     * thing wherever it is asked. Fifteen per cent is where the sweep showed it
     * stops binding on anything the model builds.
     */
    static boolean tooFragile(double[] stats){
        return stats[0] > 0 && (stats[0] - stats[1]) > fragilityBar() * stats[0];
    }

    /**
     * -PlineupByExpected: fill the slots by EXPECTATION, score them on the DRAW.
     *
     * The comment at the head of this file has promised this flag since the
     * bench half went in - "the truth is between, so -PlineupByExpected prints
     * the stingy end and the difference is the bracket rather than a claim" -
     * and until 2026-08-31 nothing read it. build.gradle forwarded the name, so
     * -PlineupByExpected=true was accepted, forwarded and ignored, and the
     * bracket the comment describes had never been printed. That is TRAPS.md F27
     * exactly: prose describing a mechanism the code does not implement.
     *
     * Off by default, so every number computed so far is unchanged: with the
     * flag absent the fill sorts on the drawn points, which is what it has
     * always done. On, it sorts on the mean of the man's rank - what you knew
     * before the season - and still counts what he drew, which is the stingy end
     * of the bracket and the hindsight-free one.
     */
    static final boolean BY_EXPECTED = Boolean.getBoolean("lineupByExpected");

    static double oneSeason(List<Slot> roster, Map<Position, List<List<Double>>> pools,
                            Map<Position, double[]> curve, int world){
        return oneSeason(roster, pools, curve, world, false);
    }

    static double oneSeason(List<Slot> roster, Map<Position, List<List<Double>>> pools,
                            Map<Position, double[]> curve, int world, boolean ratios){
        // {what his rank promised, what he drew}. Both are needed the moment the
        // lineup may be set on one and scored on the other.
        Map<Position, List<double[]>> pool = new EnumMap<>(Position.class);
        for(Slot slot : roster){
            double[] mean = curve.get(slot.position());
            double expected = mean != null && slot.rank() >= 0 && slot.rank() < mean.length
                    ? mean[slot.rank()] : 0;
            pool.computeIfAbsent(slot.position(), u -> new ArrayList<>())
                    .add(new double[]{expected,
                            drawn(pools, slot.position(), slot.rank(), world, curve, ratios)});
        }
        Comparator<double[]> best = BY_EXPECTED
                ? Comparator.comparingDouble((double[] man) -> man[0]).reversed()
                : Comparator.comparingDouble((double[] man) -> man[1]).reversed();
        for(List<double[]> values : pool.values()){
            values.sort(best);
        }
        double total = 0;
        List<double[]> flex = new ArrayList<>();
        total += fillDrawn(pool, Position.QB, 1, curve, flex, false);
        total += fillDrawn(pool, Position.RB, 2, curve, flex, true);
        total += fillDrawn(pool, Position.WR, 3, curve, flex, true);
        total += fillDrawn(pool, Position.TE, 1, curve, flex, true);
        total += fillDrawn(pool, Position.DEF, 1, curve, flex, false);
        flex.sort(best);
        for(int slot = 0; slot < 2; slot++){
            total += slot < flex.size() ? flex.get(slot)[1] : replacement(curve, Position.RB);
        }
        return total;
    }

    /** The greedy fill over {expected, drawn} pairs; the points counted are the draw. */
    static double fillDrawn(Map<Position, List<double[]>> pool, Position position, int slots,
                            Map<Position, double[]> curve, List<double[]> flex,
                            boolean flexes){
        List<double[]> have = pool.getOrDefault(position, List.of());
        double total = 0;
        for(int slot = 0; slot < slots; slot++){
            total += slot < have.size() ? have.get(slot)[1] : replacement(curve, position);
        }
        if(flexes){
            for(int extra = slots; extra < have.size(); extra++){
                flex.add(have.get(extra));
            }
        }
        return total;
    }

    /**
     * The best legal lineup this league starts, valued at mean points by rank.
     *
     * An unfilled slot is NOT zero - the league still fields somebody there, off
     * the wire. It is scored at the mean man one past what this league leaves
     * undrafted, which is the same replacement idea the rest of the repo uses,
     * only measured rather than chosen.
     */
    static double lineup(List<Slot> roster, Map<Position, double[]> curve){
        Map<Position, List<Double>> pool = new EnumMap<>(Position.class);
        for(Slot slot : roster){
            double[] mean = curve.get(slot.position());
            if(mean == null || slot.rank() >= mean.length){
                continue;
            }
            pool.computeIfAbsent(slot.position(), u -> new ArrayList<>()).add(mean[slot.rank()]);
        }
        for(List<Double> values : pool.values()){
            values.sort(Comparator.reverseOrder());
        }
        double total = 0;
        List<Double> flex = new ArrayList<>();
        total += fill(pool, Position.QB, 1, curve, flex, false);
        total += fill(pool, Position.RB, 2, curve, flex, true);
        total += fill(pool, Position.WR, 3, curve, flex, true);
        total += fill(pool, Position.TE, 1, curve, flex, true);
        total += fill(pool, Position.DEF, 1, curve, flex, false);
        flex.sort(Comparator.reverseOrder());
        for(int slot = 0; slot < 2; slot++){
            total += slot < flex.size() ? flex.get(slot)
                    : replacement(curve, Position.RB);
        }
        return total;
    }

    static double fill(Map<Position, List<Double>> pool, Position position, int slots,
                       Map<Position, double[]> curve, List<Double> flex, boolean flexes){
        List<Double> have = pool.getOrDefault(position, List.of());
        double total = 0;
        for(int slot = 0; slot < slots; slot++){
            total += slot < have.size() ? have.get(slot) : replacement(curve, position);
        }
        if(flexes){
            for(int extra = slots; extra < have.size(); extra++){
                flex.add(have.get(extra));
            }
        }
        return total;
    }

    /** The mean man just past where this league stops drafting the position. */
    static double replacement(Map<Position, double[]> curve, Position position){
        double[] mean = curve.get(position);
        if(mean == null){
            return 0;
        }
        int rank = switch(position){
            case QB -> 21; case RB -> 61; case WR -> 81; case TE -> 19; default -> 13;
        };
        return rank < mean.length ? mean[rank] : mean[mean.length - 1];
    }
}
