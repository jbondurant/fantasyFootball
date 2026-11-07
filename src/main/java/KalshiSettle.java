import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * SCORING THE KALSHI LEDGER AFTER THE GAMES - rules fixed before the first one.
 *
 * KalshiFair writes down, before kickoff, the model's price and the market's
 * quotes for every NFL prop contract Kalshi lists. This scores those rows
 * against what happened, and nothing about the rules below may change after a
 * ledger it scores was written:
 *
 *  - Threshold contracts only (ladders and escalators are not scored; their
 *    payoffs are not in the ledger). The outcome is the stat in Sleeper's
 *    stat row for the week, summed over the model's keys; a man with no gp is
 *    left out and counted (Kalshi settles an active man who never plays at the
 *    pre-game price, and an inactive man's settlement is not in its rules).
 *  - WHO IS SHARPER: Brier score of the model's probability against the
 *    market's midpoint on the same contracts, the paired difference clustered
 *    by player (one man's thresholds move together).
 *  - WHAT IT WOULD HAVE MADE: one contract at the ask, YES where the model's
 *    edge after fees exceeded e and NO likewise, for e = 3, 5 and 10 cents;
 *    profit per contract and per dollar staked, clustered by player; split by
 *    stat and side.
 *
 * One week is noise: a season of weeks is the test. -Pweeks=3,4,5 scores
 * several; every ledger under data/kalshi-ledger/ for those weeks is read,
 * and each contract counts from the LAST ledger written before ITS game's
 * kickoff (Eastern, from nflverse's schedule) - a row written after its game
 * began is never scored.
 *
 *     ./gradlew run -Pmain=KalshiSettle -Pweeks=3
 */
public class KalshiSettle {

    static final double[] EDGES = {0.03, 0.05, 0.10};

    /** One scored contract. */
    record Scored(String player, String stat, double model, Double mid, Double yesAsk, Double noAsk, boolean hit) {}

    /** The ledger rows of one file, as maps by column name. */
    static List<Map<String, String>> read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String[] header = lines.get(0).split(",", -1);
        List<Map<String, String>> out = new ArrayList<>();
        for(String line : lines.subList(1, lines.size())){
            String[] cells = line.split(",", -1);
            Map<String, String> row = new HashMap<>();
            for(int i = 0; i < header.length && i < cells.length; i++){
                row.put(header[i], cells[i]);
            }
            out.add(row);
        }
        return out;
    }

    static Double number(String s){
        return s == null || s.isEmpty() ? null : Double.parseDouble(s);
    }

    /** Profit of one contract at the ask: YES pays 1 on a hit, NO on a miss; the fee is Kalshi's taker formula. */
    static double profit(boolean yes, double ask, boolean hit){
        boolean won = yes == hit;
        return (won ? 1 : 0) - ask - KalshiArb.fee(ask);
    }

    /** Mean and clustered standard error of values grouped by cluster key. */
    static double[] clustered(List<double[]> valueCluster){
        double n = valueCluster.size();
        if(n == 0){
            return new double[]{Double.NaN, Double.NaN};
        }
        double mean = valueCluster.stream().mapToDouble(v -> v[0]).sum() / n;
        Map<Double, Double> sums = new HashMap<>();
        for(double[] v : valueCluster){
            sums.merge(v[1], v[0] - mean, Double::sum);
        }
        double g = sums.size();
        double s = sums.values().stream().mapToDouble(x -> x * x).sum();
        double var = g > 1 ? g / (g - 1) * s / (n * n) : Double.NaN;
        return new double[]{mean, Math.sqrt(var)};
    }

    public static void main(String[] args) throws IOException {
        String season = LeagueWeek.season();
        List<Integer> weeks = new ArrayList<>();
        for(String w : System.getProperty("weeks", String.valueOf(LeagueWeek.week() - 1)).split(",")){
            weeks.add(Integer.parseInt(w.trim()));
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format("KALSHI LEDGER, SCORED  %s  (season %s, weeks %s)%n", LocalDate.now(), season, weeks));
        List<Scored> scored = new ArrayList<>();
        Map<String, Integer> clusterIds = new HashMap<>();
        int notPlayed = 0;
        for(int week : weeks){
            if(!LeagueWeek.finished(season, week)){
                out.append(String.format("week %d is not finished: not scored%n", week));
                continue;
            }
            Map<String, JsonObject> actual = PropShape.statsById(LeagueWeek.teamStatsBody(season, week));
            for(Map<String, String> row : beforeKickoff(season, week, out)){
                if(!"THRESHOLD".equals(row.get("kind"))){
                    continue;
                }
                JsonObject a = actual.get(row.get("sleeper_id"));
                if(a == null || FaabDemand.stat(a, "gp") < 1){
                    notPlayed++;
                    continue;
                }
                PropModel.Stat stat = PropModel.find(row.get("stat"), PlayerImportAndSetup.Position.valueOf(row.get("position")));
                if(stat == null){
                    continue;
                }
                double y = PropShape.sum(a, stat.keys());
                int strike = Integer.parseInt(row.get("strike").replace("+", ""));
                Double bid = number(row.get("yes_bid"));
                Double ask = number(row.get("yes_ask"));
                Double mid = bid != null && ask != null && bid > 0 && ask < 1 ? (bid + ask) / 2 : null;
                String cluster = week + "|" + row.get("sleeper_id");
                clusterIds.computeIfAbsent(cluster, k -> clusterIds.size());
                scored.add(new Scored(cluster, row.get("stat"), Double.parseDouble(row.get("model")), mid, ask, number(row.get("no_ask")), y >= strike));
            }
        }
        out.append(String.format("%d threshold contracts scored; %d left out because the man did not play%n", scored.size(), notPlayed));
        if(scored.isEmpty()){
            System.out.print(out);
            return;
        }

        // who is sharper
        List<double[]> diff = new ArrayList<>();
        double bm = 0;
        double bk = 0;
        int both = 0;
        for(Scored s : scored){
            if(s.mid() == null){
                continue;
            }
            double o = s.hit() ? 1 : 0;
            double m = (s.model() - o) * (s.model() - o);
            double k = (s.mid() - o) * (s.mid() - o);
            bm += m;
            bk += k;
            both++;
            diff.add(new double[]{m - k, clusterIds.get(s.player())});
        }
        double[] d = clustered(diff);
        out.append(String.format("%nWHO IS SHARPER on %d contracts with a two-sided quote: Brier model %.4f, market midpoint %.4f;%n", both, bm / both, bk / both));
        out.append(String.format("model minus market %+.4f +- %.4f (clustered by player-week; negative = the model was closer)%n", d[0], d[1]));

        // what it would have made
        out.append("\nWHAT ONE CONTRACT AT THE ASK WOULD HAVE MADE, by the model's edge after fees:\n");
        out.append(String.format("%-8s %-5s %6s %12s %12s %10s%n", "edge >", "side", "bets", "profit/ctr", "se", "total $"));
        for(double e : EDGES){
            for(boolean yes : new boolean[]{true, false}){
                List<double[]> pnl = new ArrayList<>();
                double staked = 0;
                for(Scored s : scored){
                    Double ask = yes ? s.yesAsk() : s.noAsk();
                    if(ask == null || ask <= 0 || ask >= 1){
                        continue;
                    }
                    double fair = yes ? s.model() : 1 - s.model();
                    if(fair - ask - KalshiArb.fee(ask) <= e){
                        continue;
                    }
                    pnl.add(new double[]{profit(yes, ask, s.hit()), clusterIds.get(s.player())});
                    staked += ask;
                }
                double[] p = clustered(pnl);
                double total = pnl.stream().mapToDouble(v -> v[0]).sum();
                out.append(String.format("%-8.2f %-5s %6d %+12.4f %12.4f %+10.2f   (%+.1f%% of %.0f staked)%n", e, yes ? "YES" : "NO", pnl.size(),
                        p[0], p[1], total, staked > 0 ? 100 * total / staked : 0, staked));
            }
        }
        out.append("\nby stat, edge > 0.03, both sides together:\n");
        Map<String, List<double[]>> byStat = new TreeMap<>();
        for(Scored s : scored){
            for(boolean yes : new boolean[]{true, false}){
                Double ask = yes ? s.yesAsk() : s.noAsk();
                if(ask == null || ask <= 0 || ask >= 1){
                    continue;
                }
                double fair = yes ? s.model() : 1 - s.model();
                if(fair - ask - KalshiArb.fee(ask) > 0.03){
                    byStat.computeIfAbsent(s.stat(), k -> new ArrayList<>()).add(new double[]{profit(yes, ask, s.hit()), clusterIds.get(s.player())});
                }
            }
        }
        for(Map.Entry<String, List<double[]>> e : byStat.entrySet()){
            double[] p = clustered(e.getValue());
            out.append(String.format("  %-18s %6d bets  %+.4f +- %.4f a contract%n", e.getKey(), e.getValue().size(), p[0], p[1]));
        }
        out.append("\nA profit inside two standard errors of zero is not a result. Weeks accumulate; nothing here is a reason to bet until\n");
        out.append("many weeks agree, and the model beating the midpoint on Brier is the precondition for any of it.\n");
        System.out.print(out);
        Path report = Path.of("data", "kalshi-settle-" + season + "-w" + weeks.get(0) + (weeks.size() > 1 ? "-w" + weeks.get(weeks.size() - 1) : "") + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }

    /** Kickoff (ET, "yyyy-MM-ddTHH:mm") of the game a Kalshi event suffix names, e.g. 26SEP28PHICHI. */
    static String kickoff(String eventSuffix, String season, int week){
        for(NflverseGames.Game g : NflverseGames.games()){
            if(g.season().equals(season) && g.week() == week && g.gameday() != null
                    && eventSuffix.startsWith(g.gameday().substring(2, 4))
                    && eventSuffix.contains(kalshiCode(g.home())) && eventSuffix.contains(kalshiCode(g.away()))){
                return g.gameday() + "T" + (g.gametime() == null ? "13:00" : g.gametime());
            }
        }
        return null;
    }

    /** Kalshi writes team codes as the NFL does; Sleeper's LAR is Kalshi's LAR too, so only JAX needs a second look. */
    static String kalshiCode(String team){
        return "JAX".equals(team) ? "JA" : team;
    }

    /**
     * Every ledger row of the week that was written before its own game's
     * kickoff, the latest such row per contract.
     */
    static List<Map<String, String>> beforeKickoff(String season, int week, StringBuilder out) throws IOException {
        List<Path> files = new ArrayList<>();
        if(Files.isDirectory(KalshiFair.LEDGER)){
            try(var list = Files.list(KalshiFair.LEDGER)){
                list.filter(p -> p.getFileName().toString().startsWith(season + "-w" + week + "-")).sorted().forEach(files::add);
            }
        }
        Map<String, Map<String, String>> byTicker = new java.util.LinkedHashMap<>();
        Map<String, String> kick = new HashMap<>();
        int late = 0;
        int unknown = 0;
        for(Path p : files){
            // <season>-w<week>-<yyyy-MM-ddTHH-mm-ss>.csv, written in local (Eastern) time
            String stamp = p.getFileName().toString().replace(season + "-w" + week + "-", "").replace(".csv", "");
            String taken = stamp.substring(0, 13) + ":" + stamp.substring(14, 16);
            for(Map<String, String> row : read(p)){
                String k = kick.computeIfAbsent(row.get("game"), g -> {
                    String v = kickoff(g, season, week);
                    return v == null ? "" : v;
                });
                if(k.isEmpty()){
                    unknown++;
                    continue;
                }
                if(taken.compareTo(k) >= 0){
                    late++;
                    continue;
                }
                byTicker.put(row.get("ticker"), row);
            }
        }
        out.append(String.format("week %d: %d ledger(s); %d contracts written before their kickoff, %d rows after it (not scored), %d with no scheduled game found%n",
                week, files.size(), byTicker.size(), late, unknown));
        return new ArrayList<>(byTicker.values());
    }
}
