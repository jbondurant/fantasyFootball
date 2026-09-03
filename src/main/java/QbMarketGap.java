import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Justin's actual question: is the league's QB interest LOW RELATIVE TO
 * SLEEPER ADP, adjusted for keepers - and was it always? Two statistics per
 * season, both keeper-adjusted:
 *
 *   surplus   for every in-draft pick with a known ADP: pick number minus
 *             ADP. QB surplus minus non-QB surplus isolates QB-specific
 *             coolness from the board-wide shift keeper slots cause.
 *   drone gap the exact counterfactual: replay the season's real slot
 *             order with every pick taking the best available ADP (kept
 *             players off the board, keeper slots skipped), and compare
 *             where the k-th QB actually went vs where the market would
 *             have taken it. Positive = the league lets QBs fall.
 *
 *   ./gradlew run -Pmain=QbMarketGap
 */
public class QbMarketGap {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        System.out.printf("%-8s %6s %10s %12s %12s %10s %8s%n", "SEASON", "QBs",
                "QB surp", "non-QB surp", "QB-specific", "drone gap", "kept QB");
        for(String label : configuration.getPreviousSeasons()){
            if(label == null){
                continue;
            }
            DraftBacktest.Season season;
            try {
                season = new DraftBacktest.Season(configuration, label);
            }
            catch(Exception missing){
                continue;
            }

            // real surpluses
            List<Double> qbSurplus = new ArrayList<>();
            List<Double> otherSurplus = new ArrayList<>();
            List<Integer> realQbPicks = new ArrayList<>();
            int keptQBs = 0;
            for(JsonElement pickElement : season.picks){
                JsonObject pick = pickElement.getAsJsonObject();
                JsonElement isKeeper = pick.get("is_keeper");
                boolean keeper = isKeeper != null && !isKeeper.isJsonNull()
                        && isKeeper.getAsBoolean();
                Player player = Player.getPlayerFromSIDV2(
                        pick.get("player_id").getAsString());
                if(player == null){
                    continue;
                }
                if(keeper){
                    if(player.position == Position.QB){
                        keptQBs++;
                    }
                    continue;
                }
                Double adp = season.adp.get(pick.get("player_id").getAsString());
                int pickNo = pick.get("pick_no").getAsInt();
                if(player.position == Position.QB){
                    realQbPicks.add(pickNo);
                }
                if(adp == null){
                    continue;
                }
                double surplus = pickNo - adp;
                if(player.position == Position.QB){
                    qbSurplus.add(surplus);
                }
                else if(StartingLineup.isSkillPosition(player.position)){
                    otherSurplus.add(surplus);
                }
            }

            // drone counterfactual: same slots, pure ADP drafting
            List<String> board = new ArrayList<>();
            for(String sleeperID : season.adp.keySet()){
                Player player = Player.getPlayerFromSIDV2(sleeperID);
                if(player != null && StartingLineup.isSkillPosition(player.position)
                        && !season.keptIDs.contains(sleeperID)){
                    board.add(sleeperID);
                }
            }
            board.sort(Comparator.comparingDouble(season.adp::get));
            List<Integer> droneQbPicks = new ArrayList<>();
            for(JsonElement pickElement : season.picks){
                JsonObject pick = pickElement.getAsJsonObject();
                JsonElement isKeeper = pick.get("is_keeper");
                if(isKeeper != null && !isKeeper.isJsonNull() && isKeeper.getAsBoolean()){
                    continue;
                }
                if(board.isEmpty()){
                    break;
                }
                String chosen = board.remove(0);
                if(Player.getPlayerFromSIDV2(chosen).position == Position.QB){
                    droneQbPicks.add(pick.get("pick_no").getAsInt());
                }
            }
            realQbPicks.sort(Integer::compare);
            droneQbPicks.sort(Integer::compare);
            double droneGap = 0;
            int compared = Math.min(Math.min(realQbPicks.size(), droneQbPicks.size()), 8);
            for(int k = 0; k < compared; k++){
                droneGap += realQbPicks.get(k) - droneQbPicks.get(k);
            }
            droneGap = compared == 0 ? 0 : droneGap / compared;

            double qbMean = qbSurplus.stream().mapToDouble(Double::doubleValue)
                    .average().orElse(0);
            double otherMean = otherSurplus.stream().mapToDouble(Double::doubleValue)
                    .average().orElse(0);
            System.out.printf("%-8s %6d %10.1f %12.1f %12.1f %10.1f %8d%n", label,
                    qbSurplus.size(), qbMean, otherMean, qbMean - otherMean, droneGap,
                    keptQBs);
        }
        System.out.println("\nQB-specific = QB surplus minus non-QB surplus (board-shift"
                + "\nremoved); drone gap = mean picks later than a pure-ADP league would"
                + "\nhave taken the first 8 QBs, same board and keeper slots. Positive ="
                + "\nthe league is cooler on QBs than the market.");
    }
}
