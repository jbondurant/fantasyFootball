import com.google.gson.JsonObject;

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

/**
 * THIS WEEK'S PROJECTION FROM THE BETTING MARKET, FOR THE LINEUP.
 *
 * MarketVsSleeper raced Kalshi's prop prices against Sleeper's projections
 * over the 2025 season: with each priced stat replaced by the market's, a
 * man's fantasy points were closer to what he scored (RMSE 6.27 against 6.33
 * on 3,548 player-weeks; squared-error gain +0.77 +- 0.27, clustered by
 * week), mostly through quarterbacks' passing and touchdowns everywhere, and
 * no better on receptions. This is that projection, live: Sleeper's weekly
 * number with every stat Kalshi prices replaced by the market's expected stat
 * - the projection whose PropModel distribution best reproduces the
 * contract prices, read the way MarketVsSleeper was validated - scored under
 * this league's rules.
 *
 * It then shows Justin's roster both ways and the best lineup under each. The
 * edge is about a tenth of a point a man: it decides close calls, not rosters.
 *
 *     ./gradlew run -Pmain=MarketProjection -Pweek=3                 (live Kalshi read)
 *     ./gradlew run -Pmain=MarketProjection -Pweek=3 -Poffline=true  (the latest archived read)
 */
public class MarketProjection {

    /** id -> {Sleeper's league points, the market's, the number of priced stats}. */
    static Map<String, double[]> week(String season, int week, Map<String, List<KalshiMarkets.Contract>> bySeries,
                                      Map<PropModel.Stat, PropModel.Fitted> model, LeagueScoringSettings scoring) throws IOException {
        String body = LeagueWeek.projectionsBody(season, week);
        Map<String, JsonObject> projected = ScreenData.projectedLines(body);
        Map<String, Double> sleeperPoints = LeagueWeek.projectedFrom(body);
        Map<String, List<String>> names = KalshiFair.nameIndex(projected);
        Map<String, Double> weight = Map.of("passing yards", scoring.passYard, "passing TDs", scoring.passTD, "rushing yards", scoring.rushYard,
                "receiving yards", scoring.receivingYard, "receptions", scoring.reception, "touchdowns", scoring.rushTD,
                "interceptions", scoring.interception, "rush + rec yards", scoring.rushYard);
        Map<String, List<double[]>> quotes = new HashMap<>();
        Map<String, PropModel.Stat> statOf = new HashMap<>();
        for(List<KalshiMarkets.Contract> list : bySeries.values()){
            for(KalshiMarkets.Contract c : list){
                if(c.kind() != KalshiMarkets.Kind.THRESHOLD || c.yesBid() == null || c.yesAsk() == null){
                    continue;
                }
                double mid = (c.yesBid() + c.yesAsk()) / 2;
                if(mid < 0.02 || mid > 0.98 || c.yesAsk() - c.yesBid() > 0.20){
                    continue;
                }
                for(String id : names.getOrDefault(KalshiFair.normal(KalshiFair.kalshiName(c)), List.of())){
                    Player p = Player.getPlayerFromSIDV2(id);
                    PropModel.Stat s = p == null || p.position == null ? null : PropModel.find(c.stat(), p.position);
                    if(s != null && weight.containsKey(s.name())){
                        String key = id + "|" + s.name();
                        quotes.computeIfAbsent(key, k -> new ArrayList<>()).add(new double[]{c.strike(), mid});
                        statOf.put(key, s);
                        break;
                    }
                }
            }
        }
        // rush + rec yards overlaps rushing and receiving yards: used only for a man priced on neither
        Map<String, double[]> out = new HashMap<>();
        for(Map.Entry<String, List<double[]>> e : quotes.entrySet()){
            String id = e.getKey().substring(0, e.getKey().indexOf('|'));
            PropModel.Stat s = statOf.get(e.getKey());
            if(s.name().equals("rush + rec yards") && (quotes.containsKey(id + "|rushing yards") || quotes.containsKey(id + "|receiving yards"))){
                continue;
            }
            PropModel.Fitted f = model.get(s);
            double mu = PropShape.sum(projected.get(id), s.keys());
            double implied = MarketVsSleeper.implied(f, e.getValue(), null);
            double market = f.expect(implied, y -> y, null);
            double[] v = out.computeIfAbsent(id, k -> new double[]{sleeperPoints.getOrDefault(id, 0.0), sleeperPoints.getOrDefault(id, 0.0), 0});
            v[1] += weight.get(s.name()) * (market - mu);
            v[2]++;
        }
        return out;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        String season = LeagueWeek.season();
        int week = Integer.getInteger("week", LeagueWeek.week());
        boolean offline = Boolean.parseBoolean(System.getProperty("offline", "false"));
        Map<String, List<KalshiMarkets.Contract>> bySeries = offline ? KalshiMarkets.latestArchived() : KalshiMarkets.snapshot();
        Map<PropModel.Stat, PropModel.Fitted> model = PropModel.fit();
        Map<String, double[]> market = week(season, week, bySeries, model, scoring);
        Map<String, Double> sleeper = LeagueWeek.projected(season, week);

        String me = System.getProperty("me", configuration.getUserIDToDisplayName().getOrDefault(configuration.getMyID(), configuration.getMyID()));
        Map<String, String> ownerOf = LeagueOwners.today(configuration);
        java.util.Set<String> reserve = LeagueOwners.reserve(configuration);
        List<TeamRankings.Man> bySleeper = new ArrayList<>();
        List<TeamRankings.Man> byMarket = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        out.append(String.format("MARKET PROJECTION  %s  (week %d; %s Kalshi read; %d men with a priced stat)%n", LocalDateTime.now().withNano(0), week,
                offline ? "archived" : "live", market.size()));
        out.append("Sleeper's weekly projection with every stat Kalshi prices replaced by the market's (2025 backtest: closer to what\n");
        out.append("happened by about 1% RMSE, QB passing and touchdowns most, receptions not at all). League points.\n\n");
        out.append(String.format("%-24s %-4s %9s %9s %8s %7s%n", me + "'s roster", "pos", "Sleeper", "market", "diff", "priced"));
        List<String[]> rows = new ArrayList<>();
        for(Map.Entry<String, String> e : ownerOf.entrySet()){
            if(!e.getValue().equals(me) || reserve.contains(e.getKey())){
                continue;
            }
            Player p = Player.getPlayerFromSIDV2(e.getKey());
            if(p == null || p.position == null){
                continue;
            }
            double s = sleeper.getOrDefault(e.getKey(), 0.0);
            double[] m = market.get(e.getKey());
            double mk = m == null ? s : m[1];
            String name = p.firstName + " " + p.lastName;
            bySleeper.add(new TeamRankings.Man(e.getKey(), name, p.position.name(), p.team, s, false, 0, null));
            byMarket.add(new TeamRankings.Man(e.getKey(), name, p.position.name(), p.team, mk, false, 0, null));
            rows.add(new String[]{name, p.position.name(), String.format("%.1f", s), String.format("%.1f", mk),
                    String.format("%+.1f", mk - s), m == null ? "-" : String.valueOf((int) m[2])});
        }
        rows.sort(Comparator.comparingDouble((String[] r) -> -Double.parseDouble(r[3])));
        for(String[] r : rows){
            out.append(String.format("%-24s %-4s %9s %9s %8s %7s%n", r[0], r[1], r[2], r[3], r[4], r[5]));
        }
        TeamRankings.Lineup a = TeamRankings.bestLineup(bySleeper);
        TeamRankings.Lineup b = TeamRankings.bestLineup(byMarket);
        List<String> onlySleeper = new ArrayList<>();
        List<String> onlyMarket = new ArrayList<>();
        java.util.Set<String> startA = new java.util.HashSet<>();
        java.util.Set<String> startB = new java.util.HashSet<>();
        a.starting().forEach(m -> startA.add(m.id()));
        b.starting().forEach(m -> startB.add(m.id()));
        for(TeamRankings.Man m : a.starting()){
            if(!startB.contains(m.id())){
                onlySleeper.add(m.name());
            }
        }
        for(TeamRankings.Man m : b.starting()){
            if(!startA.contains(m.id())){
                onlyMarket.add(m.name());
            }
        }
        out.append(String.format("%nbest lineup on Sleeper: %.1f points; on the market: %.1f%n", a.starters(), b.starters()));
        out.append(onlyMarket.isEmpty() ? "the two agree on every starter.\n"
                : "the market starts " + String.join(", ", onlyMarket) + " where Sleeper starts " + String.join(", ", onlySleeper) + ".\n");

        out.append("\nlargest market-minus-Sleeper gaps this week, any man (a gap is news the market has, or noise on a thin book):\n");
        List<Map.Entry<String, double[]>> gaps = new ArrayList<>(market.entrySet());
        gaps.sort(Comparator.comparingDouble((Map.Entry<String, double[]> e) -> -Math.abs(e.getValue()[1] - e.getValue()[0])));
        for(Map.Entry<String, double[]> e : gaps.subList(0, Math.min(15, gaps.size()))){
            Player p = Player.getPlayerFromSIDV2(e.getKey());
            String owner = ownerOf.getOrDefault(e.getKey(), "free");
            out.append(String.format("  %-24s %-3s %6.1f -> %6.1f  (%+.1f)  %s%n", p == null ? e.getKey() : p.firstName + " " + p.lastName,
                    p == null ? "" : p.position, e.getValue()[0], e.getValue()[1], e.getValue()[1] - e.getValue()[0], owner));
        }
        System.out.print(out);
        Path report = Path.of("data", "market-projection-" + season + "-w" + week + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }
}
