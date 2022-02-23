import java.util.ArrayList;

public class FantasyProsScore {


    public ArrayList<Score> fantasyProsScoreLeagueAdjusted;


    public FantasyProsScore(LeagueScoringSettings leagueScoringSettings){
        ArrayList<QBProjection> qbProjections = FantasyProsProjections.getQBProjections();
        ArrayList<FlexProjection> flexProjections = FantasyProsProjections.getFlexProjections();
        ArrayList<DEFProjection> defProjections = FantasyProsProjections.getDEFProjections();

        ArrayList<Score> qbScores = scoreQuarterbacks(qbProjections, leagueScoringSettings);
        ArrayList<Score> flexScores = scoreFlexes(flexProjections, leagueScoringSettings);
        ArrayList<Score> defScores = scoreDefenses(defProjections, leagueScoringSettings);

        ArrayList<Score> allScores = new ArrayList<Score>();
        allScores.addAll(qbScores);
        allScores.addAll(flexScores);
        allScores.addAll(defScores);

        fantasyProsScoreLeagueAdjusted = allScores;

    }

    public static ArrayList<Score> scoreQuarterbacks(ArrayList<QBProjection> projectionsQB, LeagueScoringSettings lss){
        ArrayList<Score> scores = new ArrayList<Score>();
        for(QBProjection qbProj : projectionsQB){
            Player player = qbProj.player;
            //pass yards, pass td, interceptions
            double scoreThrowing = qbProj.yds * lss.passYard + qbProj.tds * lss.passTD + qbProj.ints * lss.interception;
            //rush yards, rush td, fumbles lost
            double scoreRushing = qbProj.rushYds * lss.rushYard + qbProj.rushTds * lss.rushTD + qbProj.fl * lss.fumbleLost;
            double projectedScore = scoreThrowing + scoreRushing;
            Score score = new Score(projectedScore, player);
            scores.add(score);
        }
        return scores;
    }

    public static ArrayList<Score> scoreFlexes(ArrayList<FlexProjection> projectionsFlex, LeagueScoringSettings lss){
        ArrayList<Score> scores = new ArrayList<Score>();
        for(FlexProjection flexProj : projectionsFlex){
            Player player = flexProj.player;
            //pass yards, pass td, interceptions
            double scoreReceiving = flexProj.rec * lss.reception + flexProj.recYDS * lss.receivingYard + flexProj.recTDS * lss.receivingTD;
            //rush yards, rush td, fumbles lost
            double scoreRushing = flexProj.rushYDS * lss.rushYard + flexProj.rushTDS * lss.rushTD + flexProj.fl * lss.fumbleLost;
            double projectedScore = scoreReceiving + scoreRushing;
            Score score = new Score(projectedScore, player);
            scores.add(score);
        }
        return scores;
    }

    //can't calculate projections based on lss for defenses
    public static ArrayList<Score> scoreDefenses(ArrayList<DEFProjection> projectionsDEF, LeagueScoringSettings lss){
        ArrayList<Score> scores = new ArrayList<Score>();
        for(DEFProjection defProj : projectionsDEF){
            Player player = defProj.player;
            double projectedScore = defProj.fpts;
            Score score = new Score(projectedScore, player);
            scores.add(score);
        }
        return scores;
    }

    public static void main(String[] args){
        SleeperLeague funL = SleeperLeague.getFunLeague();
        LeagueScoringSettings funSettings = funL.league.leagueScoringSettings;
        //looks like bug on website values which I confirmed with funSettings.interception = -2.0;
        FantasyProsScore funScores = new FantasyProsScore(funSettings);

        SleeperLeague seriousL = SleeperLeague.getSeriousLeague();
        LeagueScoringSettings seriousSettings = seriousL.league.leagueScoringSettings;
        FantasyProsScore seriousScores = new FantasyProsScore(seriousSettings);
    }





}
