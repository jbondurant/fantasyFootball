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
                 double points, double overWire, boolean rookie, boolean young){}

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
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
                actuals = HistoricalActuals.pointsBySleeperID(season);
            }
            catch(Exception missing){
                continue;
            }
            if(actuals.isEmpty()){
                continue;
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
                benches.add(new Bench(player.firstName + " " + player.lastName,
                        player.position, round, season, points, overWire,
                        rookies.contains(id), young.contains(id)));
            }
        }

        System.out.printf("%d picks in rounds 8-16 across %d seasons, joined to the"
                + " SAME season's actual points.%n", benches.size(), starterBar.size());
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
        int n = group.size();
        if(n < 2){
            return 0.0;
        }
        double mean = group.stream().mapToDouble(Bench::overWire).average().orElse(0);
        double variance = group.stream()
                .mapToDouble(b -> (b.overWire() - mean) * (b.overWire() - mean))
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
