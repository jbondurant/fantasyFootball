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
    // that pool is scored in, and it moves with the pool's key as well as its
    // scoring - 8.5 a week under pts_half_ppr on the projection-keyed pool that
    // ships, 8.7 on the ADP-keyed one. A single cached value would let a tool that scores
    // both ways price the wire in one unit and the rosters in the other, which
    // is the units bug that once printed 0.0 for defences.
    private static final Map<Boolean, Double> streamedDefence = new HashMap<>();

    /**
     * Override the streamed-defence rate, for the sensitivity check only.
     *
     * WireRateStress showed the shipped 8.75 is chosen by sorting undrafted
     * defences on their REALISED season and averaging the best quarter of them,
     * which is a choice made after the season. Hindsight-free streaming policies
     * measured on the same five seasons return 7.5-7.7. This knob exists so the
     * backtest can be rerun at the honest rate instead of the number being
     * argued about; it is absent by default and the default path is untouched.
     *
     *   ./gradlew run -Pmain=PlanBacktest -PwireDef=7.73
     */
    static synchronized double streamedDefencePerWeek(){
        String override = System.getProperty("wireDef");
        if(override != null && !override.isBlank()){
            return Double.parseDouble(override.trim());
        }
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
        // A STRAWMAN, kept only because every number on record was measured
        // against it. RUNBOOK.md:77 makes the round-10 quarterback conditional
        // ("if there - else RB/WR"), :78 says round 11 is "anything you want",
        // and :79 offers round 14 as the ALTERNATIVE to that stash, not an
        // addition. This string takes both conditionals as certain and adds a
        // tight end at 11, so it drafts two quarterbacks - three with Purdy
        // kept, on a one-quarterback lineup - and a second tight end.
        //
        // Correcting it is worth -17 points, which is nothing against a
        // 125-point bar, and THAT is the finding: seasonPoints scores the best
        // legal lineup and never charges a roster for the picks it wasted. Two
        // unusable quarterbacks cost nothing here. So a model that avoids the
        // traps will not score better for avoiding them - it will be sound, not
        // stronger, and those are different goals.
        STRATEGIES.put("RUNBOOK committed",
                "RB RB RB WR WR WR WR TE WR QB TE QB RB DEF");
        // What the document actually prescribes, with its conditionals resolved
        // the way it resolves them: one stash, round 11 free, round 14 skill.
        STRATEGIES.put("RUNBOOK as written",
                "RB RB RB WR WR WR WR TE WR QB WR RB RB DEF");
        // What BoardValue drafts when the board falls at ADP. Kept here so the
        // higher-resolution harness can score it beside everything else.
        STRATEGIES.put("board value",
                "RB TE RB TE WR WR RB WR WR QB RB RB WR DEF");
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

    /**
     * A historical board: the men in ADP order, their positions, the weekly
     * actuals, and each man's SOURCE positional rank - his place among every
     * playable row of the ADP file at his position, joined or not. A synthetic
     * board built with the four-argument constructor ranks by list position,
     * which is the same thing when every man is present.
     */
    public record Board(String season, List<String> ids, Map<String, Position> positionOf,
                 List<Map<String, Double>> weekly, Map<String, Integer> rankOf){

        public Board(String season, List<String> ids, Map<String, Position> positionOf,
                     List<Map<String, Double>> weekly){
            this(season, ids, positionOf, weekly, indexRanks(ids, positionOf));
        }

        /** Rank by list position within each position - every man present, no gaps. */
        public static Map<String, Integer> indexRanks(List<String> ids, Map<String, Position> positionOf){
            Map<String, Integer> rankOf = new HashMap<>();
            Map<Position, Integer> next = new EnumMap<>(Position.class);
            for(String id : ids){
                rankOf.put(id, next.merge(positionOf.get(id), 1, Integer::sum) - 1);
            }
            return rankOf;
        }

        /** The same board, same men and same ranks, scored by a different weekly feed. */
        public Board withWeekly(List<Map<String, Double>> replacement){
            return new Board(season, ids, positionOf, replacement, rankOf);
        }

        /** Each man's tier on this board, from his source rank (TRAPS #80). */
        public Map<String, Integer> tiersOf(){
            Map<String, Integer> tierOf = new HashMap<>();
            for(String id : ids){
                tierOf.put(id, rankOf.getOrDefault(id, 0) / WeeklyStarterValue.TIER);
            }
            return tierOf;
        }
    }

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
        record Row(String id, Position position, double adp){}   // id null when the name did not join
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
            rows.add(new Row(id, position, Double.parseDouble(cells[adpCol])));
        }
        rows.sort(Comparator.comparingDouble(Row::adp));
        // source ranks: every playable row advances its position's counter, so
        // a man whose name did not join still holds his place (TRAPS #80)
        List<String> ids = new ArrayList<>();
        Map<String, Position> positionOf = new HashMap<>();
        Map<String, Integer> rankOf = new HashMap<>();
        Map<Position, Integer> next = new EnumMap<>(Position.class);
        for(Row row : rows){
            int rank = next.merge(row.position(), 1, Integer::sum) - 1;
            if(row.id() != null && !positionOf.containsKey(row.id())){
                ids.add(row.id());
                positionOf.put(row.id(), row.position());
                rankOf.put(row.id(), rank);
            }
        }
        List<Map<String, Double>> weekly = new ArrayList<>();
        for(int week = 1; week <= WeeklyActuals.WEEKS; week++){
            weekly.add(LeagueActuals.weeklyPoints(season, week));
        }
        return new Board(season, ids, positionOf, weekly, rankOf);
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

    /**
     * -PholdKeepers=true. Off leaves every existing number untouched.
     *
     * -PleagueKeepers implies it: with all twenty-four off the board Justin
     * holds his two by construction, and a roster that holds a quarterback and
     * a back must say so to RosterRules or the legality arithmetic below goes
     * wrong in the other direction.
     */
    public static boolean holdKeepers(){
        return Boolean.getBoolean("holdKeepers") || EraSlate.enabled();
    }

    /**
     * The keeper slots Justin starts with, as (position, board rank).
     *
     * Purdy is QB9 and Tuten RB23, so that is what the valuation must be told.
     * BoardValue read these off a running count of who had left the board,
     * which for the first man kept is 2 by arithmetic and not by measurement -
     * it priced Purdy as the second-best quarterback alive. Harmless while the
     * only thing gone was the keepers themselves and plainly wrong the moment
     * twenty-four men are, so it is corrected here rather than there, and only
     * behind the flag: every number on record was measured with the old
     * behaviour and must stay reproducible tonight.
     *
     *   ./gradlew run -Pmain=BoardValue -PholdKeepers=true -PkeeperRanks=true
     */
    public static boolean keeperRanks(){
        return Boolean.getBoolean("keeperRanks") || EraSlate.enabled();
    }

    /** Everyone kept LEAGUE-WIDE on this board, mine included. Empty unless flagged. */
    public static List<String> offBoard(Board board){
        if(EraSlate.enabled()){
            return EraSlate.heldOn(board);
        }
        return holdKeepers() ? keeperIDs(board) : List.of();
    }

    /** The ones of those that are mine, and go onto my roster. */
    public static List<String> heldByMe(Board board){
        if(EraSlate.enabled()){
            return EraSlate.mineOn(board);
        }
        return holdKeepers() ? keeperIDs(board) : List.of();
    }

    /** A pick a keeper has already spent selects nobody. Empty unless flagged. */
    public static Set<Integer> spentPicks(){
        return EraSlate.enabled() ? EraSlate.occupiedPicks() : Set.of();
    }

    /**
     * A man's positional rank on this board, which is what a value curve indexes.
     *
     * Counted over the WHOLE board rather than over who is still on it, because
     * the curve is "what the nth best back at this position returns" and the men
     * above him being kept does not promote him. It is the SOURCE's order where
     * the board carries one (TRAPS #80): a man whose name failed the join holds
     * his place too. Somebody not on the board reads one past the deepest man of
     * his position, the same convention the pools use.
     */
    public static int rankOn(Board board, String him){
        Integer source = board.rankOf().get(him);
        if(source != null){
            return source + 1;   // the source's order, 1-based (TRAPS #80)
        }
        Position position = board.positionOf().get(him);
        int rank = 0;
        for(String id : board.ids()){
            if(board.positionOf().get(id) == position){
                rank++;
                if(id.equals(him)){
                    return rank;
                }
            }
        }
        return rank + 1;
    }

    /**
     * What a fourteen-pick plan must still supply. With the keepers held, the
     * quarterback and one back are already covered, so a nought-quarterback
     * plan becomes legal - which is the whole point of holding them.
     */
    /*
     * Superseded by RosterRules, which is now the single authority on what a
     * legal roster is and what a pick costs. The map used to be typed here -
     * QB 1, RB 2, WR 3, TE 1, DEF 1, minus one QB and one RB when the keepers
     * are held - and typing it in a second place is how the arithmetic drifts.
     * RosterRules subtracts the same thing from the league's own roster_positions
     * array, so the numbers are identical (RosterRulesTest pins both settings of
     * -PholdKeepers) and there is only one of them.
     */
    public static Map<Position, Integer> requiredPicks(){
        RosterRules rules = RosterRules.live();
        return holdKeepers() ? rules.justins().stillNeeds() : rules.empty().stillNeeds();
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
        // Off the board - nobody drafts a man somebody already owns - and mine
        // onto the roster, so the empty slot he fills is actually filled. With
        // -PleagueKeepers that is all twenty-four, of which two are mine.
        gone.addAll(offBoard(board));
        mine.addAll(heldByMe(board));
        Set<Integer> myPicks = new HashSet<>();
        for(int pick : MY_PICKS){
            myPicks.add(pick);
        }
        // The picks those keepers have already spent. Removing the men without
        // removing the picks would have the other eleven consume twenty-four
        // EXTRA men off the bottom of the board, which is a different wrong
        // board rather than a smaller one.
        Set<Integer> spent = spentPicks();
        int taken = 0;
        for(int pick = 1; pick <= 200 && taken < MY_PICKS.length; pick++){
            if(!myPicks.contains(pick) && spent.contains(pick)){
                continue;
            }
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
     *
     * AUDITED 2026-09-01, because the paragraph above is exactly the kind of
     * prose this repo has been burnt by three times (TRAPS.md F27) and nobody
     * had checked it against the code. It is TRUE: the only sort key below is
     * boardRank, which is the index into board.ids(), which PlanBacktest.board
     * builds by sorting the season's FantasyPros preseason ADP file on its AVG
     * column. The week's realised points enter in exactly two places - deciding
     * who PLAYED, and being added up - and in neither does a bigger number move
     * a man up the depth chart.
     *
     * The three-argument form below exists so that claim is MEASURED rather
     * than described: ScorerHonestyAudit scores the same rosters both ways and
     * prints the premium hindsight would have been worth. Nothing in the repo
     * passes byRealised = true except that audit and its tests.
     */
    public static double seasonPoints(Board board, List<String> roster){
        return seasonPoints(board, roster, false);
    }

    /**
     * The same eighteen weeks, with the depth chart set either way.
     *
     * byRealised = false is the shipped scorer and the only thing any published
     * number was computed with. byRealised = true is the counterfactual - a
     * manager who reads the box score before he sets his lineup - and it is
     * here to be SUBTRACTED, not used. It is also the correct scorer for best
     * ball, where choosing retrospectively is the rules rather than cheating.
     */
    public static double seasonPoints(Board board, List<String> roster, boolean byRealised){
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
            // THE DEPTH CHART. Preseason ADP rank by default; the week's result
            // only under the audit's counterfactual. Built inside the week loop
            // because the realised key changes every week and the honest one
            // does not - which is itself the difference between them.
            Comparator<String> depthChart = byRealised
                    ? Comparator.comparingDouble(
                            (String id) -> points.getOrDefault(id, 0.0)).reversed()
                    : Comparator.comparingInt(
                            id -> boardRank.getOrDefault(id, Integer.MAX_VALUE));
            for(List<String> ids : up.values()){
                ids.sort(depthChart);
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
            flex.sort(depthChart);
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
