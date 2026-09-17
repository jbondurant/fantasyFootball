import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * WHO TO BID ON, AND HOW MUCH: THE RUN, AND THE DEMAND THAT CAN BE SEEN COMING.
 *
 * Justin, week 2: use the claim data to tweak the bid model, and note that most
 * of the money is spent on the Wednesday run, the first after the week's games.
 * Two things follow from that, both measured here on the five finished seasons
 * of this league's own claims (the same feed {@link WaiverLog} prints live):
 *
 *  1. THE RUN. The league runs daily waivers, but the runs are not alike. The
 *     harvest is cut by the weekday the claims cleared, and the share of
 *     contests, of dollars, the price quantiles and the win ladder are printed
 *     per weekday. The page bids from one pooled ladder; if the big run is a
 *     different market, the ladder for the run being bid into is the one to use.
 *
 *  2. THE DEMAND. FAAB-PLAN.md found the clearing price is driven by how many
 *     managers bid (r = +0.50) and by nothing observable about the man - but
 *     the features it tried were projections, which for a past week have no
 *     vintage. The stats feed does: a man's SNAP SHARE last week and its jump
 *     over the week before, his touches and his points, and whether a manager
 *     dropped him this week, are all on disk as the market saw them. A ridge
 *     logistic model of P(two or more bidders) on those, fitted leaving each
 *     season out, is scored on the held-out season (log loss against the base
 *     rate, and the price quantiles by predicted tercile - if the top tercile
 *     clears at a higher price than the bottom, demand was foreseeable). The
 *     win ladder is then stratified by predicted demand.
 *
 *  3. THIS WEEK. The wire's men in the wire's own order, each with his
 *     observable demand features and the big-run bid that wins 50 / 75 / 90
 *     per cent. The tercile ladders are used only if section 2 found the
 *     model separated from the base rate; otherwise every man bids into the
 *     one big-run ladder, and the demand column is printed for reading, not
 *     for pricing. What he is WORTH is TuesdaySwap's number, not this tool's;
 *     the two together are the bid.
 *
 * A contest whose top bid lost for a reason other than room is dropped, as
 * FaabBid drops it: that is not a revealed price. A claim that died for room is
 * still a bidder - the manager wanted him.
 *
 *   ./gradlew run -Pmain=FaabDemand
 */
public class FaabDemand {

    static final ZoneId LEAGUE_ZONE = ZoneId.of("America/New_York");
    static final double LAMBDA = 1.0;
    static final int[] LADDER = {0, 1, 2, 3, 5, 8, 13, 20, 35, 50};
    static final String[] FEATURES = {"snap share", "snap jump", "touches", "points", "dropped", "RB", "WR", "TE", "QB"};

    /** One settled contest with everything the market could have seen. */
    record Contest(String season, int leg, String playerID, Position position, long cleared,
                   int bidders, int price, double[] features) {
        boolean contested(){
            return bidders >= 2;
        }
    }

    /* ------------------------------------------------------------ pure pieces, tested */

    /** The weekday a run cleared, in the league's zone. */
    static DayOfWeek weekday(long cleared){
        return Instant.ofEpochMilli(cleared).atZone(LEAGUE_ZONE).getDayOfWeek();
    }

    /** Snap share from a week's stat line: offensive snaps over the team's, or NaN when either is missing. */
    static double snapShare(JsonObject stats){
        if(stats == null || !stats.has("off_snp") || !stats.has("tm_off_snp")
                || stats.get("off_snp").isJsonNull() || stats.get("tm_off_snp").isJsonNull()){
            return Double.NaN;
        }
        double team = stats.get("tm_off_snp").getAsDouble();
        return team <= 0 ? Double.NaN : stats.get("off_snp").getAsDouble() / team;
    }

    static double stat(JsonObject stats, String key){
        return stats != null && stats.has(key) && !stats.get(key).isJsonNull() ? stats.get(key).getAsDouble() : 0;
    }

    /**
     * The feature row: snap share last week, its jump over the week before
     * (missing weeks read as zero share), touches and league points last week,
     * dropped-this-leg, and the position as four indicators.
     */
    static double[] features(JsonObject lastWeek, JsonObject weekBefore, double pointsLastWeek,
                             boolean dropped, Position position){
        double share = snapShare(lastWeek);
        double before = snapShare(weekBefore);
        double s = Double.isNaN(share) ? 0 : share;
        double b = Double.isNaN(before) ? 0 : before;
        return new double[]{s, s - b, stat(lastWeek, "rec_tgt") + stat(lastWeek, "rush_att"), pointsLastWeek,
                dropped ? 1 : 0, position == Position.RB ? 1 : 0, position == Position.WR ? 1 : 0,
                position == Position.TE ? 1 : 0, position == Position.QB ? 1 : 0};
    }

    static double sigmoid(double z){
        return 1 / (1 + Math.exp(-z));
    }

    /**
     * Ridge logistic regression by Newton's method: beta = [intercept, b1..bp],
     * the intercept unpenalised, features already standardised. Ten steps are
     * plenty at this size; the penalty keeps the Hessian invertible.
     */
    static double[] logistic(List<double[]> x, boolean[] y, double lambda){
        int p = x.isEmpty() ? 0 : x.get(0).length;
        int n = p + 1;
        double[] beta = new double[n];
        for(int step = 0; step < 10; step++){
            double[][] h = new double[n][n];
            double[] g = new double[n];
            for(int r = 0; r < x.size(); r++){
                double[] row = new double[n];
                row[0] = 1;
                System.arraycopy(x.get(r), 0, row, 1, p);
                double z = 0;
                for(int i = 0; i < n; i++){
                    z += beta[i] * row[i];
                }
                double mu = sigmoid(z);
                double w = mu * (1 - mu);
                for(int i = 0; i < n; i++){
                    g[i] += ((y[r] ? 1 : 0) - mu) * row[i];
                    for(int j = 0; j < n; j++){
                        h[i][j] += w * row[i] * row[j];
                    }
                }
            }
            for(int i = 1; i < n; i++){
                g[i] -= lambda * beta[i];
                h[i][i] += lambda;
            }
            double[] delta = solve(h, g);
            double norm = 0;
            for(int i = 0; i < n; i++){
                beta[i] += delta[i];
                norm += Math.abs(delta[i]);
            }
            if(norm < 1e-8){
                break;
            }
        }
        return beta;
    }

    /** Gaussian elimination with partial pivoting; a singular pivot contributes zero. */
    static double[] solve(double[][] a0, double[] b0){
        int n = b0.length;
        double[][] a = new double[n][];
        for(int i = 0; i < n; i++){
            a[i] = a0[i].clone();
        }
        double[] b = b0.clone();
        for(int col = 0; col < n; col++){
            int pivot = col;
            for(int r = col + 1; r < n; r++){
                if(Math.abs(a[r][col]) > Math.abs(a[pivot][col])){
                    pivot = r;
                }
            }
            double[] t = a[col]; a[col] = a[pivot]; a[pivot] = t;
            double tb = b[col]; b[col] = b[pivot]; b[pivot] = tb;
            if(Math.abs(a[col][col]) < 1e-12){
                continue;
            }
            for(int r = 0; r < n; r++){
                if(r == col){
                    continue;
                }
                double f = a[r][col] / a[col][col];
                for(int c = col; c < n; c++){
                    a[r][c] -= f * a[col][c];
                }
                b[r] -= f * b[col];
            }
        }
        double[] out = new double[n];
        for(int i = 0; i < n; i++){
            out[i] = Math.abs(a[i][i]) < 1e-12 ? 0 : b[i] / a[i][i];
        }
        return out;
    }

    static double predict(double[] beta, double[] z){
        double s = beta[0];
        for(int j = 0; j < z.length; j++){
            s += beta[j + 1] * z[j];
        }
        return sigmoid(s);
    }

    /** Mean log loss of predicted probabilities against outcomes. */
    static double logLoss(List<Double> p, List<Boolean> y){
        double sum = 0;
        for(int i = 0; i < p.size(); i++){
            double q = Math.min(1 - 1e-9, Math.max(1e-9, p.get(i)));
            sum -= y.get(i) ? Math.log(q) : Math.log(1 - q);
        }
        return p.isEmpty() ? Double.NaN : sum / p.size();
    }

    /** The share of clearing prices a bid beats, ties at half. */
    static double winChance(List<Integer> prices, int bid){
        if(prices.isEmpty()){
            return Double.NaN;
        }
        double beaten = 0;
        for(int price : prices){
            beaten += bid > price ? 1 : bid == price ? 0.5 : 0;
        }
        return beaten / prices.size();
    }

    static int quantile(List<Integer> prices, double q){
        if(prices.isEmpty()){
            return 0;
        }
        List<Integer> sorted = new ArrayList<>(prices);
        Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size() - 1, (int) (q * sorted.size())));
    }

    /** The smallest bid on the ladder that reaches the target chance, or the ladder's top. */
    static int bidFor(List<Integer> prices, double target){
        for(int bid : LADDER){
            if(winChance(prices, bid) >= target){
                return bid;
            }
        }
        return LADDER[LADDER.length - 1];
    }

    /* ------------------------------------------------------------ the harvest */

    /** The men dropped by any transaction in a week's feed, by the moment of the drop. */
    static Map<String, List<Long>> dropsIn(String body){
        Map<String, List<Long>> out = new HashMap<>();
        for(JsonElement element : JsonParser.parseString(body).getAsJsonArray()){
            JsonObject row = element.getAsJsonObject();
            if(!row.has("drops") || !row.get("drops").isJsonObject() || !"complete".equals(
                    row.has("status") && !row.get("status").isJsonNull() ? row.get("status").getAsString() : "")){
                continue;
            }
            long at = row.has("status_updated") && !row.get("status_updated").isJsonNull()
                    ? row.get("status_updated").getAsLong() : 0;
            for(String id : row.getAsJsonObject("drops").keySet()){
                out.computeIfAbsent(id, k -> new ArrayList<>()).add(at);
            }
        }
        return out;
    }

    /** Every settled contest of one season with its features, from the frozen feeds. */
    static List<Contest> harvest(String season, String leagueID, java.util.function.IntFunction<String> transactions,
                                 java.util.function.IntFunction<JsonObject> actuals,
                                 java.util.function.IntFunction<Map<String, Double>> points){
        List<Contest> out = new ArrayList<>();
        Map<Integer, String> bodies = new HashMap<>();
        Map<Integer, Map<String, List<Long>>> drops = new HashMap<>();
        for(int leg = 1; leg <= 18; leg++){
            String body = transactions.apply(leg);
            bodies.put(leg, body);
            drops.put(leg, dropsIn(body));
        }
        Map<Integer, JsonObject> weeks = new HashMap<>();
        Map<Integer, Map<String, Double>> scored = new HashMap<>();
        for(int leg = 1; leg <= 18; leg++){
            List<WaiverLog.Claim> claims = WaiverLog.claims(bodies.get(leg), leg);
            for(List<WaiverLog.Claim> contest : WaiverLog.contests(claims).values()){
                WaiverLog.Claim winner = null;
                int highestReal = Integer.MIN_VALUE;
                Set<Integer> rosters = new TreeSet<>();
                for(WaiverLog.Claim c : contest){
                    rosters.add(c.rosterID());
                    if(c.won()){
                        winner = c;
                    }
                    else if(!WaiverLog.diedForRoom(c)){
                        highestReal = Math.max(highestReal, c.bid());
                    }
                }
                if(winner == null || winner.bid() < highestReal){
                    continue;   // no revealed price
                }
                Player player = Player.getPlayerFromSIDV2(winner.playerID());
                Position position = player == null ? null : player.position;
                if(position == null || position == Position.DEF){
                    continue;   // defences are streamed on the matchup, a different market
                }
                int last = leg - 1;
                JsonObject lastWeek = last >= 1 ? weeks.computeIfAbsent(last, actuals::apply) : null;
                JsonObject before = last >= 2 ? weeks.computeIfAbsent(last - 1, actuals::apply) : null;
                Map<String, Double> lastPoints = last >= 1 ? scored.computeIfAbsent(last, points::apply) : Map.of();
                JsonObject lastLine = lastWeek != null && lastWeek.has(winner.playerID()) && lastWeek.get(winner.playerID()).isJsonObject()
                        ? lastWeek.getAsJsonObject(winner.playerID()) : null;
                JsonObject beforeLine = before != null && before.has(winner.playerID()) && before.get(winner.playerID()).isJsonObject()
                        ? before.getAsJsonObject(winner.playerID()) : null;
                boolean dropped = false;
                for(int l = Math.max(1, leg - 1); l <= leg; l++){
                    for(long at : drops.get(l).getOrDefault(winner.playerID(), List.of())){
                        if(at < winner.cleared() && at > winner.cleared() - 8L * 24 * 3600 * 1000){
                            dropped = true;
                        }
                    }
                }
                out.add(new Contest(season, leg, winner.playerID(), position, winner.cleared(), rosters.size(),
                        winner.bid(), features(lastLine, beforeLine, lastPoints.getOrDefault(winner.playerID(), 0.0),
                                dropped, position)));
            }
        }
        return out;
    }

    /* ------------------------------------------------------------ the report */

    public static void main(String[] args) throws IOException {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        List<Contest> all = new ArrayList<>();
        List<String> seasons = new ArrayList<>();
        for(LeagueTransactions.Year year : LeagueTransactions.completedSeasons(configuration.getLeagueID())){
            String season = year.season();
            List<Contest> found = harvest(season, year.leagueID(),
                    leg -> LeagueTransactions.transactionsRaw(year.leagueID(), leg),
                    week -> JsonParser.parseString(WeeklyActuals.raw(season, week)).getAsJsonObject(),
                    week -> LeagueActuals.leagueWeeklyPoints(season, week));
            all.addAll(found);
            seasons.add(season);
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format("FAAB DEMAND  %s  (%d settled contests over %d seasons %s-%s; skill positions; features as the market saw them)%n",
                LocalDate.now(), all.size(), seasons.size(), seasons.get(0), seasons.get(seasons.size() - 1)));

        // ---------------------------------------------------------------- 1. the run
        out.append("\n== 1. THE RUN: contests and dollars by the weekday they cleared (league zone), regular-season legs 2-18 ==\n");
        Map<DayOfWeek, List<Contest>> byDay = new TreeMap<>();
        for(Contest c : all){
            if(c.leg() >= 2){
                byDay.computeIfAbsent(weekday(c.cleared()), k -> new ArrayList<>()).add(c);
            }
        }
        int totalDollars = 0;
        int totalContests = 0;
        for(List<Contest> day : byDay.values()){
            for(Contest c : day){
                totalDollars += c.price();
                totalContests++;
            }
        }
        out.append(String.format("%-10s %8s %7s %8s %7s %9s %7s %7s %7s   %s%n", "day", "contests", "share", "dollars", "share",
                "contested", "median", "p75", "p90", "P(win) at $1 $3 $5 $8 $13 $20"));
        DayOfWeek bigRun = null;
        int bigDollars = -1;
        for(Map.Entry<DayOfWeek, List<Contest>> e : byDay.entrySet()){
            List<Integer> prices = new ArrayList<>();
            int dollars = 0;
            int contested = 0;
            for(Contest c : e.getValue()){
                prices.add(c.price());
                dollars += c.price();
                if(c.contested()){
                    contested++;
                }
            }
            if(dollars > bigDollars){
                bigDollars = dollars;
                bigRun = e.getKey();
            }
            out.append(String.format("%-10s %8d %6.0f%% %8d %6.0f%% %8.0f%% %7d %7d %7d   ", e.getKey(), prices.size(),
                    100.0 * prices.size() / totalContests, dollars, 100.0 * dollars / totalDollars,
                    100.0 * contested / prices.size(), quantile(prices, 0.5), quantile(prices, 0.75), quantile(prices, 0.9)));
            for(int bid : new int[]{1, 3, 5, 8, 13, 20}){
                out.append(String.format(" %3.0f%%", 100 * winChance(prices, bid)));
            }
            out.append('\n');
        }
        out.append(String.format("the big run is %s: the ladder to bid into on that day is its row, not the pooled one the page uses today.%n", bigRun));

        // ---------------------------------------------------------------- 2. the demand
        out.append("\n== 2. THE DEMAND: P(two or more bidders) from what the market could see, leave-one-season-out ==\n");
        List<Contest> pool = new ArrayList<>();
        for(Contest c : all){
            if(c.leg() >= 2){
                pool.add(c);
            }
        }
        double base = 0;
        for(Contest c : pool){
            base += c.contested() ? 1 : 0;
        }
        base /= Math.max(1, pool.size());
        // single-feature correlations with bidders and with price
        out.append(String.format("%-12s %10s %10s%n", "feature", "r bidders", "r price"));
        for(int f = 0; f < FEATURES.length; f++){
            List<double[]> vb = new ArrayList<>();
            List<double[]> vp = new ArrayList<>();
            for(Contest c : pool){
                vb.add(new double[]{c.features()[f], c.bidders()});
                vp.add(new double[]{c.features()[f], c.price()});
            }
            out.append(String.format("%-12s %+10.3f %+10.3f%n", FEATURES[f], WeeklyFeedAudit.fit(vb).r(), WeeklyFeedAudit.fit(vp).r()));
        }
        List<Double> predicted = new ArrayList<>();
        List<Boolean> outcome = new ArrayList<>();
        Map<String, List<Double>> lossBySeason = new TreeMap<>();
        Map<String, List<Double>> baseBySeason = new TreeMap<>();
        Map<Contest, Double> pOf = new HashMap<>();
        for(String held : seasons){
            List<double[]> trainX = new ArrayList<>();
            List<Boolean> trainY = new ArrayList<>();
            for(Contest c : pool){
                if(!c.season().equals(held)){
                    trainX.add(c.features());
                    trainY.add(c.contested());
                }
            }
            if(trainX.size() < 50){
                continue;
            }
            double[][] m = UsageSignal.moments(trainX, FEATURES.length);
            List<double[]> z = new ArrayList<>();
            boolean[] y = new boolean[trainX.size()];
            for(int i = 0; i < y.length; i++){
                z.add(UsageSignal.standardise(trainX.get(i), m));
                y[i] = trainY.get(i);
            }
            double trainBase = 0;
            for(boolean b : y){
                trainBase += b ? 1 : 0;
            }
            trainBase /= y.length;
            double[] beta = logistic(z, y, LAMBDA);
            for(Contest c : pool){
                if(c.season().equals(held)){
                    double p = predict(beta, UsageSignal.standardise(c.features(), m));
                    pOf.put(c, p);
                    predicted.add(p);
                    outcome.add(c.contested());
                    lossBySeason.computeIfAbsent(held, k -> new ArrayList<>()).add(logLoss(List.of(p), List.of(c.contested())));
                    baseBySeason.computeIfAbsent(held, k -> new ArrayList<>()).add(logLoss(List.of(trainBase), List.of(c.contested())));
                }
            }
        }
        double[] loss = RosBands.overSeasons(lossBySeason);
        double[] baseLoss = RosBands.overSeasons(baseBySeason);
        Map<String, List<Double>> gain = new TreeMap<>();
        for(String s : lossBySeason.keySet()){
            List<Double> d = new ArrayList<>();
            for(int i = 0; i < lossBySeason.get(s).size(); i++){
                d.add(baseBySeason.get(s).get(i) - lossBySeason.get(s).get(i));
            }
            gain.put(s, d);
        }
        double[] g = RosBands.overSeasons(gain);
        out.append(String.format("%ncontested base rate %.0f%%; held-out log loss %.3f vs base rate's %.3f: gain %+.3f +- %.3f over %d seasons%s%n",
                100 * base, loss[0], baseLoss[0], g[0], g[1], (int) g[2], g[0] > 2 * g[1] ? "  <- real" : "  <- not separated"));
        // terciles of predicted demand: outcomes and prices
        List<Contest> ranked = new ArrayList<>(pOf.keySet());
        ranked.sort(Comparator.comparingDouble(pOf::get));
        int third = ranked.size() / 3;
        List<List<Contest>> terciles = List.of(ranked.subList(0, third), ranked.subList(third, 2 * third), ranked.subList(2 * third, ranked.size()));
        String[] labels = {"low demand", "mid", "high demand"};
        out.append(String.format("%-12s %8s %9s %9s %7s %7s %7s   %s%n", "predicted", "contests", "P(cont.)", "mean p", "median", "p75", "p90", "P(win) at $1 $3 $5 $8 $13 $20"));
        List<List<Integer>> tercilePrices = new ArrayList<>();
        for(int t = 0; t < 3; t++){
            List<Integer> prices = new ArrayList<>();
            double contested = 0;
            double meanP = 0;
            for(Contest c : terciles.get(t)){
                prices.add(c.price());
                contested += c.contested() ? 1 : 0;
                meanP += pOf.get(c);
            }
            tercilePrices.add(prices);
            int n = Math.max(1, prices.size());
            out.append(String.format("%-12s %8d %8.0f%% %8.0f%% %7d %7d %7d   ", labels[t], prices.size(), 100 * contested / n,
                    100 * meanP / n, quantile(prices, 0.5), quantile(prices, 0.75), quantile(prices, 0.9)));
            for(int bid : new int[]{1, 3, 5, 8, 13, 20}){
                out.append(String.format(" %3.0f%%", 100 * winChance(prices, bid)));
            }
            out.append('\n');
        }
        // the fit on everything, for section 3
        List<double[]> allX = new ArrayList<>();
        boolean[] allY = new boolean[pool.size()];
        for(int i = 0; i < pool.size(); i++){
            allX.add(pool.get(i).features());
            allY[i] = pool.get(i).contested();
        }
        double[][] mAll = UsageSignal.moments(allX, FEATURES.length);
        List<double[]> zAll = new ArrayList<>();
        for(double[] x : allX){
            zAll.add(UsageSignal.standardise(x, mAll));
        }
        double[] betaAll = logistic(zAll, allY, LAMBDA);
        out.append("all-season fit, log-odds per sd:");
        for(int f = 0; f < FEATURES.length; f++){
            out.append(String.format(" %s %+.2f", FEATURES[f], betaAll[f + 1]));
        }
        out.append('\n');
        double cutLow = pOf.isEmpty() ? 0 : pOf.get(ranked.get(Math.min(ranked.size() - 1, third)));
        double cutHigh = pOf.isEmpty() ? 0 : pOf.get(ranked.get(Math.min(ranked.size() - 1, 2 * third)));
        boolean earned = g[0] > 2 * g[1];
        // big-run ladder by tercile
        out.append(String.format("%non the big run (%s) only, by predicted tercile:%n", bigRun));
        List<List<Integer>> bigTercilePrices = new ArrayList<>();
        for(int t = 0; t < 3; t++){
            List<Integer> prices = new ArrayList<>();
            for(Contest c : terciles.get(t)){
                if(weekday(c.cleared()) == bigRun){
                    prices.add(c.price());
                }
            }
            bigTercilePrices.add(prices);
            out.append(String.format("%-12s %8d %27s %7d %7d %7d   ", labels[t], prices.size(), "", quantile(prices, 0.5), quantile(prices, 0.75), quantile(prices, 0.9)));
            for(int bid : new int[]{1, 3, 5, 8, 13, 20}){
                out.append(String.format(" %3.0f%%", 100 * winChance(prices, bid)));
            }
            out.append('\n');
        }

        // ---------------------------------------------------------------- 3. this week
        String season = LeagueWeek.season();
        int week = LeagueWeek.week();
        int last = week - 1;
        List<Integer> bigRunAll = new ArrayList<>();
        for(Contest c : pool){
            if(weekday(c.cleared()) == bigRun){
                bigRunAll.add(c.price());
            }
        }
        out.append(String.format("%n== 3. THIS WEEK: the wire's men and the big-run bid that wins 50 / 75 / 90%% (%s) ==%n",
                earned ? "in each man's predicted-demand tercile" : "one ladder for all: section 2 did not earn the terciles"));
        if(last < 1){
            out.append("no played week yet\n");
        }
        else{
            JsonObject lastWeek = JsonParser.parseString(LeagueWeek.actualsBody(season, last)).getAsJsonObject();
            JsonObject before = last >= 2 ? JsonParser.parseString(LeagueWeek.actualsBody(season, last - 1)).getAsJsonObject() : null;
            Map<String, Double> lastPoints = LeagueWeek.actualSoFar(season, last);
            Map<String, List<Long>> dropped = new HashMap<>();
            for(int l = Math.max(1, week - 1); l <= week; l++){
                dropsIn(LeagueWeek.transactions(configuration.getLeagueID(), l)).forEach((id, at) -> dropped.computeIfAbsent(id, k -> new ArrayList<>()).addAll(at));
            }
            Map<String, Double> projections = ProjectionSources.resolve("sleeper");
            Set<String> owned = LeagueWeek.rostered(configuration);
            record Candidate(String name, Position position, double projection, double p, double[] f){}
            List<Candidate> candidates = new ArrayList<>();
            for(Map.Entry<String, Double> e : projections.entrySet()){
                if(owned.contains(e.getKey())){
                    continue;
                }
                Player player = Player.getPlayerFromSIDV2(e.getKey());
                if(player == null || player.position == null || player.position == Position.DEF || player.team == null){
                    continue;
                }
                JsonObject line = lastWeek.has(e.getKey()) && lastWeek.get(e.getKey()).isJsonObject() ? lastWeek.getAsJsonObject(e.getKey()) : null;
                JsonObject beforeLine = before != null && before.has(e.getKey()) && before.get(e.getKey()).isJsonObject() ? before.getAsJsonObject(e.getKey()) : null;
                double[] f = features(line, beforeLine, lastPoints.getOrDefault(e.getKey(), 0.0),
                        dropped.containsKey(e.getKey()), player.position);
                candidates.add(new Candidate(player.firstName + " " + player.lastName, player.position, e.getValue(),
                        predict(betaAll, UsageSignal.standardise(f, mAll)), f));
            }
            candidates.sort(Comparator.comparingDouble((Candidate c) -> -c.projection()));
            out.append(String.format("%-22s %-3s %7s %8s %-12s %5s %5s %6s %6s %7s   %s%n", "player", "pos", "season", "P(cont.)", "tercile",
                    "share", "jump", "touch", "pts", "dropped", "bid to win 50 / 75 / 90 on the big run"));
            int shown = 0;
            for(Candidate c : candidates){
                if(shown++ >= 25){
                    break;
                }
                int t = c.p() < cutLow ? 0 : c.p() < cutHigh ? 1 : 2;
                List<Integer> prices = earned ? bigTercilePrices.get(t) : bigRunAll;
                out.append(String.format("%-22s %-3s %7.1f %7.0f%% %-12s %4.0f%% %+4.0f%% %6.1f %6.1f %7s   $%d / $%d / $%d%n",
                        c.name(), c.position(), c.projection(), 100 * c.p(), earned ? labels[t] : "-", 100 * c.f()[0], 100 * c.f()[1],
                        c.f()[2], c.f()[3], c.f()[4] > 0 ? "yes" : "", bidFor(prices, 0.5), bidFor(prices, 0.75), bidFor(prices, 0.9)));
            }
            out.append("the twenty-five free skill men by season projection; worth is TuesdaySwap's column, and the bid is the smallest\n");
            out.append("on the ladder that wins the share shown against the big run's prices. A bid above his worth is a loss.\n");
        }

        System.out.print(out);
        Path report = Path.of("data", "faab-demand-" + LocalDate.now() + ".txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }
}
