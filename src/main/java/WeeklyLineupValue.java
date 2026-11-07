import PlayerImportAndSetup.Position;

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

    WeeklyLineupValue(TreeMap<Integer, Map<String, Double>> projected,
                      TreeMap<Integer, Map<Position, List<Map.Entry<String, Double>>>> free,
                      java.util.function.Function<String, Position> positionOf, String label){
        this.projected = projected;
        this.free = free;
        this.positionOf = positionOf;
        this.label = label;
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
                total += filled(scratch[QB], 1) + filled(scratch[RB], 2) + filled(scratch[WR], 3) + filled(scratch[TE], 1)
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
                total += (first == EMPTY ? 0 : first) + (second == EMPTY ? 0 : second);
            }
            return total;
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
     * The objective on one pricing ("sleeper-remaining", "ros" or "fantasypros")
     * for the weeks from the one in progress to the end of the fantasy playoffs,
     * with every man rostered in the league today kept off the wire.
     */
    public static WeeklyLineupValue forSource(AAAConfiguration configuration, String source){
        TitleOdds.League league = TitleOdds.league(configuration);
        Set<String> owned = LeagueOwners.today(configuration).keySet();
        Map<String, Double> rates = "ros".equals(source) ? RosModel.rates() : Map.of();
        Set<String> started = NflverseGames.kickedOff(NflverseGames.games(), league.season(), league.thisWeek(),
                java.time.LocalDateTime.now(java.time.ZoneId.of("America/New_York")));
        java.util.function.Function<String, Position> positionOf = id -> {
            Player p = Player.getPlayerFromSIDV2(id);
            return p == null ? null : p.position;
        };
        Map<String, Double> scale = Map.of();
        if("fantasypros".equals(source)){
            List<Map<String, Double>> weeks = new ArrayList<>();
            for(int w = league.thisWeek(); w <= league.lastWeek(); w++){
                weeks.add(LeagueWeek.projected(league.season(), w));   // every week priced, games under way included
            }
            scale = perGameScale(weeks, fantasyProsRanks(), positionOf);
        }
        TreeMap<Integer, Map<String, Double>> projected = new TreeMap<>();
        TreeMap<Integer, Map<Position, List<Map.Entry<String, Double>>>> free = new TreeMap<>();
        for(int w = league.thisWeek(); w <= league.lastWeek(); w++){
            Map<String, Double> week = new HashMap<>();
            for(Map.Entry<String, Double> e : LeagueWeek.projected(league.season(), w).entrySet()){
                if(w == league.thisWeek() && started.contains(ProjectionSources.teamOf(e.getKey()))){
                    continue;           // his game this week is under way or over: a move now cannot reach it
                }
                week.put(e.getKey(), rates.getOrDefault(e.getKey(), e.getValue()) * scale.getOrDefault(e.getKey(), 1.0));
            }
            projected.put(w, week);
            free.put(w, freeAgents(week, owned, positionOf));
        }
        return new WeeklyLineupValue(projected, free, positionOf,
                "weekly lineups on " + source + ", weeks " + league.thisWeek() + "-" + league.lastWeek());
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
