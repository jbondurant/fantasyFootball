import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * D4b-lite: WHO reads WHAT. The league-wide resemblance race crowned
 * Sleeper's board; this asks the per-manager question with the ~45 in-game
 * picks each manager owns: for each manager, the mean log2 rank of their
 * chosen players on each candidate feed. The feed that explains a manager
 * best is, operationally, the sheet that manager reads - identifiable only
 * at Sleeper-vs-ESPN scale, per the honesty note in MODEL.md.
 *
 *   ./gradlew run -Pmain=ManagerFeedTable
 */
public class ManagerFeedTable {

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();

        // manager -> feed family -> [sum log2, n]
        Map<String, Map<String, double[]>> table = new TreeMap<>();
        String[] families = {"sleeper", "fp-ecr", "ffc", "espn", "yahoo"};

        for(int i = 0; i < drafts.size() && i < seasons.size(); i++){
            String season = seasons.get(i);
            if(season == null){
                continue;
            }
            Map<String, Map<String, Integer>> feeds = new LinkedHashMap<>();
            Map<String, Integer> defaults = ReachAudit.defaultsFeed(season);
            if(defaults != null){
                feeds.put("sleeper", defaults);
            }
            for(File file : new File("data").listFiles()){
                String name = file.getName();
                if(name.matches("fp-ecr-dated-" + season + "-\\d{8}\\.json")
                        && !feeds.containsKey("fp-ecr")){
                    feeds.put("fp-ecr", FeedResemblance.ecrRanks(file));
                }
                else if(name.matches("fp-adp-ppr-" + season + "-\\d{8}\\.csv")){
                    Map<String, Map<String, Integer>> columns =
                            FeedResemblance.csvColumns(file);
                    if(columns.containsKey("ESPN")){
                        feeds.put("espn", columns.get("ESPN"));
                    }
                    if(columns.containsKey("FFC")){
                        feeds.put("ffc", columns.get("FFC"));
                    }
                }
                else if(name.matches("fp-adp-halfppr-" + season + "-\\d{8}\\.csv")){
                    Map<String, Map<String, Integer>> columns =
                            FeedResemblance.csvColumns(file);
                    if(columns.containsKey("Yahoo")){
                        feeds.put("yahoo", columns.get("Yahoo"));
                    }
                }
            }
            if(feeds.size() < 3){
                continue;
            }

            for(Map.Entry<String, Map<String, Integer>> feed : feeds.entrySet()){
                Map<String, Integer> ranks = feed.getValue();
                if(ranks == null){
                    continue;
                }
                List<String> ordered = new ArrayList<>(ranks.keySet());
                ordered.sort(Comparator.comparingInt(ranks::get));
                Set<String> taken = new HashSet<>();
                for(JsonElement pickElement : drafts.get(i)){
                    JsonObject pick = pickElement.getAsJsonObject();
                    String sleeperID = pick.get("player_id").getAsString();
                    JsonElement isKeeper = pick.get("is_keeper");
                    JsonElement pickedBy = pick.get("picked_by");
                    boolean keeper = isKeeper != null && !isKeeper.isJsonNull()
                            && isKeeper.getAsBoolean();
                    Player player = Player.getPlayerFromSIDV2(sleeperID);
                    if(!keeper && pickedBy != null && !pickedBy.isJsonNull()
                            && player != null
                            && StartingLineup.isSkillPosition(player.position)
                            && ranks.containsKey(sleeperID)){
                        int rank = 1;
                        for(String candidate : ordered){
                            if(candidate.equals(sleeperID)){
                                break;
                            }
                            if(!taken.contains(candidate)){
                                rank++;
                            }
                        }
                        double[] cell = table
                                .computeIfAbsent(pickedBy.getAsString(),
                                        u -> new HashMap<>())
                                .computeIfAbsent(feed.getKey(), u -> new double[2]);
                        cell[0] += Math.log(rank) / Math.log(2);
                        cell[1]++;
                    }
                    taken.add(sleeperID);
                }
            }
        }

        System.out.printf("%-22s", "MANAGER");
        for(String family : families){
            System.out.printf(" %8s", family);
        }
        System.out.printf("   best%n");
        for(Map.Entry<String, Map<String, double[]>> row : table.entrySet()){
            double[] anchor = row.getValue().get("sleeper");
            if(anchor == null || anchor[1] < 25){
                continue;
            }
            System.out.printf("%-22s", HumanOfInterest.getHumanFromID(row.getKey()));
            String best = "?";
            double bestValue = Double.MAX_VALUE;
            for(String family : families){
                double[] cell = row.getValue().get(family);
                if(cell == null || cell[1] < 25){
                    System.out.printf(" %8s", "-");
                    continue;
                }
                double mean = cell[0] / cell[1];
                if(mean < bestValue){
                    bestValue = mean;
                    best = family;
                }
                System.out.printf(" %8.2f", mean);
            }
            System.out.printf("   %s%n", best);
        }
        System.out.println("\ncells = mean log2 rank of that manager's picks on that feed"
                + "\n(lower = they read it); differences under ~0.1 are ties.");
    }

}
