import java.util.ArrayList;
import java.util.PriorityQueue;

public class FantasyProsReports {

    //two issues, I don't have deep copy methods
    //so I have to reinitialize my static variables after messing with them


    public static ScoreOrderedPlayers funPlayers;
    public static ScoreOrderedPlayers seriousPlayers;
    public static ScoreOrderedPlayers fpECR;



    static{
        initializeReportVariables();
    }

    public static void initializeReportVariables() {
        SleeperLeague tempFun = SleeperLeague.getFunLeague();
        SleeperLeague tempSerious = SleeperLeague.getSeriousLeague();

        FantasyProsScore funLSS = new FantasyProsScore(tempFun.league.leagueScoringSettings);
        FantasyProsScore seriousLSS = new FantasyProsScore(tempSerious.league.leagueScoringSettings);

        ArrayList<Score> funPlayersUnranked = funLSS.fantasyProsScoreLeagueAdjusted;
        ArrayList<Score> seriousPlayersUnranked = seriousLSS.fantasyProsScoreLeagueAdjusted;

        funPlayers = new ScoreOrderedPlayers(funPlayersUnranked);
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

    //Fun vs ECRADP
    public static void fve(){
        System.out.println("Name,\tFun,\tFantasyProsECR,\tDifference");
        ScoreOrderedPlayers.compareTwoOrders(funPlayers, fpECR);
    }

    //Serious vs Fun
    public static void svf(){
        System.out.println("Name,\tSerious,\tFun,\tDifference");
        ScoreOrderedPlayers.compareTwoOrders(seriousPlayers, funPlayers);
    }

    public static void main(String[] args){
        //sve();
        //fve();
        svf();
    }
}
