import PlayerImportAndSetup.Position;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Tuesday waiver question, in the shape Justin actually faces it.
 *
 * Not "rank the free agents". Waivers clear Tuesday for men who played since the
 * previous Thursday, a drop is triggered BY an add and is never planned in
 * advance, and DOING NOTHING is both the default and the usual right answer.
 * So this searches PAIRS - add this man, drop that one - and refuses to name
 * one unless it clears its own noise.
 *
 * A pair is worth the roster's value with it minus the roster's value without
 * it, on {@link WeeklyStarterValue}: seventeen weeks of the best legal ten,
 * scored on drawn historical seasons. That objective is the right one here
 * because it prices a BENCH man correctly - by how often he would actually be
 * promoted into the lineup - which is the whole question a waiver claim asks and
 * the thing a projection ranking cannot answer.
 *
 *   ./gradlew run -Pmain=TuesdaySwap [-Pweek=n] [-Pme=<name>] [-Pcandidates=40]
 *                                    [-Pscenarios=480] [-PswapFloor=<points>]
 *
 * TWO HONEST LIMITS, printed with the answer rather than buried.
 *
 * The projections are Sleeper's SEASON numbers, which in-season become
 * rest-of-season - that is what makes them the right feed for "is this man worth
 * having from here", and it also means the objective's seventeen-week framing
 * is now a seventeen-week-equivalent unit rather than a calendar. The report
 * scales the headline to the weeks that are actually left and says so.
 *
 * And the noise floor is the objective's own: `ObjectiveStability` measured the
 * worst seed-to-seed spread of a man's marginal at 6.8 points, so a swap worth
 * less than that is the yardstick moving, not the roster improving.
 */
public class TuesdaySwap {

    /** One candidate move. `gain` is in the objective's seventeen-week units. */
    public record Swap(String addId, String addName, Position addPosition,
                       String dropId, String dropName, double gain) {}

    /** Every legal pair, best first. A swap must keep the roster at its size. */
    static List<Swap> search(List<String> roster, List<String> candidates,
                             Map<String, String> nameOf, Map<String, Position> positionOf,
                             java.util.function.ToDoubleFunction<List<String>> value){
        double base = value.applyAsDouble(roster);
        List<Swap> swaps = new ArrayList<>();
        for(String add : candidates){
            for(String drop : roster){
                List<String> after = new ArrayList<>(roster);
                after.remove(drop);
                after.add(add);
                swaps.add(new Swap(add, nameOf.getOrDefault(add, add), positionOf.get(add),
                        drop, nameOf.getOrDefault(drop, drop), value.applyAsDouble(after) - base));
            }
        }
        swaps.sort(Comparator.comparingDouble(Swap::gain).reversed());
        return swaps;
    }

    /**
     * The second half of a move that empties a lineup slot, and the price of the
     * whole plan.
     *
     * {@link WeeklyStarterValue} fills an unfilled slot from the wire for free.
     * That is deliberate and, on the DRAFT path, sound: the roster still holds
     * sixteen men, one of whom is the streamed defence, so the spot it occupies
     * is charged in the roster accounting. On the WAIVER path there is no such
     * accounting, and Justin caught it - "if I drop ravens, I need to pick up a
     * defense". A sixteen-man roster with no defence is not a roster that fields
     * ten slots; it is a roster one claim short, and that claim costs a spot.
     *
     * So a swap that empties a required slot is not a move, it is the first half
     * of one, and pricing the first half alone reports a gain nobody can
     * actually collect. This finds the cheapest completion - the best free man
     * at the emptied position, and the man he displaces - and returns the whole
     * plan's gain against the roster you started with.
     */
    public record Completion(Swap first, String addId, String addName,
                             String dropId, String dropName, double gain) {}

    /**
     * The completion `swap` forces, or null if it fills every slot on its own.
     * `replacements` are the free agents that could fill the emptied slot.
     */
    static Completion complete(List<String> roster, Swap swap, List<String> replacements,
                               Map<String, String> nameOf, Map<String, Position> positionOf,
                               Map<String, Double> points,
                               java.util.function.ToDoubleFunction<List<String>> value){
        int required = TradeMarket.slotsFilled(roster, points, positionOf);
        List<String> after = new ArrayList<>(roster);
        after.remove(swap.dropId());
        after.add(swap.addId());
        if(TradeMarket.slotsFilled(after, points, positionOf) >= required){
            return null;                      // nothing was emptied; the swap stands
        }
        Position emptied = positionOf.get(swap.dropId());
        double base = value.applyAsDouble(roster);
        Completion best = null;
        for(String replacement : replacements){
            if(positionOf.get(replacement) != emptied || replacement.equals(swap.addId())){
                continue;
            }
            for(String second : after){
                if(second.equals(swap.addId())){
                    continue;                 // the man just claimed is not the man to cut
                }
                List<String> completed = new ArrayList<>(after);
                completed.remove(second);
                completed.add(replacement);
                if(TradeMarket.slotsFilled(completed, points, positionOf) < required){
                    continue;                 // still short; not a plan either
                }
                double gain = value.applyAsDouble(completed) - base;
                if(best == null || gain > best.gain()){
                    best = new Completion(swap, replacement,
                            nameOf.getOrDefault(replacement, replacement),
                            second, nameOf.getOrDefault(second, second), gain);
                }
            }
        }
        return best;
    }

    /**
     * One row per man worth adding, PRICED BEFORE ANYTHING IS RANKED.
     *
     * A raw pair is half a plan. "Drop the Ravens for +3.6" empties the defence
     * slot, and the roster is full, so fielding a defence again costs another
     * spot and another claim - the completed plan is -1.6, and it is the
     * completed plan you can actually collect. Ranking on the raw number puts a
     * man at the top of the board whose real move loses points.
     *
     * So per added man: prefer the best drop that KEEPS EVERY SLOT FILLED, fall
     * back to the one that empties a slot when the safe pairing gains nothing
     * (a man who only helps by leaving a hole must not silently vanish - that
     * would hide the very move the flag exists to warn about), and price that
     * fallback at its completion. `worth` is what the whole plan is worth.
     *
     * THIS LIVED IN LeagueConsole AND NOWHERE ELSE until 2026-09-08, so the page
     * ranked on the completed plan while this tool - which writes the report and
     * names the CLAIM - still ranked on the raw pair. The two named different
     * best adds from the same feeds all season. One function, both callers.
     */
    record Priced(Swap swap, Swap free, boolean hole, Completion completion,
                  double worth, double altGain) {}

    static List<Priced> price(List<Swap> swaps, List<String> roster, List<String> candidates,
                              Map<String, String> nameOf, Map<String, Position> positionOf,
                              Map<String, Double> points,
                              java.util.function.ToDoubleFunction<List<String>> value){
        int slotsNow = TradeMarket.slotsFilled(roster, points, positionOf);
        Map<String, Swap> keepsSlots = new java.util.LinkedHashMap<>();
        Map<String, Swap> unconstrained = new java.util.LinkedHashMap<>();
        for(Swap swap : swaps){                      // already sorted, best first
            unconstrained.putIfAbsent(swap.addId(), swap);
            if(keepsSlots.containsKey(swap.addId())){
                continue;
            }
            List<String> after = new ArrayList<>(roster);
            after.remove(swap.dropId());
            after.add(swap.addId());
            if(TradeMarket.slotsFilled(after, points, positionOf) >= slotsNow){
                keepsSlots.put(swap.addId(), swap);
            }
        }
        List<Swap> shown = new ArrayList<>();
        for(Map.Entry<String, Swap> entry : unconstrained.entrySet()){
            Swap safe = keepsSlots.get(entry.getKey());
            shown.add(safe != null && safe.gain() >= 0.05 ? safe : entry.getValue());
        }
        List<Priced> priced = new ArrayList<>();
        for(Swap swap : shown){
            Swap free = unconstrained.get(swap.addId());
            boolean hole = free == swap && keepsSlots.get(swap.addId()) != swap;
            Completion completion = complete(roster, free, candidates, nameOf, positionOf,
                    points, value);
            double altGain = completion == null ? free.gain() : completion.gain();
            priced.add(new Priced(swap, free, hole, completion,
                    hole && completion != null ? completion.gain() : swap.gain(), altGain));
        }
        priced.sort(Comparator.comparingDouble(Priced::worth).reversed());
        return priced;
    }

    /**
     * The move to make, or null for DO NOTHING - which is the answer whenever
     * the best plan does not clear the floor. Waiting costs nothing and buys a
     * week of information; a move inside the noise costs a roster spot for a
     * coin flip.
     *
     * ON THE PRICED ROWS, not the raw pairs: a pair worth +8 that empties a slot
     * and completes to -1 is not a move that clears an 6.8 floor, it is a loss
     * that clears it. Today's floor happens to sit above every hole-creating
     * pair on the board, which is the only reason this never printed one.
     */
    static Priced recommend(List<Priced> priced, double floor){
        return priced.isEmpty() || priced.get(0).worth() < floor ? null : priced.get(0);
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String season = LeagueWeek.season();
        int week = LeagueWeek.week();
        int scenarios = Integer.getInteger("scenarios", 480);
        int perPosition = Integer.getInteger("candidates", 40);
        double floor = Double.parseDouble(System.getProperty("swapFloor", "6.8"));
        String me = System.getProperty("me", configuration.getUserIDToDisplayName()
                .getOrDefault(configuration.getMyID(), configuration.getMyID()));

        Map<String, Double> points = ProjectionSources.resolve("sleeper");
        WeeklyStarterValue value = WeeklyStarterValue.forCurrentBoard(configuration, points, scenarios, 424_242L);
        Map<String, String> ownerOf = LeagueOwners.today(configuration);

        List<String> roster = new ArrayList<>();
        Map<String, String> nameOf = new HashMap<>();
        Map<String, Position> positionOf = new HashMap<>();
        for(Map.Entry<String, String> entry : ownerOf.entrySet()){
            if(entry.getValue().equals(me)){
                roster.add(entry.getKey());
            }
        }
        Set<String> owned = new HashSet<>(ownerOf.keySet());

        // the wire, pruned to the men who could plausibly matter: the best few
        // dozen per position by projection, because a search over every free
        // agent is a search over a thousand men who are free for a reason
        Map<Position, List<String>> freeByPosition = new HashMap<>();
        for(Map.Entry<String, Double> entry : points.entrySet()){
            if(owned.contains(entry.getKey()) || entry.getValue() == null || entry.getValue() <= 0){
                continue;
            }
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null || player.position == null || player.position == Position.OTHER){
                continue;
            }
            freeByPosition.computeIfAbsent(player.position, u -> new ArrayList<>()).add(entry.getKey());
        }
        List<String> candidates = new ArrayList<>();
        for(Map.Entry<Position, List<String>> entry : freeByPosition.entrySet()){
            List<String> men = entry.getValue();
            men.sort(Comparator.comparingDouble((String id) -> -points.get(id)));
            candidates.addAll(men.subList(0, Math.min(perPosition, men.size())));
        }
        for(String id : new ArrayList<>(candidates)){
            Player player = Player.getPlayerFromSIDV2(id);
            nameOf.put(id, player.firstName + " " + player.lastName);
            positionOf.put(id, player.position);
        }
        for(String id : roster){
            Player player = Player.getPlayerFromSIDV2(id);
            nameOf.put(id, player == null ? id : player.firstName + " " + player.lastName);
            positionOf.put(id, player == null ? null : player.position);
        }

        List<Swap> swaps = search(roster, candidates, nameOf, positionOf, ids -> value.of(ids));
        List<Priced> priced = price(swaps, roster, candidates, nameOf, positionOf, points,
                ids -> value.of(ids));
        Priced best = recommend(priced, floor);
        int weeksLeft = Math.max(1, 15 - week);   // the regular season runs to week 14

        StringBuilder out = new StringBuilder();
        out.append(DataStamp.line()).append("\n");
        out.append(String.format("TUESDAY SWAP  %s  season %s, waivers for week %d  (%s)%n",
                LocalDate.now(), season, week, me));
        out.append(String.format("%d free agents searched against all %d roster spots = %d pairs, on the weekly-starter%n",
                candidates.size(), roster.size(), swaps.size()));
        out.append(String.format("objective (%d drawn seasons), which prices a bench man by how often he would actually start.%n", scenarios));
        out.append(String.format("Gains are in the objective's seventeen-week units; about %d of those weeks are left, so%n"
                + "scale by %.2f for what a move is worth from here.%n", weeksLeft, weeksLeft / 17.0));
        out.append(String.format("Nothing under %.1f points is named: that is the yardstick's own seed-to-seed spread%n"
                + "(ObjectiveStability), so a smaller gain is the measurement moving and not the roster.%n%n", floor));

        // THE COMPLETED PLAN, which is the number you can collect. A row whose
        // drop empties a slot is priced at what refilling it costs, and it is
        // flagged, because "+3.6" and "-1.6 once you field a defence again" are
        // not the same recommendation.
        out.append(String.format("%-22s %-4s -> drop %-22s %9s %9s%n", "ADD", "POS", "", "17wk", "from here"));
        for(Priced row : priced.subList(0, Math.min(8, priced.size()))){
            Swap swap = row.swap();
            out.append(String.format("%-22s %-4s -> drop %-22s %+9.1f %+9.1f%s%n", swap.addName(),
                    swap.addPosition(), swap.dropName(), row.worth(), row.worth() * weeksLeft / 17.0,
                    row.hole() ? "   <- and refill the slot" : ""));
        }
        out.append("\n");

        // THE WHOLE LADDER FOR THE BEST ADD, because a table that names only the
        // cheapest drop invites the reading "so he is worth +4.8 over anyone".
        // He is not: the same man is worth that against the sixteenth-best
        // receiver and deeply negative against the starter at his own position.
        // A marginal is a statement about a PAIR, and printing one member of the
        // pair is how a number gets quoted as if it were about the player.
        if(!priced.isEmpty()){
            String bestAdd = priced.get(0).swap().addId();
            out.append(String.format("EVERY DROP FOR %s - the same claim against each man you hold:%n",
                    priced.get(0).swap().addName().toUpperCase()));
            List<Swap> ladder = new ArrayList<>();
            for(Swap swap : swaps){
                if(swap.addId().equals(bestAdd)){
                    ladder.add(swap);
                }
            }
            for(Swap swap : ladder){
                Completion completion = complete(roster, swap, candidates, nameOf, positionOf,
                        points, ids -> value.of(ids));
                out.append(String.format("   drop %-24s %+7.1f%s%n", swap.dropName(), swap.gain(),
                        completion == null ? ""
                                : String.format("   <- empties a slot; the whole plan (then add %s,"
                                        + " drop %s) is %+.1f",
                                completion.addName(), completion.dropName(), completion.gain())));
            }
            out.append("\n");
        }
        if(best == null){
            out.append(String.format("DO NOTHING. The best plan on the board is worth %+.1f (%.1f from here), inside the%n"
                    + "floor, so it is not a move - it is noise with a transaction attached. Waiting is free and%n"
                    + "buys another week of evidence.%n",
                    priced.isEmpty() ? 0 : priced.get(0).worth(),
                    priced.isEmpty() ? 0 : priced.get(0).worth() * weeksLeft / 17.0));
        }
        else {
            out.append(String.format("CLAIM %s, DROP %s: %+.1f over seventeen weeks, %+.1f from here.%s%n",
                    best.swap().addName(), best.swap().dropName(), best.worth(),
                    best.worth() * weeksLeft / 17.0,
                    best.hole() && best.completion() != null
                            ? String.format(" That empties a slot: the plan includes adding %s and"
                                    + " dropping %s.", best.completion().addName(),
                            best.completion().dropName())
                            : ""));
            out.append("This is worth a claim, not necessarily worth a big FAAB bid - what to pay is FaabBid's\n");
            out.append("question, and it is a different one.\n");
        }
        System.out.print(out);
        Path target = Path.of("data", "tuesday-swap-" + season + "-w" + week + ".txt");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + target);
    }
}
