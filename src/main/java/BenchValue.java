import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rounds 8-9: the first two picks after the starting nine is full. Model A
 * is silent here (every position reads the same, because nothing a bench man
 * does changes the starting-nine projection) and Model B's season-total
 * simulation is silent for a deeper reason - season totals have already
 * absorbed the games a starter missed, so there is no week left over for a
 * backup to fill.
 *
 * So do not simulate. Measure. For every pick this league actually made in
 * rounds 8-16 across five seasons, join it to what that player actually
 * scored THAT season (not the next one - that is the keeper question, and
 * LateRoundValue already answers it).
 *
 * The number that matters is not his point total. It is his total over the
 * WAIVER WIRE, floored at zero:
 *
 *     realized = max(0, actual - wireLine)
 *
 * The floor is the whole point. A bench player who busts costs nothing but
 * the roster spot, because you drop him in week 4 and stream the wire
 * instead - which is exactly what this league does. That makes a late pick
 * a call option struck at the wire line, and the gap between the naive mean
 * and the floored mean is what the right to drop him is worth.
 *
 * Usage:
 *   ./gradlew run -Pmain=BenchValue
 */
public class BenchValue {

    /** A bench pick and what actually became of him that same season. */
    record Bench(String name, Position position, int round, String season,
                 double points, double overWire, boolean rookie, boolean young,
                 boolean hasNextSeason, double nextOverWire){}

    /** Everything the join produced: the picks and the two lines they are judged against. */
    record History(List<Bench> benches, Map<String, Map<Position, Double>> starterBar,
                   Map<String, Map<Position, Double>> wireBar){}

    /**
     * Mean points over the wire for a rounds 8-9 pick at each position, floored
     * at zero. This is the base rate other tools adjust; it lives here so there
     * is one source of truth for it rather than a number copied out of output.
     */
    /**
     * The round bands, as the thing a model is asked to reproduce.
     *
     * The three numbers this league's own history produced - 44.0 in rounds
     * 8-9, 32.8 in 10-12, 31.2 in 13-16 - have been quoted in prose since they
     * were measured and nothing has ever ASKED for them programmatically, so
     * anything wanting to fit against them had to retype them. That is the
     * one-home rule and the prose-drift trap at once: a constant copied out of
     * output stops tracking the calculation the moment either moves.
     *
     * Each band comes back as {mean, two standard errors, n}. The bar matters
     * as much as the number: 111 picks of a distribution whose top ten run from
     * 125 to 172 while its median is zero is not a precise estimate of
     * anything, and a fit that lands inside the bar has not been told much.
     *
     * NOTE WHAT THIS QUANTITY IS, because it is not a lineup marginal. It is
     * the man's OWN season over the wire line, floored at zero, whether or not
     * he ever started. A bench receiver who scores 150 behind three better
     * receivers counts his full 150 over the wire here and adds nothing at all
     * to a lineup. See {@link BenchCalibration}, which is about exactly that
     * gap.
     */
    public static Map<String, double[]> overWireByBand(AAAConfiguration configuration){
        Map<String, List<Double>> collected = new java.util.LinkedHashMap<>();
        collected.put(ROUNDS_8_9, new ArrayList<>());
        collected.put(ROUNDS_10_12, new ArrayList<>());
        collected.put(ROUNDS_13_16, new ArrayList<>());
        for(Bench bench : gather(configuration).benches()){
            collected.get(band(bench.round())).add(bench.overWire());
        }
        Map<String, double[]> out = new java.util.LinkedHashMap<>();
        for(Map.Entry<String, List<Double>> entry : collected.entrySet()){
            List<Double> values = entry.getValue();
            double mean = values.stream().mapToDouble(Double::doubleValue)
                    .average().orElse(0);
            out.put(entry.getKey(), new double[]{mean, twoStandardErrorsOf(values),
                    values.size()});
        }
        return out;
    }

    public static final String ROUNDS_8_9 = "rounds 8-9";
    public static final String ROUNDS_10_12 = "rounds 10-12";
    public static final String ROUNDS_13_16 = "rounds 13-16";

    /** Which band a round falls in. The same split {@link #report} prints. */
    public static String band(int round){
        return round <= 9 ? ROUNDS_8_9 : round <= 12 ? ROUNDS_10_12 : ROUNDS_13_16;
    }

    /**
     * The same rates, measured over the band the ADVISED ROUND falls in.
     *
     * The no-argument form collects rounds 1-9, which is exactly rounds 8-9 in
     * practice - the nine-round game spends 1-7 on starters, so there are no
     * earlier bench picks and both windows return the identical 111 men. That
     * is the right population for a round 8-9 pick.
     *
     * It is the WRONG one for a round 12 pick, and DraftNight.benchGuidance
     * fires at round >= 8, which on the sixteen-round schedule runs to 16. The
     * rates fall a long way across the draft - QB 87.7 over the wire in rounds
     * 8-9 against 45.0 in rounds 10-16, RB 46.1 against 39.1 - so a late pick
     * was being advised with early-pick numbers, overstating bench value by
     * forty to ninety per cent. The ORDERING is the same in both, so the
     * conclusion benchGuidance draws does not change; the magnitudes on screen
     * were simply wrong.
     */
    public static Map<Position, Double> overWireByPosition(AAAConfiguration configuration,
                                                           int forRound){
        int from = forRound <= 9 ? 1 : forRound <= 12 ? 10 : 13;
        int to = forRound <= 9 ? 9 : forRound <= 12 ? 12 : 16;
        Map<Position, List<Double>> collected = new EnumMap<>(Position.class);
        for(Bench bench : gather(configuration).benches()){
            if(bench.round() >= from && bench.round() <= to){
                collected.computeIfAbsent(bench.position(), u -> new ArrayList<>())
                        .add(bench.overWire());
            }
        }
        Map<Position, Double> means = new EnumMap<>(Position.class);
        for(Map.Entry<Position, List<Double>> entry : collected.entrySet()){
            means.put(entry.getKey(), entry.getValue().stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0));
        }
        return means;
    }

    public static Map<Position, Double> overWireByPosition(AAAConfiguration configuration){
        Map<Position, List<Double>> collected = new EnumMap<>(Position.class);
        for(Bench bench : gather(configuration).benches()){
            if(bench.round() <= 9){
                collected.computeIfAbsent(bench.position(), u -> new ArrayList<>())
                        .add(bench.overWire());
            }
        }
        Map<Position, Double> means = new EnumMap<>(Position.class);
        for(Map.Entry<Position, List<Double>> entry : collected.entrySet()){
            means.put(entry.getKey(), entry.getValue().stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0));
        }
        return means;
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        History history = gather(configuration);
        List<Bench> benches = history.benches();
        Map<String, Map<Position, Double>> starterBar = history.starterBar();
        Map<String, Map<Position, Double>> wireBar = history.wireBar();
        report(benches, starterBar, wireBar);
    }

    static History gather(AAAConfiguration configuration){
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();
        Map<Position, Integer> wireRanks = InsuranceTest.replacementRanks(configuration);

        List<Bench> benches = new ArrayList<>();
        Map<String, Map<Position, Double>> starterBar = new HashMap<>();
        Map<String, Map<Position, Double>> wireBar = new HashMap<>();
        for(int i = 0; i < drafts.size() && i < seasons.size(); i++){
            String season = seasons.get(i);
            if(season == null){
                continue;
            }
            Map<String, Double> actuals;
            try {
                // Through the dispatcher, not the raw feed. LeagueActuals says
                // it plainly - "anything that grades outcomes should call the
                // dispatchers rather than the raw feed, so one switch moves all
                // of it at once" - and this file was one of the places still
                // reading pts_half_ppr directly. With the flag off it is
                // byte-identical; with -PleagueScoredActuals=true the target
                // numbers arrive in the SAME scoring the model values a roster
                // in, which is the only way the two are comparable at all.
                actuals = LeagueActuals.seasonPoints(season);
            }
            catch(Exception missing){
                continue;
            }
            if(actuals.isEmpty()){
                continue;
            }
            // The keeper term needs the FOLLOWING season too. The most recent
            // season has no following season yet; those picks are marked rather
            // than scored zero, so an unplayable year cannot masquerade as a bust.
            Map<String, Double> nextActuals = new HashMap<>();
            try {
                nextActuals = LeagueActuals.seasonPoints(
                        String.valueOf(Integer.parseInt(season) + 1));
            }
            catch(Exception noNextSeason){
                nextActuals = new HashMap<>();
            }
            boolean hasNext = !nextActuals.isEmpty();
            Map<Position, Double> nextWire = new EnumMap<>(Position.class);
            if(hasNext){
                Map<Position, List<Double>> nextByPosition = new EnumMap<>(Position.class);
                for(Map.Entry<String, Double> entry : nextActuals.entrySet()){
                    Player player = Player.getPlayerFromSIDV2(entry.getKey());
                    if(player != null && StartingLineup.isSkillPosition(player.position)){
                        nextByPosition.computeIfAbsent(player.position,
                                u -> new ArrayList<>()).add(entry.getValue());
                    }
                }
                for(List<Double> values : nextByPosition.values()){
                    values.sort(Comparator.reverseOrder());
                }
                for(Position position : new Position[]{Position.QB, Position.RB,
                        Position.WR, Position.TE}){
                    nextWire.put(position, atRank(nextByPosition.get(position),
                            wireRanks.getOrDefault(position, 24)));
                }
            }
            Map<Position, List<Double>> byPosition = new EnumMap<>(Position.class);
            for(Map.Entry<String, Double> entry : actuals.entrySet()){
                Player player = Player.getPlayerFromSIDV2(entry.getKey());
                if(player != null && StartingLineup.isSkillPosition(player.position)){
                    byPosition.computeIfAbsent(player.position, u -> new ArrayList<>())
                            .add(entry.getValue());
                }
            }
            for(List<Double> values : byPosition.values()){
                values.sort(Comparator.reverseOrder());
            }
            // the starter line (12-team: QB12/RB24/WR36/TE12) and, separately,
            // the wire line - the best man left undrafted, measured from this
            // league's own full 16-round histories.
            Map<Position, Double> bar = new EnumMap<>(Position.class);
            Map<Position, Double> wire = new EnumMap<>(Position.class);
            int[] ranks = {12, 24, 36, 12};
            Position[] positions = {Position.QB, Position.RB, Position.WR, Position.TE};
            for(int p = 0; p < positions.length; p++){
                bar.put(positions[p], atRank(byPosition.get(positions[p]), ranks[p]));
                wire.put(positions[p], atRank(byPosition.get(positions[p]),
                        wireRanks.getOrDefault(positions[p], ranks[p] * 2)));
            }
            starterBar.put(season, bar);
            wireBar.put(season, wire);

            java.util.Set<String> rookies = HistoricalProjections.rookiesForSeason(
                    configuration, season);
            java.util.Set<String> young = HistoricalProjections.youngForSeason(
                    configuration, season, 2);
            for(JsonElement element : drafts.get(i)){
                JsonObject pick = element.getAsJsonObject();
                int round = pick.get("round").getAsInt();
                JsonElement keeper = pick.get("is_keeper");
                if(round < 8 || round > 16
                        || (keeper != null && !keeper.isJsonNull()
                            && keeper.getAsBoolean())){
                    continue;
                }
                String id = pick.get("player_id").getAsString();
                Player player = Player.getPlayerFromSIDV2(id);
                if(player == null || !StartingLineup.isSkillPosition(player.position)){
                    continue;
                }
                double points = actuals.getOrDefault(id, 0.0);
                double overWire = Math.max(0.0,
                        points - wire.getOrDefault(player.position, 0.0));
                double nextOverWire = hasNext
                        ? Math.max(0.0, nextActuals.getOrDefault(id, 0.0)
                            - nextWire.getOrDefault(player.position, 0.0))
                        : 0.0;
                benches.add(new Bench(player.firstName + " " + player.lastName,
                        player.position, round, season, points, overWire,
                        rookies.contains(id), young.contains(id), hasNext,
                        nextOverWire));
            }
        }

        return new History(benches, starterBar, wireBar);
    }

    static void report(List<Bench> benches, Map<String, Map<Position, Double>> starterBar,
                       Map<String, Map<Position, Double>> wireBar){
        System.out.printf("%d picks in rounds 8-16 across %d seasons, joined to the"
                + " SAME season's actual points.%n", benches.size(), starterBar.size());
        Map<Position, Integer> wireRanks = InsuranceTest.replacementRanks(
                AAAConfiguration.getInstance());
        System.out.printf("wire line = the best undrafted man at each position:"
                + " QB%d RB%d WR%d TE%d%n%n",
                wireRanks.getOrDefault(Position.QB, 0),
                wireRanks.getOrDefault(Position.RB, 0),
                wireRanks.getOrDefault(Position.WR, 0),
                wireRanks.getOrDefault(Position.TE, 0));

        List<Bench> eight = benches.stream().filter(b -> b.round() <= 9).toList();
        System.out.println("ROUNDS 8-9 ONLY - the two picks in question");
        System.out.printf("%-6s %5s %9s %10s %12s %8s %10s%n", "POS", "n", "hit rate",
                "mean pts", "over wire", "+/-2se", "if it hits");
        for(Position position : new Position[]{Position.QB, Position.RB,
                Position.WR, Position.TE}){
            List<Bench> group = eight.stream()
                    .filter(b -> b.position() == position).toList();
            if(group.isEmpty()){
                continue;
            }
            long hits = group.stream().filter(b -> b.points()
                    >= starterBar.get(b.season()).getOrDefault(b.position(), 0.0)).count();
            double whenHit = group.stream().filter(b -> b.points()
                    >= starterBar.get(b.season()).getOrDefault(b.position(), 0.0))
                    .mapToDouble(Bench::overWire).average().orElse(0);
            System.out.printf("%-6s %5d %8.0f%% %10.1f %12.1f %8.1f %10.1f%n", position,
                    group.size(), 100.0 * hits / group.size(),
                    group.stream().mapToDouble(Bench::points).average().orElse(0),
                    group.stream().mapToDouble(Bench::overWire).average().orElse(0),
                    twoStandardErrors(group), whenHit);
        }

        System.out.printf("%n%-18s %6s %9s %10s %12s%n", "GROUP", "n", "hit rate",
                "mean pts", "over wire");
        band("rounds 8-9", benches.stream().filter(b -> b.round() <= 9).toList(),
                starterBar);
        band("rounds 10-12", benches.stream().filter(b -> b.round() >= 10
                && b.round() <= 12).toList(), starterBar);
        band("rounds 13-16", benches.stream().filter(b -> b.round() >= 13).toList(),
                starterBar);
        band("rookies (8-9)", eight.stream().filter(Bench::rookie).toList(), starterBar);
        band("young <=2yr (8-9)", eight.stream().filter(Bench::young).toList(),
                starterBar);
        band("veterans (8-9)", eight.stream().filter(b -> !b.young()).toList(),
                starterBar);

        System.out.println("\nthe ten best rounds 8-9 picks this league ever made:");
        eight.stream()
                .sorted(Comparator.comparingDouble(Bench::overWire).reversed())
                .limit(10)
                .forEach(b -> System.out.printf("   %-24s %-3s r%-3d %s -> %.1f pts"
                        + " (%.1f over wire)%n", b.name(), b.position(), b.round(),
                        b.season(), b.points(), b.overWire()));

        double naive = eight.stream().mapToDouble(b -> b.points()
                - wireBar.get(b.season()).getOrDefault(b.position(), 0.0))
                .average().orElse(0);
        double floored = eight.stream().mapToDouble(Bench::overWire).average().orElse(0);
        System.out.printf("%nheld all season, a rounds 8-9 pick averaged %.1f over the"
                + " wire.%nwith the right to drop him, %.1f. the difference, %.1f pts,"
                + "%nis what dropping busts is worth - and it is why a bench pick is"
                + "%nan option, not an asset.%n", naive, floored, floored - naive);
        System.out.println("\n'hit' = reached a startable line THAT season"
                + " (QB12/RB24/WR36/TE12).\nhit rates are NOT comparable across"
                + " positions - QB12 of ~32 starting quarterbacks is a far\neasier"
                + " bar than WR36 of ~90. only the over-wire column compares,"
                + " and it is\nin raw points, which flatters QB because this league"
                + " pays 6 per passing TD.");
    }

    /**
     * Two standard errors on the mean over-wire value. Five seasons of one
     * league is a small sample - QB and TE especially - and a position gap
     * smaller than these bars is not a gap.
     */
    static double twoStandardErrors(List<Bench> group){
        return twoStandardErrorsOf(group.stream().map(Bench::overWire).toList());
    }

    /** The same bar over bare numbers, so a band can ask for it too. */
    public static double twoStandardErrorsOf(List<Double> values){
        int n = values.size();
        if(n < 2){
            return 0.0;
        }
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .sum() / (n - 1);
        return 2.0 * Math.sqrt(variance / n);
    }

    static double atRank(List<Double> sortedDescending, int rank){
        if(sortedDescending == null || sortedDescending.size() < rank){
            return 0.0;
        }
        return sortedDescending.get(rank - 1);
    }

    static void band(String label, List<Bench> group,
                     Map<String, Map<Position, Double>> bar){
        if(group.size() < 5){
            System.out.printf("%-18s %6d   (too few)%n", label, group.size());
            return;
        }
        long hits = group.stream().filter(b -> b.points()
                >= bar.get(b.season()).getOrDefault(b.position(), 0.0)).count();
        System.out.printf("%-18s %6d %8.0f%% %10.1f %12.1f%n", label, group.size(),
                100.0 * hits / group.size(),
                group.stream().mapToDouble(Bench::points).average().orElse(0),
                group.stream().mapToDouble(Bench::overWire).average().orElse(0));
    }
}
