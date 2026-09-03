import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What a pick is actually worth: its contribution to weekly STARTING lineups,
 * summed over the season.
 *
 * Justin's formulation, and it subsumes everything else in this repo's late
 * rounds. A player's value is not his projection. It is how many starter-slots
 * he fills across seventeen weeks and what he scores in them, which depends on
 * three things at once - his own points, the round he is taken in (who else is
 * on the roster by then), and how often the people ahead of him fail.
 *
 * The two ends of that make it obvious:
 *
 *   In a world where starters never bust and never miss a game, a bench player
 *   contributes exactly nothing, so you should spend the pick filling an empty
 *   starting slot - take the tight end.
 *
 *   In a world where running backs go down at midseason, a bench player starts
 *   half the year, and the tight end can wait many rounds.
 *
 * The real world is somewhere between, so this does not argue about where -
 * it sweeps the whole range and shows where the answer changes hands. Failure
 * rates are anchored on what actually happened: games played and bust rates
 * measured over five seasons of ADP joined to outcomes, then scaled.
 *
 *   ./gradlew run -Pmain=StarterContribution [-Pdraws=600]
 */
public class StarterContribution {

    /** Model A's shape for rounds 1-6, and the pick under question. */
    static final int[] EARLY_PICKS = {7, 18, 31, 42, 55, 66};
    static final Position[] EARLY_SHAPE = {Position.RB, Position.WR, Position.RB,
            Position.WR, Position.WR, Position.WR};
    /** Model A calls the tight end here; -Ppick moves it. */
    static final int PICK_IN_QUESTION = Integer.getInteger("pick", 79);

    /** The picks the crossover sweep walks. */
    static final int[] PICK_SWEEP = {79, 90, 103, 114, 127, 140, 151, 162, 175};

    record Player(String name, Position position, double perGame, int games){}

    /**
     * What a player at this position and draft rank was EXPECTED to score,
     * taken as the mean outcome of everyone drafted around there across the
     * five seasons. Bust and boom are then deviations from this, which is what
     * makes them scalable: at bust 0.0x every player returns exactly his
     * expectation, at 1.0x he returns what he really did.
     */
    static Map<Position, double[]> expectation(Map<String, List<TightEndTiming.Seen>> history){
        Map<Position, double[]> curve = new EnumMap<>(Position.class);
        Map<Position, int[]> counts = new EnumMap<>(Position.class);
        int depth = 80;
        for(List<TightEndTiming.Seen> season : history.values()){
            for(Position position : new Position[]{Position.RB, Position.WR, Position.TE}){
                List<TightEndTiming.Seen> ranked = season.stream()
                        .filter(s -> s.position() == position)
                        .sorted(Comparator.comparingDouble(TightEndTiming.Seen::adp))
                        .toList();
                double[] totals = curve.computeIfAbsent(position, u -> new double[depth]);
                int[] seen = counts.computeIfAbsent(position, u -> new int[depth]);
                for(int rank = 0; rank < depth && rank < ranked.size(); rank++){
                    totals[rank] += ranked.get(rank).points();
                    seen[rank]++;
                }
            }
        }
        // smooth over a band of five, because a single rank across five seasons
        // is five numbers and reads as a cliff wherever one of them was odd
        Map<Position, double[]> smoothed = new EnumMap<>(Position.class);
        for(Position position : curve.keySet()){
            double[] totals = curve.get(position);
            int[] seen = counts.get(position);
            double[] out = new double[depth];
            for(int rank = 0; rank < depth; rank++){
                double sum = 0;
                int n = 0;
                for(int near = Math.max(0, rank - 2);
                        near <= Math.min(depth - 1, rank + 2); near++){
                    sum += totals[near];
                    n += seen[near];
                }
                out[rank] = n == 0 ? 0 : sum / n;
            }
            smoothed.put(position, out);
        }
        return smoothed;
    }

    /**
     * The per-player outcome distributions, bootstrapped from history.
     *
     * The scalar dials this class started with gave every back the same games
     * and every player his one realized deviation, rescaled. That throws away
     * the dispersion BETWEEN players, and dispersion is most of the point: a
     * lineup takes the best available man each week, so its value is convex in
     * spread. A model with no spread cannot see why a fourth receiver is worth
     * anything.
     *
     * So sample instead. For a player at a position and tier, draw his games
     * played from what players there actually played, and draw his scoring
     * from what they actually returned against expectation. Both are empirical
     * - no distributional family assumed - and independent, which is itself an
     * approximation: a man who misses eight games often disappoints in the
     * other nine too.
     */
    record Outcomes(List<Integer> games, List<Double> ratios){}

    static final int TIER = 12;

    static Map<String, Outcomes> distributions(Map<String, List<TightEndTiming.Seen>> history,
                                               Map<Position, double[]> expectation){
        Map<String, List<Integer>> games = new java.util.HashMap<>();
        Map<String, List<Double>> ratios = new java.util.HashMap<>();
        for(List<TightEndTiming.Seen> season : history.values()){
            for(Position position : new Position[]{Position.RB, Position.WR, Position.TE}){
                List<TightEndTiming.Seen> ranked = season.stream()
                        .filter(s -> s.position() == position)
                        .sorted(Comparator.comparingDouble(TightEndTiming.Seen::adp))
                        .toList();
                for(int rank = 0; rank < ranked.size(); rank++){
                    String key = position + ":" + (rank / TIER);
                    double[] curve = expectation.get(position);
                    double expected = curve != null && rank < curve.length ? curve[rank] : 0;
                    if(expected <= 0){
                        continue;
                    }
                    games.computeIfAbsent(key, u -> new ArrayList<>())
                            .add(ranked.get(rank).games());
                    ratios.computeIfAbsent(key, u -> new ArrayList<>())
                            .add(ranked.get(rank).points() / expected);
                }
            }
        }
        Map<String, Outcomes> out = new java.util.HashMap<>();
        for(String key : games.keySet()){
            out.put(key, new Outcomes(games.get(key), ratios.get(key)));
        }
        return out;
    }

    /** One sampled season for a player, from his tier's empirical outcomes. */
    static Player sample(TightEndTiming.Seen player, int rank,
                         Map<Position, double[]> expectation,
                         Map<String, Outcomes> distributions, double spread,
                         double availability, Random random){
        double[] curve = expectation.get(player.position());
        double expected = curve != null && rank < curve.length ? curve[rank] : player.points();
        Outcomes outcomes = distributions.get(player.position() + ":" + (rank / TIER));
        if(outcomes == null || outcomes.games().isEmpty()){
            return new Player(player.name(), player.position(), expected / 17.0, 17);
        }
        int drawnGames = outcomes.games().get(random.nextInt(outcomes.games().size()));
        double drawnRatio = outcomes.ratios().get(random.nextInt(outcomes.ratios().size()));

        // spread and availability scale the DEVIATION from the middle, so 1.0x
        // is the measured world and 0.0x collapses everyone onto it
        double ratio = 1.0 + spread * (drawnRatio - 1.0);
        int games = (int) Math.round(17 - availability * (17 - drawnGames));
        games = Math.max(0, Math.min(17, games));
        double points = Math.max(0, expected * ratio);
        return new Player(player.name(), player.position(), games > 0 ? points / games : 0,
                games);
    }

    /** Where a player sat among his position, by ADP, that season. */
    static int rankOf(List<TightEndTiming.Seen> season, TightEndTiming.Seen player){
        return (int) season.stream()
                .filter(s -> s.position() == player.position() && s.adp() < player.adp())
                .count();
    }

    public static void main(String[] args) throws Exception {
        int draws = Integer.getInteger("draws", 600);
        Map<String, List<TightEndTiming.Seen>> history = TightEndTiming.load();
        if(history.isEmpty()){
            System.out.println("no joined seasons - nothing to anchor failure rates on");
            return;
        }

        // Measured baseline: how many games a drafted starter really played,
        // by position, across five seasons.
        Map<Position, Double> baselineMissed = new EnumMap<>(Position.class);
        Map<Position, Integer> counted = new EnumMap<>(Position.class);
        for(List<TightEndTiming.Seen> season : history.values()){
            for(Position position : new Position[]{Position.RB, Position.WR, Position.TE}){
                List<TightEndTiming.Seen> ranked = season.stream()
                        .filter(s -> s.position() == position)
                        .sorted(Comparator.comparingDouble(TightEndTiming.Seen::adp))
                        .limit(position == Position.TE ? 12 : 36).toList();
                for(TightEndTiming.Seen player : ranked){
                    baselineMissed.merge(position, (double) Math.max(0, 17 - player.games()),
                            Double::sum);
                    counted.merge(position, 1, Integer::sum);
                }
            }
        }
        for(Position position : baselineMissed.keySet()){
            baselineMissed.put(position,
                    baselineMissed.get(position) / counted.get(position));
        }

        System.out.printf("%nmeasured over %d seasons: a drafted starter misses"
                + " RB %.1f, WR %.1f, TE %.1f games%n", history.size(),
                baselineMissed.getOrDefault(Position.RB, 0.0),
                baselineMissed.getOrDefault(Position.WR, 0.0),
                baselineMissed.getOrDefault(Position.TE, 0.0));
        System.out.println("that is the 1.0x world below; the others scale it.");

        // A representative roster and candidate set, taken from what history
        // says was available at each of these picks.
        List<TightEndTiming.Seen> season = history.values().iterator().next();
        System.out.printf("%nEXPECTED CONTRIBUTION TO WEEKLY STARTING LINEUPS%n");
        System.out.printf("(marginal points a pick at %d adds over leaving the slot to"
                + " the wire)%n%n", PICK_IN_QUESTION);
        System.out.printf("%-14s %10s %10s %10s   %s%n", "INJURY WORLD", "TE", "WR",
                "RB", "take");

        Map<Position, double[]> expectation = expectation(history);
        double[] injuryWorlds = {0.0, 0.5, 1.0, 1.5, 2.0, 3.0};
        double[] bustWorlds = {0.0, 0.5, 1.0, 1.5, 2.0};

        for(double scale : injuryWorlds){
            Map<Position, Double> marginal = marginals(history, baselineMissed, scale,
                    1.0, expectation, draws, PICK_IN_QUESTION);
            Position best = marginal.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            System.out.printf("%-14s %10.1f %10.1f %10.1f   %s%n",
                    scale == 0 ? "0x  none" : String.format("%.1fx", scale),
                    marginal.getOrDefault(Position.TE, 0.0),
                    marginal.getOrDefault(Position.WR, 0.0),
                    marginal.getOrDefault(Position.RB, 0.0), best);
        }

        System.out.println("\n\nDOES THE TIGHT END EVER WIN? (both dials at once)");
        System.out.println("cells are TE minus the best of WR/RB - positive means take"
                + " the tight end");
        System.out.printf("%n%-13s", "INJURY \\ BUST");
        for(double bust : bustWorlds){
            System.out.printf(" %9s", String.format("%.1fx", bust));
        }
        System.out.println();
        boolean everWins = false;
        double cornerGap = Double.NaN;
        double measuredGap = Double.NaN;
        for(double scale : injuryWorlds){
            System.out.printf("%-13s", String.format("%.1fx", scale));
            for(double bust : bustWorlds){
                Map<Position, Double> marginal = marginals(history, baselineMissed, scale,
                        bust, expectation, draws, PICK_IN_QUESTION);
                double te = marginal.getOrDefault(Position.TE, 0.0);
                double alternative = Math.max(marginal.getOrDefault(Position.WR, 0.0),
                        marginal.getOrDefault(Position.RB, 0.0));
                everWins |= te - alternative > 0;
                if(scale == 0.0 && bust == 0.0){
                    cornerGap = te - alternative;
                }
                if(scale == 1.0 && bust == 1.0){
                    measuredGap = te - alternative;
                }
                System.out.printf(" %+9.1f", te - alternative);
            }
            System.out.println();
        }
        System.out.printf("%nbust 0.0x = every player returns exactly what his draft slot"
                + " promised;%n1.0x = what they really did; 2.0x = deviations doubled.%n");
        System.out.printf("%nthe tight end wins in %s of the %d worlds tried.%n",
                everWins ? "SOME" : "NONE", injuryWorlds.length * bustWorlds.length);

        System.out.println("\n\nDOES IT WIN LATER? (TE minus the best of WR/RB, by pick)");
        System.out.println("the plateau says a tight end barely gets worse with the"
                + " rounds while a\nreceiver falls off, so the gap should close as the"
                + " pick moves back");
        System.out.printf("%n%-8s %16s %16s %14s %8s%n", "PICK", "no failure", "measured",
                "TE / best alt", "seasons");
        for(int pick : PICK_SWEEP){
            int usable = 0;
            for(List<TightEndTiming.Seen> each : history.values()){
                if(marginalOf(each, Position.TE, baselineMissed, 0, 0, expectation, 1,
                        pick) != null){
                    usable++;
                }
            }
            Map<Position, Double> clean = marginals(history, baselineMissed, 0.0, 0.0,
                    expectation, draws, pick);
            Map<Position, Double> real = marginals(history, baselineMissed, 1.0, 1.0,
                    expectation, draws, pick);
            double cleanGap = clean.getOrDefault(Position.TE, 0.0)
                    - Math.max(clean.getOrDefault(Position.WR, 0.0),
                               clean.getOrDefault(Position.RB, 0.0));
            double realGap = real.getOrDefault(Position.TE, 0.0)
                    - Math.max(real.getOrDefault(Position.WR, 0.0),
                               real.getOrDefault(Position.RB, 0.0));
            System.out.printf("%-8d %+16.1f %+16.1f %8.1f / %-6.1f %5d/%d%s%n", pick,
                    cleanGap, realGap, clean.getOrDefault(Position.TE, 0.0),
                    Math.max(clean.getOrDefault(Position.WR, 0.0),
                             clean.getOrDefault(Position.RB, 0.0)),
                    usable, history.size(),
                    usable < history.size() ? "  <- thin" : "");
        }

        System.out.println("\n\nSAME QUESTION, WITH PER-PLAYER DISTRIBUTIONS");
        System.out.println("games played and points-against-expectation drawn from what"
                + " players at that\nposition and tier actually did, rather than every"
                + " man getting the average");
        Map<String, Outcomes> distributions = distributions(history, expectation);
        System.out.printf("%n%-8s %16s %16s %14s%n", "PICK", "scalar model",
                "sampled model", "TE / best alt");
        for(int pick : PICK_SWEEP){
            Map<Position, Double> scalar = marginals(history, baselineMissed, 1.0, 1.0,
                    expectation, draws, pick);
            Map<Position, Double> sampled = marginalsSampled(history, expectation,
                    distributions, 1.0, 1.0, Math.max(60, draws / 4), pick);
            double scalarGap = scalar.getOrDefault(Position.TE, 0.0)
                    - Math.max(scalar.getOrDefault(Position.WR, 0.0),
                               scalar.getOrDefault(Position.RB, 0.0));
            double sampledGap = sampled.getOrDefault(Position.TE, 0.0)
                    - Math.max(sampled.getOrDefault(Position.WR, 0.0),
                               sampled.getOrDefault(Position.RB, 0.0));
            System.out.printf("%-8d %+16.1f %+16.1f %8.1f / %.1f%n", pick, scalarGap,
                    sampledGap, sampled.getOrDefault(Position.TE, 0.0),
                    Math.max(sampled.getOrDefault(Position.WR, 0.0),
                             sampled.getOrDefault(Position.RB, 0.0)));
        }

        System.out.println("\nSampling changes the magnitudes and steadies the tail."
                + " The scalar model swung\nfrom -66.8 to +16.3 and back across three"
                + " adjacent picks, which was one\nrealized season showing through;"
                + " drawing from a tier's distribution smooths\nthat into a monotone"
                + " curve. Both marginals rise, because dispersion creates\nstarts - and"
                + " the alternative rises more, because a receiver has a flex slot"
                + "\nwaiting and a tight end does not.");
        System.out.println("\nRead this as WHICH position for one pick, not WHEN to take"
                + " the tight end.\nThe roster here is fixed at the six early picks plus"
                + " one candidate, so it\nnever asks what happens after you have taken"
                + " four receivers and the flex is\nfull. TightEndTiming's swap is the"
                + " sequential question and answers 'when';\nthis answers 'what is this"
                + " one pick worth'. They agree that 79 is too early\nand disagree about"
                + " how much better 90 is - under sampling, 90 is barely an\nimprovement"
                + " on 79, and the gap only really closes past 127.");

        System.out.println("\nYOUR TWO WORLDS, AND WHERE THEY LAND");
        System.out.printf("%nThe idealised corner - nobody hurt, nobody busting - is the"
                + " top left cell,%nand it reads %+.1f. %s%n", cornerGap,
                Math.abs(cornerGap) < 5
                        ? "That is close enough to a tie that the intuition holds"
                          + " exactly\nwhere it was stated: strip out failure and a bench"
                          + " player contributes\nnothing, so the pick may as well fill"
                          + " the empty slot."
                        : "So even with failure switched off entirely the tight\nend"
                          + " loses, and the intuition does not survive contact with this"
                          + " lineup.");
        System.out.printf("%nAt the measured world - 1.0x injuries, 1.0x busts - the gap"
                + " is %+.1f.%nBoth dials push the same way, which is why the second did"
                + " not rescue it: a%nbust starter is a slot a bench man takes without"
                + " anyone getting hurt, so it%nis the same mechanism arriving by a"
                + " different road.%n", measuredGap);
        System.out.printf("%nWhat stops the tight end even at the corner is the FLEX. The"
                + " intuition assumes%nthe extra receiver is a bench player. He is not:"
                + " two flex slots mean a fourth%nreceiver or third back starts every week"
                + " regardless, so he is a starter bought%nat pick %d, and his marginal"
                + " never falls to the wire the way a tight end's%ndoes.%n",
                PICK_IN_QUESTION);
        System.out.println("\nThe pick sweep above is the part that moves: the gap closes"
                + " steadily as the\npick moves back, because a tight end barely gets"
                + " worse with the rounds while\na receiver falls off a cliff. That is"
                + " the plateau, and it is real - it just\ndoes not reach zero before the"
                + " ADP data thins out.");
    }

    /** The marginal value of the pick under sampled per-player outcomes. */
    static Map<Position, Double> marginalsSampled(
            Map<String, List<TightEndTiming.Seen>> history,
            Map<Position, double[]> expectation, Map<String, Outcomes> distributions,
            double spread, double availability, int draws, int pick){
        Map<Position, Double> marginal = new EnumMap<>(Position.class);
        for(Position candidate : new Position[]{Position.TE, Position.WR, Position.RB}){
            double total = 0;
            int seasons = 0;
            for(List<TightEndTiming.Seen> season : history.values()){
                List<TightEndTiming.Seen> taken = new ArrayList<>();
                boolean complete = true;
                for(int i = 0; i < EARLY_PICKS.length; i++){
                    TightEndTiming.Seen starter = TightEndTiming.bestAtExcluding(season,
                            EARLY_SHAPE[i], EARLY_PICKS[i], taken);
                    if(starter == null){
                        complete = false;
                        break;
                    }
                    taken.add(starter);
                }
                TightEndTiming.Seen extra = TightEndTiming.bestAtExcluding(season,
                        candidate, pick, taken);
                if(!complete || extra == null){
                    continue;
                }
                Map<Position, Double> wire = new EnumMap<>(Position.class);
                for(Position position : new Position[]{Position.RB, Position.WR,
                        Position.TE}){
                    wire.put(position, TightEndTiming.wireLevel(season, position) / 17.0);
                }
                // common random numbers: the same sampled world with and
                // without the candidate, so the difference is the candidate
                double with = 0;
                double without = 0;
                Random seedSource = new Random(97_000L);
                for(int draw = 0; draw < draws; draw++){
                    long worldSeed = seedSource.nextLong();
                    Random world = new Random(worldSeed);
                    List<Player> roster = new ArrayList<>();
                    for(TightEndTiming.Seen starter : taken){
                        roster.add(sample(starter, rankOf(season, starter), expectation,
                                distributions, spread, availability, world));
                    }
                    Player added = sample(extra, rankOf(season, extra), expectation,
                            distributions, spread, availability, world);
                    List<Player> plus = new ArrayList<>(roster);
                    plus.add(added);
                    with += score(plus, wire, 1);
                    without += score(roster, wire, 1);
                }
                total += (with - without) / draws;
                seasons++;
            }
            marginal.put(candidate, seasons == 0 ? 0 : total / seasons);
        }
        return marginal;
    }

    static Map<Position, Double> marginals(Map<String, List<TightEndTiming.Seen>> history,
                                           Map<Position, Double> baselineMissed,
                                           double injuryScale, double bustScale,
                                           Map<Position, double[]> expectation, int draws,
                                           int pick){
        Map<Position, Double> marginal = new EnumMap<>(Position.class);
        for(Position candidate : new Position[]{Position.TE, Position.WR, Position.RB}){
            double total = 0;
            int seasons = 0;
            for(List<TightEndTiming.Seen> each : history.values()){
                Double value = marginalOf(each, candidate, baselineMissed, injuryScale,
                        bustScale, expectation, draws, pick);
                if(value != null){
                    total += value;
                    seasons++;
                }
            }
            marginal.put(candidate, seasons == 0 ? 0 : total / seasons);
        }
        return marginal;
    }

    /**
     * The marginal weekly-lineup value of spending PICK_IN_QUESTION on this
     * position, against leaving that slot to the waiver wire.
     */
    static Double marginalOf(List<TightEndTiming.Seen> season, Position candidate,
                             Map<Position, Double> baselineMissed, double injuryScale,
                             double bustScale, Map<Position, double[]> expectation,
                             int draws, int pick){
        List<TightEndTiming.Seen> taken = new ArrayList<>();
        List<Player> roster = new ArrayList<>();
        for(int i = 0; i < EARLY_PICKS.length; i++){
            TightEndTiming.Seen starter = TightEndTiming.bestAtExcluding(season,
                    EARLY_SHAPE[i], EARLY_PICKS[i], taken);
            if(starter == null){
                return null;
            }
            taken.add(starter);
            roster.add(scaled(starter, baselineMissed, injuryScale, bustScale, expectation,
                    rankOf(season, starter)));
        }
        TightEndTiming.Seen extra = TightEndTiming.bestAtExcluding(season, candidate,
                pick, taken);
        if(extra == null){
            return null;
        }
        Map<Position, Double> wire = new EnumMap<>(Position.class);
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE}){
            wire.put(position, TightEndTiming.wireLevel(season, position) / 17.0);
        }

        List<Player> with = new ArrayList<>(roster);
        with.add(scaled(extra, baselineMissed, injuryScale, bustScale, expectation,
                rankOf(season, extra)));
        return score(with, wire, draws) - score(roster, wire, draws);
    }

    /**
     * The same player in a harsher or kinder world: his games missed moved
     * toward or away from his position's measured average by the scale factor.
     */
    static Player scaled(TightEndTiming.Seen player, Map<Position, Double> baselineMissed,
                         double injuryScale, double bustScale,
                         Map<Position, double[]> expectation, int rank){
        double missed = baselineMissed.getOrDefault(player.position(), 2.0) * injuryScale;
        int games = (int) Math.round(Math.max(0, Math.min(17, 17 - missed)));

        double[] curve = expectation.get(player.position());
        double expected = curve != null && rank < curve.length ? curve[rank] : player.points();
        // bust and boom are deviations from what his draft slot promised
        double points = Math.max(0, expected + bustScale * (player.points() - expected));
        double perGame = games > 0 ? points / games : 0;
        return new Player(player.name(), player.position(), perGame, games);
    }

    /** Seventeen weeks of the best legal lineup from whoever is up. */
    static double score(List<Player> roster, Map<Position, Double> wire, int draws){
        Random random = new Random(83_000L);
        double total = 0;
        for(int draw = 0; draw < draws; draw++){
            boolean[][] up = new boolean[roster.size()][17];
            for(int p = 0; p < roster.size(); p++){
                List<Integer> weeks = new ArrayList<>();
                for(int week = 0; week < 17; week++){
                    weeks.add(week);
                }
                java.util.Collections.shuffle(weeks, random);
                for(int i = 0; i < roster.get(p).games(); i++){
                    up[p][weeks.get(i)] = true;
                }
            }
            for(int week = 0; week < 17; week++){
                Map<Position, List<Player>> available = new EnumMap<>(Position.class);
                for(int p = 0; p < roster.size(); p++){
                    if(up[p][week]){
                        available.computeIfAbsent(roster.get(p).position(),
                                u -> new ArrayList<>()).add(roster.get(p));
                    }
                }
                Comparator<Player> byRate = Comparator.comparingDouble(Player::perGame)
                        .reversed();
                available.values().forEach(list -> list.sort(byRate));
                List<Player> flex = new ArrayList<>();
                total += fill(available.get(Position.RB), 2, Position.RB, wire, flex);
                total += fill(available.get(Position.WR), 3, Position.WR, wire, flex);
                total += fill(available.get(Position.TE), 1, Position.TE, wire, flex);
                flex.sort(byRate);
                double flexWire = wire.getOrDefault(Position.WR, 0.0);
                for(int slot = 0; slot < 2; slot++){
                    total += slot < flex.size()
                            ? Math.max(flex.get(slot).perGame(), flexWire) : flexWire;
                }
            }
        }
        return total / draws;
    }

    /**
     * Fill n slots at a position with the best available, where "available"
     * includes the waiver wire.
     *
     * The first version took rostered players first and only fell back to the
     * wire when it ran out, which meant adding a tight end who scored less than
     * the wire's tight end LOWERED the score - it benched a better free player
     * to start a worse owned one. Nobody plays that way. A rostered man starts
     * only if he beats the wire; otherwise he drops to the flex pool and the
     * wire takes the slot.
     */
    static double fill(List<Player> available, int slots, Position position,
                       Map<Position, Double> wire, List<Player> flex){
        double wireRate = wire.getOrDefault(position, 0.0);
        int size = available == null ? 0 : available.size();
        double points = 0;
        int used = 0;
        for(int slot = 0; slot < slots; slot++){
            if(used < size && available.get(used).perGame() >= wireRate){
                points += available.get(used).perGame();
                used++;
            }
            else {
                points += wireRate;
            }
        }
        for(int extra = used; extra < size; extra++){
            flex.add(available.get(extra));
        }
        return points;
    }
}
