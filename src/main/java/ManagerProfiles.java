import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * How this league, and each manager in it, drafts relative to national ADP -
 * fitted from the league's own history instead of hand-pasted constants.
 *
 * Two levels, both season-centered so a hot draft year does not read as bias:
 *
 *  - League bias per position: how many picks later (positive) or earlier
 *    (negative) than ADP the league as a whole takes that position.
 *  - Per-manager offset on top of that, shrunk toward zero by n/(n+K) so a
 *    manager with six picks at a position does not get a confident number.
 *    The league bias is the pooled prior the offsets shrink toward.
 *
 * Fit through a season cutoff, so the backtest can fit on 2021-2024 and be
 * scored on 2025 without leakage.
 */
public class ManagerProfiles {

    /** One historical non-keeper pick with a usable ADP. */
    public record PickRow(String season, String userID, Position position, int pickNo, double adp) {}

    /** Prior strength for the per-manager shrinkage, in picks-at-position. */
    public static final double SHRINK_K = 12.0;

    /** ADP beyond this is a flier, not a market price. */
    public static final double ADP_LIMIT = 250.0;

    private final Map<Position, Double> leagueBias = new EnumMap<>(Position.class);
    private final Map<String, Map<Position, Double>> managerOffset = new HashMap<>();
    private final Map<String, Map<Position, Integer>> managerPickCount = new HashMap<>();
    private final List<String> seasonsFitted = new ArrayList<>();

    /** The core fit, pure so it can be tested on synthetic rows. */
    public static ManagerProfiles fitFromRows(List<PickRow> rows, double shrinkK){
        ManagerProfiles profiles = new ManagerProfiles();

        // Season centering: a season's mean residual is the keeper-depth effect
        // (with 24 players kept, everyone drafts 'early' against ADP), not bias.
        Map<String, Double> seasonMean = new HashMap<>();
        Map<String, double[]> seasonAccumulator = new HashMap<>();
        for(PickRow row : rows){
            seasonAccumulator.computeIfAbsent(row.season(), s -> new double[2]);
            double[] acc = seasonAccumulator.get(row.season());
            acc[0] += row.pickNo() - row.adp();
            acc[1] += 1;
        }
        for(Map.Entry<String, double[]> entry : seasonAccumulator.entrySet()){
            seasonMean.put(entry.getKey(), entry.getValue()[0] / entry.getValue()[1]);
        }
        profiles.seasonsFitted.addAll(new TreeSet<>(seasonMean.keySet()));

        // League bias per position.
        Map<Position, double[]> posAccumulator = new EnumMap<>(Position.class);
        for(PickRow row : rows){
            double centered = row.pickNo() - row.adp() - seasonMean.get(row.season());
            posAccumulator.computeIfAbsent(row.position(), p -> new double[2]);
            double[] acc = posAccumulator.get(row.position());
            acc[0] += centered;
            acc[1] += 1;
        }
        for(Map.Entry<Position, double[]> entry : posAccumulator.entrySet()){
            profiles.leagueBias.put(entry.getKey(), entry.getValue()[0] / entry.getValue()[1]);
        }

        // Per-manager offsets on the residual after league bias, shrunk.
        Map<String, Map<Position, double[]>> managerAccumulator = new HashMap<>();
        for(PickRow row : rows){
            double residual = row.pickNo() - row.adp() - seasonMean.get(row.season())
                    - profiles.leagueBias.getOrDefault(row.position(), 0.0);
            managerAccumulator
                    .computeIfAbsent(row.userID(), u -> new EnumMap<>(Position.class))
                    .computeIfAbsent(row.position(), p -> new double[2]);
            double[] acc = managerAccumulator.get(row.userID()).get(row.position());
            acc[0] += residual;
            acc[1] += 1;
        }
        for(Map.Entry<String, Map<Position, double[]>> manager : managerAccumulator.entrySet()){
            Map<Position, Double> offsets = new EnumMap<>(Position.class);
            Map<Position, Integer> counts = new EnumMap<>(Position.class);
            for(Map.Entry<Position, double[]> position : manager.getValue().entrySet()){
                double n = position.getValue()[1];
                double mean = position.getValue()[0] / n;
                offsets.put(position.getKey(), mean * n / (n + shrinkK));
                counts.put(position.getKey(), (int) n);
            }
            profiles.managerOffset.put(manager.getKey(), offsets);
            profiles.managerPickCount.put(manager.getKey(), counts);
        }
        return profiles;
    }

    /** Fit from the league's own draft history, through lastSeason inclusive. */
    public static ManagerProfiles fitThroughSeason(AAAConfiguration configuration, int lastSeason){
        return fitFromRows(loadRows(configuration, lastSeason), SHRINK_K);
    }

    public static List<PickRow> loadRows(AAAConfiguration configuration, int lastSeason){
        List<PickRow> rows = new ArrayList<>();
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();
        for(int i = 0; i < drafts.size() && i < seasons.size(); i++){
            String season = seasons.get(i);
            if(season == null || Integer.parseInt(season) > lastSeason){
                continue;
            }
            Map<String, Double> adp = HistoricalProjections.adpBySleeperID(configuration, season);
            for(JsonElement pickElement : drafts.get(i)){
                JsonObject pick = pickElement.getAsJsonObject();
                JsonElement isKeeper = pick.get("is_keeper");
                if(isKeeper != null && !isKeeper.isJsonNull() && isKeeper.getAsBoolean()){
                    continue;
                }
                JsonElement pickedBy = pick.get("picked_by");
                if(pickedBy == null || pickedBy.isJsonNull()){
                    continue;
                }
                String sleeperID = pick.get("player_id").getAsString();
                Position position = positionOf(sleeperID, pick);
                if(position == null || !StartingLineup.isSkillPosition(position)){
                    continue;
                }
                Double adpValue = adp.get(sleeperID);
                if(adpValue == null || adpValue > ADP_LIMIT){
                    continue;
                }
                rows.add(new PickRow(season, pickedBy.getAsString(), position,
                        pick.get("pick_no").getAsInt(), adpValue));
            }
        }
        return rows;
    }

    private static Position positionOf(String sleeperID, JsonObject pick){
        Player player = Player.getPlayerFromSIDV2(sleeperID);
        if(player != null){
            return player.position;
        }
        JsonObject meta = pick.getAsJsonObject("metadata");
        if(meta == null || meta.get("position") == null || meta.get("position").isJsonNull()){
            return null;
        }
        String raw = meta.get("position").getAsString();
        return Position.isStandardPosition(raw) ? Position.valueOf(raw) : null;
    }

    public double leagueBias(Position position){
        return leagueBias.getOrDefault(position, 0.0);
    }

    public Map<Position, Double> leagueBiasMap(){
        return new EnumMap<>(leagueBias);
    }

    /** The shrunk per-manager deviation on top of the league bias. */
    public double managerOffset(String userID, Position position){
        return managerOffset.getOrDefault(userID, Map.of()).getOrDefault(position, 0.0);
    }

    /** Total pick adjustment for this manager at this position. */
    public double adjustmentFor(String userID, Position position){
        return leagueBias(position) + managerOffset(userID, position);
    }

    public int pickCount(String userID, Position position){
        return managerPickCount.getOrDefault(userID, Map.of()).getOrDefault(position, 0);
    }

    public List<String> seasonsFitted(){
        return seasonsFitted;
    }

    public java.util.Set<String> managers(){
        return managerOffset.keySet();
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        ManagerProfiles profiles = fitThroughSeason(configuration, lastCompleted);

        System.out.println("Fitted on seasons " + profiles.seasonsFitted() + "\n");
        System.out.println("league bias, picks later (+) or earlier (-) than ADP, season-centered:");
        for(Position position : List.of(Position.QB, Position.RB, Position.WR, Position.TE)){
            System.out.printf("   %-3s %+7.1f%n", position, profiles.leagueBias(position));
        }

        System.out.println("\nper-manager offsets on top of that (shrunk, n in brackets):");
        System.out.printf("%-14s %14s %14s %14s %14s%n", "MANAGER", "QB", "RB", "WR", "TE");
        List<String> sorted = new ArrayList<>(profiles.managers());
        sorted.sort((a, b) -> Double.compare(
                profiles.managerOffset(a, Position.QB), profiles.managerOffset(b, Position.QB)));
        for(String userID : sorted){
            StringBuilder line = new StringBuilder(String.format("%-14s", HumanOfInterest.getHumanFromID(userID)));
            for(Position position : List.of(Position.QB, Position.RB, Position.WR, Position.TE)){
                line.append(String.format("  %+6.1f (%2d)",
                        profiles.managerOffset(userID, position), profiles.pickCount(userID, position)));
            }
            System.out.println(line);
        }
    }

}
