import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Boris Chen's half-PPR draft tiers - free, current, and fetchable as plain
 * text. Tiers are ranks, not points, so they become a value feed by
 * transplanting them onto Sleeper's points curve: the r-th ranked player at
 * a position gets the r-th best Sleeper league-scored projection there,
 * except that everyone in the same TIER gets the tier's average - which is
 * exactly what a tier claims ("these players are interchangeable"), and the
 * drop between tiers stays where Chen put it.
 *
 * Day-cached like every feed. Names come from FantasyPros ECR, so the
 * repo's name matcher handles the suffixes.
 */
public class BorisChenTiers {

    static String url(String file){
        return "https://s3-us-west-1.amazonaws.com/fftiers/out/" + file + ".txt";
    }

    private static final Map<Position, String> FILES = Map.of(
            Position.QB, "text_QB",
            Position.RB, "text_RB-HALF",
            Position.WR, "text_WR-HALF",
            Position.TE, "text_TE-HALF");

    /** Position -> tiers -> the players in each, in rank order. */
    public static Map<Position, List<List<Player>>> tiers(){
        String season = AAAConfiguration.getInstance().getSeason();
        Map<Position, List<List<Player>>> out = new HashMap<>();
        for(Map.Entry<Position, String> entry : FILES.entrySet()){
            String text = InOutUtilities.getTodaysWebPage(url(entry.getValue()),
                    "borisChen_" + entry.getValue() + "_" + season);
            out.put(entry.getKey(), parse(text, entry.getKey()));
        }
        return out;
    }

    /** "Tier N: name, name, ..." lines to matched players, unmatched dropped. */
    static List<List<Player>> parse(String text, Position position){
        List<List<Player>> tiers = new ArrayList<>();
        for(String line : text.split("\n")){
            int colon = line.indexOf(':');
            if(!line.startsWith("Tier") || colon < 0){
                continue;
            }
            List<Player> tier = new ArrayList<>();
            for(String name : line.substring(colon + 1).split(",")){
                if(name.isBlank()){
                    continue;
                }
                Player player = Player.getPlayerFromNameAndPos(name.trim(), position);
                if(player != null){
                    tier.add(player);
                }
            }
            if(!tier.isEmpty()){
                tiers.add(tier);
            }
        }
        return tiers;
    }

    /**
     * The rank-to-points transplant, injectable curve for tests: tier
     * members share the mean of the Sleeper points their rank range spans.
     */
    static Map<String, Double> pointsFromTiers(List<List<Player>> tiers,
                                               List<Double> pointsCurveDescending){
        Map<String, Double> out = new HashMap<>();
        int rank = 0;
        for(List<Player> tier : tiers){
            double total = 0;
            int counted = 0;
            for(int i = rank; i < rank + tier.size(); i++){
                if(i < pointsCurveDescending.size()){
                    total += pointsCurveDescending.get(i);
                    counted++;
                }
            }
            double tierValue = counted == 0 ? 0 : total / counted;
            for(Player player : tier){
                out.put(player.sleeperIDString, tierValue);
            }
            rank += tier.size();
        }
        return out;
    }

    /** The full feed: every tiered player valued on Sleeper's points curve. */
    public static Map<String, Double> leaguePointsBySleeperID(){
        Map<String, Double> sleeper = SleeperProjections.parseTodaysWebPage();
        Map<String, Double> out = new HashMap<>();
        for(Map.Entry<Position, List<List<Player>>> entry : tiers().entrySet()){
            List<Double> curve = new ArrayList<>();
            for(Map.Entry<String, Double> projection : sleeper.entrySet()){
                Player player = Player.getPlayerFromSIDV2(projection.getKey());
                if(player != null && player.position.equals(entry.getKey())){
                    curve.add(projection.getValue());
                }
            }
            curve.sort(Comparator.reverseOrder());
            out.putAll(pointsFromTiers(entry.getValue(), curve));
        }
        return out;
    }

}
