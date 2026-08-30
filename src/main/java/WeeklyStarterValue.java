import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The 1-16 objective: the points a roster's STARTERS score over a season.
 *
 *     V(R) = 17 x E[ best legal lineup from whoever is up in one week ]
 *
 * Weeks are exchangeable because byes are out of scope, so the season collapses
 * to seventeen times one week. That is exact for the expectation - correlation
 * across weeks moves a season's variance, not its mean - and it is what makes
 * this affordable inside a draft search.
 *
 * Three things it does that a season-total rule cannot:
 *
 *   a bench player scores in the weeks he beats the men ahead of him, so his
 *   value is an option payoff and rises with spread rather than washing out;
 *
 *   availability and scoring are drawn TOGETHER, as one observed player-season
 *   from the pool for that position and tier. Measured 2026-08-29, they
 *   correlate at RB 0.347, WR 0.210, TE 0.102, QB 0.669 - because losing a
 *   role costs games and points at the same time - so drawing them apart
 *   understates the weeks a roster is short and weak at once;
 *
 *   an unfillable slot takes the WAIVER WIRE, not a zero. StartingLineup.
 *   bestNine scores unfilled slots at zero, which is right for the nine-round
 *   game and wrong here: it would make a tight end look valuable merely for
 *   existing. The greedy rule is the same - fixed slots take the best at their
 *   position, the flexes take the best two left - but the fill is done here so
 *   the wire can compete for every slot.
 *
 * Scenarios are drawn ONCE and held fixed (sample average approximation), so
 * two rosters are always compared in the same sampled world and the difference
 * between them is never sampling noise.
 */
public class WeeklyStarterValue implements RosterValue {

    static final int WEEKS = 17;
    static final int TIER = 12;

    /** One drawn week for one player: was he up, and what did he score. */
    record Draw(boolean up, double points){}

    private final int scenarios;
    private final Map<String, Draw[]> byPlayer = new HashMap<>();
    private final Map<Position, Double> wirePerWeek;
    private final Map<String, Position> positionOf;

    /**
     * @param tierOf     player id -> his position and 0-based tier at draft time
     * @param pool       historical player-seasons, keyed "POSITION:tier"
     * @param wirePerWeek what the wire supplies at each position, per week
     */
    public WeeklyStarterValue(Map<String, Position> positionOf,
                              Map<String, Integer> tierOf,
                              Map<String, List<OutcomeDistributions.Season>> pool,
                              Map<Position, Double> wirePerWeek,
                              int scenarios, long seed){
        this.scenarios = scenarios;
        this.wirePerWeek = wirePerWeek;
        this.positionOf = positionOf;
        Random random = new Random(seed);
        for(Map.Entry<String, Position> entry : positionOf.entrySet()){
            String id = entry.getKey();
            int tier = tierOf.getOrDefault(id, 3);
            List<OutcomeDistributions.Season> seasons = pool.get(entry.getValue() + ":" + tier);
            if(seasons == null || seasons.isEmpty()){
                seasons = pool.get(entry.getValue() + ":" + Math.max(0, tier - 1));
            }
            Draw[] draws = new Draw[scenarios];
            for(int s = 0; s < scenarios; s++){
                if(seasons == null || seasons.isEmpty()){
                    draws[s] = new Draw(false, 0);
                    continue;
                }
                // ONE observed season drawn whole - games and scoring together
                OutcomeDistributions.Season drawn =
                        seasons.get(random.nextInt(seasons.size()));
                boolean up = random.nextDouble() < drawn.games() / 18.0;
                double points = Math.max(0, drawn.meanWhenPlaying()
                        + random.nextGaussian() * drawn.sdWhenPlaying());
                draws[s] = new Draw(up, up ? points : 0);
            }
            byPlayer.put(id, draws);
        }
    }

    @Override
    public double of(Collection<String> roster){
        double total = 0;
        for(int s = 0; s < scenarios; s++){
            total += oneWeek(roster, s);
        }
        return WEEKS * total / scenarios;
    }

    /** The greedy legal fill, with the wire competing for every slot. */
    double oneWeek(Collection<String> roster, int scenario){
        Map<Position, List<Double>> available = new EnumMap<>(Position.class);
        for(String id : roster){
            Position position = positionOf.get(id);
            Draw[] draws = byPlayer.get(id);
            if(position == null || draws == null || !draws[scenario].up()){
                continue;
            }
            available.computeIfAbsent(position, u -> new ArrayList<>())
                    .add(draws[scenario].points());
        }
        for(List<Double> values : available.values()){
            values.sort(Comparator.reverseOrder());
        }
        List<Double> flexPool = new ArrayList<>();
        double points = 0;
        points += fill(available.get(Position.QB), 1, Position.QB, null);
        points += fill(available.get(Position.RB), 2, Position.RB, flexPool);
        points += fill(available.get(Position.WR), 3, Position.WR, flexPool);
        points += fill(available.get(Position.TE), 1, Position.TE, flexPool);
        flexPool.sort(Comparator.reverseOrder());
        double flexWire = Math.max(wirePerWeek.getOrDefault(Position.RB, 0.0),
                wirePerWeek.getOrDefault(Position.WR, 0.0));
        for(int slot = 0; slot < 2; slot++){
            points += slot < flexPool.size()
                    ? Math.max(flexPool.get(slot), flexWire) : flexWire;
        }
        return points;
    }

    private double fill(List<Double> available, int slots, Position position,
                        List<Double> flexPool){
        double wire = wirePerWeek.getOrDefault(position, 0.0);
        int size = available == null ? 0 : available.size();
        double points = 0;
        int used = 0;
        for(int slot = 0; slot < slots; slot++){
            if(used < size && available.get(used) >= wire){
                points += available.get(used);
                used++;
            }
            else {
                points += wire;
            }
        }
        if(flexPool != null){
            for(int extra = used; extra < size; extra++){
                flexPool.add(available.get(extra));
            }
        }
        return points;
    }

    @Override
    public String label(){
        return "weekly starter sum (" + scenarios + " scenarios)";
    }

    /** Historical player-seasons keyed POSITION:tier, ready to draw from. */
    public static Map<String, List<OutcomeDistributions.Season>> pool() throws Exception {
        Map<String, List<OutcomeDistributions.Season>> pool = new HashMap<>();
        for(List<OutcomeDistributions.Season> season : OutcomeDistributions.all().values()){
            for(OutcomeDistributions.Season s : season){
                pool.computeIfAbsent(s.position() + ":" + (s.rank() / TIER),
                        u -> new ArrayList<>()).add(s);
            }
        }
        return pool;
    }
}
