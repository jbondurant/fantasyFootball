import PlayerImportAndSetup.Position;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 1: what a player's season actually looks like, week by week.
 *
 * The objective needs two things per player and they are not the same thing:
 * how often he is AVAILABLE, and how much he scores WHEN he plays. Every model
 * in this repo has conflated them, because a season total is availability and
 * scoring multiplied together and there is no way to pull them apart again.
 *
 * It also settles an assumption that has been carried untested. The sampler in
 * StarterContribution drew games played and scoring INDEPENDENTLY, which is
 * probably false - a man who misses eight games is usually hobbled in the other
 * nine as well - and if it is false in that direction the sampler understates
 * the downside tail, which is precisely where bench value comes from.
 *
 *   ./gradlew run -Pmain=OutcomeDistributions
 */
public class OutcomeDistributions {

    /** One player's season, split into the two halves the objective needs. */
    public record Season(String name, Position position, int rank, int games,
                  double meanWhenPlaying, double sdWhenPlaying, double total){}

    static final int TIER = 12;

    /** Per season, how many source rows joined and which top-36 men did not (filled by load). */
    public static final Map<String, String> joinReport = new java.util.TreeMap<>();

    /** Every joined season, for anything that needs the outcome pool. */
    /**
     * Which preseason order keys a man's cell. Measured by {@link RankKeyChoice}
     * on five seasons, leave-one-season-out: the projection-keyed band predicts a
     * man's realised season 6.2 +/- 0.8 points better than the ADP-keyed one, in
     * every season separately, and by 7-11 points at QB, RB and WR. Tight ends
     * and defences are a tie. "adp" keeps the old FantasyPros-ranked pool for
     * comparison (-PpoolKey=adp).
     *
     * Keying on projections also removes the name join: the Sleeper feed and the
     * weekly actuals are both by player id, so no man is dropped for spelling and
     * no rank is compressed by a miss (TRAPS #80 does not arise on this path).
     */
    static String poolKey(){
        return System.getProperty("poolKey", "projection");
    }

    private static final Map<String, Map<String, List<Season>>> CACHE = new HashMap<>();

    public static Map<String, List<Season>> all() throws Exception {
        String key = poolKey();
        Map<String, List<Season>> cached = CACHE.get(key);
        if(cached != null){
            return cached;
        }
        Map<String, List<Season>> bySeason = new java.util.LinkedHashMap<>();
        if(key.equals("projection")){
            AAAConfiguration configuration = AAAConfiguration.getInstance();
            int current = Integer.parseInt(configuration.getSeason());
            for(int year = current - 5; year < current; year++){
                List<Season> seasons = byProjection(configuration, String.valueOf(year));
                if(seasons.size() > 80){
                    bySeason.put(String.valueOf(year), seasons);
                }
            }
        }
        else {
            for(File file : new File("data").listFiles()){
                String name = file.getName();
                if(!name.matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                    continue;
                }
                List<Season> seasons = load(file, name.split("-")[3]);
                if(seasons.size() > 80){
                    bySeason.put(name.split("-")[3], seasons);
                }
            }
        }
        CACHE.put(key, bySeason);
        return bySeason;
    }

    /**
     * A season's outcomes ranked by what the projections said, joined by player
     * id rather than by name.
     *
     * The rank is over every man the feed projects at his position, advanced
     * before the played check, so a man who never took a snap still holds his
     * place and nobody below him is promoted (the same rule as {@link #load}).
     */
    public static List<Season> byProjection(AAAConfiguration configuration, String season)
            throws Exception {
        Map<String, Double> projected = HistoricalProjections.leaguePointsBySleeperID(configuration, season);
        Map<String, Double> totals = new HashMap<>(LeagueActuals.seasonPoints(season));
        totals.putAll(LeagueActuals.seasonDefencePoints(season));
        List<Map<String, Double>> weeklyPoints = new ArrayList<>();
        List<Set<String>> weeklyPlayed = new ArrayList<>();
        for(int week = 1; week <= WeeklyActuals.WEEKS; week++){
            weeklyPoints.add(LeagueActuals.weeklyPoints(season, week));
            weeklyPlayed.add(WeeklyActuals.playedBySleeperID(season, week));
        }
        record Row(String id, String name, Position position, double projection){}
        List<Row> rows = new ArrayList<>();
        for(Map.Entry<String, Double> entry : projected.entrySet()){
            if(entry.getValue() == null || entry.getValue() <= 0){
                continue;
            }
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null || player.position == null || player.position == Position.OTHER){
                continue;
            }
            rows.add(new Row(entry.getKey(), player.firstName + " " + player.lastName,
                    player.position, entry.getValue()));
        }
        rows.sort(Comparator.comparingDouble((Row row) -> -row.projection()));
        Map<Position, Integer> nextRank = new EnumMap<>(Position.class);
        List<Season> out = new ArrayList<>();
        for(Row row : rows){
            int rank = nextRank.merge(row.position(), 1, Integer::sum) - 1;
            List<Double> scored = new ArrayList<>();
            int games = 0;
            for(int week = 0; week < WeeklyActuals.WEEKS; week++){
                if(weeklyPlayed.get(week).contains(row.id())){
                    games++;
                    scored.add(weeklyPoints.get(week).getOrDefault(row.id(), 0.0));
                }
            }
            if(games == 0){
                continue;
            }
            double m = scored.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double variance = scored.stream().mapToDouble(v -> (v - m) * (v - m)).sum()
                    / Math.max(1, scored.size() - 1);
            out.add(new Season(row.name(), row.position(), rank, games, m,
                    Math.sqrt(variance), totals.getOrDefault(row.id(), 0.0)));
        }
        joinReport.put(season, String.format(
                "%s: %d of %d projected men played a game; ranked by projection, joined by id, no name join",
                season, out.size(), rows.size()));
        return out;
    }

    public static void main(String[] args) throws Exception {
        // through all(), so -PpoolKey reaches this report; building the list here
        // instead left the two arms identical and looked like "the key does not
        // matter" when the lever simply was not connected
        Map<String, List<Season>> bySeason = all();
        System.out.printf("pool keyed by %s%n", poolKey());
        if(bySeason.isEmpty()){
            System.out.println("no seasons joined");
            return;
        }
        System.out.printf("%d seasons joined: %s%n", bySeason.size(), bySeason.keySet());
        System.out.println("\nTHE JOIN (ranks are the source's, 1-based; a man with no scoring id keeps his place)");
        for(String report : joinReport.values()){
            System.out.println("   " + report);
        }

        System.out.println("\n\nAVAILABILITY AND SCORING, SEPARATED");
        System.out.printf("%-4s %-8s %6s %9s %9s %11s %11s%n", "POS", "TIER", "n",
                "games", "sd games", "pts/game", "sd pts/game");
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE,
                Position.QB}){
            for(int tier = 0; tier < 3; tier++){
                final int t = tier;
                List<Season> group = bySeason.values().stream().flatMap(List::stream)
                        .filter(s -> s.position() == position && s.rank() / TIER == t)
                        .toList();
                if(group.size() < 8){
                    continue;
                }
                System.out.printf("%-4s %-8s %6d %9.1f %9.1f %11.1f %11.1f%n", position,
                        (tier * TIER + 1) + "-" + (tier + 1) * TIER, group.size(),
                        mean(group, Season::games), sd(group, Season::games),
                        mean(group, Season::meanWhenPlaying),
                        mean(group, Season::sdWhenPlaying));
            }
        }

        System.out.println("\n\nTHE ASSUMPTION: are availability and scoring independent?");
        System.out.println("correlation between games played and points per game,"
                + " within a position");
        System.out.printf("%n%-6s %8s %12s   %s%n", "POS", "n", "correlation", "verdict");
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE,
                Position.QB}){
            List<Season> group = bySeason.values().stream().flatMap(List::stream)
                    .filter(s -> s.position() == position && s.games() > 0).toList();
            if(group.size() < 20){
                continue;
            }
            double r = correlation(group);
            System.out.printf("%-6s %8d %12.3f   %s%n", position, group.size(), r,
                    Math.abs(r) < 0.10 ? "independent enough"
                            : r > 0 ? "NOT INDEPENDENT - the available also score more"
                            : "NOT INDEPENDENT - the available score less");
        }
        System.out.println("\nA positive correlation means missing games and scoring"
                + " badly travel together,\nso drawing them independently - as"
                + " StarterContribution does - understates how\noften a roster is short"
                + " AND weak in the same week, which is exactly when a\nbench man is"
                + " worth something.");
    }

    static double correlation(List<Season> group){
        double mx = mean(group, Season::games);
        double my = mean(group, Season::meanWhenPlaying);
        double sxy = 0;
        double sx = 0;
        double sy = 0;
        for(Season s : group){
            double dx = s.games() - mx;
            double dy = s.meanWhenPlaying() - my;
            sxy += dx * dy;
            sx += dx * dx;
            sy += dy * dy;
        }
        return sxy / Math.sqrt(sx * sy);
    }

    static double mean(List<Season> group, java.util.function.ToDoubleFunction<Season> of){
        return group.stream().mapToDouble(of).average().orElse(0);
    }

    static double sd(List<Season> group, java.util.function.ToDoubleFunction<Season> of){
        double m = mean(group, of);
        return Math.sqrt(group.stream().mapToDouble(s -> {
            double d = of.applyAsDouble(s) - m;
            return d * d;
        }).sum() / Math.max(1, group.size() - 1));
    }

    /** ADP joined to the weekly series, by normalised name. */
    public static List<Season> load(File adpFile, String season) throws Exception {
        // See LeagueActuals: off by default, and identical to the old feed
        // reads when off. The pool has to move with the backtest, because
        // PlanBacktest prices a streamed defence out of these same seasons -
        // grading rosters in league points while pricing the wire in feed
        // points would be the units bug that once printed 0.0 for defences.
        Map<String, Double> totals = new HashMap<>(LeagueActuals.seasonPoints(season));
        totals.putAll(LeagueActuals.seasonDefencePoints(season));
        Map<String, String> idByName = new HashMap<>();
        for(String id : totals.keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null){
                idByName.putIfAbsent(
                        TightEndTiming.normalise(player.firstName + " " + player.lastName), id);
            }
        }
        // the whole season's weeks, pulled once and shared
        List<Map<String, Double>> weeklyPoints = new ArrayList<>();
        List<Set<String>> weeklyPlayed = new ArrayList<>();
        for(int week = 1; week <= WeeklyActuals.WEEKS; week++){
            weeklyPoints.add(LeagueActuals.weeklyPoints(season, week));
            weeklyPlayed.add(WeeklyActuals.playedBySleeperID(season, week));
        }

        List<String[]> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(adpFile.toPath());
        String[] header = lines.get(0).split(",");
        int nameCol = -1;
        int posCol = -1;
        int adpCol = -1;
        for(int c = 0; c < header.length; c++){
            if(header[c].equals("name")){ nameCol = c; }
            if(header[c].equals("position")){ posCol = c; }
            if(header[c].equals("AVG")){ adpCol = c; }
        }
        if(nameCol < 0 || posCol < 0 || adpCol < 0){
            return List.of();
        }
        for(String line : lines.subList(1, lines.size())){
            String[] cells = line.split(",");
            if(cells.length > Math.max(adpCol, Math.max(nameCol, posCol))
                    && cells[adpCol].matches("\\d+(\\.\\d+)?")){
                rows.add(cells);
            }
        }
        final int adpColumn = adpCol;
        rows.sort(Comparator.comparingDouble(r -> Double.parseDouble(r[adpColumn])));

        Map<Position, Integer> nextRank = new EnumMap<>(Position.class);
        List<Season> out = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        int playable = 0, joined = 0;
        for(String[] cells : rows){
            String label = cells[posCol].trim();
            Position position;
            if(label.equals("DST") || label.equals("DEF")){
                position = Position.DEF;   // FantasyPros says DST, Sleeper says DEF
            }
            else {
                try {
                    position = Position.valueOf(label);
                }
                catch(IllegalArgumentException notPlayable){
                    continue;              // kickers; this league starts none
                }
            }
            // The rank is the row's place in the SOURCE's positional order,
            // advanced before the name join: an unmatched man keeps his rank
            // instead of pulling everyone below him up a place, so a cell holds
            // the seasons of the tier it is applied to. PlanBacktest.board,
            // WireRateStress and the predictability tools rank the same way
            // (TRAPS #80).
            int rank = nextRank.merge(position, 1, Integer::sum) - 1;
            playable++;
            String id = idByName.get(TightEndTiming.normalise(cells[nameCol]));
            if(id == null){
                if(rank < 3 * TIER){
                    unmatched.add(cells[nameCol].trim() + " (" + position + (rank + 1) + ")");
                }
                continue;
            }
            joined++;

            List<Double> scored = new ArrayList<>();
            int games = 0;
            for(int week = 0; week < WeeklyActuals.WEEKS; week++){
                if(weeklyPlayed.get(week).contains(id)){
                    games++;
                    scored.add(weeklyPoints.get(week).getOrDefault(id, 0.0));
                }
            }
            if(games == 0){
                continue;
            }
            double m = scored.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double variance = scored.stream().mapToDouble(v -> (v - m) * (v - m)).sum()
                    / Math.max(1, scored.size() - 1);
            out.add(new Season(cells[nameCol].trim(), position, rank, games, m,
                    Math.sqrt(variance), totals.getOrDefault(id, 0.0)));
        }
        // "not in the pool" is two things: a name the id index does not carry, and
        // a man who joined but recorded no game. The index is built from the
        // season's scoring rows, so a player who never scored is a name miss here
        // too - Aaron Rodgers 2023, four snaps and out, reads as unmatched.
        joinReport.put(season, String.format(
                "%s: %d of %d playable rows in the pool (%d joined a scoring id, %d of those recorded no game);"
                        + " rows with no scoring id inside the top 36 of a position: %s",
                season, out.size(), playable, joined, joined - out.size(),
                unmatched.isEmpty() ? "none" : String.join(", ", unmatched)));
        return out;
    }
}
