import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Outcomes for seasons the repo has never looked at - 2010 through 2025 -
 * scored in this league's points.
 *
 * The scoring itself is {@link LeagueActuals#score}, deliberately not a second
 * implementation: the repo has been burned by parallel valuations before, and
 * a 6-point passing touchdown must mean the same thing everywhere. What this
 * class adds is everything that stops being true once you leave 2021:
 *
 *   WEEKS. The regular season ran 17 weeks through 2020 and 18 from 2021.
 *   WeeklyActuals.WEEKS is the constant 18. Asking Sleeper for week 18 of 2014
 *   returns an empty object, which reads downstream as "nobody scored" - a
 *   whole phantom week of zeros, silently, for every season before 2021.
 *
 *   TEAM ABBREVIATIONS. Sleeper's SEASON endpoint speaks modern abbreviations
 *   even for 2013 - the St. Louis Rams are LAR, the San Diego Chargers LAC -
 *   while its WEEKLY files speak the era's: STL, SD, OAK. So the set of team
 *   defences cannot be read off the season feed and used on the weekly one.
 *   Get this wrong and a 2013 defence is not merely mis-scored: an unrecognised
 *   SD row falls through to the SKILL scorer, and a defence row carries its
 *   club's whole offensive line, so San Diego's defence would be credited with
 *   Philip Rivers' passing yards. Here the defence ids come from the weekly
 *   files themselves, which cannot be wrong about their own season.
 *
 * Everything is cached forever under the keys the existing tools already use,
 * so the five seasons on disk are reused rather than refetched.
 */
public class EraActuals {

    /** Weeks in the regular season. 18 since 2021; 17 for every season before. */
    public static int weeks(String season){
        return Integer.parseInt(season) >= 2021 ? 18 : 17;
    }

    /** Skill-position season rows, each carrying an embedded player object. */
    public static JsonArray skillRows(String season){
        return JsonParser.parseString(HistoricalActuals.raw(season)).getAsJsonArray();
    }

    /**
     * One week's file, parsed and then let go.
     *
     * Deliberately NOT memoised. Sixteen seasons of weekly files is roughly
     * three hundred documents, and a parsed Gson tree is several times the size
     * of its text, so holding them all is hundreds of megabytes for data every
     * caller immediately reduces to a small map of id -> points. Parse, reduce,
     * discard; the callers keep the maps.
     */
    static JsonObject week(String season, int week){
        return JsonParser.parseString(WeeklyActuals.raw(season, week)).getAsJsonObject();
    }

    /**
     * A team defence, told apart from a man by its id.
     *
     * The weekly file keys men by number and clubs by abbreviation, and carries
     * a second TEAM_XXX row per club that aggregates its whole offence - 78
     * points in a week, which scored as a player would tower over every real
     * one. Both halves of this test earn their keep.
     */
    public static boolean isDefence(String id){
        return !id.matches("\\d+") && !id.startsWith("TEAM_");
    }

    /**
     * The defence ids this season's weekly files actually use, e.g. SD in 2013.
     *
     * Week one is enough - all thirty-two clubs play it, byes start later - so
     * this is normally one file. The loop is the guard: if a season's opening
     * week is short for any reason, it keeps reading until the full set turns
     * up rather than quietly returning a partial one.
     */
    public static Set<String> defenceIDs(String season){
        Set<String> ids = new HashSet<>();
        for(int week = 1; week <= weeks(season) && ids.size() < 32; week++){
            for(String id : week(season, week).keySet()){
                if(isDefence(id)){
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /**
     * sleeper id -> league points that week.
     *
     * A man with no pts_half_ppr entry is left OUT rather than given zero, the
     * same rule the existing weekly path uses: absent means he did not play and
     * cannot be started, while zero means he played and was useless. The lineup
     * filler reads the difference, so the two paths must agree on it.
     */
    public static Map<String, Double> weeklyPoints(String season, int week){
        LeagueScoringSettings scoring = LeagueActuals.leagueScoring();
        Map<String, Double> points = new HashMap<>();
        for(Map.Entry<String, JsonElement> entry : week(season, week).entrySet()){
            if(!entry.getValue().isJsonObject()){
                continue;
            }
            JsonObject stats = entry.getValue().getAsJsonObject();
            JsonElement half = stats.get("pts_half_ppr");
            if(half == null || half.isJsonNull()){
                continue;
            }
            points.put(entry.getKey(),
                    LeagueActuals.score(stats, isDefence(entry.getKey()), scoring));
        }
        return points;
    }

    /** The same week under Sleeper's own pts_half_ppr - the control column. */
    public static Map<String, Double> weeklyFeedPoints(String season, int week){
        Map<String, Double> points = new HashMap<>();
        for(Map.Entry<String, JsonElement> entry : week(season, week).entrySet()){
            if(!entry.getValue().isJsonObject()){
                continue;
            }
            JsonElement half = entry.getValue().getAsJsonObject().get("pts_half_ppr");
            if(half != null && !half.isJsonNull()){
                points.put(entry.getKey(), half.getAsDouble());
            }
        }
        return points;
    }

    /** Season totals summed from the weeks that were actually played. */
    public static Map<String, Double> seasonPoints(String season, boolean leagueScored){
        Map<String, Double> total = new HashMap<>();
        for(int week = 1; week <= weeks(season); week++){
            Map<String, Double> points = leagueScored ? weeklyPoints(season, week)
                    : weeklyFeedPoints(season, week);
            points.forEach((id, scored) -> total.merge(id, scored, Double::sum));
        }
        return total;
    }
}
