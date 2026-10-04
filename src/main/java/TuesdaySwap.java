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
 *                                    [-Pscenarios=480] [-PswapFloor=<points>] [-Pprojections=ros]
 *
 * PRICED ON THE GAMES LEFT since 2026-09-29: Sleeper's weekly projections
 * summed over the games still to play (sleeper-remaining), with the
 * rest-of-season model (ros) beside every row. On 2026-09-28 the season feed
 * put "add Jaxson Dart, drop Bo Nix" second on this board - Dart was on IR,
 * his season over, and the season feed still carried him at 340.5. The trade
 * tools had moved off it that morning (TRAPS #150); this is the same move for
 * the wire (TRAPS #151). The history below is why the season feed was never
 * the right number in season.
 *
 * TWO HONEST LIMITS, printed with the answer rather than buried.
 *
 * The season feed's projections are Sleeper's SEASON numbers. This header used to say they
 * "in-season become rest-of-season"; that was a sentence, not a measurement,
 * and the measurement went the other way: on 2026-09-14, after fifteen of week
 * one's sixteen games, not one of 186 rostered skill men's season numbers had
 * moved on a box score (six moved, every one on an injury tag or a news item -
 * `WeekReaction` counts this every run, `MarketMovers` independently). So the
 * feed is a preseason number that moves on news, the objective's seventeen-week
 * framing is a unit rather than a calendar, and the report scales the headline
 * to the weeks actually left and says so (TRAPS #134, #136).
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

    /** The wire's pricing, and the console's: Sleeper's weekly projections for the games left. */
    static final String DEFAULT_SOURCE = "sleeper-remaining";

    /** The other pricing printed beside every row: the rest-of-season model, or the games left when ros is the one priced. */
    static String altOf(String source){
        return "ros".equals(source) ? DEFAULT_SOURCE : "ros";
    }

    /** The last week of this league's regular season (playoffs from week 15). */
    static final int LAST_REGULAR_WEEK = 14;

    /**
     * WHAT A GAIN IS DENOMINATED IN, AND SO WHAT IT IS WORTH FROM HERE AND WHAT
     * FLOOR IT HAS TO CLEAR.
     *
     * The objective reads each man's total as seventeen weeks of a rate, and is
     * exactly proportional to the totals it is given (the expected score, the
     * drawn rate and the wire all scale with them). So a gain is in the units
     * of the feed that priced it:
     *
     *   a season feed (sleeper, ros, posterior) - SEASON TOTALS. A gain is over
     *   the whole season, and what is left of it is the weeks from `week` to
     *   the end of the regular season over seventeen. The feed does update its
     *   totals in season - Tuten's keeper surplus rose from 33.8 to 58.0 the
     *   week after his first game - but an updated total is still a total.
     *
     *   a games-left feed (sleeper-remaining) - GAMES-LEFT TOTALS, weeks now to
     *   18. A gain is already over the games left, and scaling it by
     *   (15 - week) / 17 as well would discount it twice: a man worth 10 over
     *   the games left at week 4 would bid as if worth 6.5. What is left of the
     *   regular season is its share of the weeks the feed spans.
     *
     * One function for the report and the page: the report printed the scaled
     * number beside every row while the page bid FAAB on the unscaled one, and
     * a man worth 20 in week 8 drew a $3 bid there against $1 here.
     *
     * The noise floor is NOT converted here. It scales with the size of the
     * totals, and a games-left total is not the fraction of a season total its
     * weeks suggest - Sleeper's lines for the weeks to come add up to about its
     * season number - so each pricing is judged against the floor measured on
     * it (ObjectiveStability.floorFor, TRAPS #151).
     */
    record Units(String source, boolean gamesLeft, double spanWeeks, double regularWeeks){

        /** A gain in these units, as points collectable in the regular season left. */
        double fromHere(double gain){
            return spanWeeks <= 0 ? 0 : gain * regularWeeks / spanWeeks;
        }

        /** What a gain in these units is, in words. */
        String describe(){
            return gamesLeft
                    ? String.format("points over the games left (%.1f weeks to week %d, %s's own totals)",
                            spanWeeks, WeeklyActuals.WEEKS, source)
                    : "the objective's seventeen-week units (" + source + " carries season totals)";
        }

        static Units season(String source, int week){
            return new Units(source, false, 17, Math.max(1, LAST_REGULAR_WEEK + 1 - week));
        }

        static Units gamesLeft(String source, ProjectionSources.Horizon horizon){
            return new Units(source, true, horizon.weeks(), horizon.regularWeeks());
        }
    }

    /** The units of a pricing source this week. */
    static Units units(String source, int week){
        return DEFAULT_SOURCE.equals(source)
                ? Units.gamesLeft(source, ProjectionSources.remainingHorizon(LAST_REGULAR_WEEK))
                : Units.season(source, week);
    }

    /** The roster after a whole plan: the add and drop, and the completion's add and drop when it empties a slot. */
    static List<String> planAfter(List<String> roster, Priced row){
        List<String> after = new ArrayList<>(roster);
        after.remove(row.swap().dropId());
        after.add(row.swap().addId());
        if(row.completion() != null){
            after.remove(row.completion().dropId());
            after.add(row.completion().addId());
        }
        return after;
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
    /** Every free agent as an add with nothing dropped, best first: the value of the roster with him, less without. */
    static List<Map.Entry<String, Double>> openSpot(List<String> roster, List<String> candidates,
                                                    java.util.function.ToDoubleFunction<List<String>> value){
        double base = value.applyAsDouble(roster);
        List<Map.Entry<String, Double>> out = new ArrayList<>();
        for(String id : candidates){
            List<String> with = new ArrayList<>(roster);
            with.add(id);
            out.add(Map.entry(id, value.applyAsDouble(with) - base));
        }
        out.sort(Comparator.comparingDouble((Map.Entry<String, Double> e) -> -e.getValue()));
        return out;
    }

    static Priced recommend(List<Priced> priced, double floor){
        return priced.isEmpty() || priced.get(0).worth() < floor ? null : priced.get(0);
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String season = LeagueWeek.season();
        int week = LeagueWeek.week();
        int scenarios = Integer.getInteger("scenarios", 480);
        int perPosition = Integer.getInteger("candidates", 40);
        String me = System.getProperty("me", configuration.getUserIDToDisplayName()
                .getOrDefault(configuration.getMyID(), configuration.getMyID()));

        // The default is Sleeper's weekly projections for the games left, which drop
        // a man Sleeper has ruled out; -Pprojections=ros prices on the rest-of-season
        // model, sleeper on the season feed, which moves on neither results nor
        // most news (TRAPS #136, #151).
        String source = System.getProperty("projections", DEFAULT_SOURCE);
        Map<String, Double> points = ProjectionSources.resolve(source);
        Units units = units(source, week);
        // the yardstick measured on THIS pricing, never another's converted
        ObjectiveStability.Floor measured = ObjectiveStability.floorFor(source);
        double floor = measured.points();
        WeeklyStarterValue value = WeeklyStarterValue.forCurrentBoard(configuration, points, scenarios, 424_242L);
        Map<String, String> ownerOf = LeagueOwners.today(configuration);

        List<String> roster = new ArrayList<>();
        Map<String, String> nameOf = new HashMap<>();
        Map<String, Position> positionOf = new HashMap<>();
        // a man on IR holds no active spot and cannot be the drop; he stays OWNED
        // (not a free agent) but leaves the roster this search may cut from
        Set<String> reserve = LeagueOwners.reserve(configuration);
        for(Map.Entry<String, String> entry : ownerOf.entrySet()){
            if(entry.getValue().equals(me) && !reserve.contains(entry.getKey())){
                roster.add(entry.getKey());
            }
        }
        Set<String> owned = new HashSet<>(ownerOf.keySet());
        // -Pir="Mike Evans": men you are about to move to an IR slot (this league
        // takes Out and Doubtful there, two slots). They leave the active roster as
        // a reserve man does, and the spot they free is priced below as an add with
        // no drop. Justin, 2026-09-29: "which athletes to submit waiver claim for
        // while evans is in ir".
        List<String> toIr = TradeScreens.idsNamed(System.getProperty("ir", ""));
        roster.removeAll(toIr);

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

        // THE OTHER PRICING, BESIDE EVERY LISTED ROW. On 2026-09-25 a +3.1 claim of
        // Brenton Strange for Dalton Schultz was read off this report's Sleeper
        // column while the pricing that had seen Schultz's 20-point week put the
        // same move at or below zero - and the $0 recommendation went out on the
        // number that could not see the results. Both are printed now, and a
        // CLAIM is named only when the move is not a loss under either (TRAPS #146).
        String altSource = altOf(source);
        Units altUnits = units(altSource, week);
        WeeklyStarterValue alt = WeeklyStarterValue.forCurrentBoard(configuration,
                ProjectionSources.resolve(altSource), scenarios, 424_242L);
        double altBase = alt.of(roster);
        Map<Priced, Double> altWorth = new HashMap<>();
        for(Priced row : priced.subList(0, Math.min(8, priced.size()))){
            altWorth.put(row, alt.of(planAfter(roster, row)) - altBase);
        }
        if(best != null){
            altWorth.computeIfAbsent(best, row -> alt.of(planAfter(roster, row)) - altBase);
        }
        Priced blocked = null;
        if(best != null && altWorth.get(best) < 0){
            blocked = best;
            best = null;
        }

        StringBuilder out = new StringBuilder();
        out.append(DataStamp.line()).append("\n");
        out.append(String.format("TUESDAY SWAP  %s  season %s, waivers for week %d  (%s; priced on %s, with %s beside it)%n",
                LocalDate.now(), season, week, me, source, altSource));
        out.append(String.format("%d free agents searched against all %d roster spots = %d pairs, on the weekly-starter%n",
                candidates.size(), roster.size(), swaps.size()));
        out.append(String.format("objective (%d drawn seasons), which prices a bench man by how often he would actually start.%n", scenarios));
        out.append(String.format("Gains are %s; %.1f of those%n"
                + "weeks are the regular season, so a gain is worth %.2f of itself from here.%n",
                units.describe(), units.regularWeeks(), units.fromHere(1.0)));
        out.append(String.format("Nothing under %.1f points is named: that is the yardstick's own worst seed-to-seed spread,%n"
                + "measured on %s on %s (ObjectiveStability), so a smaller gain is the measurement moving%n"
                + "and not the roster.%n%n", floor, measured.source(), measured.date()));

        // THE COMPLETED PLAN, which is the number you can collect. A row whose
        // drop empties a slot is priced at what refilling it costs, and it is
        // flagged, because "+3.6" and "-1.6 once you field a defence again" are
        // not the same recommendation.
        // WHAT EACH MOVE DOES TO THE TITLE (TitleOdds): the rows are ranked on points,
        // and the title column says when in the season those points arrive. The men
        // off this search's roster (IR slots, -Pir) come back in the title roster,
        // because a man on IR now is projected back when he is.
        TitleOdds.Stakes stakes = TitleOdds.stakes(configuration, TitleOdds.SIMS);
        List<String> away = new ArrayList<>();
        for(Map.Entry<String, String> entry : ownerOf.entrySet()){
            if(entry.getValue().equals(me) && !roster.contains(entry.getKey())){
                away.add(entry.getKey());
            }
        }
        out.append(String.format("%-22s %-4s -> drop %-22s %9s %9s %9s %8s%n", "ADD", "POS", "",
                units.gamesLeft() ? "left" : "17wk", "from here", "alt here", "title"));
        for(Priced row : priced.subList(0, Math.min(8, priced.size()))){
            Swap swap = row.swap();
            List<String> titleRoster = planAfter(roster, row);
            titleRoster.addAll(away);
            out.append(String.format("%-22s %-4s -> drop %-22s %+9.1f %+9.1f %+9.1f %+7.2f%s%s%n", swap.addName(),
                    swap.addPosition(), swap.dropName(), row.worth(), units.fromHere(row.worth()),
                    altUnits.fromHere(altWorth.get(row)), 100 * stakes.one(me, titleRoster).change(),
                    row.hole() ? "   <- and refill the slot" : "",
                    altWorth.get(row) < 0 && row.worth() > 0 ? "   <- a loss on " + altSource : ""));
        }
        out.append(String.format("title is the change in your title odds in percentage points (TitleOdds, %d seasons, the same draws%n"
                + "before and after); your odds now are %.1f%%.%n", TitleOdds.SIMS, 100 * stakes.title(me)));
        out.append(String.format("alt here is the same move priced on %s and put in from-here points too, so the two columns%n"
                + "read in one unit; ros is the rest-of-season model that has seen the results (RosModel),%n"
                + "sleeper-remaining Sleeper's weekly projections for the games left, which drop a man ruled out.%n", altSource));
        out.append("\n");

        // THE OPEN SPOT: with a man on IR there is room for one more, so every free
        // agent is priced as an add with nothing dropped. It is a rental: when he
        // comes off IR a man must go to activate him, and the swap table above is
        // that later choice.
        if(!toIr.isEmpty()){
            List<String> named = new ArrayList<>();
            for(String id : toIr){
                Player player = Player.getPlayerFromSIDV2(id);
                named.add(player == null ? id : player.firstName + " " + player.lastName);
            }
            List<Map.Entry<String, Double>> open = openSpot(roster, candidates, ids -> value.of(ids));
            out.append(String.format("OPEN SPOT - with %s on IR, each free agent added with NO drop:%n", String.join(", ", named)));
            out.append(String.format("%-22s %-4s %9s %9s %9s %8s%n", "ADD", "POS", units.gamesLeft() ? "left" : "17wk", "from here",
                    "alt here", "title"));
            for(Map.Entry<String, Double> e : open.subList(0, Math.min(8, open.size()))){
                List<String> with = new ArrayList<>(roster);
                with.add(e.getKey());
                double altGain = alt.of(with) - altBase;
                List<String> titleRoster = new ArrayList<>(with);
                titleRoster.addAll(away);
                out.append(String.format("%-22s %-4s %+9.1f %+9.1f %+9.1f %+7.2f%s%n", nameOf.get(e.getKey()), positionOf.get(e.getKey()),
                        e.getValue(), units.fromHere(e.getValue()), altUnits.fromHere(altGain),
                        100 * stakes.one(me, titleRoster).change(), e.getValue() < floor ? "   <- inside the floor" : ""));
            }
            out.append(String.format("A rental until %s is activated, when somebody must be dropped - read the swap table above for who.%n%n",
                    String.join(", ", named)));
        }

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
        if(blocked != null){
            out.append(String.format("NOT CLAIMED: %s for %s clears the floor at %+.1f on %s but is a loss of %+.1f from here on %s.%n"
                    + "A move that loses under either pricing is not a move.%n%n", blocked.swap().addName(),
                    blocked.swap().dropName(), blocked.worth(), source,
                    altUnits.fromHere(altWorth.get(blocked)), altSource));
        }
        if(best == null){
            out.append(String.format("DO NOTHING. The best plan on the board is worth %+.1f (%.1f from here), inside the%n"
                    + "floor, so it is not a move - it is noise with a transaction attached. Waiting is free and%n"
                    + "buys another week of evidence.%n",
                    priced.isEmpty() ? 0 : priced.get(0).worth(),
                    priced.isEmpty() ? 0 : units.fromHere(priced.get(0).worth())));
        }
        else {
            out.append(String.format("CLAIM %s, DROP %s: %+.1f %s, %+.1f from here.%s%n",
                    best.swap().addName(), best.swap().dropName(), best.worth(),
                    units.gamesLeft() ? "over the games left" : "over seventeen weeks",
                    units.fromHere(best.worth()),
                    best.hole() && best.completion() != null
                            ? String.format(" That empties a slot: the plan includes adding %s and"
                                    + " dropping %s.", best.completion().addName(),
                            best.completion().dropName())
                            : ""));
            out.append("This is worth a claim, not necessarily worth a big FAAB bid - what to pay is FaabBid's\n");
            out.append("question, and it is a different one.\n");
        }
        System.out.print(out);
        // an -Pir run is a different roster (the man on IR is out of it), so it gets
        // its own file: it overwrote the plain report once, and the console's ladder
        // check rightly failed against a fifteen-man ladder
        Path target = Path.of("data", "tuesday-swap-" + season + "-w" + week
                + (source.equals(DEFAULT_SOURCE) ? "" : "-" + source.replace(':', '_').replace(',', '_'))
                + (toIr.isEmpty() ? "" : "-ir") + ".txt");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + target);
    }
}
