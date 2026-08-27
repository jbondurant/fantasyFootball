import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * M2 triage: raw signals for Justin's named strays, measured on real picks
 * with WITHIN-POSITION reach as the response (positional need controlled).
 * Each stray gets its empirical answer here; only large signals graduate to
 * model features and the full gate protocol. Diagnostics, not fits - the
 * multiple-testing debt stays small this way.
 *
 *   rookies      do rookies get reached for beyond their sheet rank?
 *   young        the experience proxy for age effects (true age is absent)
 *   faded names  players whose stored ADP fell >=30 ranks year-over-year:
 *                bought early (brand loyalty) or let fall (fade)?
 *   homers       (manager, NFL team) pick counts vs the league baseline
 *   6-pt display QBs whose league-scored rank beats their sheet rank: do
 *                managers respond to the richer displayed points?
 *
 *   ./gradlew run -Pmain=StrayDiagnostics
 */
public class StrayDiagnostics {

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();

        List<double[]> rookie = new ArrayList<>();     // {isRookie, reach}
        List<double[]> young = new ArrayList<>();
        List<double[]> faded = new ArrayList<>();
        List<double[]> display = new ArrayList<>();    // QBs: {upliftRank, reach}
        Map<String, Map<String, Integer>> homer = new TreeMap<>();
        Map<String, Integer> teamTotals = new TreeMap<>();

        Map<String, Map<String, Double>> adpBySeason = new HashMap<>();
        for(String season : seasons){
            if(season != null){
                try {
                    adpBySeason.put(season,
                            HistoricalProjections.adpBySleeperID(configuration, season));
                }
                catch(Exception missing){ /* skip */ }
            }
        }

        for(int i = 0; i < drafts.size() && i < seasons.size(); i++){
            String season = seasons.get(i);
            if(season == null){
                continue;
            }
            Map<String, Integer> feed = ReachAudit.defaultsFeed(season);
            if(feed == null){
                continue;
            }
            Set<String> rookies = HistoricalProjections.rookiesForSeason(configuration,
                    season);
            Set<String> youngSet = HistoricalProjections.youngForSeason(configuration,
                    season, 2);
            Map<String, String> teams = HistoricalProjections.teamBySleeperID(
                    configuration, season);
            String previous = String.valueOf(Integer.parseInt(season) - 1);
            Map<String, Double> lastAdp = adpBySeason.get(previous);
            Map<String, Double> thisAdp = adpBySeason.get(season);
            Map<String, Double> passTds = HistoricalProjections.passTdBySleeperID(
                    configuration, season);
            Map<String, Double> rawPoints = HistoricalProjections.rawPointsBySleeperID(
                    configuration, season);

            java.util.Set<String> taken = new java.util.HashSet<>();
            List<String> ordered = new ArrayList<>(feed.keySet());
            ordered.sort(java.util.Comparator.comparingInt(feed::get));
            for(com.google.gson.JsonElement pickElement : drafts.get(i)){
                com.google.gson.JsonObject pick = pickElement.getAsJsonObject();
                String sleeperID = pick.get("player_id").getAsString();
                com.google.gson.JsonElement isKeeper = pick.get("is_keeper");
                com.google.gson.JsonElement pickedBy = pick.get("picked_by");
                boolean keeper = isKeeper != null && !isKeeper.isJsonNull()
                        && isKeeper.getAsBoolean();
                Player player = Player.getPlayerFromSIDV2(sleeperID);
                if(keeper || pickedBy == null || pickedBy.isJsonNull() || player == null
                        || !StartingLineup.isSkillPosition(player.position)
                        || !feed.containsKey(sleeperID)){
                    taken.add(sleeperID);
                    continue;
                }
                int reach = SniperFit.withinPositionRank(sleeperID, player.position,
                        ordered, taken);
                rookie.add(new double[]{rookies.contains(sleeperID) ? 1 : 0, reach});
                young.add(new double[]{youngSet.contains(sleeperID) ? 1 : 0, reach});
                if(lastAdp != null && thisAdp != null && lastAdp.containsKey(sleeperID)
                        && thisAdp.containsKey(sleeperID)){
                    double drop = rank(thisAdp, sleeperID) - rank(lastAdp, sleeperID);
                    faded.add(new double[]{drop >= 30 ? 1 : 0, reach});
                }
                String team = teams.get(sleeperID);
                if(team != null){
                    homer.computeIfAbsent(pickedBy.getAsString(), u -> new TreeMap<>())
                            .merge(team, 1, Integer::sum);
                    teamTotals.merge(team, 1, Integer::sum);
                }
                if(player.position == Position.QB && passTds.containsKey(sleeperID)
                        && rawPoints.containsKey(sleeperID)){
                    display.add(new double[]{
                            passTds.getOrDefault(sleeperID, 0.0), reach});
                }
                taken.add(sleeperID);
            }
        }

        System.out.println("stray signals (response = within-position reach; positive");
        System.out.println("difference = that group gets reached FOR earlier):\n");
        contrast("rookies vs veterans", rookie);
        contrast("young (<=2yr) vs older", young);
        contrast("faded names (ADP fell 30+) vs rest", faded);
        contrast("high-passTD QBs (top half) vs low", split(display));

        System.out.println("\nhomer pairs (manager x NFL team, picks vs league share):");
        int totalPicks = teamTotals.values().stream().mapToInt(Integer::intValue).sum();
        List<String> lines = new ArrayList<>();
        for(Map.Entry<String, Map<String, Integer>> entry : homer.entrySet()){
            int managerPicks = entry.getValue().values().stream()
                    .mapToInt(Integer::intValue).sum();
            for(Map.Entry<String, Integer> team : entry.getValue().entrySet()){
                double expected = managerPicks
                        * teamTotals.get(team.getKey()) / (double) totalPicks;
                if(team.getValue() >= 4 && team.getValue() >= 2 * expected){
                    lines.add(String.format("   %-20s %-4s %d picks (expected %.1f)",
                            HumanOfInterest.getHumanFromID(entry.getKey()),
                            team.getKey(), team.getValue(), expected));
                }
            }
        }
        if(lines.isEmpty()){
            System.out.println("   none clears the bar (>=4 picks and >=2x expected)");
        }
        lines.forEach(System.out::println);
    }

    static double rank(Map<String, Double> adp, String sleeperID){
        double value = adp.get(sleeperID);
        return adp.values().stream().filter(v -> v < value).count() + 1;
    }

    static List<double[]> split(List<double[]> qbRows){
        List<Double> tds = new ArrayList<>();
        for(double[] row : qbRows){
            tds.add(row[0]);
        }
        tds.sort(Double::compare);
        if(tds.isEmpty()){
            return qbRows;
        }
        double median = tds.get(tds.size() / 2);
        List<double[]> out = new ArrayList<>();
        for(double[] row : qbRows){
            out.add(new double[]{row[0] >= median ? 1 : 0, row[1]});
        }
        return out;
    }

    static void contrast(String label, List<double[]> rows){
        double sumIn = 0;
        double sumOut = 0;
        int nIn = 0;
        int nOut = 0;
        for(double[] row : rows){
            if(row[0] > 0){
                sumIn += row[1];
                nIn++;
            }
            else {
                sumOut += row[1];
                nOut++;
            }
        }
        if(nIn < 8 || nOut < 8){
            System.out.printf("   %-38s insufficient data (%d/%d)%n", label, nIn, nOut);
            return;
        }
        double meanIn = sumIn / nIn;
        double meanOut = sumOut / nOut;
        // pooled standard error for the difference of means
        double varIn = 0;
        double varOut = 0;
        for(double[] row : rows){
            if(row[0] > 0){
                varIn += (row[1] - meanIn) * (row[1] - meanIn);
            }
            else {
                varOut += (row[1] - meanOut) * (row[1] - meanOut);
            }
        }
        double se = Math.sqrt(varIn / (nIn - 1) / nIn + varOut / (nOut - 1) / nOut);
        System.out.printf("   %-38s n=%d/%d  mean reach %.1f vs %.1f  diff %+.1f "
                        + "(+/- %.1f)%n", label, nIn, nOut, meanIn, meanOut,
                meanIn - meanOut, se);
    }
}
