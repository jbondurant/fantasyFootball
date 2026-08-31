import PlayerImportAndSetup.Position;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 4: does any of it beat the folk rules on seasons it never saw?
 *
 * Each strategy is a sequence of positions for my fourteen picks. Each drafts
 * from a past season's real ADP board with the other eleven teams taking best
 * available, and the roster it ends with is scored on what those players
 * ACTUALLY did, week by week:
 *
 *     V = SUM over 18 weeks of the best legal lineup from whoever played
 *
 * Nothing marks its own homework. No distributions, no fog, no scenario draws -
 * only real outcomes. That is the whole point, because everything built on
 * 2026-08-29 looked good against its own assumptions.
 *
 * The lineup is the league's real one: QB, RB, RB, WR, WR, WR, TE, FLEX, FLEX,
 * DEF. Ten starters, not nine. Every strategy spends its LAST pick on a
 * defence, because the league always does and not one of these models reasons
 * about defences at all - which is itself a finding about the models.
 *
 *   ./gradlew run -Pmain=PlanBacktest
 */
public class PlanBacktest {

    /**
     * What streaming a defence off waivers is worth per week.
     *
     * Computed, not typed. An earlier version hardcoded 8.7 read off another
     * tool's output, which is the same prose-drift fault this repo has been
     * fixing all day: the moment the wire calculation changes, a constant
     * copied out of it becomes a lie. This asks WeeklyStarterValue for the
     * number, so the two can never disagree.
     */
    // Keyed on the scoring in force, not held as one number. The rate is
    // computed FROM the outcome pool, so it is denominated in whatever units
    // that pool is scored in - 8.7 a week under pts_half_ppr, 9.0 under the
    // league's own rules. A single cached value would let a tool that scores
    // both ways price the wire in one unit and the rosters in the other, which
    // is the units bug that once printed 0.0 for defences.
    private static final Map<Boolean, Double> streamedDefence = new HashMap<>();

    static synchronized double streamedDefencePerWeek(){
        return streamedDefence.computeIfAbsent(LeagueActuals.enabled(), scoring -> {
            try {
                return WeeklyStarterValue.wireRates(
                        AAAConfiguration.getInstance(),
                        WeeklyStarterValue.pool()).getOrDefault(Position.DEF, 0.0);
            }
            catch(Exception unavailable){
                throw new RuntimeException("cannot price a streamed defence", unavailable);
            }
        });
    }

    /** Slot 7, keepers at r12 and r13. */
    static final int[] MY_PICKS = {7, 18, 31, 42, 55, 66, 79, 90, 103, 114, 127,
            162, 175, 186};

    static final Map<String, String> STRATEGIES = new LinkedHashMap<>();
    static {
        STRATEGIES.put("starter-sum (1-16)",
                "RB WR WR WR WR WR RB WR QB TE TE WR RB DEF");
        STRATEGIES.put("best-nine (Model A)",
                "RB WR RB WR WR WR TE QB QB QB QB QB QB DEF");
        STRATEGIES.put("RUNBOOK committed",
                "RB RB RB WR WR WR WR TE WR QB TE QB RB DEF");
        STRATEGIES.put("RB-heavy folk rule",
                "RB RB RB WR WR WR TE QB WR RB WR TE QB DEF");
        // The back-half decomposition said the starter-sum model's deficit is
        // 91 points in rounds 1-7 and 7 points in rounds 8-16 - so its late
        // picks are already as good as the committed plan's, and only its early
        // rounds lose. These test whether the halves can simply be bolted
        // together.
        STRATEGIES.put("RUNBOOK front + SS back",
                "RB RB RB WR WR WR WR   WR QB TE TE WR RB DEF");
        STRATEGIES.put("ModelA front + SS back",
                "RB WR RB WR WR WR TE   WR QB TE TE WR RB DEF");
        // NOT A LEGAL PLAN, kept as a measurement. You must field a defence in
        // week 1, so drafting none is not a strategy - and the rate it is
        // credited with is the top quartile of undrafted defences, which assumes
        // you land a good one after eleven other managers have taken theirs.
        // What this row measures is what a DRAFTED defence is worth over a
        // wire-level one. It is not an alternative to drafting.
        STRATEGIES.put("[not legal] no DEF drafted",
                "RB RB RB WR WR WR WR TE WR QB TE QB RB RB");
        STRATEGIES.put("best available by ADP", null);
    }

    public record Board(String season, List<String> ids, Map<String, Position> positionOf,
                 List<Map<String, Double>> weekly){}

    public static void main(String[] args) throws Exception {
        List<Board> boards = new ArrayList<>();
        for(File file : new File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                Board board = board(file, file.getName().split("-")[3]);
                if(board != null && board.ids().size() > 150){
                    boards.add(board);
                }
            }
        }
        boards.sort(Comparator.comparing(Board::season));
        if(boards.isEmpty()){
            System.out.println("no seasons to backtest");
            return;
        }

        System.out.printf("%nPHASE 4: every strategy on every season's REAL outcomes%n");
        System.out.printf("lineup QB/RB2/WR3/TE/FLEX2/DEF, 18 weeks, best legal"
                + " lineup each week%n");
        System.out.printf("a defence slot the roster cannot fill is STREAMED at %.1f"
                + " points a week%n%n", streamedDefencePerWeek());
        System.out.printf("%-24s", "STRATEGY");
        for(Board board : boards){
            System.out.printf(" %8s", board.season());
        }
        System.out.printf(" %9s %9s %7s%n", "mean", "vs ADP", "wins");

        Map<String, double[]> results = new LinkedHashMap<>();
        for(Map.Entry<String, String> entry : STRATEGIES.entrySet()){
            double[] scores = new double[boards.size()];
            for(int i = 0; i < boards.size(); i++){
                scores[i] = score(boards.get(i), entry.getValue());
            }
            results.put(entry.getKey(), scores);
        }
        double[] baseline = results.get("best available by ADP");
        double baselineMean = java.util.Arrays.stream(baseline).average().orElse(0);
        for(Map.Entry<String, double[]> entry : results.entrySet()){
            System.out.printf("%-24s", entry.getKey());
            int wins = 0;
            for(int i = 0; i < entry.getValue().length; i++){
                System.out.printf(" %8.0f", entry.getValue()[i]);
                if(entry.getValue()[i] > baseline[i]){
                    wins++;
                }
            }
            double mean = java.util.Arrays.stream(entry.getValue()).average().orElse(0);
            System.out.printf(" %9.0f %+9.0f %5d/%d%n", mean, mean - baselineMean,
                    wins, entry.getValue().length);
        }

        System.out.println("\nEvery strategy drafts from the same board at the same"
                + " picks, so the only\ndifference between two rows is the order of"
                + " positions. 'best available by ADP'\nis the null hypothesis: never"
                + " think about position at all.");
        System.out.println("\nCAVEAT, and it is not small: the starter-sum sequence came"
                + " from a model whose\noutcome pool was built from these same seasons."
                + " Its distributions saw them, even\nthough this scoring does not. Read"
                + " its row as flattered, and a narrow win as none.");
    }

    public static Board board(File adpFile, String season) throws Exception {
        // Graded through LeagueActuals, not the raw feed. With the flag off
        // these are exactly HistoricalActuals/WeeklyActuals; with
        // -PleagueScoredActuals=true the outcome is scored under the league's
        // own rules, which pay 6 for a passing touchdown rather than 4.
        Map<String, Double> totals = new HashMap<>(LeagueActuals.seasonPoints(season));
        totals.putAll(LeagueActuals.seasonDefencePoints(season));
        Map<String, String> idByName = new HashMap<>();
        for(String id : totals.keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null){
                idByName.putIfAbsent(TightEndTiming.normalise(
                        player.firstName + " " + player.lastName), id);
            }
        }
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
            return null;
        }
        record Row(String id, Position position, double adp){}
        List<Row> rows = new ArrayList<>();
        for(String line : lines.subList(1, lines.size())){
            String[] cells = line.split(",");
            if(cells.length <= Math.max(adpCol, Math.max(nameCol, posCol))
                    || !cells[adpCol].matches("\\d+(\\.\\d+)?")){
                continue;
            }
            String label = cells[posCol].trim();
            Position position;
            if(label.equals("DST") || label.equals("DEF")){
                position = Position.DEF;      // FantasyPros says DST, Sleeper says DEF
            }
            else {
                try {
                    position = Position.valueOf(label);
                }
                catch(IllegalArgumentException notPlayable){
                    continue;                 // kickers; this league starts none
                }
            }
            String id = idByName.get(TightEndTiming.normalise(cells[nameCol]));
            if(id != null){
                rows.add(new Row(id, position, Double.parseDouble(cells[adpCol])));
            }
        }
        rows.sort(Comparator.comparingDouble(Row::adp));
        List<String> ids = new ArrayList<>();
        Map<String, Position> positionOf = new HashMap<>();
        for(Row row : rows){
            if(!positionOf.containsKey(row.id())){
                ids.add(row.id());
                positionOf.put(row.id(), row.position());
            }
        }
        List<Map<String, Double>> weekly = new ArrayList<>();
        for(int week = 1; week <= WeeklyActuals.WEEKS; week++){
            weekly.add(LeagueActuals.weeklyPoints(season, week));
        }
        return new Board(season, ids, positionOf, weekly);
    }

    public static double score(Board board, String sequence){
        return seasonPoints(board, draft(board, sequence));
    }

    /**
     * The fourteen men this sequence actually ends up with.
     *
     * Split out of score() so a caller can ask WHO a shape drafted, not only
     * what it scored. ShapeSensitivity needs this: two sequences that differ on
     * paper can draft the identical roster - the position fallback below fires
     * when a position is exhausted - and a "perturbation" that changes nobody is
     * not evidence of a plateau, it is a no-op wearing a plateau's clothes.
     */
    /**
     * What Justin already holds, reproduced on a historical board.
     *
     * The pick schedule above ALREADY gives up rounds 12 and 13 to keepers -
     * that is the 35-pick gap - but the roster never received the two men those
     * rounds bought. Every strategy paid the same price for nothing, so the
     * comparisons stayed fair while the absolute scores ran low and, worse, the
     * LEGALITY question came out wrong: a plan drafting no quarterback fields an
     * empty QB slot here, though for a man keeping Purdy it is perfectly legal.
     *
     * The structure is copied by POSITIONAL ADP RANK, not by price, the way
     * EraKeepers does it: Purdy is QB9 and Tuten RB23 on the 2026 board, so a
     * historical season holds its own QB9 and RB23. Copying the round instead
     * would hand an old season a replacement-level quarterback and call it a
     * keeper.
     */
    public static List<String> keeperIDs(Board board){
        int[] ranks = EraKeepers.ranks();
        Map<Position, Integer> wantRank = new EnumMap<>(Position.class);
        wantRank.put(Position.QB, ranks[0]);
        wantRank.put(Position.RB, ranks[1]);
        Map<Position, Integer> seen = new EnumMap<>(Position.class);
        List<String> held = new ArrayList<>();
        for(String id : board.ids()){
            Position position = board.positionOf().get(id);
            Integer want = wantRank.get(position);
            if(want == null){
                continue;
            }
            int rank = seen.merge(position, 1, Integer::sum);
            if(rank == want){
                held.add(id);
            }
        }
        return held;
    }

    /** -PholdKeepers=true. Off leaves every existing number untouched. */
    public static boolean holdKeepers(){
        return Boolean.getBoolean("holdKeepers");
    }

    /**
     * What a fourteen-pick plan must still supply. With the keepers held, the
     * quarterback and one back are already covered, so a nought-quarterback
     * plan becomes legal - which is the whole point of holding them.
     */
    public static Map<Position, Integer> requiredPicks(){
        Map<Position, Integer> need = new EnumMap<>(Position.class);
        need.put(Position.QB, 1);
        need.put(Position.RB, 2);
        need.put(Position.WR, 3);
        need.put(Position.TE, 1);
        need.put(Position.DEF, 1);
        if(holdKeepers()){
            need.merge(Position.QB, -1, Integer::sum);
            need.merge(Position.RB, -1, Integer::sum);
        }
        return need;
    }

    public static List<String> draft(Board board, String sequence){
        List<Position> wanted = new ArrayList<>();
        if(sequence != null){
            for(String token : sequence.split("\\s+")){
                wanted.add(Position.valueOf(token));
            }
        }
        Set<String> gone = new HashSet<>();
        List<String> mine = new ArrayList<>();
        if(holdKeepers()){
            // Off the board - nobody drafts a man I already own - and onto the
            // roster, so the empty slot he fills is actually filled.
            for(String id : keeperIDs(board)){
                gone.add(id);
                mine.add(id);
            }
        }
        Set<Integer> myPicks = new HashSet<>();
        for(int pick : MY_PICKS){
            myPicks.add(pick);
        }
        int taken = 0;
        for(int pick = 1; pick <= 200 && taken < MY_PICKS.length; pick++){
            if(myPicks.contains(pick)){
                String choice = wanted.isEmpty() ? bestAvailable(board, gone, null)
                        : bestAvailable(board, gone, wanted.get(taken));
                if(choice == null){
                    choice = bestAvailable(board, gone, null);
                }
                if(choice != null){
                    mine.add(choice);
                    gone.add(choice);
                }
                taken++;
            }
            else {
                // the other eleven never draft a defence early either
                String other = bestAvailableSkill(board, gone);
                if(other != null){
                    gone.add(other);
                }
            }
        }
        return mine;
    }

    public static String bestAvailable(Board board, Set<String> gone, Position position){
        for(String id : board.ids()){
            if(!gone.contains(id)
                    && (position == null || board.positionOf().get(id) == position)){
                return id;
            }
        }
        return null;
    }

    public static String bestAvailableSkill(Board board, Set<String> gone){
        for(String id : board.ids()){
            if(!gone.contains(id) && board.positionOf().get(id) != Position.DEF){
                return id;
            }
        }
        return null;
    }

    /**
     * Eighteen weeks of the best lineup these players could actually field.
     *
     * Starters are chosen by PRESEASON RANK, not by what they scored that week.
     * The first version sorted on the week's realised points, which is perfect
     * hindsight start/sit: it hands every manager a lineup nobody can set, and
     * it rewards redundancy most at the positions with the widest weekly spread.
     * That flattered any strategy that stacked a position, which is exactly what
     * the policy under test was doing.
     *
     * Preseason rank understates a real manager, who learns during the season.
     * It is applied identically to every strategy, so the comparison stays fair,
     * and it never uses information from the future.
     */
    public static double seasonPoints(Board board, List<String> roster){
        // A streamed defence OCCUPIES A ROSTER SPOT. The roster is sixteen -
        // ten starters and six bench - and fourteen picks plus two keepers
        // fills it, so taking a defence off waivers means dropping somebody.
        // Crediting a streamed defence on top of a full roster handed that
        // strategy a player nobody has, which is why streaming looked free.
        // The man dropped is the last one drafted.
        List<String> held = new ArrayList<>(roster);
        boolean hasDefence = held.stream()
                .anyMatch(id -> board.positionOf().get(id) == Position.DEF);
        if(!hasDefence && !held.isEmpty()){
            held.remove(held.size() - 1);
        }
        roster = held;
        Map<String, Integer> boardRank = new HashMap<>();
        for(int i = 0; i < board.ids().size(); i++){
            boardRank.put(board.ids().get(i), i);
        }
        double total = 0;
        for(int week = 0; week < WeeklyActuals.WEEKS; week++){
            Map<String, Double> points = board.weekly().get(week);
            Map<Position, List<String>> up = new EnumMap<>(Position.class);
            for(String id : roster){
                // no entry that week means he did not play, so he cannot start
                if(points.get(id) != null){
                    up.computeIfAbsent(board.positionOf().get(id),
                            u -> new ArrayList<>()).add(id);
                }
            }
            for(List<String> ids : up.values()){
                ids.sort(Comparator.comparingInt(
                        id -> boardRank.getOrDefault(id, Integer.MAX_VALUE)));
            }
            List<String> flex = new ArrayList<>();
            total += fill(up.get(Position.QB), 1, null, points);
            total += fill(up.get(Position.RB), 2, flex, points);
            total += fill(up.get(Position.WR), 3, flex, points);
            total += fill(up.get(Position.TE), 1, flex, points);
            // A defence can be streamed like anything else - but it costs a
            // roster spot, charged once above by dropping the last drafted man,
            // not free every week. Both halves of Justin's point: the wire is
            // reachable, and reaching it takes up space.
            List<String> defence = up.get(Position.DEF);
            total += defence == null || defence.isEmpty()
                    ? streamedDefencePerWeek()
                    : fill(defence, 1, null, points);
            flex.sort(Comparator.comparingInt(
                    id -> boardRank.getOrDefault(id, Integer.MAX_VALUE)));
            for(int slot = 0; slot < 2 && slot < flex.size(); slot++){
                total += points.getOrDefault(flex.get(slot), 0.0);
            }
        }
        return total;
    }

    static double fill(List<String> available, int slots, List<String> flex,
                       Map<String, Double> points){
        int size = available == null ? 0 : available.size();
        double scored = 0;
        for(int slot = 0; slot < slots && slot < size; slot++){
            scored += points.getOrDefault(available.get(slot), 0.0);
        }
        if(flex != null){
            for(int extra = slots; extra < size; extra++){
                flex.add(available.get(extra));
            }
        }
        return scored;
    }
}
