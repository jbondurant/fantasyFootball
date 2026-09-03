import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Can a lineup rule LEARN in-season, and can that claim be validated at n=13?
 *
 * `WeeklyStarterValue` promotes a bench man through one channel only - a
 * starter drawn `!up()`, which is injury. Everyone still standing is ranked by
 * his PRESEASON number, which never moves, so a starter who plays seventeen
 * games and disappoints keeps his slot all year and a bench man who breaks out
 * is never promoted. Justin: "some starters bust, and some bench players boom."
 *
 * Adding that channel means adding an in-season learning rule, and a learning
 * rule is knobs. This repo has been burned three times by knobs it could not
 * identify: a fourteen-slot shape fitted on four seasons lost +126 meeting a
 * fifth; a Spearman rank correlation was shipped for weeks where a regression
 * slope belonged; and the trust coefficient's error bar covered both 0.578 and
 * 1.0, so "shrink forty percent" and "do not shrink at all" were the same
 * reading. So this tool answers the METHOD question before anybody writes the
 * channel:
 *
 *   A. Is the learning rule's free parameter measurable, and what is it?
 *   B. Does the updated ranking beat the preseason ranking at the level of a
 *      single start/sit decision - a PICK-level claim, on ~10^4 decisions?
 *   C. Does the same rule, on the same seasons, move a SEASON score enough to
 *      clear this repo's own measured bar?
 *
 * B and C are the same football. If B resolves and C does not, that is the
 * whole finding, and it is a rule about what kind of claim a new channel is
 * allowed to make.
 *
 * THE RULE UNDER TEST is the conjugate-normal posterior mean - Bayesian
 * updating with the preseason projection as the prior:
 *
 *     estimate_i(k) = (kappa * prior_i + games_i(k) * observed_i(k))
 *                     / (kappa + games_i(k))
 *
 * One free parameter. kappa is a NUMBER OF GAMES: the weight of the prior
 * measured in the same units as the evidence, and it equals the variance ratio
 * sigma^2_within / sigma^2_between. It is not a knob to be tuned on season
 * scores - it is estimated from the week-to-week scatter of every player-season
 * in the harvest, which is where the data actually is. Section A measures it
 * and Section B checks that number against the value that would have won.
 *
 * HINDSIGHT IS THE FAILURE MODE and it is guarded three ways. Priors, scales
 * and kappa are all fitted LEAVE-ONE-SEASON-OUT. Every decision at week k reads
 * only weeks 1..k. Every score is taken from weeks k+1 on. The ORACLE arm -
 * ranking by what a man went on to score that week - is printed only so the
 * size of the trap is visible; it is the bug that once produced a third-round
 * quarterback and three defences.
 *
 *   ./gradlew run -Pmain=InSeasonLearning -q
 */
public class InSeasonLearning {

    /** How deep at each position a sixteen-man roster could plausibly reach. */
    static final Map<Position, Integer> CAP = new EnumMap<>(Map.of(
            Position.QB, 24, Position.RB, 48, Position.WR, 60, Position.TE, 24));

    static final Position[] POSITIONS =
            {Position.QB, Position.RB, Position.WR, Position.TE};

    /** Positional ranks pooled either side when fitting the prior curve. */
    static final int SMOOTH = 3;

    /** A player-season needs this many games before it can inform a fit. */
    static final int MIN_GAMES = 4;

    /** The weeks a decision is allowed to have seen. */
    static final int[] WEEKS_SEEN = {1, 2, 3, 4, 5, 6, 8, 10};

    /**
     * The multiple of the measured kappa that section B2 finds would have won.
     *
     * Carried here so section C can run the BEST rule the data supports and not
     * only the honest one - if even a rule tuned on its own validation set
     * cannot move a season score, no untuned rule will either, and the negative
     * result is not an artifact of a badly chosen constant.
     */
    static final double TUNED = 4.0;

    /**
     * One player-season, laid out week by week.
     *
     * NaN is "did not play", never zero. The two are different facts and the
     * whole question turns on the difference: a man who played and scored two
     * is available-and-bad, which is what the missing channel is about, while a
     * man who did not play is the channel the model already has.
     */
    record Man(String season, String id, Position position, int rank, int overall,
               double[] week, int weeks){

        boolean played(int w){
            return w >= 1 && w <= weeks && !Double.isNaN(week[w - 1]);
        }

        int gamesThrough(int w){
            int games = 0;
            for(int i = 1; i <= Math.min(w, weeks); i++){
                if(played(i)){
                    games++;
                }
            }
            return games;
        }

        double pointsThrough(int w){
            double total = 0;
            for(int i = 1; i <= Math.min(w, weeks); i++){
                if(played(i)){
                    total += week[i - 1];
                }
            }
            return total;
        }

        int games(){
            return gamesThrough(weeks);
        }

        double ppg(){
            return games() == 0 ? 0 : pointsThrough(weeks) / games();
        }

        /** Points collected from week `after`+1 to the end, absences at zero. */
        double restPoints(int after){
            double total = 0;
            for(int i = after + 1; i <= weeks; i++){
                if(played(i)){
                    total += week[i - 1];
                }
            }
            return total;
        }

        int restGames(int after){
            int games = 0;
            for(int i = after + 1; i <= weeks; i++){
                if(played(i)){
                    games++;
                }
            }
            return games;
        }

        /** Sample variance of his played weeks, or NaN with fewer than two. */
        double weekVariance(){
            int games = games();
            if(games < 2){
                return Double.NaN;
            }
            double mean = ppg();
            double sum = 0;
            for(int i = 1; i <= weeks; i++){
                if(played(i)){
                    sum += (week[i - 1] - mean) * (week[i - 1] - mean);
                }
            }
            return sum / (games - 1);
        }
    }

    // --------------------------------------------------------------- harvest

    static Map<String, List<Man>> harvest(Map<String, EraBoards.Board> boards){
        Map<String, List<Man>> out = new TreeMap<>();
        for(Map.Entry<String, EraBoards.Board> entry : boards.entrySet()){
            EraBoards.Board board = entry.getValue();
            int weeks = board.weeks();
            Map<Position, Integer> seen = new EnumMap<>(Position.class);
            List<Man> men = new ArrayList<>();
            for(int overall = 0; overall < board.ids().size(); overall++){
                String id = board.ids().get(overall);        // board.ids() is ADP order
                Position position = board.positionOf().get(id);
                if(position == null || position == Position.DEF){
                    continue;
                }
                int rank = seen.merge(position, 1, Integer::sum);
                double[] week = new double[weeks];
                for(int i = 0; i < weeks; i++){
                    Double points = board.weekly().get(i).get(id);
                    week[i] = points == null ? Double.NaN : points;
                }
                men.add(new Man(entry.getKey(), id, position, rank, overall, week, weeks));
            }
            out.put(entry.getKey(), men);
        }
        return out;
    }

    /**
     * The scoring LEVEL of one season at one position, over the men a roster
     * could reach.
     *
     * The league scored far more in 2024 than in 2013, so a prior fitted across
     * seasons is in the wrong units for the season it is applied to. A level is
     * common to every man at a position, so it cannot change any RANKING within
     * that position - but it does change how heavily the prior outweighs the
     * evidence, which is the whole mechanism here, so it has to be right.
     */
    static double scale(List<Man> season, Position position){
        double sum = 0;
        int count = 0;
        for(Man man : season){
            if(man.position() == position && man.rank() <= CAP.get(position)
                    && man.games() >= MIN_GAMES){
                sum += man.ppg();
                count++;
            }
        }
        return count == 0 ? 1 : sum / count;
    }

    /**
     * The preseason prior: what a man at this positional ADP rank is worth,
     * in units of his own season's level, fitted on OTHER seasons only.
     *
     * This stands in for a preseason projection. The repo holds real
     * FantasyPros projections for five seasons and ADP for thirteen, and ADP is
     * a market forecast aggregated from real drafts - the same information a
     * projection is built from. Using it keeps all thirteen seasons in play,
     * which is the only axis that ever sharpens anything here.
     */
    static Map<Position, double[]> priorTable(Map<String, List<Man>> harvest,
                                              String excludeA, String excludeB){
        Map<Position, double[]> table = new EnumMap<>(Position.class);
        for(Position position : POSITIONS){
            int cap = CAP.get(position);
            double[] prior = new double[cap + 1];
            for(int rank = 1; rank <= cap; rank++){
                double sum = 0;
                int count = 0;
                for(Map.Entry<String, List<Man>> entry : harvest.entrySet()){
                    if(entry.getKey().equals(excludeA) || entry.getKey().equals(excludeB)){
                        continue;
                    }
                    double level = scale(entry.getValue(), position);
                    for(Man man : entry.getValue()){
                        if(man.position() == position && man.games() >= MIN_GAMES
                                && Math.abs(man.rank() - rank) <= SMOOTH){
                            sum += man.ppg() / level;
                            count++;
                        }
                    }
                }
                prior[rank] = count == 0 ? 1 : sum / count;
            }
            table.put(position, prior);
        }
        return table;
    }

    static double priorAt(Map<Position, double[]> table, Position position, int rank){
        double[] prior = table.get(position);
        if(prior == null){
            return 1;
        }
        return prior[Math.max(1, Math.min(prior.length - 1, rank))];
    }

    // ------------------------------------------- A. the one free parameter

    /**
     * kappa = sigma^2_within / sigma^2_between, in GAMES, fitted without the
     * held-out season.
     *
     * sigma^2_within is the week-to-week scatter of one man around his own
     * season rate. sigma^2_between is how far men at the same draft rank truly
     * differ from each other - which is NOT the raw spread of their season
     * rates, because that spread already contains the sampling noise of a
     * seventeen-week season. Subtracting it is the whole trick, and skipping it
     * is how a shrinkage constant ends up too weak by a factor of two.
     */
    record Kappa(Position position, double within, double between, double kappa,
                 int men){}

    static Map<Position, Kappa> fitKappa(Map<String, List<Man>> harvest, String heldOut){
        Map<Position, Kappa> out = new EnumMap<>(Position.class);
        for(Position position : POSITIONS){
            double withinNumerator = 0;
            double withinWeight = 0;
            for(Map.Entry<String, List<Man>> entry : harvest.entrySet()){
                if(entry.getKey().equals(heldOut)){
                    continue;
                }
                double level = scale(entry.getValue(), position);
                for(Man man : entry.getValue()){
                    if(man.position() != position || man.rank() > CAP.get(position)
                            || man.games() < MIN_GAMES){
                        continue;
                    }
                    double variance = man.weekVariance();
                    if(Double.isNaN(variance)){
                        continue;
                    }
                    withinNumerator += (man.games() - 1) * variance / (level * level);
                    withinWeight += man.games() - 1;
                }
            }
            double within = withinWeight == 0 ? 0 : withinNumerator / withinWeight;

            double sumSquares = 0;
            double sampling = 0;
            int men = 0;
            for(Map.Entry<String, List<Man>> entry : harvest.entrySet()){
                if(entry.getKey().equals(heldOut)){
                    continue;
                }
                // the prior this man is measured against must not have seen his
                // own season either, or the residual is shrunk toward himself
                Map<Position, double[]> table =
                        priorTable(harvest, heldOut, entry.getKey());
                double level = scale(entry.getValue(), position);
                for(Man man : entry.getValue()){
                    if(man.position() != position || man.rank() > CAP.get(position)
                            || man.games() < MIN_GAMES){
                        continue;
                    }
                    double residual = man.ppg() / level
                            - priorAt(table, position, man.rank());
                    sumSquares += residual * residual;
                    sampling += within / man.games();
                    men++;
                }
            }
            double observed = men > 1 ? sumSquares / (men - 1) : 0;
            double between = Math.max(1e-6, observed - (men == 0 ? 0 : sampling / men));
            out.put(position, new Kappa(position, within, between,
                    within / between, men));
        }
        return out;
    }

    // ------------------------------ the estimate a manager could have formed

    /** The posterior mean at week k, in that season's own points per game. */
    static double estimate(Man man, int seen, double kappa,
                           Map<Position, double[]> prior, double levelSeen){
        int games = man.gamesThrough(seen);
        double mean = priorAt(prior, man.position(), man.rank()) * levelSeen;
        if(games == 0){
            return mean;
        }
        double observed = man.pointsThrough(seen) / games;
        return (kappa * mean + games * observed) / (kappa + games);
    }

    /**
     * The season's scoring level as it looked THROUGH week k.
     *
     * Observable, which is the point: rescaling the prior with the level of the
     * finished season would be hindsight of exactly the kind that has already
     * reversed findings in this repo twice.
     */
    static double levelThrough(List<Man> season, Position position, int seen){
        double sum = 0;
        int count = 0;
        for(Man man : season){
            if(man.position() == position && man.rank() <= CAP.get(position)
                    && man.gamesThrough(seen) >= 1){
                sum += man.pointsThrough(seen) / man.gamesThrough(seen);
                count++;
            }
        }
        return count == 0 ? 1 : sum / count;
    }

    // ------------------------------------------- B. the pick-level question

    /**
     * One flip: the preseason board ranks A over B, the evidence through week k
     * says otherwise. Did B really outscore A over the weeks that followed?
     *
     * Self-baselining, which is why it is the right shape of question. The null
     * is exactly 50% - flipping on noise wins half the time - so no separate
     * control arm has to be trusted. And the estimand is a PROBABILITY, bounded
     * in [0,1], which is the real reason a pick-level claim resolves where a
     * season-level one does not: a bounded quantity has bounded between-season
     * variance, and between-season variance is the only kind thirteen seasons
     * cannot average away.
     */
    record Flip(int season, boolean right, double margin, boolean bothHealthy){}

    static List<Flip> flips(Map<String, List<Man>> harvest, List<String> seasons,
                            Position position, int seen, double kappaMultiple,
                            Map<String, Map<Position, double[]>> priors,
                            Map<String, Map<Position, Kappa>> kappas){
        List<Flip> found = new ArrayList<>();
        for(int s = 0; s < seasons.size(); s++){
            String season = seasons.get(s);
            List<Man> men = harvest.get(season);
            Map<Position, double[]> prior = priors.get(season);
            double kappa = kappas.get(season).get(position).kappa() * kappaMultiple;
            double level = levelThrough(men, position, seen);

            List<Man> field = new ArrayList<>();
            for(Man man : men){
                if(man.position() == position && man.rank() <= CAP.get(position)
                        && man.gamesThrough(seen) >= 1){
                    field.add(man);
                }
            }
            double[] estimate = new double[field.size()];
            for(int i = 0; i < field.size(); i++){
                estimate[i] = estimate(field.get(i), seen, kappa, prior, level);
            }
            int weeks = men.isEmpty() ? 0 : men.get(0).weeks();
            int remaining = weeks - seen;
            if(remaining < 3){
                continue;
            }
            for(int i = 0; i < field.size(); i++){
                for(int j = i + 1; j < field.size(); j++){
                    Man ahead = field.get(i);
                    Man behind = field.get(j);
                    if(ahead.rank() >= behind.rank()){
                        continue;                   // field is in ADP order already
                    }
                    if(estimate[j] <= estimate[i]){
                        continue;                   // no flip: the board still leads
                    }
                    double margin = behind.restPoints(seen) - ahead.restPoints(seen);
                    boolean healthy = ahead.restGames(seen) >= 0.8 * remaining
                            && behind.restGames(seen) >= 0.8 * remaining;
                    found.add(new Flip(s, margin > 0, margin, healthy));
                }
            }
        }
        return found;
    }

    /**
     * A flip set's accuracy with an error bar clustered on SEASON.
     *
     * Pairs inside one season are scored on the same realised football, so they
     * are not independent observations and the naive binomial error on ten
     * thousand of them is a lie. `PowerBacktest.paired` is this repo's own
     * instrument for exactly that and is reused unchanged, so these bars are on
     * the same footing as the 125-point one.
     */
    static PowerBacktest.Paired accuracy(String name, List<Flip> flips, int clusters,
                                         boolean healthyOnly){
        List<Flip> use = new ArrayList<>();
        for(Flip flip : flips){
            if(!healthyOnly || flip.bothHealthy()){
                use.add(flip);
            }
        }
        if(use.size() < clusters){
            return null;
        }
        double[] score = new double[use.size()];
        double[] diff = new double[use.size()];
        int[] clusterOf = new int[use.size()];
        for(int i = 0; i < use.size(); i++){
            score[i] = use.get(i).right() ? 1 : 0;
            diff[i] = score[i] - 0.5;
            clusterOf[i] = use.get(i).season();
        }
        return PowerBacktest.paired(name, score, diff, clusterOf, clusters);
    }

    // ----------------------------------------- C. the season-level question

    /**
     * How a week's lineup is ordered. Everything else about the fill is equal.
     *
     * The key must be comparable ACROSS positions, because the two flex slots
     * put backs, receivers and tight ends in one queue. That is why the learning
     * arms return an estimate in real points per game rather than in units of a
     * position's own level: a receiver at 1.2 of his position's level and a back
     * at 1.1 of his cannot be compared, and comparing them anyway was the first
     * bug the EraGame guard below caught.
     */
    interface Ranker {
        double key(Man man, int week);          // higher starts
    }

    /**
     * One season of a roster, scored week by week - the same greedy legal fill
     * `EraGame.seasonPoints` uses, with the ORDERING made pluggable and nothing
     * else touched. A guard in main() checks that the board ranker reproduces
     * `EraGame.seasonPoints` to the point, on every season.
     */
    static double seasonPoints(List<Man> roster, int weeks, Ranker ranker){
        double total = 0;
        for(int week = 1; week <= weeks; week++){
            Map<Position, List<Man>> up = new EnumMap<>(Position.class);
            for(Man man : roster){
                if(man.played(week)){
                    up.computeIfAbsent(man.position(), u -> new ArrayList<>()).add(man);
                }
            }
            final int w = week;
            for(List<Man> men : up.values()){
                men.sort(Comparator.comparingDouble((Man man) -> ranker.key(man, w))
                        .reversed()
                        .thenComparingInt(Man::overall));
            }
            List<Man> flex = new ArrayList<>();
            total += fill(up.get(Position.QB), 1, null, week);
            total += fill(up.get(Position.RB), 2, flex, week);
            total += fill(up.get(Position.WR), 3, flex, week);
            total += fill(up.get(Position.TE), 1, flex, week);
            flex.sort(Comparator.comparingDouble((Man man) -> ranker.key(man, w))
                    .reversed()
                    .thenComparingInt(Man::rank));
            for(int slot = 0; slot < 2 && slot < flex.size(); slot++){
                total += flex.get(slot).week()[week - 1];
            }
        }
        return total;
    }

    static double fill(List<Man> available, int slots, List<Man> flex, int week){
        int size = available == null ? 0 : available.size();
        double scored = 0;
        for(int slot = 0; slot < slots && slot < size; slot++){
            scored += available.get(slot).week()[week - 1];
        }
        if(flex != null){
            for(int extra = slots; extra < size; extra++){
                flex.add(available.get(extra));
            }
        }
        return scored;
    }

    /**
     * The scoring level a manager could have known, week by week.
     *
     * Week 1 has seen nothing, so its level comes from the OTHER seasons -
     * which is exactly what a preseason projection is, a forecast of the level
     * from previous football. From week 2 on it is read off the weeks already
     * played. The finished season is never consulted, which matters because the
     * level is what sets the prior's weight against the evidence.
     */
    static Map<Position, double[]> levelsFor(Map<String, List<Man>> harvest,
                                             List<String> seasons, String season,
                                             int weeks){
        Map<Position, double[]> out = new EnumMap<>(Position.class);
        List<Man> all = harvest.get(season);
        for(Position position : POSITIONS){
            double[] levels = new double[weeks + 1];
            double loo = 0;
            int count = 0;
            for(String other : seasons){
                if(!other.equals(season)){
                    loo += scale(harvest.get(other), position);
                    count++;
                }
            }
            levels[1] = count == 0 ? 1 : loo / count;
            for(int week = 2; week <= weeks; week++){
                levels[week] = levelThrough(all, position, week - 1);
            }
            out.put(position, levels);
        }
        return out;
    }

    /** The eleven-round shape a keeper-holding roster drafts; DEF is round 16. */
    static final List<Position> SHAPE = List.of(
            Position.RB, Position.RB, Position.RB, Position.WR, Position.WR,
            Position.WR, Position.WR, Position.TE, Position.RB, Position.WR,
            Position.RB);

    // ------------------------------------------------------------------ run

    public static void main(String[] args){
        String format = System.getProperty("format");
        Map<String, EraBoards.Board> boards = EraBoards.usable(
                format == null ? "ppr" : format, EraIngest.MIN_RATE, EraIngest.minDepth());
        Map<String, List<Man>> harvest = harvest(boards);
        List<String> seasons = new ArrayList<>(harvest.keySet());
        int clusters = seasons.size();

        Map<String, Map<Position, double[]>> priors = new TreeMap<>();
        Map<String, Map<Position, Kappa>> kappas = new TreeMap<>();
        for(String season : seasons){
            priors.put(season, priorTable(harvest, season, null));
            kappas.put(season, fitKappa(harvest, season));
        }

        System.out.printf("%nCAN A LINEUP RULE LEARN IN-SEASON, AND CAN THAT BE VALIDATED?%n%n");
        System.out.printf("seasons %d (%s-%s), boards fantasyfootballcalculator 12-team ADP,%n",
                clusters, seasons.get(0), seasons.get(clusters - 1));
        System.out.printf("outcomes sleeper weekly, scored under THIS league's settings.%n");
        System.out.printf("Every prior, level and kappa is fitted LEAVE-ONE-SEASON-OUT.%n");

        // ------------------------------------------------------ A. kappa
        System.out.printf("%n%nA. THE ONE FREE PARAMETER, MEASURED%n%n");
        System.out.printf("estimate_i(k) = (kappa*prior_i + games_i(k)*observed_i(k))"
                + " / (kappa + games_i(k))%n");
        System.out.printf("kappa is in GAMES: how much evidence it takes to move the"
                + " prior halfway.%n%n");
        System.out.printf("%-5s %9s %10s %9s %8s %10s %10s   %s%n", "POS", "sd_within",
                "sd_between", "kappa", "men", "LOO min", "LOO max", "half-way at");
        Map<Position, Double> kappaAll = new EnumMap<>(Position.class);
        for(Position position : POSITIONS){
            Kappa all = fitKappa(harvest, null).get(position);
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for(String season : seasons){
                double k = kappas.get(season).get(position).kappa();
                min = Math.min(min, k);
                max = Math.max(max, k);
            }
            kappaAll.put(position, all.kappa());
            System.out.printf("%-5s %9.3f %10.3f %9.2f %8d %10.2f %10.2f   %.1f games%n",
                    position, Math.sqrt(all.within()), Math.sqrt(all.between()),
                    all.kappa(), all.men(), min, max, all.kappa());
        }
        System.out.printf("%nsd_within and sd_between are in units of the position's own"
                + " season level,%nso they are directly comparable across positions and"
                + " eras. LOO min/max is the%nspread of the estimate over thirteen"
                + " refits, each blind to one season - the%nhonest error bar on a"
                + " constant this repo would otherwise carry on a sentence.%n");

        // ------------------------------------------------------ B. pick level
        System.out.printf("%n%nB. PICK LEVEL: when the evidence flips the board, is it right?%n%n");
        System.out.printf("Every pair of men at one position inside roster depth. The board"
                + " ranks A over B;%nthe rule at week k says B. Did B outscore A over the"
                + " REMAINING weeks? The null is%nexactly 50%%, so nothing has to be"
                + " trusted for this to mean something.%n");
        System.out.printf("Bars are 95%%, clustered on season (PowerBacktest.paired), n"
                + " flips pooled.%n%n");
        System.out.printf("%-6s %7s %9s %9s %8s %10s   %-11s %8s %8s%n", "SEEN", "POS",
                "flips", "accuracy", "95% bar", "verdict", "margin", "healthy", "n");
        for(int seen : WEEKS_SEEN){
            for(Position position : POSITIONS){
                List<Flip> found = flips(harvest, seasons, position, seen, 1.0,
                        priors, kappas);
                PowerBacktest.Paired paired = accuracy("", found, clusters, false);
                PowerBacktest.Paired healthy = accuracy("", found, clusters, true);
                if(paired == null){
                    continue;
                }
                double margin = 0;
                for(Flip flip : found){
                    margin += flip.margin();
                }
                margin = found.isEmpty() ? 0 : margin / found.size();
                System.out.printf("%-6d %7s %9d %8.1f%% %8.1f%% %10s   %+8.1f %7s%% %8d%n",
                        seen, position, found.size(), 100 * (0.5 + paired.diff()),
                        100 * paired.bar(), paired.real() ? "REAL" : "noise", margin,
                        healthy == null ? "  -"
                                : String.format("%.1f", 100 * (0.5 + healthy.diff())),
                        healthy == null ? 0 : healthy.draws());
            }
            System.out.println();
        }
        System.out.printf("margin  = mean rest-of-season points of the promoted man minus"
                + " the demoted one,%n          positive means the flip gained points."
                + " healthy = the same accuracy on the%n          subset where BOTH men"
                + " played 80%% of the remaining weeks, which strips out%n          the"
                + " injury channel the model already has and leaves only bust and boom.%n");

        // ----------------------------------- B2. is kappa identified by validation?
        System.out.printf("%n%nB2. IS KAPPA IDENTIFIED TWICE? measured against tuned%n%n");
        System.out.printf("Section A measured kappa from week-to-week scatter alone,"
                + " never looking at%nwhether it wins. This sweeps it and asks which"
                + " value WOULD have won. If the two%nagree, the parameter is identified"
                + " two independent ways and is not a knob.%n%n");
        double[] multiples = {0.0, 0.25, 0.5, 1.0, 2.0, 4.0, 16.0};
        System.out.printf("%-6s", "SEEN");
        for(double multiple : multiples){
            System.out.printf(" %9s", multiple == 0 ? "obs only" : (multiple + "x"));
        }
        System.out.printf("   %s%n", "best");
        for(int seen : WEEKS_SEEN){
            System.out.printf("%-6d", seen);
            double best = -1;
            double bestMultiple = 0;
            for(double multiple : multiples){
                List<Flip> pooled = new ArrayList<>();
                for(Position position : POSITIONS){
                    pooled.addAll(flips(harvest, seasons, position, seen, multiple,
                            priors, kappas));
                }
                PowerBacktest.Paired paired = accuracy("", pooled, clusters, false);
                double value = paired == null ? 0.5 : 0.5 + paired.diff();
                System.out.printf(" %8.1f%%", 100 * value);
                if(value > best){
                    best = value;
                    bestMultiple = multiple;
                }
            }
            System.out.printf("   %.2fx%n", bestMultiple);
        }
        System.out.printf("%nEach column is a multiple of the MEASURED kappa. 0x is a"
                + " plain k-week average%nwith no prior at all; 16x is very nearly the"
                + " preseason board. Accuracy is pooled%nover the four positions and is"
                + " the same statistic as section B.%n");

        // ------------------------------------------------------ C. season level
        System.out.printf("%n%nC. SEASON LEVEL: the same rule, on a real roster%n%n");
        double[] board = new double[clusters];
        double[] prior = new double[clusters];
        double[] learned = new double[clusters];
        double[] tuned = new double[clusters];
        double[] oracle = new double[clusters];
        int[] clusterOf = new int[clusters];
        for(int s = 0; s < clusters; s++){
            clusterOf[s] = s;
            String season = seasons.get(s);
            EraBoards.Board raw = boards.get(season);
            List<String> ids = EraGame.draft(raw, SHAPE, EraGame.keepers(raw));
            Map<String, Man> byId = new HashMap<>();
            for(Man man : harvest.get(season)){
                byId.put(man.id(), man);
            }
            List<Man> roster = new ArrayList<>();
            for(String id : ids){
                Man man = byId.get(id);
                if(man != null){
                    roster.add(man);
                }
            }
            int weeks = raw.weeks();
            Map<Position, double[]> table = priors.get(season);
            Map<Position, Kappa> kappa = kappas.get(season);
            List<Man> all = harvest.get(season);
            Map<Position, double[]> levelByWeek =
                    levelsFor(harvest, seasons, season, weeks);

            board[s] = seasonPoints(roster, weeks, (man, week) -> -man.overall());
            prior[s] = seasonPoints(roster, weeks, (man, week) ->
                    priorAt(table, man.position(), man.rank())
                            * levelByWeek.get(man.position())[week]);
            learned[s] = seasonPoints(roster, weeks, (man, week) ->
                    estimate(man, week - 1, kappa.get(man.position()).kappa(), table,
                            levelByWeek.get(man.position())[week]));
            tuned[s] = seasonPoints(roster, weeks, (man, week) ->
                    estimate(man, week - 1, TUNED * kappa.get(man.position()).kappa(),
                            table, levelByWeek.get(man.position())[week]));
            oracle[s] = seasonPoints(roster, weeks, (man, week) -> man.week()[week - 1]);

            // THE GUARD. With the board ranker this must BE EraGame.seasonPoints;
            // a copied fill that has drifted from the original would let a
            // difference in the copy masquerade as a difference in the rule.
            double reference = EraGame.seasonPoints(raw, ids);
            double defence = 0;                       // an eleven-round shape drafts none
            if(Math.abs(board[s] - (reference - defence)) > 0.01){
                throw new IllegalStateException("the board ranker does not reproduce"
                        + " EraGame.seasonPoints in " + season + ": " + board[s]
                        + " against " + reference);
            }
        }

        System.out.printf("Roster: %s plus the two keepers, drafted from seat %d on each%n",
                EraPlans.shape(SHAPE), EraGame.SLOT);
        System.out.printf("season's own board. Only the weekly ORDERING changes between"
                + " rows.%n%n");
        System.out.printf("%-26s %9s %9s %9s %9s %9s %7s%n", "RANKED EACH WEEK BY",
                "mean", "vs board", "SE(seas)", "95% bar", "80% det", "wins");
        report("preseason board rank", board, board, clusterOf, clusters);
        report("fitted prior, no update", prior, board, clusterOf, clusters);
        report("BAYES at measured kappa", learned, board, clusterOf, clusters);
        report("BAYES at " + (int) TUNED + "x kappa (tuned)", tuned, board, clusterOf,
                clusters);
        report("[hindsight] this week", oracle, board, clusterOf, clusters);

        System.out.printf("%nThe hindsight row is not a strategy. It is the ceiling on"
                + " every learning rule%nthat could ever exist here, and it is the bug"
                + " that once produced a third-round%nquarterback and three defences."
                + " Anything at all close to it is a hindsight leak.%n");

        // How much football it would take to PROVE the honest rule's gain. This
        // is the number that decides whether the season-level test is worth
        // running at all, and it has to be computed rather than guessed.
        double[] gain = new double[clusters];
        for(int s = 0; s < clusters; s++){
            gain[s] = learned[s] - board[s];
        }
        PowerBacktest.Paired honest =
                PowerBacktest.paired("", learned, gain, clusterOf, clusters);
        int needed = 0;
        for(int n = clusters; n <= 100000; n++){
            if(PowerBacktest.minimumDetectable(honest.seAt(n, 1), n)
                    <= Math.abs(honest.diff())){
                needed = n;
                break;
            }
        }
        System.out.printf("%nSeasons needed to detect the honest rule's %+.1f at 95%%"
                + " and 80%% power: %s.%nThirteen exist. This claim is not"
                + " establishable, now or ever.%n", honest.diff(),
                needed == 0 ? "more than 100,000" : String.valueOf(needed));

        // ------------------------------------------- D. would it move a pick
        System.out.printf("%n%nD. WOULD THE CHANNEL REPRICE A BENCH PICK?%n%n");
        System.out.printf("A bench man is worth his MARGINAL contribution - the roster"
                + " with him minus the%nroster without. That marginal is what a draft"
                + " objective bids. If the learning rule%ndoes not move it by more than"
                + " the gaps between the men on the board, it cannot%nreorder a pick"
                + " whatever else it is worth.%n%n");
        System.out.printf("%-10s %12s %12s %12s %10s %10s%n", "ROUND", "board", "bayes",
                "difference", "SE(seas)", "95% bar");
        for(int round = 8; round <= SHAPE.size(); round++){
            double[] marginBoard = new double[clusters];
            double[] marginBayes = new double[clusters];
            for(int s = 0; s < clusters; s++){
                String season = seasons.get(s);
                EraBoards.Board raw = boards.get(season);
                List<String> ids = EraGame.draft(raw, SHAPE, EraGame.keepers(raw));
                Map<String, Man> byId = new HashMap<>();
                for(Man man : harvest.get(season)){
                    byId.put(man.id(), man);
                }
                List<Man> full = new ArrayList<>();
                for(String id : ids){
                    Man man = byId.get(id);
                    if(man != null){
                        full.add(man);
                    }
                }
                // keepers sit in front of the drafted men in EraGame.draft, so
                // round r is at index keepers + r - 1
                int index = ids.size() - SHAPE.size() + round - 1;
                if(index < 0 || index >= full.size()){
                    continue;
                }
                List<Man> without = new ArrayList<>(full);
                Man dropped = full.get(index);
                without.remove(dropped);
                int weeks = raw.weeks();
                Map<Position, double[]> table = priors.get(season);
                Map<Position, Kappa> kappa = kappas.get(season);
                Map<Position, double[]> levels = levelsFor(harvest, seasons, season,
                        weeks);
                Ranker byBoard = (man, week) -> -man.overall();
                Ranker byBayes = (man, week) -> estimate(man, week - 1,
                        kappa.get(man.position()).kappa(), table,
                        levels.get(man.position())[week]);
                marginBoard[s] = seasonPoints(full, weeks, byBoard)
                        - seasonPoints(without, weeks, byBoard);
                marginBayes[s] = seasonPoints(full, weeks, byBayes)
                        - seasonPoints(without, weeks, byBayes);
            }
            double[] diff = new double[clusters];
            for(int s = 0; s < clusters; s++){
                diff[s] = marginBayes[s] - marginBoard[s];
            }
            PowerBacktest.Paired paired =
                    PowerBacktest.paired("", marginBayes, diff, clusterOf, clusters);
            System.out.printf("%-10d %12.1f %12.1f %+12.1f %10.1f %10.1f%n", round,
                    Arrays.stream(marginBoard).average().orElse(0),
                    Arrays.stream(marginBayes).average().orElse(0), paired.diff(),
                    paired.seSeason(), paired.bar());
        }
        System.out.printf("%nThe gaps this has to beat are on the live board: at pick 127"
                + " the objective bids%n56.3 for the best free back, 52.9 for the best"
                + " free receiver and 51.3 for the best%nfree tight end (BenchValueGap,"
                + " 2026-08-31). A repricing smaller than those 3-5 point%ngaps cannot"
                + " change which man is taken.%n");

        // -------------------------------------------------- the contrast
        System.out.printf("%n%nWHY B RESOLVES AND C DOES NOT%n%n");
        List<Flip> pooled = new ArrayList<>();
        for(Position position : POSITIONS){
            pooled.addAll(flips(harvest, seasons, position, 4, 1.0, priors, kappas));
        }
        PowerBacktest.Paired pick = accuracy("pick", pooled, clusters, false);
        double[] seasonDiff = new double[clusters];
        for(int s = 0; s < clusters; s++){
            seasonDiff[s] = learned[s] - board[s];
        }
        PowerBacktest.Paired season =
                PowerBacktest.paired("season", learned, seasonDiff, clusterOf, clusters);
        System.out.printf("%-34s %11s %11s %11s %9s%n", "THE CLAIM", "effect",
                "SE(season)", "effect/SE", "decisions");
        System.out.printf("%-34s %10.3f%% %10.3f%% %11.2f %9d%n",
                "B: a flip at week 4 is right", 100 * (0.5 + pick.diff()),
                100 * pick.seSeason(), pick.diff() / Math.max(1e-9, pick.seSeason()),
                pick.draws());
        System.out.printf("%-34s %11.1f %11.1f %11.2f %9d%n",
                "C: the season score moves", season.diff(), season.seSeason(),
                season.diff() / Math.max(1e-9, season.seSeason()), clusters);
        System.out.printf("%nBoth read the SAME thirteen seasons of football and both"
                + " cluster on season.%nThe difference is the estimand. A season score's"
                + " between-season variance is set by%nWHICH MEN the roster happened to"
                + " hold, and no amount of pooling averages that%naway. An accuracy is"
                + " bounded in [0,1], so its between-season variance is bounded%ntoo, and"
                + " pooling ten thousand decisions inside each season does shrink it.%n");
        System.out.printf("%nThat is the rule, and it is the transferable part: a new"
                + " channel earns its place%nby a BOUNDED, PER-DECISION claim measured"
                + " within season. A points-per-season%nclaim cannot be paid for at n=13"
                + " and should never be the price of admission.%n%n");
    }

    static void report(String name, double[] score, double[] baseline, int[] clusterOf,
                       int clusters){
        double[] diff = new double[score.length];
        for(int i = 0; i < score.length; i++){
            diff[i] = score[i] - baseline[i];
        }
        PowerBacktest.Paired paired =
                PowerBacktest.paired(name, score, diff, clusterOf, clusters);
        System.out.printf("%-26s %9.1f %+9.1f %9.1f %9.1f %9.1f %6d/%d%n", name,
                paired.mean(), paired.diff(), paired.seSeason(), paired.bar(),
                PowerBacktest.minimumDetectable(paired.seSeason(), clusters),
                paired.wins(), paired.draws());
    }
}
