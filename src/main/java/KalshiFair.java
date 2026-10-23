import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * THE PROP MODEL AGAINST KALSHI'S PRICES, WRITTEN DOWN BEFORE THE GAMES.
 *
 * KalshiArb found the exchange's prop book coherent with itself - whoever
 * makes it keeps the thresholds, ladders and escalators in line. The other
 * half of Justin's idea is coherence with the outside: PropModel prices every
 * contract from Sleeper's projection and the 2018-2025 record of men projected
 * like him (calibrated to within 2 points out of season), and here each
 * contract's model price is set beside the market's.
 *
 * A gap is not an edge. The market sees everything Sleeper does and more, so
 * most gaps are the model's errors; the only test is what happens. So every
 * comparison is written to a ledger (data/kalshi-ledger/&lt;timestamp&gt;.csv) with
 * the time it was made, and committed before kickoff; KalshiSettle scores
 * the ledger after the games - the model's Brier score against the market's,
 * and what taking every gap bigger than the spread and fees would have made.
 *
 * Players Sleeper lists as out, doubtful or on IR are left out (Kalshi's rules
 * cover an active man who never takes a snap - settled at the pre-game price
 * - but not an inactive one), and questionable men are flagged.
 *
 *     ./gradlew run -Pmain=KalshiFair -Pweek=3          (live Kalshi read, archived)
 *     ./gradlew run -Pmain=KalshiFair -Pweek=3 -Poffline=true
 */
public class KalshiFair {

    static final Path LEDGER = Path.of("data", "kalshi-ledger");

    static String normal(String name){
        return name == null ? "" : name.toLowerCase().replaceAll("\\b(jr|sr|ii|iii|iv|v)\\b\\.?", "").replaceAll("[^a-z]", "");
    }

    /** Sleeper id by normalised full name, for the men with a projection this week. */
    static Map<String, List<String>> nameIndex(Map<String, JsonObject> projected) throws IOException {
        JsonObject all;
        all = PlayerRawData.database();   // through the in-season daily expiry
        Map<String, List<String>> out = new HashMap<>();
        for(String id : projected.keySet()){
            JsonElement e = all.get(id);
            if(e == null || !e.isJsonObject()){
                continue;
            }
            JsonObject p = e.getAsJsonObject();
            String full = ScreenData.text(p, "full_name");
            if(full == null){
                full = ScreenData.text(p, "first_name") + " " + ScreenData.text(p, "last_name");
            }
            out.computeIfAbsent(normal(full), k -> new ArrayList<>()).add(id);
        }
        return out;
    }

    /** The player's name as Kalshi writes it: the part of the YES title before any colon. */
    static String kalshiName(KalshiMarkets.Contract c){
        String s = c.playerName() == null ? "" : c.playerName();
        int colon = s.indexOf(':');
        return colon >= 0 ? s.substring(0, colon).trim() : s.trim();
    }

    record Row(String ticker, String game, String player, String sleeperId, String position, String stat, String kind, String strike,
               double projected, double model, Double yesBid, Double yesAsk, Double noAsk, double yesAskSize, double noAskSize,
               String injury) {
        double edgeYes(){
            return yesAsk == null || yesAsk <= 0 || yesAsk >= 1 ? Double.NaN : model - yesAsk - KalshiArb.fee(yesAsk);
        }

        double edgeNo(){
            return noAsk == null || noAsk <= 0 || noAsk >= 1 ? Double.NaN : (1 - model) - noAsk - KalshiArb.fee(noAsk);
        }

        Double mid(){
            return yesBid == null || yesAsk == null || yesBid <= 0 || yesAsk >= 1 ? null : (yesBid + yesAsk) / 2;
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        String season = LeagueWeek.season();
        int week = Integer.getInteger("week", LeagueWeek.week());
        boolean offline = Boolean.parseBoolean(System.getProperty("offline", "false"));
        Map<String, List<KalshiMarkets.Contract>> bySeries = offline ? KalshiMarkets.latestArchived() : KalshiMarkets.snapshot();
        Map<String, JsonObject> projected = ScreenData.projectedLines(LeagueWeek.projectionsBody(season, week));
        Map<String, List<String>> names = nameIndex(projected);
        Map<PropModel.Stat, PropModel.Fitted> model = PropModel.fit();

        List<Row> rows = new ArrayList<>();
        Map<String, Integer> unmatched = new TreeMap<>();
        int skippedInjured = 0;
        for(List<KalshiMarkets.Contract> list : bySeries.values()){
            for(KalshiMarkets.Contract c : list){
                if(c.player() == null || c.kind() == KalshiMarkets.Kind.OTHER){
                    continue;
                }
                String name = kalshiName(c);
                List<String> ids = names.getOrDefault(normal(name), List.of());
                String id = null;
                PropModel.Stat stat = null;
                for(String candidate : ids){
                    Player p = Player.getPlayerFromSIDV2(candidate);
                    if(p == null || p.position == null){
                        continue;
                    }
                    PropModel.Stat s = PropModel.find(c.stat(), p.position);
                    if(s != null){
                        id = candidate;
                        stat = s;
                        break;
                    }
                }
                if(id == null){
                    unmatched.merge(c.stat(), 1, Integer::sum);
                    continue;
                }
                String injury = SleeperProjections.injuryStatusOf(id);
                if(injury != null && (injury.equals("Out") || injury.equals("Doubtful") || injury.equals("IR") || injury.equals("PUP")
                        || injury.equals("Sus"))){
                    skippedInjured++;
                    continue;
                }
                double m = PropShape.sum(projected.get(id), stat.keys());
                PropModel.Fitted f = model.get(stat);
                double price;
                String strike;
                if(c.kind() == KalshiMarkets.Kind.THRESHOLD){
                    price = f.over(m, c.strike(), null);
                    strike = c.strike() + "+";
                }
                else if(c.kind() == KalshiMarkets.Kind.LADDER){
                    double perUnit = c.perUnit();
                    double cap = c.cap();
                    price = f.expect(m, y -> perUnit * Math.max(0, Math.min(y, cap)));
                    strike = "ladder";
                }
                else if(c.schedule() != null){
                    KalshiArb.Payoff p = KalshiArb.escalator(c.schedule(), c.step());
                    price = f.expect(m, p::value);
                    strike = "escalator";
                }
                else{
                    continue;
                }
                rows.add(new Row(c.ticker(), c.event().substring(c.event().indexOf('-') + 1), name, id, stat.position().name(),
                        stat.name(), c.kind().name(), strike, m, price, c.yesBid(), c.yesAsk(), c.noAsk(), c.yesAskSize(), c.yesBidSize(),
                        injury == null ? "" : injury));
            }
        }

        StringBuilder out = new StringBuilder();
        out.append(String.format("KALSHI AGAINST THE PROP MODEL  %s  (week %d; %s Kalshi read; Sleeper's live week-%d projections)%n",
                LocalDateTime.now().withNano(0), week, offline ? "archived" : "live", week));
        out.append(String.format("%d contracts priced; %d players out/doubtful/IR left out; unmatched by stat: %s%n", rows.size(), skippedInjured, unmatched));

        // where the market sits against the model, by stat and by how far the strike is from the projection
        out.append("\n== WHERE THE MARKET SITS: mean (market mid - model) on thresholds, by strike over projection ==\n");
        out.append(String.format("%-18s %6s   %s%n", "stat", "n", "<0.6  0.6-0.9  0.9-1.1  1.1-1.4  >1.4   (market minus model, points)"));
        Map<String, double[][]> gap = new TreeMap<>();
        double[] cuts = {0.6, 0.9, 1.1, 1.4};
        for(Row r : rows){
            if(!r.kind().equals("THRESHOLD") || r.mid() == null || r.projected() <= 0){
                continue;
            }
            double ratio = Integer.parseInt(r.strike().replace("+", "")) / r.projected();
            int b = 0;
            while(b < cuts.length && ratio >= cuts[b]){
                b++;
            }
            double[][] g = gap.computeIfAbsent(r.stat(), k -> new double[5][2]);
            g[b][0] += r.mid() - r.model();
            g[b][1]++;
        }
        for(Map.Entry<String, double[][]> e : gap.entrySet()){
            StringBuilder cells = new StringBuilder();
            int n = 0;
            for(double[] cell : e.getValue()){
                cells.append(cell[1] == 0 ? "    -  " : String.format("%+6.1f ", 100 * cell[0] / cell[1]));
                n += (int) cell[1];
            }
            out.append(String.format("%-18s %6d   %s%n", e.getKey(), n, cells));
        }
        out.append("Positive: the market prices the YES richer than the model. A column that is positive everywhere is the market\n");
        out.append("paying for overs the model does not believe in - or the model missing what the market knows.\n");

        // the gaps big enough to pay for the spread and fees
        List<Row> yes = new ArrayList<>(rows.stream().filter(r -> r.edgeYes() > 0.03).toList());
        List<Row> no = new ArrayList<>(rows.stream().filter(r -> r.edgeNo() > 0.03).toList());
        yes.sort(Comparator.comparingDouble(r -> -r.edgeYes()));
        no.sort(Comparator.comparingDouble(r -> -r.edgeNo()));
        out.append(String.format("%n== GAPS OVER 3 CENTS AFTER FEES, at the ask: %d YES, %d NO (of %d contracts) ==%n", yes.size(), no.size(), rows.size()));
        out.append(String.format("%-40s %-6s %-16s %7s %7s %6s %6s %7s %s%n", "contract", "side", "stat", "proj", "model", "ask", "edge", "size", "note"));
        for(Row r : yes.subList(0, Math.min(25, yes.size()))){
            out.append(String.format("%-40s %-6s %-16s %7.1f %7.3f %6.2f %+6.3f %7.0f %s%n", r.player() + " " + r.strike(), "YES", r.stat(),
                    r.projected(), r.model(), r.yesAsk(), r.edgeYes(), r.yesAskSize(), r.injury()));
        }
        for(Row r : no.subList(0, Math.min(25, no.size()))){
            out.append(String.format("%-40s %-6s %-16s %7.1f %7.3f %6.2f %+6.3f %7.0f %s%n", r.player() + " " + r.strike(), "NO", r.stat(),
                    r.projected(), 1 - r.model(), r.noAsk(), r.edgeNo(), r.noAskSize(), r.injury()));
        }
        out.append("\nThese are candidates, not bets: the ledger is scored after the games (KalshiSettle), and only a record of\n");
        out.append("gaps that paid, over enough weeks to beat the noise, makes any of this money.\n");

        // the ledger
        Files.createDirectories(LEDGER);
        String stamp = LocalDateTime.now().withNano(0).toString().replace(':', '-');
        StringBuilder csv = new StringBuilder("ticker,game,player,sleeper_id,position,stat,kind,strike,projected,model,yes_bid,yes_ask,no_ask,yes_ask_size,no_ask_size,injury\n");
        for(Row r : rows){
            csv.append(String.join(",", r.ticker(), r.game(), r.player().replace(",", " "), r.sleeperId(), r.position(), r.stat(), r.kind(),
                    r.strike(), String.format("%.2f", r.projected()), String.format("%.4f", r.model()), str(r.yesBid()), str(r.yesAsk()),
                    str(r.noAsk()), String.format("%.0f", r.yesAskSize()), String.format("%.0f", r.noAskSize()), r.injury())).append('\n');
        }
        Path ledger = LEDGER.resolve(season + "-w" + week + "-" + stamp + ".csv");
        Files.writeString(ledger, csv.toString(), StandardCharsets.UTF_8);
        out.append("\nledger written to ").append(ledger).append('\n');
        System.out.print(out);
        Path report = Path.of("data", "kalshi-fair-" + season + "-w" + week + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + report);
    }

    static String str(Double d){
        return d == null ? "" : String.format("%.2f", d);
    }
}
