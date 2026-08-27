import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.file.Files;
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
 * Which rankings was the league ACTUALLY following? For every dated feed in
 * data/ (harvested near each draft) plus the stored/API feeds, walk each
 * season's real draft in pick order and score the chosen player's rank among
 * that feed's still-available players. A room following a feed picks rank ~1
 * over and over; the feed with the lowest mean log2(rank) is the room's true
 * sheet. Capture-vs-draft date gaps print beside each score, so a
 * closer-dated feed in the "wrong" scoring format can win on the merits -
 * Justin's date-versus-popularity tradeoff, measured.
 *
 *   ./gradlew run -Pmain=FeedResemblance
 */
public class FeedResemblance {

    /** season -> draft date yyyymmdd, from DraftDates (the league chain). */
    static final Map<String, Integer> DRAFT_DATES = Map.of(
            "2021", 20210905, "2022", 20220904, "2023", 20230905,
            "2024", 20240901, "2025", 20250823);

    record Feed(String label, int captureDate, Map<String, Integer> rankBySleeperID){}

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Map<String, List<Feed>> feedsBySeason = new TreeMap<>();

        // dated per-platform CSVs (fp-adp-*, sleeper-adp-dated-*)
        for(File file : new File("data").listFiles()){
            String name = file.getName();
            if(name.matches("fp-adp-\\w+-\\d{4}-\\d{8}\\.csv")){
                String[] parts = name.replace(".csv", "").split("-");
                String season = parts[3];
                int date = Integer.parseInt(parts[4]);
                for(Map.Entry<String, Map<String, Integer>> column
                        : csvColumns(file).entrySet()){
                    feedsBySeason.computeIfAbsent(season, u -> new ArrayList<>())
                            .add(new Feed(parts[2] + "-" + column.getKey(), date,
                                    column.getValue()));
                }
            }
            else if(name.matches("sleeper-adp-dated-\\d{4}-\\d{8}\\.csv")){
                String[] parts = name.replace(".csv", "").split("-");
                String season = parts[3];
                int date = Integer.parseInt(parts[4]);
                Map<String, Map<String, Integer>> columns = csvColumns(file);
                Map<String, Integer> sleeper = columns.get("sleeper_adp");
                if(sleeper != null){
                    feedsBySeason.computeIfAbsent(season, u -> new ArrayList<>())
                            .add(new Feed("sleeper-dated", date, sleeper));
                }
            }
            else if(name.matches("fp-ecr-dated-\\d{4}-\\d{8}\\.json")){
                String[] parts = name.replace(".json", "").split("-");
                String season = parts[3];
                int date = Integer.parseInt(parts[4]);
                feedsBySeason.computeIfAbsent(season, u -> new ArrayList<>())
                        .add(new Feed("fp-ecr", date, ecrRanks(file)));
            }
        }

        // stored Sleeper snapshot and FFC, per season
        for(String season : DRAFT_DATES.keySet()){
            try {
                feedsBySeason.computeIfAbsent(season, u -> new ArrayList<>())
                        .add(new Feed("sleeper-stored", 0, toRanks(
                                HistoricalProjections.adpBySleeperID(configuration, season))));
            }
            catch(Exception missing){ /* season not reachable */ }
            Map<String, Double> ffc = FFCalculatorSD.adpBySleeperID(season);
            if(!ffc.isEmpty()){
                feedsBySeason.computeIfAbsent(season, u -> new ArrayList<>())
                        .add(new Feed("ffc-api", 0, toRanks(ffc)));
            }
        }

        // the real drafts
        List<JsonArray> drafts = configuration.getPreviousDraftPicks();
        List<String> seasons = configuration.getPreviousSeasons();
        for(int i = 0; i < drafts.size() && i < seasons.size(); i++){
            String season = seasons.get(i);
            if(season == null || !feedsBySeason.containsKey(season)){
                continue;
            }
            int draftDate = DRAFT_DATES.getOrDefault(season, 0);
            System.out.printf("%n%s (draft %d): feed, capture gap in days, coverage, "
                    + "mean rank, mean log2, top1, top3%n", season, draftDate);
            List<Object[]> rows = new ArrayList<>();
            for(Feed feed : feedsBySeason.get(season)){
                double[] score = resemblance(drafts.get(i), feed.rankBySleeperID());
                if(score == null){
                    continue;
                }
                int gap = feed.captureDate() == 0 ? 999
                        : dayGap(feed.captureDate(), draftDate);
                rows.add(new Object[]{feed.label(), gap, score});
            }
            rows.sort(Comparator.comparingDouble(row -> ((double[]) row[2])[2]));
            for(Object[] row : rows){
                double[] s = (double[]) row[2];
                System.out.printf("   %-22s %5s %7.0f%% %9.1f %9.2f %6.0f%% %6.0f%%%n",
                        row[0], row[1].equals(999) ? "?" : row[1] + "d",
                        s[0] * 100, s[1], s[2], s[3] * 100, s[4] * 100);
            }
        }
        System.out.println("\nmean log2(rank of the chosen player among the feed's still-"
                + "\navailable players) is the headline: lower = the room is reading this"
                + "\nsheet. top1/top3 = share of picks that were the feed's next-best.");
    }

    /** coverage, mean rank, mean log2 rank, top1 share, top3 share. */
    static double[] resemblance(JsonArray picks, Map<String, Integer> ranks){
        List<String> ordered = new ArrayList<>(ranks.keySet());
        ordered.sort(Comparator.comparingInt(ranks::get));
        Set<String> taken = new HashSet<>();
        int scored = 0;
        int inDraft = 0;
        double rankSum = 0;
        double logSum = 0;
        int top1 = 0;
        int top3 = 0;
        for(JsonElement pickElement : picks){
            JsonObject pick = pickElement.getAsJsonObject();
            String sleeperID = pick.get("player_id").getAsString();
            JsonElement isKeeper = pick.get("is_keeper");
            boolean keeper = isKeeper != null && !isKeeper.isJsonNull()
                    && isKeeper.getAsBoolean();
            if(keeper){
                taken.add(sleeperID);
                continue;
            }
            Player player = Player.getPlayerFromSIDV2(sleeperID);
            if(player == null || !StartingLineup.isSkillPosition(player.position)){
                taken.add(sleeperID);
                continue;
            }
            inDraft++;
            if(ranks.containsKey(sleeperID)){
                int rank = 1;
                for(String candidate : ordered){
                    if(candidate.equals(sleeperID)){
                        break;
                    }
                    if(!taken.contains(candidate)){
                        rank++;
                    }
                }
                scored++;
                rankSum += rank;
                logSum += Math.log(rank) / Math.log(2);
                if(rank == 1){
                    top1++;
                }
                if(rank <= 3){
                    top3++;
                }
            }
            taken.add(sleeperID);
        }
        if(scored < 40){
            return null;
        }
        return new double[]{scored / (double) inDraft, rankSum / scored,
                logSum / scored, top1 / (double) scored, top3 / (double) scored};
    }

    static int dayGap(int capture, int draft){
        java.time.LocalDate c = java.time.LocalDate.of(capture / 10000,
                capture / 100 % 100, capture % 100);
        java.time.LocalDate d = java.time.LocalDate.of(draft / 10000,
                draft / 100 % 100, draft % 100);
        return (int) Math.abs(java.time.temporal.ChronoUnit.DAYS.between(c, d));
    }

    static Map<String, Integer> toRanks(Map<String, Double> values){
        List<String> ordered = new ArrayList<>(values.keySet());
        ordered.removeIf(id -> {
            Player player = Player.getPlayerFromSIDV2(id);
            return player == null || !StartingLineup.isSkillPosition(player.position);
        });
        ordered.sort(Comparator.comparingDouble(values::get));
        Map<String, Integer> ranks = new HashMap<>();
        for(int i = 0; i < ordered.size(); i++){
            ranks.put(ordered.get(i), i + 1);
        }
        return ranks;
    }

    /** Every numeric column of a harvested CSV as its own sleeperID->rank map. */
    static Map<String, Map<String, Integer>> csvColumns(File file) throws Exception {
        List<String> lines = Files.readAllLines(file.toPath());
        String[] header = lines.get(0).split(",");
        Map<String, Map<String, Double>> raw = new LinkedHashMap<>();
        for(int c = 2; c < header.length; c++){
            raw.put(header[c], new HashMap<>());
        }
        for(String line : lines.subList(1, lines.size())){
            String[] cells = line.split(",");
            if(cells.length < 3){
                continue;
            }
            Position position;
            try {
                position = Position.valueOf(cells[1]);
            }
            catch(IllegalArgumentException notSkill){
                continue;
            }
            Player player = Player.getPlayerFromNameAndPos(cells[0], position);
            if(player == null){
                continue;
            }
            for(int c = 2; c < header.length && c < cells.length; c++){
                if(!cells[c].isEmpty() && cells[c].matches("\\d+(\\.\\d+)?")){
                    raw.get(header[c]).putIfAbsent(player.sleeperIDString,
                            Double.parseDouble(cells[c]));
                }
            }
        }
        Map<String, Map<String, Integer>> columns = new LinkedHashMap<>();
        for(Map.Entry<String, Map<String, Double>> entry : raw.entrySet()){
            if(entry.getValue().size() >= 80){
                columns.put(entry.getKey(), toRanks(entry.getValue()));
            }
        }
        return columns;
    }

    static Map<String, Integer> ecrRanks(File file) throws Exception {
        JsonObject blob = JsonParser.parseString(Files.readString(file.toPath()))
                .getAsJsonObject();
        Map<String, Double> values = new HashMap<>();
        for(JsonElement element : blob.getAsJsonArray("players")){
            JsonObject entry = element.getAsJsonObject();
            Position position = FantasyProsEcrData.toPosition(
                    entry.get("player_position_id").getAsString());
            if(position == null || !StartingLineup.isSkillPosition(position)){
                continue;
            }
            Player player = Player.getPlayerFromNameAndPos(
                    entry.get("player_name").getAsString(), position);
            if(player != null && entry.has("rank_ecr")){
                values.putIfAbsent(player.sleeperIDString,
                        entry.get("rank_ecr").getAsDouble());
            }
        }
        return toRanks(values);
    }
}
