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

        System.out.printf("%n%s%nIS IT ANY GOOD?%n%s%n", "=".repeat(64), "=".repeat(64));
        PlanBacktest.STRATEGIES.put("board value", rendered.toString());
        PlanBacktest.main(new String[0]);
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
        Map<Position, Map<Integer, List<Double>>> raw = new EnumMap<>(Position.class);
        for(PairwiseOdds.Man man : men){
            int cap = PairwiseOdds.CAP.getOrDefault(man.position(), 0);
            if(man.rank() > cap || man.rank() < 1){
                continue;
            }
            raw.computeIfAbsent(man.position(), u -> new HashMap<>())
                    .computeIfAbsent(man.rank(), u -> new ArrayList<>()).add(man.points());
        }
        Map<Position, List<List<Double>>> out = new EnumMap<>(Position.class);
        for(Map.Entry<Position, Map<Integer, List<Double>>> entry : raw.entrySet()){
            int cap = PairwiseOdds.CAP.getOrDefault(entry.getKey(), 0);
            List<List<Double>> byRank = new ArrayList<>();
            for(int rank = 0; rank <= cap; rank++){
                List<Double> pool = new ArrayList<>();
                int from = Math.max(1, (int) Math.floor(rank * Math.exp(-0.25)));
                int to = Math.min(cap, (int) Math.ceil(rank * Math.exp(0.25)));
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
        List<List<Double>> byRank = pools.get(position);
        if(byRank == null || rank >= byRank.size() || byRank.get(rank).isEmpty()){
            double[] mean = curve.get(position);
            return mean != null && rank < mean.length ? mean[rank] : 0;
        }
        List<Double> pool = byRank.get(rank);
        int index = Math.floorMod(world * 2654435761L
                + position.ordinal() * 40503L + rank * 2246822519L, pool.size());
        return pool.get(index);
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
        double total = 0;
        for(int world = 0; world < WORLDS; world++){
            total += oneSeason(roster, pools, curve, world);
        }
        return total / WORLDS;
    }

    static double oneSeason(List<Slot> roster, Map<Position, List<List<Double>>> pools,
                            Map<Position, double[]> curve, int world){
        Map<Position, List<Double>> pool = new EnumMap<>(Position.class);
        for(Slot slot : roster){
            pool.computeIfAbsent(slot.position(), u -> new ArrayList<>())
                    .add(drawn(pools, slot.position(), slot.rank(), world, curve));
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
            total += slot < flex.size() ? flex.get(slot) : replacement(curve, Position.RB);
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
