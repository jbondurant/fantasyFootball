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
import java.util.TreeSet;

/**
 * WHO ACTUALLY TRADES, from this league's own log.
 *
 * Every acceptance signal in {@link TradeMarket} is a model of how a rival
 * VALUES a trade - his starters, his season, his keepers, what he could get
 * elsewhere. Not one of them models whether he trades at all, and that is
 * probably the largest term. A manager who has completed one deal in five years
 * will not take your best offer; one who trades every September will look at
 * anything. Sending to the second sort is most of the job.
 *
 * WHY THIS MEASUREMENT AND NOT A FITTED ACCEPTANCE MODEL. The obvious thing to
 * do with a log of completed trades is to fit what gets accepted - score each on
 * (his starters gained, draft-position gap, his season gained) and learn a
 * boundary. Justin killed that, correctly: "many trades were lopsided, so I'm
 * hesitant about that approach." A sample where lopsided deals were accepted
 * teaches a permissive boundary that says yes to everything, and it is not even
 * a sample of what managers accept - it is a sample of what the managers who say
 * yes accept, which over-represents exactly the people who do not evaluate.
 *
 * Fifty-one trades cannot carry a decision surface over trade terms. They can
 * carry about twelve per-manager rates and a handful of pair counts, robustly,
 * and those sidestep lopsidedness entirely because the question is WHO rather
 * than WHAT TERMS.
 *
 * THE JOIN IS roster_id, per season. A transaction names roster ids, and a
 * roster id means a different person in a different league year, so each
 * season's own rosters and users are fetched and the mapping rebuilt. Attributing
 * 2021's roster 4 to whoever holds roster 4 today would be inventing history.
 *
 *   ./gradlew run -Pmain=TradePartners
 */
public class TradePartners {

    /** One manager's record: seasons seen, trades completed, and with whom. */
    public record Record(String manager, int seasons, int trades,
                         Map<String, Integer> withWhom) {

        /** Trades per season he was in the league. The number that matters. */
        public double rate(){
            return seasons == 0 ? 0 : (double) trades / seasons;
        }
    }

    /** roster_id to display name, for ONE league year. */
    static Map<Integer, String> managersOf(String leagueID){
        Map<String, String> nameByUser = new HashMap<>();
        for(JsonElement element : JsonParser.parseString(InOutUtilities.getCachedForever(
                "https://api.sleeper.app/v1/league/" + leagueID + "/users",
                "sleeperUsers" + leagueID)).getAsJsonArray()){
            JsonObject user = element.getAsJsonObject();
            if(user.has("user_id") && !user.get("user_id").isJsonNull()){
                nameByUser.put(user.get("user_id").getAsString(),
                        user.has("display_name") && !user.get("display_name").isJsonNull()
                                ? user.get("display_name").getAsString()
                                : user.get("user_id").getAsString());
            }
        }
        Map<Integer, String> byRoster = new TreeMap<>();
        for(JsonElement element : JsonParser.parseString(InOutUtilities.getCachedForever(
                "https://api.sleeper.app/v1/league/" + leagueID + "/rosters",
                "sleeperRosters" + leagueID)).getAsJsonArray()){
            JsonObject roster = element.getAsJsonObject();
            if(!roster.has("roster_id") || roster.get("roster_id").isJsonNull()){
                continue;
            }
            String owner = roster.has("owner_id") && !roster.get("owner_id").isJsonNull()
                    ? roster.get("owner_id").getAsString() : null;
            byRoster.put(roster.get("roster_id").getAsInt(),
                    owner == null ? "roster " + roster.get("roster_id").getAsInt()
                            : nameByUser.getOrDefault(owner, owner));
        }
        return byRoster;
    }

    /** The roster ids a move touched, on either side. */
    static Set<Integer> participants(LeagueTransactions.Move move){
        Set<Integer> rosters = new TreeSet<>();
        if(move.adds() != null){
            rosters.addAll(move.adds().values());
        }
        if(move.drops() != null){
            rosters.addAll(move.drops().values());
        }
        return rosters;
    }

    /**
     * Every manager's completed-trade record across the finished seasons.
     *
     * A trade counts once for each manager in it. Deals involving more than two
     * rosters are rare but legal, and counting them per participant is the
     * honest reading of "how often does this person do a deal".
     */
    static List<Record> records(String leagueID){
        Map<String, Integer> trades = new TreeMap<>();
        Map<String, Integer> seasons = new TreeMap<>();
        Map<String, Map<String, Integer>> pairs = new TreeMap<>();
        for(LeagueTransactions.Year year : LeagueTransactions.completedSeasons(leagueID)){
            Map<Integer, String> managerOf = managersOf(year.leagueID());
            for(String manager : managerOf.values()){
                seasons.merge(manager, 1, Integer::sum);
                trades.putIfAbsent(manager, 0);
                pairs.putIfAbsent(manager, new TreeMap<>());
            }
            for(LeagueTransactions.Move move : LeagueTransactions.moves(year)){
                if(!"trade".equals(move.type()) || !move.complete()){
                    continue;
                }
                List<String> inIt = new ArrayList<>();
                for(int roster : participants(move)){
                    String manager = managerOf.get(roster);
                    if(manager != null && !inIt.contains(manager)){
                        inIt.add(manager);
                    }
                }
                for(String manager : inIt){
                    trades.merge(manager, 1, Integer::sum);
                    for(String other : inIt){
                        if(!other.equals(manager)){
                            pairs.computeIfAbsent(manager, u -> new TreeMap<>())
                                    .merge(other, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        List<Record> out = new ArrayList<>();
        for(String manager : seasons.keySet()){
            out.add(new Record(manager, seasons.get(manager), trades.getOrDefault(manager, 0),
                    pairs.getOrDefault(manager, Map.of())));
        }
        out.sort(Comparator.comparingDouble(Record::rate).reversed());
        return out;
    }

    public static void main(String[] args) throws Exception {
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        String leagueID = configuration.getLeagueID();
        List<Record> records = records(leagueID);

        int totalTrades = 0, totalSeasons = 0;
        for(Record record : records){
            totalTrades += record.trades();
            totalSeasons = Math.max(totalSeasons, record.seasons());
        }
        // each trade was counted once per participant, so halve for the count of
        // DEALS - stated rather than left for the reader to wonder about
        StringBuilder out = new StringBuilder();
        out.append(String.format("WHO ACTUALLY TRADES  %s  (%d finished seasons)%n%n",
                LocalDate.now(), totalSeasons));
        out.append("Every acceptance signal on the trades board models how a rival VALUES a deal.\n");
        out.append("None of them models whether he does deals at all, which is probably the bigger\n");
        out.append("term: a manager who has completed one in five years will decline your best offer.\n\n");
        out.append(String.format("%-14s %8s %8s %9s   %s%n",
                "MANAGER", "SEASONS", "TRADES", "PER YEAR", "MOST OFTEN WITH"));
        for(Record record : records){
            String favourite = record.withWhom().entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(e -> e.getKey() + " (" + e.getValue() + ")").orElse("-");
            out.append(String.format("%-14s %8d %8d %9.2f   %s%n",
                    record.manager(), record.seasons(), record.trades(), record.rate(), favourite));
        }
        out.append(String.format("%n%d participations across %d completed trades.%n",
                totalTrades, totalTrades / 2));
        out.append("A trade counts once for each manager in it, so participations are about twice deals.\n\n");
        out.append("READ THIS AGAINST THE BOARD. TradeMarket prices each rival's fallback as the best\n");
        out.append("deal he could find - an assumption of exhaustive search. These rates are what that\n");
        out.append("assumption is worth: a manager doing well under one trade a season is not running\n");
        out.append("a pairwise search over eleven rosters, so his real fallback is far below the\n");
        out.append("computed one and your offer is worth more to him than the board says.\n");

        Path target = Path.of("data", "trade-partners-" + LocalDate.now() + ".txt");
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.print(out);
        System.out.println("written to " + target);
    }
}
