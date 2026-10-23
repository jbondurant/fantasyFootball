import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OTHER LEAGUES' TRADES: enough accepted trades to measure, week by week, how
 * much draft position still counts against this season's production.
 *
 * Justin, 2026-10-22, on the draft weight in TradeScreens: "i don't think my
 * league has enough trades to make this test worth it. Can you make a heuristic
 * based on other league trades perhaps?" and then, of a rule of thumb: "no. try
 * to make a curve that's not so wishy washy. I don't mind downloading data".
 * This league has about sixty accepted trade sides over five seasons - a
 * handful per week of the season, too few to say whether draft position still
 * counts for 40% after one game or 10%.
 *
 * Sleeper's public API answers, without a login, which leagues a user is in
 * for a season, who is in a league, and every transaction a league made. So
 * the harvest walks outward from the managers of this league: their other
 * leagues, those leagues' members, their leagues, and so on, until it holds
 * enough leagues like this one. "Like this one" ({@link #likeThisOne}): a
 * finished redraft or keeper season (dynasty managers price years, not
 * weeks), 10 to 14 teams, half or full PPR, one quarterback, no superflex, no
 * defensive players, no best ball, and starting two backs, two or three
 * receivers, a tight end and one or two flexes.
 *
 * What it keeps of each league is its completed trades from weeks 1-14 (the
 * trade deadline falls before 14 almost everywhere): no waivers, no free-agent
 * moves. DraftWeightCurve does the pricing; this only fetches.
 *
 * Three rules keep the sample from being one person's: at most
 * {@link #PER_USER} leagues from any manager's list a season, no league from a
 * contest series ({@link #CONTEST}), and an equal share for each season.
 *
 * Etiquette: Sleeper asks callers to stay under 1000 calls a minute. This
 * makes at most one call every {@link #GAP_MS} ms (about 550 a minute),
 * retries a failed call three times with a growing wait, and caches every
 * answer under data/other-leagues/ (gitignored), so a rerun resumes rather
 * than refetching and a larger -PotherLeagues only fetches the difference.
 *
 *   ./gradlew run -Pmain=OtherLeagues [-PotherLeagues=1500] [-PotherSeasons=2021,2022,2023,2024,2025] [-PotherUsers=5000]
 */
public class OtherLeagues {

    static final Path DIR = Path.of("data", "other-leagues");
    static final Path MANIFEST = DIR.resolve("leagues.tsv");
    static final String API = "https://api.sleeper.app/v1/";
    static final long GAP_MS = 110;
    static final int LAST_WEEK = 14;

    /** One league the harvest keeps. */
    record League(String id, String season, int teams, double rec, int type, String name) {}

    /** At most this many leagues from any one manager's list in a season, so one busy account cannot be the sample. */
    static final int PER_USER = 3;

    /**
     * A name one account holds this many times in one season is a contest
     * series, not a league of people who know each other: on 2026-10-22 the
     * first harvest kept 1,125 of 1,200 leagues from 2021, nearly all
     * "Megalabowl" - one account sat in 1,560 of them - and only 34 managers'
     * lists had been read.
     */
    static final int CONTEST = 10;

    /** The names that are contest series in a season ({@link #CONTEST}), as season|name. */
    static Set<String> contests(String season, JsonArray leagues){
        Map<String, Integer> count = new java.util.HashMap<>();
        for(JsonElement e : leagues){
            count.merge(EraBoards.text(e.getAsJsonObject(), "name"), 1, Integer::sum);
        }
        Set<String> out = new LinkedHashSet<>();
        count.forEach((name, n) -> {
            if(n >= CONTEST){
                out.add(season + "|" + name);
            }
        });
        return out;
    }

    private static long lastCall;

    /** One network call, paced and retried; throws only after the last retry fails. */
    static synchronized String fetch(String url){
        RuntimeException last = null;
        for(int attempt = 0; attempt < 4; attempt++){
            long wait = lastCall + GAP_MS * (attempt == 0 ? 1 : 10L * attempt * attempt) - System.currentTimeMillis();
            if(wait > 0){
                try {
                    Thread.sleep(wait);
                }
                catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            lastCall = System.currentTimeMillis();
            try {
                return WebUrlUtility.urlToString(url);
            }
            catch(RuntimeException failed){
                last = failed;
            }
        }
        throw last;
    }

    /** A cached answer, fetched once. An empty answer from Sleeper ("null", "[]") is an answer and is kept. */
    static String cached(Path file, String url) throws IOException {
        if(Files.exists(file)){
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        String body;
        try {
            body = fetch(url);
        }
        catch(RuntimeException empty){
            if(empty.getMessage() != null && empty.getMessage().startsWith("empty response")){
                body = "[]";
            }
            else{
                throw empty;
            }
        }
        Files.createDirectories(file.getParent());
        Path part = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(part, body, StandardCharsets.UTF_8);
        Files.move(part, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return body;
    }

    static int count(JsonArray positions, String... slots){
        Set<String> wanted = Set.of(slots);
        int n = 0;
        for(JsonElement e : positions){
            n += !e.isJsonNull() && wanted.contains(e.getAsString()) ? 1 : 0;
        }
        return n;
    }

    static double number(JsonObject object, String key, double otherwise){
        JsonElement e = object == null ? null : object.get(key);
        return e == null || e.isJsonNull() ? otherwise : e.getAsDouble();
    }

    /** A finished redraft or keeper season shaped like this league (see the class comment). */
    static boolean likeThisOne(JsonObject league){
        if(!"complete".equals(EraBoards.text(league, "status")) || !league.has("roster_positions")
                || !league.get("roster_positions").isJsonArray()){
            return false;
        }
        JsonObject settings = league.has("settings") && league.get("settings").isJsonObject()
                ? league.getAsJsonObject("settings") : new JsonObject();
        JsonObject scoring = league.has("scoring_settings") && league.get("scoring_settings").isJsonObject()
                ? league.getAsJsonObject("scoring_settings") : new JsonObject();
        int type = (int) number(settings, "type", 0);
        int teams = (int) number(league, "total_rosters", 0);
        double rec = number(scoring, "rec", 0);
        JsonArray positions = league.getAsJsonArray("roster_positions");
        int flex = count(positions, "FLEX", "WRRB_FLEX", "REC_FLEX");
        int wr = count(positions, "WR");
        return type <= 1 && number(settings, "best_ball", 0) == 0
                && teams >= 10 && teams <= 14 && rec >= 0.5 && rec <= 1.0
                && count(positions, "QB") == 1 && count(positions, "SUPER_FLEX") == 0
                && count(positions, "DL", "LB", "DB", "IDP_FLEX", "DE", "DT", "CB", "S") == 0
                && count(positions, "RB") == 2 && wr >= 2 && wr <= 3 && count(positions, "TE") == 1
                && flex >= 1 && flex <= 2;
    }

    /** The completed trades of one league, weeks 1 to {@link #LAST_WEEK}, cached as one file. */
    static JsonArray trades(League league) throws IOException {
        Path file = DIR.resolve("trades").resolve(league.season() + "-" + league.id() + ".json");
        if(Files.exists(file)){
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
        }
        JsonArray kept = new JsonArray();
        for(int week = 1; week <= LAST_WEEK; week++){
            String body;
            try {
                body = fetch(API + "league/" + league.id() + "/transactions/" + week);
            }
            catch(RuntimeException empty){
                if(empty.getMessage() != null && empty.getMessage().startsWith("empty response")){
                    continue;
                }
                throw empty;
            }
            JsonElement parsed = JsonParser.parseString(body);
            if(!parsed.isJsonArray()){
                continue;
            }
            for(JsonElement e : parsed.getAsJsonArray()){
                JsonObject row = e.getAsJsonObject();
                if("trade".equals(EraBoards.text(row, "type")) && "complete".equals(EraBoards.text(row, "status"))){
                    JsonObject slim = new JsonObject();
                    for(String key : List.of("transaction_id", "leg", "created", "status_updated", "roster_ids",
                            "adds", "drops", "draft_picks", "waiver_budget")){
                        if(row.has(key)){
                            slim.add(key, row.get(key));
                        }
                    }
                    kept.add(slim);
                }
            }
        }
        Files.createDirectories(file.getParent());
        Path part = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(part, kept.toString(), StandardCharsets.UTF_8);
        Files.move(part, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return kept;
    }

    /** Every league harvested so far, from the manifest. */
    public static List<League> manifest() throws IOException {
        List<League> out = new ArrayList<>();
        if(!Files.exists(MANIFEST)){
            return out;
        }
        for(String line : Files.readAllLines(MANIFEST, StandardCharsets.UTF_8)){
            String[] c = line.split("\t");
            if(c.length >= 5 && !c[0].equals("league")){
                out.add(new League(c[0], c[1], Integer.parseInt(c[2]), Double.parseDouble(c[3]), Integer.parseInt(c[4]),
                        c.length > 5 ? c[5] : ""));
            }
        }
        return out;
    }

    /** One harvested league's trades, read from the cache (never fetched here). */
    public static JsonArray cachedTrades(League league) throws IOException {
        Path file = DIR.resolve("trades").resolve(league.season() + "-" + league.id() + ".json");
        return Files.exists(file) ? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()
                : new JsonArray();
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int target = Integer.getInteger("otherLeagues", 1500);
        List<String> seasons = List.of(System.getProperty("otherSeasons", "2021,2022,2023,2024,2025").split(","));

        // seeds: everyone who has managed in this league
        Deque<String> users = new ArrayDeque<>();
        Set<String> seenUsers = new LinkedHashSet<>();
        Deque<String> leaguesToOpen = new ArrayDeque<>();
        Set<String> opened = new LinkedHashSet<>();
        Set<String> ours = new LinkedHashSet<>();     // this league's own seasons: the thing being compared against, not the sample
        for(LeagueTransactions.Year year : LeagueTransactions.chain(configuration.getLeagueID())){
            leaguesToOpen.add(year.leagueID());
            ours.add(year.leagueID());
        }
        // The manifest is rebuilt by the same walk every run, over the cached answers, so the rules below
        // always apply to all of it; only managers and leagues not yet cached cost a call.
        Map<String, League> kept = new LinkedHashMap<>();
        int perSeason = (target + seasons.size() - 1) / seasons.size();
        Map<String, Integer> bySeason = new java.util.HashMap<>();
        Set<String> contests = new LinkedHashSet<>();
        if(Files.isDirectory(DIR.resolve("users"))){
            try(var files = Files.list(DIR.resolve("users"))){
                for(Path f : files.filter(f -> f.toString().endsWith(".json")).toList()){
                    String name = f.getFileName().toString();
                    JsonElement list = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
                    if(list.isJsonArray()){
                        contests.addAll(contests(name.substring(name.lastIndexOf('-') + 1, name.length() - 5), list.getAsJsonArray()));
                    }
                }
            }
        }
        int calls = 0;
        int maxUsers = Integer.getInteger("otherUsers", 5000);
        while(kept.size() < target && calls / seasons.size() < maxUsers){
            if(users.isEmpty()){
                if(leaguesToOpen.isEmpty()){
                    break;
                }
                String id = leaguesToOpen.poll();
                if(!opened.add(id)){
                    continue;
                }
                JsonElement members;
                try {
                    members = JsonParser.parseString(cached(DIR.resolve("members").resolve(id + ".json"),
                            API + "league/" + id + "/users"));
                }
                catch(RuntimeException failed){
                    System.out.println("skipped members of " + id + ": " + failed.getMessage());
                    continue;
                }
                if(members.isJsonArray()){
                    for(JsonElement m : members.getAsJsonArray()){
                        String uid = EraBoards.text(m.getAsJsonObject(), "user_id");
                        if(!uid.isBlank() && seenUsers.add(uid)){
                            users.add(uid);
                        }
                    }
                }
                continue;
            }
            String uid = users.poll();
            for(String season : seasons){
                JsonElement leagues;
                try {
                    leagues = JsonParser.parseString(cached(DIR.resolve("users").resolve(uid + "-" + season + ".json"),
                            API + "user/" + uid + "/leagues/nfl/" + season));
                }
                catch(RuntimeException failed){
                    System.out.println("skipped user " + uid + " (" + season + "): " + failed.getMessage());
                    continue;
                }
                calls++;
                if(!leagues.isJsonArray()){
                    continue;
                }
                contests.addAll(contests(season, leagues.getAsJsonArray()));
                int fromHim = 0;
                for(JsonElement e : leagues.getAsJsonArray()){
                    JsonObject league = e.getAsJsonObject();
                    String id = EraBoards.text(league, "league_id");
                    String name = EraBoards.text(league, "name").replace('\t', ' ').replace('\n', ' ');
                    if(id.isBlank() || contests.contains(season + "|" + name)){
                        continue;
                    }
                    leaguesToOpen.add(id);
                    if(likeThisOne(league) && !ours.contains(id) && !kept.containsKey(season + "-" + id)
                            && fromHim < PER_USER && bySeason.getOrDefault(season, 0) < perSeason){
                        League l = new League(id, season, (int) number(league, "total_rosters", 0),
                                number(league.getAsJsonObject("scoring_settings"), "rec", 0),
                                (int) number(league.getAsJsonObject("settings"), "type", 0), name);
                        try {
                            trades(l);
                        }
                        catch(RuntimeException failed){
                            System.out.println("skipped league " + id + " (" + season + "): " + failed.getMessage());
                            continue;
                        }
                        kept.put(season + "-" + id, l);
                        fromHim++;
                        bySeason.merge(season, 1, Integer::sum);
                        if(kept.size() % 50 == 0){
                            System.out.printf("%d leagues kept, %d users read%n", kept.size(), calls / seasons.size());
                            write(kept);
                        }
                    }
                }
            }
        }
        // a contest flagged after one of its leagues was kept is still a contest
        kept.values().removeIf(l -> contests.contains(l.season() + "|" + l.name()));
        write(kept);
        int trades = 0;
        for(League l : kept.values()){
            trades += cachedTrades(l).size();
        }
        System.out.printf("%d leagues like this one (%s), %d completed trades, weeks 1-%d; %d managers' lists read;%n"
                        + "%d contest series left out; manifest %s%n",
                kept.size(), bySeason, trades, LAST_WEEK, calls / seasons.size(), contests.size(), MANIFEST);
    }

    static void write(Map<String, League> kept) throws IOException {
        StringBuilder out = new StringBuilder("league\tseason\tteams\trec\ttype\tname\n");
        for(League l : kept.values()){
            out.append(String.format("%s\t%s\t%d\t%.1f\t%d\t%s%n", l.id(), l.season(), l.teams(), l.rec(), l.type(), l.name()));
        }
        Files.createDirectories(DIR);
        Files.writeString(MANIFEST, out.toString(), StandardCharsets.UTF_8);
    }
}
