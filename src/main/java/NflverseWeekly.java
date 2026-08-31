import PlayerImportAndSetup.Position;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Weekly player stats from nflverse, 2010-2025, scored under THIS league's
 * rules.
 *
 * Sleeper is the repo's usual outcome feed and it has one hole that caps the
 * whole harvest: it carries no stat rows at all for men who left the league
 * before Sleeper existed. Randy Moss, Michael Turner and Rashard Mendenhall are
 * missing even from 2013. Since the men who vanish are disproportionately the
 * men who BUSTED, a sample that quietly drops them is biased in exactly the
 * direction a bust study cares about. The nflverse files have them: roughly
 * 1,800 players and 17,500 rows a season, sixteen seasons, about 280,000
 * player-weeks.
 *
 * THREE RULES, all of them load-bearing.
 *
 *   SCORE FROM COMPONENTS. The files carry `fantasy_points` and
 *   `fantasy_points_ppr` and both are ignored. They are somebody else's
 *   scoring - four points a passing touchdown - and this repo has already been
 *   burned once by choosing a plan on 6-point quarterbacks and grading it on
 *   4-point ones. Points here come from passing/rushing/receiving components
 *   through the league's own {@link LeagueScoringSettings}, the same settings
 *   object {@link LeagueActuals} uses, so the two paths cannot drift apart.
 *
 *   REGULAR SEASON ONLY. The files carry POST rows too - Mendenhall shows 19
 *   rows in a 17-week season - and a playoff week folded into a season rate
 *   would inflate exactly the players who were good enough to reach January.
 *
 *   FUMBLES ARE TWO CHARGES, NOT ONE. This league pays -1 for every fumble AND
 *   -1 for a lost one, so a lost fumble costs two. nflverse splits both across
 *   three columns each (sack, rushing, receiving); all six are summed, in two
 *   groups, exactly as {@link LeagueActuals#scoreSkill} reads its `fum` and
 *   `fum_lost`.
 *
 * The files are on disk under data/nflverse and gitignored. Nothing here
 * downloads.
 */
public class NflverseWeekly {

    public static final Path DIRECTORY = Path.of("data", "nflverse");
    public static final int FIRST_SEASON = 2010;
    public static final int LAST_SEASON = 2025;

    public static Path file(String season){
        return DIRECTORY.resolve("stats_player_week_" + season + ".csv");
    }

    public static boolean available(String season){
        return Files.isReadable(file(season));
    }

    /** One man's identity in this feed, plus what he did in one week. */
    public record Row(String id, String name, Position position, int week,
                      String team, double points){}

    /**
     * Every regular-season skill row of one season, league-scored.
     *
     * Parsed and handed back as a flat list rather than memoised: a season is
     * about 17,500 rows of 200 columns, and every caller immediately reduces it
     * to a small map. Parse, reduce, discard - the same policy
     * {@link EraActuals#week} settles on for the Sleeper files.
     */
    public static List<Row> rows(String season){
        Path path = file(season);
        if(!Files.isReadable(path)){
            throw new IllegalStateException("no nflverse file for " + season
                    + " at " + path.toAbsolutePath());
        }
        LeagueScoringSettings scoring = LeagueActuals.leagueScoring();
        List<Row> rows = new ArrayList<>();
        try(BufferedReader reader = Files.newBufferedReader(path,
                StandardCharsets.UTF_8)){
            String header = reader.readLine();
            if(header == null){
                return rows;
            }
            Map<String, Integer> column = new HashMap<>();
            List<String> names = split(header);
            for(int i = 0; i < names.size(); i++){
                column.put(names.get(i), i);
            }
            String line;
            while((line = reader.readLine()) != null){
                List<String> cells = split(line);
                if(cells.size() < names.size()){
                    continue;
                }
                if(!"REG".equals(cell(cells, column, "season_type"))){
                    continue;               // playoffs are not the regular season
                }
                String label = cell(cells, column, "position");
                if(!Position.isStandardPosition(label)){
                    continue;               // linemen, kickers, the whole defence
                }
                Position position = Position.valueOf(label);
                if(!StartingLineup.isSkillPosition(position)){
                    continue;
                }
                int week = (int) number(cells, column, "week");
                if(week < 1){
                    continue;
                }
                rows.add(new Row(cell(cells, column, "player_id"),
                        displayName(cells, column), position, week,
                        cell(cells, column, "team"),
                        score(cells, column, scoring)));
            }
        }
        catch(IOException broken){
            throw new UncheckedIOException(broken);
        }
        return rows;
    }

    /**
     * One stat line under the league's settings.
     *
     * Deliberately parallel to {@link LeagueActuals#scoreSkill} rather than
     * calling it: that one reads a Sleeper JSON object with Sleeper's key
     * names, and bending a CSV row into that shape to reuse ten lines of
     * arithmetic would hide the mapping. The mapping is the part worth being
     * able to read, so it is written out.
     */
    static double score(List<String> cells, Map<String, Integer> column,
                        LeagueScoringSettings lss){
        double passing = number(cells, column, "passing_yards") * lss.passYard
                + number(cells, column, "passing_tds") * lss.passTD
                + number(cells, column, "passing_interceptions") * lss.interception
                + number(cells, column, "passing_2pt_conversions") * lss.passTwoPoint;
        double rushing = number(cells, column, "rushing_yards") * lss.rushYard
                + number(cells, column, "rushing_tds") * lss.rushTD
                + number(cells, column, "rushing_2pt_conversions") * lss.rushTwoPoint;
        double receiving = number(cells, column, "receptions") * lss.reception
                + number(cells, column, "receiving_yards") * lss.receivingYard
                + number(cells, column, "receiving_tds") * lss.receivingTD
                + number(cells, column, "receiving_2pt_conversions")
                        * lss.receivingTwoPoint;
        // Both charges stack: a lost fumble is also a fumble.
        double lost = number(cells, column, "sack_fumbles_lost")
                + number(cells, column, "rushing_fumbles_lost")
                + number(cells, column, "receiving_fumbles_lost");
        double all = number(cells, column, "sack_fumbles")
                + number(cells, column, "rushing_fumbles")
                + number(cells, column, "receiving_fumbles");
        double loose = number(cells, column, "special_teams_tds") * lss.specialTeamsTD;
        return passing + rushing + receiving + lost * lss.fumbleLost
                + all * lss.fumble + loose;
    }

    static String displayName(List<String> cells, Map<String, Integer> column){
        String display = cell(cells, column, "player_display_name");
        return display.isBlank() ? cell(cells, column, "player_name") : display;
    }

    static String cell(List<String> cells, Map<String, Integer> column, String key){
        Integer index = column.get(key);
        return index == null || index >= cells.size() ? "" : cells.get(index);
    }

    static double number(List<String> cells, Map<String, Integer> column, String key){
        String text = cell(cells, column, key);
        if(text.isBlank() || "NA".equals(text)){
            return 0;
        }
        try {
            return Double.parseDouble(text);
        }
        catch(NumberFormatException notANumber){
            return 0;
        }
    }

    /**
     * One CSV line into cells, honouring quoted fields.
     *
     * A naive split on commas is wrong here: game_id and some display names
     * carry them inside quotes, and a shifted row would silently read
     * receiving yards out of the rushing column. Doubled quotes inside a quoted
     * field are the escape, per RFC 4180.
     */
    static List<String> split(String line){
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for(int i = 0; i < line.length(); i++){
            char c = line.charAt(i);
            if(quoted){
                if(c == '"'){
                    if(i + 1 < line.length() && line.charAt(i + 1) == '"'){
                        cell.append('"');
                        i++;
                    }
                    else {
                        quoted = false;
                    }
                }
                else {
                    cell.append(c);
                }
            }
            else if(c == '"'){
                quoted = true;
            }
            else if(c == ','){
                cells.add(cell.toString());
                cell.setLength(0);
            }
            else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    // ------------------------------------------------------------------

    /** week -> (nflverse player id -> league points). Absent means he did not play. */
    public static Map<Integer, Map<String, Double>> weeklyPoints(String season){
        Map<Integer, Map<String, Double>> weeks = new HashMap<>();
        for(Row row : rows(season)){
            weeks.computeIfAbsent(row.week(), w -> new HashMap<>())
                    .merge(row.id(), row.points(), Double::sum);
        }
        return weeks;
    }

    public static void main(String[] args){
        System.out.printf("%nNFLVERSE WEEKLY, LEAGUE-SCORED%n%n");
        System.out.printf("%-8s %9s %9s %8s %9s %28s%n", "SEASON", "rows",
                "players", "weeks", "top score", "the top man");
        for(int year = FIRST_SEASON; year <= LAST_SEASON; year++){
            String season = String.valueOf(year);
            if(!available(season)){
                System.out.printf("%-8s %9s%n", season, "missing");
                continue;
            }
            List<Row> rows = rows(season);
            Map<String, Double> totals = new HashMap<>();
            Map<String, String> names = new HashMap<>();
            int maxWeek = 0;
            for(Row row : rows){
                totals.merge(row.id(), row.points(), Double::sum);
                names.put(row.id(), row.name() + " " + row.position());
                maxWeek = Math.max(maxWeek, row.week());
            }
            String best = totals.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                    .orElse("");
            System.out.printf("%-8s %9d %9d %8d %9.1f %28s%n", season, rows.size(),
                    totals.size(), maxWeek, totals.getOrDefault(best, 0.0),
                    names.getOrDefault(best, ""));
        }
        System.out.println("\nScored from raw components under this league's"
                + " settings - 6 a passing\ntouchdown, -1 a fumble, half a point"
                + " a reception. The files' own\nfantasy_points columns are not"
                + " read.");
    }
}
