import org.checkerframework.checker.units.qual.A;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

public class FPRosterSerious {

    public static LeagueScoringSettings lssSerious = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;

    public static HashMap<String, Double> playerSRIDToScore = new HashMap<>();
    public static HashMap<String, Double> playerCSVSRIDToScore = new HashMap<>();

    static{
        boolean is6PtsThrow = true;
        playerSRIDToScore = InSeasonProjectionsFP.playerToScoreProjFPROS(is6PtsThrow);
        //todo 2023 uncomment playerCSVSRIDToScore = CSVProjectionsFP.playerToScoreProjFPROS(is6PtsThrow);
        playerCSVSRIDToScore = null;
    }

    String userID;

    ArrayList<Score> draftedPlayersWithProj;

    double scoreBestLineup;

    boolean isInSeason;

    boolean isCSV;


    public FPRosterSerious(String uid, ArrayList<Player> dp, boolean iis, boolean icsv){
        userID = uid;
        draftedPlayersWithProj = getFPProjForPlayers(dp, iis, icsv);
        scoreBestLineup = scoreBestROSStartingLineup();
        isInSeason = iis;
        isCSV = icsv;

    }

    public void removeScore(Score s){
        Score scoreToRemove = null;
        for(Score score : draftedPlayersWithProj){
            if(score.player.sportRadarID == null){
                //System.out.println("something is wrong perhaps");
                continue;
            }
            if(score.player.sportRadarID.equals(s.player.sportRadarID)){
                scoreToRemove = score;
            }
        }
        draftedPlayersWithProj.remove(scoreToRemove);
    }

    public void addScore(Score s){
        draftedPlayersWithProj.add(s);
    }

    public static FPRosterSerious makeCopy(FPRosterSerious fpRoster){
        ArrayList<Score> usedScores = fpRoster.draftedPlayersWithProj;
        ArrayList<Player> usedPlayers = new ArrayList<Player>();
        for(Score score : usedScores){
            usedPlayers.add(score.player);
        }

        String usedID = fpRoster.userID;
        FPRosterSerious copyOfRoster = new FPRosterSerious(usedID, usedPlayers, fpRoster.isInSeason, fpRoster.isCSV);
        return copyOfRoster;
    }

    public double scoreBestROSStartingLineup(){
        ScoreOrderedPlayers sop = new ScoreOrderedPlayers(draftedPlayersWithProj);
        double scoreQBs = 0.0;
        if(!sop.quarterbacks.isEmpty()) {
             Score tempQB = sop.quarterbacks.poll();
             scoreQBs += tempQB.score;
        }
        double scoreRBs = 0.0;
        if(!sop.runningBacks.isEmpty()){
            Score tempRB = sop.runningBacks.poll();
            scoreRBs += tempRB.score;
        }
        if(!sop.runningBacks.isEmpty()){
            Score tempRB = sop.runningBacks.poll();
            scoreRBs += tempRB.score;
        }

        double scoreWRs = 0.0;
        if(!sop.wideReceivers.isEmpty()){
            Score tempWR = sop.wideReceivers.poll();
            scoreWRs += tempWR.score;
        }
        if(!sop.wideReceivers.isEmpty()){
            Score tempWR = sop.wideReceivers.poll();
            scoreWRs += tempWR.score;
        }
        if(!sop.wideReceivers.isEmpty()){
            Score tempWR = sop.wideReceivers.poll();
            scoreWRs += tempWR.score;
        }

        double scoreTEs = 0.0;
        if(!sop.tightEnds.isEmpty()){
            Score tempTE = sop.tightEnds.poll();
            scoreTEs += tempTE.score;
        }


        double sfRB1 = 0.0;
        double sfRB2 = 0.0;
        double sfWR1 = 0.0;
        double sfWR2 = 0.0;
        double sfTE1 = 0.0;
        double sfTE2 = 0.0;
        if(!sop.runningBacks.isEmpty()) {
            Score tempSFRB = sop.runningBacks.poll();
            sfRB1 += tempSFRB.score;
        }
        if(!sop.runningBacks.isEmpty()) {
            Score tempSFRB = sop.runningBacks.poll();
            sfRB2 += tempSFRB.score;
        }
        if(!sop.wideReceivers.isEmpty()) {
            Score tempSFWR = sop.wideReceivers.poll();
            sfWR1 += tempSFWR.score;
        }
        if(!sop.wideReceivers.isEmpty()) {
            Score tempSFWR = sop.wideReceivers.poll();
            sfWR2 += tempSFWR.score;
        }
        if(!sop.tightEnds.isEmpty()) {
            Score tempSFTE = sop.tightEnds.poll();
            sfTE1 += tempSFTE.score;
        }
        if(!sop.tightEnds.isEmpty()) {
            Score tempSFTE = sop.tightEnds.poll();
            sfTE2 += tempSFTE.score;
        }

        double scoreFlexes = getFlexScore(sfRB1, sfRB2, sfWR1, sfWR2, sfTE1, sfTE2);

        double scoreDef = 0.0;
        if(!sop.defenses.isEmpty()){
            scoreDef += sop.defenses.poll().score;
        }

        double scoreAll = scoreQBs + scoreRBs + scoreWRs + scoreTEs + scoreFlexes + scoreDef;
        scoreBestLineup = scoreAll;
        return scoreAll;
    }

    public static double getFlexScore(double a, double b, double c, double d, double e, double f){
        ArrayList<Double> allVals = new ArrayList<>();
        allVals.add(a);
        allVals.add(b);
        allVals.add(c);
        allVals.add(d);
        allVals.add(e);
        allVals.add(f);
        Collections.sort(allVals);
        Collections.reverse(allVals);
        //TODO TEST correct order
        double scoreToReturn = allVals.get(0) + allVals.get(1);
        return scoreToReturn;
    }


    public static double getFlexScoreForPrint(Score a, Score b, Score c, Score d, Score e, Score f){
        ArrayList<Score> allVals = new ArrayList<>();
        allVals.add(a);
        allVals.add(b);
        allVals.add(c);
        allVals.add(d);
        allVals.add(e);
        allVals.add(f);

        Collections.sort(allVals, new ScoreComparatorWithNulls());
        //Collections.reverse(allVals);
        //TODO TEST correct order
        System.out.println("\t\t" + allVals.get(0).player.firstName + allVals.get(0).player.lastName + "\t" + allVals.get(0).score);
        System.out.println("\t\t" + allVals.get(1).player.firstName + allVals.get(1).player.lastName + "\t" + allVals.get(1).score);
        double scoreToReturn = allVals.get(0).score + allVals.get(1).score;
        return scoreToReturn;
    }

    public static ArrayList<Score> getFPProjForPlayers(ArrayList<Player> dp, boolean inSeason, boolean isCSV){
        ArrayList<Score> toReturn = new ArrayList<>();
        FantasyProsScore fps = new FantasyProsScore(lssSerious);
        for(Player p : dp){
            String pID1 = p.sportRadarID;
            if(inSeason && (!isCSV)) {
                if (playerSRIDToScore.containsKey(pID1)) {
                    double scoreOfPlayer = playerSRIDToScore.get(pID1);
                    Score tempScore = new Score(scoreOfPlayer, p);
                    toReturn.add(tempScore);
                } else {
                    System.out.println("Player score for " + p.firstName + " " + p.lastName + " not found");
                }
            }
            else if(inSeason && isCSV){
                if(playerCSVSRIDToScore.containsKey(pID1)){
                  double scoreOfPlayer = playerCSVSRIDToScore.get(pID1);
                  Score tempScore = new Score(scoreOfPlayer, p);
                  toReturn.add(tempScore);
                }
                else {
                    System.out.println("Player score for " + p.firstName + " " + p.lastName + " not found");
                }
            }
            else{
                ArrayList<Score> scoreList = SleeperLeague.getScoreList();
                double scoreOfPlayer = 0.0;
                for(Score score : scoreList){
                    if(score.player != null && score.player.sportRadarID.equals(p.sportRadarID)){
                        scoreOfPlayer = score.score;
                        Score tempScore = new Score(scoreOfPlayer, p);
                        toReturn.add(tempScore);
                    }
                }
                if(scoreOfPlayer == 0.0) {
                    System.out.println("Player score for " + p.firstName + " " + p.lastName + " not found");
                }
            }
        }
        return toReturn;
    }

    public static void printWorstStartingQBRosterOrder(ArrayList<FPRosterSerious> allRosters){
        Collections.sort(allRosters, new FPRosterSeriousStartingQBComparator());

        for(FPRosterSerious ros : allRosters){
            String nameStartingQB = "";
            double scoreStartingQB = 0.0;
            String teamID = ros.userID;

            for(Score score : ros.draftedPlayersWithProj){
                if(score.player.position.equals(Position.QB)){
                    if(score.score > scoreStartingQB){
                        scoreStartingQB = score.score;
                        nameStartingQB = score.player.firstName + " " + score.player.lastName;
                    }
                }
            }
            System.out.println("TeamID:\t" + teamID + "\tStartingQB:\t" + nameStartingQB + "\tProjection:\t" + scoreStartingQB);
        }
    }



    public double printBestROSStartingLineup(){
        ScoreOrderedPlayers sop = new ScoreOrderedPlayers(draftedPlayersWithProj);
        System.out.println("Best Roster Lineup:");
        double scoreQBs = 0.0;
        System.out.println("\tBest QB Lineup:");
        if(!sop.quarterbacks.isEmpty()) {
            Score tempQB = sop.quarterbacks.poll();
            scoreQBs += tempQB.score;
            System.out.println("\t\t" + tempQB.player.firstName + tempQB.player.lastName + "\t" + tempQB.score);
        }
        double scoreRBs = 0.0;
        System.out.println("\tBest RB Lineup:");
        if(!sop.runningBacks.isEmpty()){
            Score tempRB = sop.runningBacks.poll();
            scoreRBs += tempRB.score;
            System.out.println("\t\t" + tempRB.player.firstName + tempRB.player.lastName + "\t" + tempRB.score);

        }
        if(!sop.runningBacks.isEmpty()){
            Score tempRB = sop.runningBacks.poll();
            scoreRBs += tempRB.score;
            System.out.println("\t\t" + tempRB.player.firstName + tempRB.player.lastName + "\t" + tempRB.score);
        }

        double scoreWRs = 0.0;
        System.out.println("\tBest WR Lineup:");
        if(!sop.wideReceivers.isEmpty()){
            Score tempWR = sop.wideReceivers.poll();
            scoreWRs += tempWR.score;
            System.out.println("\t\t" + tempWR.player.firstName + tempWR.player.lastName + "\t" + tempWR.score);

        }
        if(!sop.wideReceivers.isEmpty()){
            Score tempWR = sop.wideReceivers.poll();
            scoreWRs += tempWR.score;
            System.out.println("\t\t" + tempWR.player.firstName + tempWR.player.lastName + "\t" + tempWR.score);
        }
        if(!sop.wideReceivers.isEmpty()){
            Score tempWR = sop.wideReceivers.poll();
            scoreWRs += tempWR.score;
            System.out.println("\t\t" + tempWR.player.firstName + tempWR.player.lastName + "\t" + tempWR.score);
        }

        System.out.println("\tBest TE Lineup:");
        double scoreTEs = 0.0;
        if(!sop.tightEnds.isEmpty()){
            Score tempTE = sop.tightEnds.poll();
            scoreTEs += tempTE.score;
            System.out.println("\t\t" + tempTE.player.firstName + tempTE.player.lastName + "\t" + tempTE.score);

        }

        System.out.println("\tBest FLEX Lineup:");

        double sfRB1 = 0.0;
        double sfRB2 = 0.0;
        double sfWR1 = 0.0;
        double sfWR2 = 0.0;
        double sfTE1 = 0.0;
        double sfTE2 = 0.0;

        Score tempSFRB1 = null;
        Score tempSFRB2 = null;
        Score tempSFWR1 = null;
        Score tempSFWR2 = null;
        Score tempSFTE1 = null;
        Score tempSFTE2 = null;
        if(!sop.runningBacks.isEmpty()) {
           tempSFRB1 = sop.runningBacks.poll();
        }
        if(!sop.runningBacks.isEmpty()) {
            tempSFRB2 = sop.runningBacks.poll();
        }
        if(!sop.wideReceivers.isEmpty()) {
            tempSFWR1 = sop.wideReceivers.poll();
        }
        if(!sop.wideReceivers.isEmpty()) {
            tempSFWR2 = sop.wideReceivers.poll();
        }
        if(!sop.tightEnds.isEmpty()) {
            tempSFTE1 = sop.tightEnds.poll();
        }
        if(!sop.tightEnds.isEmpty()) {
            tempSFTE2 = sop.tightEnds.poll();
        }

        double scoreFlexes = getFlexScoreForPrint(tempSFRB1, tempSFRB2, tempSFWR1, tempSFWR2, tempSFTE1, tempSFTE2);
        //double scoreFlexes = getFlexScore(sfRB1, sfRB2, sfWR1, sfWR2, sfTE1, sfTE2);

        System.out.println("\tBest DEF Lineup:");
        double scoreDef = 0.0;
        if(!sop.defenses.isEmpty()){
            Score tempDEF = sop.defenses.poll();
            scoreDef = tempDEF.score;
            System.out.println("\t\t" + tempDEF.player.firstName + tempDEF.player.lastName + "\t" + tempDEF.score);
        }

        double scoreAll = scoreQBs + scoreRBs + scoreWRs + scoreTEs + scoreFlexes + scoreDef;
        scoreBestLineup = scoreAll;
        return scoreAll;
    }
}
