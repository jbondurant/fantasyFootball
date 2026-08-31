import PlayerImportAndSetup.Position;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Draft boards for every season Sleeper still has outcomes for, joined to
 * those outcomes - and honest about how much of each board it could not join.
 *
 * The board is Fantasy Football Calculator's ADP, aggregated from real 12-team
 * drafts, one request a season. The outcomes are Sleeper's, in this league's
 * points via {@link EraActuals}. The join between them is BY NAME, and that is
 * the part of the whole exercise most likely to fail quietly: a season that
 * matched 70% of its board would still produce a perfectly plausible number,
 * computed on a board with a third of the players deleted - which is not a
 * board anybody drafted from, and nothing in the output would say so.
 *
 * So the match rate is a first-class output, not a log line. {@link #build}
 * records it per season, {@link EraIngest} prints it with the unmatched names,
 * and {@link #usable} refuses a season that falls below the gate.
 *
 * Three traps, all found by looking rather than by assuming:
 *
 *   - The season stat rows carry an embedded player object with first and last
 *     name, so men who retired in 2014 resolve without the Sleeper player
 *     directory, which no longer reliably holds them.
 *   - Matching only players who SCORED would delete the busts, and the injured
 *     first-rounder is exactly the outcome a draft plan has to survive. The
 *     directory is built from every row in the season, scored or not.
 *   - FFC speaks modern team abbreviations for every season, so its 2013 board
 *     calls the St. Louis Rams LAR while that season's weekly files say STL.
 *     Defences are joined through that translation and checked against the ids
 *     the season really used.
 */
public class EraBoards {

    public static final int TEAMS = 12;

    /**
     * PPR boards, for every season, including the ones where half-PPR exists.
     *
     * This league is half-PPR and FFC publishes half-PPR from 2018, so the
     * obvious choice is half-PPR where available and PPR before. It is the
     * wrong one. That policy puts a FORMAT CHANGE at 2018, in the middle of the
     * sample, exactly where the old-versus-recent question is being asked - and
     * any difference found between the eras would then be inseparable from the
     * boards having changed scoring. PPR runs the whole way, so it is the
     * format that lets the regime test mean something.
     *
     * It costs accuracy on the board: PPR lifts receivers and pass-catching
     * backs relative to half-PPR. That cost is measured rather than argued
     * about - -Pformat=half-ppr reruns everything from 2018 on the league's own
     * format, and RegimeShift reports whether the verdict moves.
     *
     * PPR is also the deeper feed. The 2022 half-PPR board has 122 players and
     * cannot supply an eleven-round draft; its PPR board has 152.
     */
    public static String defaultFormat(String season){
        return "ppr";
    }

    public record Row(String id, String name, Position position, double adp){}

    /** How much of one season's board reached its outcomes, and what did not. */
    public record Match(String season, String format, int boardRows, int matched,
                        int ambiguous, int topHundred, int topHundredMatched,
                        int skill, int defences, List<String> missedByAdp, int weeks,
                        int drafts, int exact, int loosened){
        public double rate(){
            return boardRows == 0 ? 0 : (double) matched / boardRows;
        }
        public double topRate(){
            return topHundred == 0 ? 0 : (double) topHundredMatched / topHundred;
        }
    }

    public record Board(String season, String format, List<String> ids,
                        Map<String, Position> positionOf, Map<String, Double> adp,
                        List<Map<String, Double>> weekly, int weeks, Match match){

        /** Season totals for the men on this board, summed over its own weeks. */
        public Map<String, Double> seasonPoints(){
            Map<String, Double> total = new HashMap<>();
            for(Map<String, Double> week : weekly){
                week.forEach((id, points) -> total.merge(id, points, Double::sum));
            }
            return total;
        }
    }

    /** Clubs that moved. FFC says LAC for 2013; that season's files say SD. */
    static final Map<String, String> MOVED =
            Map.of("LAC", "SD", "LAR", "STL", "LV", "OAK", "JAX", "JAC");

    /**
     * Names, flattened until both sources survive the comparison.
     *
     * Builds on TightEndTiming.normalise - punctuation, and the Jr/Sr/II/III
     * suffixes Sleeper keeps and FFC drops - and adds the two failures that
     * showed up on 2010s boards: a hyphen only one source writes, and doubled
     * internal whitespace.
     */
    public static String normalise(String name){
        return TightEndTiming.normalise(name)
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    static String key(String name, Position position){
        return normalise(name) + "|" + position;
    }

    public static Board build(String season, String format){
        int weeks = EraActuals.weeks(season);

        // Four indexes over the same men, tried in order of how much they
        // assume. See match() for why each one exists.
        Map<String, List<JsonObject>> byNamePosition = new LinkedHashMap<>();
        Map<String, List<JsonObject>> byName = new LinkedHashMap<>();
        Map<String, List<JsonObject>> byLastPositionTeam = new LinkedHashMap<>();
        Map<String, List<JsonObject>> byLastTeam = new LinkedHashMap<>();
        for(JsonElement element : EraActuals.skillRows(season)){
            JsonObject row = element.getAsJsonObject();
            JsonObject player = row.getAsJsonObject("player");
            if(player == null || !row.has("player_id")){
                continue;
            }
            String position = text(player, "position");
            if(!Position.isStandardPosition(position) || position.equals("DEF")){
                continue;
            }
            String name = normalise(text(player, "first_name") + " "
                    + text(player, "last_name"));
            String last = normalise(text(player, "last_name"));
            String team = text(row, "team");
            byNamePosition.computeIfAbsent(name + "|" + position, k -> new ArrayList<>())
                    .add(row);
            byName.computeIfAbsent(name, k -> new ArrayList<>()).add(row);
            byLastPositionTeam.computeIfAbsent(last + "|" + position + "|" + team,
                    k -> new ArrayList<>()).add(row);
            byLastTeam.computeIfAbsent(last + "|" + team, k -> new ArrayList<>()).add(row);
        }

        Set<String> defenceIDs = EraActuals.defenceIDs(season);

        List<Row> rows = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        int boardRows = 0;
        int ambiguous = 0;
        int topHundred = 0;
        int topHundredMatched = 0;
        int exact = 0;
        int loosenedCount = 0;
        JsonObject json = JsonParser.parseString(adpJson(season, format)).getAsJsonObject();
        int drafts = json.getAsJsonObject("meta").get("total_drafts").getAsInt();
        for(JsonElement element : json.getAsJsonArray("players")){
            JsonObject entry = element.getAsJsonObject();
            String label = text(entry, "position");
            if(!Position.isStandardPosition(label)){
                continue;                       // kickers; this league starts none
            }
            Position position = Position.valueOf(label);
            String name = text(entry, "name");
            String team = text(entry, "team");
            double adp = entry.get("adp").getAsDouble();
            boardRows++;
            boolean top = adp <= 100;
            if(top){
                topHundred++;
            }
            String id;
            if(position == Position.DEF){
                id = defenceID(team, defenceIDs);
            }
            else {
                String exactKey = normalise(name) + "|" + position;
                id = skillID(byNamePosition.get(exactKey), team);
                if(id != null){
                    exact++;
                }
                else {
                    id = loosened(normalise(name), position, team, byName,
                            byLastPositionTeam, byLastTeam);
                    if(id != null){
                        loosenedCount++;
                    }
                    else if(byNamePosition.containsKey(exactKey)){
                        ambiguous++;
                    }
                }
            }
            if(id == null){
                missed.add(String.format("%.0f %s %s", adp, name, label));
                continue;
            }
            rows.add(new Row(id, name, position, adp));
            if(top){
                topHundredMatched++;
            }
        }
        rows.sort(Comparator.comparingDouble(Row::adp));

        List<String> ids = new ArrayList<>();
        Map<String, Position> positionOf = new HashMap<>();
        Map<String, Double> adp = new HashMap<>();
        int defences = 0;
        for(Row row : rows){
            if(positionOf.containsKey(row.id())){
                continue;
            }
            ids.add(row.id());
            positionOf.put(row.id(), row.position());
            adp.put(row.id(), row.adp());
            if(row.position() == Position.DEF){
                defences++;
            }
        }
        List<Map<String, Double>> weekly = new ArrayList<>();
        for(int week = 1; week <= weeks; week++){
            weekly.add(EraActuals.weeklyPoints(season, week));
        }
        Match match = new Match(season, format, boardRows, ids.size(), ambiguous,
                topHundred, topHundredMatched, ids.size() - defences, defences,
                missed, weeks, drafts, exact, loosenedCount);
        return new Board(season, format, ids, positionOf, adp, weekly, weeks, match);
    }

    /**
     * One man, or nobody.
     *
     * Two kinds of collision, and they need different answers.
     *
     * The first is two real players: Steve Smith and Steve Smith were both
     * starting receivers from 2007 to 2014. The team breaks that tie when it is
     * decisive, and when it is not this returns null and the player counts as
     * unmatched. A coin flip here would be a silent lie.
     *
     * The second is a real player against a MODERN STUB. Sleeper answers a 2010
     * season request with a row for every player in its present-day directory,
     * so Frank Gore Jr., who was born into the league in 2024, appears in 2010
     * beside Frank Gore with six placeholder stat keys and no games. That took
     * Frank Gore - a top-20 pick in four straight drafts - off four boards.
     * The discriminator is participation: exactly one of them played. That is
     * an IDENTITY question ("which of these rows is the man the board means"),
     * not a performance one, so it does not bias the outcomes - and a man who
     * genuinely played nothing stays matched unless he also collides, in which
     * case he is reported rather than guessed at.
     */
    static String skillID(List<JsonObject> candidates, String team){
        if(candidates == null || candidates.isEmpty()){
            return null;
        }
        if(candidates.size() == 1){
            return candidates.get(0).get("player_id").getAsString();
        }
        String byTeam = unique(candidates, candidate -> team.equals(text(candidate, "team")));
        if(byTeam != null){
            return byTeam;
        }
        return unique(candidates, EraBoards::played);
    }

    /**
     * The three ways the two feeds disagree about a man who is plainly the
     * same man, tried hardest-evidence first, each requiring a UNIQUE answer.
     *
     * Every one of these was found by reading the unmatched list rather than
     * guessed at in advance:
     *
     *   POSITION. Sleeper lists Devin Funchess and Jordan Matthews as tight
     *   ends; FFC's board sells them as receivers. Keying on name plus position
     *   dropped both from four boards between them. The board's label is the
     *   one kept, because the board is what the draft is being replayed
     *   against - a 2016 manager drafting Funchess was drafting a receiver.
     *
     *   FIRST NAME. Sleeper says William Fuller and Marquise Brown; FFC says
     *   Will Fuller and Hollywood Brown. Last name plus position plus club
     *   settles those without inviting a nickname table nobody will maintain.
     *
     *   BOTH AT ONCE. Last name plus club, the loosest rung, and the one most
     *   likely to be wrong - so it still refuses to answer unless exactly one
     *   man fits, which two receivers named Brown on one roster would not.
     */
    static String loosened(String name, Position position, String team,
                           Map<String, List<JsonObject>> byName,
                           Map<String, List<JsonObject>> byLastPositionTeam,
                           Map<String, List<JsonObject>> byLastTeam){
        List<JsonObject> sameName = byName.get(name);
        if(sameName != null && sameName.size() == 1){
            return sameName.get(0).get("player_id").getAsString();
        }
        String last = name.contains(" ") ? name.substring(name.lastIndexOf(' ') + 1) : name;
        List<JsonObject> sameClub = byLastPositionTeam.get(last + "|" + position + "|" + team);
        if(sameClub != null && sameClub.size() == 1){
            return sameClub.get(0).get("player_id").getAsString();
        }
        sameClub = byLastTeam.get(last + "|" + team);
        if(sameClub != null && sameClub.size() == 1){
            return sameClub.get(0).get("player_id").getAsString();
        }
        return null;
    }

    /** The one candidate the test picks out, or null if it picks none or several. */
    static String unique(List<JsonObject> candidates,
                         java.util.function.Predicate<JsonObject> test){
        String found = null;
        for(JsonObject candidate : candidates){
            if(test.test(candidate)){
                if(found != null){
                    return null;
                }
                found = candidate.get("player_id").getAsString();
            }
        }
        return found;
    }

    /** Did this row's man take the field that season at all? */
    static boolean played(JsonObject row){
        JsonObject stats = row.getAsJsonObject("stats");
        if(stats == null){
            return false;
        }
        JsonElement games = stats.get("gp");
        return games != null && !games.isJsonNull() && games.getAsDouble() > 0;
    }

    /** A defence's id in this season's weekly files, relocations undone. */
    static String defenceID(String team, Set<String> weeklyIDs){
        if(weeklyIDs.contains(team)){
            return team;
        }
        String moved = MOVED.get(team);
        if(moved != null && weeklyIDs.contains(moved)){
            return moved;
        }
        for(Map.Entry<String, String> entry : MOVED.entrySet()){
            if(entry.getValue().equals(team) && weeklyIDs.contains(entry.getKey())){
                return entry.getKey();
            }
        }
        return null;
    }

    public static String adpJson(String season, String format){
        String url = "https://fantasyfootballcalculator.com/api/v1/adp/" + format
                + "?teams=" + TEAMS + "&year=" + season + "&position=all";
        return InOutUtilities.getCachedForever(url,
                "ffcBoard" + format.replace("-", "") + season);
    }

    static String text(JsonObject object, String key){
        JsonElement element = object == null ? null : object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    /** Every season worth trying. Sleeper scores nothing before 2010. */
    public static final int FIRST_SEASON = 2010;
    public static final int LAST_SEASON = 2025;

    public static List<String> candidateSeasons(){
        List<String> seasons = new ArrayList<>();
        for(int year = FIRST_SEASON; year <= LAST_SEASON; year++){
            seasons.add(String.valueOf(year));
        }
        return seasons;
    }

    /**
     * The gates a season must pass to be allowed into a backtest.
     *
     * MATCH RATE, because a board with unmatched players deleted is a board
     * nobody drafted from. DEPTH, because a twelve-team draft of N rounds
     * removes 12N players and a board shorter than that runs dry - the
     * replaying draft then starts handing out whoever is left, which looks
     * like a strategy result and is an artifact.
     */
    public static Map<String, Board> usable(String format, double minRate, int minDepth){
        Map<String, Board> boards = new TreeMap<>();
        for(String season : candidateSeasons()){
            Board board = tryBuild(season, format);
            if(board != null && board.match().rate() >= minRate
                    && board.match().skill() >= minDepth){
                boards.put(season, board);
            }
        }
        return boards;
    }

    /** null rather than an exception when a feed simply has no such season. */
    public static Board tryBuild(String season, String format){
        try {
            return build(season, format == null ? defaultFormat(season) : format);
        }
        catch(RuntimeException unavailable){
            return null;
        }
    }
}
