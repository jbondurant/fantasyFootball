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
 * Is chasing a top-four defence worth the pick it costs?
 *
 * The instinct is real and common: the best defences score meaningfully more
 * than the worst, so reach for one. The question that decides it is not whether
 * DEF1 beats DEF20 - it does - but three things together:
 *
 *   does the preseason top four ACTUALLY finish in the top four,
 *   how much do they beat a defence you could have had for free,
 *   and what were you giving up at that pick.
 *
 * The third is the one instinct never prices. A defence taken in round 9 costs
 * a round-9 skill player, and that player is also worth something over HIS
 * replacement. Only the difference between those two matters.
 *
 *   ./gradlew run -Pmain=DefenceTiming
 */
public class DefenceTiming {

    record Seen(String name, Position position, int posRank, double adp, double actual){}

    /** Defences this league drafts; beyond that is free. */
    static final int DRAFTED_DEFENCES = 12;

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

        System.out.printf("%nDID THE PRESEASON TOP FOUR ACTUALLY FINISH THERE?%n%n");
        System.out.printf("%-8s %-46s %10s%n", "SEASON", "preseason DEF1-4 -> where they"
                + " finished", "in top 4");
        int hits = 0;
        int counted = 0;
        for(Map.Entry<String, List<Seen>> entry : bySeason.entrySet()){
            List<Seen> defences = entry.getValue().stream()
                    .filter(s -> s.position() == Position.DEF)
                    .sorted(Comparator.comparingDouble(Seen::adp)).toList();
            if(defences.size() < 12){
                continue;
            }
            List<Seen> byActual = new ArrayList<>(defences);
            byActual.sort(Comparator.comparingDouble(Seen::actual).reversed());
            StringBuilder finishes = new StringBuilder();
            int inTop4 = 0;
            for(int i = 0; i < 4; i++){
                int finish = byActual.indexOf(defences.get(i)) + 1;
                finishes.append(finishes.length() == 0 ? "" : ", ").append(finish);
                if(finish <= 4){
                    inTop4++;
                }
            }
            hits += inTop4;
            counted += 4;
            System.out.printf("%-8s %-46s %10d/4%n", entry.getKey(), finishes, inTop4);
        }
        System.out.printf("%noverall %d of %d preseason top-four defences finished top"
                + " four (%.0f%%).%nchance alone would give %.0f%%.%n", hits, counted,
                100.0 * hits / counted, 100.0 * 4 / 32);

        System.out.printf("%n%nWHAT THE PICK ACTUALLY BUYS, AGAINST WHAT IT COSTS%n%n");
        System.out.printf("%-8s %7s %8s %10s %11s   %-22s %10s%n", "SEASON", "DEF1-4",
                "adp", "vs free", "round", "skill at that adp", "vs free");
        double defenceGain = 0;
        double skillGain = 0;
        int seasons = 0;
        List<Double> differences = new ArrayList<>();
        for(Map.Entry<String, List<Seen>> entry : bySeason.entrySet()){
            List<Seen> all = entry.getValue();
            List<Seen> defences = all.stream().filter(s -> s.position() == Position.DEF)
                    .sorted(Comparator.comparingDouble(Seen::adp)).toList();
            if(defences.size() <= DRAFTED_DEFENCES){
                continue;
            }
            double topFour = defences.subList(0, 4).stream()
                    .mapToDouble(Seen::actual).average().orElse(0);
            double free = defences.subList(DRAFTED_DEFENCES,
                    Math.min(defences.size(), DRAFTED_DEFENCES + 8)).stream()
                    .mapToDouble(Seen::actual).average().orElse(0);
            double adp = defences.subList(0, 4).stream()
                    .mapToDouble(Seen::adp).average().orElse(0);

            // what a skill player taken at that same pick returned over HIS free man
            Seen skill = all.stream()
                    .filter(s -> s.position() != Position.DEF && s.adp() >= adp)
                    .min(Comparator.comparingDouble(Seen::adp)).orElse(null);
            if(skill == null){
                continue;
            }
            double skillFree = freeLevel(all, skill.position());
            System.out.printf("%-8s %7.1f %8.0f %10.1f %11s   %-22s %10.1f%n",
                    entry.getKey(), topFour, adp, topFour - free,
                    "r" + (int) Math.ceil(adp / 12.0),
                    skill.name() + " (" + skill.position() + ")",
                    skill.actual() - skillFree);
            defenceGain += topFour - free;
            skillGain += skill.actual() - skillFree;
            differences.add((topFour - free) - (skill.actual() - skillFree));
            seasons++;
        }
        if(seasons == 0){
            return;
        }
        defenceGain /= seasons;
        skillGain /= seasons;
        System.out.printf("%nmean over %d seasons: a top-four defence beat a free one by"
                + " %.1f points.%nthe skill player you passed on beat HIS free man by"
                + " %.1f.%n", seasons, defenceGain, skillGain);
        // five seasons and one named player a side, so the bars are wide and
        // the season-by-season count is the sturdier read
        double mean = differences.stream().mapToDouble(Double::doubleValue)
                .average().orElse(0);
        double variance = differences.stream().mapToDouble(d -> (d - mean) * (d - mean))
                .sum() / Math.max(1, differences.size() - 1);
        double bar = 2 * Math.sqrt(variance / differences.size());
        long defenceWon = differences.stream().filter(d -> d > 0).count();
        System.out.printf("%nthe pick is worth %+.1f points a season, +/- %.1f.%n"
                + "the defence won %d of %d seasons.%n", mean, bar, defenceWon,
                differences.size());
        System.out.printf("%n%s%n", Math.abs(mean) < bar
                ? "NO ANSWER - the difference sits inside its own error bar."
                : mean > 0 ? "THE INSTINCT IS RIGHT."
                : "IGNORE THE INSTINCT - the skill player was worth more, by more"
                  + " than the noise.");
    }

    static double freeLevel(List<Seen> all, Position position){
        int drafted = position == Position.QB ? 20 : position == Position.TE ? 18
                : position == Position.RB ? 60 : 80;
        List<Seen> ranked = all.stream().filter(s -> s.position() == position)
                .sorted(Comparator.comparingInt(Seen::posRank)).toList();
        if(ranked.size() <= drafted){
            return 0;
        }
        return ranked.subList(drafted, Math.min(ranked.size(), drafted + 12)).stream()
                .mapToDouble(Seen::actual).average().orElse(0);
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
        record Row(String name, String id, Position position, double adp){}
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
                rows.add(new Row(cells[nameCol].trim(), id, position,
                        Double.parseDouble(cells[adpCol])));
            }
        }
        rows.sort(Comparator.comparingDouble(Row::adp));
        Map<Position, Integer> nextRank = new EnumMap<>(Position.class);
        List<Seen> out = new ArrayList<>();
        for(Row row : rows){
            int rank = nextRank.merge(row.position(), 1, Integer::sum) - 1;
            out.add(new Seen(row.name(), row.position(), rank, row.adp(),
                    actual.getOrDefault(row.id(), 0.0)));
        }
        return out;
    }
}
