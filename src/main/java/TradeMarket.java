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
 *   ./gradlew run -Pmain=TradeMarket [-Pme=<name>] [-Pdepth=2] [-Ptop=12]
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
     * Every size-balanced swap between two rosters, each side priced BY ITS OWN
     * LIGHTS.
     *
     * Justin: "generally people don't give away their keepers, and don't see
     * much added value in receiving keepers." That is not a detail, it is a
     * different objective on the other side of the table, and pricing both
     * rosters the same way gets the trade wrong in both directions. A rival who
     * does not count keeper value will part with a cheap keeper for season
     * points - so acquiring one is EASIER than a symmetric model thinks - and he
     * takes no credit for one he receives, so paying him in keepers is worthless
     * to him. Justin's own gain still counts his, because he does.
     */
    static List<Trade> between(String me, String them, List<String> mine, List<String> theirs,
                               java.util.function.ToDoubleFunction<List<String>> myValue,
                               java.util.function.ToDoubleFunction<List<String>> theirValue, int pool){
        double myBefore = myValue.applyAsDouble(mine);
        double theirBefore = theirValue.applyAsDouble(theirs);
        List<Trade> trades = new ArrayList<>();
        for(String give : mine){
            for(String get : theirs){
                List<String> myAfter = swap(mine, List.of(give), List.of(get));
                List<String> theirAfter = swap(theirs, List.of(get), List.of(give));
                trades.add(new Trade(them, List.of(give), List.of(get),
                        myValue.applyAsDouble(myAfter) - myBefore,
                        theirValue.applyAsDouble(theirAfter) - theirBefore));
            }
        }
        // two for two, but only among each side's most valuable few: the full
        // search is 14,400 pairs a rival and almost all of it is noise
        List<String> myTop = mine.subList(0, Math.min(pool, mine.size()));
        List<String> theirTop = theirs.subList(0, Math.min(pool, theirs.size()));
        for(int i = 0; i < myTop.size(); i++){
            for(int j = i + 1; j < myTop.size(); j++){
                for(int k = 0; k < theirTop.size(); k++){
                    for(int l = k + 1; l < theirTop.size(); l++){
                        List<String> give = List.of(myTop.get(i), myTop.get(j));
                        List<String> get = List.of(theirTop.get(k), theirTop.get(l));
                        trades.add(new Trade(them, give, get,
                                myValue.applyAsDouble(swap(mine, give, get)) - myBefore,
                                theirValue.applyAsDouble(swap(theirs, get, give)) - theirBefore));
                    }
                }
            }
        }
        return trades;
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
        int depth = Integer.getInteger("depth", 2);
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

        StringBuilder out = new StringBuilder();
        out.append(String.format("TRADE MARKET  %s  (%s)%n", LocalDate.now(), me));
        out.append(String.format("Every size-balanced swap with all eleven rivals, both rosters priced on the same%n"
                + "weekly-starter objective (%d drawn seasons). Only trades BOTH sides gain from are listed:%n"
                + "an offer the other manager loses on is an offer he declines.%n", scenarios));
        out.append(withKeepers
                ? String.format("THE TWO SIDES ARE PRICED DIFFERENTLY, on purpose. YOUR value is the season PLUS the best two%n"
                        + "keeper surpluses on your roster (a surplus being what a man is worth beyond the pick you spend to%n"
                        + "keep him, measured at HIS OWN position). HIS value is the season alone, because in this league%n"
                        + "people do not give away their keepers and see little in receiving one. Two consequences worth%n"
                        + "knowing: buying a cheap keeper off somebody is EASIER than a symmetric model would say, and%n"
                        + "paying anybody IN keepers buys you nothing. -Pkeepers=false prices you the same way he is.%n%n")
                : String.format("KEEPER VALUE IS OFF for both sides (-Pkeepers=true to count yours). This prices 2026 alone,%n"
                        + "so giving up a cheap keeper looks free when it is not.%n%n"));

        List<Trade> all = new ArrayList<>();
        for(Map.Entry<String, List<String>> entry : rosters.entrySet()){
            if(entry.getKey().equals(me)){
                continue;
            }
            all.addAll(between(me, entry.getKey(), rosters.get(me), entry.getValue(), scorer, season, pool));
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

        out.append(String.format("%-34s %-34s %8s %8s %8s   %s%n",
                "YOU GIVE", "YOU GET", "you", "him", "season", "WITH / VERDICT"));
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
            if(hisKeeper > 25){
                verdict += "  (asking for a man worth keeping)";
            }
            out.append(String.format("%-34s %-34s %+8.1f %+8.1f %+8.1f   %s%n",
                    label(trade.give(), nameOf), label(trade.get(), nameOf),
                    trade.myGain(), trade.theirGain(), seasonOnly, verdict));
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
            List<Step> steps = chain(me, rosters, scorer, season, depth, pool, tradeFloor);
            List<Step> churn = chain(me, rosters, scorer, season, depth, pool, 0.0);
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
                        : String.format("  Still going at %d and stopped by -Pdepth, not by the board. Raise it.%n", depth));
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
    static List<Step> chain(String me, Map<String, List<String>> rosters,
                            java.util.function.ToDoubleFunction<List<String>> myValue,
                            java.util.function.ToDoubleFunction<List<String>> theirValue,
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
