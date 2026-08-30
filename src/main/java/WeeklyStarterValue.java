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

    /**
     * One drawn week: was he up, what you EXPECTED of him, and what he scored.
     *
     * Both numbers are needed because a lineup is set before the week. Sorting
     * candidates by what they went on to score is perfect hindsight, and it made
     * redundancy look far more valuable than it is - a second quarterback is
     * only worth the max of two if you know in advance which will hit. That one
     * mistake produced both of the policy's pathologies: a third-round
     * quarterback and three defences.
     */
    record Draw(boolean up, double expected, double points){}

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
                    draws[s] = new Draw(false, 0, 0);
                    continue;
                }
                // ONE observed season drawn whole - games and scoring together
                OutcomeDistributions.Season drawn =
                        seasons.get(random.nextInt(seasons.size()));
                boolean up = random.nextDouble() < drawn.games() / 18.0;
                double points = Math.max(0, drawn.meanWhenPlaying()
                        + random.nextGaussian() * drawn.sdWhenPlaying());
                draws[s] = new Draw(up, drawn.meanWhenPlaying(), up ? points : 0);
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
        Map<Position, List<Draw>> available = new EnumMap<>(Position.class);
        for(String id : roster){
            Position position = positionOf.get(id);
            Draw[] draws = byPlayer.get(id);
            if(position == null || draws == null || !draws[scenario].up()){
                continue;
            }
            available.computeIfAbsent(position, u -> new ArrayList<>())
                    .add(draws[scenario]);
        }
        // sorted by what you EXPECTED, because that is all a lineup can be set
        // on; the points counted are what actually happened
        for(List<Draw> values : available.values()){
            values.sort(Comparator.comparingDouble(Draw::expected).reversed());
        }
        List<Draw> flexPool = new ArrayList<>();
        double points = 0;
        points += fill(available.get(Position.QB), 1, Position.QB, null);
        points += fill(available.get(Position.RB), 2, Position.RB, flexPool);
        points += fill(available.get(Position.WR), 3, Position.WR, flexPool);
        points += fill(available.get(Position.TE), 1, Position.TE, flexPool);
        // The league starts a defence, so V(R) has to score one or every roster
        // is short a slot. It is filled from the wire when the roster has none,
        // which is nearly always the right answer: a preseason defence ranking
        // correlates 0.277 with the season against 0.578 for the skill
        // positions, and 0.047 in 2024. There is too little signal there for a
        // pick to buy anything, so the objective should - and now does - decline
        // to spend one early without being told to.
        points += fill(available.get(Position.DEF), 1, Position.DEF, null);
        flexPool.sort(Comparator.comparingDouble(Draw::expected).reversed());
        double flexWire = Math.max(wirePerWeek.getOrDefault(Position.RB, 0.0),
                wirePerWeek.getOrDefault(Position.WR, 0.0));
        for(int slot = 0; slot < 2; slot++){
            points += slot < flexPool.size() && flexPool.get(slot).expected() >= flexWire
                    ? flexPool.get(slot).points() : flexWire;
        }
        return points;
    }

    private double fill(List<Draw> available, int slots, Position position,
                        List<Draw> flexPool){
        double wire = wirePerWeek.getOrDefault(position, 0.0);
        int size = available == null ? 0 : available.size();
        double points = 0;
        int used = 0;
        for(int slot = 0; slot < slots; slot++){
            // start him only if you EXPECTED him to beat the wire, then take
            // whatever he actually did
            if(used < size && available.get(used).expected() >= wire){
                points += available.get(used).points();
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

    /**
     * Build the objective for the season being drafted: every player on the
     * board placed in a position-and-tier cell, drawing from what players in
     * that cell historically did, with the wire set at the replacement level
     * this league actually leaves undrafted.
     */
    public static WeeklyStarterValue forCurrentBoard(AAAConfiguration configuration,
                                                     Map<String, Double> projections,
                                                     int scenarios, long seed)
            throws Exception {
        Map<String, Position> positionOf = new HashMap<>();
        Map<String, Integer> tierOf = new HashMap<>();
        Map<Position, List<String>> byPosition = new EnumMap<>(Position.class);
        for(String id : projections.keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            // DEF too: the league starts one, and gating this on
            // isSkillPosition left rostered defences with no sampled outcomes
            // at all, so they were silently treated as never available.
            if(player != null && (StartingLineup.isSkillPosition(player.position)
                    || player.position == Position.DEF)){
                positionOf.put(id, player.position);
                byPosition.computeIfAbsent(player.position, u -> new ArrayList<>()).add(id);
            }
        }
        for(List<String> ids : byPosition.values()){
            ids.sort(Comparator.comparingDouble(id -> -projections.get(id)));
            for(int rank = 0; rank < ids.size(); rank++){
                tierOf.put(ids.get(rank), rank / TIER);
            }
        }
        Map<String, List<OutcomeDistributions.Season>> pool = pool();
        Map<Position, Double> wire = wireRates(configuration, pool);
        return new WeeklyStarterValue(positionOf, tierOf, pool, wire, scenarios, seed);
    }

    /**
     * What the waiver wire supplies to a manager who actually works it.
     *
     * The first version averaged the whole replacement tier, which is what the
     * wire offers a manager who never touches it. Nobody drafts that way, and
     * it made the wire too weak: a fourth tight end scoring a shade over a
     * random deep player looked like a gain, so the search hoarded cheap
     * redundancy - it took four tight ends in the first 1-16 run, which no one
     * would hold past October.
     *
     * A manager streaming picks the BEST option available, so the wire is the
     * top of that tier rather than its middle. Chosen on expected rate, not on
     * what the player went on to score: picking the best realised outcome would
     * be the same hindsight that wrecked wireLevel in TightEndTiming, and this
     * is deliberately the honest version - you choose before the week, and then
     * take what comes.
     */
    public static Map<Position, Double> wireRates(AAAConfiguration configuration,
            Map<String, List<OutcomeDistributions.Season>> pool){
        Map<Position, Integer> replacement = InsuranceTest.replacementRanks(configuration);
        Map<Position, Double> wire = new EnumMap<>(Position.class);
        for(Position position : new Position[]{Position.QB, Position.RB, Position.WR,
                Position.TE, Position.DEF}){
            int from = replacement.getOrDefault(position, position == Position.DEF ? 13 : 24);
            // Selected by RANK, not by tier. Tiers are twelve wide, so QB21 -
            // the first quarterback this league leaves undrafted - falls in the
            // 13-24 band, and taking that band's best returned QB13-15 at 18.3
            // points a week. That is a startable quarterback somebody owns, not
            // a wire option.
            List<Double> rates = new ArrayList<>();
            for(List<OutcomeDistributions.Season> cell : pool.values()){
                for(OutcomeDistributions.Season season : cell){
                    if(season.position() == position && season.rank() >= from - 1
                            && season.rank() < from - 1 + 24){
                        rates.add(season.meanWhenPlaying() * season.games() / 18.0);
                    }
                }
            }
            if(rates.isEmpty()){
                wire.put(position, 0.0);
                continue;
            }
            rates.sort(Comparator.reverseOrder());
            int best = Math.max(1, rates.size() / 4);
            wire.put(position, rates.subList(0, best).stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0));
        }
        return wire;
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
