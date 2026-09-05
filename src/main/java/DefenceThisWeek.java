import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
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
 * Which defence to start this week - the live counterpart of the one streaming
 * policy this repo has actually measured.
 *
 * The model's whole defence advice rests on {@link WireRateStress}: a manager
 * who STREAMS on form, reacting after week 2, scores 8.03 a week where holding
 * the best undrafted defence all season scores 7.21, both hindsight-free. That
 * number decides what a drafted defence is worth, and until now nothing
 * implemented the policy it describes, so the number applied to nobody.
 *
 * This reproduces it exactly rather than approximately, because a policy that
 * merely resembles the backtested one inherits none of its measurement:
 *
 *   weeks 1..lag   there is no form yet, so take the best FREE defence by
 *                  preseason ADP - the only ranking that exists
 *   week > lag     take the free defence with the best points per game over the
 *                  weeks ALREADY FINISHED, preseason ADP breaking ties
 *
 * Nothing in the choice for week w may see week w. `LeagueWeek.actual` refuses
 * an unfinished week, so that is enforced rather than promised.
 *
 *   ./gradlew run -Pmain=DefenceThisWeek [-Pweek=n] [-Plag=2] [-Pme=<name>]
 *
 * WHAT IT DOES NOT SAY. The 8.03 was measured for a manager who streams all
 * season and never drafts a defence. Justin drafted the Ravens, so his weekly
 * question is "start the man I hold, or claim the form leader" - a related
 * question, not the same one. The report prints both sides and says which
 * comparison is measured and which is not.
 */
public class DefenceThisWeek {

    /** One defence this week: what it is projected, its form so far, and its preseason rank. */
    public record Defence(String id, String name, double preseasonAdp, Double form,
                          int gamesSoFar, boolean mine, boolean free) {}

    /**
     * The best defence Justin can START this week under the measured rule:
     * before `lag` finished weeks exist, by preseason ADP; after, by points per
     * game over the finished weeks. Null when he can start nothing.
     *
     * THE POOL IS HIS OWN DEFENCE PLUS THE FREE ONES, and that is a deliberate
     * departure from {@link WireRateStress#form}, which chooses among UNDRAFTED
     * defences only because the manager it models never drafts one. Applied
     * literally to a manager who holds a defence, that rule produces nonsense:
     * on the real 2026 week-1 board every defence better than the Ravens was
     * rostered, so "the best free defence" was the Lions at ADP 174.7 against a
     * Ravens at 132.4, and the tool cheerfully advised trading down. The
     * streaming edge comes from SWITCHING ON FORM later, not from starting a
     * worse defence in week 1. A man who holds one keeps the option to start
     * him, so he belongs in the choice set.
     */
    static Defence pick(List<Defence> pool, int finishedWeeks, int lag){
        List<Defence> startable = new ArrayList<>();
        for(Defence defence : pool){
            if(defence.free() || defence.mine()){
                startable.add(defence);
            }
        }
        if(startable.isEmpty()){
            return null;
        }
        startable.sort(Comparator.comparingDouble(Defence::preseasonAdp));
        if(finishedWeeks < lag){
            return startable.get(0);   // no form yet; ADP is the only ranking there is
        }
        Defence best = startable.get(0);
        double bestForm = -Double.MAX_VALUE;
        for(Defence defence : startable){
            // ADP order breaks ties because the list is already sorted by it
            if(defence.form() != null && defence.form() > bestForm){
                bestForm = defence.form();
                best = defence;
            }
        }
        return best;
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String season = LeagueWeek.season();
        int week = LeagueWeek.week();
        int lag = Integer.getInteger("lag", 2);
        String me = System.getProperty("me", configuration.getUserIDToDisplayName()
                .getOrDefault(configuration.getMyID(), configuration.getMyID()));

        // preseason ADP from the FROZEN draft-night feed - immutable, committed,
        // and the same numbers the room drafted against
        Map<String, Double> adp = new HashMap<>();
        Map<String, String> nameOf = new HashMap<>();
        JsonArray feed = JsonParser.parseString(Files.readString(
                Path.of("data", "fixtures", season + "-pre-draft", "sleeperProjections" + season + ".txt")))
                .getAsJsonArray();
        for(JsonElement element : feed){
            JsonObject row = element.getAsJsonObject();
            JsonObject player = row.getAsJsonObject("player");
            if(player == null || !"DEF".equals(player.has("position") && !player.get("position").isJsonNull()
                    ? player.get("position").getAsString() : null)){
                continue;
            }
            JsonObject stats = row.getAsJsonObject("stats");
            if(stats == null || !stats.has("adp_half_ppr")){
                continue;
            }
            String id = row.get("player_id").getAsString();
            adp.put(id, stats.get("adp_half_ppr").getAsDouble());
            nameOf.put(id, player.has("last_name") && !player.get("last_name").isJsonNull()
                    ? player.get("last_name").getAsString() : id);
        }

        // form over the FINISHED weeks only
        int finished = 0;
        Map<String, double[]> scoredAndPlayed = new HashMap<>();
        for(int w = 1; w < week; w++){
            if(!LeagueWeek.finished(w)){
                continue;
            }
            finished++;
            Map<String, Double> actual = LeagueWeek.actual(season, w);
            for(String id : adp.keySet()){
                Double points = actual.get(id);
                if(points == null){
                    continue;
                }
                double[] running = scoredAndPlayed.computeIfAbsent(id, u -> new double[2]);
                running[0] += points;
                running[1] += 1;
            }
        }

        Map<String, String> ownerOf = LeagueOwners.today(configuration);
        List<Defence> pool = new ArrayList<>();
        for(Map.Entry<String, Double> entry : new TreeMap<>(adp).entrySet()){
            String id = entry.getKey();
            double[] running = scoredAndPlayed.get(id);
            Double form = running == null || running[1] == 0 ? null : running[0] / running[1];
            String owner = ownerOf.get(id);
            pool.add(new Defence(id, nameOf.getOrDefault(id, id), entry.getValue(), form,
                    running == null ? 0 : (int) running[1], me.equals(owner), owner == null));
        }
        Defence policy = pick(pool, finished, lag);
        Defence held = pool.stream().filter(Defence::mine).findFirst().orElse(null);

        StringBuilder out = new StringBuilder();
        out.append(String.format("DEFENCE FOR WEEK %d  %s  season %s  (%s)%n", week, LocalDate.now(), season, me));
        out.append(String.format("The measured policy (WireRateStress: stream on form, react after week %d - 8.03 a week%n"
                + "against 7.21 for holding the best undrafted defence, both hindsight-free).%n", lag));
        out.append(finished < lag
                ? String.format("%d finished week(s), fewer than the lag, so the pick is the best FREE defence by preseason ADP -%n"
                        + "the only ranking that exists yet. This is what the backtest does in these weeks too.%n%n", finished)
                : String.format("%d finished week(s): the pick is the best FREE defence by points per game over those weeks.%n"
                        + "Nothing in this choice can see week %d.%n%n", finished, week));
        out.append(String.format("%-16s %8s %8s %7s   %s%n", "DEFENCE", "ADP", "FORM", "GAMES", "WHO HOLDS HIM"));
        List<Defence> shown = new ArrayList<>(pool);
        shown.sort(finished < lag
                ? Comparator.comparingDouble(Defence::preseasonAdp)
                : Comparator.comparingDouble((Defence d) -> d.form() == null ? Double.MAX_VALUE : -d.form()));
        for(Defence defence : shown.subList(0, Math.min(12, shown.size()))){
            out.append(String.format("%-16s %8.1f %8s %7d   %s%n", defence.name(), defence.preseasonAdp(),
                    defence.form() == null ? "-" : String.format("%.1f", defence.form()),
                    defence.gamesSoFar(),
                    defence.mine() ? "YOU" : defence.free() ? "free" : ownerOf.getOrDefault(defence.id(), "?")));
        }
        out.append("\n");
        if(policy == null){
            out.append("No free defence at all - nothing to stream, start what you hold.\n");
        }
        else if(held == null){
            out.append(String.format("You hold no defence. The policy says claim and start %s.%n", policy.name()));
        }
        else if(policy.id().equals(held.id())){
            out.append(String.format("START %s - you already hold the best defence available to you%s.%n", held.name(),
                    finished < lag ? " by preseason rank" : " on form"));
        }
        else {
            out.append(String.format("THE POLICY SAYS: claim and start %s (free%s).%n", policy.name(),
                    policy.form() == null ? String.format(", preseason ADP %.1f", policy.preseasonAdp())
                            : String.format(", %.1f a game over %d", policy.form(), policy.gamesSoFar())));
            out.append(String.format("YOU HOLD: %s (%s).%n", held.name(),
                    held.form() == null ? String.format("preseason ADP %.1f", held.preseasonAdp())
                            : String.format("%.1f a game over %d", held.form(), held.gamesSoFar())));
            out.append("\nRead this honestly. The 8.03 was measured for a manager who streams ALL SEASON and never\n");
            out.append("drafts a defence, choosing among UNDRAFTED men only. You drafted one, so the choice set here\n");
            out.append("is yours plus the free men - otherwise the rule would tell you to trade down to whatever the\n");
            out.append("league happened to leave, which is what it did on the week-1 board before this was fixed.\n");
            out.append("What IS measured is that over five seasons, choosing on form beat holding by 0.82 points a\n");
            out.append("week - about 14 a season, against a weekly noise far larger. Treat it as a tilt, not an\n");
            out.append("instruction, and never claim a defence at a real FAAB price on this margin alone.\n");
        }
        System.out.print(out);
        Path target = Path.of("data", "defence-" + season + "-w" + week + ".txt");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.println("written to " + target);
    }
}
