import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * D2: the reach distribution, measured against the sheet the room actually
 * sees. For every in-draft pick, reach = (the chosen player's rank among the
 * Sleeper-defaults feed's still-available players) - 1: zero = chalk, big =
 * a real deviation. Reported per manager (the epsilon fingerprints the
 * sniper mixture will fit) and league-wide per season (is the room really
 * drifting off-script, or was that keeper mechanics?).
 *
 *   ./gradlew run -Pmain=ReachAudit
 */
public class ReachAudit {

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();
        Map<String, Map<String, List<Integer>>> reachesByManager = new TreeMap<>();

        for(int i = 0; i < drafts.size() && i < seasons.size(); i++){
            String season = seasons.get(i);
            if(season == null){
                continue;
            }
            Map<String, Integer> feed = defaultsFeed(season);
            if(feed == null){
                System.out.printf("%s: no defaults/dated feed, skipped%n", season);
                continue;
            }
            List<String> ordered = new ArrayList<>(feed.keySet());
            ordered.sort(Comparator.comparingInt(feed::get));

            Set<String> taken = new HashSet<>();
            List<Integer> league = new ArrayList<>();
            for(JsonElement pickElement : drafts.get(i)){
                JsonObject pick = pickElement.getAsJsonObject();
                String sleeperID = pick.get("player_id").getAsString();
                JsonElement isKeeper = pick.get("is_keeper");
                if(isKeeper != null && !isKeeper.isJsonNull() && isKeeper.getAsBoolean()){
                    taken.add(sleeperID);
                    continue;
                }
                JsonElement pickedBy = pick.get("picked_by");
                Player player = Player.getPlayerFromSIDV2(sleeperID);
                if(pickedBy == null || pickedBy.isJsonNull() || player == null
                        || !StartingLineup.isSkillPosition(player.position)
                        || !feed.containsKey(sleeperID)){
                    taken.add(sleeperID);
                    continue;
                }
                int rank = 0;
                for(String candidate : ordered){
                    if(candidate.equals(sleeperID)){
                        break;
                    }
                    if(!taken.contains(candidate)){
                        rank++;
                    }
                }
                league.add(rank);
                reachesByManager
                        .computeIfAbsent(pickedBy.getAsString(), u -> new TreeMap<>())
                        .computeIfAbsent(season, u -> new ArrayList<>()).add(rank);
                taken.add(sleeperID);
            }
            league.sort(Integer::compare);
            System.out.printf("%s: n=%d, median reach %d, p80 %d, p95 %d, max %d, "
                            + "share>=10: %.0f%%, share>=25: %.0f%%%n", season,
                    league.size(), quantile(league, 0.5), quantile(league, 0.8),
                    quantile(league, 0.95), league.get(league.size() - 1),
                    100.0 * share(league, 10), 100.0 * share(league, 25));
        }

        System.out.printf("%n%-22s %5s %7s %5s %5s %8s %8s   per-season medians%n",
                "MANAGER", "n", "median", "p80", "p95", ">=10", ">=25");
        for(Map.Entry<String, Map<String, List<Integer>>> entry
                : reachesByManager.entrySet()){
            List<Integer> all = new ArrayList<>();
            StringBuilder perSeason = new StringBuilder();
            for(Map.Entry<String, List<Integer>> bySeason : entry.getValue().entrySet()){
                all.addAll(bySeason.getValue());
                List<Integer> sorted = new ArrayList<>(bySeason.getValue());
                sorted.sort(Integer::compare);
                perSeason.append(bySeason.getKey().substring(2)).append(":")
                        .append(quantile(sorted, 0.5)).append(" ");
            }
            if(all.size() < 10){
                continue;
            }
            all.sort(Integer::compare);
            System.out.printf("%-22s %5d %7d %5d %5d %7.0f%% %7.0f%%   %s%n",
                    HumanOfInterest.getHumanFromID(entry.getKey()), all.size(),
                    quantile(all, 0.5), quantile(all, 0.8), quantile(all, 0.95),
                    100.0 * share(all, 10), 100.0 * share(all, 25), perSeason);
        }
        System.out.println("\nreach = chosen player's rank among the defaults feed's still-"
                + "\navailable players (0 = chalk). share>=10 approximates each manager's"
                + "\nsniper epsilon; p95 sizes the tail the mixture must reproduce.");
    }

    /** Prefer the true defaults sheet; fall back to nearest dated Sleeper ADP. */
    static Map<String, Integer> defaultsFeed(String season) throws Exception {
        File best = null;
        for(File file : new File("data").listFiles()){
            String name = file.getName();
            if(name.matches("sleeper-defaults-" + season + "-\\d{8}\\.csv")){
                return FeedResemblance.csvColumns(file).get("sleeper_rank");
            }
            if(name.matches("sleeper-adp-dated-" + season + "-\\d{8}\\.csv")){
                best = file;
            }
        }
        return best == null ? null : FeedResemblance.csvColumns(best).get("sleeper_adp");
    }

    static int quantile(List<Integer> sortedAscending, double q){
        return sortedAscending.get((int) Math.floor(q * (sortedAscending.size() - 1)));
    }

    static double share(List<Integer> values, int threshold){
        return values.stream().filter(v -> v >= threshold).count() / (double) values.size();
    }
}
