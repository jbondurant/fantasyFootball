import PlayerImportAndSetup.Position;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Does MY starting nine need more bench cover than the average starting nine?
 *
 * BenchValue measures what a rounds 8-9 pick was worth over the wire across
 * five seasons of this league. That number ALREADY contains the ordinary
 * injury environment - those historical picks played out in seasons where
 * starters got hurt at normal rates, and the bench men who replaced them are
 * exactly what makes the 44 points. Modelling starter injury risk on top of
 * that base rate in absolute terms would count the same games twice.
 *
 * What the base rate cannot know is whether THIS nine is unusually fragile.
 * So the only honest use of the injury file here is a DEVIATION: each starter
 * against the average starter at his own position, because a running back
 * misses far more time than a quarterback and a global average would smear
 * that away.
 *
 * The ratio it prints - my expected starter-games lost at a position over what
 * an average set of starters would lose there - is the multiplier on that
 * position's base rate. Above 1.0 means my cover at that position is worth
 * more than history says; below 1.0 means less.
 *
 * Usage:
 *   ./gradlew run -Pmain=StarterRisk
 *   ./gradlew run -Pmain=StarterRisk -Proster="Derrick Henry,Malik Nabers,..."
 */
public class StarterRisk {

    /** The nine from the 2026-08-28 mock, as a default when no roster is given. */
    static final String MOCK_NINE = "Brock Purdy,Derrick Henry,David Montgomery,"
            + "Bhayshul Tuten,Malik Nabers,Mike Evans,Christian Watson,Jayden Reed,"
            + "George Kittle";

    /** Starter-caliber pool at each position: 12 teams, 1QB/2RB/3WR/1TE plus 2 flex. */
    static final Map<Position, Integer> STARTER_POOL = Map.of(
            Position.QB, 12, Position.RB, 36, Position.WR, 48, Position.TE, 12);

    record Risk(String name, Position position, double missed, double odds){}

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();

        Map<String, Risk> file = readInjuryFile();
        if(file.isEmpty()){
            System.out.println("no injury file - nothing to measure");
            return;
        }

        // Starter-caliber baseline. The file covers deep backups too, and their
        // projected games missed drag a whole-file average down, which would
        // make any real starter look fragile by comparison.
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration, List.of(),
                null, null);
        Map<String, Double> projections = planner.points();
        Map<Position, List<String>> byPosition = new EnumMap<>(Position.class);
        for(String id : projections.keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null && StartingLineup.isSkillPosition(player.position)){
                byPosition.computeIfAbsent(player.position, u -> new ArrayList<>()).add(id);
            }
        }
        Map<Position, Double> baseline = new EnumMap<>(Position.class);
        Map<Position, Integer> baselineCount = new EnumMap<>(Position.class);
        for(Map.Entry<Position, List<String>> entry : byPosition.entrySet()){
            List<String> ids = new ArrayList<>(entry.getValue());
            ids.sort(Comparator.comparingDouble(id -> -projections.get(id)));
            int pool = Math.min(STARTER_POOL.getOrDefault(entry.getKey(), 12), ids.size());
            List<Double> missed = new ArrayList<>();
            for(String id : ids.subList(0, pool)){
                Player player = Player.getPlayerFromSIDV2(id);
                Risk risk = file.get(player.firstName + " " + player.lastName);
                if(risk != null){
                    missed.add(risk.missed());
                }
            }
            baselineCount.put(entry.getKey(), missed.size());
            baseline.put(entry.getKey(), missed.stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0));
        }

        String rosterNames = System.getProperty("roster", MOCK_NINE);
        List<Risk> mine = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        for(String name : rosterNames.split(",")){
            Risk risk = file.get(name.trim());
            if(risk == null){
                unmatched.add(name.trim());
            }
            else {
                mine.add(risk);
            }
        }

        System.out.printf("%nMY STARTERS vs the average starter at the same position%n"
                + "(Draft Sharks projected games missed, file dated 2026-07-07)%n%n");
        System.out.printf("%-4s %-22s %8s %9s %9s   %s%n", "POS", "PLAYER",
                "MISSED", "pos avg", "vs avg", "");
        mine.sort(Comparator.comparing((Risk r) -> r.position().ordinal())
                .thenComparing(Comparator.comparingDouble(Risk::missed).reversed()));
        for(Risk risk : mine){
            double average = baseline.getOrDefault(risk.position(), 0.0);
            double delta = risk.missed() - average;
            String flag = delta <= -1.0 ? "durable"
                    : delta >= 1.0 ? "FRAGILE" : "";
            System.out.printf("%-4s %-22s %8.1f %9.1f %+9.1f   %s%n", risk.position(),
                    risk.name(), risk.missed(), average, delta, flag);
        }
        if(!unmatched.isEmpty()){
            System.out.println("\nnot in the injury file: " + unmatched);
        }

        Map<Position, Double> baseRate = BenchValue.overWireByPosition(configuration);
        System.out.printf("%n%-4s %8s %10s %11s %8s %11s %11s%n", "POS", "starters",
                "mine", "baseline", "ratio", "base rate", "adjusted");
        for(Position position : new Position[]{Position.QB, Position.RB,
                Position.WR, Position.TE}){
            List<Risk> group = mine.stream()
                    .filter(r -> r.position() == position).toList();
            if(group.isEmpty()){
                continue;
            }
            double sum = group.stream().mapToDouble(Risk::missed).sum();
            double expected = group.size() * baseline.getOrDefault(position, 0.0);
            double ratio = expected > 0 ? sum / expected : 1.0;
            double rate = baseRate.getOrDefault(position, 0.0);
            System.out.printf("%-4s %8d %10.1f %11.1f %7.2fx %11.1f %11.1f%n", position,
                    group.size(), sum, expected, ratio, rate, rate * ratio);
        }

        double mySum = mine.stream().mapToDouble(Risk::missed).sum();
        double poolSum = mine.stream()
                .mapToDouble(r -> baseline.getOrDefault(r.position(), 0.0)).sum();
        System.out.printf("%nacross the nine: %.1f starter-games projected lost,"
                + " against %.1f for an%naverage nine at the same positions"
                + " (%.2fx).%n", mySum, poolSum,
                poolSum > 0 ? mySum / poolSum : 1.0);
        System.out.printf("%nbaseline pools: %s%n", baselineCount);
        System.out.println("\nthe base rate already contains the ordinary injury"
                + " environment, so only\nthe RATIO is new information here. it is"
                + " a projection made 7 weeks before\nthe draft - nothing from"
                + " training camp is in it.");
    }

    static Map<String, Risk> readInjuryFile() throws Exception {
        Map<String, Risk> risks = new HashMap<>();
        Path file = Path.of("data", "draftsharks-injury-2026-0707.csv");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for(String line : lines.subList(1, lines.size())){
            String[] cells = line.split(",");
            if(cells.length >= 4){
                Position position;
                try {
                    position = Position.valueOf(cells[1].trim());
                }
                catch(IllegalArgumentException notSkill){
                    continue;
                }
                risks.put(cells[0].trim(), new Risk(cells[0].trim(), position,
                        Double.parseDouble(cells[3]), Double.parseDouble(cells[2])));
            }
        }
        return risks;
    }
}
