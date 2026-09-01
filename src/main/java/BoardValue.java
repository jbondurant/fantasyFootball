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
    /**
     * How many of each the rule will ever take. -PmaxTE overrides the tight end.
     *
     * ONE tight end, changed from two after Justin pushed twice.
     *
     * First: "does the model choose to pick 2 tight ends, I'm surprised we pick
     * more than 1." I answered that it was buying insurance on a single-slot
     * position and that the backtest could not tell 1 from 2 - 2023 against
     * 2018, seventeen points across caps of 1/2/3 and non-monotonic.
     *
     * Then: "I'm surprised a te 2 has much of an edge over the wire." That is
     * the objection that lands, and it exposes the flaw in my own answer. I had
     * valued the insurance against ZERO. It should be valued against the WIRE,
     * and the tight end tier this year is flat a long way down: replacement is
     * TE19, Darren Waller at 99.1, against Kincaid at 130.6. So the second
     * tight end is worth 31.5 points and only when the first one is lost, which
     * the harvest puts at 25% for tight ends:
     *
     *     31.5 x 0.25 = about 8 points of expectation
     *     paid roughly 10-15 points of projection to get it
     *
     * A bad trade, and his instinct found it where the backtest could not. The
     * default follows the ARITHMETIC and not the backtest, deliberately: the
     * backtest's own ranking puts maxTE 3 highest at 2035, which is noise, and
     * letting it choose would argue for three tight ends on a one-tight-end
     * lineup. -PmaxTE=2 restores the old behaviour.
     */
    static final Map<Position, Integer> MOST = new EnumMap<>(Map.of(
            Position.QB, Integer.getInteger("maxQB", 2),
            Position.RB, Integer.getInteger("maxRB", 7),
            Position.WR, Integer.getInteger("maxWR", 8),
            Position.TE, Integer.getInteger("maxTE", 1),
            Position.DEF, 1));

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
     * truth is between, so -PbestBall prints the hindsight end and the
     * difference is the bracket rather than a claim.
     *
     * THE DEFAULT IS THE HONEST END, and this paragraph said the opposite for
     * several hours after the code changed under it.
     *
     * It used to read "DEFAULT IS THE GENEROUS END, and it is hindsight... the
     * fill sorts on what each man DREW". That was true when written and false
     * from the moment BY_EXPECTED was flipped on: the fill now sorts on what
     * was EXPECTED and scores what was DRAWN. A reader trusting the old text
     * would have believed the default was the exact reverse of what it is.
     *
     * TRAPS.md F27, the fourth time in three days, and the one that has cost
     * this repo the most: a comment on the objective is what gets quoted when
     * somebody asks what the model believes, and three separate wrong answers
     * tonight came from quoting one. Left as a record rather than silently
     * corrected, because the pattern is the finding.
     *
     * -PbestBall restores the drawn-points fill, which is not a legacy switch
     * but the correct model for best ball, where the lineup really is chosen
     * after the week is played.
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
        // EVERYONE KEPT, off the board; MINE, onto the roster. With
        // -PleagueKeepers that is twenty-four and two; otherwise it is the two
        // that are both, and this reduces to what it always did - the two are
        // at different positions, so counting them together or one at a time
        // gives the same taken() either way.
        gone.addAll(PlanBacktest.offBoard(board));
        for(String id : PlanBacktest.heldByMe(board)){
            mine.add(id);
            Position position = board.positionOf().get(id);
            // His REAL rank on this board when asked for, rather than a count
            // of who is gone: Purdy is QB9, and taken() called here answers 2
            // because two men have left, not because anyone measured him. See
            // PlanBacktest.keeperRanks.
            held.add(new Slot(position, PlanBacktest.keeperRanks()
                    ? PlanBacktest.rankOn(board, id)
                    : taken(board, gone, position)));
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
        // Picks a keeper has already spent select nobody - see
        // PlanBacktest.spentPicks. Empty unless -PleagueKeepers.
        Set<Integer> spent = PlanBacktest.spentPicks();
        int made = 0;
        for(int pick = 1; pick <= 200 && made < PlanBacktest.MY_PICKS.length; pick++){
            if(!myPicks.contains(pick)){
                if(spent.contains(pick)){
                    continue;
                }
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
                // TWO PLY when -Pdepth=2: try every legal SECOND pick too and
                // keep the best, instead of handing the next pick straight to
                // the greedy tail. Justin has twelve seconds a pick where this
                // costs half of one, so the question is whether depth buys
                // anything that sampling did not.
                List<Slot> ended;
                if(LOOKAHEAD >= 2 && made + 1 < PlanBacktest.MY_PICKS.length){
                    ended = null;
                    double deepest = -1e9;
                    for(Position second : new Position[]{Position.RB, Position.WR,
                            Position.TE, Position.QB, Position.DEF}){
                        if(counts.getOrDefault(second, 0) >= MOST.get(second)
                                || !next.canDraft(second, round(made + 1))){
                            continue;
                        }
                        List<Slot> two = new ArrayList<>(after);
                        two.add(new Slot(second,
                                adpDepth(board, second, PlanBacktest.MY_PICKS[made + 1]) + 1));
                        Map<Position, Integer> deeper = new EnumMap<>(counts);
                        deeper.merge(second, 1, Integer::sum);
                        List<Slot> tail = rolloutRoster(board, curve, pools, count, two,
                                deeper, next.canDraft(second, round(made + 1))
                                        ? next.draft("y", second, round(made + 1)) : next,
                                made + 2);
                        double value = empirical(tail, pools, curve, count);
                        if(value > deepest){
                            deepest = value;
                            ended = tail;
                        }
                    }
                    if(ended == null){
                        ended = rolloutRoster(board, curve, pools, count, after,
                                counts, next, made + 1);
                    }
                }
                else {
                    ended = rolloutRoster(board, curve, pools, count, after,
                            counts, next, made + 1);
                }
                double[] both = stats(ended, pools, curve, count, false);
                // REFUSE THE FRAGILE ONE. A path whose bad world sits more than
                // the bar below its own average is rejected however good its
                // average is - Justin plays one season, not six hundred.
                // -Pfloor ranks on the TENTH PERCENTILE instead of filtering on
                // it. Justin's own formulation, arrived at by reading the swing
                // column: "is it essentially doing .84*2271 - .89*2233". Those
                // two products ARE the two floors, and comparing them directly
                // is strictly better design than my bar - it is continuous, so
                // nothing sits one point the wrong side of a threshold I chose,
                // and it needs no threshold at all.
                if(RANK_ON_FLOOR){
                    if(both[1] > most){
                        most = both[1];
                        take = position;
                    }
                    continue;
                }
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
                // LEGALITY ONLY IN THE TAIL, not appetite.
                //
                // Justin: the 3 TE model should be indicative of a problem with
                // the model, and we should fix that. He is right and it is not
                // the noise I called it. MOST is a hand-typed appetite - RB 7,
                // WR 8 - and it was consulted HERE, inside the greedy tail, and
                // the tail is how a candidate gets EVALUATED. So an arbitrary
                // parameter was contaminating the evaluation rather than merely
                // bounding the roster, and the symptom was a score that is
                // non-monotonic in a cap that never binds: maxTE 1 and 14 both
                // give 2023, while maxTE 3 gives 2035 by changing an RB/WR
                // decision in a season where the tight end count never differs.
                //
                // The tail now asks only whether a pick is LEGAL. What the
                // roster should want is the valuation's job, and if the
                // valuation cannot be trusted to decline a third tight end then
                // capping it is hiding the fault rather than fixing it.
                if(!legal.canDraft(position, round(made))){
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
    /**
     * Scenarios drawn once and held. -Pworlds raises it.
     *
     * Justin has more time than the half second a pick currently costs and
     * asked whether spending it improves anything. More worlds is the cheapest
     * thing to spend it on and also the least likely to help: the worlds are
     * RESAMPLED from sixteen seasons, so more of them estimate the same sixteen
     * seasons more precisely and cannot learn anything those seasons do not
     * contain. Measured rather than assumed.
     */
    static final int WORLDS = Integer.getInteger("worlds", 600);

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
    static final boolean RANK_ON_FLOOR = Boolean.getBoolean("floor");

    /**
     * How many picks the search looks ahead before the tail turns greedy.
     *
     * Named `lookahead`, not `depth`. Something in this JVM already owns a
     * system property called "depth" and sets it to 0, so -Pdepth=2 arrived as
     * "0" and the two-ply branch never ran - which is why depth 1 and depth 2
     * produced byte-identical results and differed by 0.4 seconds. The
     * allowlist test passes a flag like that happily, because the flag IS
     * forwarded; it is the VALUE that is stolen. Worth a test of its own.
     */
    static final int LOOKAHEAD = Integer.getInteger("lookahead", 1);

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
     * -PbestBall: fill the slots by the DRAW, which is what best ball does.
     *
     * The comment at the head of this file has promised this flag since the
     * bench half went in - "the truth is between, so -PbestBall prints
     * the stingy end and the difference is the bracket rather than a claim" -
     * and until 2026-08-31 nothing read it. build.gradle forwarded the name, so
     * -PbestBall=true was accepted, forwarded and ignored, and the
     * bracket the comment describes had never been printed. That is TRAPS.md F27
     * exactly: prose describing a mechanism the code does not implement.
     *
     * ON by default: the lineup is set on what was KNOWN, and scored on what
     * happened.
     *
     * Justin would not let the maxTE=3 result go - the 3 TE model should be
     * indicative of a problem, and we should fix that. It was. Filling by DRAWN
     * points means playing whichever tight end turned out better, which nobody
     * can do in August: TRAPS.md C12, the fault that has already reversed
     * several findings here. Under it every extra body is a lottery ticket
     * always cashed correctly, so more bodies are always better and the
     * appetite cap was the only thing stopping a third tight end. Capping it
     * was hiding the fault.
     *
     * Turning it on the first time BROKE the model: it drafted sixteen
     * receivers in two of five seasons, because a bench man chosen on preseason
     * expectation can never enter a lineup, so his marginal is exactly zero and
     * the search stops discriminating. That is not an argument for hindsight,
     * it is the discovery that this model had no honest reason to own a bench
     * at all. The availability channel in oneSeason is that reason, and with it
     * the cap sweep is monotone and converges - 1935, 1955, 1957, 1957 for
     * maxTE 1, 2, 3, 14 - where before it wandered non-monotonically.
     *
     * AND BEST BALL IS WHY THE OLD BEHAVIOUR SURVIVES AS A FLAG. Justin's
     * observation, and it is the useful half: in best ball the lineup IS chosen
     * retrospectively, so sorting on realised points is not hindsight there, it
     * is the rules. -PbestBall is not a legacy switch, it is the correct model
     * for a different format - honest in one game and cheating in the other.
     */
    static final boolean BY_EXPECTED = !Boolean.getBoolean("bestBall");

    /**
     * HOW A MANAGER SETS HIS LINEUP, as ONE parameterised family instead of two
     * unrelated switches. Extracted 2026-09-01 so the parameter could be FITTED
     * rather than chosen; see {@link BenchCalibration}.
     *
     * There are two forms, and they are the two Justin named:
     *
     *   THRESHOLD.  A man whose drawn season falls below `lostBelow` times what
     *     his rank normally returns counts as LOST and is benched outright; the
     *     lineup then fills by EXPECTATION from whoever is left. lostBelow 0 is
     *     nobody ever lost, which is the useless bench - a man chosen on
     *     preseason expectation can never enter a lineup, so his marginal is
     *     exactly zero and the search drafts sixteen receivers. lostBelow 1 is
     *     "bench anybody who fell short of his projection at all", which is a
     *     lot of hindsight wearing a threshold.
     *
     *   BLEND.  No benching at all. The lineup is ORDERED on
     *
     *         expected + lambda * (drawn - expected)
     *
     *     so lambda 0 is the same useless bench as lostBelow 0, and lambda 1 is
     *     perfect hindsight - which is best ball's actual rule, not this
     *     league's, and is what -PbestBall already prints. Anything between is
     *     "you work some of it out during the season". Justin's proposal, and
     *     it is the better-shaped object: continuous, so nothing sits one point
     *     the wrong side of a threshold somebody picked.
     *
     * HINDSIGHT, declared rather than denied: BOTH forms leak some. The
     * threshold uses the realised season to decide who is benched; the blend
     * uses it to decide the whole order. That is the point - a bench is worth
     * nothing to a manager who learns nothing - and the parameter is exactly
     * "how much does he work out". What must never leak is the SCORE: the
     * points counted are always the draw of whoever was selected, never the
     * best draw available.
     */
    /**
     * `wireWhenAllLost` is the THIRD choice in here and it was never a choice -
     * it was an accident, and it is the shipped behaviour.
     *
     * When every man a roster owns at a position has a lost season, somebody
     * still has to occupy the slot. Two answers are defensible: he starts his
     * least-bad man, or he drops them and streams the wire. The code did the
     * second, the comment beside it promised the first, and nobody had noticed
     * because the mechanism that decided was a bug (see {@link #bench}).
     *
     * TRUE is the wire, and it is the default because every number on record -
     * 1935 mean, 1792 worst, the whole tagged backtest - was measured with it.
     * FALSE is what the comment always claimed. -PwireWhenAllLost=false.
     * It matters to the very question being fitted, because it is exactly the
     * world a second body at a position is owned FOR, so it is swept beside
     * lostBelow rather than assumed.
     */
    record Selection(boolean blend, double lostBelow, double lambda,
                     boolean wireWhenAllLost){

        /** What the LINEUP is ordered on. Never what it is scored on. */
        double order(double expected, double drawn){
            return blend ? expected + lambda * (drawn - expected) : expected;
        }

        /** Is this man benched before the lineup is even set? Threshold form only. */
        boolean lost(double expected, double drawn){
            return !blend && expected > 0 && drawn < lostBelow * expected;
        }

        Selection fielding(boolean wire){
            return new Selection(blend, lostBelow, lambda, wire);
        }

        static Selection threshold(double lostBelow){
            return new Selection(false, lostBelow, 0, WIRE_WHEN_ALL_LOST);
        }

        static Selection blend(double lambda){
            return new Selection(true, 0, lambda, WIRE_WHEN_ALL_LOST);
        }
    }

    /** -PwireWhenAllLost=false makes a position that loses everybody field its best man. */
    static final boolean WIRE_WHEN_ALL_LOST =
            !"false".equals(System.getProperty("wireWhenAllLost"));

    /**
     * The shipped rule. -PlostBelow sets the threshold; -Plambda switches to the
     * blend and sets its weight. Passing both is a contradiction and the blend
     * wins, loudly - a run that silently used one of two rules would be worse
     * than a crash.
     */
    static Selection shipped(){
        String lambda = System.getProperty("lambda");
        if(lambda != null && !lambda.isBlank()){
            if(System.getProperty("lostBelow") != null){
                throw new IllegalArgumentException(
                        "-Plambda and -PlostBelow are two different lineup rules;"
                                + " pass one");
            }
            return Selection.blend(Double.parseDouble(lambda.trim()));
        }
        return Selection.threshold(
                Double.parseDouble(System.getProperty("lostBelow", "0.55")));
    }

    /**
     * MUTABLE, and only so a sweep can exist.
     *
     * Every other knob in this file is a final read of a system property, which
     * is right for a knob you set once at the command line and wrong for one
     * whose whole purpose is to be varied thirty times inside a single run. A
     * fit that had to fork a JVM per grid point would take an hour and nobody
     * would run it twice.
     *
     * Nothing but {@link BenchCalibration} assigns this, it is assigned from one
     * thread, and it is restored after every sweep. If a second writer ever
     * appears, make it a parameter on stats()/oneSeason() instead.
     */
    static Selection SELECTION = shipped();

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
        // WHO IS AVAILABLE, which is the only honest reason to own a bench.
        //
        // Without this, removing hindsight leaves a bench man worth EXACTLY
        // zero - he can never enter a lineup chosen on preseason expectation -
        // so the marginal collapses, the search stops discriminating and it
        // drafts sixteen receivers. That is what happened the first time
        // BY_EXPECTED was switched on.
        //
        // A bench man is worth what he is worth when the man ahead is LOST. So
        // a drawn season far below what that rank normally returns counts as
        // lost, the manager benches him - which is a thing anybody can see by
        // October, unlike knowing who will outscore whom - and the lineup is
        // filled by EXPECTATION from whoever is left. Selection uses only what
        // was knowable; scoring still uses what happened.
        //
        // No new data: the threshold reads the same pooled ratios everything
        // else here draws from.
        //
        // WHICH RULE is BoardValue.SELECTION - threshold or blend, see the
        // record above. The blend benches nobody and does its work in the
        // ordering instead, so this loop is a no-op under it.
        Selection rule = SELECTION;
        if(BY_EXPECTED){                  // realised-fill needs no availability
            for(Map.Entry<Position, List<double[]>> entry : pool.entrySet()){
                bench(entry.getValue(), rule);
            }
        }
        Comparator<double[]> best = BY_EXPECTED
                ? Comparator.comparingDouble(
                        (double[] man) -> rule.order(man[0], man[1])).reversed()
                : Comparator.comparingDouble((double[] man) -> man[1]).reversed();
        for(List<double[]> values : pool.values()){
            values.sort(best);
        }
        // STREAMING A DEFENCE NEEDS A ROSTER SPOT, and at the last pick there
        // is not one.
        //
        // Justin: wouldn't it be a bug if defense reads 0.0 at round 16. It
        // was. An unfilled defence slot was credited the replacement man for
        // free at EVERY round, so a defence added nothing anywhere and the
        // model never once chose one on value - RosterRules forced it when the
        // picks ran out, and the right pick was arriving for the wrong reason.
        //
        // Free is correct while a spot remains: defences are interchangeable,
        // the bench is fungible, and you drop your least useful man and stream
        // one the same day. That is Justin's own correction and it still holds.
        // It stops holding when the roster is FULL, because then the man you
        // drop is a real one - which is exactly what PlanBacktest.seasonPoints
        // has always charged and what this valuation never did. The model was
        // optimising something the scorer did not measure.
        //
        // So: no defence and no spare spot means the weakest man on the roster
        // goes to make room for the stream.
        boolean hasDefence = pool.containsKey(Position.DEF)
                && !pool.get(Position.DEF).isEmpty();
        if(!hasDefence && roster.size() >= RosterRules.live().size()){
            List<double[]> weakest = null;
            double least = Double.MAX_VALUE;
            for(List<double[]> values : pool.values()){
                if(!values.isEmpty() && values.get(values.size() - 1)[1] < least){
                    least = values.get(values.size() - 1)[1];
                    weakest = values;
                }
            }
            if(weakest != null){
                weakest.remove(weakest.size() - 1);
            }
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

    /**
     * Bench everyone this rule calls LOST.
     *
     * WHAT IT REPLACES DID NOT DO WHAT ITS COMMENT SAID. The old guard read
     *
     *     return gone && entry.getValue().size() > 1;
     *
     * from inside that same list's own removeIf, above a comment promising
     * "never empty a position entirely - somebody starts, even a busted man".
     * ArrayList.removeIf tests every element BEFORE it removes any of them, so
     * size() reported the ORIGINAL count throughout however many were already
     * doomed. Two men at a position who both had bad seasons were therefore
     * BOTH deleted and the slot fell to the wire - the exact thing the comment
     * said could not happen. Only a one-man position was ever protected.
     *
     * The behaviour is kept and the comment is withdrawn, because the accident
     * is arguably the better model: a manager whose backs have all fallen over
     * does go to the wire, and `replacement` is the wire. It is now a stated
     * choice on {@link Selection} instead of an emergent property of a bug, so
     * it can be swept - and {@link BenchCalibration} sweeps it, since "all my
     * men at this position are lost" is precisely the world a second body is
     * owned for.
     *
     * Who survives under wireWhenAllLost=false is stated rather than left to
     * list order: the best by EXPECTATION, because otherwise a roster's value
     * would depend on the order it happened to be drafted in.
     */
    static void bench(List<double[]> men, Selection rule){
        if(men.size() <= 1){
            return;                       // one man always starts, lost or not
        }
        List<double[]> playing = new ArrayList<>();
        double[] fallback = null;
        for(double[] man : men){
            if(man[0] <= 0 || !rule.lost(man[0], man[1])){
                playing.add(man);
            }
            if(fallback == null || man[0] > fallback[0]){
                fallback = man;
            }
        }
        if(playing.isEmpty() && !rule.wireWhenAllLost()){
            playing.add(fallback);        // all lost, and somebody still starts
        }
        men.clear();
        men.addAll(playing);
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
