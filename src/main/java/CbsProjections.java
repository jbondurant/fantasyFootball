import PlayerImportAndSetup.Position;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CBS Sports projections - server-rendered stat-line tables, one page per
 * position, scored under league settings by the shared scorer. Column layouts
 * are fixed per position and were verified against the live pages; the parse
 * sanity-checks itself (the top QB must project a starter's points per game)
 * so a silent CBS redesign fails loudly instead of feeding garbage.
 *
 * Two timeframes. "season" is the preseason board. Once games are played CBS
 * serves its CURRENT WEEK at that address - the page titled "Week 3 Proj" came
 * back for the season URL on 2026-09-11 and 09-25 - and the old guard threw
 * on it, which took the whole projection archive down with it for three weeks
 * (TRAPS #147). So the page's title is read and a week page refused by name,
 * and in season the archive reads "restofseason", which CBS still serves with
 * the same columns (gp is the games left).
 */
public class CbsProjections {

    static String url(String position, String season){
        return url(position, season, "season");
    }

    static String url(String position, String season, String timeframe){
        return "https://www.cbssports.com/fantasy/football/stats/" + position + "/" + season
                + "/" + timeframe + "/projections/ppr/";
    }

    private static final Pattern TITLE = Pattern.compile("<title>([^<]*)</title>");

    /**
     * Refuses a page that is not the timeframe asked for: "2026 Projections ..."
     * for the season, "Rest of Season Proj ..." for the rest of it. CBS answers
     * the season address with its week page in season, and a week's numbers
     * archived as a season's are wrong by a factor of fifteen.
     */
    static void checkServed(String html, String timeframe, String position){
        Matcher title = TITLE.matcher(html);
        String served = title.find() ? title.group(1).trim() : "(no title)";
        boolean right = timeframe.equals("restofseason") ? served.startsWith("Rest of Season")
                : served.matches("\\d{4} Projections.*");
        if(!right){
            throw new IllegalStateException("CBS served \"" + served + "\" for its " + timeframe + " " + position
                    + " page - in season the season address serves the current week; the rest of the season is cbs-ros");
        }
    }

    private static final Pattern ROW =
            Pattern.compile("<tr class=\"TableBase-bodyTr\\s*\">(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL =
            Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL);
    private static final Pattern LONG_NAME = Pattern.compile(
            "CellPlayerName--long[^>]*>(?:\\s*<[^>]*>)*\\s*([^<]+)", Pattern.DOTALL);

    public static HashMap<String, Double> leaguePointsBySleeperID(){
        return leaguePointsBySleeperID("season");
    }

    /** "season" or "restofseason", league-scored, by Sleeper id. */
    public static HashMap<String, Double> leaguePointsBySleeperID(String timeframe){
        String season = AAAConfiguration.getInstance().getSeason();
        LeagueScoringSettings scoring =
                SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        HashMap<String, Double> out = new HashMap<>();
        for(String position : new String[]{"QB", "RB", "WR", "TE"}){
            String html = InOutUtilities.getTodaysWebPage(url(position, season, timeframe),
                    (timeframe.equals("season") ? "cbsProjections_" : "cbsRosProjections_") + position + "_" + season);
            checkServed(html, timeframe, position);
            double bestPerGame = 0;
            Matcher rows = ROW.matcher(html);
            while(rows.find()){
                List<String> raw = new ArrayList<>();
                Matcher cells = CELL.matcher(rows.group(1));
                while(cells.find()){
                    raw.add(cells.group(1));
                }
                JsonObject stats = parseRow(position, raw);
                if(stats == null){
                    continue;
                }
                Matcher name = LONG_NAME.matcher(raw.get(0));
                if(!name.find()){
                    continue;
                }
                Player player = Player.getPlayerFromNameAndPos(name.group(1).trim(),
                        Position.valueOf(position));
                if(player == null){
                    continue;
                }
                double points = SleeperProjections.scoreStatLine(stats, scoring);
                out.put(player.sleeperIDString, points);
                double games = stats.get("gp").getAsDouble();
                if(games > 0){
                    bestPerGame = Math.max(bestPerGame, points / games);
                }
            }
            // per game, so the check holds for a season page and for the rest
            // of one in December alike: a starting QB scores 15+ a game here
            if(position.equals("QB") && bestPerGame < 15){
                throw new IllegalStateException("CBS QB parse looks broken - best QB scored "
                        + bestPerGame + " a game; the page layout has probably changed");
            }
        }
        return out;
    }

    /** Fixed per-position column layouts, verified 2026-08-25. */
    static JsonObject parseRow(String position, List<String> raw){
        List<Double> values = new ArrayList<>();
        for(int cell = 1; cell < raw.size(); cell++){
            try {
                values.add(Double.parseDouble(
                        raw.get(cell).replaceAll("<[^>]+>", "").trim().replace(",", "")));
            } catch (NumberFormatException notNumeric){
                return null;
            }
        }
        JsonObject stats = new JsonObject();
        try {
            stats.addProperty("gp", values.get(0));
            switch(position){
                // gp, att, cmp, passYds, y/g, passTD, INT, rating, rushAtt,
                // rushYds, avg, rushTD, FL, fpts, fppg
                case "QB" -> {
                    stats.addProperty("pass_yd", values.get(3));
                    stats.addProperty("pass_td", values.get(5));
                    stats.addProperty("pass_int", values.get(6));
                    stats.addProperty("rush_yd", values.get(9));
                    stats.addProperty("rush_td", values.get(11));
                    stats.addProperty("fum_lost", values.get(12));
                }
                // gp, rushAtt, rushYds, avg, rushTD, targets, rec, recYds,
                // y/g, avg, recTD, FL, fpts, fppg
                case "RB" -> {
                    stats.addProperty("rush_yd", values.get(2));
                    stats.addProperty("rush_td", values.get(4));
                    stats.addProperty("rec", values.get(6));
                    stats.addProperty("rec_yd", values.get(7));
                    stats.addProperty("rec_td", values.get(10));
                    stats.addProperty("fum_lost", values.get(11));
                }
                // gp, targets, rec, recYds, y/g, avg, recTD, rushAtt,
                // rushYds, avg, rushTD, FL, fpts, fppg
                case "WR" -> {
                    stats.addProperty("rec", values.get(2));
                    stats.addProperty("rec_yd", values.get(3));
                    stats.addProperty("rec_td", values.get(6));
                    stats.addProperty("rush_yd", values.get(8));
                    stats.addProperty("rush_td", values.get(10));
                    stats.addProperty("fum_lost", values.get(11));
                }
                // gp, targets, rec, recYds, y/g, avg, recTD, FL, fpts, fppg
                default -> {
                    stats.addProperty("rec", values.get(2));
                    stats.addProperty("rec_yd", values.get(3));
                    stats.addProperty("rec_td", values.get(6));
                    stats.addProperty("fum_lost", values.get(7));
                }
            }
        } catch (IndexOutOfBoundsException tooShort){
            return null;
        }
        return stats;
    }

    public static void main(String[] args){
        Map<String, Double> points = leaguePointsBySleeperID();
        System.out.printf("cbs: %d players scored under league settings%n", points.size());
        points.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(10)
                .forEach(entry -> {
                    Player player = Player.getPlayerFromSIDV2(entry.getKey());
                    System.out.printf("   %-24s %-3s %6.1f%n",
                            player.firstName + " " + player.lastName, player.position,
                            entry.getValue());
                });
    }

}
