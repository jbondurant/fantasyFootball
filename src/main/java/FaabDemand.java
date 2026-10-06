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
 *  2. THE DEMAND, OVER THE WHOLE WIRE. Justin's objection to a first draft
 *     that modelled only the men somebody had already claimed: on any run
 *     ninety-five men in a hundred draw no bid at all, so the question is who
 *     gets a bid, and the answer has to be attached to what happened to him.
 *     So the population is rebuilt: at every big run, every skill man who
 *     played the week just ended and was on no roster (the matchups feed
 *     carries each week's rosters), plus the men claimed. The label is whether
 *     anyone bid, how many, and the price. The features are what the market
 *     could see, vintage-free: his snap share that week and its jump over the
 *     week before (the trace of an injury ahead of him), his touches and league
 *     points that week and per game so far, whether he was dropped that week,
 *     his preseason ADP (the FFC board, undrafted at the tail), position, and
 *     whether he is the next man up behind a teammate who went down in that
 *     week ({@link NextManUp}, from the team each man played for that week).
 *     A ridge logistic model of P(any bid) is fitted leaving each season out
 *     and scored on the held-out season; a claim is then read by decile.
 *
 *  2a. THE NEXT MAN UP, with and without. The first live run missed Rashod
 *     Bateman, promoted by a teammate's early exit in week 1 (TRAPS #148);
 *     the feature is scored leave-one-season-out against the same model
 *     without it, and this season's cleared runs - in no fit - are ranked
 *     both ways, man by man.
 *
 *  2b. THE PRICE, BY QUALITY. Justin's second objection: contested men vary
 *     wildly in quality, so one ladder over them mixes a $76 starter with a $2
 *     handcuff. Among the claimed, the clearing price is put against the same
 *     quality features - ADP band and points per game so far - so the ladder
 *     is read in the band the man is in, if the bands separate.
 *
 *  3. THIS WEEK. Every free skill man who played, with his P(any bid) from the
 *     all-season fit, his features, and the big-run bid that wins 50 / 75 / 90
 *     per cent in his quality band (if 2b earned bands) or on the whole run.
 *     What he is WORTH is TuesdaySwap's number; the two together are the bid.
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
    static final String[] FEATURES = {"snap share", "snap jump", "touches", "points", "ppg so far", "log ADP", "dropped", "RB", "WR", "TE", "QB",
            "next man up"};
    /** The columns of the model before the teammate feature, for the with-and-without test. */
    static final int[] WITHOUT_NEXT_UP = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    /** The ADP rank an undrafted man is given: past the board, the same for every season. */
    static final double UNDRAFTED_ADP = 300;

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
     * points per game so far, log of preseason ADP (undrafted at the tail),
     * dropped-this-week, the position as four indicators, and whether he is
     * the next man up behind a teammate who went down in the week just played
     * ({@link NextManUp}).
     */
    static double[] features(JsonObject lastWeek, JsonObject weekBefore, double pointsLastWeek, double ppgSoFar,
                             double adp, boolean dropped, Position position, boolean nextUp){
        double share = snapShare(lastWeek);
        double before = snapShare(weekBefore);
        double s = Double.isNaN(share) ? 0 : share;
        double b = Double.isNaN(before) ? 0 : before;
        return new double[]{s, s - b, stat(lastWeek, "rec_tgt") + stat(lastWeek, "rush_att"), pointsLastWeek,
                ppgSoFar, Math.log(Math.max(1, adp)), dropped ? 1 : 0,
                position == Position.RB ? 1 : 0, position == Position.WR ? 1 : 0,
                position == Position.TE ? 1 : 0, position == Position.QB ? 1 : 0, nextUp ? 1 : 0};
    }

    /** A feature row reduced to the given columns. */
    static double[] pick(double[] row, int[] columns){
        double[] out = new double[columns.length];
        for(int i = 0; i < columns.length; i++){
            out[i] = row[columns[i]];
        }
        return out;
    }

    /**
     * Leave-one-season-out on the given columns: each season's men predicted
     * by a fit on the other seasons. Seasons with fewer than a hundred
     * training rows are left out, as in the main fit.
     */
    static Map<WireMan, Double> heldOut(List<WireMan> wire, List<String> seasons, int[] columns){
        Map<WireMan, Double> out = new HashMap<>();
        for(String held : seasons){
            List<double[]> trainX = new ArrayList<>();
            List<Boolean> trainY = new ArrayList<>();
            for(WireMan m : wire){
                if(!m.season().equals(held)){
                    trainX.add(pick(m.features(), columns));
                    trainY.add(m.claimed());
                }
            }
            if(trainX.size() < 100){
                continue;
            }
            double[][] mo = UsageSignal.moments(trainX, columns.length);
            List<double[]> z = new ArrayList<>();
            boolean[] y = new boolean[trainX.size()];
            for(int i = 0; i < y.length; i++){
                z.add(standardised(trainX.get(i), mo));
                y[i] = trainY.get(i);
            }
            double[] beta = logistic(z, y, LAMBDA);
            for(WireMan m : wire){
                if(m.season().equals(held)){
                    out.put(m, predict(beta, standardised(pick(m.features(), columns), mo)));
                }
            }
        }
        return out;
    }

    /** A fit on every man given, on the given columns: {beta, moments}, for predicting men outside it. */
    record Fit(double[] beta, double[][] moments, int[] columns) {
        double p(double[] features){
            return predict(beta, standardised(pick(features, columns), moments));
        }
    }

    static Fit fit(List<WireMan> wire, int[] columns){
        List<double[]> x = new ArrayList<>();
        boolean[] y = new boolean[wire.size()];
        for(int i = 0; i < wire.size(); i++){
            x.add(pick(wire.get(i).features(), columns));
            y[i] = wire.get(i).claimed();
        }
        double[][] mo = UsageSignal.moments(x, columns.length);
        List<double[]> z = new ArrayList<>();
        for(double[] row : x){
            z.add(standardised(row, mo));
        }
        return new Fit(logistic(z, y, LAMBDA), mo, columns);
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

    /** Standardised and clipped at three sd: one extreme week must not push the logit off the fitted range. */
    static double[] standardised(double[] row, double[][] moments){
        double[] z = UsageSignal.standardise(row, moments);
        for(int j = 0; j < z.length; j++){
            z[j] = Math.max(-3, Math.min(3, z[j]));
        }
        return z;
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

    static int[] allColumns(){
        int[] all = new int[FEATURES.length];
        for(int i = 0; i < all.length; i++){
            all[i] = i;
        }
        return all;
    }

    static String nameOf(String id){
        Player player = Player.getPlayerFromSIDV2(id);
        return player == null ? id : player.firstName + " " + player.lastName;
    }

    /** One free man at one big run: what the market could see, and what it did. */
    record WireMan(String season, int weekPlayed, String playerID, Position position, double adp,
                   int bidders, int price, boolean priced, double[] features) {
        boolean claimed(){
            return bidders >= 1;
        }
    }

    /** A settled contest for the run table: when it cleared, how many bid, what it cost. */
    record Contest(String season, int leg, long cleared, int bidders, int price) {
        boolean contested(){
            return bidders >= 2;
        }
    }

    /** Every man on a roster in a week, from the matchups feed. */
    static Set<String> rosteredIn(String matchupsBody){
        Set<String> out = new java.util.HashSet<>();
        for(JsonElement e : JsonParser.parseString(matchupsBody).getAsJsonArray()){
            JsonObject row = e.getAsJsonObject();
            if(row.has("players") && row.get("players").isJsonArray()){
                for(JsonElement id : row.getAsJsonArray("players")){
                    if(!id.isJsonNull()){
                        out.add(id.getAsString());
                    }
                }
            }
        }
        return out;
    }

    /**
     * Every settled contest of one season (for the run table), and every free
     * man at every big run with his label (for the demand model). The week just
     * played at a run is the highest leg among its claims minus one; the free
     * pool is every skill man with a stat row that week who was on no roster in
     * that week's matchups, plus the men claimed (dropped mid-week, or absent
     * from the stat feed).
     */
    static void harvest(String season, java.util.function.IntFunction<String> transactions,
                        java.util.function.IntFunction<String> matchups,
                        java.util.function.IntFunction<JsonObject> actuals,
                        java.util.function.IntFunction<Map<String, Double>> points,
                        java.util.function.IntFunction<Map<String, NextManUp.Line>> teamLines, int lastPlayed,
                        Map<String, Double> adpOf, List<Contest> contests, List<WireMan> wire){
        Map<Integer, Map<String, List<Long>>> drops = new HashMap<>();
        List<WaiverLog.Claim> claims = new ArrayList<>();
        for(int leg = 1; leg <= 18; leg++){
            String body = transactions.apply(leg);
            drops.put(leg, dropsIn(body));
            claims.addAll(WaiverLog.claims(body, leg));
        }
        // settled contests, and the claims by clearing day
        record Settled(String playerID, int leg, long cleared, int bidders, int price, boolean priced){}
        Map<String, List<Settled>> byDay = new TreeMap<>();
        for(List<WaiverLog.Claim> contest : WaiverLog.contests(claims).values()){
            WaiverLog.Claim winner = null;
            int highestReal = Integer.MIN_VALUE;
            int maxLeg = 0;
            Set<Integer> rosters = new TreeSet<>();
            for(WaiverLog.Claim c : contest){
                rosters.add(c.rosterID());
                maxLeg = Math.max(maxLeg, c.week());
                if(c.won()){
                    winner = c;
                }
                else if(!WaiverLog.diedForRoom(c)){
                    highestReal = Math.max(highestReal, c.bid());
                }
            }
            WaiverLog.Claim any = contest.get(0);
            Player player = Player.getPlayerFromSIDV2(any.playerID());
            if(player == null || player.position == null || player.position == Position.DEF){
                continue;
            }
            boolean priced = winner != null && winner.bid() >= highestReal;
            if(priced){
                contests.add(new Contest(season, maxLeg, winner.cleared(), rosters.size(), winner.bid()));
            }
            String day = Instant.ofEpochMilli(any.cleared()).atZone(LEAGUE_ZONE).toLocalDate().toString();
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(new Settled(any.playerID(), maxLeg, any.cleared(),
                    rosters.size(), priced ? winner.bid() : -1, priced));
        }
        // the big-run days: one per week, the day with the most claims among those in legs 2-18
        Map<Integer, String> bigDayOfLeg = new TreeMap<>();
        Map<Integer, Integer> bigCount = new TreeMap<>();
        for(Map.Entry<String, List<Settled>> e : byDay.entrySet()){
            int leg = 0;
            for(Settled s : e.getValue()){
                leg = Math.max(leg, s.leg());
            }
            if(leg < 2 || e.getValue().size() <= bigCount.getOrDefault(leg, 0)){
                continue;
            }
            bigCount.put(leg, e.getValue().size());
            bigDayOfLeg.put(leg, e.getKey());
        }
        Map<Integer, JsonObject> weeks = new HashMap<>();
        Map<Integer, Map<String, Double>> scored = new HashMap<>();
        Map<Integer, Map<String, NextManUp.Line>> teamWeeks = new HashMap<>();
        for(Map.Entry<Integer, String> e : bigDayOfLeg.entrySet()){
            int leg = e.getKey();
            int played = leg - 1;
            if(played > lastPlayed){
                continue;       // a run in a week whose games are not all played yet is not the big run
            }
            for(int k = 1; k <= played; k++){
                teamWeeks.computeIfAbsent(k, teamLines::apply);
            }
            Map<String, String> promoted = NextManUp.promoted(teamWeeks, played, adpOf);
            JsonObject lastWeek = weeks.computeIfAbsent(played, actuals::apply);
            JsonObject before = played >= 2 ? weeks.computeIfAbsent(played - 1, actuals::apply) : null;
            Map<String, Double> lastPoints = scored.computeIfAbsent(played, points::apply);
            Set<String> rostered = rosteredIn(matchups.apply(played));
            Map<String, Settled> claimedHere = new HashMap<>();
            for(Settled s : byDay.get(e.getValue())){
                claimedHere.put(s.playerID(), s);
            }
            Set<String> pool = new TreeSet<>(claimedHere.keySet());
            for(String id : lastWeek.keySet()){
                if(LeagueActuals.isMan(id) && !rostered.contains(id) && lastWeek.get(id).isJsonObject()
                        && lastWeek.getAsJsonObject(id).has("pts_half_ppr")){
                    pool.add(id);
                }
            }
            long runMoment = byDay.get(e.getValue()).get(0).cleared();
            for(String id : pool){
                Player player = Player.getPlayerFromSIDV2(id);
                if(player == null || player.position == null || player.position == Position.DEF
                        || !(player.position == Position.QB || player.position == Position.RB
                        || player.position == Position.WR || player.position == Position.TE)){
                    continue;
                }
                JsonObject line = lastWeek.has(id) && lastWeek.get(id).isJsonObject() ? lastWeek.getAsJsonObject(id) : null;
                JsonObject beforeLine = before != null && before.has(id) && before.get(id).isJsonObject() ? before.getAsJsonObject(id) : null;
                double total = 0;
                int games = 0;
                for(int w = 1; w <= played; w++){
                    Double pts = scored.computeIfAbsent(w, points::apply).get(id);
                    if(pts != null){
                        total += pts;
                        games++;
                    }
                }
                boolean dropped = false;
                for(int l = Math.max(1, leg - 1); l <= leg; l++){
                    for(long at : drops.get(l).getOrDefault(id, List.of())){
                        if(at < runMoment && at > runMoment - 8L * 24 * 3600 * 1000){
                            dropped = true;
                        }
                    }
                }
                double adp = adpOf.getOrDefault(id, UNDRAFTED_ADP);
                Settled s = claimedHere.get(id);
                wire.add(new WireMan(season, played, id, player.position, adp,
                        s == null ? 0 : s.bidders(), s == null ? -1 : s.price(), s != null && s.priced(),
                        features(line, beforeLine, lastPoints.getOrDefault(id, 0.0), games == 0 ? 0 : total / games,
                                adp, dropped, player.position, promoted.containsKey(id))));
            }
        }
    }

    /* ------------------------------------------------------------ the report */

    public static void main(String[] args) throws IOException {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        List<Contest> all = new ArrayList<>();
        List<WireMan> wire = new ArrayList<>();
        List<String> seasons = new ArrayList<>();
        Map<String, EraBoards.Board> boards = EraBoards.usable("ppr", EraIngest.MIN_RATE, EraIngest.minDepth());
        for(LeagueTransactions.Year year : LeagueTransactions.completedSeasons(configuration.getLeagueID())){
            String season = year.season();
            EraBoards.Board board = boards.get(season);
            harvest(season,
                    leg -> LeagueTransactions.transactionsRaw(year.leagueID(), leg),
                    week -> LineupPromotion.matchupsRaw(year.leagueID(), week),
                    week -> JsonParser.parseString(WeeklyActuals.raw(season, week)).getAsJsonObject(),
                    week -> LeagueActuals.leagueWeeklyPoints(season, week),
                    week -> NextManUp.lines(LeagueWeek.teamStatsBody(season, week)), WeeklyActuals.WEEKS,
                    board == null ? Map.of() : board.adp(), all, wire);
            seasons.add(season);
        }
        // this season, harvested the same way over its finished weeks and kept
        // out of every fit: the out-of-sample check on the runs already cleared
        String seasonNow = LeagueWeek.season();
        int lastPlayedNow = LeagueWeek.week() - 1;
        Map<String, Double> preseasonAdp = SleeperProjections.adpSnapshot(RosModel.preseasonLines(), RosModel.preseasonAdpDay());
        List<WireMan> wireNow = new ArrayList<>();
        if(lastPlayedNow >= 1){
            harvest(seasonNow,
                    leg -> leg <= lastPlayedNow + 1 ? LeagueWeek.transactions(configuration.getLeagueID(), leg) : "[]",
                    week -> LeagueWeek.matchups(configuration.getLeagueID(), week),
                    week -> JsonParser.parseString(LeagueWeek.actualsBody(seasonNow, week)).getAsJsonObject(),
                    week -> LeagueWeek.actualSoFar(seasonNow, week),
                    week -> NextManUp.lines(LeagueWeek.teamStatsBody(seasonNow, week)), lastPlayedNow,
                    preseasonAdp, new ArrayList<>(), wireNow);
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format("FAAB DEMAND  %s  (%d settled contests over %d seasons %s-%s; %d free men at %d big runs; features as the market saw them)%n",
                LocalDate.now(), all.size(), seasons.size(), seasons.get(0), seasons.get(seasons.size() - 1), wire.size(),
                wire.stream().map(w -> w.season() + "w" + w.weekPlayed()).distinct().count()));

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

        // ---------------------------------------------------------------- 2. the demand, over the wire
        out.append("\n== 2. THE DEMAND OVER THE WHOLE WIRE: P(any bid) at the big run, leave-one-season-out ==\n");
        double base = 0;
        for(WireMan m : wire){
            base += m.claimed() ? 1 : 0;
        }
        base /= Math.max(1, wire.size());
        out.append(String.format("%d free skill men who played, at the big run after their week; %.1f%% drew a bid, %.1f%% two or more.%n",
                wire.size(), 100 * base, 100.0 * wire.stream().filter(m -> m.bidders() >= 2).count() / Math.max(1, wire.size())));
        out.append(String.format("%-12s %10s %10s   %s%n", "feature", "r any bid", "r bidders", "mean, claimed vs not"));
        for(int f = 0; f < FEATURES.length; f++){
            List<double[]> va = new ArrayList<>();
            List<double[]> vb = new ArrayList<>();
            double sumC = 0, sumN = 0;
            int nC = 0, nN = 0;
            for(WireMan m : wire){
                va.add(new double[]{m.features()[f], m.claimed() ? 1 : 0});
                vb.add(new double[]{m.features()[f], m.bidders()});
                if(m.claimed()){ sumC += m.features()[f]; nC++; } else { sumN += m.features()[f]; nN++; }
            }
            out.append(String.format("%-12s %+10.3f %+10.3f   %.2f vs %.2f%n", FEATURES[f], WeeklyFeedAudit.fit(va).r(),
                    WeeklyFeedAudit.fit(vb).r(), sumC / Math.max(1, nC), sumN / Math.max(1, nN)));
        }
        Map<String, List<Double>> lossBySeason = new TreeMap<>();
        Map<String, List<Double>> baseBySeason = new TreeMap<>();
        Map<WireMan, Double> pOf = new HashMap<>();
        for(String held : seasons){
            List<double[]> trainX = new ArrayList<>();
            List<Boolean> trainY = new ArrayList<>();
            for(WireMan m : wire){
                if(!m.season().equals(held)){
                    trainX.add(m.features());
                    trainY.add(m.claimed());
                }
            }
            if(trainX.size() < 100){
                continue;
            }
            double[][] mo = UsageSignal.moments(trainX, FEATURES.length);
            List<double[]> z = new ArrayList<>();
            boolean[] y = new boolean[trainX.size()];
            double trainBase = 0;
            for(int i = 0; i < y.length; i++){
                z.add(standardised(trainX.get(i), mo));
                y[i] = trainY.get(i);
                trainBase += y[i] ? 1 : 0;
            }
            trainBase /= y.length;
            double[] beta = logistic(z, y, LAMBDA);
            for(WireMan m : wire){
                if(m.season().equals(held)){
                    double p = predict(beta, standardised(m.features(), mo));
                    pOf.put(m, p);
                    lossBySeason.computeIfAbsent(held, k -> new ArrayList<>()).add(logLoss(List.of(p), List.of(m.claimed())));
                    baseBySeason.computeIfAbsent(held, k -> new ArrayList<>()).add(logLoss(List.of(trainBase), List.of(m.claimed())));
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
        boolean earned = !Double.isNaN(g[1]) && g[0] > RosBands.tCritical95((int) g[2] - 1) * g[1];
        out.append(String.format("%nheld-out log loss %.4f vs base rate's %.4f: gain %+.4f +- %.4f over %d seasons%s%n",
                loss[0], baseLoss[0], g[0], g[1], (int) g[2], earned ? "  <- real" : "  <- not separated"));
        List<WireMan> ranked = new ArrayList<>(pOf.keySet());
        ranked.sort(Comparator.comparingDouble(pOf::get));
        int deciles = 10;
        out.append(String.format("%-8s %7s %8s %9s %9s   %s%n", "decile", "men", "mean p", "claimed", "2+ bids", "by predicted P(any bid), held out"));
        for(int d = 0; d < deciles; d++){
            List<WireMan> slice = ranked.subList(d * ranked.size() / deciles, (d + 1) * ranked.size() / deciles);
            double meanP = 0, claimed = 0, two = 0;
            for(WireMan m : slice){
                meanP += pOf.get(m);
                claimed += m.claimed() ? 1 : 0;
                two += m.bidders() >= 2 ? 1 : 0;
            }
            int n = Math.max(1, slice.size());
            out.append(String.format("%-8d %7d %7.1f%% %8.1f%% %8.1f%%%n", d + 1, slice.size(), 100 * meanP / n, 100 * claimed / n, 100 * two / n));
        }
        List<double[]> allX = new ArrayList<>();
        boolean[] allY = new boolean[wire.size()];
        for(int i = 0; i < wire.size(); i++){
            allX.add(wire.get(i).features());
            allY[i] = wire.get(i).claimed();
        }
        double[][] mAll = UsageSignal.moments(allX, FEATURES.length);
        List<double[]> zAll = new ArrayList<>();
        for(double[] x : allX){
            zAll.add(standardised(x, mAll));
        }
        double[] betaAll = logistic(zAll, allY, LAMBDA);
        out.append("all-season fit, log-odds per sd:");
        for(int f = 0; f < FEATURES.length; f++){
            out.append(String.format(" %s %+.2f", FEATURES[f], betaAll[f + 1]));
        }
        out.append('\n');

        // ---------------------------------------------------------------- 2a. the next man up
        out.append("\n== 2a. THE NEXT MAN UP: his team's leader at his position went down in the week just played (NextManUp) ==\n");
        int flagged = 0;
        int flaggedClaimed = 0;
        int rest = 0;
        int restClaimed = 0;
        for(WireMan m : wire){
            if(m.features()[11] > 0){
                flagged++;
                flaggedClaimed += m.claimed() ? 1 : 0;
            }
            else{
                rest++;
                restClaimed += m.claimed() ? 1 : 0;
            }
        }
        out.append(String.format("%d of %d free men were the next man up; %.1f%% of them drew a bid, against %.1f%% of the rest.%n",
                flagged, wire.size(), 100.0 * flaggedClaimed / Math.max(1, flagged), 100.0 * restClaimed / Math.max(1, rest)));
        Map<WireMan, Double> pWithout = heldOut(wire, seasons, WITHOUT_NEXT_UP);
        Map<String, List<Double>> nextUpGain = new TreeMap<>();
        for(Map.Entry<WireMan, Double> e : pOf.entrySet()){
            WireMan m = e.getKey();
            if(pWithout.containsKey(m)){
                nextUpGain.computeIfAbsent(m.season(), k -> new ArrayList<>()).add(
                        logLoss(List.of(pWithout.get(m)), List.of(m.claimed())) - logLoss(List.of(e.getValue()), List.of(m.claimed())));
            }
        }
        double[] nu = RosBands.overSeasons(nextUpGain);
        boolean nextUpEarns = !Double.isNaN(nu[1]) && nu[0] > RosBands.tCritical95((int) nu[2] - 1) * nu[1];
        out.append(String.format("held-out log loss, the same men and seasons: gain from adding it %+.4f +- %.4f over %d seasons%s%n",
                nu[0], nu[1], (int) nu[2], nextUpEarns ? "  <- it earns its place" : "  <- not separated: noise on this evidence"));
        Map<String, List<Double>> flaggedGain = new TreeMap<>();
        for(Map.Entry<WireMan, Double> e : pOf.entrySet()){
            WireMan m = e.getKey();
            if(m.features()[11] > 0 && pWithout.containsKey(m)){
                flaggedGain.computeIfAbsent(m.season(), k -> new ArrayList<>()).add(
                        logLoss(List.of(pWithout.get(m)), List.of(m.claimed())) - logLoss(List.of(e.getValue()), List.of(m.claimed())));
            }
        }
        double[] fg = RosBands.overSeasons(flaggedGain);
        out.append(String.format("on the flagged men alone: %+.4f +- %.4f a man (the whole-wire gain is this spread over %d men per flag)%n",
                fg[0], fg[1], Math.round((double) wire.size() / Math.max(1, flagged))));

        Fit withIt = fit(wire, allColumns());
        Fit withoutIt = fit(wire, WITHOUT_NEXT_UP);
        out.append(String.format("%nTHIS SEASON (%s), in no fit - the runs already cleared, scored by the fit on all %d past seasons:%n",
                seasonNow, seasons.size()));
        if(wireNow.isEmpty()){
            out.append("no finished week yet\n");
        }
        else{
            List<Double> pw = new ArrayList<>();
            List<Double> po = new ArrayList<>();
            List<Boolean> yNow = new ArrayList<>();
            for(WireMan m : wireNow){
                pw.add(withIt.p(m.features()));
                po.add(withoutIt.p(m.features()));
                yNow.add(m.claimed());
            }
            out.append(String.format("log loss over %d free men at %d run%s: %.4f with the feature, %.4f without (a check, not a test - one season)%n",
                    wireNow.size(), wireNow.stream().map(WireMan::weekPlayed).distinct().count(),
                    wireNow.stream().map(WireMan::weekPlayed).distinct().count() == 1 ? "" : "s",
                    logLoss(pw, yNow), logLoss(po, yNow)));
            Map<Integer, Map<String, String>> events = NextManUp.season(
                    week -> NextManUp.lines(LeagueWeek.teamStatsBody(seasonNow, week)), lastPlayedNow, preseasonAdp);
            for(int played = 1; played <= lastPlayedNow; played++){
                List<WireMan> run = new ArrayList<>();
                for(WireMan m : wireNow){
                    if(m.weekPlayed() == played){
                        run.add(m);
                    }
                }
                if(run.isEmpty()){
                    continue;
                }
                List<WireMan> byWith = new ArrayList<>(run);
                byWith.sort(Comparator.comparingDouble((WireMan m) -> -withIt.p(m.features())));
                List<WireMan> byWithout = new ArrayList<>(run);
                byWithout.sort(Comparator.comparingDouble((WireMan m) -> -withoutIt.p(m.features())));
                out.append(String.format("%n-- the run after week %d: %d free men --%n", played, run.size()));
                Map<String, String> here = events.getOrDefault(played, Map.of());
                out.append("next men up that week (leader who went down -> next man, and whether he was free):\n");
                Map<String, WireMan> inRun = new HashMap<>();
                for(WireMan m : run){
                    inRun.put(m.playerID(), m);
                }
                for(Map.Entry<String, String> ev : here.entrySet()){
                    WireMan m = inRun.get(ev.getKey());
                    out.append(String.format("   %-22s -> %-22s %s%n", nameOf(ev.getValue()), nameOf(ev.getKey()),
                            m == null ? "rostered, or no stat row" : m.claimed() ? m.bidders() + " bidder" + (m.bidders() == 1 ? "" : "s") : "free, no bid"));
                }
                out.append(String.format("%-22s %-3s %8s %10s %14s %14s%n", "claimed man", "pos", "bidders", "next up", "with: P, rank", "without"));
                for(WireMan m : byWith){
                    if(!m.claimed()){
                        continue;
                    }
                    out.append(String.format("%-22s %-3s %8d %10s %7.0f%%, %4d %7.0f%%, %4d%n", nameOf(m.playerID()), m.position(), m.bidders(),
                            m.features()[11] > 0 ? "yes" : "", 100 * withIt.p(m.features()), byWith.indexOf(m) + 1,
                            100 * withoutIt.p(m.features()), byWithout.indexOf(m) + 1));
                }
            }
        }

        // ---------------------------------------------------------------- 2b. the price by quality
        out.append("\n== 2b. THE PRICE BY QUALITY, among the claimed at the big run (priced contests only) ==\n");
        String[] adpBands = {"ADP 1-100", "ADP 101-200", "ADP 200+ or undrafted"};
        String[] ppgBands = {"ppg < 5", "5-10", "10-15", "15+"};
        List<List<Integer>> pricesByAdp = new ArrayList<>();
        List<List<Integer>> pricesByPpg = new ArrayList<>();
        for(int i = 0; i < 4; i++){
            if(i < 3){
                pricesByAdp.add(new ArrayList<>());
            }
            pricesByPpg.add(new ArrayList<>());
        }
        for(WireMan m : wire){
            if(!m.priced()){
                continue;
            }
            int a = m.adp() <= 100 ? 0 : m.adp() <= 200 ? 1 : 2;
            double ppg = m.features()[4];
            int q = ppg < 5 ? 0 : ppg < 10 ? 1 : ppg < 15 ? 2 : 3;
            pricesByAdp.get(a).add(m.price());
            pricesByPpg.get(q).add(m.price());
        }
        out.append(String.format("%-18s %7s %7s %7s %7s   %s%n", "band", "n", "median", "p75", "p90", "P(win) at $1 $3 $5 $8 $13 $20"));
        for(int i = 0; i < 3; i++){
            List<Integer> pr = pricesByAdp.get(i);
            out.append(String.format("%-18s %7d %7d %7d %7d   ", adpBands[i], pr.size(), quantile(pr, 0.5), quantile(pr, 0.75), quantile(pr, 0.9)));
            for(int bid : new int[]{1, 3, 5, 8, 13, 20}){
                out.append(String.format(" %3.0f%%", 100 * winChance(pr, bid)));
            }
            out.append('\n');
        }
        for(int i = 0; i < 4; i++){
            List<Integer> pr = pricesByPpg.get(i);
            out.append(String.format("%-18s %7d %7d %7d %7d   ", ppgBands[i], pr.size(), quantile(pr, 0.5), quantile(pr, 0.75), quantile(pr, 0.9)));
            for(int bid : new int[]{1, 3, 5, 8, 13, 20}){
                out.append(String.format(" %3.0f%%", 100 * winChance(pr, bid)));
            }
            out.append('\n');
        }
        List<double[]> priceVsAdp = new ArrayList<>();
        List<double[]> priceVsPpg = new ArrayList<>();
        List<double[]> priceVsJump = new ArrayList<>();
        for(WireMan m : wire){
            if(m.priced()){
                priceVsAdp.add(new double[]{m.features()[5], m.price()});
                priceVsPpg.add(new double[]{m.features()[4], m.price()});
                priceVsJump.add(new double[]{m.features()[1], m.price()});
            }
        }
        out.append(String.format("r of the price with log ADP %+.3f, with ppg so far %+.3f, with the snap jump %+.3f (n %d)%n",
                WeeklyFeedAudit.fit(priceVsAdp).r(), WeeklyFeedAudit.fit(priceVsPpg).r(), WeeklyFeedAudit.fit(priceVsJump).r(), priceVsAdp.size()));

        // ---------------------------------------------------------------- 3. this week
        String season = LeagueWeek.season();
        int week = LeagueWeek.week();
        int last = week - 1;
        out.append(String.format("%n== 3. THIS WEEK: every free skill man who played, P(any bid) at the big run, and the bid that wins 50 / 75 / 90%% in his ADP band ==%n"));
        if(last < 1){
            out.append("no played week yet\n");
        }
        else{
            JsonObject lastWeek = JsonParser.parseString(LeagueWeek.actualsBody(season, last)).getAsJsonObject();
            JsonObject before = last >= 2 ? JsonParser.parseString(LeagueWeek.actualsBody(season, last - 1)).getAsJsonObject() : null;
            List<Map<String, Double>> scoredWeeks = new ArrayList<>();
            for(int w = 1; w <= last; w++){
                scoredWeeks.add(LeagueWeek.actualSoFar(season, w));
            }
            Map<String, List<Long>> dropped = new HashMap<>();
            for(int l = Math.max(1, week - 1); l <= week; l++){
                dropsIn(LeagueWeek.transactions(configuration.getLeagueID(), l)).forEach((id, at) -> dropped.computeIfAbsent(id, k -> new ArrayList<>()).addAll(at));
            }
            // the preseason board, as the harvest reads each past season's: today's
            // ADP has absorbed the season so far, the market's surprises included
            Map<String, Double> adpNow = preseasonAdp;
            Map<String, String> promotedNow = NextManUp.season(
                    w -> NextManUp.lines(LeagueWeek.teamStatsBody(season, w)), last, preseasonAdp).getOrDefault(last, Map.of());
            // The pool at the run: men on no roster DURING the week just played,
            // the same rule as the harvest - today's rosters already hold the men
            // this morning's run awarded, and the backtest below needs them in.
            // the run that has already cleared on these same features: what the league did against what the model said
            List<WaiverLog.Claim> thisSeason = new ArrayList<>();
            for(int l = 1; l <= week; l++){
                thisSeason.addAll(WaiverLog.claims(LeagueWeek.transactions(configuration.getLeagueID(), l), l));
            }
            Map<String, Integer> biddersNow = new HashMap<>();
            Map<String, Integer> priceNow = new HashMap<>();
            String lastRunDay = null;
            for(List<WaiverLog.Claim> contest : WaiverLog.contests(thisSeason).values()){
                String day = Instant.ofEpochMilli(contest.get(0).cleared()).atZone(LEAGUE_ZONE).toLocalDate().toString();
                if(lastRunDay == null || day.compareTo(lastRunDay) > 0){
                    lastRunDay = day;
                }
            }
            for(List<WaiverLog.Claim> contest : WaiverLog.contests(thisSeason).values()){
                String day = Instant.ofEpochMilli(contest.get(0).cleared()).atZone(LEAGUE_ZONE).toLocalDate().toString();
                if(!day.equals(lastRunDay)){
                    continue;
                }
                Set<Integer> rosters = new TreeSet<>();
                for(WaiverLog.Claim c : contest){
                    rosters.add(c.rosterID());
                    if(c.won()){
                        priceNow.put(c.playerID(), c.bid());
                    }
                }
                biddersNow.put(contest.get(0).playerID(), rosters.size());
            }
            Set<String> owned = rosteredIn(LeagueWeek.matchups(configuration.getLeagueID(), last));
            record Candidate(String name, Position position, double p, double adp, double[] f){}
            List<Candidate> candidates = new ArrayList<>();
            Set<String> poolNow = new TreeSet<>(biddersNow.keySet());     // claimed men are in the pool whatever the rosters say
            for(String id : lastWeek.keySet()){
                if(!owned.contains(id) && LeagueActuals.isMan(id) && lastWeek.get(id).isJsonObject()
                        && lastWeek.getAsJsonObject(id).has("pts_half_ppr")){
                    poolNow.add(id);
                }
            }
            for(String id : poolNow){
                Player player = Player.getPlayerFromSIDV2(id);
                if(player == null || player.position == null || player.position == Position.DEF || player.team == null){
                    continue;
                }
                JsonObject line = lastWeek.has(id) && lastWeek.get(id).isJsonObject() ? lastWeek.getAsJsonObject(id) : null;
                JsonObject beforeLine = before != null && before.has(id) && before.get(id).isJsonObject() ? before.getAsJsonObject(id) : null;
                double total = 0;
                int games = 0;
                for(Map<String, Double> w : scoredWeeks){
                    Double pts = w.get(id);
                    if(pts != null){ total += pts; games++; }
                }
                double adp = adpNow.getOrDefault(id, UNDRAFTED_ADP);
                adp = adp >= 999 ? UNDRAFTED_ADP : adp;
                double[] f = features(line, beforeLine, scoredWeeks.get(last - 1).getOrDefault(id, 0.0),
                        games == 0 ? 0 : total / games, adp, dropped.containsKey(id), player.position, promotedNow.containsKey(id));
                candidates.add(new Candidate(player.firstName + " " + player.lastName, player.position,
                        predict(betaAll, standardised(f, mAll)), adp, f));
            }
            candidates.sort(Comparator.comparingDouble((Candidate c) -> -c.p()));
            out.append(String.format("%-22s %-3s %8s %6s %5s %5s %6s %6s %6s %7s %7s   %s%n", "player", "pos", "P(bid)", "ADP", "share", "jump",
                    "touch", "pts", "ppg", "dropped", "next up", "bid to win 50 / 75 / 90 in his ADP band"));
            int shown = 0;
            for(Candidate c : candidates){
                if(shown++ >= 30){
                    break;
                }
                int a = c.adp() <= 100 ? 0 : c.adp() <= 200 ? 1 : 2;
                List<Integer> pr = pricesByAdp.get(a);
                out.append(String.format("%-22s %-3s %7.0f%% %6s %4.0f%% %+4.0f%% %6.1f %6.1f %6.1f %7s %7s   $%d / $%d / $%d%n",
                        c.name(), c.position(), 100 * c.p(), c.adp() >= UNDRAFTED_ADP ? "-" : String.format("%.0f", c.adp()),
                        100 * c.f()[0], 100 * c.f()[1], c.f()[2], c.f()[3], c.f()[4], c.f()[6] > 0 ? "yes" : "", c.f()[11] > 0 ? "yes" : "",
                        bidFor(pr, 0.5), bidFor(pr, 0.75), bidFor(pr, 0.9)));
            }
            out.append("the thirty free skill men the model expects a bid on; ADP is Sleeper's half-PPR preseason board (the harvest used\n");
            out.append("each past season's FFC board). Worth is TuesdaySwap's column, and a bid above it is a loss whatever the ladder says.\n");
            Map<String, Double> pByName = new HashMap<>();
            Map<String, Integer> rankByName = new HashMap<>();
            for(int i = 0; i < candidates.size(); i++){
                pByName.put(candidates.get(i).name(), candidates.get(i).p());
                rankByName.put(candidates.get(i).name(), i + 1);
            }
            out.append(String.format("%nthe run that already cleared on these features (%s): the skill men who drew a bid, with the model's P(bid) and rank among %d free men%n",
                    lastRunDay, candidates.size()));
            List<String> claimedIds = new ArrayList<>(biddersNow.keySet());
            claimedIds.sort(Comparator.comparingInt((String id) -> -biddersNow.get(id)));
            for(String id : claimedIds){
                Player player = Player.getPlayerFromSIDV2(id);
                if(player == null || player.position == null || player.position == Position.DEF){
                    continue;
                }
                String nm = player.firstName + " " + player.lastName;
                out.append(String.format("   %-22s %-3s %d bidder%s, %s   model: %s%n", nm, player.position, biddersNow.get(id),
                        biddersNow.get(id) == 1 ? "" : "s", priceNow.containsKey(id) ? "won at $" + priceNow.get(id) : "nobody won",
                        pByName.containsKey(nm) ? String.format("%.0f%%, rank %d", 100 * pByName.get(nm), rankByName.get(nm)) : "not a skill man in the pool"));
            }
        }

        System.out.print(out);
        Path report = Path.of("data", "faab-demand-" + LocalDate.now() + ".txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }
}
