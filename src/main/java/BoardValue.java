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
     * WHY THE BENCH HALF IS NOT HERE, having been tried and removed.
     *
     * From pick 103 on every cell above reads zero: once the lineup is full, a
     * season-total model filled by expectation cannot tell one bench man from
     * another, so six of fourteen picks fall to a tie-break. The obvious repair
     * is to price a bench man at what this league's real bench picks returned -
     * BenchValue measured 44.0 points over the wire in rounds 8-9, 32.8 in
     * 10-12, 31.2 in 13-16 - and it is measured, not modelled, so it respects
     * the no-intraseason rule.
     *
     * It fails, and instructively. Those figures are RAW POINTS, and BenchValue
     * says so in its own output: "it is in raw points, which flatters QB
     * because this league pays 6 per passing TD". Applied flat, a backup
     * quarterback priced at 88 at every pick and the model took quarterbacks at
     * 7 and 18 - the identical cross-position raw-points error that cost
     * RankDraft 68 points, repeated one step later by me.
     *
     * The honest repair needs the chance the man ahead of him FAILS, because a
     * bench quarterback behind a kept Purdy is worth almost nothing while a
     * fifth receiver has five slots to fall into. That is the promotion channel
     * Justin ruled out, and two independent measurements put its whole value at
     * +5.6 +/- 7.7 and +17 a season against a 125-point bar. So the bench half
     * is left out rather than faked: this model is complete for STARTERS, and
     * says so, instead of guessing after the lineup fills.
     */

    public static void main(String[] args) throws Exception {
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Map<Position, double[]> curve = RankDraft.pointsByRank(men);
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
                double gain = urgency(curve, adp, roster, position, picks[i], next);
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
    static double urgency(Map<Position, double[]> curve, Map<Position, List<Double>> adp,
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
        double base = lineup(roster, curve);
        List<Slot> withNow = new ArrayList<>(roster);
        withNow.add(new Slot(position, early));
        double gainNow = lineup(withNow, curve) - base;
        if(next < 0){
            return gainNow;
        }
        int late = RankDraft.depth(board, next);
        if(late >= mean.length){
            return gainNow;
        }
        List<Slot> withLater = new ArrayList<>(roster);
        withLater.add(new Slot(position, late));
        return gainNow - (lineup(withLater, curve) - base);
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
