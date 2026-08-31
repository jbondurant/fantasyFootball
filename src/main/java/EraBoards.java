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

    /** FFC publishes half-PPR boards from 2018; before that PPR is the closest. */
    public static String defaultFormat(String season){
        return Integer.parseInt(season) >= 2018 ? "half-ppr" : "ppr";
    }

    public record Row(String id, String name, Position position, double adp){}

    /** How much of one season's board reached its outcomes, and what did not. */
    public record Match(String season, String format, int boardRows, int matched,
                        int ambiguous, int topHundred, int topHundredMatched,
                        int skill, int defences, List<String> missedByAdp, int weeks,
                        int drafts){
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

        // name+position -> the men who wore it, from every row in the season
        Map<String, List<JsonObject>> byName = new LinkedHashMap<>();
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
            String name = (text(player, "first_name") + " " + text(player, "last_name"));
            byName.computeIfAbsent(key(name, Position.valueOf(position)),
                    k -> new ArrayList<>()).add(row);
        }

        Set<String> defenceIDs = EraActuals.defenceIDs(season);

        List<Row> rows = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        int boardRows = 0;
        int ambiguous = 0;
        int topHundred = 0;
        int topHundredMatched = 0;
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
                id = skillID(byName.get(key(name, position)), team);
                if(id == null && byName.containsKey(key(name, position))){
                    ambiguous++;
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
                missed, weeks, drafts);
        return new Board(season, format, ids, positionOf, adp, weekly, weeks, match);
    }

    /**
     * One man, or nobody.
     *
     * Two men have shared a name, a position and a season - Steve Smith and
     * Steve Smith were both starting receivers from 2007 to 2014 - so the team
     * breaks the tie when it is decisive. When it is not, this returns null and
     * the player is counted as unmatched. A coin flip here would be a silent
     * lie, and silent lies are the whole thing this join is guarding against.
     */
    static String skillID(List<JsonObject> candidates, String team){
        if(candidates == null || candidates.isEmpty()){
            return null;
        }
        if(candidates.size() == 1){
            return candidates.get(0).get("player_id").getAsString();
        }
        String found = null;
        for(JsonObject candidate : candidates){
            if(team.equals(text(candidate, "team"))){
                if(found != null){
                    return null;                // two of them on the same club
                }
                found = candidate.get("player_id").getAsString();
            }
        }
        return found;
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
