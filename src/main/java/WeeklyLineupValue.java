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
 * Two pricings, as the trade tools always use:
 *   sleeper-remaining - Sleeper's league-scored projection for each week;
 *   ros - each man's rest-of-season rate per played game (RosModel.rates) in
 *         the weeks Sleeper projects him to play; a man the model does not
 *         read keeps Sleeper's number.
 *
 * What it gives up: WeeklyStarterValue drew whole seasons of outcomes, so a
 * bench carried insurance against injuries that have not happened. This sees
 * only the injuries Sleeper already projects. With the wire as strong as it
 * is, that insurance is mostly the wire anyway; TitleOdds keeps the luck.
 */
public class WeeklyLineupValue implements RosterValue {

    private final TreeMap<Integer, Map<String, Double>> weeks;
    private final String label;

    WeeklyLineupValue(TreeMap<Integer, Map<String, Double>> weeks, String label){
        this.weeks = weeks;
        this.label = label;
    }

    @Override
    public double of(Collection<String> roster){
        List<String> men = new ArrayList<>(roster);
        double total = 0;
        for(Map<String, Double> week : weeks.values()){
            total += TitleOdds.weekMean(men, week);
        }
        return total;
    }

    @Override
    public String label(){
        return label;
    }

    /** The weeks this prices, first to last. */
    public List<Integer> weeks(){
        return new ArrayList<>(weeks.keySet());
    }

    /** The week's best free agents at a position, best first, as the pool carries them. */
    public List<Double> wire(int week, Position position){
        List<Double> out = new ArrayList<>();
        for(int k = 1; k <= TitleOdds.WIRE_DEPTH.getOrDefault(position, 0); k++){
            Double points = weeks.getOrDefault(week, Map.of()).get(TitleOdds.WIRE + position.name() + ":" + k);
            if(points != null){
                out.add(points);
            }
        }
        return out;
    }

    /**
     * The objective on one pricing ("sleeper-remaining" or "ros") for the weeks
     * from the one in progress to the end of the fantasy playoffs, with every
     * man rostered in the league today kept off the wire.
     */
    public static WeeklyLineupValue forSource(AAAConfiguration configuration, String source){
        TitleOdds.League league = TitleOdds.league(configuration);
        Set<String> owned = LeagueOwners.today(configuration).keySet();
        Map<String, Double> rates = "ros".equals(source) ? RosModel.rates() : Map.of();
        Set<String> started = NflverseGames.kickedOff(NflverseGames.games(), league.season(), league.thisWeek(),
                java.time.LocalDateTime.now(java.time.ZoneId.of("America/New_York")));
        TreeMap<Integer, Map<String, Double>> weeks = new TreeMap<>();
        for(int w = league.thisWeek(); w <= league.lastWeek(); w++){
            Map<String, Double> week = new HashMap<>();
            for(Map.Entry<String, Double> e : LeagueWeek.projected(league.season(), w).entrySet()){
                if(w == league.thisWeek() && started.contains(ProjectionSources.teamOf(e.getKey()))){
                    continue;           // his game this week is under way or over: a trade cannot reach it
                }
                week.put(e.getKey(), rates.getOrDefault(e.getKey(), e.getValue()));
            }
            weeks.put(w, withFreeAgents(week, owned));
        }
        return new WeeklyLineupValue(weeks, "weekly lineups on " + source + ", weeks " + league.thisWeek() + "-" + league.lastWeek());
    }

    /** A week's projection with its best free agents added under WIRE: keys (TitleOdds' convention). */
    static Map<String, Double> withFreeAgents(Map<String, Double> week, Set<String> owned){
        Map<Position, List<Double>> free = new HashMap<>();
        week.forEach((id, points) -> {
            Player p = owned.contains(id) ? null : Player.getPlayerFromSIDV2(id);
            if(p != null && TitleOdds.WIRE_DEPTH.containsKey(p.position)){
                free.computeIfAbsent(p.position, k -> new ArrayList<>()).add(points);
            }
        });
        Map<String, Double> out = new HashMap<>(week);
        TitleOdds.withWire(out, free);
        return out;
    }
}
