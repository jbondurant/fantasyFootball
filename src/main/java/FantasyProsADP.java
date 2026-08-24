import java.util.ArrayList;
import java.util.List;

/**
 * FantasyPros expert consensus ranking, half-PPR.
 *
 * These pages no longer carry a "sportsdata_id", so rows are matched to Sleeper
 * players by name/team/position instead. Rows that match nothing (rookies
 * FantasyPros lists before Sleeper does, retired players, and so on) are
 * dropped rather than added with a null player.
 */
public class FantasyProsADP {

    public static String filepathStart = "fantasyProsADP";
    public static String webURL = "https://www.fantasypros.com/nfl/rankings/half-point-ppr-cheatsheets.php";

    private static ArrayList<Rank> rankingFPADP;

    public static synchronized ArrayList<Rank> getRankingFPECR(){
        if(rankingFPADP == null){
            rankingFPADP = parseTodaysWebPage();
        }
        return rankingFPADP;
    }

    private static String getTodaysWebPage(){
        return InOutUtilities.getTodaysWebPage(webURL, filepathStart);
    }

    public static ArrayList<Rank> parseTodaysWebPage(){
        List<FantasyProsEcrData.Entry> entries = FantasyProsEcrData.parse(getTodaysWebPage());

        ArrayList<Rank> todaysRankings = new ArrayList<Rank>();
        int unmatched = 0;
        for(FantasyProsEcrData.Entry entry : entries){
            Player player = entry.resolvePlayer();
            if(player == null){
                unmatched++;
                continue;
            }
            todaysRankings.add(new Rank(entry.rankEcr, player));
        }
        if(todaysRankings.isEmpty()){
            throw new RuntimeException("no FantasyPros ranking rows could be matched to a sleeper player");
        }
        if(unmatched > 0){
            System.out.println("FantasyPros ranking: " + unmatched + " of " + entries.size()
                    + " rows had no matching sleeper player");
        }
        return todaysRankings;
    }

    public static void main(String[] args){
        ArrayList<Rank> ranking = getRankingFPECR();
        System.out.println("ranked " + ranking.size() + " players");
        for(int i = 0; i < Math.min(10, ranking.size()); i++){
            Rank rank = ranking.get(i);
            System.out.println(rank.rankNum + "\t" + rank.player.firstName + " " + rank.player.lastName
                    + "\t" + rank.player.position + "\t" + rank.player.team);
        }
    }

}
