import java.util.ArrayList;
import java.util.PriorityQueue;

public class FantasyProsReports {

    //two issues, I don't have deep copy methods
    //so I have to reinitialize my static variables after messing with them


    public static ScoreOrderedPlayers seriousPlayers;
    public static ScoreOrderedPlayers fpECR;



    static{
        initializeReportVariables();
    }

    public static void initializeReportVariables() {
        SleeperLeague tempSerious = SleeperLeague.getSeriousLeague();

        FantasyProsScore seriousLSS = new FantasyProsScore(tempSerious.league.leagueScoringSettings);

        ArrayList<Score> seriousPlayersUnranked = seriousLSS.fantasyProsScoreLeagueAdjusted;

        seriousPlayers = new ScoreOrderedPlayers(seriousPlayersUnranked);

        ArrayList<Rank> rankingFPECR = FantasyProsADP.getRankingFPECR();
        ArrayList<Score> scoringFPECR = Score.rankingToScoring(rankingFPECR);
        fpECR = new ScoreOrderedPlayers(scoringFPECR);

    }

    //Serious vs ECRADP
    public static void sve(){
        System.out.println("Name,\tSerious,\tFantasyProsECR,\tDifference");
        ScoreOrderedPlayers.compareTwoOrders(seriousPlayers, fpECR);
    }

    public static void main(String[] args){
        sve();
    }
}
