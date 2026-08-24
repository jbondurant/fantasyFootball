import PlayerImportAndSetup.Position;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;

/**
 * Projected stat lines, split into the shapes the scoring code expects.
 *
 * This replaces the old FantasyProsProjections scraper. That scraper read the
 * projections tables off fantasypros.com, and two things killed it: the pages
 * now render only ten players each (the rest arrive over XHR), and the
 * `scoring=HALF` variant it requested for RB/WR/TE returns an empty table. It
 * had also been quietly producing nothing useful for years - every projection
 * was attached to a null player, because the id lookups it called
 * (getPlayerFromFPid, and an SRID that was never assigned) always returned null.
 *
 * Sleeper publishes the same stat categories, keyed by an id we can actually
 * join on, so the projections come from there and {@link FantasyProsScore}
 * still applies the league's scoring settings to them unchanged.
 */
public class StatLineProjections {

    private static ArrayList<QBProjection> projectionsQB;
    private static ArrayList<FlexProjection> projectionsFlex;
    private static ArrayList<DEFProjection> projectionsDEF;

    private static synchronized void initialize(){
        if(projectionsQB != null){
            return;
        }
        ArrayList<QBProjection> qbs = new ArrayList<>();
        ArrayList<FlexProjection> flexes = new ArrayList<>();
        ArrayList<DEFProjection> defenses = new ArrayList<>();

        for(JsonElement jsonPlayer : SleeperProjections.getTodaysProjections()){
            JsonObject playerObject = jsonPlayer.getAsJsonObject();
            JsonObject stats = playerObject.getAsJsonObject("stats");
            if(stats == null){
                continue;
            }
            Player player = Player.getPlayerFromSIDV2(playerObject.get("player_id").getAsString());
            if(player == null){
                continue;
            }

            if(player.position.equals(Position.QB)){
                qbs.add(new QBProjection(quarterbackStats(stats), player));
            }
            else if(player.position.equals(Position.RB)
                    || player.position.equals(Position.WR)
                    || player.position.equals(Position.TE)){
                flexes.add(new FlexProjection(flexStats(stats), player));
            }
            else if(player.position.equals(Position.DEF)){
                defenses.add(new DEFProjection(defenseStats(stats), player));
            }
        }

        projectionsQB = qbs;
        projectionsFlex = flexes;
        projectionsDEF = defenses;
    }

    private static double[] quarterbackStats(JsonObject stats){
        return new double[]{
                SleeperProjections.optionalStat(stats, "pass_att"),
                SleeperProjections.optionalStat(stats, "pass_cmp"),
                SleeperProjections.optionalStat(stats, "pass_yd"),
                SleeperProjections.optionalStat(stats, "pass_td"),
                SleeperProjections.optionalStat(stats, "pass_int"),
                SleeperProjections.optionalStat(stats, "rush_att"),
                SleeperProjections.optionalStat(stats, "rush_yd"),
                SleeperProjections.optionalStat(stats, "rush_td"),
                SleeperProjections.optionalStat(stats, "fum_lost"),
                SleeperProjections.optionalStat(stats, "pts_half_ppr")
        };
    }

    private static double[] flexStats(JsonObject stats){
        return new double[]{
                SleeperProjections.optionalStat(stats, "rush_att"),
                SleeperProjections.optionalStat(stats, "rush_yd"),
                SleeperProjections.optionalStat(stats, "rush_td"),
                SleeperProjections.optionalStat(stats, "rec"),
                SleeperProjections.optionalStat(stats, "rec_yd"),
                SleeperProjections.optionalStat(stats, "rec_td"),
                SleeperProjections.optionalStat(stats, "fum_lost"),
                SleeperProjections.optionalStat(stats, "pts_half_ppr")
        };
    }

    private static double[] defenseStats(JsonObject stats){
        return new double[]{
                SleeperProjections.optionalStat(stats, "sack"),
                SleeperProjections.optionalStat(stats, "int"),
                SleeperProjections.optionalStat(stats, "fum_rec"),
                SleeperProjections.optionalStat(stats, "ff"),
                SleeperProjections.optionalStat(stats, "def_td"),
                SleeperProjections.optionalStat(stats, "safe"),
                SleeperProjections.optionalStat(stats, "pts_allow"),
                SleeperProjections.optionalStat(stats, "yds_allow"),
                SleeperProjections.optionalStat(stats, "pts_half_ppr")
        };
    }

    public static ArrayList<QBProjection> getQBProjections(){
        initialize();
        return projectionsQB;
    }

    public static ArrayList<FlexProjection> getFlexProjections(){
        initialize();
        return projectionsFlex;
    }

    public static ArrayList<DEFProjection> getDEFProjections(){
        initialize();
        return projectionsDEF;
    }

    public static void main(String[] args){
        System.out.println("QB projections:\t" + getQBProjections().size());
        System.out.println("Flex projections:\t" + getFlexProjections().size());
        System.out.println("DEF projections:\t" + getDEFProjections().size());
    }

}
