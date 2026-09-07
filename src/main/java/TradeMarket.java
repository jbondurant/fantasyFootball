import PlayerImportAndSetup.Position;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a trade piece is actually WORTH, given who else could supply it.
 *
 * {@link TradeFinder} already enumerates single, double and triple swaps across
 * every rival and already files them by how much they help the other side - "a
 * trade nobody else wants is not a trade" is its own comment, and it predates
 * this class by a long way. This does not repeat that. It adds the three things
 * Justin asked for that it does not do:
 *
 *   1. both rosters priced on {@link WeeklyStarterValue} - the objective that
 *      knows about a bench, a defence and an injury - rather than on projected
 *      starters, so a man is worth what he is worth by how often he is actually
 *      promoted into the lineup
 *   2. the SUPPLY behind a piece: how many rivals could offer the same thing
 *   3. trading POWER: what the roster can become two trades deep, which a
 *      one-step search cannot see
 *
 * Every roster is priced by the same {@link WeeklyStarterValue} the keeper work
 * used: seventeen weeks of the best legal ten, scored on drawn historical
 * seasons, so a bench man is worth what he is worth by how often he is actually
 * promoted. A trade is worth, to each side, that roster's value after minus
 * before. Only trades where BOTH numbers are positive are ever shown - not out
 * of politeness, but because an offer the other manager loses on is an offer he
 * declines, and a list of those is a list of nothing.
 *
 *   ./gradlew run -Pmain=TradeMarket [-Pme=<name>] [-PchainDepth=2] [-Ptop=12]
 *                                    [-Pscenarios=240] [-Ppool=8]
 *
 * WHAT IT DOES NOT DO, and cannot. Justin asked for a cost on unenticing offers -
 * a model of what a manager will accept. Sleeper records only COMPLETED trades:
 * 51 of them across this league's five seasons, and not one refusal. There is no
 * record of what was turned down, so acceptance cannot be fitted, and a number
 * invented for it would be exactly the signal-that-is-not-there this repo keeps
 * catching (TRAPS #93, #94). What stands in for it is honest and weaker: trades
 * are ranked by what they give the OTHER side as well as this one, and the
 * report shows his gain so the offer can be judged by eye.
 */
public class TradeMarket {

    /** One offer: what goes each way, and what each side gains. */
    public record Trade(String withManager, List<String> give, List<String> get,
                        double myGain, double theirGain) {

        /** The smaller of the two gains - a trade is only as good as its weaker half. */
        public double weaker(){
            return Math.min(myGain, theirGain);
        }
    }

    /**
     * How many OTHER managers could offer a comparable man at this position -
     * the supply behind a piece.
     *
     * Justin's point, and it is the difference between a trade tool and a
     * calculator: his second quarterback is only worth something if quarterbacks
     * are scarce. If nine other teams are also carrying a spare, the man is not
     * a chip, he is inventory. A team counts as an alternative seller when it
     * holds more men at that position than the lineup starts.
     */
    static int alternativeSellers(Map<String, List<String>> rosters, String me,
                                  Map<String, Position> positionOf, Position position, int startersNeeded){
        int sellers = 0;
        for(Map.Entry<String, List<String>> entry : rosters.entrySet()){
            if(entry.getKey().equals(me)){
                continue;
            }
            int held = 0;
            for(String id : entry.getValue()){
                if(positionOf.get(id) == position){
                    held++;
                }
            }
            if(held > startersNeeded){
                sellers++;
            }
        }
        return sellers;
    }

    /**
     * WHAT HE CAN GET INSTEAD OF DEALING WITH YOU.
     *
     * Justin, 2026-09-06: "many people could gain like a ton... That means, for
     * example, that trades where I offer someone like Nix will not be as great,
     * because people can easily make a two sided positive trade elsewhere."
     *
     * That is the hole in reading `him` as an acceptance signal. A gain of +9 to
     * the man opposite sounds persuasive until you notice that six other
     * managers are sitting on a spare quarterback and any of them can hand him
     * +11 tomorrow. What decides whether he takes YOUR offer is not what it
     * gains him, it is what it gains him ABOVE HIS BEST ALTERNATIVE - and a
     * league of twelve rosters has a lot of alternatives.
     *
     * So: for each rival, the best gain he can get from a mutually-good trade
     * with somebody who is not Justin. Both sides of those trades are priced by
     * the rival objective, because a trade only counts as an alternative if the
     * OTHER manager would also take it - an offer nobody accepts is not an
     * outside option, it is a wish.
     *
     * Two honest limits. The search is size-balanced, so it cannot see the
     * uneven deals Justin describes (two men for one, with the short side
     * refilled off the wire); a manager who can build those has a BETTER outside
     * option than this reports, which makes every edge here optimistic. And it
     * runs at a reduced pool for cost, so it is a floor on his alternatives, not
     * a ceiling. Both mean the same thing: if this says your offer barely beats
     * what he can get elsewhere, it does not.
     */
    /**
     * HOW OFTEN ANYBODY ACTUALLY TRADES, measured rather than assumed.
     *
     * Justin, 2026-09-06: "the other managers don't have the tools to find the
     * perfect trades elsewhere." That is the second thing wrong with reading a
     * rival's fallback as the BEST deal available to him, and unlike the
     * recursion it cannot be fixed by computing harder - it is a fact about
     * people, so it has to come from their record.
     *
     * This league's own log has it. Every completed trade in every finished
     * season, over the twelve managers who could have made one: if a manager
     * completes about one trade a season while the board offers him dozens that
     * would help both sides, then he does not find his maximum, and a fallback
     * computed as that maximum is an upper bound and should be read as one.
     *
     * Returns {trades, seasons} so the caller can divide by the league size
     * itself rather than being handed a rate whose denominator is invisible.
     */
    static int[] realisedTrades(String leagueID){
        int trades = 0, seasons = 0;
        for(LeagueTransactions.Year year : LeagueTransactions.completedSeasons(leagueID)){
            seasons++;
            for(LeagueTransactions.Move move : LeagueTransactions.moves(year)){
                if("trade".equals(move.type()) && move.complete()){
                    trades++;
                }
            }
        }
        return new int[]{trades, seasons};
    }

    /**
     * One rival-to-rival trade: who, what each side sends, and what each gains.
     *
     * The men are kept, not just the gains, because the same search answers two
     * questions - what a manager's fallback is worth (which needs only the
     * numbers) and what each individual player fetches on the open market (which
     * needs to know who was in the deal).
     */
    record Alternative(String one, String two, List<String> oneGives, List<String> twoGives,
                       double gainOne, double gainTwo) {}

    /**
     * Every mutually-good trade between two managers who are not Justin. The
     * expensive half of the outside-option calculation, done once.
     */
    static List<Alternative> alternatives(String me, Map<String, List<String>> rosters,
                                          Side rivalValue, int pool, Map<String, Double> points){
        List<String> rivals = new ArrayList<>();
        for(String manager : rosters.keySet()){
            if(!manager.equals(me)){
                rivals.add(manager);
            }
        }
        List<Alternative> found = new ArrayList<>();
        for(int i = 0; i < rivals.size(); i++){
            for(int j = i + 1; j < rivals.size(); j++){
                String one = rivals.get(i), two = rivals.get(j);
                // BALANCED AND UNEVEN BOTH. A rival who can send two men for one
                // has better alternatives than a balanced-only search credits
                // him with, and every one of those is a thing Justin's offer has
                // to beat. Leaving them out flattered his own board in exactly
                // the column that decides whether an offer gets taken.
                List<Trade> board = new ArrayList<>(between(one, two, rosters.get(one),
                        rosters.get(two), rivalValue, rivalValue, pool));
                board.addAll(unbalanced(one, two, rosters.get(one), rosters.get(two),
                        rivalValue, rivalValue, pool, points));
                for(Trade trade : mutual(board)){
                    found.add(new Alternative(one, two, trade.give(), trade.get(),
                            trade.myGain(), trade.theirGain()));
                }
            }
        }
        return found;
    }

    /**
     * WHAT HE CAN GET INSTEAD OF DEALING WITH YOU.
     *
     * Justin, 2026-09-06: "their perfect trades also would need to be recursively
     * limited by the ability of the other other manager to make a better trade."
     *
     * He is right, and the first two attempts at it were both wrong in
     * instructive ways. Taking each rival's best mutually-good trade with anybody
     * credits him with deals the man opposite would decline. Trying to fix that
     * by ITERATING - keep only trades whose counterparty beats his own current
     * fallback, recompute, repeat - does not converge, and cannot:
     *
     *   round 0: nobody has a fallback, so every trade counts, so each fallback
     *            becomes that manager's naive MAXIMUM.
     *   round 1: a trade counts for A only if it beats B's fallback. But B's
     *            fallback is now the largest gain B can get anywhere, so NO
     *            trade beats it - not even the one that produced it. Everything
     *            drops to zero.
     *   round 2: back to the maximum. A period-two cycle, forever.
     *
     * Reading the last round of a cycle as "settled" is how that shipped a page
     * saying every rival's alternative was worth exactly 0.0 and every one of
     * ninety-seven offers was competitive. The oscillation is not a numerical
     * wobble to damp; the recursion simply has no fixed point in that form,
     * because a manager's fallback was being defined in terms of a quantity that
     * already contains it.
     *
     * The structure Justin is describing is a MATCHING. Managers pair off; a
     * pair does the trade that maximises what there is to split; and what a
     * rival can get instead of dealing with Justin is what he gets from the
     * partner he would actually end up with - not the best partner he can name,
     * because that partner has someone better in mind. Pairing greedily by joint
     * surplus makes every manager's fallback a deal that a specific other
     * manager is also taking, which is exactly the constraint the iteration was
     * groping for and could not express.
     */
    static Map<String, Double> outsideOption(String me, Map<String, List<String>> rosters,
                                             Side rivalValue, int pool, Map<String, Double> points){
        return match(alternatives(me, rosters, rivalValue, pool, points), rosters.keySet(), me).fallback();
    }

    /**
     * WHAT EACH MAN FETCHES ON THE OPEN MARKET, ONE AT A TIME.
     *
     * Justin, 2026-09-06: "find trades which are within range of what an owner
     * can get in value from trading the players I want from them with other
     * owners, not necessarily in the same pair, but could be each individually,
     * or each as part of some bundle, but where their contributions are
     * isolated."
     *
     * So: for every man on a rival's roster, the best gain his OWNER can get
     * from a straight one-for-one that sends him to somebody who is not Justin.
     * One-for-one is what makes the contribution isolated - in a two-for-two the
     * gain belongs to the pair and splitting it between the men is a choice, not
     * a measurement. Asking for two of a manager's players is then asking him to
     * forgo two of these, and the offer has to be in range of their sum.
     *
     * It is a floor on what he could get, in two ways worth saying: bundles can
     * be worth more than their parts, and the search runs at a reduced pool. A
     * man priced at zero here has no one-for-one buyer, not no value.
     */
    static Map<String, Double> sellPrice(List<Alternative> alternatives){
        Map<String, Double> price = new TreeMap<>();
        for(Alternative alternative : alternatives){
            if(alternative.oneGives().size() == 1){
                price.merge(alternative.oneGives().get(0), alternative.gainOne(), Math::max);
            }
            if(alternative.twoGives().size() == 1){
                price.merge(alternative.twoGives().get(0), alternative.gainTwo(), Math::max);
            }
        }
        return price;
    }

    /** Who paired with whom, and what each of them got out of it. */
    public record Market(Map<String, String> partner, Map<String, Double> fallback,
                         Map<String, Double> naive) {}

    /**
     * Pair the rivals off and read each one's fallback out of the pairing.
     *
     * `naive` is kept alongside - each manager's best gain over ALL partners,
     * ignoring whether that partner would have him - so the difference between
     * the two is visible rather than asserted. It is always the larger.
     */
    static Market match(List<Alternative> alternatives, Set<String> managers, String me){
        // the deal a pair would actually strike: the one with the most joint
        // surplus to divide, which is the trade they would negotiate towards
        Map<String, Alternative> bestForPair = new TreeMap<>();
        Map<String, Double> naive = new TreeMap<>();
        for(String manager : managers){
            if(!manager.equals(me)){
                naive.put(manager, 0.0);
            }
        }
        for(Alternative alternative : alternatives){
            naive.merge(alternative.one(), alternative.gainOne(), Math::max);
            naive.merge(alternative.two(), alternative.gainTwo(), Math::max);
            String key = alternative.one().compareTo(alternative.two()) < 0
                    ? alternative.one() + "\u0000" + alternative.two()
                    : alternative.two() + "\u0000" + alternative.one();
            Alternative held = bestForPair.get(key);
            if(held == null
                    || alternative.gainOne() + alternative.gainTwo() > held.gainOne() + held.gainTwo()){
                bestForPair.put(key, alternative);
            }
        }

        List<Alternative> pairs = new ArrayList<>(bestForPair.values());
        pairs.sort(Comparator.comparingDouble((Alternative a) -> a.gainOne() + a.gainTwo()).reversed());
        Map<String, String> partner = new TreeMap<>();
        Map<String, Double> fallback = new TreeMap<>();
        for(String manager : naive.keySet()){
            fallback.put(manager, 0.0);
        }
        for(Alternative alternative : pairs){
            if(partner.containsKey(alternative.one()) || partner.containsKey(alternative.two())){
                continue;                    // one of them is already spoken for
            }
            partner.put(alternative.one(), alternative.two());
            partner.put(alternative.two(), alternative.one());
            fallback.put(alternative.one(), alternative.gainOne());
            fallback.put(alternative.two(), alternative.gainTwo());
        }
        // AN ODD NUMBER OF RIVALS LEAVES ONE OVER, and a leftover manager with a
        // fallback of zero is plainly wrong: he is not barred from trading, he
        // just has no partner in the greedy pairing. He can still break up a
        // pair - if some matched manager would rather deal with HIM than with
        // the partner he has, that is a blocking pair, and the deal it names is
        // genuinely available to both. So one pass of that: an unmatched
        // manager's fallback is the best trade he can offer somebody who would
        // take it over what he currently holds. If nobody would, zero is right,
        // and it is right for a reason rather than by omission.
        for(String spare : fallback.keySet()){
            if(partner.containsKey(spare)){
                continue;
            }
            for(Alternative alternative : pairs){
                String other = alternative.one().equals(spare) ? alternative.two()
                        : alternative.two().equals(spare) ? alternative.one() : null;
                if(other == null){
                    continue;
                }
                double hisShare = alternative.one().equals(spare)
                        ? alternative.gainTwo() : alternative.gainOne();
                double mine = alternative.one().equals(spare)
                        ? alternative.gainOne() : alternative.gainTwo();
                if(hisShare > fallback.get(other)){
                    fallback.merge(spare, mine, Math::max);   // he would break his pair for this
                }
            }
        }
        return new Market(partner, fallback, naive);
    }

    /** What a trade is worth to one manager: his roster before, what leaves, what arrives. */
    public interface Side {
        double gain(List<String> before, List<String> out, List<String> in);
    }

    /** A plain roster scorer, for a side that values a roster and not a transaction. */
    static Side scoring(java.util.function.ToDoubleFunction<List<String>> value){
        return (before, out, in) -> value.applyAsDouble(swap(before, out, in)) - value.applyAsDouble(before);
    }

    /**
     * The other manager, as Justin describes him: he does not care about GAINING
     * keeper value, and he cares deeply about LOSING it.
     *
     * That asymmetry is a ratchet, not a discount, and it points the opposite way
     * from the first version of this. Treating him as simply indifferent to
     * keepers made him a cheap seller of them - the model happily proposed buying
     * other people's round-14 men for season points. He is not a cheap seller. He
     * is somebody who will not feel the keeper he receives and will feel every
     * point of the one he gives up. So: his season gain, minus what leaving costs
     * his own best two, plus nothing at all for what arrives.
     */
    static Side lossAverseOnKeepers(java.util.function.ToDoubleFunction<List<String>> season,
                                    Map<String, Double> surplus){
        return (before, out, in) -> {
            double seasonGain = season.applyAsDouble(swap(before, out, in)) - season.applyAsDouble(before);
            List<String> keptBack = new ArrayList<>(before);
            keptBack.removeAll(out);
            double keeperLoss = keeperValue(before, surplus) - keeperValue(keptBack, surplus);
            return seasonGain - keeperLoss;
        };
    }

    /**
     * Every size-balanced swap between two rosters, each side priced BY ITS OWN
     * LIGHTS - and each side's lights are a property of the TRADE, not just of
     * the roster it ends with, because loss aversion cannot be written as a
     * roster score.
     */
    static List<Trade> between(String me, String them, List<String> mine, List<String> theirs,
                               Side myValue, Side theirValue, int pool){
        List<Trade> trades = new ArrayList<>();
        for(String give : mine){
            for(String get : theirs){
                trades.add(new Trade(them, List.of(give), List.of(get),
                        myValue.gain(mine, List.of(give), List.of(get)),
                        theirValue.gain(theirs, List.of(get), List.of(give))));
            }
        }
        // two for two and three for three, among each side's most valuable few:
        // the unrestricted search is 14,400 pairs a rival and almost all of it is
        // noise. Three-man deals are real here - eleven of this league's 51
        // completed trades moved four players and six moved five or more - so
        // leaving them out was leaving out the shape it actually trades in.
        List<String> myTop = mine.subList(0, Math.min(pool, mine.size()));
        List<String> theirTop = theirs.subList(0, Math.min(pool, theirs.size()));
        for(List<String> give : combinations(myTop, 2)){
            for(List<String> get : combinations(theirTop, 2)){
                trades.add(new Trade(them, give, get,
                        myValue.gain(mine, give, get), theirValue.gain(theirs, get, give)));
            }
        }
        for(List<String> give : combinations(myTop, 3)){
            for(List<String> get : combinations(theirTop, 3)){
                trades.add(new Trade(them, give, get,
                        myValue.gain(mine, give, get), theirValue.gain(theirs, get, give)));
            }
        }
        return trades;
    }

    /**
     * TWO FOR ONE, AND ONE FOR TWO - with the roster arithmetic paid for.
     *
     * The balanced search cannot see the shape Justin most needs. He holds seven
     * receivers and five backs at positions where every rival has a spare, so
     * the trade that helps him is consolidation: send two men nobody starts,
     * receive one who starts. `between` only ever offers same-size swaps, so
     * that deal was not on the board at all.
     *
     * It also fixes an optimism. Every rival's outside option was computed over
     * balanced swaps only, and a manager who can build uneven deals has better
     * alternatives than that credits him with - so leaving these out made
     * Justin's own offers look better than they are, in the column that decides
     * whether they get taken.
     *
     * THE SIDE RECEIVING MORE MEN MUST DROP ONE, because rosters are full at
     * sixteen. That is not a detail to wave through: it is the same "empties a
     * slot" accounting the waiver board needed, and skipping it would price a
     * seventeen-man roster nobody is allowed to hold. He drops his lowest
     * projected man - a cheap rule, chosen over searching the drop because that
     * would cost sixteen objective runs per candidate trade, and stated here
     * rather than buried: a manager who would drop somebody smarter than his
     * worst man does better than this says.
     *
     * The side SENDING more men simply ends a man short, which the objective
     * already handles - an unfilled slot is refilled from the wire, and a
     * fifteen-man roster is legal.
     */
    static List<Trade> unbalanced(String me, String them, List<String> mine, List<String> theirs,
                                  Side myValue, Side theirValue, int pool,
                                  Map<String, Double> points){
        List<String> myTop = mine.subList(0, Math.min(pool, mine.size()));
        List<String> theirTop = theirs.subList(0, Math.min(pool, theirs.size()));
        List<Trade> trades = new ArrayList<>();

        // I send two, receive one: I end at fifteen, he ends at seventeen and cuts
        for(List<String> give : combinations(myTop, 2)){
            for(String get : theirTop){
                List<String> in = List.of(get);
                String cut = worstOther(theirs, give, points);
                if(cut == null){
                    continue;
                }
                List<String> hisOut = new ArrayList<>(in);
                hisOut.add(cut);
                trades.add(new Trade(them, give, in,
                        myValue.gain(mine, give, in), theirValue.gain(theirs, hisOut, give)));
            }
        }
        // I receive two, send one: I end at seventeen and cut, he ends at fifteen
        for(String give : myTop){
            for(List<String> get : combinations(theirTop, 2)){
                List<String> out = List.of(give);
                String cut = worstOther(mine, get, points);
                if(cut == null){
                    continue;
                }
                List<String> myOut = new ArrayList<>(out);
                myOut.add(cut);
                trades.add(new Trade(them, myOut, get,
                        myValue.gain(mine, myOut, get), theirValue.gain(theirs, get, out)));
            }
        }
        return trades;
    }

    /** The lowest-projected man on a roster who is not part of the deal. */
    static String worstOther(List<String> roster, List<String> exclude, Map<String, Double> points){
        String worst = null;
        double lowest = Double.MAX_VALUE;
        for(String id : roster){
            if(exclude.contains(id)){
                continue;
            }
            double projected = points.getOrDefault(id, 0.0);
            if(projected < lowest){
                lowest = projected;
                worst = id;
            }
        }
        return worst;
    }

    /** Every unordered choice of `size` men from `from`. */
    static List<List<String>> combinations(List<String> from, int size){
        List<List<String>> out = new ArrayList<>();
        int n = from.size();
        if(size > n){
            return out;
        }
        int[] index = new int[size];
        for(int i = 0; i < size; i++){ index[i] = i; }
        while(true){
            List<String> pick = new ArrayList<>();
            for(int i : index){ pick.add(from.get(i)); }
            out.add(pick);
            int spot = size - 1;
            while(spot >= 0 && index[spot] == n - size + spot){ spot--; }
            if(spot < 0){
                return out;
            }
            index[spot]++;
            for(int i = spot + 1; i < size; i++){ index[i] = index[i - 1] + 1; }
        }
    }

    static List<String> swap(List<String> roster, List<String> out, List<String> in){
        List<String> after = new ArrayList<>(roster);
        after.removeAll(out);
        after.addAll(in);
        return after;
    }

    /** Only the offers both sides gain from, best for us first. */
    static List<Trade> mutual(List<Trade> trades){
        List<Trade> good = new ArrayList<>();
        for(Trade trade : trades){
            if(trade.myGain() > 0 && trade.theirGain() > 0){
                good.add(trade);
            }
        }
        good.sort(Comparator.comparingDouble(Trade::myGain).reversed());
        return good;
    }

    /**
     * And it has to stand up WITHOUT the keeper value too - Justin's own
     * requirement, and the reason for it showed up the moment the two sides were
     * priced differently.
     *
     * Rivals here do not price keepers, so they will sell one cheaply; counting
     * that on his side alone made the whole board keeper-buying, and the season
     * column went negative. The best offer on it asked him to give up Derrick
     * Henry for Chase Brown - seventeen points WORSE in 2026 - which is the fire
     * sale he ruled out, in week 1, while stating that the plan is to win this
     * year. Keeper value is a tiebreak on a trade that already helps now, not a
     * reason to get worse. `-PsellMode=true` lifts this for a season that is
     * already lost, which is the only time it should be lifted.
     */
    static List<Trade> alsoGoodThisSeason(List<Trade> trades,
                                          java.util.function.ToDoubleFunction<Trade> seasonGain){
        List<Trade> good = new ArrayList<>();
        for(Trade trade : trades){
            if(seasonGain.applyAsDouble(trade) > 0){
                good.add(trade);
            }
        }
        return good;
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int scenarios = Integer.getInteger("scenarios", 240);
        int pool = Integer.getInteger("pool", 8);
        int top = Integer.getInteger("top", 12);
        // 'chainDepth', not 'depth' - see BoardValue.LOOKAHEAD. That property is
        // owned by something in this JVM and reads back 0, and 0 fails the
        // `depth > 1` guard below, so the whole TRADING POWER section would
        // silently not print. A flag that steals its own value is worse than a
        // missing one: nothing errors, the section just is not there.
        int depth = Integer.getInteger("chainDepth", 2);
        String me = System.getProperty("me", configuration.getUserIDToDisplayName()
                .getOrDefault(configuration.getMyID(), configuration.getMyID()));

        Map<String, Double> points = ProjectionSources.resolve("sleeper");
        WeeklyStarterValue value = WeeklyStarterValue.forCurrentBoard(configuration, points, scenarios, 424_242L);
        boolean withKeepers = Boolean.parseBoolean(System.getProperty("keepers", "true"));
        Map<String, String> ownerOf = LeagueOwners.today(configuration);
        Map<String, List<String>> rosters = new TreeMap<>();
        Map<String, String> nameOf = new HashMap<>();
        Map<String, Position> positionOf = new HashMap<>();
        for(Map.Entry<String, String> entry : ownerOf.entrySet()){
            rosters.computeIfAbsent(entry.getValue(), u -> new ArrayList<>()).add(entry.getKey());
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            nameOf.put(entry.getKey(), player == null ? entry.getKey()
                    : player.firstName + " " + player.lastName);
            positionOf.put(entry.getKey(), player == null ? null : player.position);
        }
        for(List<String> roster : rosters.values()){
            roster.sort(Comparator.comparingDouble((String id) -> -points.getOrDefault(id, 0.0)));
        }

        // WHAT EACH MAN IS WORTH TO KEEP, for every roster - a trade moves keeper
        // value as surely as it moves this season's lineup
        Map<String, Position> everyPosition = new HashMap<>(positionOf);
        for(String id : points.keySet()){
            everyPosition.computeIfAbsent(id, u -> {
                Player player = Player.getPlayerFromSIDV2(u);
                return player == null ? null : player.position;
            });
        }
        Map<Position, java.util.TreeMap<Double, Double>> bestByAdp =
                bestStillAvailable(points, everyPosition);
        Map<String, Integer> keeperRound = new HashMap<>();
        for(String manager : rosters.keySet()){
            String user = configuration.getUserIDToDisplayName().entrySet().stream()
                    .filter(e -> e.getValue().equals(manager)).map(Map.Entry::getKey)
                    .findFirst().orElse(manager);
            try {
                for(Keeper keeper : KeeperChooser.eligibleCandidates(configuration, user)){
                    keeperRound.put(keeper.player.sleeperIDString, keeper.roundCanBeKept);
                }
            }
            catch(RuntimeException notPriceable){
                // a manager whose previous-season picks are missing simply has no
                // keeper column; that is a gap to state, not a reason to stop
            }
        }
        Map<String, Double> surplus = new HashMap<>();
        for(String id : keeperRound.keySet()){
            surplus.put(id, keeperPoints(keeperRound, points, bestByAdp, everyPosition, configuration, id));
        }
        java.util.function.ToDoubleFunction<List<String>> season = ids -> value.of(ids);
        java.util.function.ToDoubleFunction<List<String>> both =
                ids -> value.of(ids) + keeperValue(ids, surplus);
        java.util.function.ToDoubleFunction<List<String>> scorer = withKeepers ? both : season;
        Side mySide = scoring(scorer);
        Side theirSide = withKeepers ? lossAverseOnKeepers(season, surplus) : scoring(season);

        StringBuilder out = new StringBuilder();
        out.append(String.format("TRADE MARKET  %s  (%s)%n", LocalDate.now(), me));
        out.append(String.format("Every size-balanced swap with all eleven rivals, both rosters priced on the same%n"
                + "weekly-starter objective (%d drawn seasons). Only trades BOTH sides gain from are listed:%n"
                + "an offer the other manager loses on is an offer he declines.%n", scenarios));
        out.append(withKeepers
                ? String.format("THE TWO SIDES ARE PRICED DIFFERENTLY, on purpose. YOUR value is the season PLUS the best two%n"
                        + "keeper surpluses on your roster (a surplus being what a man is worth beyond the pick you spend to%n"
                        + "keep him, measured at HIS OWN position). HIS is LOSS-AVERSE on keepers: he takes no credit for one%n"
                        + "he receives and feels every point of one he gives up, which is how people here actually behave.%n"
                        + "So asking for another manager's keeper is HARD - the ask is priced at what it costs him - and%n"
                        + "paying anybody IN keepers buys you nothing. -Pkeepers=false prices you the same way he is.%n%n")
                : String.format("KEEPER VALUE IS OFF for both sides (-Pkeepers=true to count yours). This prices 2026 alone,%n"
                        + "so giving up a cheap keeper looks free when it is not.%n%n"));

        List<Trade> all = new ArrayList<>();
        for(Map.Entry<String, List<String>> entry : rosters.entrySet()){
            if(entry.getKey().equals(me)){
                continue;
            }
            all.addAll(between(me, entry.getKey(), rosters.get(me), entry.getValue(), mySide, theirSide, pool));
            all.addAll(unbalanced(me, entry.getKey(), rosters.get(me), entry.getValue(),
                    mySide, theirSide, pool, points));
        }
        List<Trade> mutuallyGood = mutual(all);
        java.util.function.ToDoubleFunction<Trade> seasonGain = trade ->
                season.applyAsDouble(swap(rosters.get(me), trade.give(), trade.get()))
                        - season.applyAsDouble(rosters.get(me));
        boolean sellMode = Boolean.getBoolean("sellMode");
        List<Trade> good = sellMode ? mutuallyGood : alsoGoodThisSeason(mutuallyGood, seasonGain);
        out.append(String.format("%d swaps searched, %d good for both sides, %d of those also good for 2026.%n",
                all.size(), mutuallyGood.size(), good.size()));
        out.append(sellMode
                ? String.format("SELL MODE: trades that only pay next year are INCLUDED. Use this when the season is gone.%n%n")
                : String.format("A trade must stand up WITHOUT the keeper value as well - the plan is to win 2026, and%n"
                        + "keeper value is a tiebreak on a deal that already helps now, not a reason to get worse.%n"
                        + "%d offers were dropped for failing that. -PsellMode=true to see them.%n%n",
                        mutuallyGood.size() - good.size()));

        out.append(String.format("%-28s %-28s %7s %7s %7s %8s %11s   %s%n",
                "YOU GIVE", "YOU GET", "you", "him", "season", "SIMPLE", "ADP g/g", "WITH / HOW IT READS"));
        for(Trade trade : good.subList(0, Math.min(top, good.size()))){
            // the same trade priced the OTHER way, so a deal that only works
            // because of keepers - or only in spite of them - shows itself
            double seasonOnly = season.applyAsDouble(swap(rosters.get(me), trade.give(), trade.get()))
                    - season.applyAsDouble(rosters.get(me));
            String verdict = trade.withManager()
                    + (seasonOnly > 0 ? "" : "  NEXT YEAR ONLY");
            // is this an ask for a man HE would want to keep? He does not price
            // that, so the model says yes and the man across the table may not
            double hisKeeper = 0;
            for(String id : trade.get()){
                hisKeeper = Math.max(hisKeeper, surplus.getOrDefault(id, 0.0));
            }
            if(asksForAKeeper(hisKeeper)){
                verdict += "  (asking for a man worth keeping)";
            }
            Optics optics = optics(trade.give(), trade.get(), SleeperProjections::adpOf);
            double simple = simpleStarters(swap(rosters.get(me), trade.give(), trade.get()), points, positionOf)
                    - simpleStarters(rosters.get(me), points, positionOf);
            out.append(String.format("%-28s %-28s %+7.1f %+7.1f %+7.1f %+8.1f %5.0f/%-5.0f   %s - %s%n",
                    label(trade.give(), nameOf), label(trade.get(), nameOf),
                    trade.myGain(), trade.theirGain(), seasonOnly, simple,
                    optics.mine(), optics.theirs(), verdict, optics.verdict()));
        }
        if(good.isEmpty()){
            out.append("Nothing. Every swap that helps you costs the other man more than it gives him,\n"
                    + "which is what a league of twelve reasonable drafts usually looks like.\n");
        }

        // WHAT A PIECE IS ACTUALLY WORTH: the supply behind it
        out.append("\nWHAT YOUR SURPLUS IS WORTH - how many rivals could offer the same thing:\n");
        Map<Position, Integer> starts = Map.of(Position.QB, 1, Position.RB, 2,
                Position.WR, 3, Position.TE, 1, Position.DEF, 1);
        for(Map.Entry<Position, Integer> entry : new TreeMap<>(starts).entrySet()){
            int mineHeld = 0;
            for(String id : rosters.get(me)){
                if(positionOf.get(id) == entry.getKey()){
                    mineHeld++;
                }
            }
            int sellers = alternativeSellers(rosters, me, positionOf, entry.getKey(), entry.getValue());
            out.append(String.format("  %-4s you hold %d, start %d;  %d of 11 rivals also carry a spare -> %s%n",
                    entry.getKey(), mineHeld, entry.getValue(), sellers,
                    mineHeld <= entry.getValue() ? "nothing to sell"
                            : sellers >= 6 ? "INVENTORY, not a chip - the market is flooded"
                            : sellers <= 2 ? "SCARCE - this is where your leverage is"
                            : "ordinary supply"));
        }

        // TRADING POWER: can he just keep trading and keep improving?
        if(depth > 1){
            double tradeFloor = Double.parseDouble(System.getProperty("tradeFloor", "6.8"));
            List<Step> steps = chain(me, rosters, mySide, theirSide, depth, pool, tradeFloor);
            List<Step> churn = chain(me, rosters, mySide, theirSide, depth, pool, 0.0);
            out.append(String.format("%n(The chain below is not filtered for 2026 - it is the ceiling of what the board%n"
                    + "offers, not a plan. Read the table above for what to actually send.)%n"));
            out.append(String.format("%nTRADING POWER - trade after trade, each re-searched on the board the last one left.%n"));
            out.append(String.format("Only trades worth more than %.1f to you are taken: that is the objective's own%n"
                    + "seed-to-seed spread, so anything smaller is the yardstick moving and not the roster.%n%n", tradeFloor));
            if(steps.isEmpty()){
                out.append("  nothing at all - the board is already at a two-sided fixed point.\n");
            }
            double givenAway = 0;
            for(int i = 0; i < steps.size(); i++){
                Step step = steps.get(i);
                givenAway += step.trade().theirGain();
                out.append(String.format("  %2d. %-34s for %-34s %+7.1f  (him %+6.1f)  running %+7.1f%n",
                        i + 1, label(step.trade().give(), nameOf), label(step.trade().get(), nameOf),
                        step.trade().myGain(), step.trade().theirGain(), step.cumulative()));
            }
            if(!steps.isEmpty()){
                double total = steps.get(steps.size() - 1).cumulative();
                out.append(String.format("%n  %d trades, %+.1f to you, %+.1f handed to the men opposite to make them take it.%n",
                        steps.size(), total, givenAway));
                out.append(steps.size() < depth
                        ? String.format("  IT RAN OUT after %d, short of the %d asked for: nothing left clears the floor.%n",
                                steps.size(), depth)
                        : String.format("  Still going at %d and stopped by -PchainDepth, not by the board. Raise it.%n", depth));
                out.append(String.format("  The first trade alone was %+.1f, so the chain is worth %.1fx a single one -%n"
                        + "  which is what one-step searching leaves behind.%n",
                        steps.get(0).trade().myGain(), total / Math.max(0.1, steps.get(0).trade().myGain())));
                out.append(String.format("%n  AND THE ANSWER TO 'CAN I JUST KEEP TRADING?' - no. Ignoring the floor entirely,%n"
                        + "  the chain runs %d trades and %+.1f, but everything past the %d above is smaller than the%n"
                        + "  yardstick's own noise, and it starts churning: it reacquires men it gave away earlier.%n"
                        + "  Every trade has to improve BOTH rosters, so the league's total value only rises and is%n"
                        + "  bounded by the best possible allocation of a fixed set of players. It must stop, and it%n"
                        + "  does. Local maxima are NOT the problem here; the floor is.%n",
                        churn.size(), churn.isEmpty() ? 0 : churn.get(churn.size() - 1).cumulative(), steps.size()));
            }
        }

        out.append("\nWHAT THIS CANNOT TELL YOU. Sleeper records only COMPLETED trades - 51 across five\n");
        out.append("seasons of this league, and not one refusal - so there is no way to fit what a manager\n");
        out.append("will ACCEPT. 'His gain' is the honest stand-in: a trade that clearly helps him is one he\n");
        out.append("is likelier to take. Judge the offer by that column and by what you know about him.\n");
        System.out.print(out);
        Path target = Path.of("data", "trades-" + LocalDate.now() + ".txt");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + target);
    }

    /**
     * How a trade LOOKS by draft position, which before the season is most of
     * how it is judged.
     *
     * Justin: "before like the first week, the current adp matters, so even if a
     * pick is advantageous for both parties, people will not like to trade their
     * round n pick for my round m pick if my m is significantly > n." That is a
     * third thing, distinct from either side's valuation: two men can both gain
     * on the objective and the trade still be refused because one manager is
     * visibly handing over the earlier pick.
     *
     * The anchor is the BEST man each way, not the sum, because that is what a
     * trade gets named after - "he gave up Henry" - and because ADPs do not add:
     * two men at 90 are not one man at 45. `theirs` is the earliest ADP among the
     * men Justin receives, `mine` the earliest among those he sends. A negative
     * gap means he is receiving the earlier pick and should expect resistance
     * however good the arithmetic looks.
     */
    /**
     * Is this an ask for a man the other manager would want to keep? One home
     * for the rule: the console used to re-decide it in JavaScript, so tuning
     * the number here would have stopped the terminal flagging trades the page
     * kept flagging, with nothing failing.
     */
    static final double HIS_KEEPER_POINTS = 25;

    /**
     * Picks of draft position past which an ask reads as a grab.
     *
     * One home for it: Optics.verdict says "he will feel that" at this number,
     * and the console's good-partner filter has to agree with the sentence
     * printed beside it, or the page calls a trade fair and describes it as
     * something he will resent in the same row.
     */
    static final double OPTICS_GRAB = 25;

    static boolean asksForAKeeper(double hisKeeperSurplus){
        return hisKeeperSurplus > HIS_KEEPER_POINTS;
    }

    public record Optics(double mine, double theirs, int menEachWay) {

        /**
         * Picks of draft position between the two headline men. A LOWER ADP is an
         * EARLIER pick, so a POSITIVE gap means the man arriving was drafted
         * earlier than the man leaving - Justin is asking for the better pick,
         * which is the hard direction.
         */
        public double gap(){
            return mine - theirs;
        }

        public String verdict(){
            double gap = gap();
            if(gap >= 60){
                return "you are asking for a much earlier pick - expect a no on sight";
            }
            if(gap >= OPTICS_GRAB){
                return "you are asking for the earlier pick - he will feel that";
            }
            if(gap <= -25){
                return "you hand over the earlier pick - easy for him to say yes to";
            }
            return "reads even on draft position";
        }
    }

    /** The earliest ADP on each side of a trade. */
    static Optics optics(List<String> give, List<String> get,
                         java.util.function.ToDoubleFunction<String> adpOf){
        double mine = Double.MAX_VALUE, theirs = Double.MAX_VALUE;
        for(String id : give){ mine = Math.min(mine, adpOf.applyAsDouble(id)); }
        for(String id : get){ theirs = Math.min(theirs, adpOf.applyAsDouble(id)); }
        return new Optics(mine, theirs, Math.max(give.size(), get.size()));
    }

    /**
     * THE SIMPLE MODEL: what the best legal ten projects, and nothing else.
     *
     * Justin wants both numbers on the table, and the reason is social rather
     * than statistical: "some league members have a preference for the simple,
     * and other league members have a preference for the complex." The complex
     * one - {@link WeeklyStarterValue} - prices a bench by how often it is
     * promoted, draws whole historical seasons for injuries and boom-or-bust,
     * and is the better number. It is also unarguable-with over a chat message.
     * This one adds up the starters anybody can see, so a trade can be made the
     * case for in the terms the other manager already uses.
     *
     * They will sometimes disagree, and when they do that IS the argument: a
     * trade good on starters and bad on the full model is one where the bench or
     * the injury risk is doing the work.
     */
    static double simpleStarters(List<String> roster, Map<String, Double> points,
                                 Map<String, Position> positionOf){
        return simpleLineup(roster, points, positionOf).starters();
    }

    /** How many of the ten slots this roster can actually fill. */
    static int slotsFilled(List<String> roster, Map<String, Double> points,
                           Map<String, Position> positionOf){
        return simpleLineup(roster, points, positionOf).starting().size();
    }

    private static TeamRankings.Lineup simpleLineup(List<String> roster, Map<String, Double> points,
                                                    Map<String, Position> positionOf){
        List<TeamRankings.Man> men = new ArrayList<>();
        for(String id : roster){
            Position position = positionOf.get(id);
            men.add(new TeamRankings.Man(id, id, position == null ? "?" : position.name(), "",
                    points.getOrDefault(id, 0.0), false, 0, ""));
        }
        return TeamRankings.bestLineup(men);
    }

    /** One step of a chain: the trade taken, and where the roster stood after it. */
    public record Step(Trade trade, double cumulative) {}

    /**
     * What a man is worth to KEEP next year, in this season's points.
     *
     * Justin's objection, and it is the right one: a trade priced only on this
     * season cannot see that giving up Tuten gives up a round-12 keeper. The
     * league's own surplus definition is in picks - {@link
     * KeeperChooser#adpSurplus} is "the pick I would spend minus where the
     * player actually goes" - and picks do not add to a roster value measured in
     * points. So it is converted honestly: what he projects, minus what the pick
     * you would spend on him actually buys, taken off the current board.
     *
     * Zero for a man the rules will not let you keep, and never negative: a
     * keeper you would not declare costs nothing, you simply do not declare him.
     *
     * THE COMPARISON IS AT HIS OWN POSITION, and the first version was not.
     * Measured against the whole board it made every quarterback a franchise
     * keeper - Purdy read a surplus of 272 and Nix 208 - because this league
     * pays 6 for a passing touchdown while the ADP it drafts against is
     * calibrated for 4, so a quarterback's projection towers over a board sorted
     * by draft position. The board even ran backwards: the man at pick 175
     * "projected" 314.9 against 225.5 at pick 18. What a keeper saves is the
     * pick, and what the pick buys is THE BEST MAN AT HIS POSITION STILL THERE -
     * which is the quantity this compares him with.
     */
    static double keeperPoints(Map<String, Integer> keeperRound, Map<String, Double> points,
                               Map<Position, java.util.TreeMap<Double, Double>> bestByAdp,
                               Map<String, Position> positionOf, AAAConfiguration configuration, String id){
        Integer round = keeperRound.get(id);
        Position position = positionOf.get(id);
        if(round == null || position == null){
            return 0;
        }
        java.util.TreeMap<Double, Double> atPosition = bestByAdp.get(position);
        if(atPosition == null){
            return 0;
        }
        double pick = configuration.pickNumberFor(round);
        // Nobody at his position has an ADP that late: everyone is gone by then,
        // so the pick buys a waiver-level man and the keeper saves his whole
        // projection. Falling back to the deepest entry instead would credit him
        // with a replacement who does not exist.
        Map.Entry<Double, Double> replacement = atPosition.ceilingEntry(pick);
        double available = replacement == null ? 0 : replacement.getValue();
        return Math.max(0, points.getOrDefault(id, 0.0) - available);
    }

    /**
     * Per position, ADP -> the best projection still on the board at that ADP or
     * later. Walked from the back so each entry is a running maximum: what the
     * best man at this position is worth if you wait until this pick.
     */
    static Map<Position, java.util.TreeMap<Double, Double>> bestStillAvailable(
            Map<String, Double> points, Map<String, Position> positionOf){
        Map<Position, List<String>> byPosition = new java.util.EnumMap<>(Position.class);
        for(String id : points.keySet()){
            Position position = positionOf.get(id);
            if(position != null && position != Position.OTHER){
                byPosition.computeIfAbsent(position, u -> new ArrayList<>()).add(id);
            }
        }
        Map<Position, java.util.TreeMap<Double, Double>> out = new java.util.EnumMap<>(Position.class);
        for(Map.Entry<Position, List<String>> entry : byPosition.entrySet()){
            List<String> men = entry.getValue();
            men.sort(Comparator.comparingDouble(SleeperProjections::adpOf));
            java.util.TreeMap<Double, Double> curve = new java.util.TreeMap<>();
            double running = 0;
            for(int i = men.size() - 1; i >= 0; i--){
                running = Math.max(running, points.getOrDefault(men.get(i), 0.0));
                curve.put(SleeperProjections.adpOf(men.get(i)), running);
            }
            out.put(entry.getKey(), curve);
        }
        return out;
    }

    /**
     * A roster's keeper value: the best TWO surpluses on it, because two is all
     * the league lets anybody keep. A third good keeper is worth nothing next
     * March and must not be counted as though it were.
     */
    static double keeperValue(List<String> roster, Map<String, Double> surplus){
        List<Double> best = new ArrayList<>();
        for(String id : roster){
            best.add(surplus.getOrDefault(id, 0.0));
        }
        best.sort(Comparator.reverseOrder());
        double total = 0;
        for(int i = 0; i < Math.min(2, best.size()); i++){
            total += best.get(i);
        }
        return total;
    }

    /**
     * Trade after trade, each one re-searched on the board the last one left,
     * until nothing mutually good is left.
     *
     * This is the question "can I just keep trading and keep improving?" asked
     * properly. It has to terminate: every trade in the chain improves BOTH
     * rosters on the same objective, so the total value held across the league
     * strictly increases, and that total is bounded by the best possible
     * allocation of a fixed set of players. The chain stops when no swap helps
     * two sides at once - which is a real fixed point and not a search limit.
     * What is worth knowing is how FAR away it is, and how much of the gain is
     * his rather than given away to keep the trades acceptable.
     */
    /**
     * WHAT A TRADE OPENS UP, not just what it is worth.
     *
     * Justin: "I'd prefer to do a +5 trade that opens up a total possibility of
     * a chain of +80 trades, than a +20 trade that only opens up a chain of
     * +45." That is a different question from the one the table answers, and
     * the greedy chain cannot answer it either: `chain` always takes the biggest
     * step available, so it finds the +20 and never learns what the +5 leads to.
     *
     * A first trade changes the board for everybody - it moves men onto and off
     * two rosters - so the trades available afterwards are not the trades
     * available now. This forces the given first move, then lets the greedy
     * chain run from the board that move leaves, and returns the WHOLE sequence
     * with the forced trade as step one. Its last cumulative is what the first
     * trade is really worth.
     *
     * `chainPool` is DELIBERATELY SMALLER than the pool the headline table uses,
     * and this is the honest trade-off rather than a corner cut quietly. One
     * board search at pool 8 is about 4,200 candidate trades a rival, each
     * costing two runs of the objective; a lookahead over twelve first moves at
     * depth six is seventy-two of those searches, which is hours. At pool 4 it
     * is roughly three hundred a rival - thirteen times cheaper - and the chain
     * still sees every side's best few men, which is where chains come from.
     * The page states the pool it used, so the number can be read for what it
     * is: a floor on what the trade opens up, not a ceiling.
     */
    static List<Step> chainAfter(String me, Map<String, List<String>> rosters, Trade first,
                                 Side myValue, Side theirValue,
                                 int maxSteps, int chainPool, double floor){
        Map<String, List<String>> board = new TreeMap<>();
        for(Map.Entry<String, List<String>> entry : rosters.entrySet()){
            board.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        board.put(me, swap(board.get(me), first.give(), first.get()));
        board.put(first.withManager(), swap(board.get(first.withManager()), first.get(), first.give()));

        List<Step> steps = new ArrayList<>();
        steps.add(new Step(first, first.myGain()));
        for(Step step : chain(me, board, myValue, theirValue, maxSteps - 1, chainPool, floor)){
            steps.add(new Step(step.trade(), first.myGain() + step.cumulative()));
        }
        return steps;
    }

    /** The total a first trade is worth once everything it unlocks is counted. */
    static double reach(List<Step> chain){
        return chain.isEmpty() ? 0 : chain.get(chain.size() - 1).cumulative();
    }

    static List<Step> chain(String me, Map<String, List<String>> rosters,
                            Side myValue, Side theirValue,
                            int maxSteps, int pool, double floor){
        Map<String, List<String>> board = new TreeMap<>();
        for(Map.Entry<String, List<String>> entry : rosters.entrySet()){
            board.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        List<Step> steps = new ArrayList<>();
        double cumulative = 0;
        for(int step = 0; step < maxSteps; step++){
            List<Trade> candidates = new ArrayList<>();
            for(Map.Entry<String, List<String>> entry : board.entrySet()){
                if(entry.getKey().equals(me)){
                    continue;
                }
                candidates.addAll(between(me, entry.getKey(), board.get(me), entry.getValue(),
                        myValue, theirValue, pool));
            }
            List<Trade> good = mutual(candidates);
            // A TRADE UNDER THE FLOOR IS NOT A TRADE. The objective's own
            // seed-to-seed spread is 6.8 points (ObjectiveStability), so a chain
            // that keeps accepting +0.4 and +0.9 is not improving a roster, it is
            // walking around inside the yardstick. Run without this the chain went
            // twenty deep, and step 17 traded BACK for the man step 1 gave away.
            if(good.isEmpty() || good.get(0).myGain() < floor){
                break;
            }
            Trade best = good.get(0);
            board.put(me, swap(board.get(me), best.give(), best.get()));
            board.put(best.withManager(), swap(board.get(best.withManager()), best.get(), best.give()));
            cumulative += best.myGain();
            steps.add(new Step(best, cumulative));
        }
        return steps;
    }

    private static String label(List<String> ids, Map<String, String> nameOf){
        List<String> names = new ArrayList<>();
        for(String id : ids){
            names.add(nameOf.getOrDefault(id, id));
        }
        return String.join(" + ", names);
    }
}
