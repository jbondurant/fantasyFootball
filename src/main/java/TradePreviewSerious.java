public class TradePreviewSerious {

    ScoredRoster initialRosterTeam1;
    ScoredRoster initialRosterTeam2;

    Score t1p1Score;
    Score t1p2Score;
    Score t2p1Score;
    Score t2p2Score;

    Score t1p3Score;
    Score t2p3Score;

    double improvementT1;
    double improvementT2;

    TradePreviewSerious(ScoredRoster t1, ScoredRoster t2, Score t1p1S, Score t2p1S){

        if(t1p1S.player.lastName.equals("Murray")) {
            if (t2p1S.player.lastName.equals("Hill")) {
                int r = 0;
            }
        }

        t1p3Score = null;
        t2p3Score = null;
        t1p2Score = null;
        t2p2Score = null;

        t1p1Score = t1p1S;
        t2p1Score = t2p1S;

        ScoredRoster t1Copy = ScoredRoster.makeCopy(t1);
        ScoredRoster t2Copy = ScoredRoster.makeCopy(t2);

        initialRosterTeam1 = ScoredRoster.makeCopy(t1);
        initialRosterTeam2 = ScoredRoster.makeCopy(t2);

        double initialScoreT1 = t1Copy.scoreBestROSStartingLineup();
        double initialScoreT2 = t2Copy.scoreBestROSStartingLineup();

        int t1Size = t1Copy.draftedPlayersWithProj.size();
        t1Copy.removeScore(t1p1S);
        t2Copy.removeScore(t2p1S);

        t1Copy.addScore(t2p1S);
        t2Copy.addScore(t1p1S);

        int t1SizeAgain = t1Copy.draftedPlayersWithProj.size();

        double postSwapScoreT1 = t1Copy.scoreBestROSStartingLineup();
        double postSwapScoreT2 = t2Copy.scoreBestROSStartingLineup();


        improvementT1 = postSwapScoreT1 - initialScoreT1;
        improvementT2 = postSwapScoreT2 - initialScoreT2;

        /*
        if(t1p1S.player.lastName.equals("Murray")){
            if(t2p1S.player.lastName.equals("Hill")){
                    t1.printBestROSStartingLineup();
                    System.out.println("-------------");
                    t2.printBestROSStartingLineup();
                    System.out.println("-------------");
                    t1Copy.printBestROSStartingLineup();
                    System.out.println("-------------");
                    t2Copy.printBestROSStartingLineup();
                    System.out.println("-------------");

            }
        }
        */

    }

    TradePreviewSerious(ScoredRoster t1, ScoredRoster t2, Score t1p1S, Score t1p2S, Score t2p1S, Score t2p2S){

        t1p3Score = null;
        t2p3Score = null;

        t1p1Score = t1p1S;
        t1p2Score = t1p2S;
        t2p1Score = t2p1S;
        t2p2Score = t2p2S;

        if(t1p1S.player.firstName.equals("Marvin") && t2p1S.player.firstName.equals("Keenan") && t2p2S.player.firstName.equals("Christian")){
            String abc = t1p2S.player.firstName;
            if(abc.startsWith("P")) {
                int r = 1;
            }

        }

        ScoredRoster t1Copy = ScoredRoster.makeCopy(t1);
        ScoredRoster t2Copy = ScoredRoster.makeCopy(t2);

        double initialScoreT1 = t1Copy.scoreBestROSStartingLineup();
        double initialScoreT2 = t2Copy.scoreBestROSStartingLineup();

        int t1Size = t1Copy.draftedPlayersWithProj.size();
        t1Copy.removeScore(t1p1S);
        t1Copy.removeScore(t1p2S);
        t2Copy.removeScore(t2p1S);
        t2Copy.removeScore(t2p2S);

        t1Copy.addScore(t2p1S);
        t1Copy.addScore(t2p2S);
        t2Copy.addScore(t1p1S);
        t2Copy.addScore(t1p2S);



        int t1SizeAgain = t1Copy.draftedPlayersWithProj.size();

        double postSwapScoreT1 = t1Copy.scoreBestROSStartingLineup();
        double postSwapScoreT2 = t2Copy.scoreBestROSStartingLineup();


        improvementT1 = postSwapScoreT1 - initialScoreT1;
        improvementT2 = postSwapScoreT2 - initialScoreT2;

    }




    TradePreviewSerious(ScoredRoster t1, ScoredRoster t2, Score t1p1S, Score t1p2S, Score t1p3S, Score t2p1S, Score t2p2S, Score t2p3S){

        t1p1Score = t1p1S;
        t1p2Score = t1p2S;
        t1p3Score = t1p3S;
        t2p1Score = t2p1S;
        t2p2Score = t2p2S;
        t2p3Score = t2p3S;





        if(t1p1S.player.firstName.equals("Marvin") && t2p1S.player.firstName.equals("Keenan") && t2p2S.player.firstName.equals("Christian")){
            String abc = t1p2S.player.firstName;
            if(abc.startsWith("P")) {
                int r = 1;
            }

        }

        ScoredRoster t1Copy = ScoredRoster.makeCopy(t1);
        ScoredRoster t2Copy = ScoredRoster.makeCopy(t2);

        double initialScoreT1 = t1Copy.scoreBestROSStartingLineup();
        double initialScoreT2 = t2Copy.scoreBestROSStartingLineup();

        int t1Size = t1Copy.draftedPlayersWithProj.size();
        t1Copy.removeScore(t1p1S);
        t1Copy.removeScore(t1p2S);
        t1Copy.removeScore(t1p3S);
        t2Copy.removeScore(t2p1S);
        t2Copy.removeScore(t2p2S);
        t2Copy.removeScore(t2p3S);

        t1Copy.addScore(t2p1S);
        t1Copy.addScore(t2p2S);
        t1Copy.addScore(t2p3S);
        t2Copy.addScore(t1p1S);
        t2Copy.addScore(t1p2S);
        t2Copy.addScore(t1p3S);

        int t1SizeAgain = t1Copy.draftedPlayersWithProj.size();

        double postSwapScoreT1 = t1Copy.scoreBestROSStartingLineup();
        double postSwapScoreT2 = t2Copy.scoreBestROSStartingLineup();


        improvementT1 = postSwapScoreT1 - initialScoreT1;
        improvementT2 = postSwapScoreT2 - initialScoreT2;





    }

    public static String printTradePreviewString(TradePreviewSerious tps){

        if(tps.t2p2Score == null) {
            String banner = "-----------------------------------------------------\n";

            String toGive = "give:\t" + tps.t1p1Score.player.firstName + " " + tps.t1p1Score.player.lastName;
            String toTake = "take:\t" + tps.t2p1Score.player.firstName + " " + tps.t2p1Score.player.lastName;
            String pointChanges = "Your gain:\t" + tps.improvementT1 + "\n" + "Their gain:\t" + tps.improvementT2 + "\n";
            String toPrint = banner + toGive + "\n" + toTake + "\n" + pointChanges + banner;
            //System.out.println(toPrint);
            return toPrint;
        }
        else{
            String banner = "-----------------------------------------------------\n";

            String toGive = "give:\t" + tps.t1p1Score.player.firstName + " " + tps.t1p1Score.player.lastName;
            toGive += "\tand\t" + tps.t1p2Score.player.firstName + " " + tps.t1p2Score.player.lastName;

            String toTake = "take:\t" + tps.t2p1Score.player.firstName + " " + tps.t2p1Score.player.lastName;
            toTake += "\tand\t" + tps.t2p2Score.player.firstName + " " + tps.t2p2Score.player.lastName;

            String pointChanges = "Your gain:\t" + tps.improvementT1 + "\n" + "Their gain:\t" + tps.improvementT2 + "\n";

            if(tps.t2p3Score != null){
                toGive += "\tand\t" + tps.t1p3Score.player.firstName + " " + tps.t1p3Score.player.lastName;
                toTake += "\tand\t" + tps.t2p3Score.player.firstName + " " + tps.t2p3Score.player.lastName;
            }
            String toPrint = banner + toGive + "\n" + toTake + "\n" + pointChanges + banner;
            //System.out.println(toPrint);
            return toPrint;
        }

    }
}
