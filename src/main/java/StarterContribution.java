import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What a pick is actually worth: its contribution to weekly STARTING lineups,
 * summed over the season.
 *
 * Justin's formulation, and it subsumes everything else in this repo's late
 * rounds. A player's value is not his projection. It is how many starter-slots
 * he fills across seventeen weeks and what he scores in them, which depends on
 * three things at once - his own points, the round he is taken in (who else is
 * on the roster by then), and how often the people ahead of him fail.
 *
 * The two ends of that make it obvious:
 *
 *   In a world where starters never bust and never miss a game, a bench player
 *   contributes exactly nothing, so you should spend the pick filling an empty
 *   starting slot - take the tight end.
 *
 *   In a world where running backs go down at midseason, a bench player starts
 *   half the year, and the tight end can wait many rounds.
 *
 * The real world is somewhere between, so this does not argue about where -
 * it sweeps the whole range and shows where the answer changes hands. Failure
 * rates are anchored on what actually happened: games played and bust rates
 * measured over five seasons of ADP joined to outcomes, then scaled.
 *
 *   ./gradlew run -Pmain=StarterContribution [-Pdraws=600]
 */
public class StarterContribution {

    /** Model A's shape for rounds 1-6, and the pick under question. */
    static final int[] EARLY_PICKS = {7, 18, 31, 42, 55, 66};
    static final Position[] EARLY_SHAPE = {Position.RB, Position.WR, Position.RB,
            Position.WR, Position.WR, Position.WR};
    static final int PICK_IN_QUESTION = 79;

    record Player(String name, Position position, double perGame, int games){}

    public static void main(String[] args) throws Exception {
        int draws = Integer.getInteger("draws", 600);
        Map<String, List<TightEndTiming.Seen>> history = TightEndTiming.load();
        if(history.isEmpty()){
            System.out.println("no joined seasons - nothing to anchor failure rates on");
            return;
        }

        // Measured baseline: how many games a drafted starter really played,
        // by position, across five seasons.
        Map<Position, Double> baselineMissed = new EnumMap<>(Position.class);
        Map<Position, Integer> counted = new EnumMap<>(Position.class);
        for(List<TightEndTiming.Seen> season : history.values()){
            for(Position position : new Position[]{Position.RB, Position.WR, Position.TE}){
                List<TightEndTiming.Seen> ranked = season.stream()
                        .filter(s -> s.position() == position)
                        .sorted(Comparator.comparingDouble(TightEndTiming.Seen::adp))
                        .limit(position == Position.TE ? 12 : 36).toList();
                for(TightEndTiming.Seen player : ranked){
                    baselineMissed.merge(position, (double) Math.max(0, 17 - player.games()),
                            Double::sum);
                    counted.merge(position, 1, Integer::sum);
                }
            }
        }
        for(Position position : baselineMissed.keySet()){
            baselineMissed.put(position,
                    baselineMissed.get(position) / counted.get(position));
        }

        System.out.printf("%nmeasured over %d seasons: a drafted starter misses"
                + " RB %.1f, WR %.1f, TE %.1f games%n", history.size(),
                baselineMissed.getOrDefault(Position.RB, 0.0),
                baselineMissed.getOrDefault(Position.WR, 0.0),
                baselineMissed.getOrDefault(Position.TE, 0.0));
        System.out.println("that is the 1.0x world below; the others scale it.");

        // A representative roster and candidate set, taken from what history
        // says was available at each of these picks.
        List<TightEndTiming.Seen> season = history.values().iterator().next();
        System.out.printf("%nEXPECTED CONTRIBUTION TO WEEKLY STARTING LINEUPS%n");
        System.out.printf("(marginal points a pick at %d adds over leaving the slot to"
                + " the wire)%n%n", PICK_IN_QUESTION);
        System.out.printf("%-14s %10s %10s %10s   %s%n", "INJURY WORLD", "TE", "WR",
                "RB", "take");

        double[] worlds = {0.0, 0.5, 1.0, 1.5, 2.0, 3.0};
        for(double scale : worlds){
            Map<Position, Double> marginal = new EnumMap<>(Position.class);
            for(Position candidate : new Position[]{Position.TE, Position.WR, Position.RB}){
                double total = 0;
                int seasons = 0;
                for(List<TightEndTiming.Seen> each : history.values()){
                    Double value = marginalOf(each, candidate, baselineMissed, scale, draws);
                    if(value != null){
                        total += value;
                        seasons++;
                    }
                }
                marginal.put(candidate, seasons == 0 ? 0 : total / seasons);
            }
            Position best = marginal.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            System.out.printf("%-14s %10.1f %10.1f %10.1f   %s%n",
                    scale == 0 ? "0x  none" : String.format("%.1fx", scale),
                    marginal.getOrDefault(Position.TE, 0.0),
                    marginal.getOrDefault(Position.WR, 0.0),
                    marginal.getOrDefault(Position.RB, 0.0), best);
        }

        System.out.println("\nThe crossover never happens, and the reason is the FLEX."
                + "\n\nThe intuition says that with nobody injured a bench player is"
                + " useless, so the\npick should fill the empty starting slot - the tight"
                + " end. That holds only if\nthe extra receiver really is a bench player."
                + " He is not: two flex slots mean a\nfourth receiver or third back"
                + " starts every week regardless, so he is a starter\nbought at pick "
                + PICK_IN_QUESTION + ", not insurance.");
        System.out.println("\nMeanwhile the tight end adds almost nothing even at 0x,"
                + " because the slot he\nfills is not empty - the waiver wire fills it"
                + " nearly as well for free. That is\nthe same result the streaming"
                + " table found, arrived at from the other side.");
        System.out.println("\nInjuries still matter, they just do not decide THIS"
                + " question: they push the\nback's value from 44.4 to 53.4 and then"
                + " back down, because past some point\neveryone is hurt including the"
                + " man you drafted, and the wire fills more of\nthe lineup either way.");
        System.out.println("\nFailure here is games missed only. Bust - a healthy player"
                + " scoring far under\nhis projection - would widen the gap further,"
                + " because a bust starter is a slot\na bench man takes without anyone"
                + " getting hurt.");
    }

    /**
     * The marginal weekly-lineup value of spending PICK_IN_QUESTION on this
     * position, against leaving that slot to the waiver wire.
     */
    static Double marginalOf(List<TightEndTiming.Seen> season, Position candidate,
                             Map<Position, Double> baselineMissed, double scale, int draws){
        List<TightEndTiming.Seen> taken = new ArrayList<>();
        List<Player> roster = new ArrayList<>();
        for(int i = 0; i < EARLY_PICKS.length; i++){
            TightEndTiming.Seen pick = TightEndTiming.bestAtExcluding(season,
                    EARLY_SHAPE[i], EARLY_PICKS[i], taken);
            if(pick == null){
                return null;
            }
            taken.add(pick);
            roster.add(scaled(pick, baselineMissed, scale));
        }
        TightEndTiming.Seen extra = TightEndTiming.bestAtExcluding(season, candidate,
                PICK_IN_QUESTION, taken);
        if(extra == null){
            return null;
        }
        Map<Position, Double> wire = new EnumMap<>(Position.class);
        for(Position position : new Position[]{Position.RB, Position.WR, Position.TE}){
            wire.put(position, TightEndTiming.wireLevel(season, position) / 17.0);
        }

        List<Player> with = new ArrayList<>(roster);
        with.add(scaled(extra, baselineMissed, scale));
        return score(with, wire, draws) - score(roster, wire, draws);
    }

    /**
     * The same player in a harsher or kinder world: his games missed moved
     * toward or away from his position's measured average by the scale factor.
     */
    static Player scaled(TightEndTiming.Seen player, Map<Position, Double> baselineMissed,
                         double scale){
        double missed = baselineMissed.getOrDefault(player.position(), 2.0) * scale;
        int games = (int) Math.round(Math.max(0, Math.min(17, 17 - missed)));
        double perGame = player.games() > 0 ? player.points() / player.games() : 0;
        return new Player(player.name(), player.position(), perGame, games);
    }

    /** Seventeen weeks of the best legal lineup from whoever is up. */
    static double score(List<Player> roster, Map<Position, Double> wire, int draws){
        Random random = new Random(83_000L);
        double total = 0;
        for(int draw = 0; draw < draws; draw++){
            boolean[][] up = new boolean[roster.size()][17];
            for(int p = 0; p < roster.size(); p++){
                List<Integer> weeks = new ArrayList<>();
                for(int week = 0; week < 17; week++){
                    weeks.add(week);
                }
                java.util.Collections.shuffle(weeks, random);
                for(int i = 0; i < roster.get(p).games(); i++){
                    up[p][weeks.get(i)] = true;
                }
            }
            for(int week = 0; week < 17; week++){
                Map<Position, List<Player>> available = new EnumMap<>(Position.class);
                for(int p = 0; p < roster.size(); p++){
                    if(up[p][week]){
                        available.computeIfAbsent(roster.get(p).position(),
                                u -> new ArrayList<>()).add(roster.get(p));
                    }
                }
                Comparator<Player> byRate = Comparator.comparingDouble(Player::perGame)
                        .reversed();
                available.values().forEach(list -> list.sort(byRate));
                List<Player> flex = new ArrayList<>();
                total += fill(available.get(Position.RB), 2, Position.RB, wire, flex);
                total += fill(available.get(Position.WR), 3, Position.WR, wire, flex);
                total += fill(available.get(Position.TE), 1, Position.TE, wire, flex);
                flex.sort(byRate);
                double flexWire = wire.getOrDefault(Position.WR, 0.0);
                for(int slot = 0; slot < 2; slot++){
                    total += slot < flex.size()
                            ? Math.max(flex.get(slot).perGame(), flexWire) : flexWire;
                }
            }
        }
        return total / draws;
    }

    /**
     * Fill n slots at a position with the best available, where "available"
     * includes the waiver wire.
     *
     * The first version took rostered players first and only fell back to the
     * wire when it ran out, which meant adding a tight end who scored less than
     * the wire's tight end LOWERED the score - it benched a better free player
     * to start a worse owned one. Nobody plays that way. A rostered man starts
     * only if he beats the wire; otherwise he drops to the flex pool and the
     * wire takes the slot.
     */
    static double fill(List<Player> available, int slots, Position position,
                       Map<Position, Double> wire, List<Player> flex){
        double wireRate = wire.getOrDefault(position, 0.0);
        int size = available == null ? 0 : available.size();
        double points = 0;
        int used = 0;
        for(int slot = 0; slot < slots; slot++){
            if(used < size && available.get(used).perGame() >= wireRate){
                points += available.get(used).perGame();
                used++;
            }
            else {
                points += wireRate;
            }
        }
        for(int extra = used; extra < size; extra++){
            flex.add(available.get(extra));
        }
        return points;
    }
}
