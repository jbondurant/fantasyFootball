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

        System.out.println("Given the projections on FantasyPros and your league settings");
        System.out.println("Best QBs are:\t" + qb1Name + "\t" + qb2Name + "\t" + qb3Name);
        System.out.println("Best RBs are:\t" + rb1Name + "\t" + rb2Name + "\t" + rb3Name);
        System.out.println("Best WRs are:\t" + wr1Name + "\t" + wr2Name + "\t" + wr3Name);
        System.out.println("Best TEs are:\t" + te1Name + "\t" + te2Name + "\t" + te3Name);
        System.out.println("Best DEFs are:\t" + def1Name + "\t" + def2Name + "\t" + def3Name);

        ArrayList<Score> scoreList = SleeperLeague.getScoreList(false);
        System.out.println("QB1 " + Player.scorePlayer(scoreList, bap.quarterbackRT1.player));
        System.out.println("QB2 " + Player.scorePlayer(scoreList, bap.quarterbackRT2.player));
        System.out.println("QB3 " + Player.scorePlayer(scoreList, bap.quarterbackRT3.player));

        System.out.println("RB1 " + Player.scorePlayer(scoreList, bap.runningBackRT1.player));
        System.out.println("RB2 " + Player.scorePlayer(scoreList, bap.runningBackRT2.player));
        System.out.println("RB3 " + Player.scorePlayer(scoreList, bap.runningBackRT3.player));


    }

}
