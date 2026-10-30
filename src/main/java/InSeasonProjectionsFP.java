import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * FantasyPros rest-of-season pages.
 *
 * These used to be the projected-points source: each player row carried an
 * "r2p_pts" field. It went missing for a while and is back by 2026-10-29, but it
 * is FantasyPros' consensus RANK converted to points (on standard scoring), not
 * an aggregate of anyone's projections - Justin, that day: "the projections seem
 * to be calculated from aggregation of rankings, and not from aggregation of
 * projections which is concerning". So the rank is what is read:
 *
 *  - {@link #getRosRanking()} - the consensus rank, the median of the last
 *    {@link #DAYS} days' pulls - is the useful thing here, and
 *    {@link #panels()} says which experts today's consensus averages.
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
    private static final java.util.Map<String, FantasyProsEcrData.Panel> panels = new java.util.LinkedHashMap<>();

    /**
     * A rank is the MEDIAN of the pulls saved over this many days, today's
     * included. Justin, 2026-10-29: "the experts they pull the rankings from
     * change depending on which day you pull". The consensus is a handful of
     * experts (six of 37 that day) and which handful moves from day to day; the
     * median over a few days' pulls keeps one day's panel from deciding a trade.
     * It lags news by a day or two, which Sleeper, the base the trades are found
     * on, does not. A day with no saved pull just counts fewer days.
     */
    static final int DAYS = 3;

    /** An expert whose last update is older than this, in days before the page, is called out. */
    static final double STALE_DAYS = 7;

    /** Today's panel behind each position page, in page order: who the consensus is and how stale. */
    public static synchronized java.util.Map<String, FantasyProsEcrData.Panel> panels(){
        getRosRanking();
        return panels;
    }

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
        String today = InOutUtilities.getTodaysWebPage(webURL, filepathStart);
        FantasyProsEcrData.Panel panel = FantasyProsEcrData.panel(today);
        panels.put(filepathStart.replace("fantasyProsProjectionInSeason", ""), panel);
        if(panel != null && panel.stalestDays() > STALE_DAYS){
            System.out.printf("FantasyPros %s: the consensus includes an expert last updated %.0f days before the page%n",
                    filepathStart, panel.stalestDays());
        }
        List<String> pages = new ArrayList<>(List.of(today));
        String fixtureDir = System.getProperty("fixtureDir");
        if(fixtureDir == null || fixtureDir.isBlank()){
            java.time.LocalDate day = java.time.LocalDate.parse(DateStuff.DateUtility.getTodaysDate());
            for(int back = 1; back < DAYS; back++){
                java.nio.file.Path saved = java.nio.file.Path.of(filepathStart + day.minusDays(back) + ".txt");
                try {
                    if(java.nio.file.Files.isRegularFile(saved)){
                        pages.add(java.nio.file.Files.readString(saved));
                    }
                }
                catch(java.io.IOException unreadable){
                    // a day that cannot be read counts as a day not pulled
                }
            }
        }
        java.util.Map<String, Player> byId = new HashMap<>();
        java.util.Map<String, List<Integer>> ranks = new java.util.LinkedHashMap<>();
        for(String page : pages){
            for(FantasyProsEcrData.Entry entry : FantasyProsEcrData.parse(page)){
                Player player = entry.resolvePlayer();
                if(player == null || player.position != entry.position){
                    // a page's rank is a rank at the page's position: the name-and-team fallback matched
                    // FantasyPros' TE61 Riley Nowakowski to a man Sleeper lists at RB (2026-10-29), and a
                    // TE rank read against running backs made him a top free-agent back
                    continue;
                }
                byId.put(player.sleeperIDString, player);
                ranks.computeIfAbsent(player.sleeperIDString, k -> new ArrayList<>()).add(entry.rankEcr);
            }
        }
        ArrayList<Rank> ranking = new ArrayList<>();
        ranks.forEach((id, list) -> ranking.add(new Rank(median(list), byId.get(id))));
        return ranking;
    }

    /** The middle of a few ranks, the lower middle of an even count (rounded toward the better rank). */
    static int median(List<Integer> ranks){
        List<Integer> sorted = new ArrayList<>(ranks);
        java.util.Collections.sort(sorted);
        return sorted.get((sorted.size() - 1) / 2);
    }

    public static HashMap<String, Double> playerToScoreProjFPROS(boolean is6ptsThrow){
        throw new UnsupportedOperationException(
                "FantasyPros' rest-of-season points (r2p_pts, back on its pages by 2026-10-29) are its "
                        + "consensus RANK converted to points on standard scoring, not a projection and not this "
                        + "league's scoring, so IN_SEASON_FP_SITE does not read them. Use ProjectionSource.SLEEPER, "
                        + "or InSeasonProjectionsFP.getRosRanking() for ranks.");
    }

    public static void main(String[] args){
        ArrayList<Rank> ranking = getRosRanking();
        System.out.println("rest-of-season ranks matched for " + ranking.size() + " players");
    }

}
