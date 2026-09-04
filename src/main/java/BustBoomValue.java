import PlayerImportAndSetup.Position;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * WeeklyStarterValue with the missing promotion channel bolted on, as KNOBS.
 *
 * This is not a model anyone should ship. It is the instrument for one question:
 * if the objective could see BUST and BOOM as well as injury, would it ever
 * order Justin's positions differently at picks 79-127? The rates are free
 * parameters swept across every plausible value rather than measured, because
 * measuring them is a month of work and the draft is tomorrow.
 *
 * WHAT THE SHIPPED OBJECTIVE DOES. WeeklyStarterValue.oneWeek() drops a starter
 * only when he is drawn !up() - injury - and sorts the survivors by EXPECTED,
 * the preseason projection, which never updates. A starter who plays seventeen
 * games and disappoints keeps his ranking and keeps starting; a bench man who
 * breaks out is never promoted.
 *
 * WHAT THIS ADDS. Each player-season carries a hidden LEVEL: with probability
 * bustRate his real per-game rate is bustFactor x projection, with probability
 * boomRate it is boomFactor x projection, otherwise it is his projection. The
 * season splits into two regimes:
 *
 *     weeks 1..LAG        the lineup is set on the PRESEASON expectation,
 *                         exactly as the shipped objective does it. Nobody
 *                         knows yet.
 *     weeks LAG+1..17     the lineup is set on the UPDATED expectation, which
 *                         is the realised level. The manager has seen LAG weeks
 *                         and has learned who is who.
 *
 *     V(R) = LAG x oneWeek(preseason order) + (17-LAG) x oneWeek(learned order)
 *
 * HINDSIGHT. The promotion rule may only use what LAG weeks of football would
 * have shown, and the flag is a property of the player's SEASON - his level -
 * not of the week being scored. No week's own points are ever used to decide
 * whether to start him that week. That distinction is the whole reason this
 * repo's redundancy findings collapsed twice: sorting candidates by what they
 * went on to score in THAT WEEK is worth a third quarterback and three
 * defences, and none of it is real.
 *
 * DELIBERATELY OVER-POWERED, in three ways, because the job is a BOUND:
 *
 *   detection is PERFECT after the lag - the updated expectation is exactly the
 *   realised level, with no error and no partial credit;
 *   the bust and boom multipliers are applied ON TOP of the drawn historical
 *   season, which already contains busts and booms of its own, so the spread is
 *   double-counted;
 *   promotion is FREE - no roster churn, no waiver cost, no week lost to a
 *   wrong call.
 *
 * Every one of those pushes the channel's value UP. So if a parameter cell does
 * not reorder Justin's picks here, no honest version of this model reorders
 * them either, and that is the finding the sweep is after.
 *
 * COMMON RANDOM NUMBERS. The draw sequence is byte-for-byte the shipped one -
 * the bust/boom uniforms come from a SEPARATE generator - so at bustRate =
 * boomRate = 0 this reproduces WeeklyStarterValue exactly, and every parameter
 * cell is compared in the same sampled world. The uniforms are also drawn once
 * and thresholded per cell, so raising bustRate can only ever ADD busts to the
 * set the lower rate already had.
 */
public class BustBoomValue implements RosterValue {

    static final int WEEKS = WeeklyStarterValue.WEEKS;

    /** How a player-season came out, and the coin that decides his level. */
    record Draw(boolean up, double expected, double rate, double spread,
                double noise, double bustCoin, double boomCoin){}

    /** One cell of the sweep. */
    public record Knobs(double bustRate, double boomRate, int lag,
                        double bustFactor, double boomFactor){

        public static Knobs off(){
            return new Knobs(0, 0, 17, 0.6, 1.6);
        }
    }

    private final int scenarios;
    private final Map<String, Draw[]> byPlayer = new HashMap<>();
    private final Map<Position, Double> wirePerWeek;
    private final Map<String, Position> positionOf;
    private Knobs knobs = Knobs.off();

    /**
     * Built from exactly the inputs WeeklyStarterValue takes, and drawing in
     * exactly the same order. The parallel construction is on purpose: it is
     * what lets the bustRate = 0 row be checked against the shipped objective
     * instead of merely resembling it.
     */
    public BustBoomValue(Map<String, Position> positionOf,
                         Map<String, Integer> tierOf,
                         Map<String, List<OutcomeDistributions.Season>> pool,
                         Map<Position, Double> wirePerWeek,
                         Map<String, Double> expected,
                         int scenarios, long seed){
        this.scenarios = scenarios;
        this.wirePerWeek = wirePerWeek;
        this.positionOf = positionOf;
        Random random = new Random(seed);
        // separate stream, so the shipped sequence above is untouched
        Random coins = new Random(seed ^ 0x5DEECE66DL);
        for(Map.Entry<String, Position> entry : positionOf.entrySet()){
            String id = entry.getKey();
            int tier = tierOf.getOrDefault(id, 3);
            List<OutcomeDistributions.Season> seasons = pool.get(entry.getValue() + ":" + tier);
            if(seasons == null || seasons.isEmpty()){
                seasons = pool.get(entry.getValue() + ":" + Math.max(0, tier - 1));
            }
            double tierMean = 0;
            if(seasons != null && !seasons.isEmpty()){
                for(OutcomeDistributions.Season season : seasons){
                    tierMean += season.meanWhenPlaying() * season.games();
                }
                tierMean /= seasons.size();
            }
            double mine = expected.getOrDefault(id, 0.0);

            Draw[] draws = new Draw[scenarios];
            // the same stratified, noise-free draw as WeeklyStarterValue, consuming
            // `random` in the same order, so at zero rates this IS the shipped
            // objective (BustBoomValueTest pins that)
            List<OutcomeDistributions.Season> order =
                    seasons == null ? List.of() : new ArrayList<>(seasons);
            Collections.shuffle(order, random);
            List<Integer> strata = new ArrayList<>();
            for(int s = 0; s < scenarios; s++){ strata.add(s); }
            Collections.shuffle(strata, random);
            for(int s = 0; s < scenarios; s++){
                if(order.isEmpty() || tierMean <= 0){
                    draws[s] = new Draw(false, 0, 0, 0, 0,
                            coins.nextDouble(), coins.nextDouble());
                    continue;
                }
                OutcomeDistributions.Season drawn = order.get(s % order.size());
                double ratio = drawn.meanWhenPlaying() * drawn.games() / tierMean;
                int games = Math.max(1, drawn.games());
                double rate = mine * ratio / games;
                double u = (strata.get(s) + random.nextDouble()) / scenarios;
                boolean up = u < games / 18.0;
                draws[s] = new Draw(up, mine / 17.0, rate, 0, 0,
                        coins.nextDouble(), coins.nextDouble());
            }
            byPlayer.put(id, draws);
        }
    }

    public void set(Knobs knobs){
        this.knobs = knobs;
    }

    /** 1 if he came out level, bustFactor if he busted, boomFactor if he boomed. */
    double factor(Draw draw){
        if(draw.bustCoin() < knobs.bustRate()){
            return knobs.bustFactor();
        }
        if(draw.boomCoin() < knobs.boomRate()){
            return knobs.boomFactor();
        }
        return 1.0;
    }

    /** What he scores, once his level is known. Same shape as the shipped draw. */
    double points(Draw draw, double factor){
        return draw.up()
                ? Math.max(0, factor * (draw.rate() + draw.noise() * draw.spread()))
                : 0;
    }

    @Override
    public double of(Collection<String> roster){
        int lag = Math.max(0, Math.min(WEEKS, knobs.lag()));
        double total = 0;
        for(int s = 0; s < scenarios; s++){
            if(lag > 0){
                total += lag * oneWeek(roster, s, false);
            }
            if(lag < WEEKS){
                total += (WEEKS - lag) * oneWeek(roster, s, true);
            }
        }
        return total / scenarios;
    }

    /**
     * One drawn week, filled greedily with the wire competing for every slot.
     *
     * Identical to WeeklyStarterValue.oneWeek() except for `learned`: when it is
     * false the ordering key is the preseason projection, when it is true it is
     * the projection times the realised level. The points counted are the same
     * either way - only who gets started changes, which is precisely the channel
     * the shipped objective is missing.
     */
    double oneWeek(Collection<String> roster, int scenario, boolean learned){
        Map<Position, List<double[]>> available = new EnumMap<>(Position.class);
        for(String id : roster){
            Position position = positionOf.get(id);
            Draw[] draws = byPlayer.get(id);
            if(position == null || draws == null || !draws[scenario].up()){
                continue;
            }
            Draw draw = draws[scenario];
            double factor = factor(draw);
            // {what the lineup is set on, what he actually scores}
            available.computeIfAbsent(position, u -> new ArrayList<>())
                    .add(new double[]{learned ? draw.expected() * factor : draw.expected(),
                            points(draw, factor)});
        }
        for(List<double[]> values : available.values()){
            values.sort(Comparator.comparingDouble((double[] v) -> v[0]).reversed());
        }
        List<double[]> flexPool = new ArrayList<>();
        double points = 0;
        points += fill(available.get(Position.QB), 1, Position.QB, null);
        points += fill(available.get(Position.RB), 2, Position.RB, flexPool);
        points += fill(available.get(Position.WR), 3, Position.WR, flexPool);
        points += fill(available.get(Position.TE), 1, Position.TE, flexPool);
        points += fill(available.get(Position.DEF), 1, Position.DEF, null);
        flexPool.sort(Comparator.comparingDouble((double[] v) -> v[0]).reversed());
        double flexWire = Math.max(wirePerWeek.getOrDefault(Position.RB, 0.0),
                                   wirePerWeek.getOrDefault(Position.WR, 0.0));
        for(int slot = 0; slot < 2; slot++){
            points += slot < flexPool.size() && flexPool.get(slot)[0] >= flexWire
                    ? flexPool.get(slot)[1] : flexWire;
        }
        return points;
    }

    private double fill(List<double[]> available, int slots, Position position,
                        List<double[]> flexPool){
        double wire = wirePerWeek.getOrDefault(position, 0.0);
        int size = available == null ? 0 : available.size();
        double points = 0;
        int used = 0;
        for(int slot = 0; slot < slots; slot++){
            if(used < size && available.get(used)[0] >= wire){
                points += available.get(used)[1];
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
        return String.format("bust/boom (%.2f/%.2f, lag %d, %d scenarios)",
                knobs.bustRate(), knobs.boomRate(), knobs.lag(), scenarios);
    }

    /** The same board WeeklyStarterValue.forCurrentBoard builds, same wire. */
    public static BustBoomValue forCurrentBoard(AAAConfiguration configuration,
                                                Map<String, Double> projections,
                                                int scenarios, long seed)
            throws Exception {
        Map<String, Position> positionOf = new HashMap<>();
        Map<String, Integer> tierOf = new HashMap<>();
        Map<Position, List<String>> byPosition = new EnumMap<>(Position.class);
        for(String id : projections.keySet()){
            Player player = Player.getPlayerFromSIDV2(id);
            if(player != null && (StartingLineup.isSkillPosition(player.position)
                    || player.position == Position.DEF)){
                positionOf.put(id, player.position);
                byPosition.computeIfAbsent(player.position, u -> new ArrayList<>()).add(id);
            }
        }
        for(List<String> ids : byPosition.values()){
            ids.sort(Comparator.comparingDouble(id -> -projections.get(id)));
            for(int rank = 0; rank < ids.size(); rank++){
                tierOf.put(ids.get(rank), rank / WeeklyStarterValue.TIER);
            }
        }
        Map<String, List<OutcomeDistributions.Season>> pool = WeeklyStarterValue.pool();
        Map<Position, Integer> replacement = InsuranceTest.replacementRanks(configuration);
        Map<Position, Double> wire = new EnumMap<>(Position.class);
        for(Map.Entry<Position, List<String>> entry : byPosition.entrySet()){
            List<String> ids = entry.getValue();
            int rank = replacement.getOrDefault(entry.getKey(),
                    entry.getKey() == Position.DEF ? 13 : 24);
            int index = Math.min(Math.max(0, rank - 1), ids.size() - 1);
            double held = projections.getOrDefault(ids.get(index), 0.0) / 17.0;
            // the same streamed defence wire as WeeklyStarterValue.forCurrentBoard
            wire.put(entry.getKey(), entry.getKey() == Position.DEF
                    ? held * WeeklyStarterValue.DEF_STREAM_OVER_HOLD : held);
        }
        return new BustBoomValue(positionOf, tierOf, pool, wire, projections,
                scenarios, seed);
    }
}
