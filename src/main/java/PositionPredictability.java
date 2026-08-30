import PlayerImportAndSetup.Position;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Defences are notoriously unpredictable" - is that true here?
 *
 * It is one of the oldest claims in fantasy and it decides how a draft model
 * should treat the position: if a preseason defence ranking carries no
 * information about the season that follows, then no pick spent early on one
 * can be justified, and the folk rule of taking a defence last is correct for
 * a measurable reason rather than out of habit.
 *
 * Measured the same way every other feed in this repo is measured: the rank
 * correlation between what the board said in the preseason and what actually
 * happened, within a position, so positions are compared on equal terms.
 *
 *   ./gradlew run -Pmain=PositionPredictability
 */
public class PositionPredictability {

    record Seen(Position position, int rank, double actual){}

    /**
     * How much a preseason ranking at each position is worth believing, as the
     * mean rank correlation with what actually happened.
     *
     * Exposed because a projection you cannot trust should not be taken at face
     * value: defence rankings correlate 0.277 with the season against 0.63 for
     * backs and receivers, so a defence projected far above its peers is mostly
     * noise and should be regressed toward the positional mean before anything
     * spends a pick on it.
     */
    public static Map<Position, Double> reliability() throws Exception {
        Map<Position, List<Double>> pooled = new EnumMap<>(Position.class);
        for(File file : new File("data").listFiles()){
            if(!file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                continue;
            }
            List<Seen> season = load(file, file.getName().split("-")[3]);
            if(season.size() < 100){
                continue;
            }
            for(Position position : new Position[]{Position.QB, Position.RB,
                    Position.WR, Position.TE, Position.DEF}){
                List<Seen> group = season.stream()
                        .filter(s -> s.position() == position).toList();
                if(group.size() >= 8){
                    pooled.computeIfAbsent(position, u -> new ArrayList<>())
                            .add(spearman(group));
                }
            }
        }
        Map<Position, Double> out = new EnumMap<>(Position.class);
        for(Map.Entry<Position, List<Double>> entry : pooled.entrySet()){
            out.put(entry.getKey(), Math.max(0, entry.getValue().stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0)));
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        Map<String, List<Seen>> bySeason = new java.util.TreeMap<>();
        for(File file : new File("data").listFiles()){
            if(file.getName().matches("fp-adp-halfppr-\\d{4}-\\d{8}\\.csv")){
                String season = file.getName().split("-")[3];
                List<Seen> seen = load(file, season);
                if(seen.size() > 100){
                    bySeason.put(season, seen);
                }
            }
        }
        if(bySeason.isEmpty()){
            System.out.println("no seasons joined");
            return;
        }

        Position[] positions = {Position.QB, Position.RB, Position.WR, Position.TE,
                Position.DEF};
        System.out.printf("%nHOW WELL DOES A PRESEASON RANK PREDICT THE SEASON?%n");
        System.out.printf("(spearman between draft-board rank and actual points,"
                + " within position)%n%n");
        System.out.printf("%-8s", "SEASON");
        for(Position position : positions){
            System.out.printf(" %8s", position);
        }
        System.out.println();

        Map<Position, List<Double>> pooled = new EnumMap<>(Position.class);
        Map<Position, Integer> counts = new EnumMap<>(Position.class);
        for(Map.Entry<String, List<Seen>> entry : bySeason.entrySet()){
            System.out.printf("%-8s", entry.getKey());
            for(Position position : positions){
                List<Seen> group = entry.getValue().stream()
                        .filter(s -> s.position() == position).toList();
                if(group.size() < 8){
                    System.out.printf(" %8s", "-");
                    continue;
                }
                double r = spearman(group);
                pooled.computeIfAbsent(position, u -> new ArrayList<>()).add(r);
                counts.merge(position, group.size(), Integer::sum);
                System.out.printf(" %8.3f", r);
            }
            System.out.println();
        }

        System.out.printf("%n%-8s", "mean");
        for(Position position : positions){
            List<Double> values = pooled.get(position);
            System.out.printf(" %8s", values == null ? "-"
                    : String.format("%.3f", values.stream()
                        .mapToDouble(Double::doubleValue).average().orElse(0)));
        }
        System.out.printf("%n%-8s", "n");
        for(Position position : positions){
            System.out.printf(" %8d", counts.getOrDefault(position, 0));
        }
        System.out.println();

        List<Double> defence = pooled.get(Position.DEF);
        if(defence != null){
            double defenceMean = defence.stream().mapToDouble(Double::doubleValue)
                    .average().orElse(0);
            double others = 0;
            int seen = 0;
            for(Position position : new Position[]{Position.QB, Position.RB,
                    Position.WR, Position.TE}){
                List<Double> values = pooled.get(position);
                if(values != null){
                    others += values.stream().mapToDouble(Double::doubleValue)
                            .average().orElse(0);
                    seen++;
                }
            }
            others /= Math.max(1, seen);
            System.out.printf("%ndefence %.3f against %.3f for the skill positions.%n",
                    defenceMean, others);
            System.out.println(defenceMean < others - 0.15
                    ? "The folk claim holds: a preseason defence ranking says markedly"
                      + " less about\nthe season than a ranking at any other position."
                    : Math.abs(defenceMean - others) <= 0.15
                    ? "The folk claim does NOT hold: defences are predicted about as"
                      + " well as\nanything else, and taking one last needs a different"
                      + " justification."
                    : "Defences are predicted BETTER than the skill positions, which is"
                      + " the\nopposite of the folk claim.");
        }
    }

    static double spearman(List<Seen> group){
        List<Seen> byActual = new ArrayList<>(group);
        byActual.sort(Comparator.comparingDouble(Seen::actual).reversed());
        Map<Seen, Integer> actualRank = new HashMap<>();
        for(int i = 0; i < byActual.size(); i++){
            actualRank.put(byActual.get(i), i);
        }
        List<Seen> byBoard = new ArrayList<>(group);
        byBoard.sort(Comparator.comparingInt(Seen::rank));
        double sum = 0;
        int n = byBoard.size();
        for(int i = 0; i < n; i++){
            double d = i - actualRank.get(byBoard.get(i));
            sum += d * d;
        }
        return 1 - 6 * sum / ((double) n * (n * n - 1));
    }

    static List<Seen> load(File adpFile, String season) throws Exception {
        Map<String, Double> actual = new HashMap<>(
                HistoricalActuals.pointsBySleeperID(season));
        actual.putAll(HistoricalActuals.defencePointsBySleeperID(season));
        Map<String, String> idByName = new HashMap<>();
        for(String id : actual.keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null){
                idByName.putIfAbsent(TightEndTiming.normalise(
                        player.firstName + " " + player.lastName), id);
            }
        }
        List<String> lines = Files.readAllLines(adpFile.toPath());
        String[] header = lines.get(0).split(",");
        int nameCol = -1;
        int posCol = -1;
        int adpCol = -1;
        for(int c = 0; c < header.length; c++){
            if(header[c].equals("name")){ nameCol = c; }
            if(header[c].equals("position")){ posCol = c; }
            if(header[c].equals("AVG")){ adpCol = c; }
        }
        if(nameCol < 0 || posCol < 0 || adpCol < 0){
            return List.of();
        }
        record Row(String id, Position position, double adp){}
        List<Row> rows = new ArrayList<>();
        for(String line : lines.subList(1, lines.size())){
            String[] cells = line.split(",");
            if(cells.length <= Math.max(adpCol, Math.max(nameCol, posCol))
                    || !cells[adpCol].matches("\\d+(\\.\\d+)?")){
                continue;
            }
            String label = cells[posCol].trim();
            Position position;
            if(label.equals("DST") || label.equals("DEF")){
                position = Position.DEF;
            }
            else {
                try {
                    position = Position.valueOf(label);
                }
                catch(IllegalArgumentException notPlayable){
                    continue;
                }
            }
            String id = idByName.get(TightEndTiming.normalise(cells[nameCol]));
            if(id != null){
                rows.add(new Row(id, position, Double.parseDouble(cells[adpCol])));
            }
        }
        rows.sort(Comparator.comparingDouble(Row::adp));
        Map<Position, Integer> nextRank = new EnumMap<>(Position.class);
        List<Seen> out = new ArrayList<>();
        for(Row row : rows){
            int rank = nextRank.merge(row.position(), 1, Integer::sum) - 1;
            out.add(new Seen(row.position(), rank, actual.getOrDefault(row.id(), 0.0)));
        }
        return out;
    }
}
