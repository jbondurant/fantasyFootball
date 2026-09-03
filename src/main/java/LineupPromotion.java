import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What the missing channel is actually WORTH, in league points, measured on
 * the real rosters twelve real managers really held.
 *
 * {@link PromotionBehaviour} shows the churn - roughly 28 adds a team a season,
 * two thirds of them men nobody drafted - and that a one-for-one swap gains
 * about seven rest-of-season points for the ROSTER. That is not the number the
 * model needs. A roster gain is only worth something if the added man reaches
 * the NINE, and most waiver adds never do. So this class stops asking about
 * rosters and asks about lineups.
 *
 * Sleeper keeps the answer: /v1/league/{id}/matchups/{week} returns, for every
 * roster in every week, the men on it AND the ten it actually started. So the
 * same real roster can be run through four different lineup rules and the gaps
 * between them are the value of each piece of knowledge:
 *
 *   PRESEASON   fill the nine by August expectation and never update. This is
 *               exactly what WeeklyStarterValue does - survivors ranked by a
 *               preseason number that never moves - so this row IS the model.
 *   FORM        fill the nine by a blend of August expectation and the man's
 *               own points per game through the PREVIOUS week. This is the
 *               bust/boom channel, implemented with the detection lag
 *               DetectionLag measures, and it is strictly causal: week w reads
 *               weeks 1..w-1 and nothing else.
 *   ACTUAL      what the manager really started. A human with a waiver wire, a
 *               beat report and a Sunday morning.
 *   PERFECT     the best nine by realised points. Hindsight, and impossible -
 *               it is here only as the ceiling that bounds all the others.
 *
 * PRESEASON to FORM is the whole prize: the most a mechanical bust-and-boom
 * rule could add to the model as it stands. ACTUAL to PERFECT is what no rule
 * of any kind can reach.
 *
 * The lineups are built by a slot filler that respects QB/RB/RB/WR/WR/WR/TE
 * plus two flexes, so a rule cannot cheat by starting five backs. The defence
 * is excluded on purpose, as everywhere else in this repo.
 *
 * Scored through {@link EraActuals#weeklyPoints}, the league-scored path -
 * Sleeper's own players_points pays 4 for a passing touchdown where this
 * league pays 6.
 *
 *   ./gradlew run -Pmain=LineupPromotion
 */
public class LineupPromotion {

    /** Fantasy regular season. Weeks 15+ are playoffs; half the league sits. */
    public static final int LAST_WEEK = 14;

    static final int BOOTSTRAP = 2000;

    /** Where a started man came from. */
    public enum Origin { EARLY, MIDDLE, LATE, UNDRAFTED }

    static Origin origin(Integer pick){
        if(pick == null){
            return Origin.UNDRAFTED;
        }
        return pick <= 48 ? Origin.EARLY : pick <= 108 ? Origin.MIDDLE : Origin.LATE;
    }

    static String matchupsRaw(String leagueID, int week){
        return InOutUtilities.getCachedForever(
                "https://api.sleeper.app/v1/league/" + leagueID + "/matchups/" + week,
                "sleeperMatchups" + leagueID + "w" + week);
    }

    /** One roster in one week: who was on it, and who was started. */
    public record RosterWeek(int rosterID, int week, List<String> roster,
                             List<String> started){}

    public static List<RosterWeek> week(String leagueID, int week){
        List<RosterWeek> rows = new ArrayList<>();
        JsonArray array = JsonParser.parseString(matchupsRaw(leagueID, week))
                .getAsJsonArray();
        for(JsonElement element : array){
            JsonObject row = element.getAsJsonObject();
            if(!row.has("roster_id") || row.get("roster_id").isJsonNull()){
                continue;
            }
            rows.add(new RosterWeek(row.get("roster_id").getAsInt(), week,
                    ids(row, "players"), ids(row, "starters")));
        }
        return rows;
    }

    static List<String> ids(JsonObject row, String key){
        List<String> out = new ArrayList<>();
        JsonElement element = row.get(key);
        if(element == null || !element.isJsonArray()){
            return out;
        }
        for(JsonElement id : element.getAsJsonArray()){
            if(!id.isJsonNull()){
                String text = id.getAsString();
                if(!text.isBlank() && !"0".equals(text)){
                    out.add(text);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // The slot filler. A rule proposes an ORDER; the slots decide who plays.
    // ------------------------------------------------------------------

    /**
     * Fill QB, RB, RB, WR, WR, WR, TE, FLEX, FLEX by a rule's own ordering,
     * then score the chosen men by what they really did.
     *
     * The two steps are deliberately separated. A rule that ranked men by
     * realised points would BE hindsight, and this repo has been bitten by
     * exactly that twice; keeping the ordering key and the scoring map as
     * different arguments makes it visible at every call site which one a rule
     * is allowed to see. The PERFECT row in main is the single place they are
     * knowingly the same, and it is labelled as the ceiling.
     *
     * `availability` is the second thing a rule may or may not know: whether a
     * man took the field at all. WeeklyStarterValue already gets this for free
     * through its !up() draw, so a fair reading of what the BUST channel adds
     * has to grant it to both sides. Off, an unavailable man can be started and
     * scores nothing, which is the pre-injury-channel model.
     */
    public static double fill(List<String> roster, Map<String, Double> points,
                              java.util.function.ToDoubleFunction<String> better,
                              boolean availability){
        Map<Position, List<String>> byPosition = new EnumMap<>(Position.class);
        for(String id : roster){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player == null || !StartingLineup.isSkillPosition(player.position)){
                continue;
            }
            if(availability && !points.containsKey(id)){
                continue;                     // did not play; !up() would bench him
            }
            byPosition.computeIfAbsent(player.position, p -> new ArrayList<>()).add(id);
        }
        Comparator<String> order = Comparator.comparingDouble(better).reversed();
        byPosition.values().forEach(list -> list.sort(order));

        double total = 0;
        List<String> flexPool = new ArrayList<>();
        Map<Position, Integer> fixed = new EnumMap<>(Position.class);
        fixed.put(Position.QB, 1);
        fixed.put(Position.RB, 2);
        fixed.put(Position.WR, 3);
        fixed.put(Position.TE, 1);
        for(Map.Entry<Position, Integer> slot : fixed.entrySet()){
            List<String> have = byPosition.getOrDefault(slot.getKey(), List.of());
            for(int i = 0; i < have.size(); i++){
                if(i < slot.getValue()){
                    total += points.getOrDefault(have.get(i), 0.0);
                }
                else if(slot.getKey() != Position.QB){
                    flexPool.add(have.get(i));       // a QB can never flex here
                }
            }
        }
        flexPool.sort(order);
        for(int i = 0; i < StartingLineup.FLEX_SLOTS && i < flexPool.size(); i++){
            total += points.getOrDefault(flexPool.get(i), 0.0);
        }
        return total;
    }

    /** Sum the skill men a manager really started. The defence slot is dropped. */
    public static double startedSkill(List<String> started, Map<String, Double> points){
        double total = 0;
        for(String id : started){
            if(EraActuals.isDefence(id)){
                continue;
            }
            Player player = Player.getPlayerFromSIDV2(id);
            if(player == null || !StartingLineup.isSkillPosition(player.position)){
                continue;
            }
            total += points.getOrDefault(id, 0.0);
        }
        return total;
    }

    // ------------------------------------------------------------------

    public static void main(String[] args){
        String leagueID = AAAConfiguration.getInstance().getLeagueID();
        List<LeagueTransactions.Year> years =
                LeagueTransactions.completedSeasons(leagueID);
        double blend = Double.parseDouble(System.getProperty("blend", "0.45"));

        System.out.printf("%nWHAT THE MISSING CHANNEL IS WORTH, ON REAL ROSTERS%n%n");
        System.out.printf("seasons          %d (%s)%n", years.size(),
                String.join(" ", years.stream()
                        .map(LeagueTransactions.Year::season).toList()));
        System.out.printf("weeks            1-%d (fantasy regular season)%n", LAST_WEEK);
        System.out.printf("form blend       %.2f on points-per-game to date,"
                + " %.2f on preseason%n", blend, 1 - blend);
        System.out.printf("                 (DetectionLag's measured w* around"
                + " weeks 8-12)%n%n");

        // Six columns per season: the four rules with an unavailable man
        // startable, then the two that matter - preseason and form, both
        // granted the !up() knowledge WeeklyStarterValue already has.
        Map<String, double[]> perSeason = new TreeMap<>();
        Map<String, long[]> startsByOrigin = new TreeMap<>();
        long[][] startsByWeek = new long[LAST_WEEK + 1][Origin.values().length];
        double[][] pointsByWeek = new double[LAST_WEEK + 1][Origin.values().length];
        double[] sweepWeights = {0.0, 0.15, 0.3, 0.45, 0.6, 0.8, 1.0};
        Map<String, double[]> sweep = new TreeMap<>();

        for(LeagueTransactions.Year year : years){
            String season = year.season();
            EraBoards.Board board = EraBoards.tryBuild(season, null);
            Map<Position, DetectionLag.Curve> curves = seasonCurves(season);
            Map<String, Integer> positionRank = positionRanks(board);
            Map<String, Integer> picks = LeagueTransactions.draftPicks(year);

            Map<Integer, Map<String, Double>> weekly = new HashMap<>();
            for(int week = 1; week <= LAST_WEEK; week++){
                weekly.put(week, EraActuals.weeklyPoints(season, week));
            }

            // running points-per-game to date, rebuilt as the season walks
            Map<String, double[]> toDate = new HashMap<>();   // {sum, games}
            double preseasonTotal = 0, formTotal = 0, actualTotal = 0, perfectTotal = 0;
            double preseasonUp = 0, formUp = 0;
            double[] sweepTotals = new double[sweepWeights.length];
            long[] origins = new long[Origin.values().length];

            for(int week = 1; week <= LAST_WEEK; week++){
                Map<String, Double> points = weekly.get(week);
                for(RosterWeek row : week(year.leagueID(), week)){
                    java.util.function.ToDoubleFunction<String> preseasonKey =
                            id -> preseason(id, positionRank, curves);
                    java.util.function.ToDoubleFunction<String> formKey =
                            formKey(blend, toDate, positionRank, curves);
                    preseasonTotal += fill(row.roster(), points, preseasonKey, false);
                    formTotal += fill(row.roster(), points, formKey, false);
                    preseasonUp += fill(row.roster(), points, preseasonKey, true);
                    formUp += fill(row.roster(), points, formKey, true);
                    actualTotal += startedSkill(row.started(), points);
                    perfectTotal += fill(row.roster(), points,
                            id -> points.getOrDefault(id, 0.0), true);
                    for(int i = 0; i < sweepWeights.length; i++){
                        sweepTotals[i] += fill(row.roster(), points,
                                formKey(sweepWeights[i], toDate, positionRank, curves),
                                true);
                    }

                    for(String id : row.started()){
                        if(EraActuals.isDefence(id)){
                            continue;
                        }
                        Origin from = origin(picks.get(id));
                        origins[from.ordinal()]++;
                        startsByWeek[week][from.ordinal()]++;
                        pointsByWeek[week][from.ordinal()] +=
                                points.getOrDefault(id, 0.0);
                    }
                }
                // only NOW does week w join the to-date record, so week w+1's
                // form rule reads weeks 1..w and never its own week
                points.forEach((id, scored) -> {
                    double[] seen = toDate.computeIfAbsent(id, k -> new double[2]);
                    seen[0] += scored;
                    seen[1] += 1;
                });
            }
            perSeason.put(season, new double[]{preseasonTotal / 12, formTotal / 12,
                    actualTotal / 12, perfectTotal / 12, preseasonUp / 12, formUp / 12});
            for(int i = 0; i < sweepTotals.length; i++){
                sweepTotals[i] /= 12;
            }
            sweep.put(season, sweepTotals);
            startsByOrigin.put(season, origins);
        }

        System.out.printf("POINTS PER TEAM PER SEASON, weeks 1-%d, same real"
                + " rosters%n", LAST_WEEK);
        System.out.printf("%-8s %11s %11s %11s %11s %13s %13s%n", "SEASON",
                "preseason", "form", "actual", "perfect", "form - pre",
                "actual - pre");
        for(Map.Entry<String, double[]> entry : perSeason.entrySet()){
            double[] v = entry.getValue();
            System.out.printf("%-8s %11.1f %11.1f %11.1f %11.1f %+13.1f %+13.1f%n",
                    entry.getKey(), v[0], v[1], v[2], v[3], v[1] - v[0], v[2] - v[0]);
        }
        double[] means = new double[6];
        for(double[] v : perSeason.values()){
            for(int i = 0; i < 6; i++){
                means[i] += v[i] / perSeason.size();
            }
        }
        System.out.printf("%-8s %11.1f %11.1f %11.1f %11.1f %+13.1f %+13.1f%n", "MEAN",
                means[0], means[1], means[2], means[3], means[1] - means[0],
                means[2] - means[0]);

        System.out.printf("%nTHE SAME THING WITH THE INJURY CHANNEL ALREADY ON%n");
        System.out.printf("(a man who did not play is benched by both rules -"
                + " WeeklyStarterValue's !up()%n draw already does this, so this"
                + " is the honest apples-to-apples)%n%n");
        System.out.printf("%-8s %14s %14s %13s%n", "SEASON", "preseason+up",
                "form+up", "form - pre");
        for(Map.Entry<String, double[]> entry : perSeason.entrySet()){
            double[] v = entry.getValue();
            System.out.printf("%-8s %14.1f %14.1f %+13.1f%n", entry.getKey(), v[4],
                    v[5], v[5] - v[4]);
        }
        System.out.printf("%-8s %14.1f %14.1f %+13.1f%n", "MEAN", means[4], means[5],
                means[5] - means[4]);

        double[] formGap = bootstrap(perSeason, 1, 0, 20260831L);
        double[] formGapUp = bootstrap(perSeason, 5, 4, 20260831L);
        double[] actualGap = bootstrap(perSeason, 2, 4, 20260831L);
        double[] ceiling = bootstrap(perSeason, 3, 2, 20260831L);
        System.out.printf("%nWHAT EACH PIECE OF KNOWLEDGE IS WORTH,"
                + " points per team per season%n");
        System.out.printf("   form over preseason, no injury channel  %+7.1f"
                + "  95%% [%+.1f, %+.1f]%n", means[1] - means[0],
                DetectionLag.percentile(formGap, 2.5),
                DetectionLag.percentile(formGap, 97.5));
        System.out.printf("   form over preseason, injury channel ON  %+7.1f"
                + "  95%% [%+.1f, %+.1f]   <- the prize%n", means[5] - means[4],
                DetectionLag.percentile(formGapUp, 2.5),
                DetectionLag.percentile(formGapUp, 97.5));
        System.out.printf("   the injury channel by itself            %+7.1f"
                + "%n", means[4] - means[0]);
        System.out.printf("   a real human over preseason+up          %+7.1f"
                + "  95%% [%+.1f, %+.1f]   <- what a manager adds%n",
                means[2] - means[4], DetectionLag.percentile(actualGap, 2.5),
                DetectionLag.percentile(actualGap, 97.5));
        System.out.printf("   hindsight over a real human             %+7.1f"
                + "  95%% [%+.1f, %+.1f]   <- unreachable by anyone%n",
                means[3] - means[2], DetectionLag.percentile(ceiling, 2.5),
                DetectionLag.percentile(ceiling, 97.5));
        System.out.printf("   (%d resamples of the %d seasons; the 95%% bar for a"
                + " plan-level claim is 125)%n", BOOTSTRAP, perSeason.size());

        System.out.printf("%nSWEEPING THE BLEND WEIGHT (injury channel on)%n");
        System.out.printf("%-14s", "weight on form");
        for(double w : sweepWeights){
            System.out.printf("%9.2f", w);
        }
        System.out.printf("%n%-14s", "gain vs pre");
        for(int i = 0; i < sweepWeights.length; i++){
            double mean = 0;
            for(double[] v : sweep.values()){
                mean += v[i] / sweep.size();
            }
            System.out.printf("%+9.1f", mean - means[4]);
        }
        System.out.println("\n   weight 0.00 reproduces preseason exactly - the"
                + " control that proves\n   the sweep is measuring the blend and"
                + " not a coding difference.");

        System.out.printf("%nWHERE A STARTED MAN CAME FROM (skill slots only)%n");
        System.out.printf("%-8s %12s %12s %12s %12s%n", "SEASON", "rounds 1-4",
                "rounds 5-9", "rounds 10+", "undrafted");
        for(Map.Entry<String, long[]> entry : startsByOrigin.entrySet()){
            long total = 0;
            for(long count : entry.getValue()){
                total += count;
            }
            System.out.printf("%-8s", entry.getKey());
            for(Origin from : Origin.values()){
                System.out.printf("%11.0f%%",
                        total == 0 ? 0 : 100.0 * entry.getValue()[from.ordinal()] / total);
            }
            System.out.println();
        }

        System.out.printf("%nUNDRAFTED SHARE OF SKILL STARTS, BY WEEK%n");
        System.out.printf("%-10s", "week");
        for(int week = 1; week <= LAST_WEEK; week++){
            System.out.printf("%5d", week);
        }
        System.out.printf("%n%-10s", "share");
        for(int week = 1; week <= LAST_WEEK; week++){
            long total = 0;
            for(Origin from : Origin.values()){
                total += startsByWeek[week][from.ordinal()];
            }
            System.out.printf("%4.0f%%", total == 0 ? 0
                    : 100.0 * startsByWeek[week][Origin.UNDRAFTED.ordinal()] / total);
        }
        System.out.printf("%n%-10s", "pts/start");
        for(int week = 1; week <= LAST_WEEK; week++){
            long n = startsByWeek[week][Origin.UNDRAFTED.ordinal()];
            System.out.printf("%5.1f", n == 0 ? 0
                    : pointsByWeek[week][Origin.UNDRAFTED.ordinal()] / n);
        }
        System.out.printf("%n%-10s", "pts/start early");
        for(int week = 1; week <= LAST_WEEK; week++){
            long n = startsByWeek[week][Origin.EARLY.ordinal()];
            System.out.printf("%5.1f", n == 0 ? 0
                    : pointsByWeek[week][Origin.EARLY.ordinal()] / n);
        }
        System.out.println("\n");
    }

    // ------------------------------------------------------------------

    /**
     * The form rule's ordering key: August, bent by what the man has done.
     *
     * `toDate` holds {points, games} summed over the weeks ALREADY PLAYED - the
     * caller folds each week in only after that week's lineups are set, so this
     * closure can never see the week it is choosing for. A man with no games
     * yet keeps his August number, which is the right default and also the
     * reason weight 1.0 is not the same as ignoring the board.
     */
    static java.util.function.ToDoubleFunction<String> formKey(double weight,
            Map<String, double[]> toDate, Map<String, Integer> ranks,
            Map<Position, DetectionLag.Curve> curves){
        return id -> {
            double pre = preseason(id, ranks, curves);
            double[] seen = toDate.get(id);
            if(seen == null || seen[1] < 1){
                return pre;
            }
            return weight * (seen[0] / seen[1]) + (1 - weight) * pre;
        };
    }

    /**
     * The preseason curve for one season, fitted on every OTHER season.
     *
     * Same leave-one-season-out guarantee as {@link DetectionLag}: the season
     * being replayed contributes nothing to the August expectation it is
     * graded against.
     */
    private static Map<String, Map<Position, DetectionLag.Curve>> curveCache;

    static synchronized Map<Position, DetectionLag.Curve> seasonCurves(String season){
        if(curveCache == null){
            // Thirteen seasons of weekly files, parsed once. Rebuilding this
            // per season would reparse roughly two hundred documents five times.
            curveCache = DetectionLag.leaveOneSeasonOut(DetectionLag.load(null), false);
        }
        return curveCache.getOrDefault(season, new EnumMap<>(Position.class));
    }

    /** sleeper id -> his rank within his position on that season's ADP board. */
    static Map<String, Integer> positionRanks(EraBoards.Board board){
        Map<String, Integer> ranks = new HashMap<>();
        if(board == null){
            return ranks;
        }
        Map<Position, Integer> counter = new EnumMap<>(Position.class);
        List<String> ordered = new ArrayList<>(board.ids());
        ordered.sort(Comparator.comparingDouble(id -> board.adp().get(id)));
        for(String id : ordered){
            Position position = board.positionOf().get(id);
            if(position != null && StartingLineup.isSkillPosition(position)){
                ranks.put(id, counter.merge(position, 1, Integer::sum));
            }
        }
        return ranks;
    }

    /**
     * August expectation, in points per game.
     *
     * A man with no rank was not on the national board at all - he is the
     * undrafted case, and the preseason rule has nothing to say about him. He
     * is placed one past the board's usable depth rather than at zero: the
     * model does not think he is worthless, it thinks he is the last man in,
     * which is exactly its blindness and the thing being priced.
     */
    static final int OFF_BOARD_RANK = 65;

    static double preseason(String id, Map<String, Integer> ranks,
                            Map<Position, DetectionLag.Curve> curves){
        Player player = Player.getPlayerFromSIDV2(id);
        if(player == null || !StartingLineup.isSkillPosition(player.position)){
            return 0;
        }
        DetectionLag.Curve curve = curves.get(player.position);
        if(curve == null){
            return 0;
        }
        return curve.predict(ranks.getOrDefault(id, OFF_BOARD_RANK));
    }

    static double[] bootstrap(Map<String, double[]> perSeason, int high, int low,
                              long seed){
        List<String> seasons = new ArrayList<>(perSeason.keySet());
        Random random = new Random(seed);
        double[] draws = new double[BOOTSTRAP];
        for(int draw = 0; draw < BOOTSTRAP; draw++){
            double sum = 0;
            for(int i = 0; i < seasons.size(); i++){
                double[] v = perSeason.get(seasons.get(random.nextInt(seasons.size())));
                sum += v[high] - v[low];
            }
            draws[draw] = sum / seasons.size();
        }
        return draws;
    }
}
