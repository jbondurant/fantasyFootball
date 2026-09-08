import PlayerImportAndSetup.Position;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * IS THE KEEPER SURPLUS BUILT ON SOMEBODY ELSE'S DRAFT BOARD?
 *
 * `TradeMarket.bestStillAvailable` answers "the best man at this position still
 * on the board at pick N", and it answers it with Sleeper's `adp_half_ppr` -
 * a national average over leagues that are not this one. Keeper surplus is
 * that replacement subtracted from the man, so every keeper number rests on it.
 *
 * `QbMarketGap` has already measured that this league is cooler on quarterbacks
 * than the market, in every one of five seasons: a drone gap of -0.1 to 20.3
 * picks, mean about twelve. QBs last LONGER here than Sleeper says. So at a
 * late pick the quarterbacks genuinely available are better than the curve
 * believes, the replacement is stronger than assumed, and a QB keeper's surplus
 * is OVERSTATED.
 *
 * That is not academic on 2026-09-07: the corrected keeper panel had just named
 * Bo Nix at r15 as a keeper worth +32.8, on the strength of a curve that thinks
 * the quarterbacks around pick 175 are worse than this league would leave there.
 *
 * This prices every man both ways - the curve as it stands, and the curve
 * shifted by the measured gap - and prints the difference. A recommendation that
 * survives both is safe. One that does not was resting on the wrong league.
 *
 *   ./gradlew run -Pmain=KeeperDriftCheck [-PqbDrift=12]
 */
public class KeeperDriftCheck {

    /** Picks later than Sleeper ADP that this league actually takes a QB. */
    static double qbDrift(){
        return Double.parseDouble(System.getProperty("qbDrift", "11.7"));
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String me = System.getProperty("me", configuration.getUserIDToDisplayName()
                .getOrDefault(configuration.getMyID(), configuration.getMyID()));
        Map<String, Double> points = ProjectionSources.resolve("sleeper");
        Map<String, String> ownerOf = LeagueOwners.today(configuration);
        Map<String, Position> positionOf = new HashMap<>();
        Map<String, String> nameOf = new HashMap<>();
        List<String> mine = new ArrayList<>();
        for(Map.Entry<String, String> entry : ownerOf.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            positionOf.put(entry.getKey(), player == null ? null : player.position);
            nameOf.put(entry.getKey(), player == null ? entry.getKey()
                    : player.firstName + " " + player.lastName);
            if(entry.getValue().equals(me)){
                mine.add(entry.getKey());
            }
        }
        // ...with the three-consecutive-year cap, which needs the earlier drafts
        List<String> earlier = new ArrayList<>();
        for(com.google.gson.JsonArray board : configuration.getPreviousDraftPicks()){
            earlier.add(board.toString());
        }
        Map<String, NextYearKeepers.Cost> nextYear = NextYearKeepers.from(
                configuration.getTodaysDraftPicks(), NextYearKeepers.consecutiveYears(earlier));
        Map<String, Integer> keeperRound = new HashMap<>();
        for(Map.Entry<String, NextYearKeepers.Cost> entry : nextYear.entrySet()){
            if(entry.getValue().keepable()){
                keeperRound.put(entry.getKey(), entry.getValue().round());
            }
        }
        // THE CURVE MUST KNOW THE WHOLE PLAYER POOL, not just rostered men.
        // Built from `positionOf` - which came from LeagueOwners and therefore
        // holds only the 192 men on rosters - the deepest quarterback it knew
        // had an ADP under 175, so ceilingEntry(175) returned nothing, the
        // replacement was priced at zero, and Bo Nix's keeper surplus came out
        // as his ENTIRE season projection: 347.7 points. An answer that large is
        // not a finding, it is a missing denominator.
        Map<String, Position> everyPosition = new HashMap<>(positionOf);
        for(String id : points.keySet()){
            if(!everyPosition.containsKey(id)){
                Player player = Player.getPlayerFromSIDV2(id);
                everyPosition.put(id, player == null ? null : player.position);
            }
        }
        Map<Position, TreeMap<Double, Double>> curve =
                TradeMarket.bestStillAvailable(points, everyPosition);
        Map<String, Integer> slotOfPlayer = TradeMarket.draftSlotOfPlayer(
                LeagueOwners.today(configuration), configuration);

        double drift = qbDrift();
        StringBuilder out = new StringBuilder();
        out.append(String.format("KEEPER DRIFT CHECK  %s  (QB drift %.1f picks)%n%n",
                LocalDate.now(), drift));
        out.append("Keeper surplus subtracts the best man still available at the keeper's pick, and that\n");
        out.append("availability comes from Sleeper's national ADP. QbMarketGap measured this league letting\n");
        out.append("quarterbacks fall in all five seasons, so at a late pick the QBs really on the board are\n");
        out.append("BETTER than the curve thinks and a QB keeper's surplus is overstated.\n\n");
        out.append(String.format("%-22s %-4s %6s %10s %12s %10s%n",
                "MAN", "POS", "ROUND", "AS PRICED", "DRIFT-AWARE", "CHANGE"));
        List<String> order = new ArrayList<>(mine);
        order.sort(Comparator.comparingDouble((String id) ->
                -TradeMarket.keeperPoints(keeperRound, points, curve, everyPosition,
                        configuration, slotOfPlayer, id)));
        for(String id : order){
            Integer round = keeperRound.get(id);
            if(round == null){
                continue;
            }
            double asPriced = TradeMarket.keeperPoints(keeperRound, points, curve,
                    positionOf, configuration, slotOfPlayer, id);
            double shifted = driftAware(keeperRound, points, curve, positionOf, configuration,
                    slotOfPlayer, id, drift);
            out.append(String.format("%-22s %-4s %6s %10.1f %12.1f %+10.1f%n",
                    trim(nameOf.getOrDefault(id, id), 22),
                    positionOf.get(id) == null ? "?" : positionOf.get(id).name(),
                    "r" + round, asPriced, shifted, shifted - asPriced));
        }
        out.append("\nOnly quarterbacks move: the gap measured is QB-specific, with the board-wide keeper shift\n");
        out.append("already removed. A keeper that survives both columns is safe; one that does not was\n");
        out.append("resting on a draft board belonging to other leagues.\n");

        Path target = Path.of("data", "keeper-drift-" + LocalDate.now() + ".txt");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.print(out);
        System.out.println("written to " + target);
    }

    /**
     * Surplus with the lookup moved EARLIER by the drift, for quarterbacks only.
     *
     * A QB whose national ADP is X is really taken at about X + drift here, so
     * the men still on the board at pick N are those with ADP at or beyond
     * N - drift. Looking there returns a stronger replacement, which is the
     * point.
     */
    static double driftAware(Map<String, Integer> keeperRound, Map<String, Double> points,
                             Map<Position, TreeMap<Double, Double>> curve,
                             Map<String, Position> positionOf, AAAConfiguration configuration,
                             Map<String, Integer> slotOfPlayer, String id, double drift){
        Position position = positionOf.get(id);
        Integer round = keeperRound.get(id);
        if(position == null || round == null){
            return 0;
        }
        if(position != Position.QB){
            return TradeMarket.keeperPoints(keeperRound, points, curve, positionOf,
                    configuration, slotOfPlayer, id);
        }
        TreeMap<Double, Double> atPosition = curve.get(position);
        if(atPosition == null){
            return 0;
        }
        double pick = Math.max(1,
                TradeMarket.keeperPickNumber(configuration, slotOfPlayer, round, id) - drift);
        Map.Entry<Double, Double> replacement = atPosition.ceilingEntry(pick);
        double available = replacement == null ? 0 : replacement.getValue();
        return Math.max(0, points.getOrDefault(id, 0.0) - available);
    }

    private static String trim(String text, int width){
        return text.length() <= width ? text : text.substring(0, width - 1);
    }
}
