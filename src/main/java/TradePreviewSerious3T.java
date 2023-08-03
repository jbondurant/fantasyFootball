import java.util.ArrayList;
import java.util.List;

public class TradePreviewSerious3T {

    Score t1p1Score;
    Score t1p2Score;
    Score t1p3Score;

    Score t2p1Score;
    Score t2p2Score;
    Score t2p3Score;

    Score t3p1Score;
    Score t3p2Score;
    Score t3p3Score;

    Score t1Received1;
    Score t1Received2;

    double improvementT1;
    double improvementT2;
    double improvementT3;

    String t1ReceivedPlayers;
    String t2ReceivedPlayers;
    String t3ReceivedPlayers;

    TradePreviewSerious3T(FPRosterSerious t1, FPRosterSerious t2, FPRosterSerious t3, Score t1p1S, Score t1p2S, Score t2p1S, Score t2p2S, Score t3p1S, Score t3p2S, List<Character> perm, int minMe, int minThem){
        double minMine = (double) minMe;
        double minTheirs = (double) minThem;

        t1p3Score = null;
        t2p3Score = null;
        t3p3Score = null;

        t1p1Score = t1p1S;
        t1p2Score = t1p2S;

        t2p1Score = t2p1S;
        t2p2Score = t2p2S;

        t3p1Score = t3p1S;
        t3p2Score = t3p2S;

        t1ReceivedPlayers = "";
        t2ReceivedPlayers = "";
        t3ReceivedPlayers = "";

        t1Received1 = null;
        t1Received2 = null;
        ArrayList<Score> t1ReceivedBoth = new ArrayList<>();

        FPRosterSerious t1Copy = FPRosterSerious.makeCopy(t1);
        FPRosterSerious t2Copy = FPRosterSerious.makeCopy(t2);
        FPRosterSerious t3Copy = FPRosterSerious.makeCopy(t3);

        double initialScoreT1 = t1Copy.scoreBestROSStartingLineup();
        double initialScoreT2 = t2Copy.scoreBestROSStartingLineup();
        double initialScoreT3 = t3Copy.scoreBestROSStartingLineup();

        int t1Size = t1Copy.draftedPlayersWithProj.size();
        t1Copy.removeScore(t1p1S);
        t1Copy.removeScore(t1p2S);
        t2Copy.removeScore(t2p1S);
        t2Copy.removeScore(t2p2S);
        t3Copy.removeScore(t3p1S);
        t3Copy.removeScore(t3p2S);

        // 1 of 6
        if(perm.get(0) == 'a'){
            t1Copy.addScore(t1p1S);
            t1ReceivedPlayers += t1p1S.player.firstName + " " + t1p1S.player.lastName;
            t1ReceivedBoth.add(t1p1S);
        }
        else if(perm.get(0) == 'b'){
            t1Copy.addScore(t1p2S);
            t1ReceivedPlayers += t1p2S.player.firstName + " " + t1p2S.player.lastName;
            t1ReceivedBoth.add(t1p2S);
        }
        else if(perm.get(0) == 'c'){
            t1Copy.addScore(t2p1S);
            t1ReceivedPlayers += t2p1S.player.firstName + " " + t2p1S.player.lastName;
            t1ReceivedBoth.add(t2p1S);
        }
        else if(perm.get(0) == 'd'){
            t1Copy.addScore(t2p2S);
            t1ReceivedPlayers += t2p2S.player.firstName + " " + t2p2S.player.lastName;
            t1ReceivedBoth.add(t2p2S);
        }
        else if(perm.get(0) == 'e'){
            t1Copy.addScore(t3p1S);
            t1ReceivedPlayers += t3p1S.player.firstName + " " + t3p1S.player.lastName;
            t1ReceivedBoth.add(t3p1S);
        }
        else if(perm.get(0) == 'f'){
            t1Copy.addScore(t3p2S);
            t1ReceivedPlayers += t3p2S.player.firstName + " " + t3p2S.player.lastName;
            t1ReceivedBoth.add(t3p2S);
        }
        // 2 of 6
        if(perm.get(1) == 'a'){
            t1Copy.addScore(t1p1S);
            t1ReceivedPlayers += t1p1S.player.firstName + " " + t1p1S.player.lastName;
            t1ReceivedBoth.add(t1p1S);
        }
        else if(perm.get(1) == 'b'){
            t1Copy.addScore(t1p2S);
            t1ReceivedPlayers += t1p2S.player.firstName + " " + t1p2S.player.lastName;
            t1ReceivedBoth.add(t1p2S);
        }
        else if(perm.get(1) == 'c'){
            t1Copy.addScore(t2p1S);
            t1ReceivedPlayers += t2p1S.player.firstName + " " + t2p1S.player.lastName;
            t1ReceivedBoth.add(t2p1S);
        }
        else if(perm.get(1) == 'd'){
            t1Copy.addScore(t2p2S);
            t1ReceivedPlayers += t2p2S.player.firstName + " " + t2p2S.player.lastName;
            t1ReceivedBoth.add(t2p2S);
        }
        else if(perm.get(1) == 'e'){
            t1Copy.addScore(t3p1S);
            t1ReceivedPlayers += t3p1S.player.firstName + " " + t3p1S.player.lastName;
            t1ReceivedBoth.add(t3p1S);
        }
        else if(perm.get(1) == 'f'){
            t1Copy.addScore(t3p2S);
            t1ReceivedPlayers += t3p2S.player.firstName + " " + t3p2S.player.lastName;
            t1ReceivedBoth.add(t3p2S);
        }

        int t1SizeAgain = t1Copy.draftedPlayersWithProj.size();
        double postSwapScoreT1 = t1Copy.scoreBestROSStartingLineup();
        improvementT1 = postSwapScoreT1 - initialScoreT1;

        if(improvementT1 < minMine){
            improvementT1 = -900.0;
            improvementT2 = -900.0;
            improvementT3 = -900.0;
            return;
        }



        // 3 of 6
        if(perm.get(2) == 'a'){
            t2Copy.addScore(t1p1S);
            t2ReceivedPlayers += t1p1S.player.firstName + " " + t1p1S.player.lastName;
            t1ReceivedBoth.add(t1p1S);
        }
        else if(perm.get(2) == 'b'){
            t2Copy.addScore(t1p2S);
            t2ReceivedPlayers += t1p2S.player.firstName + " " + t1p2S.player.lastName;
            t1ReceivedBoth.add(t1p2S);
        }
        else if(perm.get(2) == 'c'){
            t2Copy.addScore(t2p1S);
            t2ReceivedPlayers += t2p1S.player.firstName + " " + t2p1S.player.lastName;
            t1ReceivedBoth.add(t2p1S);
        }
        else if(perm.get(2) == 'd'){
            t2Copy.addScore(t2p2S);
            t2ReceivedPlayers += t2p2S.player.firstName + " " + t2p2S.player.lastName;
            t1ReceivedBoth.add(t2p2S);
        }
        else if(perm.get(2) == 'e'){
            t2Copy.addScore(t3p1S);
            t2ReceivedPlayers += t3p1S.player.firstName + " " + t3p1S.player.lastName;
            t1ReceivedBoth.add(t3p1S);
        }
        else if(perm.get(2) == 'f'){
            t2Copy.addScore(t3p2S);
            t2ReceivedPlayers += t3p2S.player.firstName + " " + t3p2S.player.lastName;
            t1ReceivedBoth.add(t3p2S);
        }
        // 4 of 6
        if(perm.get(3) == 'a'){
            t2Copy.addScore(t1p1S);
            t2ReceivedPlayers += t1p1S.player.firstName + " " + t1p1S.player.lastName;
            t1ReceivedBoth.add(t1p1S);
        }
        else if(perm.get(3) == 'b'){
            t2Copy.addScore(t1p2S);
            t2ReceivedPlayers += t1p2S.player.firstName + " " + t1p2S.player.lastName;
            t1ReceivedBoth.add(t1p2S);
        }
        else if(perm.get(3) == 'c'){
            t2Copy.addScore(t2p1S);
            t2ReceivedPlayers += t2p1S.player.firstName + " " + t2p1S.player.lastName;
            t1ReceivedBoth.add(t2p1S);
        }
        else if(perm.get(3) == 'd'){
            t2Copy.addScore(t2p2S);
            t2ReceivedPlayers += t2p2S.player.firstName + " " + t2p2S.player.lastName;
            t1ReceivedBoth.add(t2p2S);
        }
        else if(perm.get(3) == 'e'){
            t2Copy.addScore(t3p1S);
            t2ReceivedPlayers += t3p1S.player.firstName + " " + t3p1S.player.lastName;
            t1ReceivedBoth.add(t3p1S);

        }
        else if(perm.get(3) == 'f'){
            t2Copy.addScore(t3p2S);
            t2ReceivedPlayers += t3p2S.player.firstName + " " + t3p2S.player.lastName;
            t1ReceivedBoth.add(t3p2S);
        }


        int t2SizeAgain = t2Copy.draftedPlayersWithProj.size();
        double postSwapScoreT2 = t2Copy.scoreBestROSStartingLineup();
        improvementT2 = postSwapScoreT2 - initialScoreT2;
        if(improvementT2 < minTheirs){
            improvementT1 = -900.0;
            improvementT2 = -900.0;
            improvementT3 = -900.0;
            return;
        }

        // 5 of 6
        if(perm.get(4) == 'a'){
            t3Copy.addScore(t1p1S);
            t3ReceivedPlayers += t1p1S.player.firstName + " " + t1p1S.player.lastName;
            t1ReceivedBoth.add(t1p1S);
        }
        else if(perm.get(4) == 'b'){
            t3Copy.addScore(t1p2S);
            t3ReceivedPlayers += t1p2S.player.firstName + " " + t1p2S.player.lastName;
            t1ReceivedBoth.add(t1p2S);
        }
        else if(perm.get(4) == 'c'){
            t3Copy.addScore(t2p1S);
            t3ReceivedPlayers += t2p1S.player.firstName + " " + t2p1S.player.lastName;
            t1ReceivedBoth.add(t2p1S);
        }
        else if(perm.get(4) == 'd'){
            t3Copy.addScore(t2p2S);
            t3ReceivedPlayers += t2p2S.player.firstName + " " + t2p2S.player.lastName;
            t1ReceivedBoth.add(t2p2S);
        }
        else if(perm.get(4) == 'e'){
            t3Copy.addScore(t3p1S);
            t3ReceivedPlayers += t3p1S.player.firstName + " " + t3p1S.player.lastName;
            t1ReceivedBoth.add(t3p1S);
        }
        else if(perm.get(4) == 'f'){
            t3Copy.addScore(t3p2S);
            t3ReceivedPlayers += t3p2S.player.firstName + " " + t3p2S.player.lastName;
            t1ReceivedBoth.add(t3p2S);
        }
        // 6 of 6
        if(perm.get(5) == 'a'){
            t3Copy.addScore(t1p1S);
            t3ReceivedPlayers += t1p1S.player.firstName + " " + t1p1S.player.lastName;
            t1ReceivedBoth.add(t1p1S);
        }
        else if(perm.get(5) == 'b'){
            t3Copy.addScore(t1p2S);
            t3ReceivedPlayers += t1p2S.player.firstName + " " + t1p2S.player.lastName;
            t1ReceivedBoth.add(t1p2S);
        }
        else if(perm.get(5) == 'c'){
            t3Copy.addScore(t2p1S);
            t3ReceivedPlayers += t2p1S.player.firstName + " " + t2p1S.player.lastName;
            t1ReceivedBoth.add(t2p1S);
        }
        else if(perm.get(5) == 'd'){
            t3Copy.addScore(t2p2S);
            t3ReceivedPlayers += t2p2S.player.firstName + " " + t2p2S.player.lastName;
            t1ReceivedBoth.add(t2p2S);
        }
        else if(perm.get(5) == 'e'){
            t3Copy.addScore(t3p1S);
            t3ReceivedPlayers += t3p1S.player.firstName + " " + t3p1S.player.lastName;
            t1ReceivedBoth.add(t3p1S);
        }
        else if(perm.get(5) == 'f'){
            t3Copy.addScore(t3p2S);
            t3ReceivedPlayers += t3p2S.player.firstName + " " + t3p2S.player.lastName;
            t1ReceivedBoth.add(t3p2S);
        }

        int t3SizeAgain = t3Copy.draftedPlayersWithProj.size();
        double postSwapScoreT3 = t3Copy.scoreBestROSStartingLineup();
        improvementT3 = postSwapScoreT3 - initialScoreT3;

        if(improvementT3 < minTheirs){
            improvementT1 = -900.0;
            improvementT2 = -900.0;
            improvementT3 = -900.0;
            return;
        }
        t1Received1 = t1ReceivedBoth.get(0);
        t1Received2 = t1ReceivedBoth.get(1);
    }

    public static String printTradePreview(TradePreviewSerious3T tps){



        String banner = "-----------------------------------------------------\n";

        String team1ToGive = "my team gives:\t" + tps.t1p1Score.player.firstName + " " + tps.t1p1Score.player.lastName;
        team1ToGive += "\tand\t" + tps.t1p2Score.player.firstName + " " + tps.t1p2Score.player.lastName+ "\n";

        String team2ToGive = "team 2 gives:\t" + tps.t2p1Score.player.firstName + " " + tps.t2p1Score.player.lastName;
        team2ToGive += "\tand\t" + tps.t2p2Score.player.firstName + " " + tps.t2p2Score.player.lastName+ "\n";

        String team3ToGive = "team 3 gives:\t" + tps.t3p1Score.player.firstName + " " + tps.t3p1Score.player.lastName;
        team3ToGive += "\tand\t" + tps.t3p2Score.player.firstName + " " + tps.t3p2Score.player.lastName+ "\n";

        String team1ToReceive = "my team receives:\t" + tps.t1ReceivedPlayers + "\n";
        String team2ToReceive = "team 2 receives:\t" + tps.t2ReceivedPlayers + "\n";
        String team3ToReceive = "team 3 receives:\t" + tps.t3ReceivedPlayers + "\n";

        String pointChanges = "Your gain:\t" + tps.improvementT1 + "\n" + "Team 2 gain:\t" + tps.improvementT2 + "\n" + "Team 3 gain:\t" + tps.improvementT3 + "\n";

        if(tps.t2p3Score != null){
            //team2ToGive += "\tand\t" + tps.t1p3Score.player.firstName + " " + tps.t1p3Score.player.lastName;
            int u=1;
        }


        String toPrint = banner + team1ToGive + team2ToGive + team3ToGive + team1ToReceive + team2ToReceive + team3ToReceive + pointChanges + banner;
        //System.out.println(toPrint);
        return toPrint;
    }
}
