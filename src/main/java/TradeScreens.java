import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * WHAT THE MAN ACROSS THE TABLE SEES, and whether anyone in this league ever
 * took less of it.
 *
 * Justin, 2026-09-27, on the trade the short list put first - Malik Nabers and
 * Rhamondre Stevenson for Ja'Marr Chase: "no way that trade goes through.
 * something is wrong if you thought it would have even the slightest chance of
 * getting accepted." He was right, and the board had said the opposite twice.
 * Renteez gained on the objective, a roster model he does not run. And the one
 * check on how the trade LOOKS measured draft position on a straight line:
 * Nabers went at 28.6 and Chase at 3.9, 24.7 picks, under the 25 that meant "a
 * grab", so it printed "reads even on draft position". Twenty-five picks between
 * a first-rounder and a third-rounder is not the twenty-five between the tenth
 * round and the twelfth. The numbers on his screen that week - Chase 15.2 a
 * game, Nabers 5.2 with his quarterback on IR, Stevenson 6.5 - were never read.
 *
 * So two screens, both from HIS side of the trade, both what he receives over
 * what he sends, both in season points above the last starter at a man's
 * position:
 *
 *   DRAFT VALUE - each man's ADP read off a chart of what a man drafted there
 *   has actually been worth: season points above the last starter at his
 *   position, averaged over every board EraBoards can join to outcomes and
 *   forced to fall with ADP (pool-adjacent violators). It is steep where the
 *   draft is steep, so pick 4 against pick 29 is a large gap and pick 104
 *   against 129 a small one, and it adds across a package, so two men are not
 *   priced as their best one.
 *
 *   POINTS THIS SEASON - each man's points a game so far above the per-game
 *   points of the last starter at his position, at a season's pace, and zero
 *   for a man on IR. This is the number on the player card.
 *
 * A trade is FAIR TO HIM when he does not lose on either. That is the bar for
 * sending it, and it is where the reputation Justin asked for comes from: the
 * man opposite can check both numbers himself.
 *
 * WHAT THIS DOES NOT DO: fit acceptance. Justin ruled that out (TradePartners:
 * "many trades were lopsided"), and the reason stands - a sample of accepted
 * trades teaches a boundary that says yes to everything. What those trades CAN
 * do is bound the other direction. {@link #history} puts every side of every
 * accepted player-for-player trade on the same two screens, and a proposal that
 * asks him to take less on BOTH than every side that ever said yes here has no
 * precedent in this league. Lopsided precedents make that bound permissive,
 * which is the right direction for "no chance": outside a permissive envelope
 * is really outside.
 *
 *   ./gradlew run -Pmain=TradeScreens -Psend="Malik Nabers,Rhamondre Stevenson" -Preceive="Ja'Marr Chase"
 */
public class TradeScreens {

    static final List<Position> SKILL = List.of(Position.QB, Position.RB, Position.WR, Position.TE);

    /** A skill man; null-safe, because List.of(...).contains(null) throws. */
    static boolean skill(Position p){
        return p != null && SKILL.contains(p);
    }

    /** Injury tags that mean he cannot produce now: his points so far are not what the other manager is buying. */
    static final Set<String> SIDELINED = Set.of("IR", "PUP", "Sus", "NA");

    /** A season, for the points screen: per-game surplus times this. */
    static final int PACE = 17;

    /** Below this many points a side sent nothing on a screen, and cannot have lost on it. */
    static final double NOTHING = 1.0;

    /** The draft-value chart: ADP -> season points above the starter line, never rising with ADP. */
    public record Chart(double[] adp, double[] value) {

        public double at(double x){
            if(adp.length == 0){
                return 0;
            }
            if(!(x > adp[0])){
                return value[0];
            }
            if(x >= adp[adp.length - 1]){
                return value[value.length - 1];
            }
            int hi = 1;
            while(adp[hi] < x){
                hi++;
            }
            double width = adp[hi] - adp[hi - 1];
            if(width <= 0){
                return value[hi];
            }
            double t = (x - adp[hi - 1]) / width;
            return value[hi - 1] + t * (value[hi] - value[hi - 1]);
        }
    }

    /**
     * The non-increasing least-squares fit to {adp, surplus} pairs
     * (pool-adjacent violators), read back as each block's mean ADP and mean
     * surplus; {@link Chart#at} interpolates between block centres.
     */
    static Chart fit(List<double[]> pairs){
        List<double[]> sorted = new ArrayList<>(pairs);
        sorted.sort(Comparator.comparingDouble(p -> p[0]));
        List<double[]> blocks = new ArrayList<>();     // {sum of adp, sum of surplus, count}
        for(double[] p : sorted){
            blocks.add(new double[]{p[0], p[1], 1});
            while(blocks.size() >= 2){
                double[] last = blocks.get(blocks.size() - 1);
                double[] previous = blocks.get(blocks.size() - 2);
                if(previous[1] / previous[2] >= last[1] / last[2]){
                    break;
                }
                previous[0] += last[0];
                previous[1] += last[1];
                previous[2] += last[2];
                blocks.remove(blocks.size() - 1);
            }
        }
        double[] x = new double[blocks.size()];
        double[] y = new double[blocks.size()];
        for(int i = 0; i < blocks.size(); i++){
            x[i] = blocks.get(i)[0] / blocks.get(i)[2];
            y[i] = blocks.get(i)[1] / blocks.get(i)[2];
        }
        return new Chart(x, y);
    }

    /** The last starter's number at each skill position: fixed slots, then the flex to the best remaining. */
    static Map<Position, Double> line(Map<String, Double> score, Map<String, Position> positionOf, int teams, int flex){
        Map<Position, List<Double>> byPosition = new EnumMap<>(Position.class);
        score.forEach((id, v) -> {
            Position p = positionOf.get(id);
            if(skill(p) && v > 0){
                byPosition.computeIfAbsent(p, k -> new ArrayList<>()).add(v);
            }
        });
        ReplacementLevel level = ReplacementLevel.greedy(byPosition, teams, flex);
        Map<Position, Double> out = new EnumMap<>(Position.class);
        for(Position p : SKILL){
            out.put(p, level.of(p));
        }
        return out;
    }

    /** The chart from history: every board man's season points above his position's starter line, by his ADP. */
    static Chart chart(Collection<EraBoards.Board> boards, int teams, int flex){
        List<double[]> pairs = new ArrayList<>();
        for(EraBoards.Board board : boards){
            Map<String, Double> season = board.seasonPoints();
            Map<Position, Double> line = line(season, positions(board.season()), teams, flex);
            for(String id : board.ids()){
                Position p = board.positionOf().get(id);
                if(skill(p)){
                    pairs.add(new double[]{board.adp().get(id), Math.max(0, season.getOrDefault(id, 0.0) - line.get(p))});
                }
            }
        }
        return fit(pairs);
    }

    /** Every man's position in a past season, from its season rows (which carry the player object). */
    static Map<String, Position> positions(String season){
        Map<String, Position> out = new HashMap<>();
        for(JsonElement element : EraActuals.skillRows(season)){
            JsonObject row = element.getAsJsonObject();
            JsonObject player = row.getAsJsonObject("player");
            String position = EraBoards.text(player, "position");
            if(player != null && row.has("player_id") && Position.isStandardPosition(position)){
                out.put(row.get("player_id").getAsString(), Position.valueOf(position));
            }
        }
        return out;
    }

    /** id -> {points, games} over the weeks given, a game being a week in which he has a stat row. */
    static Map<String, double[]> soFar(List<Map<String, Double>> weeks){
        return LeagueWeek.sumWeeks(weeks);
    }

    /**
     * The points screen per man: points a game above the per-game points of the
     * last starter at his position, times a season. The line counts men with at
     * least half the weeks, so one big game from a backup does not set it. A man
     * with no games, or one who is sidelined, shows nothing.
     */
    static Map<String, Double> production(Map<String, double[]> soFar, Map<String, Position> positionOf,
                                          int weeksPlayed, int teams, int flex, Set<String> sidelined){
        int minGames = Math.max(1, (weeksPlayed + 1) / 2);
        Map<String, Double> perGame = new HashMap<>();
        soFar.forEach((id, s) -> {
            if(s[1] >= minGames){
                perGame.put(id, s[0] / s[1]);
            }
        });
        Map<Position, Double> line = line(perGame, positionOf, teams, flex);
        Map<String, Double> out = new HashMap<>();
        soFar.forEach((id, s) -> {
            Position p = positionOf.get(id);
            if(skill(p) && s[1] > 0 && !sidelined.contains(id)){
                out.put(id, PACE * Math.max(0, s[0] / s[1] - line.get(p)));
            }
        });
        return out;
    }

    static double ratio(double in, double out){
        return out < NOTHING ? Double.POSITIVE_INFINITY : in / out;
    }

    /** One side of one accepted trade in this league, on both screens. */
    public record Precedent(String season, int week, String manager, String gets, String gives,
                            double draftIn, double draftOut, double pointsIn, double pointsOut) {
        public double draftRatio(){
            return ratio(draftIn, draftOut);
        }
        public double pointsRatio(){
            return ratio(pointsIn, pointsOut);
        }
    }

    /**
     * A trade as the man opposite sees it: what he receives and sends on each
     * screen, and who in this league ever took as little. `with` is the man
     * opposite, so a precedent can be told apart from his own.
     */
    public record Screens(double draftIn, double draftOut, double pointsIn, double pointsOut,
                          List<String> takenBy, int sides, String with) {

        public double draftRatio(){
            return ratio(draftIn, draftOut);
        }

        public double pointsRatio(){
            return ratio(pointsIn, pointsOut);
        }

        /** Sides of accepted trades here at least as lopsided on both screens. */
        public int precedents(){
            return takenBy.size();
        }

        /** Of those, his own. */
        public int his(){
            int n = 0;
            for(String m : takenBy){
                if(m.equals(with)){
                    n++;
                }
            }
            return n;
        }

        /** He does not lose on either number he can check. */
        public boolean fairToHim(){
            return draftRatio() >= 1 && pointsRatio() >= 1;
        }

        /** He loses, and no side of any accepted trade here ever took as little on both. */
        public boolean noChance(){
            return !fairToHim() && precedents() == 0;
        }

        /**
         * He comes out behind on BOTH numbers he can check, so nothing on his
         * screen argues for the trade. Nabers + Stevenson for Ja'Marr Chase was
         * this (78% of the draft value, 0% of the season's points): two accepted
         * sides in five seasons took as little, both other managers, which is
         * other people's mistakes rather than a reason he would make this one.
         */
        public boolean losesOnBoth(){
            return draftRatio() < 1 && pointsRatio() < 1;
        }

        /** Worth putting in front of him at all: fair, or ahead on one of his numbers with a precedent here. */
        public boolean worthAsking(){
            return fairToHim() || !losesOnBoth() && !noChance();
        }

        /** "45%/0%": what he gets back on each screen, "-" where he sends nothing on it. */
        public String ratios(){
            return share(draftRatio()) + "/" + share(pointsRatio());
        }

        public String verdict(){
            if(fairToHim()){
                return "fair to him on draft value and on this season's points";
            }
            String took = "he gets back " + share(draftRatio()) + " of the draft value and " + share(pointsRatio())
                    + " of the season's points he gives";
            if(precedents() == 0){
                return "NO CHANCE - " + took + "; no side of the " + sides + " accepted here took that little on both";
            }
            String who = precedents() + " of " + sides + " accepted sides here took as little ("
                    + String.join(", ", new java.util.TreeSet<>(takenBy)) + ")" + (with == null ? "" : his() == 0 ? ", never " + with : "");
            return (losesOnBoth() ? "NO - behind on both numbers he can see: " : "a long shot - ") + took + "; " + who;
        }

        static String share(double r){
            return Double.isInfinite(r) ? "-" : String.format("%.0f%%", 100 * r);
        }
    }

    /** The managers of the sides of accepted trades at least as lopsided as this on BOTH screens, one entry a side. */
    static List<String> precedents(double draftRatio, double pointsRatio, List<Precedent> history){
        List<String> out = new ArrayList<>();
        for(Precedent p : history){
            if(p.draftRatio() <= draftRatio && p.pointsRatio() <= pointsRatio){
                out.add(p.manager());
            }
        }
        return out;
    }

    /** One trade from HIS side: he receives hisIn and sends hisOut. */
    static Screens screen(List<String> hisIn, List<String> hisOut, ToDoubleFunction<String> draft,
                          ToDoubleFunction<String> points, List<Precedent> history, String with){
        double draftIn = 0, draftOut = 0, pointsIn = 0, pointsOut = 0;
        for(String id : hisIn){
            draftIn += draft.applyAsDouble(id);
            pointsIn += points.applyAsDouble(id);
        }
        for(String id : hisOut){
            draftOut += draft.applyAsDouble(id);
            pointsOut += points.applyAsDouble(id);
        }
        return new Screens(draftIn, draftOut, pointsIn, pointsOut,
                precedents(ratio(draftIn, draftOut), ratio(pointsIn, pointsOut), history), history.size(), with);
    }

    /** Every side of every completed player-for-player trade in the league's finished seasons, on both screens. */
    record History(List<Precedent> sides, int trades, int withPicks, int noBoard) {}

    static History history(String leagueID, Map<String, EraBoards.Board> boards, Chart chart, int teams, int flex){
        List<Precedent> sides = new ArrayList<>();
        int trades = 0, withPicks = 0, noBoard = 0;
        for(LeagueTransactions.Year year : LeagueTransactions.completedSeasons(leagueID)){
            String season = year.season();
            EraBoards.Board board = boards.containsKey(season) ? boards.get(season) : EraBoards.tryBuild(season, "ppr");
            Map<String, Position> positionOf = positions(season);
            Map<String, String> nameOf = names(season);
            Map<Integer, String> managerOf = TradePartners.managersOf(year.leagueID());
            Map<Integer, Map<String, Double>> productionAt = new HashMap<>();
            for(int w = 1; w <= EraActuals.weeks(season); w++){
                JsonArray rows = JsonParser.parseString(LeagueTransactions.transactionsRaw(year.leagueID(), w)).getAsJsonArray();
                for(JsonElement element : rows){
                    JsonObject row = element.getAsJsonObject();
                    if(!"trade".equals(EraBoards.text(row, "type")) || !"complete".equals(EraBoards.text(row, "status"))){
                        continue;
                    }
                    trades++;
                    if(nonEmpty(row, "draft_picks") || nonEmpty(row, "waiver_budget")){
                        withPicks++;       // a pick or FAAB is on neither screen, so the trade would read as a gift
                        continue;
                    }
                    if(board == null){
                        noBoard++;
                        continue;
                    }
                    int leg = row.has("leg") && !row.get("leg").isJsonNull() ? row.get("leg").getAsInt() : w;
                    int played = Math.max(0, Math.min(leg - 1, board.weekly().size()));
                    Map<String, Double> production = productionAt.computeIfAbsent(played, k -> production(
                            soFar(board.weekly().subList(0, k)), positionOf, k, teams, flex, Set.of()));
                    Map<Integer, List<String>> in = byRoster(row, "adds");
                    Map<Integer, List<String>> out = byRoster(row, "drops");
                    Set<Integer> rosters = new LinkedHashSet<>(in.keySet());
                    rosters.addAll(out.keySet());
                    for(int roster : rosters){
                        List<String> gets = in.getOrDefault(roster, List.of());
                        List<String> gives = out.getOrDefault(roster, List.of());
                        ToDoubleFunction<String> draft = id -> skill(positionOf.get(id))
                                ? chart.at(board.adp().getOrDefault(id, 999.0)) : 0;
                        ToDoubleFunction<String> points = id -> production.getOrDefault(id, 0.0);
                        sides.add(new Precedent(season, leg, managerOf.getOrDefault(roster, "roster " + roster),
                                named(gets, nameOf), named(gives, nameOf),
                                sum(gets, draft), sum(gives, draft), sum(gets, points), sum(gives, points)));
                    }
                }
            }
        }
        return new History(sides, trades, withPicks, noBoard);
    }

    static double sum(List<String> ids, ToDoubleFunction<String> f){
        double total = 0;
        for(String id : ids){
            total += f.applyAsDouble(id);
        }
        return total;
    }

    static boolean nonEmpty(JsonObject row, String key){
        JsonElement e = row.get(key);
        return e != null && e.isJsonArray() && e.getAsJsonArray().size() > 0;
    }

    /** roster id -> the players a trade row moves to it (adds) or from it (drops). */
    static Map<Integer, List<String>> byRoster(JsonObject row, String key){
        Map<Integer, List<String>> out = new HashMap<>();
        JsonElement e = row.get(key);
        if(e != null && e.isJsonObject()){
            for(Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()){
                if(!entry.getValue().isJsonNull()){
                    out.computeIfAbsent(entry.getValue().getAsInt(), k -> new ArrayList<>()).add(entry.getKey());
                }
            }
        }
        return out;
    }

    static Map<String, String> names(String season){
        Map<String, String> out = new HashMap<>();
        for(JsonElement element : EraActuals.skillRows(season)){
            JsonObject row = element.getAsJsonObject();
            JsonObject player = row.getAsJsonObject("player");
            if(player != null && row.has("player_id")){
                out.put(row.get("player_id").getAsString(),
                        EraBoards.text(player, "first_name") + " " + EraBoards.text(player, "last_name"));
            }
        }
        return out;
    }

    static String named(List<String> ids, Map<String, String> nameOf){
        List<String> out = new ArrayList<>();
        for(String id : ids){
            Player p = nameOf.containsKey(id) ? null : Player.getPlayerFromSIDV2(id);
            out.add(nameOf.containsKey(id) ? nameOf.get(id) : p == null ? id : p.firstName + " " + p.lastName);
        }
        return out.isEmpty() ? "nothing" : String.join(" + ", out);
    }

    /** Everything a caller needs to put today's trades on both screens. */
    public record Context(Chart chart, History history, ToDoubleFunction<String> draft, ToDoubleFunction<String> points,
                          int weeksPlayed) {

        /** A trade from his side: he receives what Justin gives and sends what Justin gets (and any man he must cut). */
        public Screens of(TradeMarket.Trade trade){
            return screen(trade.give(), trade.hisOut(), draft, points, history.sides(), trade.withManager());
        }
    }

    public static Context load(AAAConfiguration configuration){
        int teams = configuration.getLeagueJson().getAsJsonObject("settings").get("num_teams").getAsInt();
        int flex = StartingLineup.flexSlotsPerTeam(configuration);
        Map<String, EraBoards.Board> boards = EraBoards.usable("ppr", EraIngest.MIN_RATE, EraIngest.minDepth());
        Chart chart = chart(boards.values(), teams, flex);
        History history = history(configuration.getLeagueID(), boards, chart, teams, flex);

        String season = LeagueWeek.season();
        int week = LeagueWeek.week();
        List<Map<String, Double>> weeks = new ArrayList<>();
        for(int w = 1; w <= week; w++){
            weeks.add(LeagueWeek.actualSoFar(season, w));
        }
        Map<String, double[]> sums = soFar(weeks);
        Map<String, Position> positionOf = new HashMap<>();
        Set<String> sidelined = new HashSet<>();
        for(String id : sums.keySet()){
            Player p = Player.getPlayerFromSIDV2(id);
            if(p != null && p.position != null){
                positionOf.put(id, p.position);
            }
            String status = SleeperProjections.injuryStatusOf(id);
            if(status != null && SIDELINED.contains(status)){
                sidelined.add(id);
            }
        }
        Map<String, Double> production = production(sums, positionOf, week, teams, flex, sidelined);
        ToDoubleFunction<String> draft = id -> {
            Player p = Player.getPlayerFromSIDV2(id);
            return p != null && skill(p.position) ? chart.at(SleeperProjections.adpOf(id)) : 0;
        };
        return new Context(chart, history, draft, id -> production.getOrDefault(id, 0.0), week);
    }

    /** Today's men with these full names (a team, so a retired namesake is not picked up), in order. */
    static List<String> idsNamed(String csv) throws IOException {
        JsonObject db;
        try(FileReader reader = new FileReader("sleeperDataPlayerAPI.json")){
            db = JsonParser.parseReader(reader).getAsJsonObject();
        }
        List<String> out = new ArrayList<>();
        for(String raw : csv.split(",")){
            String name = raw.trim();
            if(name.isEmpty()){
                continue;
            }
            for(Map.Entry<String, JsonElement> e : db.entrySet()){
                JsonObject p = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : null;
                if(p != null && KalshiFair.normal(name).equals(KalshiFair.normal(ScreenData.text(p, "full_name")))
                        && ScreenData.text(p, "team") != null){
                    out.add(e.getKey());
                }
            }
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Context context = load(configuration);
        History history = context.history();
        StringBuilder out = new StringBuilder();
        out.append(String.format("TRADE SCREENS  %s  (what the man opposite sees; season %s, week %d)%n%n", LocalDate.now(),
                LeagueWeek.season(), context.weeksPlayed()));

        out.append("THE DRAFT-VALUE CHART - season points above the last starter at his position, for a man drafted at each ADP\n");
        out.append("(every board EraBoards joins to outcomes, fitted to fall with ADP):\n  ");
        for(double adp : new double[]{1, 4, 8, 12, 18, 24, 29, 36, 48, 60, 72, 96, 120, 150, 180}){
            out.append(String.format("%.0f:%.0f  ", adp, context.chart().at(adp)));
        }
        out.append(String.format("%n  So pick 4 against pick 29 is %.0f against %.0f, and pick 104 against 129 is %.0f against %.0f -%n"
                        + "  the same 25 picks, which the old straight-line check could not tell apart.%n%n",
                context.chart().at(4), context.chart().at(29), context.chart().at(104), context.chart().at(129)));

        List<Precedent> sides = new ArrayList<>(history.sides());
        out.append(String.format("THIS LEAGUE'S ACCEPTED TRADES on the same screens: %d completed trades in its finished seasons; %d carried%n"
                        + "a draft pick or FAAB (on neither screen, so left out) and %d fell in a season with no joined board.%n"
                        + "That leaves %d sides. Each side: what it got back on each screen, over what it gave.%n",
                history.trades(), history.withPicks(), history.noBoard(), sides.size()));
        sides.sort(Comparator.comparingDouble((Precedent p) -> Math.max(p.draftRatio(), p.pointsRatio())));
        out.append(String.format("  %-6s %-4s %-14s %8s %8s   %s%n", "season", "wk", "manager", "draft", "points", "GOT  <-  GAVE"));
        for(Precedent p : sides.subList(0, Math.min(20, sides.size()))){
            out.append(String.format("  %-6s %-4d %-14s %8s %8s   %s  <-  %s%n", p.season(), p.week(), p.manager(),
                    Screens.share(p.draftRatio()), Screens.share(p.pointsRatio()), p.gets(), p.gives()));
        }
        out.append("  (the twenty most lopsided against the side named, by the better of its two screens)\n");

        String give = System.getProperty("send", "");
        String get = System.getProperty("receive", "");
        if(!give.isBlank() && !get.isBlank()){
            List<String> hisIn = idsNamed(give);
            List<String> hisOut = idsNamed(get);
            Map<String, String> ownerOf = LeagueOwners.today(configuration);
            Screens s = screen(hisIn, hisOut, context.draft(), context.points(), sides,
                    hisOut.isEmpty() ? null : ownerOf.get(hisOut.get(0)));
            out.append(String.format("%nTHE TRADE - you give %s, you get %s. From HIS side:%n", give, get));
            for(String id : hisIn){
                out.append(String.format("  he gets  %-24s draft %6.1f   points %6.1f%n", name(id), context.draft().applyAsDouble(id),
                        context.points().applyAsDouble(id)));
            }
            for(String id : hisOut){
                out.append(String.format("  he gives %-24s draft %6.1f   points %6.1f%n", name(id), context.draft().applyAsDouble(id),
                        context.points().applyAsDouble(id)));
            }
            out.append(String.format("  totals: draft %.1f for %.1f, points %.1f for %.1f%n  %s%n", s.draftIn(), s.draftOut(),
                    s.pointsIn(), s.pointsOut(), s.verdict()));
            out.append(objective(configuration, ownerOf, hisIn, hisOut));
        }
        System.out.print(out);
        Path report = Path.of("data", "trade-screens-" + LocalDate.now() + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }

    /**
     * WHY THE OBJECTIVE SAID WHAT IT SAID. Justin, 2026-09-27: "I feel like the
     * trade evaluator must be wrong as well to give up chase in this situation."
     * So the same trade is priced for both managers on each projection source
     * the trade tools can use, and his best lineup is printed before and after
     * on the one that moves on news, so the slot the trade is supposed to fill
     * can be seen rather than inferred. The side receiving more men cuts his
     * lowest-projected other man, as TradeMarket.unbalanced does.
     */
    static String objective(AAAConfiguration configuration, Map<String, String> ownerOf, List<String> mySend,
                            List<String> myReceive) throws Exception {
        String me = configuration.getUserIDToDisplayName().getOrDefault(configuration.getMyID(), configuration.getMyID());
        Map<String, List<String>> rosters = new HashMap<>();
        ownerOf.forEach((id, m) -> rosters.computeIfAbsent(m, k -> new ArrayList<>()).add(id));
        String with = ownerOf.get(myReceive.get(0));
        List<String> mine = rosters.get(me);
        List<String> his = rosters.get(with);
        StringBuilder out = new StringBuilder(String.format(
                "%nWHAT THE OBJECTIVE SAYS - 17 x the best legal lineup, the bench as insurance, 240 drawn seasons - on each pricing:%n"));
        out.append(String.format("  %-18s %9s %9s   %s%n", "pricing", "you", with, "his cut (if he takes more men)"));
        Map<String, Double> remaining = null;
        for(String source : List.of("sleeper", "sleeper-remaining", "ros")){
            Map<String, Double> points = ProjectionSources.resolve(source);
            if(source.equals("sleeper-remaining")){
                remaining = points;
            }
            WeeklyStarterValue value = WeeklyStarterValue.forCurrentBoard(configuration, points, 240, 424_242L);
            List<String> mySends = new ArrayList<>(mySend);
            List<String> hisSends = new ArrayList<>(myReceive);
            String cut = null;
            if(mySend.size() > myReceive.size()){
                cut = TradeMarket.worstOther(his, myReceive, points);
                hisSends.add(cut);
            }
            if(myReceive.size() > mySend.size()){
                mySends.add(TradeMarket.worstOther(mine, mySend, points));
            }
            double myGain = value.of(TradeMarket.swap(mine, mySends, myReceive)) - value.of(mine);
            double hisGain = value.of(TradeMarket.swap(his, hisSends, mySend)) - value.of(his);
            out.append(String.format("  %-18s %+9.1f %+9.1f   %s%n", source, myGain, hisGain, cut == null ? "" : name(cut)));
        }
        out.append(String.format("%n  %s's best lineup on sleeper-remaining (Sleeper's weekly projections over the games left):%n", with));
        List<String> after = new ArrayList<>(his);
        after.removeAll(myReceive);
        if(mySend.size() > myReceive.size()){
            after.remove(TradeMarket.worstOther(his, myReceive, remaining));
        }
        after.addAll(mySend);
        TeamRankings.Lineup before = lineup(his, remaining);
        TeamRankings.Lineup then = lineup(after, remaining);
        out.append(String.format("  %-34s %-34s%n", "BEFORE", "AFTER"));
        for(int i = 0; i < Math.max(before.starting().size(), then.starting().size()); i++){
            out.append(String.format("  %-34s %-34s%n", slot(before.starting(), i), slot(then.starting(), i)));
        }
        out.append(String.format("  %-34s %-34s%n", String.format("starters %.1f", before.starters()),
                String.format("starters %.1f", then.starters())));
        return out.toString();
    }

    static TeamRankings.Lineup lineup(List<String> roster, Map<String, Double> points){
        List<TeamRankings.Man> men = new ArrayList<>();
        for(String id : roster){
            Player p = Player.getPlayerFromSIDV2(id);
            if(p != null && p.position != null){
                men.add(new TeamRankings.Man(id, p.firstName + " " + p.lastName, p.position.name(), p.team,
                        points.getOrDefault(id, 0.0), false, 0, null));
            }
        }
        return TeamRankings.bestLineup(men);
    }

    static String slot(List<TeamRankings.Man> starting, int i){
        if(i >= starting.size()){
            return "";
        }
        TeamRankings.Man m = starting.get(i);
        return String.format("%-3s %-22s %6.1f", m.position(), m.name(), m.points());
    }

    static String name(String id){
        Player p = Player.getPlayerFromSIDV2(id);
        return p == null ? id : p.firstName + " " + p.lastName;
    }
}
