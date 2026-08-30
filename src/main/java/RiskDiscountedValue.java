import PlayerImportAndSetup.Position;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Model A's objective, extended to ten slots and sixteen rounds, plus exactly
 * one new number per player.
 *
 * The starter-sum simulation that preceded this had about ten hand-set choices
 * in it - tier width, three definitions of the waiver wire, a week collapse, a
 * Gaussian spread, an availability rule, a start/sit rule, roster caps, a wire
 * policy, a scenario count - and was wrong in eight of them inside one day.
 * Every one was a surface to be wrong on. Model A has none, because its
 * objective is a DEFINITION rather than a simulation: the best legal lineup out
 * of projections cannot be wrong, only the projections can.
 *
 * So this keeps the definition and adds one term:
 *
 *     value(player) = projection x (expected games played / 17)
 *
 * A player who is expected to miss three games is worth 14/17 of his
 * projection. That is the whole risk layer. No sampling, no tiers replacing
 * projections, no wire - and no way for it to decide a defence or a tight end
 * is worth an early pick, because both are scored on the same projections
 * everything else is.
 *
 * Games missed comes from the Draft Sharks export where the player is in it,
 * and from the position's measured average where he is not - RB 3.1, WR 2.9,
 * TE 3.0 games, from 2021-2025 (OutcomeDistributions).
 */
public class RiskDiscountedValue implements RosterValue {

    /** The league's real lineup: QB, RB, RB, WR, WR, WR, TE, FLEX, FLEX, DEF. */
    static final Map<Position, Integer> SLOTS = new EnumMap<>(Position.class);
    static {
        SLOTS.put(Position.QB, 1);
        SLOTS.put(Position.RB, 2);
        SLOTS.put(Position.WR, 3);
        SLOTS.put(Position.TE, 1);
        SLOTS.put(Position.DEF, 1);
    }
    static final int FLEX = 2;

    private final Map<String, Double> discounted = new HashMap<>();

    /**
     * What an unfilled slot is worth - the replacement-rank player's own
     * discounted projection.
     *
     * NOT zero. Zero says a slot you have not drafted for stays empty all
     * season, which is false and which made the first version take a defence in
     * round 7: filling an empty slot looked worth a hundred and fifty points
     * when in truth you would fill it in round 16 for nearly as much. One
     * number per position, read off the same projections as everything else -
     * QB21, RB61, WR81, TE19, DEF13, the ranks this league actually leaves
     * undrafted.
     */
    private final Map<Position, Double> unfilled = new EnumMap<>(Position.class);

    public RiskDiscountedValue(Map<String, Double> projections,
                               Map<Position, Double> positionGamesMissed,
                               Map<Position, Integer> replacementRanks,
                               Map<Position, Double> reliability){
        Map<String, Double> missed = draftSharks();

        // A projection is only worth believing as far as its position's
        // preseason ranking has historically predicted the season - 0.63 for
        // backs and receivers, 0.277 for defences. So regress each player
        // toward his position's mean by that much. Without it the objective
        // took a defence in round 7: the best defence PROJECTED 64 points above
        // replacement, and it believed all of it, when the 0.277 says almost
        // none of that gap is real.
        Map<Position, double[]> means = new EnumMap<>(Position.class);
        for(Map.Entry<String, Double> entry : projections.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player != null){
                double[] cell = means.computeIfAbsent(player.position,
                        u -> new double[2]);
                cell[0] += entry.getValue();
                cell[1]++;
            }
        }
        for(Map.Entry<String, Double> entry : projections.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null){
                continue;
            }
            String name = player.firstName + " " + player.lastName;
            double out = missed.containsKey(name) ? missed.get(name)
                    : positionGamesMissed.getOrDefault(player.position, 0.0);
            double available = Math.max(0.0, Math.min(1.0, (17.0 - out) / 17.0));
            double[] cell = means.get(player.position);
            double mean = cell == null || cell[1] == 0 ? entry.getValue()
                    : cell[0] / cell[1];
            double trust = reliability.getOrDefault(player.position, 1.0);
            double believed = mean + trust * (entry.getValue() - mean);
            discounted.put(entry.getKey(), Math.max(0, believed) * available);
        }
        Map<Position, List<Double>> byPosition = new EnumMap<>(Position.class);
        for(Map.Entry<String, Double> entry : discounted.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player != null){
                byPosition.computeIfAbsent(player.position, u -> new ArrayList<>())
                        .add(entry.getValue());
            }
        }
        for(Map.Entry<Position, List<Double>> entry : byPosition.entrySet()){
            List<Double> values = entry.getValue();
            values.sort(Comparator.reverseOrder());
            int rank = replacementRanks.getOrDefault(entry.getKey(),
                    entry.getKey() == Position.DEF ? 13 : 24);
            unfilled.put(entry.getKey(),
                    values.get(Math.min(Math.max(0, rank - 1), values.size() - 1)));
        }
    }

    /** What this objective thinks an empty slot at each position is worth. */
    public Map<Position, Double> unfilledValues(){
        return unfilled;
    }

    /** The whole objective: the best legal ten, on risk-discounted projections. */
    @Override
    public double of(Collection<String> roster){
        Map<Position, List<Double>> available = new EnumMap<>(Position.class);
        for(String id : roster){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player == null || !discounted.containsKey(id)){
                continue;
            }
            available.computeIfAbsent(player.position, u -> new ArrayList<>())
                    .add(discounted.get(id));
        }
        for(List<Double> values : available.values()){
            values.sort(Comparator.reverseOrder());
        }
        double total = 0;
        List<Double> flexPool = new ArrayList<>();
        for(Map.Entry<Position, Integer> slot : SLOTS.entrySet()){
            List<Double> have = available.getOrDefault(slot.getKey(), List.of());
            double replacement = unfilled.getOrDefault(slot.getKey(), 0.0);
            for(int i = 0; i < slot.getValue(); i++){
                total += i < have.size() ? Math.max(have.get(i), replacement)
                        : replacement;
            }
            // a quarterback cannot flex, and neither can a defence
            if(slot.getKey() != Position.QB && slot.getKey() != Position.DEF){
                for(int extra = slot.getValue(); extra < have.size(); extra++){
                    flexPool.add(have.get(extra));
                }
            }
        }
        flexPool.sort(Comparator.reverseOrder());
        double flexReplacement = Math.max(unfilled.getOrDefault(Position.RB, 0.0),
                unfilled.getOrDefault(Position.WR, 0.0));
        for(int i = 0; i < FLEX; i++){
            total += i < flexPool.size() ? Math.max(flexPool.get(i), flexReplacement)
                    : flexReplacement;
        }
        return total;
    }

    @Override
    public String label(){
        return "best legal ten, risk-discounted projections";
    }

    /** Measured games missed by position, so players outside the file still get one. */
    public static Map<Position, Double> positionGamesMissed() throws Exception {
        Map<Position, double[]> totals = new EnumMap<>(Position.class);
        for(List<OutcomeDistributions.Season> season : OutcomeDistributions.all().values()){
            for(OutcomeDistributions.Season player : season){
                if(player.rank() >= 36){
                    continue;
                }
                double[] cell = totals.computeIfAbsent(player.position(),
                        u -> new double[2]);
                cell[0] += Math.max(0, 17 - player.games());
                cell[1]++;
            }
        }
        Map<Position, Double> missed = new EnumMap<>(Position.class);
        for(Map.Entry<Position, double[]> entry : totals.entrySet()){
            missed.put(entry.getKey(), entry.getValue()[1] == 0 ? 0
                    : entry.getValue()[0] / entry.getValue()[1]);
        }
        return missed;
    }

    /** name -> projected games missed, from the Draft Sharks export. */
    static Map<String, Double> draftSharks(){
        Map<String, Double> missed = new HashMap<>();
        try {
            List<String> lines = Files.readAllLines(
                    Path.of("data", "draftsharks-injury-2026-0707.csv"),
                    StandardCharsets.UTF_8);
            for(String line : lines.subList(1, lines.size())){
                String[] cells = line.split(",");
                if(cells.length >= 4){
                    missed.put(cells[0].trim(), Double.parseDouble(cells[3]));
                }
            }
        }
        catch(Exception unreadable){
            System.out.println("no injury file (" + unreadable.getMessage()
                    + ") - falling back to position averages for everyone");
        }
        return missed;
    }
}
