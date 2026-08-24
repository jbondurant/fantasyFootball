import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * FantasyPros rest-of-season pages.
 *
 * These used to be the projected-points source: each player row carried an
 * "r2p_pts" field. FantasyPros removed it (along with "sportsdata_id") and the
 * pages now publish expert consensus *ranks* only. There is no longer any way
 * to get projected points out of them, so:
 *
 *  - {@link #getRosRanking()} still works, and is the useful thing here.
 *  - {@link #playerToScoreProjFPROS} cannot work and says so, loudly. Use
 *    {@link ProjectionSource#SLEEPER}, which is backed by real stat-line
 *    projections.
 */
public class InSeasonProjectionsFP {

    public static String filepathStartQB = "fantasyProsProjectionInSeasonQB";
    public static String filepathStartRBHalf = "fantasyProsProjectionInSeasonRBHalf";
    public static String filepathStartWRHalf = "fantasyProsProjectionInSeasonWRHalf";
    public static String filepathStartTEHalf = "fantasyProsProjectionInSeasonTEHalf";
    public static String filepathStartDEF = "fantasyProsProjectionInSeasonDEF";

    public static String webURLQB = "https://www.fantasypros.com/nfl/rankings/ros-qb.php";
    public static String webURLRBHalf = "https://www.fantasypros.com/nfl/rankings/ros-half-point-ppr-rb.php";
    public static String webURLWRHalf = "https://www.fantasypros.com/nfl/rankings/ros-half-point-ppr-wr.php";
    public static String webURLTEHalf = "https://www.fantasypros.com/nfl/rankings/ros-half-point-ppr-te.php";
    public static String webURLDEF = "https://www.fantasypros.com/nfl/rankings/ros-dst.php";

    private static ArrayList<Rank> rosRanking;

    /** Rest-of-season expert consensus rank, per position, matched to players. */
    public static synchronized ArrayList<Rank> getRosRanking(){
        if(rosRanking == null){
            ArrayList<Rank> ranking = new ArrayList<>();
            ranking.addAll(rankPage(webURLQB, filepathStartQB));
            ranking.addAll(rankPage(webURLRBHalf, filepathStartRBHalf));
            ranking.addAll(rankPage(webURLWRHalf, filepathStartWRHalf));
            ranking.addAll(rankPage(webURLTEHalf, filepathStartTEHalf));
            ranking.addAll(rankPage(webURLDEF, filepathStartDEF));
            rosRanking = ranking;
        }
        return rosRanking;
    }

    private static ArrayList<Rank> rankPage(String webURL, String filepathStart){
        String entireHTML = InOutUtilities.getTodaysWebPage(webURL, filepathStart);
        List<FantasyProsEcrData.Entry> entries = FantasyProsEcrData.parse(entireHTML);
        ArrayList<Rank> ranking = new ArrayList<>();
        for(FantasyProsEcrData.Entry entry : entries){
            Player player = entry.resolvePlayer();
            if(player == null){
                continue;
            }
            ranking.add(new Rank(entry.rankEcr, player));
        }
        return ranking;
    }

    public static HashMap<String, Double> playerToScoreProjFPROS(boolean is6ptsThrow){
        throw new UnsupportedOperationException(
                "FantasyPros no longer publishes rest-of-season projected points (the r2p_pts field was "
                        + "removed from their rankings pages), so IN_SEASON_FP_SITE cannot produce scores. "
                        + "Use ProjectionSource.SLEEPER, or InSeasonProjectionsFP.getRosRanking() for ranks.");
    }

    public static void main(String[] args){
        ArrayList<Rank> ranking = getRosRanking();
        System.out.println("rest-of-season ranks matched for " + ranking.size() + " players");
    }

}
