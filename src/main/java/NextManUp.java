import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * THE MAN A TEAMMATE'S INJURY PROMOTES.
 *
 * The waiver model read box scores, and its first live miss was a teammate's
 * injury: Rashod Bateman drew four bids on 2026-09-16, the morning after
 * Baltimore's top receiver by ADP, Zay Flowers, played 20 of the team's 68
 * snaps in week 1 (and none in week 2) while Bateman played 53 - rank 156 of
 * 162 for a model that could not see the team (TRAPS #141, corrected in #148).
 *
 * The event, read from the box scores with the team each man played for that
 * week ({@link LeagueWeek#teamStatsBody}):
 *
 *  - the LEADER at a team and position is the man with the best preseason ADP
 *    among those who played there in the week just played or the two before;
 *  - he WENT DOWN in the week just played when he played under half his
 *    reference share of the team's snaps, or none. His reference is his mean
 *    share over his earlier weeks, and he must have been up (at least half
 *    of it) in his team's previous game: a man already out is old news, and
 *    a bye week is not a game. With no earlier week - week 1, or his first
 *    game - he is judged against the man behind him;
 *  - the NEXT MAN UP is the one of the rest at that team and position with the
 *    most snaps in the week just played, ties to the better ADP.
 *
 * No injury report is read: Sleeper's player database carries TODAY's
 * designation, not a September 2022 one. What the box score shows is the
 * absence or the early exit, and that is what the market reacted to the next
 * morning.
 */
public class NextManUp {

    /** One man's week: the team he played for, his position, his snaps and his team's. */
    record Line(String team, Position position, double snaps, double teamSnaps) {
        double share(){
            return teamSnaps > 0 ? snaps / teamSnaps : 0;
        }
    }

    /** id -> his line, from the /stats array body; rows without a team or outside the four skill positions are skipped. */
    static Map<String, Line> lines(String body){
        Map<String, Line> out = new HashMap<>();
        for(JsonElement e : JsonParser.parseString(body).getAsJsonArray()){
            JsonObject row = e.getAsJsonObject();
            JsonElement team = row.get("team");
            JsonElement id = row.get("player_id");
            JsonElement player = row.get("player");
            if(team == null || team.isJsonNull() || id == null || id.isJsonNull()
                    || player == null || !player.isJsonObject()){
                continue;
            }
            JsonElement position = player.getAsJsonObject().get("position");
            if(position == null || position.isJsonNull()){
                continue;
            }
            Position p;
            try {
                p = Position.valueOf(position.getAsString());
            }
            catch(IllegalArgumentException notOurs){
                continue;
            }
            if(!(p == Position.QB || p == Position.RB || p == Position.WR || p == Position.TE)){
                continue;
            }
            JsonObject stats = row.has("stats") && row.get("stats").isJsonObject() ? row.getAsJsonObject("stats") : new JsonObject();
            out.put(id.getAsString(), new Line(team.getAsString(), p, FaabDemand.stat(stats, "off_snp"),
                    FaabDemand.stat(stats, "tm_off_snp")));
        }
        return out;
    }

    /**
     * The men promoted in week {@code w}, each mapped to the leader who went
     * down. {@code weeks} holds the season's lines for weeks 1..w; ADP is the
     * preseason board, and a man missing from it is undrafted.
     */
    static Map<String, String> promoted(Map<Integer, Map<String, Line>> weeks, int w, Map<String, Double> adp){
        Map<String, Line> now = weeks.getOrDefault(w, Map.of());
        // each man at the team and position of his latest line in weeks w-2..w
        Map<String, Line> latest = new HashMap<>();
        for(int k = Math.max(1, w - 2); k <= w; k++){
            latest.putAll(weeks.getOrDefault(k, Map.of()));
        }
        Map<String, List<String>> groups = new TreeMap<>();
        for(Map.Entry<String, Line> e : latest.entrySet()){
            groups.computeIfAbsent(e.getValue().team() + "|" + e.getValue().position(), k -> new ArrayList<>()).add(e.getKey());
        }
        Map<String, String> out = new TreeMap<>();
        for(List<String> group : groups.values()){
            if(group.size() < 2){
                continue;
            }
            group.sort(Comparator.comparingDouble((String id) -> adp.getOrDefault(id, FaabDemand.UNDRAFTED_ADP))
                    .thenComparing(Comparator.naturalOrder()));
            String leader = group.get(0);
            if(adp.getOrDefault(leader, FaabDemand.UNDRAFTED_ADP) >= FaabDemand.UNDRAFTED_ADP){
                continue;       // nobody there the market drafted: no leader to lose
            }
            String next = null;
            double nextSnaps = -1;
            for(String id : group.subList(1, group.size())){
                Line line = now.get(id);
                if(line != null && line.snaps() > nextSnaps){     // ADP order, so a tie keeps the better pick
                    next = id;
                    nextSnaps = line.snaps();
                }
            }
            if(next == null){
                continue;
            }
            String team = latest.get(leader).team();
            List<Double> earlier = new ArrayList<>();
            boolean seenBefore = false;
            for(int k = 1; k < w; k++){
                Line line = weeks.getOrDefault(k, Map.of()).get(leader);
                if(line != null){
                    seenBefore = true;
                    if(line.snaps() > 0){
                        earlier.add(line.share());
                    }
                }
            }
            // his team's previous game: the last earlier week the team has any line in (a bye has none)
            double previous = 0;
            for(int k = w - 1; k >= 1; k--){
                Map<String, Line> week = weeks.getOrDefault(k, Map.of());
                if(week.values().stream().anyMatch(l -> l.team().equals(team))){
                    Line line = week.get(leader);
                    previous = line == null ? 0 : line.share();
                    break;
                }
            }
            Line leaderNow = now.get(leader);
            if(wentDown(earlier, seenBefore, previous, leaderNow == null ? 0 : leaderNow.share(), now.get(next).share())){
                out.put(next, leader);
            }
        }
        return out;
    }

    /**
     * Did the leader go down this week? {@code earlier} are his shares in the
     * earlier weeks he played; {@code previous} his share in his team's last
     * game; {@code now} this week's (0 for absent); {@code nextShare} the share
     * of the man behind him this week.
     */
    static boolean wentDown(List<Double> earlier, boolean seenBefore, double previous, double now, double nextShare){
        if(earlier.isEmpty()){
            // his first week in the data: judged against the man behind him. A
            // man with earlier lines and no snaps in any of them was already out.
            return !seenBefore && now < 0.5 * nextShare;
        }
        double reference = 0;
        for(double s : earlier){
            reference += s;
        }
        reference /= earlier.size();
        return now < 0.5 * reference && previous >= 0.5 * reference;
    }

    /** The promoted men of a whole season so far, by week: week -> next man up -> leader. */
    static Map<Integer, Map<String, String>> season(java.util.function.IntFunction<Map<String, Line>> lines, int lastPlayed,
                                                     Map<String, Double> adp){
        Map<Integer, Map<String, Line>> weeks = new HashMap<>();
        Map<Integer, Map<String, String>> out = new TreeMap<>();
        for(int w = 1; w <= lastPlayed; w++){
            weeks.put(w, lines.apply(w));
            out.put(w, promoted(weeks, w, adp));
        }
        return out;
    }

    /** Every man's id in a set of events, for callers that only need the flag. */
    static java.util.Set<String> ids(Map<String, String> events){
        return new TreeSet<>(events.keySet());
    }
}
