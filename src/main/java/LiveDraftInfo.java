import java.util.ArrayList;

public class LiveDraftInfo {

    boolean isFunLeague;
    ArrayList<Player> draftedPlayers;
    ArrayList<Player> rosterPlayers;
    BestAvailablePlayers bestAvailablePlayers;
    BestAvailablePlayers bestAvailablePlayersByHardcodedRank;

    public LiveDraftInfo(ArrayList<Player> dp, ArrayList<Player> rp, boolean isFun){
        isFunLeague = isFun;
        draftedPlayers = dp;
        rosterPlayers = rp;
        bestAvailablePlayers = getBestAvailablePlayers(dp, isFun);
        bestAvailablePlayersByHardcodedRank = getBestAvailablePlayersByHardcodedRank(dp);
    }

    public static BestAvailablePlayers getBestAvailablePlayersByHardcodedRank(ArrayList<Player> draftedPlayers){
        RankOrderedPlayers rop = RankOrderedPlayers.getRankOrderedPlayerHardcodedExperts();
        for(Player player : draftedPlayers){
            rop.removePlayer(player);
        }
        BestAvailablePlayers bap = new BestAvailablePlayers(rop);
        return bap;
    }

    public static BestAvailablePlayers getBestAvailablePlayers(ArrayList<Player> draftedPlayers, boolean isFun){
        RankOrderedPlayers rop;
        if(isFun){
            rop = RankOrderedPlayers.getRankOrderedPlayerFPFun();
        }
        else{
            rop = RankOrderedPlayers.getRankOrderedPlayerFPSerious();
        }

        for(Player player : draftedPlayers){
            rop.removePlayer(player);
        }

        BestAvailablePlayers bap = new BestAvailablePlayers(rop);
        return bap;
    }

    public static void LiveDraftPotentialMoveAnalyzer(BestAvailablePlayers bap){
        boolean isFun = false;
        String qb1Name = bap.quarterbackRT1.player.firstName + " " + bap.quarterbackRT1.player.lastName;
        String rb1Name = bap.runningBackRT1.player.firstName + " " + bap.runningBackRT1.player.lastName;
        String wr1Name = bap.wideReceiverRT1.player.firstName + " " + bap.wideReceiverRT1.player.lastName;
        String te1Name = bap.tightEndRT1.player.firstName + " " + bap.tightEndRT1.player.lastName;
        String def1Name = bap.defenseRT1.player.firstName + " " + bap.defenseRT1.player.lastName;

        String qb2Name = bap.quarterbackRT2.player.firstName + " " + bap.quarterbackRT2.player.lastName;
        String rb2Name = bap.runningBackRT2.player.firstName + " " + bap.runningBackRT2.player.lastName;
        String wr2Name = bap.wideReceiverRT2.player.firstName + " " + bap.wideReceiverRT2.player.lastName;
        String te2Name = bap.tightEndRT2.player.firstName + " " + bap.tightEndRT2.player.lastName;
        String def2Name = bap.defenseRT2.player.firstName + " " + bap.defenseRT2.player.lastName;

        String qb3Name = bap.quarterbackRT3.player.firstName + " " + bap.quarterbackRT3.player.lastName;
        String rb3Name = bap.runningBackRT3.player.firstName + " " + bap.runningBackRT3.player.lastName;
        String wr3Name = bap.wideReceiverRT3.player.firstName + " " + bap.wideReceiverRT3.player.lastName;
        String te3Name = bap.tightEndRT3.player.firstName + " " + bap.tightEndRT3.player.lastName;
        String def3Name = bap.defenseRT3.player.firstName + " " + bap.defenseRT3.player.lastName;

        ArrayList<Score> scoreList = SleeperLeague.getScoreList();
        int qb1ScoreRound = (int) Player.scorePlayer(scoreList, bap.quarterbackRT1.player);
        int qb2ScoreRound = (int) Player.scorePlayer(scoreList, bap.quarterbackRT2.player);
        int qb3ScoreRound = (int) Player.scorePlayer(scoreList, bap.quarterbackRT3.player);
        String qbScores = "\t\t" + qb1ScoreRound + "\t\t" + qb2ScoreRound + "\t\t" + qb3ScoreRound;
        int rb1ScoreRound = (int) Player.scorePlayer(scoreList, bap.runningBackRT1.player);
        int rb2ScoreRound = (int) Player.scorePlayer(scoreList, bap.runningBackRT2.player);
        int rb3ScoreRound = (int) Player.scorePlayer(scoreList, bap.runningBackRT3.player);
        String rbScores = "\t\t" + rb1ScoreRound + "\t\t" + rb2ScoreRound + "\t\t" + rb3ScoreRound;


        int wr1ScoreRound = (int) Player.scorePlayer(scoreList, bap.wideReceiverRT1.player);
        int wr2ScoreRound = (int) Player.scorePlayer(scoreList, bap.wideReceiverRT2.player);
        int wr3ScoreRound = (int) Player.scorePlayer(scoreList, bap.wideReceiverRT3.player);
        String wrScores = "\t\t" + wr1ScoreRound + "\t\t" + wr2ScoreRound + "\t\t" + wr3ScoreRound;

        int te1ScoreRound = (int) Player.scorePlayer(scoreList, bap.tightEndRT1.player);
        int te2ScoreRound = (int) Player.scorePlayer(scoreList, bap.tightEndRT2.player);
        int te3ScoreRound = (int) Player.scorePlayer(scoreList, bap.tightEndRT3.player);
        String teScores = "\t\t" + te1ScoreRound + "\t\t" + te2ScoreRound + "\t\t" + te3ScoreRound;


        System.out.println(qb1Name + "\t" + qb2Name + "\t" + qb3Name + qbScores);
        System.out.println(rb1Name + "\t" + rb2Name + "\t" + rb3Name + rbScores);
        System.out.println(wr1Name + "\t" + wr2Name + "\t" + wr3Name + wrScores);
        System.out.println(te1Name + "\t" + te2Name + "\t" + te3Name + teScores);
        System.out.println(def1Name + "\t" + def2Name + "\t" + def3Name);






    }

}
