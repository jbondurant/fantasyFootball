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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KALSHI'S NFL MARKETS, READ FROM ITS PUBLIC MARKET-DATA API.
 *
 * Kalshi is an exchange (CFTC-regulated) that lists NFL game and player-prop
 * contracts; its market data - every open contract with its best bid and
 * ask and their sizes - is public and needs no account
 * (api.elections.kalshi.com/trade-api/v2/markets). Nothing here trades.
 *
 * One player's stat is priced three ways, which is what makes the exchange
 * worth reading (KalshiArb):
 *
 *   THRESHOLD   "Cole Kmet: 50+ receiving yards": pays $1 if the stat clears
 *               the strike (strike_type greater, floor_strike 49.5).
 *   LADDER      pays a fixed amount per unit ($0.0025 a receiving yard), up to
 *               a cap: its price is the market's expected stat.
 *   ESCALATOR   pays a convex schedule in 10-yard steps ((y/200)^3, capped).
 *
 * Every read is kept, raw, under data/kalshi/&lt;timestamp&gt;/ (gitignored): the
 * archive is what later measures whether the prices were right, and whether
 * a gap seen at one moment was still there when it could have been traded.
 */
public class KalshiMarkets {

    static final String BASE = "https://api.elections.kalshi.com/trade-api/v2/markets";
    static final Path ARCHIVE = Path.of("data", "kalshi");

    /** The per-game NFL series: game lines, and player props priced as thresholds, ladders and escalators. */
    static final List<String> SERIES = List.of(
            "KXNFLGAME", "KXNFLSPREAD", "KXNFLTOTAL", "KXNFLTEAMTOTAL", "KXNFLTEAMTD", "KXNFLTOTALTD", "KXNFLGAMETD",
            "KXNFLPASSYDS", "KXNFLPASSTDS", "KXNFLPASSCOMP", "KXNFLPASSATT", "KXNFLPASSINT",
            "KXNFLRSHYDS", "KXNFLRECYDS", "KXNFLREC", "KXNFLRRYDS", "KXNFLANYTD", "KXNFLTD", "KXNFL2TD",
            "KXNFLLADDERRECYDS", "KXNFLLADDERRSHYDS", "KXNFLLADDERREC",
            "KXNFLESCALATORRECYDS", "KXNFLESCALATORRSHYDS", "KXNFLESCALATORREC");

    enum Kind { THRESHOLD, LADDER, ESCALATOR, OTHER }

    /**
     * One contract. {@code stat} is the statistic in the series' own words
     * ("receiving yards"); {@code strike} for a threshold is the smallest
     * whole number that pays (50 for floor_strike 49.5); {@code perUnit} and
     * {@code cap} describe a ladder; {@code schedule} an escalator's payout at
     * each step. Prices are dollars per contract; null when no one is quoting.
     */
    record Contract(String ticker, String series, String event, Kind kind, String player, String playerName, String team,
                    String stat, Integer strike, Double perUnit, Double cap, double[] schedule, int step,
                    Double yesBid, Double yesAsk, Double noBid, Double noAsk, double yesBidSize, double yesAskSize,
                    String closeTime) {}

    static String statOf(String series){
        if(series.equals("KXNFLTD")){
            return "touchdowns";
        }
        if(series.equals("KXNFLPASSINT")){
            return "interceptions";
        }
        if(series.endsWith("RECYDS")){
            return "receiving yards";
        }
        if(series.endsWith("RSHYDS")){
            return "rushing yards";
        }
        if(series.endsWith("PASSYDS")){
            return "passing yards";
        }
        if(series.endsWith("RRYDS")){
            return "rush + rec yards";
        }
        if(series.endsWith("REC")){
            return "receptions";
        }
        if(series.endsWith("PASSTDS")){
            return "passing TDs";
        }
        if(series.endsWith("PASSCOMP")){
            return "completions";
        }
        if(series.endsWith("PASSATT")){
            return "pass attempts";
        }
        return series;
    }

    static Double dollars(JsonObject o, String key){
        JsonElement e = o.get(key);
        if(e == null || e.isJsonNull()){
            return null;
        }
        try {
            return Double.parseDouble(e.getAsString());
        }
        catch(NumberFormatException notANumber){
            return null;
        }
    }

    static final Pattern ESCALATOR_STEP = Pattern.compile("(\\d+)\\s*(?:–|-|or more)[^$]*\\$([0-9]+(?:\\.[0-9]+)?)");

    /** Parse one market record of the API. */
    static Contract parse(JsonObject m, String series){
        JsonObject custom = m.has("custom_strike") && m.get("custom_strike").isJsonObject() ? m.getAsJsonObject("custom_strike") : new JsonObject();
        String strikeType = ScreenData.text(m, "strike_type");
        Kind kind = Kind.OTHER;
        Integer strike = null;
        Double perUnit = null;
        Double cap = null;
        double[] schedule = null;
        int step = 0;
        if("greater".equals(strikeType) && m.has("floor_strike") && !m.get("floor_strike").isJsonNull()){
            kind = Kind.THRESHOLD;
            strike = (int) Math.floor(m.get("floor_strike").getAsDouble()) + 1;
        }
        else if(custom.has("Payout formula") && custom.has("scalar_step")){
            // an escalator's "Payout Per Unit" is its whole schedule, so it is recognised first
            kind = Kind.ESCALATOR;
            step = Integer.parseInt(ScreenData.text(custom, "scalar_step"));
            cap = Double.parseDouble(ScreenData.text(custom, "scalar_cap"));
            schedule = escalatorSchedule(ScreenData.text(custom, "Payout Per Unit"), step, cap);
        }
        else if(custom.has("Payout Per Unit") && ScreenData.text(custom, "Payout Per Unit").matches("\\$?\\s*[0-9.]+\\s*")){
            kind = Kind.LADDER;
            perUnit = Double.parseDouble(ScreenData.text(custom, "Payout Per Unit").replace("$", "").trim());
            cap = custom.has("scalar_cap") ? Double.parseDouble(ScreenData.text(custom, "scalar_cap")) : null;
        }
        return new Contract(ScreenData.text(m, "ticker"), series, ScreenData.text(m, "event_ticker"), kind,
                ScreenData.text(custom, "football_player"), ScreenData.text(m, "yes_sub_title"), ScreenData.text(custom, "football_team"),
                statOf(series), strike, perUnit, cap, schedule, step,
                dollars(m, "yes_bid_dollars"), dollars(m, "yes_ask_dollars"), dollars(m, "no_bid_dollars"), dollars(m, "no_ask_dollars"),
                size(m, "yes_bid_size_fp"), size(m, "yes_ask_size_fp"), ScreenData.text(m, "close_time"));
    }

    static double size(JsonObject m, String key){
        Double d = dollars(m, key);
        return d == null ? 0 : d;
    }

    /**
     * An escalator's payout by step, from its schedule text ("0–9 yards,
     * $0.0000; 10–19, $0.0001; ...; and 200 or more, $1.0000"): element i is
     * the payout for a stat in [i*step, (i+1)*step), the last for the cap and
     * above. Null if the text does not read as a whole schedule.
     */
    static double[] escalatorSchedule(String text, int step, double cap){
        if(text == null || step <= 0){
            return null;
        }
        int n = (int) (cap / step) + 1;
        double[] out = new double[n];
        boolean[] seen = new boolean[n];
        Matcher m = ESCALATOR_STEP.matcher(text);
        while(m.find()){
            int from = Integer.parseInt(m.group(1));
            int i = from / step;
            if(i < n){
                out[i] = Double.parseDouble(m.group(2));
                seen[i] = true;
            }
        }
        for(boolean s : seen){
            if(!s){
                return null;
            }
        }
        return out;
    }

    /** Every open market of one series, following the API's cursor. */
    static List<JsonObject> fetchSeries(HttpClient http, String series) throws IOException, InterruptedException {
        List<JsonObject> out = new ArrayList<>();
        String cursor = null;
        do {
            String url = BASE + "?series_ticker=" + series + "&status=open&limit=1000" + (cursor == null ? "" : "&cursor=" + cursor);
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").build(),
                    HttpResponse.BodyHandlers.ofString());
            if(r.statusCode() != 200){
                throw new IOException("kalshi returned " + r.statusCode() + " for " + series);
            }
            JsonObject body = JsonParser.parseString(r.body()).getAsJsonObject();
            JsonArray markets = body.has("markets") && body.get("markets").isJsonArray() ? body.getAsJsonArray("markets") : new JsonArray();
            for(JsonElement e : markets){
                out.add(e.getAsJsonObject());
            }
            cursor = body.has("cursor") && !body.get("cursor").isJsonNull() && !body.get("cursor").getAsString().isEmpty()
                    && !markets.isEmpty() ? body.get("cursor").getAsString() : null;
        } while(cursor != null);
        return out;
    }

    /** A fresh read of every series, archived raw; series -> its contracts. */
    static Map<String, List<Contract>> snapshot() throws IOException, InterruptedException {
        HttpClient http = HttpClient.newHttpClient();
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmm"));
        Path dir = ARCHIVE.resolve(stamp);
        Files.createDirectories(dir);
        Map<String, List<Contract>> out = new TreeMap<>();
        for(String series : SERIES){
            List<JsonObject> raw = fetchSeries(http, series);
            JsonArray keep = new JsonArray();
            List<Contract> contracts = new ArrayList<>();
            for(JsonObject m : raw){
                keep.add(m);
                contracts.add(parse(m, series));
            }
            Files.writeString(dir.resolve(series + ".json"), keep.toString(), StandardCharsets.UTF_8);
            out.put(series, contracts);
        }
        return out;
    }

    /** The most recent archived read, parsed, without fetching. */
    static Map<String, List<Contract>> latestArchived() throws IOException {
        Path latest = null;
        if(Files.isDirectory(ARCHIVE)){
            try(var dirs = Files.list(ARCHIVE)){
                latest = dirs.filter(Files::isDirectory).max(Path::compareTo).orElse(null);
            }
        }
        if(latest == null){
            throw new IllegalStateException("no Kalshi snapshot under " + ARCHIVE);
        }
        Map<String, List<Contract>> out = new TreeMap<>();
        for(String series : SERIES){
            Path f = latest.resolve(series + ".json");
            List<Contract> contracts = new ArrayList<>();
            if(Files.exists(f)){
                for(JsonElement e : JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonArray()){
                    contracts.add(parse(e.getAsJsonObject(), series));
                }
            }
            out.put(series, contracts);
        }
        return out;
    }
}
