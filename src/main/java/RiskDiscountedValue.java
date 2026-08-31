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
    private final Map<String, Double> believed = new HashMap<>();

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

    /**
     * Half-width of the rank neighbourhood a projection is shrunk toward.
     *
     * Hand-chosen on 2026-08-30 when the shrinkage target changed from the
     * whole position to a local window, and never tested. ObjectiveAudit
     * measures what the plan does as it varies; TrustCoefficient measures the
     * trust coefficient that BELONGS with each width, which is the part that
     * makes the two constants one constant rather than two.
     */
    public static final int NEIGHBOURHOOD = 6;

    public RiskDiscountedValue(Map<String, Double> projections,
                               Map<Position, Double> positionGamesMissed,
                               Map<Position, Integer> replacementRanks,
                               Map<Position, Double> reliability){
        this(projections, positionGamesMissed, replacementRanks, reliability,
                NEIGHBOURHOOD, draftSharks());
    }

    /**
     * The same objective with its two hand-set inputs exposed: the width of the
     * rank neighbourhood, and the per-player games-missed table.
     *
     * Only an audit should call this. Passing an empty table puts every player
     * on his position's average, which is the cleanest way to ask what the
     * per-player injury feed is actually buying.
     */
    public RiskDiscountedValue(Map<String, Double> projections,
                               Map<Position, Double> positionGamesMissed,
                               Map<Position, Integer> replacementRanks,
                               Map<Position, Double> reliability,
                               int window,
                               Map<String, Double> perPlayerGamesMissed){
        Map<String, Double> missed = perPlayerGamesMissed;

        // A projection is only worth believing as far as its position's
        // preseason ranking has historically predicted the season - 0.63 for
        // backs and receivers, 0.277 for defences. So regress each player
        // toward his position's mean by that much. Without it the objective
        // took a defence in round 7: the best defence PROJECTED 64 points above
        // replacement, and it believed all of it, when the 0.277 says almost
        // none of that gap is real.
        // Shrink toward the mean of COMPARABLE men, not of the whole position.
        // Shrinking toward the position mean crushed skill players and left
        // defences almost untouched: tight ends have hundreds of waiver-level
        // players dragging their mean down, so Kelce lost 53% of his
        // projection, while only 32 defences exist so their mean sits near
        // their top and the Rams lost 15%. That inflated every defence against
        // every skill position - which is the defence bias, and it was
        // invisible until the marginals were printed.
        //
        // The comparison set is the men drafted around him: his own rank
        // neighbourhood, which is what "he might have been picked instead"
        // actually means.
        Map<Position, List<Double>> ranked = new EnumMap<>(Position.class);
        for(Map.Entry<String, Double> entry : projections.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player != null){
                ranked.computeIfAbsent(player.position, u -> new ArrayList<>())
                        .add(entry.getValue());
            }
        }
        for(List<Double> values : ranked.values()){
            values.sort(Comparator.reverseOrder());
        }
        Map<String, Double> neighbourhood = new HashMap<>();
        for(Map.Entry<String, Double> entry : projections.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null){
                continue;
            }
            List<Double> values = ranked.get(player.position);
            int rank = values.indexOf(entry.getValue());
            int from = Math.max(0, rank - window);
            int to = Math.min(values.size(), rank + window + 1);
            double sum = 0;
            for(int i = from; i < to; i++){
                sum += values.get(i);
            }
            neighbourhood.put(entry.getKey(), sum / (to - from));
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
            double mean = neighbourhood.getOrDefault(entry.getKey(), entry.getValue());
            double trust = reliability.getOrDefault(player.position, 1.0);
            double believed = Math.max(0, mean + trust * (entry.getValue() - mean));
            this.believed.put(entry.getKey(), believed);
            discounted.put(entry.getKey(), believed * available);
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

    /** One player's fully discounted value, for tracing a decision. */
    public double valueOf(String id){
        return discounted.getOrDefault(id, 0.0);
    }

    /** His value after the trust shrinkage but before the injury discount. */
    public double believedOf(String id){
        return believed.getOrDefault(id, 0.0);
    }

    /** Print which slot each man fills and what the empty ones contribute. */
    public void explain(java.util.Collection<String> roster){
        Map<Position, List<String>> byPosition = new EnumMap<>(Position.class);
        for(String id : roster){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null && discounted.containsKey(id)){
                byPosition.computeIfAbsent(player.position, u -> new ArrayList<>())
                        .add(id);
            }
        }
        for(List<String> ids : byPosition.values()){
            ids.sort(Comparator.comparingDouble((String id) -> -discounted.get(id)));
        }
        List<String> flexPool = new ArrayList<>();
        for(Map.Entry<Position, Integer> slot : SLOTS.entrySet()){
            List<String> have = byPosition.getOrDefault(slot.getKey(), List.of());
            double replacement = unfilled.getOrDefault(slot.getKey(), 0.0);
            for(int i = 0; i < slot.getValue(); i++){
                if(i < have.size() && discounted.get(have.get(i)) >= replacement){
                    Player player = Player.getPlayerFromSIDV2(have.get(i));
                    System.out.printf("   %-8s %-26s %10.1f%n", slot.getKey(),
                            player.firstName + " " + player.lastName,
                            discounted.get(have.get(i)));
                }
                else {
                    System.out.printf("   %-8s %-26s %10.1f%n", slot.getKey(),
                            "(empty - replacement)", replacement);
                }
            }
            if(slot.getKey() != Position.QB && slot.getKey() != Position.DEF){
                for(int extra = slot.getValue(); extra < have.size(); extra++){
                    flexPool.add(have.get(extra));
                }
            }
        }
        flexPool.sort(Comparator.comparingDouble((String id) -> -discounted.get(id)));
        double flexReplacement = Math.max(unfilled.getOrDefault(Position.RB, 0.0),
                unfilled.getOrDefault(Position.WR, 0.0));
        for(int i = 0; i < FLEX; i++){
            if(i < flexPool.size() && discounted.get(flexPool.get(i)) >= flexReplacement){
                Player player = Player.getPlayerFromSIDV2(flexPool.get(i));
                System.out.printf("   %-8s %-26s %10.1f%n", "FLEX",
                        player.firstName + " " + player.lastName,
                        discounted.get(flexPool.get(i)));
            }
            else {
                System.out.printf("   %-8s %-26s %10.1f%n", "FLEX",
                        "(empty - replacement)", flexReplacement);
            }
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
