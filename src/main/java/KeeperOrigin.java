import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where do this league's keepers actually come from?
 *
 * StashValue prices a late pick partly on the keeper it might become, and that
 * term is only worth paying for if drafting is how you get a keeper. It may
 * not be: a manager whose season has gone can sell his best player, and the
 * buyer keeps him at the seller's draft round. If keepers arrive mostly by
 * trade, then drafting one has an alternative that costs no pick, and the
 * keeper term in StashValue is overstated - both because a keeper can be had
 * another way, and because the manager who drafts him is often not the one
 * who ends up keeping him.
 *
 * So check: for every keeper this league has declared, did the manager keeping
 * him also draft him?
 *
 * Usage:
 *   ./gradlew run -Pmain=KeeperOrigin
 */
public class KeeperOrigin {

    /**
     * The share of declared keepers that the keeping manager drafted himself -
     * the fraction of a keeper's value a drafter actually captures. StashValue
     * scales its keeper term by this rather than assuming 1.0.
     */
    public static double captureRate(AAAConfiguration configuration){
        Counts counts = count(configuration);
        return counts.total() == 0 ? 1.0
                : counts.drafted() / (double) counts.total();
    }

    record Counts(int drafted, int acquired, int unknown, List<String> acquiredRows,
                  List<String> draftedRows){
        int total(){ return drafted + acquired + unknown; }
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Counts counts = count(configuration);
        int drafted = counts.drafted();
        int acquired = counts.acquired();
        int unknown = counts.unknown();
        List<String> acquiredRows = counts.acquiredRows();
        List<String> draftedRows = counts.draftedRows();
        report(configuration, drafted, acquired, unknown, acquiredRows, draftedRows);
    }

    static Counts count(AAAConfiguration configuration){
        List<Keeper> keepers = configuration.getTodaysKeepers();
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();

        // player id -> who drafted him, in the most recent draft that has him
        Map<String, String> draftedBy = new HashMap<>();
        Map<String, Integer> draftedRound = new HashMap<>();
        for(JsonArray draft : drafts){
            for(JsonElement element : draft){
                JsonObject pick = element.getAsJsonObject();
                JsonElement by = pick.get("picked_by");
                JsonElement id = pick.get("player_id");
                if(by == null || by.isJsonNull() || id == null || id.isJsonNull()){
                    continue;
                }
                draftedBy.putIfAbsent(id.getAsString(), by.getAsString());
                draftedRound.putIfAbsent(id.getAsString(),
                        pick.get("round").getAsInt());
            }
        }

        int drafted = 0;
        int acquired = 0;
        int unknown = 0;
        List<String> acquiredRows = new ArrayList<>();
        List<String> draftedRows = new ArrayList<>();
        for(Keeper keeper : keepers){
            String id = keeper.player.sleeperIDString;
            String keeperName = HumanOfInterest.getHumanFromID(keeper.humanWhoCanKeep);
            String origin = draftedBy.get(id);
            String label = String.format("   %-22s %-3s r%-3d kept by %-14s",
                    keeper.player.firstName + " " + keeper.player.lastName,
                    keeper.player.position, keeper.roundCanBeKept, keeperName);
            if(origin == null){
                unknown++;
                acquiredRows.add(label + " (not drafted in league history)");
            }
            else if(origin.equals(keeper.humanWhoCanKeep)){
                drafted++;
                draftedRows.add(label);
            }
            else {
                acquired++;
                acquiredRows.add(label + " drafted by "
                        + HumanOfInterest.getHumanFromID(origin));
            }
        }

        return new Counts(drafted, acquired, unknown, acquiredRows, draftedRows);
    }

    static void report(AAAConfiguration configuration, int drafted, int acquired,
                       int unknown, List<String> acquiredRows, List<String> draftedRows){
        int total = drafted + acquired + unknown;
        System.out.printf("%n%d keepers declared for the %s season.%n%n",
                total, configuration.getSeason());
        System.out.printf("   drafted by the manager keeping him:   %2d  (%.0f%%)%n",
                drafted, 100.0 * drafted / Math.max(1, total));
        System.out.printf("   acquired from another manager:        %2d  (%.0f%%)%n",
                acquired, 100.0 * acquired / Math.max(1, total));
        System.out.printf("   never drafted in league history:      %2d  (%.0f%%)%n",
                unknown, 100.0 * unknown / Math.max(1, total));

        if(!acquiredRows.isEmpty()){
            System.out.println("\nkeepers that did NOT come from that manager's draft:");
            acquiredRows.forEach(System.out::println);
        }
        if(!draftedRows.isEmpty()){
            System.out.println("\nkeepers the manager drafted himself:");
            draftedRows.forEach(System.out::println);
        }

        double capture = drafted / (double) Math.max(1, total);
        System.out.printf("%nStashValue's keeper term assumes the drafter captures it."
                + "%nHe does %.0f%% of the time here, so that term should be scaled by"
                + " about%n%.2f - and the rest of a keeper's value is reachable by"
                + " trade, at no cost%nin draft picks.%n", 100 * capture, capture);
    }
}
