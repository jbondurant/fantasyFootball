import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * KALSHI'S SETTLED NFL PROP MARKETS, EACH PRICED AN HOUR BEFORE ITS KICKOFF.
 *
 * Justin, 2026-09-26: can the efficient market be used to beat Sleeper -
 * or are Sleeper's projections better than the betting market's for a
 * player's fantasy points? A market price is a projection built from
 * everyone's information, and Kalshi's history is public: every settled
 * market (the whole 2025 season sits behind /historical) with hour candles of
 * its best bid and ask. This reads, for the 2025 regular season:
 *
 *   KXNFLPASSYDS, KXNFLPASSTDS, KXNFLRSHYDS, KXNFLRECYDS, KXNFLREC  thresholds
 *   KXNFLANYTD                                                     anytime touchdown
 *
 * and prices each market at the close of the last hour candle ending at
 * least an hour before its game's kickoff (nflverse's schedule, Eastern).
 * Sleeper's stored weekly projection is also a late read, taken after the
 * inactives, so the two are compared at about the same moment.
 *
 * Everything read is cached forever under data/kalshi-history/ (gitignored):
 * a settled market's history cannot change. The read is paced under
 * Kalshi's published public limit (about 20 reads a second).
 *
 *     ./gradlew run -Pmain=KalshiHistory       (fetches what is not cached; prints coverage)
 */
public class KalshiHistory {

    static final Path HOME = Path.of("data", "kalshi-history");
    static final String API = "https://api.elections.kalshi.com/trade-api/v2/historical/markets";
    static final List<String> SERIES = List.of("KXNFLPASSYDS", "KXNFLPASSTDS", "KXNFLRSHYDS", "KXNFLRECYDS", "KXNFLREC", "KXNFLANYTD");
    static final ZoneId EASTERN = ZoneId.of("America/New_York");

    /** One settled prop market with its pre-game quote. strike is the smallest whole stat that paid YES (1 for anytime TD). */
    record Priced(String ticker, String series, String stat, String playerName, String game, LocalDate date, String kickoffEt,
                  int strike, String result, Double bid, Double ask) {
        Double mid(){
            return bid == null || ask == null ? null : (bid + ask) / 2;
        }
    }

    static String stat(String series){
        return series.equals("KXNFLANYTD") ? "touchdowns" : KalshiMarkets.statOf(series);
    }

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static long lastCall = 0;

    /** One slot per 70 ms across every thread: at most ~14 calls a second, under the public limit. */
    static synchronized void pace() throws InterruptedException {
        long wait = 70 - (System.currentTimeMillis() - lastCall);
        if(wait > 0){
            Thread.sleep(wait);
        }
        lastCall = System.currentTimeMillis();
    }

    /** A GET, paced, retried on 429 and server errors. */
    static String get(String url) throws IOException, InterruptedException {
        for(int attempt = 0; attempt < 6; attempt++){
            pace();
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").build(),
                    HttpResponse.BodyHandlers.ofString());
            if(r.statusCode() == 200){
                return r.body();
            }
            if(r.statusCode() == 429 || r.statusCode() >= 500){
                Thread.sleep(1000L << attempt);
                continue;
            }
            throw new IOException("kalshi " + r.statusCode() + " for " + url);
        }
        throw new IOException("kalshi kept refusing " + url);
    }

    /** Every settled market of a series, cached as one file. */
    static JsonArray markets(String series) throws IOException, InterruptedException {
        Path cache = HOME.resolve("markets").resolve(series + ".json");
        if(Files.exists(cache)){
            return JsonParser.parseString(Files.readString(cache, StandardCharsets.UTF_8)).getAsJsonArray();
        }
        JsonArray all = new JsonArray();
        String cursor = null;
        do {
            JsonObject body = JsonParser.parseString(get(API + "?series_ticker=" + series + "&limit=1000"
                    + (cursor == null ? "" : "&cursor=" + cursor))).getAsJsonObject();
            JsonArray page = body.has("markets") ? body.getAsJsonArray("markets") : new JsonArray();
            all.addAll(page);
            cursor = page.isEmpty() || !body.has("cursor") || body.get("cursor").isJsonNull() || body.get("cursor").getAsString().isEmpty()
                    ? null : body.get("cursor").getAsString();
        } while(cursor != null);
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, all.toString(), StandardCharsets.UTF_8);
        return all;
    }

    /** The date an event ticker names: KXNFLRECYDS-25OCT05MINCLE -> 2025-10-05. */
    static LocalDate dateOf(String eventTicker){
        String code = eventTicker.substring(eventTicker.indexOf('-') + 1, eventTicker.indexOf('-') + 8);
        return LocalDate.parse("20" + code.substring(0, 2) + "-" + code.substring(2, 5).charAt(0)
                + code.substring(3, 5).toLowerCase() + "-" + code.substring(5, 7), DateTimeFormatter.ofPattern("yyyy-MMM-dd", Locale.ENGLISH));
    }

    /** The regular-season game an event names (date plus both team codes in the suffix), or null. */
    static NflverseGames.Game game(String eventTicker, LocalDate date){
        String suffix = eventTicker.substring(eventTicker.indexOf('-') + 8);
        for(NflverseGames.Game g : NflverseGames.games()){
            if(date.toString().equals(g.gameday()) && suffix.contains(KalshiSettle.kalshiCode(g.home()))
                    && suffix.contains(KalshiSettle.kalshiCode(g.away()))){
                return g;
            }
        }
        return null;
    }

    /** Epoch seconds of a kickoff given in Eastern time. */
    static long kickoffEpoch(NflverseGames.Game g){
        return LocalDateTime.parse(g.gameday() + "T" + (g.gametime() == null ? "13:00" : g.gametime())).atZone(EASTERN).toEpochSecond();
    }

    static Path candleFile(String ticker){
        return HOME.resolve("candles").resolve(ticker + ".json");
    }

    /** A cached candle file that parses; a half-written one (a run killed mid-write) counts as missing. */
    static String cachedCandles(String ticker){
        Path cache = candleFile(ticker);
        if(!Files.exists(cache)){
            return null;
        }
        try {
            String body = Files.readString(cache, StandardCharsets.UTF_8);
            JsonParser.parseString(body).getAsJsonObject();
            return body;
        }
        catch(IOException | RuntimeException unreadable){
            return null;
        }
    }

    /** Fetch a market's hour candles for the twelve hours before kickoff, written atomically. */
    static String fetchCandles(String ticker, long kickoff) throws IOException, InterruptedException {
        String body = get(API + "/" + ticker + "/candlesticks?start_ts=" + (kickoff - 12 * 3600) + "&end_ts=" + kickoff + "&period_interval=60");
        Path cache = candleFile(ticker);
        Files.createDirectories(cache.getParent());
        Path tmp = cache.resolveSibling(ticker + ".json.tmp");
        Files.writeString(tmp, body, StandardCharsets.UTF_8);
        Files.move(tmp, cache, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return body;
    }

    /** Fetch every uncached market's candles, eight in flight, under the shared pace. */
    static void prefetch(Map<String, Long> kickoffByTicker) throws InterruptedException {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
        for(Map.Entry<String, Long> e : kickoffByTicker.entrySet()){
            if(cachedCandles(e.getKey()) != null){
                continue;
            }
            pool.submit(() -> {
                try {
                    fetchCandles(e.getKey(), e.getValue());
                }
                catch(Exception problem){
                    failed.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(4, java.util.concurrent.TimeUnit.HOURS);
        if(failed.get() > 0){
            System.out.println(failed.get() + " candle reads failed; a rerun fetches them");
        }
    }

    /** The {bid, ask} closes of the last hour candle ending at least an hour before kickoff; cached per market. */
    static double[] preGame(String ticker, long kickoff) throws IOException, InterruptedException {
        String body = cachedCandles(ticker);
        if(body == null){
            body = fetchCandles(ticker, kickoff);
        }
        JsonObject j = JsonParser.parseString(body).getAsJsonObject();
        JsonArray candles = j.has("candlesticks") && j.get("candlesticks").isJsonArray() ? j.getAsJsonArray("candlesticks") : new JsonArray();
        double[] out = null;
        for(JsonElement e : candles){
            JsonObject c = e.getAsJsonObject();
            if(c.get("end_period_ts").getAsLong() > kickoff - 3600){
                continue;
            }
            Double bid = close(c, "yes_bid");
            Double ask = close(c, "yes_ask");
            if(bid != null && ask != null){
                out = new double[]{bid, ask};
            }
        }
        return out;
    }

    static Double close(JsonObject candle, String side){
        if(!candle.has(side) || !candle.get(side).isJsonObject()){
            return null;
        }
        return KalshiMarkets.dollars(candle.getAsJsonObject(side), "close");
    }

    /** Every 2025 regular-season prop market of the series, priced before kickoff (fetching what is not cached). */
    static List<Priced> season2025(StringBuilder log) throws IOException, InterruptedException {
        List<Priced> out = new ArrayList<>();
        for(String series : SERIES){
            JsonArray markets = markets(series);
            Map<String, Long> toFetch = new LinkedHashMap<>();
            for(JsonElement e : markets){
                JsonObject m = e.getAsJsonObject();
                String event = ScreenData.text(m, "event_ticker");
                try {
                    LocalDate date = dateOf(event);
                    NflverseGames.Game g = date.isBefore(LocalDate.of(2025, 8, 30)) || date.isAfter(LocalDate.of(2026, 1, 5)) ? null : game(event, date);
                    if(g != null && "2025".equals(g.season())){
                        toFetch.put(ScreenData.text(m, "ticker"), kickoffEpoch(g));
                    }
                }
                catch(RuntimeException unreadable){
                    // not a dated game event
                }
            }
            prefetch(toFetch);
            int regular = 0;
            int priced = 0;
            int noGame = 0;
            for(JsonElement e : markets){
                JsonObject m = e.getAsJsonObject();
                String event = ScreenData.text(m, "event_ticker");
                LocalDate date;
                try {
                    date = dateOf(event);
                }
                catch(RuntimeException unreadable){
                    continue;
                }
                if(date.isBefore(LocalDate.of(2025, 8, 30)) || date.isAfter(LocalDate.of(2026, 1, 5))){
                    continue;           // the 2025 regular season only
                }
                NflverseGames.Game g = game(event, date);
                if(g == null || !"2025".equals(g.season())){
                    noGame++;
                    continue;
                }
                regular++;
                int strike = series.equals("KXNFLANYTD") ? 1
                        : m.has("floor_strike") && !m.get("floor_strike").isJsonNull() ? (int) Math.floor(m.get("floor_strike").getAsDouble()) + 1 : -1;
                if(strike < 0){
                    continue;
                }
                double[] q = preGame(ScreenData.text(m, "ticker"), kickoffEpoch(g));
                // the YES sub-title is the clean name ("Romeo Doubs: 50+", or just the name for anytime TD); 2025's
                // titles read "Romeo Doubs records 50+ receiving yards" and cannot be cut at a colon
                String sub = ScreenData.text(m, "yes_sub_title");
                String name = sub == null ? "" : sub.contains(":") ? sub.substring(0, sub.indexOf(':')).trim() : sub.trim();
                out.add(new Priced(ScreenData.text(m, "ticker"), series, stat(series), name, g.gameId(), date,
                        g.gameday() + "T" + g.gametime(), strike, ScreenData.text(m, "result"), q == null ? null : q[0], q == null ? null : q[1]));
                priced += q == null ? 0 : 1;
            }
            log.append(String.format("%-14s %6d settled, %6d in the 2025 regular season, %6d with a pre-game quote, %d not matched to a game%n",
                    series, markets.size(), regular, priced, noGame));
        }
        return out;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        StringBuilder log = new StringBuilder();
        List<Priced> all = season2025(log);
        System.out.print(log);
        Map<String, Integer> byWeek = new java.util.TreeMap<>();
        for(Priced p : all){
            if(p.mid() != null){
                byWeek.merge(p.date().toString().substring(0, 7), 1, Integer::sum);
            }
        }
        System.out.println("quoted markets by month: " + byWeek);
    }
}
