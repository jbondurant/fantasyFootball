import com.google.gson.*;
import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * Does the backtest grade outcomes under Justin's scoring, or someone else's?
 *
 * Projections are built with the league's own settings, and this league pays
 * 6 points for a passing touchdown. But PlanBacktest scores what really
 * happened with HistoricalActuals.pointsBySleeperID, which hands back
 * Sleeper's pts_half_ppr - a STANDARD field, computed at 4 points a passing
 * touchdown. If that is so, every quarterback is drafted on his 6-point value
 * and graded on his 4-point value, and the backtest has been quietly telling
 * us quarterbacks are worth less than they are.
 *
 * This tool measures the gap. It does not fix it: four agents are mid-run
 * against the current scoring, and moving the outcome measure under them
 * would invalidate their work.
 */
public class ScoringAudit {

    static final String[] SEASONS = {"2021", "2022", "2023", "2024", "2025"};

    public static void main(String[] args){
        double passTD = SleeperLeague.getSeriousLeague()
                .league.leagueScoringSettings.passTD;
        System.out.printf("league pays %.0f points for a passing touchdown;"
                + " pts_half_ppr pays 4%n", passTD);
        double correction = passTD - 4.0;
        if(correction == 0){
            System.out.println("no gap - the backtest already grades correctly.");
            return;
        }
        System.out.printf("%nTOP-12 QUARTERBACKS, GRADED BOTH WAYS%n");
        System.out.printf("%-6s %-22s %10s %10s %8s%n",
                "SEASON", "QUARTERBACK", "as graded", "corrected", "missed");
        double worst = 0;
        Map<String, double[]> perSeason = new LinkedHashMap<>();
        for(String season : SEASONS){
            List<double[]> deltas = new ArrayList<>();
            List<Object[]> rows = new ArrayList<>();
            for(JsonElement element : rawQBs(season)){
                JsonObject row = element.getAsJsonObject();
                JsonObject stats = row.getAsJsonObject("stats");
                if(stats == null || !row.has("player_id")){ continue; }
                JsonElement half = stats.get("pts_half_ppr");
                JsonElement tds = stats.get("pass_td");
                if(half == null || half.isJsonNull()){ continue; }
                double graded = half.getAsDouble();
                double thrown = tds == null || tds.isJsonNull() ? 0 : tds.getAsDouble();
                Player player = Player.getPlayerFromSIDV2(row.get("player_id").getAsString());
                if(player == null || player.position != Position.QB){ continue; }
                rows.add(new Object[]{ player.firstName + " " + player.lastName,
                        graded, graded + correction * thrown, correction * thrown });
            }
            rows.sort((a, b) -> Double.compare((Double) b[1], (Double) a[1]));
            double sum = 0;
            for(int i = 0; i < Math.min(12, rows.size()); i++){
                Object[] r = rows.get(i);
                sum += (Double) r[3];
                if(i < 3){
                    System.out.printf("%-6s %-22s %10.1f %10.1f %8.1f%n",
                            i == 0 ? season : "", r[0], r[1], r[2], r[3]);
                }
            }
            double mean = rows.isEmpty() ? 0 : sum / Math.min(12, rows.size());
            perSeason.put(season, new double[]{ mean });
            worst = Math.max(worst, mean);
        }
        System.out.printf("%nPOINTS A STARTING QUARTERBACK LOSES, PER SEASON%n");
        for(Map.Entry<String, double[]> entry : perSeason.entrySet()){
            System.out.printf("   %s  %6.1f%n", entry.getKey(), entry.getValue()[0]);
        }
        System.out.printf("%nA drafted quarterback is graded roughly %.0f points a season"
                + " below%nwhat he is really worth in this league. Every finding about"
                + " when to%ntake a quarterback rests on that number being zero, and it"
                + " is not.%n", worst);
    }

    static JsonArray rawQBs(String season){
        String url = "https://api.sleeper.app/stats/nfl/" + season
                + "?season_type=regular&position[]=QB&order_by=pts_half_ppr";
        String data = InOutUtilities.getCachedForever(url, "sleeperActualsQB" + season);
        return JsonParser.parseString(data).getAsJsonArray();
    }
}
