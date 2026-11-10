import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE REST OF A SEASON, AS THE BETTING MARKET PRICES IT.
 *
 * Justin, 2026-11-07, after Kalshi's props had priced only Ashton Jeanty's next
 * game: "any betting props for more than just next week game?" Kalshi lists
 * season-long player markets too (season rushing yards and touchdowns, fantasy
 * totals, top-ten finishes), but that day they were dead: spreads of 10 to 90
 * cents and almost no trading since August. Polymarket's season ladders were
 * live. Jeanty's 774.5-yard line traded about $2,400 in the week after Mike
 * Washington Jr. took his early downs. So this reads Polymarket.
 *
 * For each named man it takes every open "Pro Football: NAME SEASON Regular
 * Season STAT" event in Polymarket's public search, and each line's midpoint
 * where the spread is 20 cents or less and the midpoint is between 2 and 98
 * cents (KalshiFair's filter). Beside that it prints the stat so far from
 * Sleeper's stat rows and Sleeper's projected stat lines summed over the weeks
 * left. From those it gives the market's chance he passes Sleeper's projected
 * total, and the market's median with what that median leaves for the games
 * left. The ladder is read linearly between lines; outside them it says only
 * that. Then it swaps each priced stat's median for Sleeper's remaining number
 * in his league points over the weeks left. The ratio of the two is the factor
 * to try in TradeScreens' -PprojectionScale. A median is not a mean: for
 * touchdowns, which skew, it reads low.
 *
 *     ./gradlew run -Pmain=SeasonProps -Pplayers="Ashton Jeanty,Rhamondre Stevenson"
 *
 * Raw reads are archived under data/polymarket/ (gitignored). Report to
 * data/season-props-&lt;date&gt;.txt.
 */
public class SeasonProps {

    static final String SEARCH = "https://gamma-api.polymarket.com/public-search?limit_per_type=50&q=";
    static final Path ARCHIVE = Path.of("data", "polymarket");

    /** Polymarket's stat words, and the keys of Sleeper's stat and projection rows. */
    static final Map<String, String> KEYS = new LinkedHashMap<>();
    static {
        KEYS.put("Rushing Yards", "rush_yd");
        KEYS.put("Rushing Touchdowns", "rush_td");
        KEYS.put("Receiving Yards", "rec_yd");
        KEYS.put("Receptions", "rec");
        KEYS.put("Receiving Touchdowns", "rec_td");
        KEYS.put("Passing Yards", "pass_yd");
        KEYS.put("Passing Touchdowns", "pass_td");
    }

    /** One line of a ladder: the total it is over, and the market's chance of it. */
    record Line(double strike, double p) {}

    private static final Pattern STRIKE = Pattern.compile("^\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\+");

    /** "1,174.5+ rushing yards" -> 1174.5; null for a label that names no line. */
    static Double strike(String label){
        Matcher m = label == null ? null : STRIKE.matcher(label);
        return m != null && m.find() ? Double.parseDouble(m.group(1).replace(",", "")) : null;
    }

    /** The market's chance the total passes x, read linearly between lines; NaN outside them. */
    static double above(List<Line> ladder, double x){
        for(int i = 0; i + 1 < ladder.size(); i++){
            Line a = ladder.get(i), b = ladder.get(i + 1);
            if(x >= a.strike() && x <= b.strike()){
                return a.p() + (b.p() - a.p()) * (x - a.strike()) / (b.strike() - a.strike());
            }
        }
        return ladder.size() == 1 && x == ladder.get(0).strike() ? ladder.get(0).p() : Double.NaN;
    }

    /** The total the market puts at even money, between the first two lines that straddle it; NaN if none do. */
    static double median(List<Line> ladder){
        for(int i = 0; i + 1 < ladder.size(); i++){
            Line a = ladder.get(i), b = ladder.get(i + 1);
            if(a.p() >= 0.5 && b.p() <= 0.5 && a.p() > b.p()){
                return a.strike() + (b.strike() - a.strike()) * (a.p() - 0.5) / (a.p() - b.p());
            }
        }
        return Double.NaN;
    }

    /** The season as Polymarket writes it: 2026 -> "2026-27". */
    static String seasonLabel(String season){
        return season + "-" + String.format("%02d", (Integer.parseInt(season) + 1) % 100);
    }

    /** The stat an event prices for this man and season ("Rushing Yards"), or null if the event is about something else. */
    static String statOf(String title, String name, String season){
        String head = "Pro Football: ";
        String middle = " " + seasonLabel(season) + " Regular Season ";
        if(title == null || !title.startsWith(head) || !title.contains(middle)){
            return null;
        }
        String who = title.substring(head.length(), title.indexOf(middle));
        String stat = title.substring(title.indexOf(middle) + middle.length()).trim();
        return KalshiFair.normal(who).equals(KalshiFair.normal(name)) && KEYS.containsKey(stat) ? stat : null;
    }

    /** One event's ladder from its markets: lines that pass the quote filter, by strike; the rest counted. */
    static List<Line> ladder(JsonArray markets, int[] leftOut){
        List<Line> out = new ArrayList<>();
        for(JsonElement e : markets){
            JsonObject m = e.getAsJsonObject();
            Double k = strike(ScreenData.text(m, "groupItemTitle"));
            String bid = ScreenData.text(m, "bestBid"), ask = ScreenData.text(m, "bestAsk");
            if(k == null || bid == null || ask == null || Boolean.parseBoolean(ScreenData.text(m, "closed"))){
                continue;
            }
            double b = Double.parseDouble(bid), a = Double.parseDouble(ask), mid = (a + b) / 2;
            if(a - b > 0.20 + 1e-9 || mid <= 0.02 || mid >= 0.98){       // 0.91 - 0.71 is 0.20000000000000007 in doubles
                leftOut[0]++;
                continue;
            }
            out.add(new Line(k, mid));
        }
        out.sort(Comparator.comparingDouble(Line::strike));
        return out;
    }

    static String search(HttpClient http, String name, Path dir) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(SEARCH + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                .header("Accept", "application/json").build(), HttpResponse.BodyHandlers.ofString());
        if(r.statusCode() != 200){
            throw new IOException("polymarket returned " + r.statusCode() + " for " + name);
        }
        Files.writeString(dir.resolve(name.replaceAll("[^A-Za-z0-9]", "_") + ".json"), r.body(), StandardCharsets.UTF_8);
        return r.body();
    }

    /**
     * The season so far and Sleeper's weeks left, read once: what every ladder is
     * set against. Stat rows for every finished week and this week's for clubs
     * whose game ended; Sleeper's projected lines and points for the weeks after.
     */
    record Context(String season, int week, int done, Set<String> over, List<JsonArray> rows,
                   List<Map<String, JsonObject>> projected, List<Map<String, Double>> points, Map<String, Double> weight){

        static Context load(){
            LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
            Map<String, Double> weight = Map.of("rush_yd", scoring.rushYard, "rush_td", scoring.rushTD, "rec_yd", scoring.receivingYard,
                    "rec", scoring.reception, "rec_td", scoring.receivingTD, "pass_yd", scoring.passYard, "pass_td", scoring.passTD);
            String season = LeagueWeek.season();
            int week = LeagueWeek.week();
            int last = EraActuals.weeks(season);
            Set<String> over = NflverseGames.kickedOff(NflverseGames.games(), season, week,
                    LocalDateTime.now(ZoneId.of("America/New_York")).minusHours(4));
            int done = LeagueWeek.finished(season, week) ? week : week - 1;
            List<JsonArray> rows = new ArrayList<>();
            for(int w = 1; w <= Math.min(done + 1, week); w++){
                rows.add(JsonParser.parseString(LeagueWeek.teamStatsBody(season, w)).getAsJsonArray());
            }
            List<Map<String, JsonObject>> projected = new ArrayList<>();
            List<Map<String, Double>> points = new ArrayList<>();
            for(int w = done + 1; w <= last; w++){
                String body = LeagueWeek.projectionsBody(season, w);
                projected.add(ScreenData.projectedLines(body));
                points.add(LeagueWeek.projectedFrom(body));
            }
            return new Context(season, week, done, over, rows, projected, points, weight);
        }

        /** Whether his club's game this week, still in progress for the league, has ended: then it is in his so far, not his left. */
        boolean playedThisWeek(String id){
            Player p = Player.getPlayerFromSIDV2(id);
            return done < week && p != null && p.team != null && over.contains(p.team);
        }

        double soFar(String id, String key){
            boolean played = playedThisWeek(id);
            double total = 0;
            for(int w = 0; w < rows.size(); w++){
                if(w < done || played){
                    for(JsonElement r : rows.get(w)){
                        JsonObject row = r.getAsJsonObject();
                        if(id.equals(ScreenData.text(row, "player_id"))){
                            total += FaabDemand.stat(ScreenData.object(row, "stats"), key);
                        }
                    }
                }
            }
            return total;
        }

        double left(String id, String key){
            double total = 0;
            for(int k = playedThisWeek(id) ? 1 : 0; k < projected.size(); k++){
                JsonObject line = projected.get(k).get(id);
                if(line != null){
                    total += FaabDemand.stat(line, key);
                }
            }
            return total;
        }

        int games(String id){
            int games = 0;
            for(int k = playedThisWeek(id) ? 1 : 0; k < points.size(); k++){
                games += points.get(k).containsKey(id) ? 1 : 0;
            }
            return games;
        }

        double pointsLeft(String id){
            double total = 0;
            for(int k = playedThisWeek(id) ? 1 : 0; k < points.size(); k++){
                total += points.get(k).getOrDefault(id, 0.0);
            }
            return total;
        }
    }

    /** One stat's ladder for one man against Sleeper: his stat so far and Sleeper's projected rest, and the lines. */
    record Reading(String stat, double soFar, double left, int games, List<Line> ladder, int leftOut){
        double total(){
            return soFar + left;
        }
    }

    /** Each of his open season events read against Sleeper, by the stat it prices. */
    static List<Reading> readings(Context ctx, String id, String name, List<JsonObject> events){
        List<Reading> out = new ArrayList<>();
        for(JsonObject event : events){
            String stat = statOf(ScreenData.text(event, "title"), name, ctx.season());
            if(stat == null || Boolean.parseBoolean(ScreenData.text(event, "closed")) || !event.has("markets")){
                continue;
            }
            String key = KEYS.get(stat);
            int[] leftOut = {0};
            out.add(new Reading(stat, ctx.soFar(id, key), ctx.left(id, key), ctx.games(id), ladder(event.getAsJsonArray("markets"), leftOut), leftOut[0]));
        }
        return out;
    }

    /**
     * His league points over the weeks left with each priced stat at the market's
     * median, over Sleeper's: the factor for TradeScreens' -PprojectionScale and
     * the explorer's market pricing. NaN when no stat has a median inside its lines.
     */
    static double factor(Context ctx, String id, List<Reading> readings){
        double sleeperLeft = ctx.pointsLeft(id), marketLeft = sleeperLeft;
        boolean any = false;
        for(Reading r : readings){
            double median = median(r.ladder());
            if(!Double.isNaN(median)){
                marketLeft += ctx.weight().getOrDefault(KEYS.get(r.stat()), 0.0) * (median - r.soFar() - r.left());
                any = true;
            }
        }
        return any && sleeperLeft > 0 ? marketLeft / sleeperLeft : Double.NaN;
    }

    static final String EVENTS = "https://gamma-api.polymarket.com/events?closed=false&tag_slug=nfl&limit=100&offset=";

    /** Every open NFL event on Polymarket, a page of 100 at a time, archived raw. */
    static List<JsonObject> nflEvents(HttpClient http, Path dir) throws IOException, InterruptedException {
        List<JsonObject> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for(int offset = 0; offset < 10_000; offset += 100){
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(EVENTS + offset)).header("Accept", "application/json").build(),
                    HttpResponse.BodyHandlers.ofString());
            if(r.statusCode() != 200){
                throw new IOException("polymarket returned " + r.statusCode() + " for events at " + offset);
            }
            Files.writeString(dir.resolve("events-" + offset + ".json"), r.body(), StandardCharsets.UTF_8);
            JsonArray page = JsonParser.parseString(r.body()).getAsJsonArray();
            if(page.isEmpty()){
                break;
            }
            for(JsonElement e : page){
                JsonObject event = e.getAsJsonObject();
                if(seen.add(String.valueOf(ScreenData.text(event, "id")))){
                    out.add(event);
                }
            }
        }
        return out;
    }

    /**
     * THE MARKET'S FACTOR FOR EVERY MAN IT PRICES (2026-11-08, for the explorer's
     * market pricing). Justin: "add betting markets as a source with not measured
     * accuracy". Every open season ladder, matched to Sleeper ids by name (the
     * man's full name, suffixes dropped; a market under another spelling, Cameron
     * for Cam, is missed), read against Sleeper as main reads them. Men with no
     * ladder whose median falls inside its lines get no factor.
     */
    public static Map<String, Double> marketFactors(Set<String> ids) throws IOException, InterruptedException {
        Context ctx = Context.load();
        Path dir = ARCHIVE.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmm")));
        Files.createDirectories(dir);
        String head = "Pro Football: ", middle = " " + seasonLabel(ctx.season()) + " Regular Season ";
        Map<String, List<JsonObject>> byName = new java.util.HashMap<>();
        for(JsonObject event : nflEvents(HttpClient.newHttpClient(), dir)){
            String title = ScreenData.text(event, "title");
            if(title != null && title.startsWith(head) && title.contains(middle)){
                byName.computeIfAbsent(KalshiFair.normal(title.substring(head.length(), title.indexOf(middle))), k -> new ArrayList<>()).add(event);
            }
        }
        Map<String, Double> out = new java.util.HashMap<>();
        for(String id : ids){
            Player p = Player.getPlayerFromSIDV2(id);
            String name = p == null ? null : p.firstName + " " + p.lastName;
            List<JsonObject> events = name == null ? null : byName.get(KalshiFair.normal(name));
            if(events == null){
                continue;
            }
            double f = factor(ctx, id, readings(ctx, id, name, events));
            if(!Double.isNaN(f)){
                out.put(id, f);
            }
        }
        return out;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Context ctx = Context.load();
        Map<String, String> ownerOf = LeagueOwners.today(configuration);
        HttpClient http = HttpClient.newHttpClient();
        Path dir = ARCHIVE.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmm")));
        Files.createDirectories(dir);
        StringBuilder out = new StringBuilder();
        out.append(String.format("SEASON PROPS  %s  (season %s, week %d; Polymarket's season ladders against Sleeper's projections)%n",
                LocalDateTime.now().withNano(0), ctx.season(), ctx.week()));
        out.append("Lines are midpoints with a spread of 20 cents or less, read linearly between lines. 'So far' is Sleeper's stat\n");
        out.append(String.format("rows through week %d%s; 'left' is Sleeper's projected lines summed over the weeks after.%n", ctx.done(),
                ctx.done() < ctx.week() ? ", and week " + ctx.week() + " for a club whose game ended" : ""));

        for(String id : TradeScreens.idsNamed(System.getProperty("players", ""))){
            Player p = Player.getPlayerFromSIDV2(id);
            String name = p == null ? id : p.firstName + " " + p.lastName;
            out.append(String.format("%n%s (%s, %s)%n", name, p == null ? null : p.team, ownerOf.getOrDefault(id, "free")));
            JsonObject found = JsonParser.parseString(search(http, name, dir)).getAsJsonObject();
            JsonArray events = found.has("events") && found.get("events").isJsonArray() ? found.getAsJsonArray("events") : new JsonArray();
            List<JsonObject> list = new ArrayList<>();
            events.forEach(e -> list.add(e.getAsJsonObject()));
            List<Reading> readings = readings(ctx, id, name, list);
            if(readings.isEmpty()){
                out.append("  no open season market on Polymarket\n");
                continue;
            }
            int games = ctx.games(id);
            for(Reading r : readings){
                double total = r.total();
                out.append(String.format("  %s: %.0f so far; Sleeper projects %.1f more over %d games (%.1f a game), %.1f in all%n",
                        r.stat().toLowerCase(), r.soFar(), r.left(), games, games == 0 ? 0 : r.left() / games, total));
                StringBuilder lines = new StringBuilder();
                for(Line l : r.ladder()){
                    lines.append(String.format("  %s %.0f%%", l.strike() % 1 == 0 ? String.format("%.0f", l.strike()) : String.format("%.1f", l.strike()), 100 * l.p()));
                }
                out.append(String.format("    lines:%s%s%n", lines.length() == 0 ? " none quoted tightly enough" : lines,
                        r.leftOut() == 0 ? "" : String.format("  (%d left out: spread over 20c or price at an end)", r.leftOut())));
                List<Line> ladder = r.ladder();
                double pass = above(ladder, total);
                double median = median(ladder);
                out.append(String.format("    market: %s he passes Sleeper's %.1f; ", Double.isNaN(pass)
                        ? (ladder.isEmpty() ? "no reading of whether" : total < ladder.get(0).strike() ? String.format("over %.0f%% that", 100 * ladder.get(0).p())
                        : String.format("under %.0f%% that", 100 * ladder.get(ladder.size() - 1).p())) : String.format("%.0f%% that", 100 * pass), total));
                if(Double.isNaN(median)){
                    out.append("no median inside the lines\n");
                }
                else{
                    double rest = median - r.soFar();
                    out.append(String.format("median %.1f, which leaves %.1f over %d games (%.1f a game), %s Sleeper's%n", median, rest, games,
                            games == 0 ? 0 : rest / games, r.left() > 0 ? String.format("%.0f%% of", 100 * rest / r.left()) : "against none of"));
                }
            }
            double sleeperLeft = ctx.pointsLeft(id), f = factor(ctx, id, readings);
            out.append(Double.isNaN(f)
                    ? String.format("  league points over the weeks left: Sleeper %.1f; no factor (%s)%n", sleeperLeft,
                    sleeperLeft > 0 ? "no priced stat has a median inside its lines" : "Sleeper projects him for nothing")
                    : String.format("  league points over the weeks left: Sleeper %.1f; with each priced stat at the market's median %.1f; factor %.2f%n",
                    sleeperLeft, sleeperLeft * f, f));
        }
        System.out.print(out);
        Path report = Path.of("data", "season-props-" + java.time.LocalDate.now() + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }
}
