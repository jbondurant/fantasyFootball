import java.util.ArrayList;

public class LiveDraftInfo {

    boolean isFunLeague;
    ArrayList<Player> draftedPlayers;
    ArrayList<Player> rosterPlayers;
    BestAvailablePlayers bestAvailablePlayers;

    public LiveDraftInfo(ArrayList<Player> dp, ArrayList<Player> rp, boolean isFun){
        isFunLeague = isFun;
        draftedPlayers = dp;
        rosterPlayers = rp;
        bestAvailablePlayers = getBestAvailablePlayers(dp, isFun);
    }

    public static BestAvailablePlayers getBestAvailablePlayers(ArrayList<Player> draftedPlayers, boolean isFun){
        RankOrderedPlayers rop;
        if(isFun){
            rop = RankOrderedPlayers.getRankOrderedPlayerFPSerious();
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

    public static void LiveDraftPotentialMoveAnalyzer(LiveDraftInfo ldifb){

        boolean isFun = ldifb.isFunLeague;

        String bestQBFirstName = ldifb.bestAvailablePlayers.quarterbackRT1.player.firstName;
        String bestQBLastName = ldifb.bestAvailablePlayers.quarterbackRT1.player.lastName;
        String bestQBFullName = bestQBFirstName + " " + bestQBLastName;

        String bestRBFirstName = ldifb.bestAvailablePlayers.runningBackRT1.player.firstName;
        String bestRBLastName = ldifb.bestAvailablePlayers.runningBackRT1.player.lastName;
        String bestRBFullName = bestRBFirstName + " " + bestRBLastName;

        String bestWRFirstName = ldifb.bestAvailablePlayers.wideReceiverRT1.player.firstName;
        String bestWRLastName = ldifb.bestAvailablePlayers.wideReceiverRT1.player.lastName;
        String bestWRFullName = bestWRFirstName + " " + bestWRLastName;

        String bestTEFirstName = ldifb.bestAvailablePlayers.tightEndRT1.player.firstName;
        String bestTELastName = ldifb.bestAvailablePlayers.tightEndRT1.player.lastName;
        String bestTEFullName = bestTEFirstName + " " + bestTELastName;

        String bestDEFFirstName = ldifb.bestAvailablePlayers.defenseRT1.player.firstName;
        String bestDEFLastName = ldifb.bestAvailablePlayers.defenseRT1.player.lastName;
        String bestDEFFullName = bestDEFFirstName + " " + bestDEFLastName;

        System.out.println("Given the projections on FantasyPros and your league settings");

        System.out.println("Best QB is:\t" + bestQBFullName);

        System.out.println("Best RB is:\t" + bestRBFullName);

        System.out.println("Best WR is:\t" + bestWRFullName);

        System.out.println("Best TE is:\t" + bestTEFullName);

        System.out.println("Best DEF is:\t" + bestDEFFullName);
    }

}
