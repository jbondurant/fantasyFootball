import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * What a real manager in THIS league actually did - the observation that
 * replaces a modelling assumption.
 *
 * `WeeklyStarterValue` has one promotion channel, an injury draw, and ranks
 * survivors by a preseason expectation that never updates. Justin named the
 * gap on 2026-08-31: "some starters bust, and some bench players boom." Before
 * pricing a bust-and-boom channel it is worth asking whether the twelve humans
 * in this league behave as if one exists, and how fast - and that is not a
 * modelling question, it is a log-reading question. Five completed seasons of
 * this league's transaction log answer it directly.
 *
 * Four things get measured, none of them assumed:
 *
 *   VOLUME       how much churn there is, per team per week.
 *   DROP TIMING  when a DRAFTED player gets cut by the man who drafted him,
 *                by draft round. This is the detection lag as revealed by
 *                behaviour rather than by regression - the week a manager
 *                stopped waiting.
 *   PROMOTION    what share of adds are men nobody drafted, which is the boom
 *                channel actually firing.
 *   PAYOFF       what the added man scored over the rest of the season against
 *                what the dropped man scored, in this league's points. If that
 *                is near zero the whole channel is worth little no matter how
 *                fast it fires.
 *
 * Graded through {@link EraActuals#weeklyPoints}, the league-scored path:
 * Sleeper's own pts_half_ppr pays 4 for a passing touchdown where this league
 * pays 6, and the feed changed its own fumble rule mid-history, so raw feed
 * totals are not a stable unit across the five seasons.
 *
 *   ./gradlew run -Pmain=PromotionBehaviour
 */
public class PromotionBehaviour {

    public static final int TEAMS = 12;

    /** Rounds 1-9 of a 12-team draft fill the nine skill slots. */
    public static final int STARTER_PICKS = 108;

    static final int BOOTSTRAP = 2000;

    /** A completed add/drop pair, and what each man did afterwards. */
    public record Swap(String season, int week, String added, String dropped,
                       double addedRest, double droppedRest, int bid){
        public double gain(){
            return addedRest - droppedRest;
        }
    }

    // ------------------------------------------------------------------

    public static void main(String[] args){
        String leagueID = AAAConfiguration.getInstance().getLeagueID();
        List<LeagueTransactions.Year> years =
                LeagueTransactions.completedSeasons(leagueID);

        System.out.printf("%nWHAT A REAL MANAGER DID%n%n");
        System.out.printf("league chain     %s%n", leagueID);
        System.out.printf("completed seasons %d (%s)%n", years.size(),
                String.join(" ", years.stream()
                        .map(LeagueTransactions.Year::season).toList()));

        // ---------------- volume ----------------
        System.out.printf("%nVOLUME%n");
        System.out.printf("%-8s %8s %10s %10s %10s %12s%n", "SEASON", "rows",
                "completed", "failed", "trades", "adds/team");
        Map<String, List<LeagueTransactions.Move>> log = new TreeMap<>();
        for(LeagueTransactions.Year year : years){
            List<LeagueTransactions.Move> moves = LeagueTransactions.moves(year);
            log.put(year.season(), moves);
            long completed = moves.stream()
                    .filter(LeagueTransactions.Move::complete).count();
            long failed = moves.size() - completed;
            long trades = moves.stream().filter(m -> "trade".equals(m.type())).count();
            long adds = moves.stream().filter(LeagueTransactions.Move::complete)
                    .mapToLong(m -> m.adds().size()).sum();
            System.out.printf("%-8s %8d %10d %10d %10d %12.1f%n", year.season(),
                    moves.size(), completed, failed, trades, adds / (double) TEAMS);
        }

        // ---------------- weekly churn ----------------
        System.out.printf("%nCOMPLETED ADDS BY WEEK, per team%n");
        System.out.printf("%-8s", "SEASON");
        for(int week = 1; week <= 18; week++){
            System.out.printf("%5d", week);
        }
        System.out.println();
        for(Map.Entry<String, List<LeagueTransactions.Move>> entry : log.entrySet()){
            double[] perWeek = new double[19];
            for(LeagueTransactions.Move move : entry.getValue()){
                if(move.complete() && move.week() >= 1 && move.week() <= 18){
                    perWeek[move.week()] += move.adds().size();
                }
            }
            System.out.printf("%-8s", entry.getKey());
            for(int week = 1; week <= 18; week++){
                System.out.printf("%5.1f", perWeek[week] / TEAMS);
            }
            System.out.println();
        }

        // ---------------- drop timing for drafted men ----------------
        System.out.printf("%nWHEN A DRAFTED MAN GETS CUT BY THE MAN WHO DREW HIM%n");
        System.out.printf("(the detection lag as behaviour: the week a manager"
                + " stopped waiting)%n%n");
        int[][] cutByRound = new int[3][20];       // 0: rounds 1-4, 1: 5-9, 2: 10+
        int[] drafted = new int[3];
        List<Integer> starterCutWeeks = new ArrayList<>();
        for(LeagueTransactions.Year year : years){
            Map<String, Integer> picks = LeagueTransactions.draftPicks(year);
            Map<String, Integer> owner = LeagueTransactions.draftedBy(year);
            Map<String, Integer> firstCut = new HashMap<>();
            for(LeagueTransactions.Move move : log.get(year.season())){
                if(!move.complete()){
                    continue;
                }
                for(Map.Entry<String, Integer> drop : move.drops().entrySet()){
                    if(!picks.containsKey(drop.getKey())){
                        continue;
                    }
                    if(!drop.getValue().equals(owner.get(drop.getKey()))){
                        continue;              // cut by a later owner, not the drafter
                    }
                    firstCut.merge(drop.getKey(), move.week(), Math::min);
                }
            }
            for(Map.Entry<String, Integer> pick : picks.entrySet()){
                int bucket = bucket(pick.getValue());
                drafted[bucket]++;
                Integer week = firstCut.get(pick.getKey());
                if(week != null && week >= 1 && week <= 18){
                    cutByRound[bucket][week]++;
                    if(pick.getValue() <= STARTER_PICKS){
                        starterCutWeeks.add(week);
                    }
                }
            }
        }
        System.out.printf("%-14s %7s %8s %s%n", "DRAFTED IN", "n", "ever cut",
                "cumulative % cut by end of week");
        System.out.printf("%-14s %7s %8s", "", "", "");
        for(int week = 1; week <= 14; week++){
            System.out.printf("%5d", week);
        }
        System.out.println();
        String[] labels = {"rounds 1-4", "rounds 5-9", "rounds 10+"};
        for(int bucket = 0; bucket < 3; bucket++){
            int total = 0;
            for(int week = 1; week <= 18; week++){
                total += cutByRound[bucket][week];
            }
            System.out.printf("%-14s %7d %7.0f%%", labels[bucket], drafted[bucket],
                    drafted[bucket] == 0 ? 0 : 100.0 * total / drafted[bucket]);
            int running = 0;
            for(int week = 1; week <= 14; week++){
                running += cutByRound[bucket][week];
                System.out.printf("%4.0f%%", drafted[bucket] == 0 ? 0
                        : 100.0 * running / drafted[bucket]);
            }
            System.out.println();
        }
        if(!starterCutWeeks.isEmpty()){
            List<Integer> sorted = new ArrayList<>(starterCutWeeks);
            sorted.sort(Integer::compareTo);
            System.out.printf("%nrounds 1-9 cut by their drafter: n=%d,"
                            + " median week %d, quartiles %d and %d%n",
                    sorted.size(), sorted.get(sorted.size() / 2),
                    sorted.get(sorted.size() / 4), sorted.get(3 * sorted.size() / 4));
        }

        // ---------------- who gets added ----------------
        System.out.printf("%nWHO GETS ADDED%n");
        System.out.printf("%-8s %10s %14s %14s %10s%n", "SEASON", "adds",
                "undrafted", "median bid", "max bid");
        for(LeagueTransactions.Year year : years){
            Map<String, Integer> picks = LeagueTransactions.draftPicks(year);
            int adds = 0, undrafted = 0;
            List<Integer> bids = new ArrayList<>();
            for(LeagueTransactions.Move move : log.get(year.season())){
                if(!move.complete()){
                    continue;
                }
                for(String id : move.adds().keySet()){
                    adds++;
                    if(!picks.containsKey(id)){
                        undrafted++;
                    }
                }
                if("waiver".equals(move.type()) && move.bid() > 0){
                    bids.add(move.bid());
                }
            }
            bids.sort(Integer::compareTo);
            System.out.printf("%-8s %10d %13.0f%% %14s %10s%n", year.season(), adds,
                    adds == 0 ? 0 : 100.0 * undrafted / adds,
                    bids.isEmpty() ? "-" : String.valueOf(bids.get(bids.size() / 2)),
                    bids.isEmpty() ? "-" : String.valueOf(bids.get(bids.size() - 1)));
        }

        // ---------------- did it pay ----------------
        List<Swap> swaps = swaps(years, log);
        System.out.printf("%nDID THE SWAP PAY?%n");
        System.out.printf("(one completed transaction that added exactly one man"
                + " and dropped exactly one;%n rest-of-season league points for"
                + " each, weeks after the move)%n%n");
        System.out.printf("%-8s %8s %12s %12s %12s%n", "SEASON", "n", "added",
                "dropped", "gain");
        Map<String, List<Swap>> bySeason = new TreeMap<>();
        for(Swap swap : swaps){
            bySeason.computeIfAbsent(swap.season(), s -> new ArrayList<>()).add(swap);
        }
        for(Map.Entry<String, List<Swap>> entry : bySeason.entrySet()){
            System.out.printf("%-8s %8d %12.1f %12.1f %+12.1f%n", entry.getKey(),
                    entry.getValue().size(), mean(entry.getValue(), Swap::addedRest),
                    mean(entry.getValue(), Swap::droppedRest),
                    mean(entry.getValue(), Swap::gain));
        }
        if(!swaps.isEmpty()){
            double[] draws = bootstrapSeasons(bySeason, 20260831L);
            System.out.printf("%-8s %8d %12.1f %12.1f %+12.1f%n", "ALL",
                    swaps.size(), mean(swaps, Swap::addedRest),
                    mean(swaps, Swap::droppedRest), mean(swaps, Swap::gain));
            System.out.printf("%n   mean gain per swap %+.1f points,"
                            + " 95%% interval [%+.1f, %+.1f]%n",
                    mean(swaps, Swap::gain), DetectionLag.percentile(draws, 2.5),
                    DetectionLag.percentile(draws, 97.5));
            System.out.printf("   (%d resamples of the %d seasons - the season is"
                    + " the unit of independent randomness)%n", BOOTSTRAP,
                    bySeason.size());
        }

        // ---------------- the gain by week of the move ----------------
        System.out.printf("%nGAIN BY WEEK OF THE MOVE%n");
        System.out.printf("%-8s %8s %12s %14s%n", "WEEK", "n", "mean gain",
                "gain per week left");
        for(int week = 1; week <= 14; week++){
            final int w = week;
            List<Swap> some = swaps.stream().filter(s -> s.week() == w).toList();
            if(some.size() < 5){
                continue;
            }
            System.out.printf("%-8d %8d %+12.1f %14.2f%n", week, some.size(),
                    mean(some, Swap::gain), mean(some, Swap::gain) / (18 - week));
        }
        System.out.println();
    }

    // ------------------------------------------------------------------

    static int bucket(int pick){
        return pick <= 48 ? 0 : pick <= STARTER_PICKS ? 1 : 2;
    }

    /**
     * One-for-one completed swaps, with each man's rest-of-season points.
     *
     * Restricted to one-in-one-out on purpose: a multi-player move has no
     * unambiguous pairing, and guessing one would put a modelling choice in the
     * middle of the one measurement here that is supposed to be an observation.
     * A swap counts weeks strictly AFTER the move, so nothing the manager could
     * not yet have seen is credited to his decision.
     */
    static List<Swap> swaps(List<LeagueTransactions.Year> years,
                            Map<String, List<LeagueTransactions.Move>> log){
        List<Swap> swaps = new ArrayList<>();
        for(LeagueTransactions.Year year : years){
            String season = year.season();
            int weeks = LeagueTransactions.weeks(season);
            Map<Integer, Map<String, Double>> weekly = new HashMap<>();
            for(int week = 1; week <= weeks; week++){
                weekly.put(week, EraActuals.weeklyPoints(season, week));
            }
            for(LeagueTransactions.Move move : log.get(season)){
                if(!move.complete() || move.adds().size() != 1
                        || move.drops().size() != 1 || move.week() < 1
                        || move.week() >= weeks){
                    continue;
                }
                String added = move.adds().keySet().iterator().next();
                String dropped = move.drops().keySet().iterator().next();
                double addedRest = 0, droppedRest = 0;
                for(int week = move.week() + 1; week <= weeks; week++){
                    addedRest += weekly.get(week).getOrDefault(added, 0.0);
                    droppedRest += weekly.get(week).getOrDefault(dropped, 0.0);
                }
                swaps.add(new Swap(season, move.week(), added, dropped, addedRest,
                        droppedRest, move.bid()));
            }
        }
        return swaps;
    }

    static double mean(List<Swap> swaps, java.util.function.ToDoubleFunction<Swap> of){
        return swaps.isEmpty() ? 0
                : swaps.stream().mapToDouble(of).average().orElse(0);
    }

    /** Resample SEASONS, not swaps - two swaps in one season are not independent. */
    static double[] bootstrapSeasons(Map<String, List<Swap>> bySeason, long seed){
        List<String> seasons = new ArrayList<>(bySeason.keySet());
        Random random = new Random(seed);
        double[] draws = new double[BOOTSTRAP];
        for(int draw = 0; draw < BOOTSTRAP; draw++){
            List<Swap> pooled = new ArrayList<>();
            for(int i = 0; i < seasons.size(); i++){
                pooled.addAll(bySeason.get(seasons.get(random.nextInt(seasons.size()))));
            }
            draws[draw] = mean(pooled, Swap::gain);
        }
        return draws;
    }
}
