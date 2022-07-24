import java.util.ArrayList;
import java.util.Collections;

public class SimulationDraft {

    //TODO Here I need to initialize users eventually and give them strategies
    private static final String myID = HumanOfInterest.humanID;
    SleeperLeague sleeperLeague;
    double scoreDraftHuman;

    public SimulationDraft(SleeperLeague sl, double sdh){
        sleeperLeague = sl;
        scoreDraftHuman = sdh;
    }

    public static SimulationDraft getSimulationPermPartial(ArrayList<Position> humanPermutation, ArrayList<Player> draftedPlayers, int roundsLeft, boolean isFun){
        SleeperLeague sl;
        if(isFun) {
            sl = SleeperLeague.getFunLeague();
        }
        else{
            sl = SleeperLeague.getSeriousLeague();
        }
        double scoreDraftHumanFun = runSimulationDraftPermPartial(sl, isFun, humanPermutation, draftedPlayers, roundsLeft);
        return new SimulationDraft(sl, scoreDraftHumanFun);
    }

    public double scoreDraft(boolean isFun){
        ArrayList<Score> scoreList = SleeperLeague.getSeriousScoreList();
        if(isFun){
            scoreList = SleeperLeague.getFunScoreList();
        }

        double totalScore = 0;
        for(User user : sleeperLeague.sleeperDraftInfo.usersInfo) {
            if (user.userID.equals(myID)) {
                Roster roster = user.roster;
                for(Player player : roster.draftedPlayers){
                    double playerScore = Player.scorePlayer(scoreList, player);
                    totalScore += playerScore;
                }
            }
        }
        return totalScore;
    }

    public static double runSimulationDraftPermPartial(SleeperLeague sl, boolean isFun, ArrayList<Position> humanPerm, ArrayList<Player> draftedPlayers, int roundsLeft){
        for(User user : sl.sleeperDraftInfo.usersInfo){
            if(user.userID.equals(myID)){
                if(isFun) {
                    user.strategy = HumanStrategy.getFPHumanStrategyFunFromPerm(humanPerm);
                }
                else{
                    user.strategy = HumanStrategy.getFPHumanStrategySeriousFromPerm(humanPerm);
                    int y=1;
                }
            }
            else{
                if(isFun) {
                    user.setStrategy(StrategyBot.getSleeperFunStrategy());
                }
                else{
                    user.setStrategy(StrategyBot.getSleeperSeriousStrategy());
                }
            }
        }
        StrategyBot commonBotStrategy;
        if(sl.sleeperDraftInfo.usersInfo.get(0).userID.equals(myID)) {
            commonBotStrategy = (StrategyBot) sl.sleeperDraftInfo.usersInfo.get(1).strategy;
        }
        else{
            commonBotStrategy = (StrategyBot) sl.sleeperDraftInfo.usersInfo.get(0).strategy;
        }
        for(User user : sl.sleeperDraftInfo.usersInfo){
            if(!user.userID.equals(myID)){
                user.strategy = commonBotStrategy;
            }
        }
        for(User user : sl.sleeperDraftInfo.usersInfo){
            for(Player player : draftedPlayers){
                user.strategy.removeDraftedPlayer(player);
            }
        }
        ArrayList<User> usersAtDraft = sl.sleeperDraftInfo.usersInfo;
        for(int i=1; i<= roundsLeft; i++){
            Collections.sort(usersAtDraft, new UserComparator());
            //System.out.println("Round:\t" + i);
            for(User user : usersAtDraft){
                Player draftedPlayer = user.strategy.selectPlayer();//moved this up
                //System.out.println(draftedPlayer.firstName + " " + draftedPlayer.lastName);
                if(user.userID.equals(myID)){
                    HumanStrategy myStrategy = (HumanStrategy) user.strategy;
                }
                user.addToRoster(draftedPlayer);
                for(User userToAlert : usersAtDraft){
                    //should be fine even with user who just drafted him;
                    userToAlert.strategy.removeDraftedPlayer(draftedPlayer);
                }
            }
            Collections.reverse(usersAtDraft);
        }
        Collections.sort(usersAtDraft, new UserComparator());
        double scoreDraftHuman = SleeperLeague.scoreSleeperDraft(sl, isFun);
        return scoreDraftHuman;
    }
}
