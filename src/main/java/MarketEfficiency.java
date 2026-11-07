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
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * HOW EFFICIENT HAS THE NFL BETTING MARKET BEEN, MEASURED ON ITS OWN PRICES.
 *
 * Justin, 2026-09-26: keep working until there is a model that makes money
 * against the people betting on sports apps - and say so if that is not
 * achievable. Before any model, the market it would have to beat: every
 * regular-season game in nflverse's schedule with its lines (from 1999) and
 * the prices actually offered on each side (from 2006, complete from 2010).
 *
 *  1. The line as a forecast: its bias and its error against what happened.
 *  2. What the obvious bets returned at the real prices - favourites, dogs,
 *     home, road, overs, unders, and the angles bettors repeat (home dogs,
 *     windy unders, primetime unders, divisional dogs, longshots) - each
 *     split into 2010-2017 and 2018-2025, because an angle that pays in one
 *     era and not the next is how a market closes one.
 *  3. The moneyline as a probability, once the margin is taken out: does it
 *     say 30% for things that happen 30% of the time?
 *  4. A public model against the line: team ratings refitted every week on
 *     past games only (ridge, time-decayed, with home field), then bet
 *     wherever it disagrees with the line by k points.
 *
 * Break-even at the standard -110 is 52.38% of bets decided. The prices here
 * are the schedule's; nflverse describes its lines as closing lines, which
 * are the hardest to beat - a bettor who takes earlier numbers faces a softer
 * market and a line that has not yet absorbed the news.
 *
 *     ./gradlew run -Pmain=MarketEfficiency
 */
public class MarketEfficiency {

    /** Profit on a one-unit winning stake at American odds. */
    static double payout(int american){
        return american > 0 ? american / 100.0 : 100.0 / -american;
    }

    /** The probability the price implies, margin included. */
    static double implied(int american){
        return american > 0 ? 100.0 / (american + 100) : -american / (-american + 100.0);
    }

    /** One bet's return per unit staked: the payout if it won, -1 if it lost, 0 on a push. */
    static double settle(int result, int american){
        return result > 0 ? payout(american) : result < 0 ? -1 : 0;
    }

    /** +1 if the home side covered the spread (home margin > spread, the spread being the home side's), -1 if not, 0 on a push. */
    static int homeCover(NflverseGames.Game g){
        double d = g.margin() - g.spread();
        return d > 0 ? 1 : d < 0 ? -1 : 0;
    }

    static int over(NflverseGames.Game g){
        double d = g.points() - g.total();
        return d > 0 ? 1 : d < 0 ? -1 : 0;
    }

    /** A strategy: which games it bets, and the bet's result and price for one game. */
    record Bet(int result, int price) {}

    record Strategy(String name, Predicate<NflverseGames.Game> takes, Function<NflverseGames.Game, Bet> bet) {}

    /** {bets, decided, wins, mean return, se of the mean return}. */
    static double[] evaluate(List<NflverseGames.Game> games, Strategy s){
        List<Double> returns = new ArrayList<>();
        int decided = 0;
        int wins = 0;
        for(NflverseGames.Game g : games){
            if(!s.takes().test(g)){
                continue;
            }
            Bet b = s.bet().apply(g);
            returns.add(settle(b.result(), b.price()));
            decided += b.result() != 0 ? 1 : 0;
            wins += b.result() > 0 ? 1 : 0;
        }
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = returns.stream().mapToDouble(r -> (r - mean) * (r - mean)).sum() / Math.max(1, returns.size() - 1);
        return new double[]{returns.size(), decided, wins, mean, Math.sqrt(var / Math.max(1, returns.size()))};
    }

    static boolean priced(NflverseGames.Game g){
        return g.played() && g.spread() != null && g.total() != null && g.homeSpreadOdds() != null && g.awaySpreadOdds() != null
                && g.overOdds() != null && g.underOdds() != null && g.homeMoneyline() != null && g.awayMoneyline() != null;
    }

    static final List<Strategy> STRATEGIES = List.of(
        new Strategy("every favourite ATS", g -> g.spread() != 0,
                g -> g.spread() > 0 ? new Bet(homeCover(g), g.homeSpreadOdds()) : new Bet(-homeCover(g), g.awaySpreadOdds())),
        new Strategy("every underdog ATS", g -> g.spread() != 0,
                g -> g.spread() < 0 ? new Bet(homeCover(g), g.homeSpreadOdds()) : new Bet(-homeCover(g), g.awaySpreadOdds())),
        new Strategy("every home side ATS", g -> !g.neutral(), g -> new Bet(homeCover(g), g.homeSpreadOdds())),
        new Strategy("every road side ATS", g -> !g.neutral(), g -> new Bet(-homeCover(g), g.awaySpreadOdds())),
        new Strategy("home underdogs ATS", g -> !g.neutral() && g.spread() < 0, g -> new Bet(homeCover(g), g.homeSpreadOdds())),
        new Strategy("divisional underdogs ATS", g -> g.divisional() && g.spread() != 0,
                g -> g.spread() < 0 ? new Bet(homeCover(g), g.homeSpreadOdds()) : new Bet(-homeCover(g), g.awaySpreadOdds())),
        new Strategy("underdogs of 7+ ATS", g -> Math.abs(g.spread()) >= 7,
                g -> g.spread() < 0 ? new Bet(homeCover(g), g.homeSpreadOdds()) : new Bet(-homeCover(g), g.awaySpreadOdds())),
        new Strategy("every over", g -> true, g -> new Bet(over(g), g.overOdds())),
        new Strategy("every under", g -> true, g -> new Bet(-over(g), g.underOdds())),
        new Strategy("unders, outdoor wind 15+ mph", g -> g.wind() != null && g.wind() >= 15
                && !"dome".equals(g.roof()) && !"closed".equals(g.roof()), g -> new Bet(-over(g), g.underOdds())),
        new Strategy("unders, primetime (20:00 ET+)", g -> g.gametime() != null && g.gametime().compareTo("20:00") >= 0,
                g -> new Bet(-over(g), g.underOdds())),
        new Strategy("unders, totals 50+", g -> g.total() >= 50, g -> new Bet(-over(g), g.underOdds())),
        new Strategy("moneyline: every favourite", g -> !g.homeMoneyline().equals(g.awayMoneyline()),
                g -> g.homeMoneyline() < g.awayMoneyline() ? new Bet(Integer.signum(g.margin()), g.homeMoneyline())
                        : new Bet(-Integer.signum(g.margin()), g.awayMoneyline())),
        new Strategy("moneyline: every underdog", g -> !g.homeMoneyline().equals(g.awayMoneyline()),
                g -> g.homeMoneyline() > g.awayMoneyline() ? new Bet(Integer.signum(g.margin()), g.homeMoneyline())
                        : new Bet(-Integer.signum(g.margin()), g.awayMoneyline())),
        new Strategy("moneyline: underdogs +200 or longer", g -> Math.max(g.homeMoneyline(), g.awayMoneyline()) >= 200,
                g -> g.homeMoneyline() > g.awayMoneyline() ? new Bet(Integer.signum(g.margin()), g.homeMoneyline())
                        : new Bet(-Integer.signum(g.margin()), g.awayMoneyline())),
        new Strategy("moneyline: favourites -300 or shorter", g -> Math.min(g.homeMoneyline(), g.awayMoneyline()) <= -300,
                g -> g.homeMoneyline() < g.awayMoneyline() ? new Bet(Integer.signum(g.margin()), g.homeMoneyline())
                        : new Bet(-Integer.signum(g.margin()), g.awayMoneyline()))
    );

    // ------------------------------------------------------------------ a public model

    /**
     * Team ratings from past games only: margin = r_home - r_away + h (h zero
     * at a neutral site), least squares with each game weighted
     * 0.5^(weeks ago / halfLife) and a ridge penalty pulling every rating to
     * zero. Teams by franchise, so a move does not reset a rating.
     */
    static Map<String, Double> ratings(List<NflverseGames.Game> past, int nowIndex, double halfLife, double ridge){
        Map<String, Integer> team = new LinkedHashMap<>();
        for(NflverseGames.Game g : past){
            team.computeIfAbsent(ScreenData.franchise(g.home()), k -> team.size());
            team.computeIfAbsent(ScreenData.franchise(g.away()), k -> team.size());
        }
        int k = team.size() + 1;           // ratings, then home field
        double[][] a = new double[k][k];
        double[] b = new double[k];
        double[] x = new double[k];
        for(int i = 0; i < past.size(); i++){
            NflverseGames.Game g = past.get(i);
            double w = Math.pow(0.5, (nowIndex - weekIndex(g)) / halfLife);
            java.util.Arrays.fill(x, 0);
            x[team.get(ScreenData.franchise(g.home()))] = 1;
            x[team.get(ScreenData.franchise(g.away()))] = -1;
            x[k - 1] = g.neutral() ? 0 : 1;
            for(int r = 0; r < k; r++){
                if(x[r] == 0){
                    continue;
                }
                b[r] += w * x[r] * g.margin();
                for(int c = 0; c < k; c++){
                    a[r][c] += w * x[r] * x[c];
                }
            }
        }
        for(int r = 0; r < k - 1; r++){
            a[r][r] += ridge;
        }
        a[k - 1][k - 1] += 1e-6;
        double[] beta = FaabDemand.solve(a, b);
        Map<String, Double> out = new HashMap<>();
        team.forEach((name, i) -> out.put(name, beta[i]));
        out.put("__home", beta[k - 1]);
        return out;
    }

    /** A running week count across seasons, 18 to a season, so decay reads in weeks. */
    static int weekIndex(NflverseGames.Game g){
        return Integer.parseInt(g.season()) * 18 + g.week();
    }

    /** The model's predicted home margin for every priced game from {@code from} on, each from games strictly before its week. */
    static Map<NflverseGames.Game, Double> walkForward(List<NflverseGames.Game> all, String from, double halfLife, double ridge){
        Map<NflverseGames.Game, Double> out = new LinkedHashMap<>();
        Map<Integer, List<NflverseGames.Game>> byWeek = new TreeMap<>();
        for(NflverseGames.Game g : all){
            if(g.season().compareTo(from) >= 0 && priced(g)){
                byWeek.computeIfAbsent(weekIndex(g), w -> new ArrayList<>()).add(g);
            }
        }
        for(Map.Entry<Integer, List<NflverseGames.Game>> e : byWeek.entrySet()){
            int now = e.getKey();
            List<NflverseGames.Game> past = new ArrayList<>();
            for(NflverseGames.Game g : all){
                if(g.played() && weekIndex(g) < now && weekIndex(g) >= now - 18 * 4){
                    past.add(g);
                }
            }
            Map<String, Double> r = ratings(past, now, halfLife, ridge);
            for(NflverseGames.Game g : e.getValue()){
                double home = r.getOrDefault(ScreenData.franchise(g.home()), 0.0);
                double away = r.getOrDefault(ScreenData.franchise(g.away()), 0.0);
                out.put(g, home - away + (g.neutral() ? 0 : r.get("__home")));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the report

    public static void main(String[] args) throws IOException {
        List<NflverseGames.Game> all = NflverseGames.games();
        StringBuilder out = new StringBuilder();
        out.append(String.format("MARKET EFFICIENCY  %s  (nflverse schedule: lines from 1999, prices from 2006, complete from 2010)%n", LocalDate.now()));

        // ---- 1. the line as a forecast
        out.append("\n== 1. THE LINE AS A FORECAST (regular season; spread from the home side, + = home favoured) ==\n");
        out.append(String.format("%-11s %6s %16s %9s %9s %16s %9s%n", "seasons", "games", "margin-spread", "sd", "MAE", "points-total", "MAE"));
        for(String[] era : new String[][]{{"1999", "2009"}, {"2010", "2017"}, {"2018", "2025"}}){
            List<Double> ds = new ArrayList<>();
            List<Double> ts = new ArrayList<>();
            for(NflverseGames.Game g : all){
                if(g.played() && g.spread() != null && g.total() != null && g.season().compareTo(era[0]) >= 0 && g.season().compareTo(era[1]) <= 0){
                    ds.add(g.margin() - g.spread());
                    ts.add(g.points() - g.total());
                }
            }
            double[] d = meanSd(ds);
            double[] t = meanSd(ts);
            out.append(String.format("%s-%s %6d %+8.2f +- %-5.2f %9.2f %9.2f %+8.2f +- %-5.2f %9.2f%n", era[0], era[1], ds.size(),
                    d[0], d[1] / Math.sqrt(ds.size()), d[1], mae(ds), t[0], t[1] / Math.sqrt(ts.size()), mae(ts)));
        }
        out.append("A line that is unbiased has margin-spread near zero; the sd is the noise any model must cut through (about 13 points).\n");

        // ---- 2. the obvious bets at the real prices
        List<NflverseGames.Game> priced = all.stream().filter(MarketEfficiency::priced)
                .filter(g -> g.season().compareTo("2010") >= 0 && g.season().compareTo("2025") <= 0).toList();
        out.append(String.format("%n== 2. WHAT THE OBVIOUS BETS RETURNED AT THE PRICES OFFERED, 2010-2025 (%d games) ==%n", priced.size()));
        out.append("return = mean profit per unit staked (pushes return 0); se its standard error. Break-even needs return 0;\n");
        out.append("at -110 that is 52.38% of decided bets.\n");
        out.append(String.format("%-38s %6s %8s %9s %9s   %-22s %s%n", "strategy", "bets", "win %", "return", "se", "2010-2017 return", "2018-2025 return"));
        for(Strategy s : STRATEGIES){
            double[] e = evaluate(priced, s);
            double[] early = evaluate(priced.stream().filter(g -> g.season().compareTo("2017") <= 0).toList(), s);
            double[] late = evaluate(priced.stream().filter(g -> g.season().compareTo("2018") >= 0).toList(), s);
            out.append(String.format("%-38s %6.0f %7.1f%% %+8.2f%% %8.2f%%   %+7.2f%% +- %-9.2f %+7.2f%% +- %.2f%n", s.name(), e[0],
                    100 * e[2] / Math.max(1, e[1]), 100 * e[3], 100 * e[4], 100 * early[3], 100 * early[4], 100 * late[3], 100 * late[4]));
        }
        out.append(String.format("%d strategies: about one would sit two standard errors from its true return by chance alone.%n", STRATEGIES.size()));

        // ---- 3. the moneyline as a probability
        out.append("\n== 3. THE MONEYLINE AS A PROBABILITY, margin removed (home share of the two implied probabilities) ==\n");
        double[] edges = {0, 0.2, 0.35, 0.5, 0.65, 0.8, 1.0001};
        out.append(String.format("%-12s %6s %12s %12s %8s%n", "implied", "games", "mean implied", "home won", "gap"));
        double brierMarket = 0;
        double brierHome = 0;
        double homeRate = 0;
        int n = 0;
        List<double[]> pw = new ArrayList<>();
        double overround = 0;
        for(NflverseGames.Game g : priced){
            if(g.margin() == 0){
                continue;
            }
            double ih = implied(g.homeMoneyline());
            double ia = implied(g.awayMoneyline());
            overround += ih + ia - 1;
            double p = ih / (ih + ia);
            double won = g.margin() > 0 ? 1 : 0;
            pw.add(new double[]{p, won});
            brierMarket += (p - won) * (p - won);
            homeRate += won;
            n++;
        }
        homeRate /= n;
        for(double[] r : pw){
            brierHome += (homeRate - r[1]) * (homeRate - r[1]);
        }
        for(int b = 0; b + 1 < edges.length; b++){
            double sumP = 0;
            double sumW = 0;
            int m = 0;
            for(double[] r : pw){
                if(r[0] >= edges[b] && r[0] < edges[b + 1]){
                    sumP += r[0];
                    sumW += r[1];
                    m++;
                }
            }
            if(m > 0){
                double seW = Math.sqrt((sumW / m) * (1 - sumW / m) / m);
                out.append(String.format("%.2f-%-7.2f %6d %11.1f%% %11.1f%% %+7.1f%%  (+- %.1f)%n", edges[b], Math.min(1, edges[b + 1]), m,
                        100 * sumP / m, 100 * sumW / m, 100 * (sumW - sumP) / m, 100 * seW));
            }
        }
        out.append(String.format("Brier score: market %.4f, 'home wins at the base rate' %.4f (lower is better). Mean margin taken by the book on the\n",
                brierMarket / n, brierHome / n));
        out.append(String.format("two sides of a moneyline: %.1f%% (overround).%n", 100 * overround / n));

        // ---- 4. a public model against the line
        out.append("\n== 4. A PUBLIC MODEL AGAINST THE LINE: weekly team ratings from past results only, 2010-2025 ==\n");
        Map<NflverseGames.Game, Double> model = walkForward(all, "2010", 10, 20);
        List<Double> errModel = new ArrayList<>();
        List<Double> errLine = new ArrayList<>();
        List<double[]> xy = new ArrayList<>();
        for(Map.Entry<NflverseGames.Game, Double> e : model.entrySet()){
            NflverseGames.Game g = e.getKey();
            if(g.season().compareTo("2025") > 0){
                continue;
            }
            errModel.add(g.margin() - e.getValue());
            errLine.add(g.margin() - g.spread());
            xy.add(new double[]{g.spread(), e.getValue() - g.spread(), g.margin() - g.spread()});
        }
        out.append(String.format("%d games. MAE predicting the home margin: line %.2f, model %.2f (half-life 10 weeks, ridge 20).%n",
                errLine.size(), mae(errLine), mae(errModel)));
        double[] fit = slope(xy);
        out.append(String.format("Does the model's disagreement predict what the line missed? (margin - line) on (model - line): slope %+.3f +- %.3f,%n",
                fit[0], fit[1]));
        out.append("where 0 means the line already holds everything the model knows and 1 would mean the line ignores it.\n");
        out.append(String.format("%-28s %6s %8s %9s %9s%n", "bet the model's side when", "bets", "win %", "return", "se"));
        for(double k : new double[]{1, 2, 3, 4, 5, 7}){
            final double kk = k;
            Strategy s = new Strategy("|model - line| >= " + k, g -> model.containsKey(g) && Math.abs(model.get(g) - g.spread()) >= kk,
                    g -> model.get(g) > g.spread() ? new Bet(homeCover(g), g.homeSpreadOdds()) : new Bet(-homeCover(g), g.awaySpreadOdds()));
            double[] e = evaluate(priced, s);
            out.append(String.format("%-28s %6.0f %7.1f%% %+8.2f%% %8.2f%%%n", s.name(), e[0], 100 * e[2] / Math.max(1, e[1]), 100 * e[3], 100 * e[4]));
        }

        out.append("\n== THE READING ==\n");
        out.append("A bet has an edge only if its return is above zero by more than its noise, in both eras, and not one of many\n");
        out.append("strategies tried until one looked good. Read the tables with that rule; the conclusions are in BETTING.md.\n");

        System.out.print(out);
        Path report = Path.of("data", "market-efficiency-" + LocalDate.now() + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }

    static double[] meanSd(List<Double> v){
        double mean = v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = v.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / Math.max(1, v.size() - 1);
        return new double[]{mean, Math.sqrt(var)};
    }

    static double mae(List<Double> v){
        return v.stream().mapToDouble(Math::abs).average().orElse(0);
    }

    /** Least-squares slope of the third column on the second, with its standard error. */
    static double[] slope(List<double[]> rows){
        double mx = 0;
        double my = 0;
        for(double[] r : rows){
            mx += r[1];
            my += r[2];
        }
        mx /= rows.size();
        my /= rows.size();
        double sxx = 0;
        double sxy = 0;
        for(double[] r : rows){
            sxx += (r[1] - mx) * (r[1] - mx);
            sxy += (r[1] - mx) * (r[2] - my);
        }
        double b = sxy / sxx;
        double rss = 0;
        for(double[] r : rows){
            double e = (r[2] - my) - b * (r[1] - mx);
            rss += e * e;
        }
        return new double[]{b, Math.sqrt(rss / (rows.size() - 2) / sxx)};
    }
}
