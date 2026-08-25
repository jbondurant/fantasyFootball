import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who is keeping whom, how long they have already kept them, and how much
 * longer they are allowed to.
 *
 * A keeper can be held three consecutive years, costing a round more each year,
 * so knowing which of your leaguemates is out of road on a player is worth
 * something: their keeper is back in the draft pool next August whether they
 * like it or not.
 *
 *     ./gradlew run -Pmain=KeeperEligibility
 */
public class KeeperEligibility {

    public static class Standing {
        public final String owner;
        public final Player player;
        /** Round he costs this season. */
        public final int cost;
        /** Which consecutive year of keeping this is, counting this one. */
        public final int keeperYear;
        public final boolean keepableThisSeason;
        public final String blockedThisSeason;
        public final boolean keepableNextSeason;
        public final int costNextSeason;
        public final String blockedNextSeason;

        Standing(String owner, Player player, int cost, int keeperYear,
                 boolean thisSeason, String blockedThis,
                 boolean nextSeason, int costNext, String blockedNext){
            this.owner = owner;
            this.player = player;
            this.cost = cost;
            this.keeperYear = keeperYear;
            this.keepableThisSeason = thisSeason;
            this.blockedThisSeason = blockedThis;
            this.keepableNextSeason = nextSeason;
            this.costNextSeason = costNext;
            this.blockedNextSeason = blockedNext;
        }
    }

    /** Consecutive seasons before this one in which he was already a keeper. */
    static int consecutiveYearsBefore(String sleeperID, List<JsonArray> draftsNewestFirst){
        int years = 0;
        for(JsonArray draft : draftsNewestFirst){
            JsonObject pick = findPick(draft, sleeperID);
            if(pick == null){
                break;
            }
            JsonElement isKeeper = pick.get("is_keeper");
            if(isKeeper == null || isKeeper.isJsonNull() || !isKeeper.getAsBoolean()){
                break;
            }
            years++;
        }
        return years;
    }

    private static JsonObject findPick(JsonArray draft, String sleeperID){
        for(JsonElement element : draft){
            JsonObject pick = element.getAsJsonObject();
            JsonElement id = pick.get("player_id");
            if(id != null && !id.isJsonNull() && id.getAsString().equals(sleeperID)){
                return pick;
            }
        }
        return null;
    }

    public static Map<String, List<Standing>> standings(AAAConfiguration configuration){
        List<JsonArray> history = configuration.getPreviousDraftPicks();
        JsonArray lastSeason = history.isEmpty() ? new JsonArray() : history.get(0);
        JsonArray rosters = JsonParser.parseString(configuration.getTodaysRosterWebPageSerious()).getAsJsonArray();

        Map<String, List<Standing>> byOwner = new LinkedHashMap<>();
        for(JsonElement rosterElement : rosters){
            JsonObject roster = rosterElement.getAsJsonObject();
            JsonElement ownerElement = roster.get("owner_id");
            if(ownerElement == null || ownerElement.isJsonNull()){
                continue;
            }
            String owner = HumanOfInterest.getHumanFromID(ownerElement.getAsString());
            List<Standing> mine = new ArrayList<>();
            byOwner.put(owner, mine);

            JsonElement keepers = roster.get("keepers");
            if(keepers == null || keepers.isJsonNull()){
                continue;
            }
            for(JsonElement keeperElement : keepers.getAsJsonArray()){
                String sleeperID = keeperElement.getAsString();
                Player player = Player.getPlayerFromSIDV2(sleeperID);
                if(player == null){
                    continue;
                }
                JsonObject lastPick = findPick(lastSeason, sleeperID);
                Integer lastRound = lastPick == null ? null : lastPick.get("round").getAsInt();
                boolean wasKeeper = lastPick != null && lastPick.get("is_keeper") != null
                        && !lastPick.get("is_keeper").isJsonNull() && lastPick.get("is_keeper").getAsBoolean();

                int cost = lastRound == null ? Keeper.UNDRAFTED_ROUND_COST
                        : (wasKeeper ? lastRound - 1 : lastRound);
                int year = consecutiveYearsBefore(sleeperID, history) + 1;

                List<String> blockedNow = new ArrayList<>();
                if(lastRound != null && lastRound <= KeeperPricing.HIGHEST_KEEPABLE_DRAFT_ROUND){
                    blockedNow.add("went in round " + lastRound + " last season");
                }
                if(year > KeeperPricing.MAX_CONSECUTIVE_YEARS){
                    blockedNow.add("already kept " + (year - 1) + " straight years");
                }
                if(cost < 1){
                    blockedNow.add("would cost better than a first");
                }
                boolean okNow = blockedNow.isEmpty();

                List<String> blockedNext = new ArrayList<>();
                int costNext = cost - 1;
                if(year + 1 > KeeperPricing.MAX_CONSECUTIVE_YEARS){
                    blockedNext.add("would be year " + (year + 1) + " of " + KeeperPricing.MAX_CONSECUTIVE_YEARS);
                }
                if(cost <= KeeperPricing.HIGHEST_KEEPABLE_DRAFT_ROUND){
                    blockedNext.add("sits in round " + cost + " this season");
                }
                if(costNext < 1){
                    blockedNext.add("cost would pass a first-round pick");
                }
                boolean okNext = okNow && blockedNext.isEmpty();

                mine.add(new Standing(owner, player, cost, year,
                        okNow, String.join("; ", blockedNow),
                        okNext, costNext, String.join("; ", blockedNext)));
            }
        }
        return byOwner;
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String season = configuration.getSeason();
        int next = Integer.parseInt(season) + 1;

        System.out.println("Declared keepers for " + season
                + ", with how long each has been kept and how long they can be\n");
        System.out.printf("%-13s %-22s %-4s %6s %6s  %-10s %s%n",
                "MANAGER", "PLAYER", "POS", "COST", "YEAR", "KEEP " + season.substring(2) + "?",
                "KEEP " + String.valueOf(next).substring(2) + "?");

        List<Standing> expiring = new ArrayList<>();
        for(Map.Entry<String, List<Standing>> entry : standings(configuration).entrySet()){
            if(entry.getValue().isEmpty()){
                System.out.printf("%-13s  -- has not declared --%n", entry.getKey());
                continue;
            }
            boolean first = true;
            for(Standing standing : entry.getValue()){
                String now = standing.keepableThisSeason ? "yes" : "NO (" + standing.blockedThisSeason + ")";
                String then = standing.keepableNextSeason
                        ? "yes, r" + standing.costNextSeason
                        : "no - " + (standing.blockedNextSeason.isEmpty()
                                ? standing.blockedThisSeason : standing.blockedNextSeason);
                System.out.printf("%-13s %-22s %-4s %6s %4d/%-1d  %-10s %s%n",
                        first ? entry.getKey() : "",
                        standing.player.firstName + " " + standing.player.lastName,
                        standing.player.position,
                        "r" + standing.cost,
                        standing.keeperYear, KeeperPricing.MAX_CONSECUTIVE_YEARS,
                        now, then);
                if(standing.keepableThisSeason && !standing.keepableNextSeason){
                    expiring.add(standing);
                }
                first = false;
            }
        }

        System.out.println("\nBack in the draft pool for " + next + " whatever their manager wants:");
        for(Standing standing : expiring){
            System.out.printf("   %-22s %-4s  %s%n",
                    standing.player.firstName + " " + standing.player.lastName,
                    standing.player.position, standing.owner);
        }
    }

}
