import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * EVERY TRADE BETWEEN TWO WHOLE ROSTERS, up to maxSide men a side, any shape.
 *
 * Justin, 2026-11-06, on the board's limit of each side's top eight for anything past a
 * one-for-one: "why are we looking only at top 8, the search space should be bigger, that's
 * not an optimization i agree with." It was a speed limit on a slow objective, and it could not
 * see a package with a depth piece in it, a three-for-two or a three-for-one. WeeklyLineupValue.Table
 * made the objective far faster, so nothing is pre-judged here: every subset of
 * his up to maxSide against every subset of Justin's.
 *
 * WHO MUST BE CUT. Rosters hold a fixed number of active men (IR slots aside). A side that ends
 * over the limit cuts its lowest-projected men not in the deal and not on IR, so a three-for-two
 * is what Justin described: "1 of the 3 is just their worst bench player, given they would need
 * to drop a player regardless". A side already short cuts nobody.
 *
 * WHAT IS KEPT. The search can pass millions of trades and only a few thousand can be simulated
 * or read, so each manager's trades are held in LANES, each a separate question: any trade; one
 * he would read as fair; one at each of BOTH or more on both pace and projection. Within a lane,
 * bands of Justin's gain (BAND points wide) keep the ones that cost the other team least, so a
 * big gain is never crowded out by a thousand small cheap ones. Trades that read the same to the
 * point on both sides - a man nobody would start thrown in - are one trade, the smaller.
 */
final class TradeSearch {

    /** The "on both pace and projection" lanes, highest first. */
    static final double[] BOTH = {1.00, 0.95, 0.90, 0.85};
    static final int FAIR_LANE = 0, ANY_LANE = 1 + BOTH.length, LANES = 2 + BOTH.length;
    static final double BAND = 5;
    static final int BANDS = 16;

    record Prices(WeeklyLineupValue.Table sleeper, WeeklyLineupValue.Table model, WeeklyLineupValue.Table fantasyPros) {}

    /** Each man's value on the screens he can see, by table index. */
    record Screen(double[] draft, double[] pace, double[] projection, double[] season, double draftShare) {}

    record Rules(int maxSide, double minGain, double rejectBelow, boolean vetoOnFantasyPros, boolean vetoOnModel,
                 int activeLimit, int retain) {}

    /** One trade, in table indices: what Justin sends, receives and cuts, and what the other manager cuts. */
    static final class Deal {
        final int[] give, get, myCut, hisCut;
        final double you, him, pace, projection, blend;

        Deal(int[] give, int[] get, int[] myCut, int[] hisCut, double you, double him, double pace, double projection, double blend){
            this.give = give;
            this.get = get;
            this.myCut = myCut;
            this.hisCut = hisCut;
            this.you = you;
            this.him = him;
            this.pace = pace;
            this.projection = projection;
            this.blend = blend;
        }

        int men(){
            return give.length + get.length;
        }

        String key(){
            return Arrays.toString(give) + "|" + Arrays.toString(get);
        }
    }

    static final Comparator<Deal> LEAST_WORSE = Comparator.comparingDouble((Deal d) -> -d.him)
            .thenComparingInt(Deal::men).thenComparingDouble(d -> -d.blend)
            .thenComparing(d -> d.give, Arrays::compare).thenComparing(d -> d.get, Arrays::compare);

    record Found(long searched, long vetoed, long passed, List<List<Deal>> lanes) {}

    record Kept(Deal deal, double rise, double se, double hisTitle) {}

    record Walked(List<Kept> kept, int simulated, int skipped, boolean capReached) {}

    /** Every non-empty subset of these men up to this size. */
    static int[][] combos(int[] men, int maxSide){
        List<int[]> out = new ArrayList<>();
        for(int size = 1; size <= Math.min(maxSide, men.length); size++){
            int[] at = new int[size];
            for(int i = 0; i < size; i++){
                at[i] = i;
            }
            while(true){
                int[] one = new int[size];
                for(int i = 0; i < size; i++){
                    one[i] = men[at[i]];
                }
                out.add(one);
                int i = size - 1;
                while(i >= 0 && at[i] == men.length - size + i){
                    i--;
                }
                if(i < 0){
                    break;
                }
                at[i]++;
                for(int j = i + 1; j < size; j++){
                    at[j] = at[j - 1] + 1;
                }
            }
        }
        return out.toArray(new int[0][]);
    }

    private static boolean has(int[] men, int man){
        for(int m : men){
            if(m == man){
                return true;
            }
        }
        return false;
    }

    private static int[] without(int[] men, int[] gone){
        int[] out = new int[men.length - gone.length];
        int n = 0;
        for(int m : men){
            if(!has(gone, m)){
                out[n++] = m;
            }
        }
        return out;
    }

    /** One side's men who could be cut, cheapest first: not on IR, lowest projection first. */
    private static int[] cutOrder(int[] men, boolean[] onIr, boolean[] locked, double[] projected){
        return Arrays.stream(men).filter(m -> !onIr[m] && !locked[m]).boxed()
                .sorted(Comparator.comparingDouble((Integer m) -> projected[m]).thenComparingInt(m -> m))
                .mapToInt(Integer::intValue).toArray();
    }

    private static int active(int[] men, boolean[] onIr){
        int n = 0;
        for(int m : men){
            if(!onIr[m]){
                n++;
            }
        }
        return n;
    }

    private static double sum(int[] men, double[] value){
        double total = 0;
        for(int m : men){
            total += value[m];
        }
        return total;
    }

    /** rest less cut, then in, into buffer; how many men that is. */
    private static int roster(int[] buffer, int[] rest, int[] cut, int[] in){
        int n = 0;
        if(cut.length == 0){
            System.arraycopy(rest, 0, buffer, 0, rest.length);
            n = rest.length;
        }
        else {
            for(int m : rest){
                if(!has(cut, m)){
                    buffer[n++] = m;
                }
            }
        }
        System.arraycopy(in, 0, buffer, n, in.length);
        return n + in.length;
    }

    /** One manager's lanes as the search fills them. */
    private static final class Lanes {
        final Map<Long, Deal>[][] held;
        final double[][] floor = new double[LANES][BANDS];
        final int retain;

        @SuppressWarnings("unchecked")
        Lanes(int retain){
            this.retain = retain;
            held = new Map[LANES][BANDS];
            for(int l = 0; l < LANES; l++){
                Arrays.fill(floor[l], Double.NEGATIVE_INFINITY);
                for(int b = 0; b < BANDS; b++){
                    held[l][b] = new HashMap<>();
                }
            }
        }

        void add(int lane, int band, Deal deal){
            long key = (Math.round(deal.you * 10) << 32) ^ (Math.round(deal.him * 10) & 0xffffffffL);
            Map<Long, Deal> map = held[lane][band];
            Deal there = map.get(key);
            if(there == null || deal.men() < there.men() || deal.men() == there.men() && LEAST_WORSE.compare(deal, there) < 0){
                map.put(key, deal);
            }
            if(map.size() >= 3 * retain){
                trim(lane, band);
            }
        }

        void trim(int lane, int band){
            Map<Long, Deal> map = held[lane][band];
            if(map.size() <= retain){
                return;
            }
            List<Map.Entry<Long, Deal>> all = new ArrayList<>(map.entrySet());
            all.sort(Map.Entry.comparingByValue(LEAST_WORSE));
            Map<Long, Deal> kept = new HashMap<>();
            for(Map.Entry<Long, Deal> e : all.subList(0, retain)){
                kept.put(e.getKey(), e.getValue());
            }
            held[lane][band] = kept;
            floor[lane][band] = all.get(retain - 1).getValue().him;
        }

        List<List<Deal>> done(){
            List<List<Deal>> out = new ArrayList<>();
            for(int l = 0; l < LANES; l++){
                List<Deal> lane = new ArrayList<>();
                for(int b = 0; b < BANDS; b++){
                    trim(l, b);
                    lane.addAll(held[l][b].values());
                }
                lane.sort(LEAST_WORSE);
                out.add(lane);
            }
            return out;
        }
    }

    /**
     * Every trade between these two rosters. A trade gaining Justin less than the minimum, or
     * that a vetoing pricing calls a loss for him, goes no further; the rest are held in lanes.
     * A locked man (-Pexclude) stays where he is: he counts on his roster but is never traded or cut.
     */
    static Found search(int[] mine, int[] his, boolean[] onIr, boolean[] locked, double[] projected, Prices prices, Screen screen, Rules rules){
        double[][] scratch = prices.sleeper().scratch();
        int[] myBuffer = new int[mine.length + rules.maxSide()], hisBuffer = new int[his.length + rules.maxSide()];
        double myBase = prices.sleeper().of(mine, mine.length, scratch);
        double myFpBase = prices.fantasyPros().of(mine, mine.length, scratch);
        double myModelBase = prices.model().of(mine, mine.length, scratch);
        double hisBase = prices.sleeper().of(his, his.length, scratch);
        int myActive = active(mine, onIr), hisActive = active(his, onIr);
        int[] myOrder = cutOrder(mine, onIr, locked, projected), hisOrder = cutOrder(his, onIr, locked, projected);
        int[][] gives = combos(Arrays.stream(mine).filter(m -> !locked[m]).toArray(), rules.maxSide());
        int[][] gets = combos(Arrays.stream(his).filter(m -> !locked[m]).toArray(), rules.maxSide());
        int[][] hisRest = new int[gets.length][];
        int[][] hisCuts = new int[gets.length][];
        int[] hisStays = new int[gets.length];
        for(int g = 0; g < gets.length; g++){
            hisRest[g] = without(his, gets[g]);
            hisCuts[g] = without(hisOrder, overlap(hisOrder, gets[g]));
            hisStays[g] = hisActive - (gets[g].length - irCount(gets[g], onIr));
        }
        double w = screen.draftShare();
        long searched = 0, vetoed = 0, passed = 0;
        Lanes lanes = new Lanes(rules.retain());
        int[] none = new int[0];
        for(int[] give : gives){
            int[] myRest = without(mine, give);
            int[] myCuts = without(myOrder, overlap(myOrder, give));
            int myStays = myActive - (give.length - irCount(give, onIr));
            double giveDraft = sum(give, screen.draft()), givePace = sum(give, screen.pace()),
                    giveProjection = sum(give, screen.projection()), giveSeason = sum(give, screen.season());
            for(int g = 0; g < gets.length; g++){
                searched++;
                int[] get = gets[g];
                int myOver = myStays + get.length - rules.activeLimit();
                int hisOver = hisStays[g] + give.length - rules.activeLimit();
                if(myOver > myCuts.length || hisOver > hisCuts[g].length){
                    continue;
                }
                int[] myCut = myOver <= 0 ? none : Arrays.copyOf(myCuts, myOver);
                int n = roster(myBuffer, myRest, myCut, get);
                double you = prices.sleeper().of(myBuffer, n, scratch) - myBase;
                if(you < rules.minGain()){
                    continue;
                }
                if(rules.vetoOnFantasyPros() && prices.fantasyPros().of(myBuffer, n, scratch) - myFpBase < rules.rejectBelow()
                        || rules.vetoOnModel() && prices.model().of(myBuffer, n, scratch) - myModelBase < rules.rejectBelow()){
                    vetoed++;
                    continue;
                }
                passed++;
                int[] hisCut = hisOver <= 0 ? none : Arrays.copyOf(hisCuts[g], hisOver);
                int m = roster(hisBuffer, hisRest[g], hisCut, give);
                double him = prices.sleeper().of(hisBuffer, m, scratch) - hisBase;
                int band = Math.max(0, Math.min(BANDS - 1, (int) (you / BAND)));
                double outPace = sum(get, screen.pace()) + sum(hisCut, screen.pace());
                double outProjection = sum(get, screen.projection()) + sum(hisCut, screen.projection());
                double outDraft = sum(get, screen.draft()) + sum(hisCut, screen.draft());
                double outSeason = sum(get, screen.season()) + sum(hisCut, screen.season());
                double pace = TradeScreens.ratio(givePace, outPace), projection = TradeScreens.ratio(giveProjection, outProjection);
                double blend = TradeScreens.ratio(TradeScreens.blend(giveDraft, giveSeason, w), TradeScreens.blend(outDraft, outSeason, w));
                Deal deal = null;
                for(int l = 0; l < LANES; l++){
                    boolean in = l == ANY_LANE || l == FAIR_LANE && blend >= 1
                            || l > FAIR_LANE && l < ANY_LANE && pace >= BOTH[l - 1] && projection >= BOTH[l - 1];
                    if(in && him >= lanes.floor[l][band]){
                        if(deal == null){
                            deal = new Deal(give, get, myCut, hisCut, you, him, pace, projection, blend);
                        }
                        lanes.add(l, band, deal);
                    }
                }
            }
        }
        return new Found(searched, vetoed, passed, lanes.done());
    }

    private static int[] overlap(int[] order, int[] gone){
        return Arrays.stream(gone).filter(m -> has(order, m)).toArray();
    }

    private static int irCount(int[] men, boolean[] onIr){
        return men.length - active(men, onIr);
    }

    /**
     * Each lane walked least worse for him first, its title-odds change simulated (a simulation
     * costs about a quarter of a second, so at most `cap` of them), and each half-point bucket of
     * Justin's rise kept to `perBucket` trades, per lane. Once a lane's lower buckets are full, a
     * trade whose gain cannot plausibly reach an open one (twice the rate seen so far, plus a
     * point) is skipped. A trade met in two lanes is simulated and kept once.
     */
    static Walked walk(Found found, Function<Deal, double[]> simulate, double step, int buckets, int perBucket, int cap){
        Map<String, double[]> done = new HashMap<>();
        Map<String, Kept> kept = new LinkedHashMap<>();
        int skipped = 0;
        boolean capped = false;
        double sumRise = 0, sumYou = 0;
        for(List<Deal> lane : found.lanes()){
            int[] count = new int[buckets];
            for(Deal deal : lane){
                if(Arrays.stream(count).allMatch(n -> n >= perBucket)){
                    break;
                }
                double[] result = done.get(deal.key());
                if(result == null){
                    double rate = sumYou > 0 ? Math.max(0.02, sumRise / sumYou) : 0.1;
                    int reach = (int) Math.floor((2 * rate * deal.you + 1.0) / step) - 1;
                    boolean open = false;
                    for(int b = 0; b <= Math.min(reach, buckets - 1); b++){
                        open |= count[b] < perBucket;
                    }
                    if(!open){
                        skipped++;
                        continue;
                    }
                    if(done.size() >= cap){
                        capped = true;
                        break;
                    }
                    result = simulate.apply(deal);
                    done.put(deal.key(), result);
                    sumRise += Math.max(0, result[0]);
                    sumYou += deal.you;
                }
                if(result[0] < step){
                    continue;
                }
                int b = Math.min(buckets - 1, (int) Math.floor((result[0] - step) / step + 1e-9));
                if(count[b] < perBucket){
                    count[b]++;
                    kept.putIfAbsent(deal.key(), new Kept(deal, result[0], result[1], result[2]));
                }
            }
        }
        return new Walked(new ArrayList<>(kept.values()), done.size(), skipped, capped);
    }
}
