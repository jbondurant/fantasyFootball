import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The availability distribution, learned from this league's own drafts instead
 * of assumed Gaussian. STATUS: challenger, not shipped - on held-out 2025 it
 * scored 1.4% weighted calibration error against the Gaussian's 0.4%, so the
 * gate kept the Gaussian. Its known flaw is censoring: rows exist only for
 * players who were drafted, so the fit never sees the ones who sat, and the
 * deep-board sample is biased toward early exits. A censored-likelihood fit
 * could re-challenge; DraftBacktest prints the head-to-head every run.
 *
 * Construction: for each historical season, remove the keepers, rank the
 * remaining pool by that season's ADP. If the league drafted exactly by that
 * ranking, the k-th real selection would be the k-th ranked player - so a
 * player's residual is (selection at which he actually went) minus (his rank).
 * Keeper thinning is handled structurally by the ranking, so there is no
 * season-centering constant to argue about.
 *
 * The fit is deliberately small for ~900 observations: a mean offset per
 * position (the league bias, in selection space), and the residual
 * distribution itself kept EMPIRICALLY per depth bin - sampling bootstraps the
 * real residuals, so the fat right tail (players falling 40 selections) and
 * the tight left edge (nobody goes 40 early) come from the data, not from a
 * symmetric curve. That asymmetry is exactly what a reach-risk question lives
 * on.
 *
 *     ./gradlew run -Pmain=PickDisplacement
 */
public class PickDisplacement implements DisplacementModel {

    public record ResidualRow(String season, Position position, int parDepth, double residual) {}

    /** Depth bins in selection space: early / middle / late board. */
    static final int[] BIN_UPPER = {36, 84};

    private final Map<Position, Double> positionOffset = new EnumMap<>(Position.class);
    private final List<List<Double>> centeredByBin = new ArrayList<>();
    private final List<ResidualRow> rows;

    /** Test seam: fit from explicit rows. */
    static PickDisplacement fromRows(List<ResidualRow> rows){
        return new PickDisplacement(rows);
    }

    private PickDisplacement(List<ResidualRow> rows){
        this.rows = rows;
        for(int bin = 0; bin <= BIN_UPPER.length; bin++){
            centeredByBin.add(new ArrayList<>());
        }
        Map<Position, double[]> accumulator = new EnumMap<>(Position.class);
        for(ResidualRow row : rows){
            accumulator.computeIfAbsent(row.position(), p -> new double[2]);
            accumulator.get(row.position())[0] += row.residual();
            accumulator.get(row.position())[1] += 1;
        }
        for(Map.Entry<Position, double[]> entry : accumulator.entrySet()){
            positionOffset.put(entry.getKey(), entry.getValue()[0] / entry.getValue()[1]);
        }
        for(ResidualRow row : rows){
            centeredByBin.get(binOf(row.parDepth()))
                    .add(row.residual() - positionOffset.get(row.position()));
        }
    }

    static int binOf(int depth){
        for(int bin = 0; bin < BIN_UPPER.length; bin++){
            if(depth < BIN_UPPER[bin]){
                return bin;
            }
        }
        return BIN_UPPER.length;
    }

    public static PickDisplacement fitThroughSeason(AAAConfiguration configuration, int lastSeason){
        return new PickDisplacement(loadRows(configuration, lastSeason));
    }

    public static List<ResidualRow> loadRows(AAAConfiguration configuration, int lastSeason){
        List<ResidualRow> rows = new ArrayList<>();
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();
        for(int i = 0; i < drafts.size() && i < seasons.size(); i++){
            String season = seasons.get(i);
            if(season == null || Integer.parseInt(season) > lastSeason){
                continue;
            }
            Map<String, Double> adp = HistoricalProjections.adpBySleeperID(configuration, season);

            List<JsonObject> picks = new ArrayList<>();
            Set<String> kept = new HashSet<>();
            for(JsonElement pickElement : drafts.get(i)){
                JsonObject pick = pickElement.getAsJsonObject();
                JsonElement isKeeper = pick.get("is_keeper");
                if(isKeeper != null && !isKeeper.isJsonNull() && isKeeper.getAsBoolean()){
                    kept.add(pick.get("player_id").getAsString());
                }
                else {
                    picks.add(pick);
                }
            }
            picks.sort(Comparator.comparingInt(p -> p.get("pick_no").getAsInt()));

            // Par ranking of the thinned pool.
            List<Map.Entry<String, Double>> pool = new ArrayList<>();
            for(Map.Entry<String, Double> entry : adp.entrySet()){
                if(entry.getValue() < 900 && !kept.contains(entry.getKey())){
                    pool.add(entry);
                }
            }
            pool.sort(Map.Entry.comparingByValue());
            Map<String, Integer> parDepth = new HashMap<>();
            for(int rank = 0; rank < pool.size(); rank++){
                parDepth.put(pool.get(rank).getKey(), rank + 1);
            }

            int selection = 0;
            for(JsonObject pick : picks){
                selection++;
                String sleeperID = pick.get("player_id").getAsString();
                Integer par = parDepth.get(sleeperID);
                if(par == null){
                    continue;
                }
                Player player = Player.getPlayerFromSIDV2(sleeperID);
                if(player == null || !StartingLineup.isSkillPosition(player.position)){
                    continue;
                }
                rows.add(new ResidualRow(season, player.position, par, selection - par));
            }
        }
        return rows;
    }

    /** League bias in selection space; positive means the position falls. */
    public double offset(Position position){
        return positionOffset.getOrDefault(position, 0.0);
    }

    /** One draw of how far this player lands from his par selection. */
    @Override
    public double sample(Random random, int parDepth, Position position){
        List<Double> bin = centeredByBin.get(binOf(parDepth));
        if(bin.isEmpty()){
            return offset(position);
        }
        return offset(position) + bin.get(random.nextInt(bin.size()));
    }

    public int rowCount(){
        return rows.size();
    }

    double quantileOfBin(int bin, double q){
        List<Double> sorted = new ArrayList<>(centeredByBin.get(bin));
        sorted.sort(Double::compareTo);
        return sorted.get((int) Math.min(sorted.size() - 1, Math.floor(q * sorted.size())));
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        PickDisplacement fitted = fitThroughSeason(configuration, lastCompleted);

        System.out.println("Learned from " + fitted.rowCount() + " picks, 2021-" + lastCompleted + "\n");
        System.out.println("position offsets, in selections (positive = the league lets them fall):");
        for(Position position : List.of(Position.QB, Position.RB, Position.WR, Position.TE)){
            System.out.printf("   %-3s %+7.1f%n", position, fitted.offset(position));
        }
        System.out.println("\nresidual distribution by board depth (centered; the asymmetry is the point):");
        System.out.printf("   %-14s %6s %8s %8s %8s %8s %8s%n",
                "DEPTH", "N", "p10", "p25", "p50", "p75", "p90");
        String[] labels = {"1-35", "36-83", "84+"};
        for(int bin = 0; bin < fitted.centeredByBin.size(); bin++){
            System.out.printf("   %-14s %6d %8.1f %8.1f %8.1f %8.1f %8.1f%n",
                    labels[bin], fitted.centeredByBin.get(bin).size(),
                    fitted.quantileOfBin(bin, 0.10), fitted.quantileOfBin(bin, 0.25),
                    fitted.quantileOfBin(bin, 0.50), fitted.quantileOfBin(bin, 0.75),
                    fitted.quantileOfBin(bin, 0.90));
        }
    }

}
