import PlayerImportAndSetup.Position;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A REST-OF-SEASON PROJECTION: IS SLEEPER SLOW, OR RIGHTLY CAUTIOUS - AND CAN
 * THE TWO BE COMBINED INTO SOMETHING BETTER THAN EITHER?
 *
 * Justin, week 3: "can you write a model to figure out ROS projections, since
 * Sleeper doesn't appear to be updating as fast - or is Sleeper updating
 * properly but being less reactionary, to keep a good squared-error model?"
 *
 * Two facts were already measured and are not re-measured here:
 *
 *   - Sleeper's SEASON feed does not react to results at all; it moves on
 *     injury and news only (TRAPS #136, MarketMovers, ProjectionDrift).
 *   - Sleeper's WEEKLY feed does react: after week 1 the week-2 projection
 *     leaned on the week-1 surprise by 0.095 +- 0.022, against 0.101 for the
 *     squared-error-optimal share 1/(kappa+1) (WeeklyFeedAudit, 2026-09-15).
 *
 * The squared-error-optimal update is already a model here: the conjugate
 * posterior of {@link InSeasonLearning}, whose one free parameter kappa is
 * sigma^2_within / sigma^2_between measured on thirteen seasons - which is the
 * weight that minimises expected squared error under the model, not a tuned
 * knob. It is served as {@code -Pprojections=posterior}.
 *
 * What was never tested, and is the test here: as estimates of a man's
 * rest-of-season rate, is Sleeper's next-week projection better or worse than
 * the posterior on SQUARED error, and does a blend of the two beat both?
 *
 *   rate_hat = w * posterior + (1 - w) * sleeper_next_week
 *
 * w is the least-squares weight, fitted per position and weeks-seen on every
 * season but one and scored on the one left out, over every season whose week
 * projections Sleeper still serves with points in them (2018 onward; its
 * earlier week files are placeholder rows). The target is the man's
 * mean over his played games from week k+2 on - the week Sleeper projected is
 * not scored, so neither estimator gets credit for knowing one matchup. Every
 * difference is paired on the same men and clustered on season.
 *
 * The reading this gives: w near 0 means Sleeper's number already carries the
 * posterior's information (it is updating properly); w near 1 means it adds
 * nothing to the posterior; a blend that beats both by more than its own bar
 * is a better rest-of-season projection than either, and that is the only
 * case in which a new projection source would be earned.
 *
 * Sleeper's number here is ONE game's projection, opponent included, set
 * against a rest-of-season target; its matchup term is noise to that target,
 * so part of its extra error - most at quarterback - is the matchup and not a
 * reluctance to update. A mean over later weeks' projections would average it
 * out but would read numbers made after week k, so it cannot be tested
 * honestly on the past.
 *
 * THIS WEEK: the same blend applied to the season being played - the
 * posterior built exactly as validated (positional ADP rank to the prior
 * table, the level of this season's played weeks, kappa on all seasons), with
 * Sleeper's ADP ranks standing in for the FFC board's, blended with Sleeper's
 * projection for the current week at the weight fitted for this many weeks
 * seen. {@code -Pprojections=ros} serves it to every tool as a season total:
 * the rate per played game times the weeks Sleeper projects him to play, so a
 * total keeps the same availability every untouched man's does. Men with no played game,
 * outside the four positions, or past roster depth by ADP - the population the
 * backtest never scored - keep Sleeper's number. That includes a backup who has
 * just become a starter: his season number is stale and the model cannot vouch
 * for a replacement, so it says so rather than guessing.
 *
 * One caution printed with the answer: Sleeper serves ONE number per past week
 * with no vintage. RecordBook measured those numbers as forecasts (r = 0.528
 * against outcomes), but this season's week-1 numbers still moved for 81 of
 * 809 men between the last pre-kickoff read and the post-game read (TRAPS
 * #137). Any leakage flatters Sleeper, so a result in the posterior's favour
 * is conservative and one in Sleeper's favour is not.
 *
 *   ./gradlew run -Pmain=RosModel
 */
public class RosModel {

    static final int[] SEEN = {1, 2, 3, 4, 6, 8};

    /**
     * THE POPULATION THE DECISION IS MADE OVER: the whole preseason board, by
     * positional ADP rank. InSeasonLearning's study stops at a sixteen-man
     * roster's plausible depth (QB 24, RB 48, WR 60, TE 24), which left out men
     * this league actually rosters: on 2026-09-25 the deepest rostered man at
     * each position sat at preseason rank QB 27, RB 60, WR 91 and TE 27 - Dalton
     * Schultz, the tight end Justin starts, was the 25th. A model that cannot
     * price the man in the lineup is not answering the question, so the
     * backtest, the prior table, kappa and the level all run on this depth, and
     * the backtest says whether the blend still earns its place here.
     */
    static final Map<Position, Integer> DEPTH = new EnumMap<>(Map.of(
            Position.QB, 28, Position.RB, 74, Position.WR, 96, Position.TE, 32));

    /** One man at k games seen: the two estimates and what he then did. */
    record Case(String season, Position position, int seen, String id, double posterior, double sleeper,
                double target) {}

    /**
     * The least-squares weight on {@code a} against {@code b} for predicting
     * {@code y}: argmin over w of the sum of (w a + (1-w) b - y)^2, clipped to
     * [0, 1]. Rows are {a, b, y}. When the two estimates never differ, every
     * weight predicts the same and a half is returned.
     */
    static double blendWeight(List<double[]> rows){
        double numerator = 0;
        double denominator = 0;
        for(double[] r : rows){
            double d = r[0] - r[1];
            numerator += d * (r[2] - r[1]);
            denominator += d * d;
        }
        if(denominator <= 0){
            return 0.5;
        }
        return Math.max(0, Math.min(1, numerator / denominator));
    }

    static double blend(double w, double posterior, double sleeper){
        return w * posterior + (1 - w) * sleeper;
    }

    /** The mean of each season's list, with the standard error over seasons. */
    static double[] overSeasons(Map<String, List<Double>> bySeason){
        return RosBands.overSeasons(bySeason);
    }

    /** Every case from every season whose week k+1 projection carries points; the seasons used are added to {@code withWeeks}. */
    static List<Case> cases(Map<String, List<InSeasonLearning.Man>> harvest, List<String> seasons,
                            Map<String, Map<Position, double[]>> priors,
                            Map<String, Map<Position, InSeasonLearning.Kappa>> kappas, List<String> withWeeks){
        List<Case> cases = new ArrayList<>();
        for(String season : seasons){
            boolean any = false;
            for(int seen : SEEN){
                Map<String, Double> next;
                try {
                    next = LeagueWeek.projected(season, seen + 1);
                }
                catch(RuntimeException unavailable){
                    continue;
                }
                if(next.isEmpty()){
                    continue;
                }
                any = true;
                List<InSeasonLearning.Man> men = harvest.get(season);
                for(Position position : InSeasonLearning.POSITIONS){
                    double level = InSeasonLearning.levelThrough(men, position, seen, DEPTH);
                    double kappa = kappas.get(season).get(position).kappa();
                    for(InSeasonLearning.Man man : men){
                        if(man.position() != position || man.rank() > DEPTH.get(position)
                                || man.gamesThrough(seen) < 1 || man.restGames(seen + 1) < 3
                                || !next.containsKey(man.id())){
                            continue;
                        }
                        cases.add(new Case(season, position, seen, man.id(),
                                InSeasonLearning.estimate(man, seen, kappa, priors.get(season), level),
                                next.get(man.id()),
                                man.restPoints(seen + 1) / man.restGames(seen + 1)));
                    }
                }
            }
            if(any){
                withWeeks.add(season);
            }
        }
        return cases;
    }

    /** The all-season least-squares weight on the posterior, per position, at one weeks-seen. */
    static Map<Position, Double> weights(List<Case> cases, int seen){
        Map<Position, Double> out = new EnumMap<>(Position.class);
        for(Position position : InSeasonLearning.POSITIONS){
            List<double[]> rows = new ArrayList<>();
            for(Case c : cases){
                if(c.seen() == seen && c.position() == position){
                    rows.add(new double[]{c.posterior(), c.sleeper(), c.target()});
                }
            }
            if(rows.size() >= 20){
                out.put(position, blendWeight(rows));
            }
        }
        return out;
    }

    /** The validated weeks-seen nearest at or below the weeks actually seen, so a weight is never borrowed from later. */
    static int seenFor(int weeksSeen){
        int best = SEEN[0];
        for(int s : SEEN){
            if(s <= weeksSeen){
                best = s;
            }
        }
        return best;
    }

    /**
     * This season's men in the harvest's shape: positional rank by Sleeper's ADP
     * (lower is earlier), overall rank the same way, and each played week's
     * league points with NaN for a week not played.
     */
    static List<InSeasonLearning.Man> seasonMen(String season, Map<String, Double> adpOf, Map<String, Position> positionOf,
                                                List<Map<String, Double>> playedWeeks, int weeks){
        List<String> ids = new ArrayList<>(adpOf.keySet());
        ids.sort(java.util.Comparator.comparingDouble(adpOf::get));
        Map<Position, Integer> seen = new EnumMap<>(Position.class);
        List<InSeasonLearning.Man> men = new ArrayList<>();
        int overall = 0;
        for(String id : ids){
            Position position = positionOf.get(id);
            if(position == null){
                continue;
            }
            overall++;
            int rank = seen.merge(position, 1, Integer::sum);
            double[] week = new double[weeks];
            java.util.Arrays.fill(week, Double.NaN);
            for(int w = 0; w < Math.min(weeks, playedWeeks.size()); w++){
                Double points = playedWeeks.get(w).get(id);
                if(points != null){
                    week[w] = points;
                }
            }
            men.add(new InSeasonLearning.Man(season, id, position, rank, overall, week, weeks));
        }
        return men;
    }

    /** The archived ADP lines, read once. */
    static List<String> preseasonLines(){
        try {
            return Files.readAllLines(AdpSnapshot.CSV, StandardCharsets.UTF_8);
        }
        catch(IOException unreadable){
            throw new IllegalStateException("cannot read " + AdpSnapshot.CSV + "; the prior ranks must be preseason", unreadable);
        }
    }

    /** The last archived ADP day before Sleeper's season start - the board the prior ranks on. */
    static String preseasonAdpDay(){
        String day = WeekReaction.priorDay(ProjectionShootout.archiveDays(preseasonLines()),
                LeagueWeek.state().get("season_start_date").getAsString());
        if(day == null){
            throw new IllegalStateException("no ADP snapshot before the season start in " + AdpSnapshot.CSV
                    + "; the prior ranks must be preseason, and today's ADP has absorbed the season so far");
        }
        return day;
    }

    /** One man's rest-of-season read this week. */
    record Ros(String id, Position position, int games, double posterior, Double sleeperWeek, double weight, double rate) {}

    /**
     * The blend for the season being played: every skill man with a Sleeper ADP
     * and a played game, the posterior as validated, Sleeper's current-week
     * projection, and the weight fitted at the weeks seen. A man Sleeper does
     * not project this week (bye, out) keeps the posterior alone - there is no
     * second number to blend.
     */
    static Map<String, Ros> thisSeason(Map<String, List<InSeasonLearning.Man>> harvest, List<Case> cases){
        String season = LeagueWeek.season();
        int current = LeagueWeek.week();
        int weeksSeen = 0;
        List<Map<String, Double>> played = new ArrayList<>();
        for(int w = 1; w < current; w++){
            played.add(LeagueWeek.actual(season, w));
            weeksSeen++;
        }
        Map<String, Ros> out = new java.util.LinkedHashMap<>();
        if(weeksSeen == 0){
            return out;
        }

        // THE PRIOR IS PRESEASON. The backtest ranked every man on the board as it
        // stood before the season; today's ADP has absorbed two weeks of results
        // and news (57 positional ranks moved between 09-11 and 09-25 alone), and
        // ranking on it would count the evidence twice - once in the prior, once
        // in the update. The ranks come from the last ADP snapshot before
        // Sleeper's season start; positions from today's feed, which do not move.
        Map<String, Double> snapshotAdp = SleeperProjections.adpSnapshot(preseasonLines(), preseasonAdpDay());
        Map<String, Double> adpOf = new HashMap<>();
        Map<String, Position> positionOf = new HashMap<>();
        for(com.google.gson.JsonElement e : SleeperProjections.getTodaysProjections()){
            com.google.gson.JsonObject record = e.getAsJsonObject();
            com.google.gson.JsonObject stats = record.has("stats") && record.get("stats").isJsonObject()
                    ? record.getAsJsonObject("stats") : null;
            com.google.gson.JsonObject player = record.has("player") && record.get("player").isJsonObject()
                    ? record.getAsJsonObject("player") : null;
            if(stats == null || player == null || !stats.has("adp_half_ppr") || stats.get("adp_half_ppr").isJsonNull()
                    || !player.has("position") || player.get("position").isJsonNull()){
                continue;
            }
            Position position = WeekReaction.skill(player.get("position").getAsString());
            String id = record.get("player_id").getAsString();
            Double adp = snapshotAdp.get(id);
            if(position == null || adp == null || adp >= 999){
                continue;
            }
            adpOf.put(id, adp);
            positionOf.put(id, position);
        }
        List<InSeasonLearning.Man> men = seasonMen(season, adpOf, positionOf, played, WeeklyStarterValue.WEEKS);
        Map<Position, double[]> prior = InSeasonLearning.priorTable(harvest, null, null, DEPTH);
        Map<Position, InSeasonLearning.Kappa> kappa = InSeasonLearning.fitKappa(harvest, null, DEPTH);
        int k = seenFor(weeksSeen);
        Map<Position, Double> w = weights(cases, k);
        Map<String, Double> sleeperWeek = LeagueWeek.projected(season, current);
        for(InSeasonLearning.Man man : men){
            int games = man.gamesThrough(weeksSeen);
            // THE VALIDATED POPULATION ONLY - the whole preseason board (DEPTH),
            // which the backtest scored. Past it the prior table clamps to its last
            // rank and hands a man off the board the last ranked man's prior - a
            // first run gave a man Sleeper projects for 0 season points 7.9 a game
            // on one catch. Such a man keeps Sleeper's number, as everywhere else.
            if(games == 0 || man.rank() > DEPTH.get(man.position())){
                continue;
            }
            double level = InSeasonLearning.levelThrough(men, man.position(), weeksSeen, DEPTH);
            double posterior = InSeasonLearning.estimate(man, weeksSeen, kappa.get(man.position()).kappa(), prior, level);
            Double s = sleeperWeek.get(man.id());
            double weight = s == null ? 1.0 : w.getOrDefault(man.position(), 1.0);
            out.put(man.id(), new Ros(man.id(), man.position(), games, posterior, s, weight,
                    s == null ? posterior : blend(weight, posterior, s)));
        }
        return out;
    }

    /** The {@code -Pprojections=ros} source: Sleeper's season numbers with every played skill man replaced by his blended rate over the objective's games. */
    public static Map<String, Double> season(AAAConfiguration configuration){
        Map<String, EraBoards.Board> boards = EraBoards.usable("ppr", EraIngest.MIN_RATE, EraIngest.minDepth());
        Map<String, List<InSeasonLearning.Man>> harvest = InSeasonLearning.harvest(boards);
        List<String> seasons = new ArrayList<>(harvest.keySet());
        Map<String, Map<Position, double[]>> priors = new TreeMap<>();
        Map<String, Map<Position, InSeasonLearning.Kappa>> kappas = new TreeMap<>();
        for(String season : seasons){
            priors.put(season, InSeasonLearning.priorTable(harvest, season, null, DEPTH));
            kappas.put(season, InSeasonLearning.fitKappa(harvest, season, DEPTH));
        }
        List<Case> cases = cases(harvest, seasons, priors, kappas, new ArrayList<>());
        Map<String, Double> out = new java.util.LinkedHashMap<>(SleeperProjections.parseTodaysWebPage());
        Map<String, Ros> ros = thisSeason(harvest, cases);
        // A SEASON TOTAL INCLUDES THE GAMES HE MISSES. The objective reads it that
        // way - it scales a man against historical full-season totals, missed
        // weeks and all - and every man this source does not touch keeps Sleeper's
        // number, which counts them too. The blend is a rate per PLAYED game, so
        // it is multiplied by the weeks Sleeper projects him to play, not by
        // seventeen: rate x 17 inflated every blended man by about 8% against the
        // rest (the RosModel refuters, 2026-09-25; TRAPS #145).
        Map<String, Integer> weeks = LeagueWeek.projectedWeeks(LeagueWeek.season());
        int set = 0;
        for(Ros r : ros.values()){
            int n = weeks.getOrDefault(r.id(), 0);
            if(n > 0){
                out.put(r.id(), r.rate() * n);
                set++;
            }
        }
        System.out.printf("ros: %d men set to their blended rate times the weeks Sleeper projects them to play%n", set);
        return out;
    }

    public static void main(String[] args) throws IOException {
        Map<String, EraBoards.Board> boards = EraBoards.usable("ppr", EraIngest.MIN_RATE, EraIngest.minDepth());
        Map<String, List<InSeasonLearning.Man>> harvest = InSeasonLearning.harvest(boards);
        List<String> seasons = new ArrayList<>(harvest.keySet());
        Map<String, Map<Position, double[]>> priors = new TreeMap<>();
        Map<String, Map<Position, InSeasonLearning.Kappa>> kappas = new TreeMap<>();
        for(String season : seasons){
            priors.put(season, InSeasonLearning.priorTable(harvest, season, null, DEPTH));
            kappas.put(season, InSeasonLearning.fitKappa(harvest, season, DEPTH));
        }
        List<String> withWeeks = new ArrayList<>();
        List<Case> cases = cases(harvest, seasons, priors, kappas, withWeeks);

        StringBuilder out = new StringBuilder();
        out.append(String.format("REST-OF-SEASON MODEL  %s  (%d cases over %s; posterior fitted on %d seasons %s-%s, leave-one-season-out)%n",
                LocalDate.now(), cases.size(), String.join(", ", withWeeks), seasons.size(), seasons.get(0),
                seasons.get(seasons.size() - 1)));
        out.append("estimates of a man's rate from week k+2 on, after k games: the posterior (InSeasonLearning's rule), Sleeper's\n");
        out.append("week k+1 projection, and their least-squares blend w*posterior + (1-w)*Sleeper, w fitted on the other seasons.\n");
        out.append("RMSE in league points a game; the paired columns are differences in squared error on the same men, mean over\n");
        out.append(String.format("seasons +- se (%d seasons, each a cluster); negative favours the first named. A reading needs the%n", withWeeks.size()));
        out.append(String.format("t critical value for that many seasons (%.3f), not two; and these are 24 readings a table on overlapping men.%n",
                RosBands.tCritical95(withWeeks.size() - 1)));

        Map<Integer, Map<Position, Double>> weightAll = new TreeMap<>();
        Map<Integer, Boolean> blendEarned = new TreeMap<>();
        for(int seen : SEEN){
            out.append(String.format("%n== after %d game%s ==%n", seen, seen == 1 ? "" : "s"));
            out.append(String.format("%-4s %5s %8s %8s %8s %6s   %-26s %-26s %s%n", "pos", "men", "RMSE post", "RMSE slpr",
                    "RMSE blnd", "w", "slpr - post (sq err)", "blend - better of two", "reading"));
            Map<String, List<Double>> pooledBlendVsBest = new TreeMap<>();
            Map<Position, Double> weights = new EnumMap<>(Position.class);
            for(Position position : InSeasonLearning.POSITIONS){
                List<Case> here = new ArrayList<>();
                for(Case c : cases){
                    if(c.seen() == seen && c.position() == position){
                        here.add(c);
                    }
                }
                if(here.size() < 20){
                    continue;
                }
                Map<String, List<Double>> sqPost = new TreeMap<>();
                Map<String, List<Double>> sqSlpr = new TreeMap<>();
                Map<String, List<Double>> sqBlend = new TreeMap<>();
                Map<String, List<Double>> slprMinusPost = new TreeMap<>();
                for(String held : withWeeks){
                    List<double[]> train = new ArrayList<>();
                    for(Case c : here){
                        if(!c.season().equals(held)){
                            train.add(new double[]{c.posterior(), c.sleeper(), c.target()});
                        }
                    }
                    if(train.size() < 10){
                        continue;
                    }
                    double w = blendWeight(train);
                    for(Case c : here){
                        if(!c.season().equals(held)){
                            continue;
                        }
                        double ep = sq(c.posterior() - c.target());
                        double es = sq(c.sleeper() - c.target());
                        double eb = sq(blend(w, c.posterior(), c.sleeper()) - c.target());
                        sqPost.computeIfAbsent(held, k -> new ArrayList<>()).add(ep);
                        sqSlpr.computeIfAbsent(held, k -> new ArrayList<>()).add(es);
                        sqBlend.computeIfAbsent(held, k -> new ArrayList<>()).add(eb);
                        slprMinusPost.computeIfAbsent(held, k -> new ArrayList<>()).add(es - ep);
                    }
                }
                // blend against the better single estimator, chosen per season on the TRAINING seasons only,
                // so the comparison is not handed the better of two by hindsight
                Map<String, List<Double>> blendVsBest = new TreeMap<>();
                for(String held : sqBlend.keySet()){
                    double trainPost = 0;
                    double trainSlpr = 0;
                    for(String other : sqPost.keySet()){
                        if(!other.equals(held)){
                            trainPost += mean(sqPost.get(other));
                            trainSlpr += mean(sqSlpr.get(other));
                        }
                    }
                    List<Double> better = trainPost <= trainSlpr ? sqPost.get(held) : sqSlpr.get(held);
                    List<Double> d = new ArrayList<>();
                    for(int i = 0; i < better.size(); i++){
                        d.add(sqBlend.get(held).get(i) - better.get(i));
                    }
                    blendVsBest.put(held, d);
                    pooledBlendVsBest.computeIfAbsent(held, k -> new ArrayList<>()).addAll(d);
                }
                List<double[]> allRows = new ArrayList<>();
                for(Case c : here){
                    allRows.add(new double[]{c.posterior(), c.sleeper(), c.target()});
                }
                double wAll = blendWeight(allRows);
                weights.put(position, wAll);
                double[] sp = overSeasons(slprMinusPost);
                double[] bb = overSeasons(blendVsBest);
                String reading = !RosBands.separated(sp) ? "not separated"
                        : sp[0] < 0 ? "Sleeper better" : "posterior better";
                if(RosBands.belowZero(bb)){
                    reading += "; BLEND beats both";
                }
                out.append(String.format("%-4s %5d %8.2f %8.2f %8.2f %6.2f   %+7.2f +- %-14.2f %+7.2f +- %-14.2f %s%n",
                        position, here.size(), Math.sqrt(overSeasons(sqPost)[0]), Math.sqrt(overSeasons(sqSlpr)[0]),
                        Math.sqrt(overSeasons(sqBlend)[0]), wAll, sp[0], sp[1], bb[0], bb[1], reading));
            }
            double[] pooled = overSeasons(pooledBlendVsBest);
            boolean earned = RosBands.belowZero(pooled);
            blendEarned.put(seen, earned);
            weightAll.put(seen, weights);
            out.append(String.format("all positions: blend minus the better single estimator %+.2f +- %.2f in squared error%s%n",
                    pooled[0], pooled[1], earned ? "  <- the blend is EARNED at this many games" : "  <- not separated"));
        }

        out.append("\n== THE READING ==\n");
        out.append("w is the weight the data puts on the posterior against Sleeper's next-week number (0 = all Sleeper, 1 = all posterior).\n");
        out.append("A w near 0 says Sleeper's number already holds what the posterior knows: it is updating, just not in its SEASON feed.\n");
        out.append("A w between says each knows something the other does not - Sleeper the injury report, depth chart and matchup,\n");
        out.append("the posterior the measured rate at which a box score is worth believing.\n");
        out.append("Matchup caution: Sleeper's number is one game's projection, opponent included, against a rest-of-season target;\n");
        out.append("part of its extra error - most at quarterback - is that matchup, not a reluctance to update.\n");
        out.append("Leakage caution: past week projections have no vintage and a few are post-game revisions (TRAPS #137); any leak\n");
        out.append("flatters Sleeper, so a reading in the posterior's favour is conservative and one in Sleeper's favour is not.\n");

        // ---------------------------------------------------------------- this week
        Map<String, Ros> ros = thisSeason(harvest, cases);
        if(!ros.isEmpty()){
            AAAConfiguration configuration = AAAConfiguration.getInstance();
            String me = configuration.getUserIDToDisplayName().getOrDefault(configuration.getMyID(), configuration.getMyID());
            Map<String, String> ownerOf = LeagueOwners.today(configuration);
            Map<String, Double> seasonFeed = SleeperProjections.parseTodaysWebPage();
            int k = seenFor(LeagueWeek.week() - 1);
            out.append(String.format("%n== THIS WEEK: the blend on the season being played, at the weights for %d week%s seen (%s) ==%n",
                    k, k == 1 ? "" : "s", blendEarned.getOrDefault(k, false) ? "the blend was EARNED at this many games, pooled over positions"
                            : "the blend was NOT separated at this many games - read it as the posterior with Sleeper's news"));
            out.append(String.format("prior ranks from the ADP snapshot of %s, the last before the season started; the per-position%n", preseasonAdpDay()));
            out.append("verdicts above say where each position's blend stands on its own.\n");
            out.append(String.format("%-22s %-3s %-12s %5s %9s %9s %6s %9s %9s%n", "player", "pos", "owner", "games",
                    "posterior", "slpr wk", "w", "ROS/g", "slpr seas"));
            List<Ros> mine = new ArrayList<>();
            List<Ros> free = new ArrayList<>();
            for(Ros r : ros.values()){
                String owner = ownerOf.get(r.id());
                if(me.equals(owner)){
                    mine.add(r);
                }
                else if(owner == null){
                    free.add(r);
                }
            }
            java.util.Comparator<Ros> byRate = java.util.Comparator.comparingDouble(Ros::rate).reversed();
            mine.sort(java.util.Comparator.comparing((Ros r) -> r.position()).thenComparing(byRate));
            free.sort(byRate);
            for(Ros r : mine){
                out.append(row(r, me, seasonFeed));
            }
            out.append("the best free men at each position:\n");
            for(Position position : InSeasonLearning.POSITIONS){
                int shown = 0;
                for(Ros r : free){
                    if(r.position() == position && shown < 3){
                        out.append(row(r, "free agent", seasonFeed));
                        shown++;
                    }
                }
            }
            out.append(String.format("ROS/g is the blend, w*posterior + (1-w)*Sleeper's week-%d projection; slpr seas is Sleeper's season number\n",
                    LeagueWeek.week()));
            out.append(String.format("over %d games, which does not move on results. -Pprojections=ros serves ROS/g times the weeks Sleeper%n",
                    WeeklyStarterValue.WEEKS));
            out.append("projects him to play, so a blended total counts missed games the way every other man's does.\n");
            out.append("Only men on the preseason board (the population the backtest scored) are blended; a backup who has just\n");
            out.append("become a starter keeps Sleeper's stale season number here, and his current-week projection is the better read.\n");
        }

        System.out.print(out);
        Path report = Path.of("data", "ros-model-" + LocalDate.now() + ".txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }

    private static String row(Ros r, String owner, Map<String, Double> seasonFeed){
        Player player = Player.getPlayerFromSIDV2(r.id());
        String name = player == null ? r.id() : player.firstName + " " + player.lastName;
        return String.format("%-22s %-3s %-12s %5d %9.1f %9s %6.2f %9.1f %9.1f%n", name.length() > 22 ? name.substring(0, 22) : name,
                r.position(), owner.length() > 12 ? owner.substring(0, 12) : owner, r.games(), r.posterior(),
                r.sleeperWeek() == null ? "-" : String.format("%.1f", r.sleeperWeek()), r.weight(), r.rate(),
                seasonFeed.getOrDefault(r.id(), 0.0) / WeeklyStarterValue.WEEKS);
    }

    static double sq(double x){
        return x * x;
    }

    static double mean(List<Double> xs){
        double s = 0;
        for(double x : xs){
            s += x;
        }
        return xs.isEmpty() ? Double.NaN : s / xs.size();
    }
}
