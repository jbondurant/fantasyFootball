import PlayerImportAndSetup.Position;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FantasyPros draft projections, parsed from their public pages. NOT a
 * production source - Justin does not particularly trust these numbers - but
 * the one fetchable site that publishes both a points total and the full
 * stat line, which makes it the validation instrument for ProjectionBridge:
 * score the stat line under league settings directly, bridge the site's own
 * points total with Sleeper TD counts, and the residual measures how
 * accurate the bridge trick is for any points-only source.
 */
public class FantasyProsProjections {

    public record FpRow(Player player, JsonObject stats, double sitePoints){}

    static String url(String position){
        return "https://www.fantasypros.com/nfl/projections/" + position + ".php?week=draft";
    }

    private static final Pattern CELL = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL);

    public static List<FpRow> rows(){
        List<FpRow> rows = new ArrayList<>();
        String season = AAAConfiguration.getInstance().getSeason();
        for(String position : new String[]{"qb", "rb", "wr", "te"}){
            String html = InOutUtilities.getTodaysWebPage(url(position),
                    "fantasyProsProjections_" + position + "_" + season);
            for(String chunk : html.split("<tr class=\"mpb-player-")){
                int end = chunk.indexOf("</tr>");
                if(end < 0 || !chunk.contains("</td>")){
                    continue;
                }
                List<String> cells = new ArrayList<>();
                Matcher matcher = CELL.matcher(chunk.substring(0, end));
                while(matcher.find()){
                    cells.add(matcher.group(1).replaceAll("<[^>]+>", " ").trim());
                }
                FpRow row = parse(position, cells);
                if(row != null){
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /** One table row to a Sleeper-keyed stat line; null when unusable. */
    static FpRow parse(String position, List<String> cells){
        int expected = switch(position){
            case "qb" -> 11;
            case "te" -> 6;
            default -> 9;
        };
        if(cells.size() != expected){
            return null;
        }
        String[] tokens = cells.get(0).trim().split("\\s+");
        String team = tokens.length > 1 && tokens[tokens.length - 1].matches("[A-Z]{2,3}")
                ? tokens[tokens.length - 1] : "";
        String name = String.join(" ", java.util.Arrays.copyOfRange(tokens, 0,
                team.isEmpty() ? tokens.length : tokens.length - 1));
        Player player = Player.getPlayerFromNameAndPos(name,
                Position.valueOf(position.toUpperCase()));
        if(player == null){
            return null;
        }
        double[] values = new double[cells.size() - 1];
        try {
            for(int c = 1; c < cells.size(); c++){
                values[c - 1] = Double.parseDouble(cells.get(c).replace(",", ""));
            }
        } catch (NumberFormatException unusable){
            return null;
        }
        JsonObject stats = new JsonObject();
        switch(position){
            case "qb" -> {
                stats.addProperty("pass_yd", values[2]);
                stats.addProperty("pass_td", values[3]);
                stats.addProperty("pass_int", values[4]);
                stats.addProperty("rush_yd", values[6]);
                stats.addProperty("rush_td", values[7]);
                stats.addProperty("fum_lost", values[8]);
            }
            case "rb" -> {
                stats.addProperty("rush_yd", values[1]);
                stats.addProperty("rush_td", values[2]);
                stats.addProperty("rec", values[3]);
                stats.addProperty("rec_yd", values[4]);
                stats.addProperty("rec_td", values[5]);
                stats.addProperty("fum_lost", values[6]);
            }
            case "wr" -> {
                stats.addProperty("rec", values[0]);
                stats.addProperty("rec_yd", values[1]);
                stats.addProperty("rec_td", values[2]);
                stats.addProperty("rush_yd", values[4]);
                stats.addProperty("rush_td", values[5]);
                stats.addProperty("fum_lost", values[6]);
            }
            default -> {
                stats.addProperty("rec", values[0]);
                stats.addProperty("rec_yd", values[1]);
                stats.addProperty("rec_td", values[2]);
                stats.addProperty("fum_lost", values[3]);
            }
        }
        return new FpRow(player, stats, values[values.length - 1]);
    }

}
