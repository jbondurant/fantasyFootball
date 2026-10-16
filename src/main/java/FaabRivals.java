import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * WHO ELSE WANTS HIM: the waiver run as the auction it is.
 *
 * FAAB here is a sealed first-price auction - each manager bids, the highest
 * pays his own bid. What a man costs is set by the most motivated OTHER bidder,
 * so the useful question about a target is not only what he is worth to Justin
 * but who else he would help and how much each of them has left to spend.
 * FaabBid prices a claim from the league's past clearing prices, the same for
 * every target; this reads the room for each one. Justin, 2026-10-03, on the
 * ideas serious tools use: "let's do all of what you'd build."
 *
 * Every manager's gain from a target is priced on the same objective as the
 * wire (WeeklyStarterValue on sleeper-remaining): his active roster with the
 * target in and his lowest-projected active man out, less as it stands. A gain
 * over the noise floor is a manager the target genuinely helps.
 *
 * What it does not know is how a manager decides. People bid on box scores and
 * headlines as much as on need (FaabDemand measures that side: who gets any bid
 * at all), so this is the need half of the market, said as that. And it cannot
 * turn a gain into dollars: the league's past claims do not record what the
 * winner thought the man was worth.
 *
 *   ./gradlew run -Pmain=FaabRivals [-PfaabTargets=8]
 */
public class FaabRivals {

    /** One manager's gain from one target, and the man he would cut for him. */
    record Need(String manager, double gain, String cut) {}

    /** A target's market: Justin's gain and every rival's, best first. */
    record Market(String id, double mine, List<Need> rivals) {

        /** Rivals he helps beyond the noise: the ones with a reason to bid. */
        int bidders(double floor){
            int n = 0;
            for(Need need : rivals){
                n += need.gain() >= floor ? 1 : 0;
            }
            return n;
        }
    }

    /** Each manager's gain from the target: in for his lowest-projected active man. */
    static List<Need> needs(String target, Map<String, List<String>> active, Map<String, Double> points,
                            java.util.function.ToDoubleFunction<List<String>> value, Map<String, Double> base){
        List<Need> out = new ArrayList<>();
        for(Map.Entry<String, List<String>> e : active.entrySet()){
            String cut = TradeMarket.worstOther(e.getValue(), List.of(target), points);
            if(cut == null){
                continue;
            }
            List<String> after = new ArrayList<>(e.getValue());
            after.remove(cut);
            after.add(target);
            out.add(new Need(e.getKey(), value.applyAsDouble(after) - base.get(e.getKey()), cut));
        }
        out.sort(Comparator.comparingDouble((Need n) -> -n.gain()));
        return out;
    }

    /**
     * The run a claim placed now clears in - the league's daily run at noon
     * Eastern, today if before it, else tomorrow (a man just dropped clears a day
     * later) - and what claims on that weekday have cost here, 2021-2025.
     */
    static String runNote(java.time.ZonedDateTime now, Map<java.time.DayOfWeek, double[]> days){
        java.time.ZonedDateTime run = now.withHour(12).withMinute(0).withSecond(0).withNano(0);
        if(!now.isBefore(run)){
            run = run.plusDays(1);
        }
        java.time.DayOfWeek day = run.getDayOfWeek();
        double[] d = days.get(day);
        if(d == null){
            return String.format("A claim placed now clears %s noon. (No by-day table yet: run FaabDemand.)%n", day);
        }
        java.time.DayOfWeek big = day;
        double most = -1;
        for(Map.Entry<java.time.DayOfWeek, double[]> e : days.entrySet()){
            if(e.getValue()[0] > most){
                most = e.getValue()[0];
                big = e.getKey();
            }
        }
        return String.format("A claim placed now clears %s noon%s. On %ss here, 2021-2025: %.0f%% of claims contested, median price $%.0f,%n"
                        + "p75 $%.0f, p90 $%.0f; a bid of $1 won %.0f%%, $3 %.0f%%, $5 %.0f%%.%s%n",
                day, day == big ? " - THE BIG RUN, the first after the games" : "", day.toString().charAt(0) + day.toString().substring(1).toLowerCase(),
                100 * d[1], d[2], d[3], d[4], 100 * d[5], 100 * d[6], 100 * d[7],
                day == big ? "" : String.format(" The %s run is where the money goes (%.0f%% contested, median $%.0f): 'need' is%n"
                        + "who a man would help, and off that day it is a ceiling on competition, not a forecast of it.",
                        big, 100 * days.get(big)[1], days.get(big)[2]));
    }

    /** Does a claim placed now clear in the big run (the weekday most claims clear)? True when there is no table to say. */
    static boolean clearsOnBigRun(java.time.ZonedDateTime now, Map<java.time.DayOfWeek, double[]> days){
        if(days.isEmpty()){
            return true;
        }
        java.time.ZonedDateTime run = now.withHour(12).withMinute(0).withSecond(0).withNano(0);
        if(!now.isBefore(run)){
            run = run.plusDays(1);
        }
        java.time.DayOfWeek big = null;
        double most = -1;
        for(Map.Entry<java.time.DayOfWeek, double[]> e : days.entrySet()){
            if(e.getValue()[0] > most){
                most = e.getValue()[0];
                big = e.getKey();
            }
        }
        return run.getDayOfWeek() == big;
    }

    /** FAAB left per manager, from the rosters feed's own counter. */
    static Map<String, Integer> faabLeft(AAAConfiguration configuration, Map<Integer, String> managerOf, int budget){
        Map<String, Integer> left = new TreeMap<>();
        for(JsonElement element : JsonParser.parseString(configuration.getTodaysRosterWebPageSerious()).getAsJsonArray()){
            JsonObject roster = element.getAsJsonObject();
            if(!roster.has("roster_id") || !roster.has("settings")){
                continue;
            }
            JsonObject s = roster.getAsJsonObject("settings");
            String manager = managerOf.get(roster.get("roster_id").getAsInt());
            if(manager != null){
                int used = s.has("waiver_budget_used") && !s.get("waiver_budget_used").isJsonNull() ? s.get("waiver_budget_used").getAsInt() : 0;
                left.put(manager, budget - used);
            }
        }
        return left;
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String me = configuration.getUserIDToDisplayName().getOrDefault(configuration.getMyID(), configuration.getMyID());
        int perPosition = Integer.getInteger("faabTargets", 8);
        String source = TuesdaySwap.DEFAULT_SOURCE;
        Map<String, Double> points = ProjectionSources.resolve(source);
        WeeklyStarterValue value = WeeklyStarterValue.forCurrentBoard(configuration, points, 480, 424_242L);
        double floor = ObjectiveStability.floorFor(source).points();
        Map<String, String> ownerOf = LeagueOwners.today(configuration);
        Set<String> reserve = LeagueOwners.reserve(configuration);
        Map<String, List<String>> active = new TreeMap<>();
        ownerOf.forEach((id, m) -> {
            if(!reserve.contains(id)){
                active.computeIfAbsent(m, k -> new ArrayList<>()).add(id);
            }
        });
        Map<String, Double> base = new HashMap<>();
        active.forEach((m, roster) -> base.put(m, value.of(roster)));
        int budget = configuration.getLeagueJson().getAsJsonObject("settings").get("waiver_budget").getAsInt();
        Map<String, Integer> left = faabLeft(configuration, SeasonLedger.managerByRoster(configuration), budget);

        // the targets: the best few free agents per position on the wire's own pricing
        Map<Position, List<String>> free = new HashMap<>();
        points.forEach((id, p) -> {
            Player player = ownerOf.containsKey(id) || p == null || p <= 0 ? null : Player.getPlayerFromSIDV2(id);
            if(player != null && player.position != null && player.position != Position.OTHER){
                free.computeIfAbsent(player.position, k -> new ArrayList<>()).add(id);
            }
        });
        List<Market> markets = new ArrayList<>();
        for(List<String> men : free.values()){
            men.sort(Comparator.comparingDouble((String id) -> -points.get(id)));
            for(String id : men.subList(0, Math.min(perPosition, men.size()))){
                List<Need> all = needs(id, active, points, value::of, base);
                double mine = 0;
                List<Need> rivals = new ArrayList<>();
                for(Need n : all){
                    if(n.manager().equals(me)){
                        mine = n.gain();
                    }
                    else{
                        rivals.add(n);
                    }
                }
                markets.add(new Market(id, mine, rivals));
            }
        }
        // a target nobody gains from is below the wire's own floor for everyone: not a market
        markets.removeIf(m -> m.mine() <= 0.5 && (m.rivals().isEmpty() || m.rivals().get(0).gain() <= 0.5));
        markets.sort(Comparator.comparingDouble((Market m) -> -(m.rivals().isEmpty() ? 0 : m.rivals().get(0).gain())));

        StringBuilder out = new StringBuilder();
        out.append(DataStamp.line()).append('\n');
        out.append(String.format("FAAB RIVALS  %s  (the waiver run as an auction; priced on %s, 480 drawn seasons)%n", LocalDate.now(), source));
        // WHICH RUN THE CLAIM LANDS IN, first. Justin, 2026-10-16: "you overestimate
        // bidding wars for non wednesday days. They almost never happen." FaabDemand
        // measured it: the run after the games (Wednesday) holds three dollars in four
        // and half its claims are contested; any other day a claim clears at a median
        // of $0. Need below is who a man would help, not who will bid on a Saturday.
        out.append(runNote(java.time.ZonedDateTime.now(FaabDemand.LEAGUE_ZONE), FaabDemand.days())).append('\n');
        out.append(String.format("Each target's gain to every manager: his active roster with the target in for his lowest-projected man,%n"
                + "less as it stands. A sealed first-price auction is priced by the most motivated OTHER bidder, so the%n"
                + "columns are the three rivals he helps most and the FAAB each has left. 'need' counts rivals he helps by%n"
                + "more than the noise floor (%.1f). This is need, not hype: FaabDemand measures who draws a bid at all.%n%n", floor));
        out.append(String.format("%-22s %-4s %7s %5s   %-26s %-26s %-26s%n", "TARGET", "POS", "you", "need", "rival 1 (gain, $ left)",
                "rival 2", "rival 3"));
        for(Market m : markets){
            Player p = Player.getPlayerFromSIDV2(m.id());
            StringBuilder row = new StringBuilder(String.format("%-22s %-4s %+7.1f %5d  ", p == null ? m.id() : p.firstName + " " + p.lastName,
                    p == null ? "" : p.position, m.mine(), m.bidders(floor)));
            for(Need n : m.rivals().subList(0, Math.min(3, m.rivals().size()))){
                row.append(String.format(" %-26s", String.format("%s %+.1f $%d", n.manager(), n.gain(), left.getOrDefault(n.manager(), 0))));
            }
            out.append(row).append('\n');
        }
        out.append(String.format("%nYour FAAB left: $%d. Read a row as: if 'need' is 0 nobody else gains beyond the noise and a small bid%n"
                + "should do; if a rival with money left gains more than you do, you are bidding against his need, not the%n"
                + "league's average price.%n", left.getOrDefault(me, 0)));
        System.out.print(out);
        Path report = Path.of("data", "faab-rivals-" + LocalDate.now() + ".txt");
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + report);
    }
}
