import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * THE GAME AROUND A PLAYER-WEEK: nflverse's schedule table, one row per NFL
 * game since 1999 - kickoff day and time, site, closing spread and total,
 * rest, division, roof, surface, temperature and wind, both starting
 * quarterbacks and both head coaches.
 *
 * The file is data/nflverse/games.csv (nflverse/nfldata, 2.2 MB), fetched on
 * 2026-09-25 with Justin's yes and gitignored like the other nflverse files.
 * Nothing here downloads.
 *
 * Every game is read from each team's side, keyed season|week|team in
 * SLEEPER's team codes - nflverse calls the Rams LA where Sleeper says LAR,
 * and nothing else differs from 2018 on - so a player-week joins on the team
 * his own stat row carries that week.
 *
 * Checked on the file itself before anything was built on it: spread_line is
 * from the HOME side and positive when the home team is favoured (it
 * correlates +0.45 with the home margin over 2,127 games 2018-2025), and
 * temperature and wind are recorded for outdoor games only, with gaps - half
 * of 2022's outdoor games carry none. A missing reading is null here, never
 * zero: calm and unknown are different answers.
 */
public class NflverseGames {

    static final Path FILE = NflverseWeekly.DIRECTORY.resolve("games.csv");

    /** One game from one team's side. Numbers the file does not carry are null. */
    record Side(String gameId, String season, int week, String team, String opponent, boolean home, boolean neutral,
                String gameday, String weekday, String gametime, Double spread, Double total,
                Integer rest, Integer opponentRest, boolean divisional, String roof, String surface,
                Double temp, Double wind, String qb, String coach, String opponentCoach, String stadium,
                String homeTeam, Double margin) {

        /** Points the closing line expected this team to score: (total + its own spread) / 2. */
        Double impliedTotal(){
            return spread == null || total == null ? null : (total + spread) / 2;
        }

        /** Roofed: a dome, or a retractable roof closed for the game. */
        boolean roofed(){
            return "dome".equals(roof) || "closed".equals(roof);
        }
    }

    static String sleeperTeam(String nflverse){
        return "LA".equals(nflverse) ? "LAR" : nflverse;
    }

    static String key(String season, int week, String team){
        return season + "|" + week + "|" + team;
    }

    /** Every regular-season game of the file from both sides, keyed {@link #key}. */
    static Map<String, Side> sides(List<String> lines){
        Map<String, Integer> column = new HashMap<>();
        List<String> header = NflverseWeekly.split(lines.get(0));
        for(int i = 0; i < header.size(); i++){
            column.put(header.get(i), i);
        }
        Map<String, Side> out = new HashMap<>();
        for(String line : lines.subList(1, lines.size())){
            if(line.isBlank()){
                continue;
            }
            List<String> cells = NflverseWeekly.split(line);
            if(!"REG".equals(text(cells, column, "game_type"))){
                continue;
            }
            String season = text(cells, column, "season");
            int week = Integer.parseInt(text(cells, column, "week"));
            String home = sleeperTeam(text(cells, column, "home_team"));
            String away = sleeperTeam(text(cells, column, "away_team"));
            boolean neutral = "Neutral".equals(text(cells, column, "location"));
            Double spread = number(cells, column, "spread_line");
            Double total = number(cells, column, "total_line");
            Integer homeRest = integer(cells, column, "home_rest");
            Integer awayRest = integer(cells, column, "away_rest");
            Double result = number(cells, column, "result");
            String common = text(cells, column, "game_id");
            String roof = text(cells, column, "roof");
            String surface = text(cells, column, "surface");
            Double temp = number(cells, column, "temp");
            Double wind = number(cells, column, "wind");
            boolean divisional = "1".equals(text(cells, column, "div_game"));
            String stadium = text(cells, column, "stadium_id");
            String gameday = text(cells, column, "gameday");
            String weekday = text(cells, column, "weekday");
            String gametime = text(cells, column, "gametime");
            out.put(key(season, week, home), new Side(common, season, week, home, away, true, neutral, gameday, weekday,
                    gametime, spread, total, homeRest, awayRest, divisional, roof, surface, temp, wind,
                    text(cells, column, "home_qb_id"), text(cells, column, "home_coach"),
                    text(cells, column, "away_coach"), stadium, home, result));
            out.put(key(season, week, away), new Side(common, season, week, away, home, false, neutral, gameday, weekday,
                    gametime, spread == null ? null : -spread, total, awayRest, homeRest, divisional, roof, surface, temp,
                    wind, text(cells, column, "away_qb_id"), text(cells, column, "away_coach"),
                    text(cells, column, "home_coach"), stadium, home, result == null ? null : -result));
        }
        return out;
    }

    /**
     * One whole game with its prices, for the market tools (MarketEfficiency):
     * the spread and total and the odds actually offered on each side, and the
     * moneylines. American odds as integers; null where the file has none
     * (prices start in 2006 and are complete from 2010). Teams in Sleeper's
     * codes. Scores are null for a game not yet played.
     */
    record Game(String gameId, String season, int week, String gameday, String gametime, String home, String away,
                Integer homeScore, Integer awayScore, boolean neutral, boolean divisional, Double spread, Double total,
                Integer homeMoneyline, Integer awayMoneyline, Integer homeSpreadOdds, Integer awaySpreadOdds,
                Integer overOdds, Integer underOdds, String roof, Double wind, Double temp, Integer homeRest, Integer awayRest) {

        boolean played(){
            return homeScore != null && awayScore != null;
        }

        /** Home points minus away points. */
        int margin(){
            return homeScore - awayScore;
        }

        int points(){
            return homeScore + awayScore;
        }
    }

    /** Every regular-season game of the file, in file order (chronological). */
    static List<Game> games(List<String> lines){
        Map<String, Integer> column = new HashMap<>();
        List<String> header = NflverseWeekly.split(lines.get(0));
        for(int i = 0; i < header.size(); i++){
            column.put(header.get(i), i);
        }
        List<Game> out = new java.util.ArrayList<>();
        for(String line : lines.subList(1, lines.size())){
            if(line.isBlank()){
                continue;
            }
            List<String> cells = NflverseWeekly.split(line);
            if(!"REG".equals(text(cells, column, "game_type"))){
                continue;
            }
            out.add(new Game(text(cells, column, "game_id"), text(cells, column, "season"),
                    Integer.parseInt(text(cells, column, "week")), text(cells, column, "gameday"), text(cells, column, "gametime"),
                    sleeperTeam(text(cells, column, "home_team")), sleeperTeam(text(cells, column, "away_team")),
                    integer(cells, column, "home_score"), integer(cells, column, "away_score"),
                    "Neutral".equals(text(cells, column, "location")), "1".equals(text(cells, column, "div_game")),
                    number(cells, column, "spread_line"), number(cells, column, "total_line"),
                    integer(cells, column, "home_moneyline"), integer(cells, column, "away_moneyline"),
                    integer(cells, column, "home_spread_odds"), integer(cells, column, "away_spread_odds"),
                    integer(cells, column, "over_odds"), integer(cells, column, "under_odds"),
                    text(cells, column, "roof"), number(cells, column, "wind"), number(cells, column, "temp"),
                    integer(cells, column, "home_rest"), integer(cells, column, "away_rest")));
        }
        return out;
    }

    /**
     * Sleeper's club codes whose game of a week has kicked off by `nowEastern`
     * (nflverse's gameday and gametime are Eastern). A game with no time is
     * taken to start at 13:00. A season the file does not schedule gives an
     * empty set - nobody has kicked off - which is the old behaviour.
     */
    static java.util.Set<String> kickedOff(List<Game> games, String season, int week, java.time.LocalDateTime nowEastern){
        java.util.Set<String> started = new java.util.HashSet<>();
        for(Game g : games){
            if(!g.season().equals(season) || g.week() != week || g.gameday() == null || g.gameday().isBlank()){
                continue;
            }
            java.time.LocalDateTime kickoff = java.time.LocalDateTime.parse(g.gameday() + "T"
                    + (g.gametime() == null || g.gametime().isBlank() ? "13:00" : g.gametime()));
            if(!nowEastern.isBefore(kickoff)){
                started.add(sleeperTeam(g.home()));
                started.add(sleeperTeam(g.away()));
            }
        }
        return started;
    }

    private static List<Game> cachedGames;

    static synchronized List<Game> games(){
        if(cachedGames == null){
            try {
                cachedGames = games(Files.readAllLines(FILE, StandardCharsets.UTF_8));
            }
            catch(IOException missing){
                throw new IllegalStateException(FILE + " is not on disk: it is nflverse/nfldata's games.csv, placed by hand", missing);
            }
        }
        return cachedGames;
    }

    private static Map<String, Side> cached;

    /** The file on disk, read once. */
    static synchronized Map<String, Side> sides(){
        if(cached == null){
            try {
                cached = sides(Files.readAllLines(FILE, StandardCharsets.UTF_8));
            }
            catch(IOException missing){
                throw new IllegalStateException(FILE + " is not on disk: it is nflverse/nfldata's games.csv, placed by hand", missing);
            }
        }
        return cached;
    }

    static String text(List<String> cells, Map<String, Integer> column, String key){
        Integer i = column.get(key);
        if(i == null || i >= cells.size()){
            return null;
        }
        String value = cells.get(i).trim();
        return value.isEmpty() || value.equals("NA") ? null : value;
    }

    static Double number(List<String> cells, Map<String, Integer> column, String key){
        String value = text(cells, column, key);
        if(value == null){
            return null;
        }
        try {
            return Double.parseDouble(value);
        }
        catch(NumberFormatException notANumber){
            return null;
        }
    }

    static Integer integer(List<String> cells, Map<String, Integer> column, String key){
        Double value = number(cells, column, key);
        return value == null ? null : (int) Math.round(value);
    }
}
