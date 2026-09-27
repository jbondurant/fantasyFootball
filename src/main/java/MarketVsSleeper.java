import PlayerImportAndSetup.Position;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * IS THE BETTING MARKET A BETTER PROJECTION THAN SLEEPER'S?
 *
 * Justin, 2026-09-26: "can it be optimized, not to beat the market but to use
 * the efficient market makers to beat Sleeper? Or are Sleeper's projections
 * better than the gambling market's for raw fantasy points?" This races them
 * on the 2025 regular season, every player-week both priced:
 *
 *  - SLEEPER: its stored weekly projected stat line (Rotowire's, read late,
 *    after the inactives). Raw, and "calibrated" - the mean outcome of men
 *    projected like him (PropModel), which removes Rotowire's small high
 *    lean so the race is about information, not calibration.
 *  - MARKET: Kalshi's prop prices an hour before kickoff (KalshiHistory). A
 *    stat's thresholds are read as the projection m* whose PropModel
 *    distribution best reproduces their midpoints (least squares - the way an
 *    implied volatility is backed out of option prices), and the market's
 *    expected stat is that distribution's mean. Anytime-touchdown prices
 *    are read the same way on touchdowns.
 *
 * PropModel's shape leaves 2025 out throughout: the season being scored never
 * shapes the distributions that read it. Only quotes with a midpoint between
 * 2 and 98 cents and a spread of 20 cents or less count, and only men who
 * played (Sleeper gp).
 *
 * Reported: per stat, the error of each against what happened, the paired
 * difference clustered by week, and a blend whose weight on the market is
 * fitted leaving each week out. Then fantasy points under this league's
 * scoring: Sleeper's projection with each priced stat replaced by the
 * market's, against Sleeper's own.
 *
 *     ./gradlew run -Pmain=MarketVsSleeper
 */
public class MarketVsSleeper {

    static final String SEASON = "2025";

    /** One player-week-stat both priced. */
    record Case(int week, String id, Position position, String stat, double sleeperRaw, double sleeperCal, double market, double actual,
                int strikes) {}

    static String normal(String name){
        return KalshiFair.normal(name);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        StringBuilder log = new StringBuilder();
        List<KalshiHistory.Priced> priced = KalshiHistory.season2025(log);
        Map<PropModel.Stat, PropModel.Fitted> model = PropModel.fit();
        ScreenData.Season season = ScreenData.load(SEASON, true, true, scoring);
        Map<Integer, Map<String, JsonObject>> actualStats = new HashMap<>();
        for(int w = 1; w <= 18; w++){
            actualStats.put(w, PropShape.statsById(LeagueWeek.teamStatsBody(SEASON, w)));
        }
        Map<String, NflverseGames.Game> gameById = new HashMap<>();
        for(NflverseGames.Game g : NflverseGames.games()){
            gameById.put(g.gameId(), g);
        }
        // Sleeper ids by normalised name, over the whole player database (2025 men are not all projected today)
        Map<String, List<String>> ids = new HashMap<>();
        JsonObject db;
        try(FileReader reader = new FileReader("sleeperDataPlayerAPI.json")){
            db = JsonParser.parseReader(reader).getAsJsonObject();
        }
        for(Map.Entry<String, JsonElement> e : db.entrySet()){
            if(e.getValue().isJsonObject()){
                JsonObject p = e.getValue().getAsJsonObject();
                String full = ScreenData.text(p, "full_name");
                if(full != null){
                    ids.computeIfAbsent(normal(full), k -> new ArrayList<>()).add(e.getKey());
                }
            }
        }

        // group the quoted thresholds by week, player and stat
        Map<String, List<double[]>> strikes = new LinkedHashMap<>();
        Map<String, String[]> who = new HashMap<>();
        int unmatched = 0;
        int unquoted = 0;
        for(KalshiHistory.Priced p : priced){
            Double mid = p.mid();
            if(mid == null || mid < 0.02 || mid > 0.98 || p.ask() - p.bid() > 0.20){
                unquoted++;
                continue;
            }
            NflverseGames.Game g = gameById.get(p.game());
            String id = null;
            for(String candidate : ids.getOrDefault(normal(p.playerName()), List.of())){
                ScreenData.Line line = season.line(g.week(), candidate);
                if(line != null && (line.team().equals(g.home()) || line.team().equals(g.away()))){
                    id = candidate;
                    break;
                }
            }
            if(id == null){
                unmatched++;
                continue;
            }
            String key = g.week() + "|" + id + "|" + p.stat();
            strikes.computeIfAbsent(key, k -> new ArrayList<>()).add(new double[]{p.strike(), mid});
            who.put(key, new String[]{String.valueOf(g.week()), id, p.stat()});
        }

        List<Case> cases = new ArrayList<>();
        int notPlayed = 0;
        for(Map.Entry<String, List<double[]>> e : strikes.entrySet()){
            String[] k = who.get(e.getKey());
            int week = Integer.parseInt(k[0]);
            ScreenData.Line line = season.line(week, k[1]);
            JsonObject proj = season.projected.getOrDefault(week, Map.of()).get(k[1]);
            JsonObject act = actualStats.get(week).get(k[1]);
            if(line == null || !line.played() || act == null){
                notPlayed++;
                continue;
            }
            PropModel.Stat stat = PropModel.find(k[2], line.position());
            if(stat == null || proj == null){
                continue;
            }
            PropModel.Fitted f = model.get(stat);
            double mu = PropShape.sum(proj, stat.keys());
            double implied = implied(f, e.getValue());
            cases.add(new Case(week, k[1], line.position(), stat.name(), mu, f.expect(mu, y -> y, SEASON),
                    f.expect(implied, y -> y, SEASON), PropShape.sum(act, stat.keys()), e.getValue().size()));
        }

        StringBuilder out = new StringBuilder();
        out.append(String.format("MARKET AGAINST SLEEPER  %s  (the %s regular season; Kalshi an hour before kickoff; PropModel shape without %s)%n%n",
                LocalDate.now(), SEASON, SEASON));
        out.append(log);
        out.append(String.format("quotes used: those with a midpoint in (0.02, 0.98) and a spread <= 0.20; %d others left out, %d not matched to a Sleeper id,%n",
                unquoted, unmatched));
        out.append(String.format("%d player-week-stats where the man did not play. %d player-week-stats raced.%n", notPlayed, cases.size()));

        out.append("\n== PER STAT: error against what happened (RMSE; bias = mean projection minus outcome) ==\n");
        out.append(String.format("%-4s %-16s %6s %10s %10s %10s %10s %10s %10s %22s %10s%n", "pos", "stat", "n", "RMSE slpr", "RMSE cal",
                "RMSE mkt", "bias slpr", "bias cal", "bias mkt", "cal - mkt (sq err)", "w market"));
        Map<String, List<Case>> byStat = new TreeMap<>();
        for(Case c : cases){
            byStat.computeIfAbsent(c.position() + "|" + c.stat(), s -> new ArrayList<>()).add(c);
        }
        for(Map.Entry<String, List<Case>> e : byStat.entrySet()){
            List<Case> list = e.getValue();
            if(list.size() < 30){
                continue;
            }
            double[] r = compare(list);
            String[] ps = e.getKey().split("\\|");
            out.append(String.format("%-4s %-16s %6d %10.2f %10.2f %10.2f %+10.2f %+10.2f %+10.2f %+10.3f +- %-8.3f %10.2f%n", ps[0], ps[1],
                    list.size(), r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]));
        }
        out.append("'cal - mkt' positive: the market's squared error is smaller (clustered by week). 'w market': the least-squares weight\n");
        out.append("on the market in a blend with calibrated Sleeper, fitted on all weeks (its held-out RMSE is in the points table).\n");

        // fantasy points
        out.append("\n== FANTASY POINTS: Sleeper's projection, and the same with each priced stat replaced by the market's ==\n");
        Map<String, Double> weight = new HashMap<>();
        weight.put("passing yards", scoring.passYard);
        weight.put("passing TDs", scoring.passTD);
        weight.put("rushing yards", scoring.rushYard);
        weight.put("receiving yards", scoring.receivingYard);
        weight.put("receptions", scoring.reception);
        weight.put("touchdowns", scoring.rushTD);
        Map<String, double[]> perPlayerWeek = new LinkedHashMap<>();       // {sleeper points, cal shift, market shift, actual, week, priced stats}
        for(Case c : cases){
            String key = c.week() + "|" + c.id();
            double[] v = perPlayerWeek.computeIfAbsent(key, k -> {
                Double p = season.projectedPoints.getOrDefault(c.week(), Map.of()).get(c.id());
                ScreenData.Line line = season.line(c.week(), c.id());
                return new double[]{p == null ? Double.NaN : p, 0, 0, line.points(), c.week(), 0};
            });
            double w = weight.getOrDefault(c.stat(), 0.0);
            v[1] += w * (c.sleeperCal() - c.sleeperRaw());
            v[2] += w * (c.market() - c.sleeperRaw());
            v[5]++;
        }
        out.append(String.format("%-28s %7s %10s %10s %10s %10s %24s%n", "player-weeks", "n", "RMSE slpr", "RMSE cal", "RMSE mkt", "RMSE blend",
                "cal - mkt (sq err)"));
        for(int minStats : new int[]{1, 2, 3}){
            List<double[]> rows = new ArrayList<>();
            for(double[] v : perPlayerWeek.values()){
                if(!Double.isNaN(v[0]) && v[5] >= minStats){
                    rows.add(v);
                }
            }
            if(rows.size() < 30){
                continue;
            }
            List<Case> asCases = new ArrayList<>();
            for(double[] v : rows){
                asCases.add(new Case((int) v[4], "", Position.WR, "points", v[0], v[0] + v[1], v[0] + v[2], v[3], 0));
            }
            double[] r = compare(asCases);
            out.append(String.format("%-28s %7d %10.2f %10.2f %10.2f %10.2f %+12.3f +- %-8.3f%n", "with " + minStats + "+ priced stats",
                    rows.size(), r[0], r[1], r[2], r[9], r[6], r[7]));
        }
        out.append("\nThe blend's weight on the market is fitted on every other week and scored on the week left out.\n");
        System.out.print(out);
        Path report = Path.of("data", "market-vs-sleeper-" + SEASON + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }

    /**
     * The projection whose PropModel distribution best reproduces the quoted
     * P(stat >= strike): least squares over a log grid, then a finer pass
     * around the best. 2025 is left out of the shape.
     */
    static double implied(PropModel.Fitted f, List<double[]> quotes){
        return implied(f, quotes, SEASON);
    }

    /** The same, leaving {@code exclude}'s season out of the shape (null: every season - a live week is in none of them). */
    static double implied(PropModel.Fitted f, List<double[]> quotes, String exclude){
        double hi = 1;
        for(double[] q : quotes){
            hi = Math.max(hi, 3 * q[0]);
        }
        double lo = 0.01;
        double best = lo;
        double bestErr = Double.MAX_VALUE;
        for(int pass = 0; pass < 2; pass++){
            double a = Math.log(lo);
            double b = Math.log(hi);
            for(int i = 0; i <= 80; i++){
                double m = Math.exp(a + (b - a) * i / 80);
                double err = 0;
                for(double[] q : quotes){
                    double d = f.over(m, q[0], exclude) - q[1];
                    err += d * d;
                }
                if(err < bestErr){
                    bestErr = err;
                    best = m;
                }
            }
            lo = best / 1.15;
            hi = best * 1.15;
        }
        return best;
    }

    /**
     * {RMSE raw, RMSE calibrated, RMSE market, bias raw, bias cal, bias market,
     * mean (sq err cal - sq err market), its se clustered by week, the market's
     * blend weight on all weeks, the blend's leave-one-week-out RMSE}.
     */
    static double[] compare(List<Case> list){
        double sr = 0, sc = 0, sm = 0, br = 0, bc = 0, bm = 0;
        Map<Integer, double[]> byWeek = new TreeMap<>();
        for(Case c : list){
            sr += sq(c.sleeperRaw() - c.actual());
            sc += sq(c.sleeperCal() - c.actual());
            sm += sq(c.market() - c.actual());
            br += c.sleeperRaw() - c.actual();
            bc += c.sleeperCal() - c.actual();
            bm += c.market() - c.actual();
            byWeek.computeIfAbsent(c.week(), w -> new double[2])[0] += sq(c.sleeperCal() - c.actual()) - sq(c.market() - c.actual());
            byWeek.get(c.week())[1]++;
        }
        int n = list.size();
        double meanD = (sc - sm) / n;
        double s = 0;
        for(double[] v : byWeek.values()){
            s += sq(v[0] - meanD * v[1]);
        }
        int g = byWeek.size();
        double se = g > 1 ? Math.sqrt((double) g / (g - 1) * s) / n : Double.NaN;
        double wAll = weight(list, -1);
        double sb = 0;
        for(Case c : list){
            double w = weight(list, c.week());
            sb += sq(w * c.market() + (1 - w) * c.sleeperCal() - c.actual());
        }
        return new double[]{Math.sqrt(sr / n), Math.sqrt(sc / n), Math.sqrt(sm / n), br / n, bc / n, bm / n, meanD, se, wAll, Math.sqrt(sb / n)};
    }

    /** The least-squares weight on the market against calibrated Sleeper, clipped to [0, 1], leaving one week out (-1: none). */
    static double weight(List<Case> list, int leaveOut){
        double num = 0;
        double den = 0;
        for(Case c : list){
            if(c.week() == leaveOut){
                continue;
            }
            double d = c.market() - c.sleeperCal();
            num += (c.actual() - c.sleeperCal()) * d;
            den += d * d;
        }
        return den <= 0 ? 0 : Math.max(0, Math.min(1, num / den));
    }

    static double sq(double x){
        return x * x;
    }
}
