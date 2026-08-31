import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Fantasy Football Calculator's draft boards joined to nflverse outcomes,
 * 2010-2025 - the widest paired sample this repo can build.
 *
 * The Sleeper-based {@link EraBoards} caps out at thirteen seasons because
 * Sleeper has no rows for men who retired before it existed, and those men are
 * disproportionately the busts. This is the same construction against
 * {@link NflverseWeekly}, which has them.
 *
 * THE BINDING CONSTRAINT IS NOW THE BOARD, NOT THE OUTCOMES. nflverse reaches
 * back to 1999; FFC returns nothing before 2010, probed and confirmed empty for
 * 2007, 2008 and 2009. So sixteen seasons is the ceiling however far the stats
 * go, and it is set by the draft side.
 *
 * THE JOIN IS BY NAME AND IT IS WHERE THIS QUIETLY FAILS. A season that matched
 * 70% of its board would still produce a perfectly plausible number, computed
 * on a board with a third of the players deleted - which is not a board anybody
 * drafted from, and nothing in the output would say so. So {@link Season}
 * carries its own match rate, {@link #main} prints it with the names that got
 * away, and {@link #usable} REFUSES a season below the gate rather than
 * quietly including it.
 *
 * One trap from the Sleeper harvest is gone here and one remains. Gone: the
 * modern stub row, where Frank Gore Jr. shadowed Frank Gore in four seasons,
 * because nflverse writes a row only for a man who actually played that week.
 * Remaining: the two feeds disagree about POSITION - FFC sells Devin Funchess
 * and Jordan Matthews as receivers, the stat feeds call them tight ends - so
 * the same loosening ladder {@link EraBoards#loosened} uses is applied, each
 * rung requiring a unique answer and refusing to guess otherwise.
 *
 *   ./gradlew run -Pmain=NflverseBoards
 */
public class NflverseBoards {

    /** Same gates as the Sleeper harvest, so the two are comparable. */
    public static final double MIN_RATE = 0.90;
    public static final double MIN_TOP_RATE = 0.95;

    public record Season(String season, String format, int weeks, int boardRows,
                         int matched, int topHundred, int topHundredMatched,
                         int exact, int loosened, List<String> missed,
                         List<DetectionLag.Man> men){
        public double rate(){
            return boardRows == 0 ? 0 : (double) matched / boardRows;
        }
        public double topRate(){
            return topHundred == 0 ? 0 : (double) topHundredMatched / topHundred;
        }
        public boolean usable(){
            return rate() >= MIN_RATE && topRate() >= MIN_TOP_RATE;
        }
    }

    /** One man's season in this feed, before the board is consulted. */
    record Career(String id, String name, Position position, String team,
                  double[] weekly){}

    public static Season build(String season, String format){
        int weeks = EraActuals.weeks(season);
        Map<String, Career> careers = careers(season, weeks);

        Map<String, List<Career>> byNamePosition = new LinkedHashMap<>();
        Map<String, List<Career>> byName = new LinkedHashMap<>();
        Map<String, List<Career>> byLastPositionTeam = new LinkedHashMap<>();
        Map<String, List<Career>> byLastTeam = new LinkedHashMap<>();
        for(Career career : careers.values()){
            String name = EraBoards.normalise(career.name());
            String last = name.contains(" ")
                    ? name.substring(name.lastIndexOf(' ') + 1) : name;
            byNamePosition.computeIfAbsent(name + "|" + career.position(),
                    k -> new ArrayList<>()).add(career);
            byName.computeIfAbsent(name, k -> new ArrayList<>()).add(career);
            byLastPositionTeam.computeIfAbsent(
                    last + "|" + career.position() + "|" + career.team(),
                    k -> new ArrayList<>()).add(career);
            byLastTeam.computeIfAbsent(last + "|" + career.team(),
                    k -> new ArrayList<>()).add(career);
        }

        List<DetectionLag.Man> men = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        Map<Position, Integer> rankCounter = new EnumMap<>(Position.class);
        int boardRows = 0, matched = 0, topHundred = 0, topHundredMatched = 0;
        int exact = 0, loosened = 0;

        JsonObject json = JsonParser.parseString(EraBoards.adpJson(season, format))
                .getAsJsonObject();
        List<JsonObject> entries = new ArrayList<>();
        for(JsonElement element : json.getAsJsonArray("players")){
            entries.add(element.getAsJsonObject());
        }
        entries.sort(Comparator.comparingDouble(e -> e.get("adp").getAsDouble()));

        for(JsonObject entry : entries){
            String label = text(entry, "position");
            if(!Position.isStandardPosition(label)){
                continue;                       // kickers
            }
            Position position = Position.valueOf(label);
            if(!StartingLineup.isSkillPosition(position)){
                continue;                       // defences are excluded on purpose
            }
            String name = EraBoards.normalise(text(entry, "name"));
            String team = text(entry, "team");
            double adp = entry.get("adp").getAsDouble();
            boardRows++;
            boolean top = adp <= 100;
            if(top){
                topHundred++;
            }

            Career found = unique(byNamePosition.get(name + "|" + position), team);
            if(found != null){
                exact++;
            }
            else {
                found = loosen(name, position, team, byName, byLastPositionTeam,
                        byLastTeam);
                if(found != null){
                    loosened++;
                }
            }
            if(found == null){
                missed.add(String.format("%.0f %s %s", adp, text(entry, "name"), label));
                continue;
            }
            matched++;
            if(top){
                topHundredMatched++;
            }
            int rank = rankCounter.merge(position, 1, Integer::sum);
            men.add(new DetectionLag.Man(season, found.id(), position, adp, rank,
                    found.weekly()));
        }
        return new Season(season, format, weeks, boardRows, matched, topHundred,
                topHundredMatched, exact, loosened, missed, men);
    }

    /**
     * Every man's season as a week-indexed array, NaN where he did not play.
     *
     * NaN rather than zero, and the distinction is the one the whole detection
     * study turns on: a man who did not play cannot be started, while a man who
     * played and scored nothing is available-and-bad. Collapsing them would
     * hand the injury channel's work to the form channel.
     *
     * The team recorded is the one he played the most weeks for, because the
     * loosest join rung keys on club and a man traded in November should still
     * be findable under the club the August board sold him at.
     */
    static Map<String, Career> careers(String season, int weeks){
        Map<String, double[]> weekly = new HashMap<>();
        Map<String, Map<String, Integer>> teamWeeks = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        Map<String, Position> positions = new HashMap<>();
        for(NflverseWeekly.Row row : NflverseWeekly.rows(season)){
            if(row.week() > weeks){
                continue;
            }
            double[] points = weekly.computeIfAbsent(row.id(), id -> {
                double[] blank = new double[weeks];
                java.util.Arrays.fill(blank, Double.NaN);
                return blank;
            });
            int index = row.week() - 1;
            points[index] = Double.isNaN(points[index]) ? row.points()
                    : points[index] + row.points();
            names.put(row.id(), row.name());
            positions.put(row.id(), row.position());
            teamWeeks.computeIfAbsent(row.id(), id -> new HashMap<>())
                    .merge(row.team(), 1, Integer::sum);
        }
        Map<String, Career> careers = new HashMap<>();
        weekly.forEach((id, points) -> {
            String team = teamWeeks.get(id).entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
            careers.put(id, new Career(id, names.get(id), positions.get(id), team,
                    points));
        });
        return careers;
    }

    /** The one candidate the club picks out, or the only one there was. */
    static Career unique(List<Career> candidates, String team){
        if(candidates == null || candidates.isEmpty()){
            return null;
        }
        if(candidates.size() == 1){
            return candidates.get(0);
        }
        Career found = null;
        for(Career candidate : candidates){
            if(team.equals(candidate.team()) || EraBoards.MOVED.getOrDefault(team, "")
                    .equals(candidate.team())){
                if(found != null){
                    return null;            // two men fit; a coin flip would be a lie
                }
                found = candidate;
            }
        }
        return found;
    }

    /** Name, then last-name-plus-club, then club alone - each needing one answer. */
    static Career loosen(String name, Position position, String team,
                         Map<String, List<Career>> byName,
                         Map<String, List<Career>> byLastPositionTeam,
                         Map<String, List<Career>> byLastTeam){
        List<Career> sameName = byName.get(name);
        if(sameName != null && sameName.size() == 1){
            return sameName.get(0);
        }
        String last = name.contains(" ")
                ? name.substring(name.lastIndexOf(' ') + 1) : name;
        Career byClub = unique(byLastPositionTeam.get(last + "|" + position + "|" + team),
                team);
        if(byClub != null){
            return byClub;
        }
        List<Career> sameClub = byLastTeam.get(last + "|" + team);
        return sameClub != null && sameClub.size() == 1 ? sameClub.get(0) : null;
    }

    static String text(JsonObject object, String key){
        JsonElement element = object == null ? null : object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    // ------------------------------------------------------------------

    public static Map<String, Season> all(String format){
        Map<String, Season> seasons = new TreeMap<>();
        for(int year = NflverseWeekly.FIRST_SEASON;
                year <= NflverseWeekly.LAST_SEASON; year++){
            String season = String.valueOf(year);
            if(!NflverseWeekly.available(season)){
                continue;
            }
            try {
                seasons.put(season, build(season, format == null ? "ppr" : format));
            }
            catch(RuntimeException unavailable){
                // a season with no board is not a season with a bad board
            }
        }
        return seasons;
    }

    /** The same shape {@link DetectionLag#load} returns, gated on match rate. */
    public static Map<String, List<DetectionLag.Man>> usable(String format){
        Map<String, List<DetectionLag.Man>> men = new TreeMap<>();
        all(format).forEach((season, built) -> {
            if(built.usable()){
                men.put(season, built.men());
            }
        });
        return men;
    }

    public static void main(String[] args){
        String format = System.getProperty("format");
        Map<String, Season> seasons = all(format);
        System.out.printf("%nFFC BOARDS JOINED TO NFLVERSE OUTCOMES%n%n");
        System.out.printf("%-8s %7s %8s %8s %9s %8s %9s %8s%n", "SEASON", "format",
                "board", "matched", "rate", "top-100", "loosened", "usable");
        for(Season season : seasons.values()){
            System.out.printf("%-8s %7s %8d %8d %8.1f%% %7.1f%% %9d %8s%n",
                    season.season(), season.format(), season.boardRows(),
                    season.matched(), season.rate() * 100, season.topRate() * 100,
                    season.loosened(), season.usable() ? "yes" : "NO");
        }
        long usable = seasons.values().stream().filter(Season::usable).count();
        System.out.printf("%nusable seasons   %d of %d (gates: match >= %.0f%%,"
                + " top-100 >= %.0f%%)%n", usable, seasons.size(), MIN_RATE * 100,
                MIN_TOP_RATE * 100);

        System.out.printf("%nWHO GOT AWAY, worst season first%n");
        seasons.values().stream()
                .sorted(Comparator.comparingDouble(Season::rate))
                .limit(3)
                .forEach(season -> System.out.printf("   %s (%.1f%%): %s%n",
                        season.season(), season.rate() * 100,
                        String.join(", ", season.missed().subList(0,
                                Math.min(12, season.missed().size())))));
        System.out.println();
    }
}
