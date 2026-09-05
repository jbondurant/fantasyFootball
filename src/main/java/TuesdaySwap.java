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
     * The move to make, or null for DO NOTHING - which is the answer whenever
     * the best pair does not clear the floor. Waiting costs nothing and buys a
     * week of information; a move inside the noise costs a roster spot for a
     * coin flip.
     */
    static Swap recommend(List<Swap> swaps, double floor){
        return swaps.isEmpty() || swaps.get(0).gain() < floor ? null : swaps.get(0);
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
        Swap best = recommend(swaps, floor);
        int weeksLeft = Math.max(1, 15 - week);   // the regular season runs to week 14

        StringBuilder out = new StringBuilder();
        out.append(String.format("TUESDAY SWAP  %s  season %s, waivers for week %d  (%s)%n",
                LocalDate.now(), season, week, me));
        out.append(String.format("%d free agents searched against all %d roster spots = %d pairs, on the weekly-starter%n",
                candidates.size(), roster.size(), swaps.size()));
        out.append(String.format("objective (%d drawn seasons), which prices a bench man by how often he would actually start.%n", scenarios));
        out.append(String.format("Gains are in the objective's seventeen-week units; about %d of those weeks are left, so%n"
                + "scale by %.2f for what a move is worth from here.%n", weeksLeft, weeksLeft / 17.0));
        out.append(String.format("Nothing under %.1f points is named: that is the yardstick's own seed-to-seed spread%n"
                + "(ObjectiveStability), so a smaller gain is the measurement moving and not the roster.%n%n", floor));

        out.append(String.format("%-22s %-4s -> drop %-22s %9s %9s%n", "ADD", "POS", "", "17wk", "from here"));
        for(Swap swap : swaps.subList(0, Math.min(8, swaps.size()))){
            out.append(String.format("%-22s %-4s -> drop %-22s %+9.1f %+9.1f%n", swap.addName(),
                    swap.addPosition(), swap.dropName(), swap.gain(), swap.gain() * weeksLeft / 17.0));
        }
        out.append("\n");
        if(best == null){
            out.append(String.format("DO NOTHING. The best pair on the board is worth %+.1f (%.1f from here), inside the%n"
                    + "floor, so it is not a move - it is noise with a transaction attached. Waiting is free and%n"
                    + "buys another week of evidence.%n",
                    swaps.isEmpty() ? 0 : swaps.get(0).gain(),
                    swaps.isEmpty() ? 0 : swaps.get(0).gain() * weeksLeft / 17.0));
        }
        else {
            out.append(String.format("CLAIM %s, DROP %s: %+.1f over seventeen weeks, %+.1f from here.%n",
                    best.addName(), best.dropName(), best.gain(), best.gain() * weeksLeft / 17.0));
            out.append("This is worth a claim, not necessarily worth a big FAAB bid - what to pay is FaabBid's\n");
            out.append("question, and it is a different one.\n");
        }
        System.out.print(out);
        Path target = Path.of("data", "tuesday-swap-" + season + "-w" + week + ".txt");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + target);
    }
}
