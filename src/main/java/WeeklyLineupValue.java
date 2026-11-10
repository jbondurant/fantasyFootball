import PlayerImportAndSetup.Position;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * THE TRADE FINDER'S OBJECTIVE SINCE 2026-10-23: a roster is worth the points
 * of its best legal lineup in every week left, each week on that week's
 * projection, with that week's best free agents in the pool.
 *
 * Justin, 2026-10-23: "should we fix the trade finder to assume best available
 * replacements for each week are on roster. for their selected week(s) where
 * they are the best option?" - and, of the roster spot a pickup needs: "let's
 * do it with no roster space required". WeeklyStarterValue floors every slot
 * at one fixed wire level a position, the man at a fixed rank on the whole
 * board, and spreads each man's total evenly over seventeen weeks, so a bye is
 * never a hole in a particular week. On 23 October that level sat 11.5 below
 * the best free back and 16.4 below the best free receiver, which made a
 * bench of spare backs and receivers look worth more than it is.
 *
 * Here each week is its own lineup: the men with a projection that week (a bye
 * or a man ruled out has none), and the week's best free agents - 1 QB, 4 RB,
 * 4 WR, 2 TE, 1 DEF (TitleOdds.WIRE_DEPTH) - behind them, a free agent starting
 * only in a week he beats the man he replaces. No roster spot is charged for
 * him (Justin's call), and no competition for him: a fill-in is added on the
 * off days, when claims here are rarely contested (FaabDemand). Today's free
 * agents stand for every future week. The weeks are the one in progress (the
 * men not yet kicked off) through the last week of the fantasy playoffs, so a
 * gain is points over those weeks.
 *
 * Three pricings:
 *   sleeper-remaining - Sleeper's league-scored projection for each week;
 *   ros - each man's rest-of-season rate per played game (RosModel.rates) in
 *         the weeks Sleeper projects him to play; a man the model does not
 *         read keeps Sleeper's number;
 *   fantasypros - FantasyPros' rest-of-season consensus ORDER on Sleeper's
 *         SCALE (2026-10-29). Its pages carry points too (r2p_pts), but on its
 *         own standard scoring, not this league's six-point passing touchdowns,
 *         so the rank is what is read: the man it ranks k-th at his position is
 *         given the k-th best points PER GAME Sleeper projects at that position
 *         over the same weeks, in the weeks Sleeper projects him to play, in the
 *         shape Sleeper gives them (matchups, a partial week). Per game and not
 *         per season: the first version read the k-th largest TOTAL onto his
 *         own, which crammed a season into the few weeks Sleeper has a man
 *         starting - free agent Deshaun Watson, QB29, priced at 48 a week and
 *         every roster's quarterback in weeks 8-10 (caught in review, before
 *         it priced a trade). What this leaves: FantasyPros' rank folds in the
 *         games a man will miss, and Sleeper's weeks count them again, so a man
 *         out for weeks reads somewhat low here. Justin had found FantasyPros ranking Nabers
 *         (WR7) ahead of Justin Jefferson (WR12) where Sleeper's numbers put
 *         Jefferson ahead, and could not say how to weight the sources: "i
 *         don't know how to weight each". The trade explorer finds trades on
 *         Sleeper's numbers and lets this one veto a trade it calls a loss for
 *         him, which needs no weights. A man FantasyPros does not rank, or whom Sleeper
 *         projects to play no more, keeps Sleeper's numbers.
 *
 * A CLAIM is priced against the wire WITHOUT the man claimed (of with
 * offWire): with every free agent in every lineup's pool for nothing, the best
 * one is already "yours" in the roster you have, and claiming him would read
 * as worth nothing. In life a man you do not claim is a man somebody else can,
 * so a claim is worth what he adds over the wire he leaves behind. A man on the
 * roster being priced is never also on its wire.
 *
 * What it gives up: WeeklyStarterValue drew whole seasons of outcomes, so a
 * bench carried insurance against injuries that have not happened. This sees
 * only the injuries Sleeper already projects. With the wire as strong as it
 * is, that insurance is mostly the wire anyway; TitleOdds keeps the luck.
 */
public class WeeklyLineupValue implements RosterValue {

    /** week -> every man projected to play that week -> his points that week, on this pricing. */
    private final TreeMap<Integer, Map<String, Double>> projected;
    /** week -> position -> that week's free agents at it, best first. */
    private final TreeMap<Integer, Map<Position, List<Map.Entry<String, Double>>>> free;
    private final java.util.function.Function<String, Position> positionOf;
    private final String label;
    /** id -> how much of his numbers are this pricing's own (coverage); null when all of them are, for every man. */
    private final Map<String, Integer> coverage;

    WeeklyLineupValue(TreeMap<Integer, Map<String, Double>> projected,
                      TreeMap<Integer, Map<Position, List<Map.Entry<String, Double>>>> free,
                      java.util.function.Function<String, Position> positionOf, String label){
        this(projected, free, positionOf, label, null);
    }

    WeeklyLineupValue(TreeMap<Integer, Map<String, Double>> projected,
                      TreeMap<Integer, Map<Position, List<Map.Entry<String, Double>>>> free,
                      java.util.function.Function<String, Position> positionOf, String label, Map<String, Integer> coverage){
        this.projected = projected;
        this.free = free;
        this.positionOf = positionOf;
        this.label = label;
        this.coverage = coverage;
    }

    /**
     * How much of a man's numbers on this pricing are its own: 2 all of them, 1
     * some of his weeks (ESPN leaves a week empty; Kalshi prices this week's
     * game and Polymarket has no season line for him), 0 none - he is on
     * Sleeper's numbers under this pricing's label. The Check-a-trade tab says
     * which.
     */
    public int coverage(String id){
        return coverage == null ? 2 : coverage.getOrDefault(id, 0);
    }

    @Override
    public double of(Collection<String> roster){
        return of(roster, List.of());
    }

    /** The roster's worth with these free agents taken off the wire as well as every man on the roster. */
    @Override
    public double of(Collection<String> roster, Collection<String> offWire){
        Set<String> gone = new java.util.HashSet<>(roster);
        gone.addAll(offWire);
        double total = 0;
        for(Map.Entry<Integer, Map<String, Double>> week : projected.entrySet()){
            total += week(roster, week.getValue(), free.getOrDefault(week.getKey(), Map.of()), gone);
        }
        return total;
    }

    /** One week's best legal lineup: the roster's men projected that week, and the best free agents still on the wire. */
    double week(Collection<String> roster, Map<String, Double> projection, Map<Position, List<Map.Entry<String, Double>>> wire,
                Set<String> gone){
        return lineup(roster, projection, wire, gone).starters();
    }

    private TeamRankings.Lineup lineup(Collection<String> roster, Map<String, Double> projection,
                                       Map<Position, List<Map.Entry<String, Double>>> wire, Set<String> gone){
        List<TeamRankings.Man> men = new ArrayList<>();
        for(String id : roster){
            Double points = projection.get(id);
            Position p = points == null ? null : positionOf.apply(id);
            if(p != null && p != Position.OTHER){
                men.add(new TeamRankings.Man(id, id, p.name(), "", points, false, 0, null));
            }
        }
        wire.forEach((position, list) -> {
            int depth = TitleOdds.WIRE_DEPTH.getOrDefault(position, 0), taken = 0;
            for(Map.Entry<String, Double> man : list){
                if(taken >= depth){
                    break;
                }
                if(!gone.contains(man.getKey())){
                    taken++;
                    men.add(new TeamRankings.Man(TitleOdds.WIRE + position.name() + ":" + taken, man.getKey(), position.name(),
                            "", man.getValue(), false, 0, null));
                }
            }
        });
        return TeamRankings.bestLineup(men);
    }

    /** One week as the objective plays it: the lineup with the wire, the lineup of the men you hold, and who starts off the wire (id -> points). */
    public record WeekFill(int week, double withWire, double ownMen, Map<String, Double> fillIns){}

    /**
     * THE CLAIMS THIS OBJECTIVE TAKES FOR GRANTED. Justin, 2026-11-03: "my faab so
     * far hasn't served a purpose yet". Every lineup here starts a free agent
     * whenever one beats the man he replaces, for nothing, so a claim only shows up
     * as a gain if it beats the wire left behind it. That is the right way to judge
     * holding a man and the wrong way to read the week: "do nothing" on the board
     * does not mean the fill-in starts himself. These are the pickups the number
     * assumes, week by week, and what each week's lineup loses without them.
     */
    public List<WeekFill> fillIns(Collection<String> roster){
        Set<String> gone = new java.util.HashSet<>(roster);
        List<WeekFill> out = new ArrayList<>();
        for(Map.Entry<Integer, Map<String, Double>> week : projected.entrySet()){
            TeamRankings.Lineup with = lineup(roster, week.getValue(), free.getOrDefault(week.getKey(), Map.of()), gone);
            double own = lineup(roster, week.getValue(), Map.of(), gone).starters();
            Map<String, Double> fills = new java.util.LinkedHashMap<>();
            for(TeamRankings.Man man : with.starting()){
                if(man.id().startsWith(TitleOdds.WIRE)){
                    fills.put(man.name(), man.points());     // a wire man's name field carries his id (lineup)
                }
            }
            out.add(new WeekFill(week.getKey(), with.starters(), own, fills));
        }
        return out;
    }

    @Override
    public String label(){
        return label;
    }

    /** A man's points over every week this prices, on its pricing (his game this week left out once it has kicked off). */
    public double total(String id){
        return total(id, projected.firstKey());
    }

    /** A man's points over the weeks this prices from `from` on. */
    public double total(String id, int from){
        double sum = 0;
        for(Map<String, Double> week : projected.tailMap(from).values()){
            sum += week.getOrDefault(id, 0.0);
        }
        return sum;
    }

    /** Every man this prices in some week. */
    public Set<String> men(){
        Set<String> out = new java.util.HashSet<>();
        projected.values().forEach(week -> out.addAll(week.keySet()));
        return out;
    }

    /**
     * THE SAME OBJECTIVE AS ARRAYS, FOR SEARCHING WHOLE ROSTERS. Justin, 2026-11-06, of the
     * trade search's top-eight limit: "that's not an optimization i agree with". Every man in
     * `men` gets an index; of(men) is what of(roster) returns, to rounding (the test proves it),
     * in about a microsecond instead of building records and maps and sorting nine times.
     * Every man in `men` is held by somebody, so none is on the wire, and a trade among them
     * cannot change the wire: each week's free agents are fixed up front.
     */
    public static final class Table {
        static final double EMPTY = Double.NEGATIVE_INFINITY;
        private static final int QB = 0, RB = 1, WR = 2, TE = 3, DEF = 4;
        /** How many of each position a week can use: the fixed slots, and enough to fill both FLEX. */
        private static final int[] KEEP = {1, 2 + TeamRankings.FLEX, 3 + TeamRankings.FLEX, 1 + TeamRankings.FLEX, 1};
        private final double[][] points;     // week -> man -> points, NaN for no projection
        private final byte[] slot;           // man -> position index, -1 for none
        private final double[][][] wire;     // week -> position -> the week's best free agents, padded with EMPTY

        private Table(double[][] points, byte[] slot, double[][][] wire){
            this.points = points;
            this.slot = slot;
            this.wire = wire;
        }

        /** Working space for of; one per thread. */
        public double[][] scratch(){
            double[][] out = new double[KEEP.length][];
            for(int i = 0; i < KEEP.length; i++){
                out[i] = new double[KEEP[i]];
            }
            return out;
        }

        /** The first `count` men of `men` as a roster: the sum over every week of its best legal lineup. */
        public double of(int[] men, int count, double[][] scratch){
            return of(men, count, scratch, null);
        }

        /** The same, writing each week's lineup into byWeek when it is given. */
        public double of(int[] men, int count, double[][] scratch, double[] byWeek){
            double total = 0;
            for(int w = 0; w < points.length; w++){
                double[] week = points[w];
                for(int i = 0; i < KEEP.length; i++){
                    System.arraycopy(wire[w][i], 0, scratch[i], 0, KEEP[i]);
                }
                for(int i = 0; i < count; i++){
                    int man = men[i];
                    double p = week[man];
                    int at = slot[man];
                    if(at >= 0 && p == p){      // not NaN
                        double[] top = scratch[at];
                        int j = top.length - 1;
                        if(p > top[j]){
                            while(j > 0 && top[j - 1] < p){
                                top[j] = top[j - 1];
                                j--;
                            }
                            top[j] = p;
                        }
                    }
                }
                double lineup = filled(scratch[QB], 1) + filled(scratch[RB], 2) + filled(scratch[WR], 3) + filled(scratch[TE], 1)
                        + filled(scratch[DEF], 1);
                double first = EMPTY, second = EMPTY;
                for(int at = RB; at <= TE; at++){
                    double[] top = scratch[at];
                    for(int j = KEEP[at] - TeamRankings.FLEX; j < top.length; j++){
                        double p = top[j];
                        if(p > first){
                            second = first;
                            first = p;
                        }
                        else if(p > second){
                            second = p;
                        }
                    }
                }
                lineup += (first == EMPTY ? 0 : first) + (second == EMPTY ? 0 : second);
                total += lineup;
                if(byWeek != null){
                    byWeek[w] = lineup;
                }
            }
            return total;
        }

        /** How many weeks this prices. */
        public int weeks(){
            return points.length;
        }

        private static double filled(double[] top, int slots){
            double sum = 0;
            for(int i = 0; i < slots; i++){
                if(top[i] != EMPTY){
                    sum += top[i];
                }
            }
            return sum;
        }
    }

    /** This pricing as a Table over these men, whose order is the table's index. */
    public Table table(List<String> men){
        Map<Position, Integer> at = Map.of(Position.QB, 0, Position.RB, 1, Position.WR, 2, Position.TE, 3, Position.DEF, 4);
        Position[] order = {Position.QB, Position.RB, Position.WR, Position.TE, Position.DEF};
        Set<String> held = new java.util.HashSet<>(men);
        byte[] slot = new byte[men.size()];
        for(int i = 0; i < slot.length; i++){
            Position p = positionOf.apply(men.get(i));
            slot[i] = p == null ? -1 : at.getOrDefault(p, -1).byteValue();
        }
        double[][] points = new double[projected.size()][men.size()];
        double[][][] wire = new double[projected.size()][order.length][];
        int w = 0;
        for(Map.Entry<Integer, Map<String, Double>> week : projected.entrySet()){
            for(int i = 0; i < slot.length; i++){
                Double p = week.getValue().get(men.get(i));
                points[w][i] = p == null ? Double.NaN : p;
            }
            Map<Position, List<Map.Entry<String, Double>>> agents = free.getOrDefault(week.getKey(), Map.of());
            for(int i = 0; i < order.length; i++){
                double[] top = new double[Table.KEEP[i]];
                java.util.Arrays.fill(top, Table.EMPTY);
                int taken = 0, depth = TitleOdds.WIRE_DEPTH.getOrDefault(order[i], 0);
                for(Map.Entry<String, Double> man : agents.getOrDefault(order[i], List.of())){
                    if(taken >= depth || taken >= top.length){
                        break;
                    }
                    if(!held.contains(man.getKey())){
                        top[taken++] = man.getValue();
                    }
                }
                wire[w][i] = top;
            }
            w++;
        }
        return new Table(points, slot, wire);
    }

    /** The weeks this prices, first to last. */
    public List<Integer> weeks(){
        return new ArrayList<>(projected.keySet());
    }

    /** The week's best free agents at a position, best first, as the pool carries them. */
    public List<Double> wire(int week, Position position){
        List<Double> out = new ArrayList<>();
        List<Map.Entry<String, Double>> list = free.getOrDefault(week, Map.of()).getOrDefault(position, List.of());
        for(int k = 0; k < Math.min(TitleOdds.WIRE_DEPTH.getOrDefault(position, 0), list.size()); k++){
            out.add(list.get(k).getValue());
        }
        return out;
    }

    /**
     * The pricings a search may be FOUND on (-Pprojections for TradeMarket and
     * TuesdaySwap under the weekly objective): the ones ProjectionSources also
     * resolves to season points, which order the cuts. The others are for the
     * Check-a-trade tab; named there, their cut order would silently be the
     * season feed's (review, 2026-11-08).
     */
    public static final List<String> FINDERS = List.of("sleeper-remaining", "sleeper", "ros");

    /** Refuses a -Pprojections name the weekly objective cannot search on. */
    public static void requireFinder(String source){
        if(!FINDERS.contains(source)){
            throw new IllegalArgumentException("-Pprojections=" + source + " has no weekly search; use one of " + String.join(", ", FINDERS)
                    + (SOURCES.contains(source) ? " (the Check-a-trade tab prices trades on " + source + ")" : " or -Pobjective=drawn"));
        }
    }

    /**
     * The pricings forSource knows. Any other name is refused: until 2026-11-07
     * an unknown name was priced on Sleeper's weekly numbers under its own label.
     * "sleeper" and "sleeper-remaining" are the same weekly numbers.
     */
    public static final List<String> SOURCES = List.of("sleeper-remaining", "sleeper", "sleeper-next", "ros", "fantasypros", "espn", "espn-next",
            "cbs", "market", "pace", "last3");

    /**
     * The objective on one pricing for the weeks from the one in progress to the
     * end of the fantasy playoffs, with every man rostered in the league today
     * kept off the wire. Every pricing starts from Sleeper's weekly projections:
     * a man is in a week only where Sleeper projects him, and games under way
     * this week are out.
     *
     * Sleeper's own weeks and the model (ros) price every man. EVERY OTHER SOURCE
     * PRICES THE MEN ON ROSTERS, and the wire stays on Sleeper's weeks. A source
     * here gives one number for a man's rest of season, and the wire is the best
     * free agent of each week: a season number laid over a fill-in's one start
     * made him every roster's pickup. That happened four times before the rule
     * was made general (2026-11-10): pace and the last three games (a 25.5-point
     * waiver quarterback), CBS (27.9), the next-game forms (a 9.7 waiver back in
     * every week from one projected start) and FantasyPros (54.9).
     *
     * On the men a source prices:
     *  - ros, pace, last3, cbs, sleeper-next, espn-next: a flat rate in each of
     *    his weeks (RosModel's blend; his points a game so far; over his last
     *    three games; CBS's rest-of-season points over its games left; Sleeper's
     *    or ESPN's projection for his next game not yet kicked off, the form
     *    RosModel scores them in, quarterbacks, backs, receivers and tight ends);
     *  - fantasypros: his FantasyPros rank read onto Sleeper's points a game
     *    (perGameScale), as a factor on his weeks;
     *  - espn: ESPN's own projection in each week it has him above zero
     *    (EspnWeekly), and Sleeper's in a week it leaves empty;
     *  - market: the betting markets (Justin, 2026-11-08: "add betting markets as
     *    a source with not measured accuracy"). Kalshi's props in place of
     *    Sleeper's stat line for this week's game where Kalshi prices him
     *    (MarketProjection); everywhere else Sleeper's numbers times his
     *    Polymarket season ladders' factor (SeasonProps.marketFactors), where a
     *    ladder is quoted tightly enough to have a median.
     * A man the source has no number for keeps Sleeper's (under espn-next,
     * Sleeper's next game). coverage(id) says which men those are, and the run
     * prints how many.
     */
    public static WeeklyLineupValue forSource(AAAConfiguration configuration, String source){
        if(!SOURCES.contains(source)){
            throw new IllegalArgumentException("no weekly pricing called '" + source + "'; known: " + String.join(", ", SOURCES));
        }
        TitleOdds.League league = TitleOdds.league(configuration);
        int first = league.thisWeek(), last = league.lastWeek();
        Set<String> owned = LeagueOwners.today(configuration).keySet();
        Set<String> started = NflverseGames.kickedOff(NflverseGames.games(), league.season(), first,
                java.time.LocalDateTime.now(java.time.ZoneId.of("America/New_York")));
        java.util.function.Function<String, Position> positionOf = id -> {
            Player p = Player.getPlayerFromSIDV2(id);
            return p == null ? null : p.position;
        };
        // Sleeper's weeks as every pricing takes them: a game under way or over this week is out, a move now cannot reach it
        TreeMap<Integer, Map<String, Double>> sleeper = new TreeMap<>();
        List<Map<String, Double>> unfiltered = new ArrayList<>();
        for(int w = first; w <= last; w++){
            Map<String, Double> all = LeagueWeek.projected(league.season(), w);
            unfiltered.add(all);
            Map<String, Double> week = new HashMap<>();
            for(Map.Entry<String, Double> e : all.entrySet()){
                if(w != first || !started.contains(ProjectionSources.teamOf(e.getKey()))){
                    week.put(e.getKey(), e.getValue());
                }
            }
            sleeper.put(w, week);
        }
        boolean everyone = source.equals("sleeper-remaining") || source.equals("sleeper") || source.equals("ros");
        Map<String, Double> rates = Map.of(), scale = Map.of();
        Map<Integer, Map<String, Double>> own = Map.of();
        Set<String> rated = Set.of();       // the men it has a season number for; null: Sleeper's own form, every man
        switch(source){
            case "ros" -> {
                rates = RosModel.rates();
                rated = rates.keySet();
            }
            case "pace", "last3" -> {
                rates = RosModel.recentRates(source);
                rated = rates.keySet();
            }
            case "cbs" -> {
                rates = cbsPerGame();
                rated = rates.keySet();
            }
            case "sleeper-next" -> {
                rates = skill(nextGame(sleeper, null), positionOf);
                rated = null;
            }
            case "espn-next" -> {
                Map<String, Double> theirs = skill(nextGame(sleeper, EspnWeekly.season(league.season()).byWeek()), positionOf);
                Map<String, Double> both = new HashMap<>(skill(nextGame(sleeper, null), positionOf));
                both.putAll(theirs);
                rates = both;
                rated = theirs.keySet();
            }
            case "fantasypros" -> {
                scale = perGameScale(unfiltered, fantasyProsRanks(), positionOf);   // every week priced, games under way included
                rated = scale.keySet();
            }
            case "espn" -> own = aboveZero(EspnWeekly.season(league.season()).byWeek());
            case "market" -> {
                own = Map.of(first, marketThisWeek(league));
                Set<String> projectedMen = new java.util.HashSet<>();
                unfiltered.forEach(week -> projectedMen.addAll(week.keySet()));
                try {
                    scale = SeasonProps.marketFactors(projectedMen);
                }
                catch(IOException | InterruptedException e){
                    if(e instanceof InterruptedException){
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("Polymarket could not be read: " + e, e);
                }
                rated = scale.keySet();
            }
            default -> rated = null;
        }
        TreeMap<Integer, Map<String, Double>> projected = priced(sleeper, owned, everyone, rates, scale, own);
        TreeMap<Integer, Map<Position, List<Map.Entry<String, Double>>>> free = new TreeMap<>();
        projected.forEach((w, week) -> free.put(w, freeAgents(week, owned, positionOf)));
        Map<String, Integer> coverage = rated == null ? null : coverage(sleeper, owned, rated, own);
        if(coverage != null){
            int priced = 0, all = 0, some = 0;
            for(String id : owned){
                boolean carried = false;
                for(Map<String, Double> week : sleeper.values()){
                    carried |= week.containsKey(id);
                }
                if(carried){
                    priced++;
                    all += coverage.getOrDefault(id, 0) == 2 ? 1 : 0;
                    some += coverage.getOrDefault(id, 0) == 1 ? 1 : 0;
                }
            }
            System.out.printf("%s: %d of the %d rostered men Sleeper projects priced on its own numbers%s, the rest on Sleeper's%n",
                    source, all, priced, some == 0 ? "" : ", " + some + " more in some of their weeks");
        }
        return new WeeklyLineupValue(projected, free, positionOf,
                "weekly lineups on " + source + ", weeks " + first + "-" + last, coverage);
    }

    /**
     * Each week's points on a source, from Sleeper's weeks: for a man it prices
     * (every man, or the men on rosters), its own number for that week where it
     * has one, else his flat rate where it has one, times his factor where it
     * has one; Sleeper's number otherwise, and for every man it does not price.
     */
    static TreeMap<Integer, Map<String, Double>> priced(java.util.SortedMap<Integer, Map<String, Double>> sleeper, Set<String> owned, boolean everyone,
                                                        Map<String, Double> rates, Map<String, Double> scale,
                                                        Map<Integer, Map<String, Double>> own){
        TreeMap<Integer, Map<String, Double>> out = new TreeMap<>();
        for(Map.Entry<Integer, Map<String, Double>> entry : sleeper.entrySet()){
            Map<String, Double> ownWeek = own.getOrDefault(entry.getKey(), Map.of());
            Map<String, Double> week = new HashMap<>();
            for(Map.Entry<String, Double> e : entry.getValue().entrySet()){
                String id = e.getKey();
                double value = e.getValue();
                if(everyone || owned.contains(id)){
                    Double theirs = ownWeek.get(id);
                    value = theirs != null ? theirs : rates.getOrDefault(id, value) * scale.getOrDefault(id, 1.0);
                }
                week.put(id, value);
            }
            out.put(entry.getKey(), week);
        }
        return out;
    }

    /**
     * How much of each rostered man's numbers are the source's own: 2 where it
     * has a season number for him or its own number in every week he is priced,
     * 1 where in some of them, absent where in none.
     */
    static Map<String, Integer> coverage(java.util.SortedMap<Integer, Map<String, Double>> sleeper, Set<String> owned, Set<String> rated,
                                         Map<Integer, Map<String, Double>> own){
        Map<String, Integer> out = new HashMap<>();
        for(String id : owned){
            int weeks = 0, theirs = 0;
            for(Map.Entry<Integer, Map<String, Double>> week : sleeper.entrySet()){
                if(week.getValue().containsKey(id)){
                    weeks++;
                    theirs += own.getOrDefault(week.getKey(), Map.of()).containsKey(id) ? 1 : 0;
                }
            }
            if(rated.contains(id) || weeks > 0 && theirs == weeks){
                out.put(id, 2);
            }
            else if(theirs > 0){
                out.put(id, 1);
            }
        }
        return out;
    }

    /**
     * ESPN's weekly projections where they are above zero. ESPN keeps an empty
     * line as 0.0, and an empty line is not a forecast of zero. Most men on IR
     * are empty in every week (Lamar Jackson, 2026-11-08). On the pages saved
     * 2026-09-25 ESPN left three weeks empty for a man it projected in others
     * (Jayden Daniels), and he played two of them (ForwardAccuracy prints the
     * count). The first Check-a-trade build put Sleeper's number over every
     * empty line, the second zero over every one, the third zero for a man ESPN
     * projected somewhere; reviews found each wrong (TRAPS #155). So an empty
     * week has no ESPN number, and the man keeps Sleeper's for it.
     */
    static Map<Integer, Map<String, Double>> aboveZero(Map<Integer, Map<String, Double>> espn){
        Map<Integer, Map<String, Double>> out = new HashMap<>();
        espn.forEach((week, men) -> {
            Map<String, Double> kept = new HashMap<>();
            men.forEach((id, points) -> {
                if(points > 0){
                    kept.put(id, points);
                }
            });
            out.put(week, kept);
        });
        return out;
    }

    /** The quarterbacks, backs, receivers and tight ends of a map: the positions RosModel scores a flat rate for. */
    static Map<String, Double> skill(Map<String, Double> rates, java.util.function.Function<String, Position> positionOf){
        Map<String, Double> out = new HashMap<>();
        rates.forEach((id, rate) -> {
            if(java.util.Arrays.asList(InSeasonLearning.POSITIONS).contains(positionOf.apply(id))){
                out.put(id, rate);
            }
        });
        return out;
    }

    /**
     * Each man's projection for his next game, the form RosModel scores
     * Sleeper and ESPN in (a week k+1 projection as his rate from then on):
     * Sleeper's number in the first of these weeks that carries him. With
     * ESPN's weeks, ESPN's number in the first week Sleeper carries him and
     * ESPN has him above zero; a man ESPN never has above zero there is left
     * out. The weeks are forSource's, so a game already kicked off this week is
     * not his next. RosModel's replay scored ESPN's number for the very next
     * week, a zero included, and only for men Sleeper projected that week.
     */
    static Map<String, Double> nextGame(java.util.SortedMap<Integer, Map<String, Double>> sleeper, Map<Integer, Map<String, Double>> espn){
        Map<String, Double> out = new HashMap<>();
        for(Map.Entry<Integer, Map<String, Double>> week : sleeper.entrySet()){
            Map<String, Double> theirs = espn == null ? null : espn.getOrDefault(week.getKey(), Map.of());
            for(Map.Entry<String, Double> e : week.getValue().entrySet()){
                if(out.containsKey(e.getKey())){
                    continue;
                }
                if(theirs == null){
                    out.put(e.getKey(), e.getValue());
                }
                else if(theirs.getOrDefault(e.getKey(), 0.0) > 0){
                    out.put(e.getKey(), theirs.get(e.getKey()));
                }
            }
        }
        return out;
    }

    /** CBS's rest-of-season points a game (points / games left), by Sleeper id; men with no games left are out. */
    static Map<String, Double> cbsPerGame(){
        Map<String, Double> out = new HashMap<>();
        CbsProjections.leagueLinesBySleeperID("restofseason").forEach((id, line) -> {
            if(line[1] > 0){
                out.put(id, line[0] / line[1]);
            }
        });
        return out;
    }

    /** This week's league points with every stat Kalshi prices at the market's (MarketProjection), by Sleeper id. */
    static Map<String, Double> marketThisWeek(TitleOdds.League league){
        try {
            LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
            Map<String, Double> out = new HashMap<>();
            MarketProjection.week(league.season(), league.thisWeek(), KalshiMarkets.snapshot(), PropModel.fit(), scoring)
                    .forEach((id, v) -> out.put(id, v[1]));
            return out;
        }
        catch(IOException | InterruptedException e){
            if(e instanceof InterruptedException){
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Kalshi could not be read: " + e, e);
        }
    }

    /** One man's value in one week on this pricing; null where it has none (a bye, not projected, or his game under way). */
    public Double value(int week, String id){
        return projected.getOrDefault(week, Map.of()).get(id);
    }

    /** FantasyPros' rest-of-season consensus rank of each man at his position (its half-PPR pages), by Sleeper id. */
    public static Map<String, Integer> fantasyProsRanks(){
        Map<String, Integer> out = new HashMap<>();
        for(Rank r : InSeasonProjectionsFP.getRosRanking()){
            if(r.player != null && r.player.sleeperIDString != null){
                out.putIfAbsent(r.player.sleeperIDString, r.rankNum);
            }
        }
        return out;
    }

    /** A ranking read onto Sleeper's points per projected game over these weeks (rankScale), as a factor on each man's weeks. */
    static Map<String, Double> perGameScale(List<Map<String, Double>> weeks, Map<String, Integer> rank,
                                            java.util.function.Function<String, Position> positionOf){
        Map<String, Double> totals = new HashMap<>();
        Map<String, Integer> games = new HashMap<>();
        for(Map<String, Double> week : weeks){
            week.forEach((id, pts) -> {
                totals.merge(id, pts, Double::sum);
                games.merge(id, 1, Integer::sum);
            });
        }
        Map<String, Double> perGame = new HashMap<>();
        totals.forEach((id, total) -> perGame.put(id, total / games.get(id)));
        return rankScale(perGame, rank, positionOf);
    }

    /**
     * A ranking read onto a scale: the man ranked k-th at his position is given
     * the k-th largest value at that position (the last one, if the ranking runs
     * deeper than the values), as a factor on his own value. A man the ranking
     * leaves out, or whose own value is nothing, gets no factor. forSource passes
     * points per projected game.
     */
    static Map<String, Double> rankScale(Map<String, Double> totals, Map<String, Integer> rank,
                                         java.util.function.Function<String, Position> positionOf){
        Map<Position, List<Double>> byPosition = new java.util.EnumMap<>(Position.class);
        totals.forEach((id, total) -> {
            Position p = positionOf.apply(id);
            if(p != null && total > 0){
                byPosition.computeIfAbsent(p, k -> new ArrayList<>()).add(total);
            }
        });
        byPosition.values().forEach(list -> list.sort(java.util.Comparator.reverseOrder()));
        Map<String, Double> out = new HashMap<>();
        rank.forEach((id, k) -> {
            double own = totals.getOrDefault(id, 0.0);
            List<Double> scale = byPosition.get(positionOf.apply(id));
            if(own > 0 && scale != null && k >= 1){
                out.put(id, scale.get(Math.min(k, scale.size()) - 1) / own);
            }
        });
        return out;
    }

    /** A week's free agents at each wire position, best first. */
    static Map<Position, List<Map.Entry<String, Double>>> freeAgents(Map<String, Double> week, Set<String> owned,
                                                                     java.util.function.Function<String, Position> positionOf){
        Map<Position, List<Map.Entry<String, Double>>> out = new java.util.EnumMap<>(Position.class);
        week.forEach((id, points) -> {
            Position p = owned.contains(id) ? null : positionOf.apply(id);
            if(p != null && TitleOdds.WIRE_DEPTH.containsKey(p)){
                out.computeIfAbsent(p, k -> new ArrayList<>()).add(Map.entry(id, points));
            }
        });
        out.values().forEach(list -> list.sort((a, b) -> Double.compare(b.getValue(), a.getValue())));
        return out;
    }
}
