import java.util.ArrayList;
import java.util.Collections;

public class SimulationDraft {

    //TODO Here I need to initialize users eventually and give them strategies
    //I need a static string of my own ID
    //Do i need to add scoring field?, no cause thats in league within sleeperleague


    private static final int numRounds = 9;//TODO hardcoded
    private static final String myID = HumanOfInterest.humanID;

    public static SleeperLeague sleeperLeagueFun;
    public static SleeperLeague sleeperLeagueSerious;

    //bad
    /*static{
        sleeperLeagueFun = SleeperLeague.getFunLeague();
        sleeperLeagueSerious = SleeperLeague.getSeriousLeague();
    }*/



    SleeperLeague sleeperLeague;
    double scoreDraftHuman;

    public SimulationDraft(SleeperLeague sl, double sdh){
        sleeperLeague = sl;
        scoreDraftHuman = sdh;
    }

    public static SimulationDraft getFunSimulation(){
        SleeperLeague sl = SleeperLeague.getFunLeague();
        double scoreDraftHumanFun = runSimulationDraft(sl, true);
        return new SimulationDraft(sl, scoreDraftHumanFun);
    }

    public static SimulationDraft getSeriousSimulation(){
        SleeperLeague sl = SleeperLeague.getSeriousLeague();
        double scoreDraftHumanSerious = runSimulationDraft(sl, false);
        return new SimulationDraft(sl, scoreDraftHumanSerious);
    }

    public static SimulationDraft getSimulationPermPartial(ArrayList<Position> humanPermutation, ArrayList<Player> draftedPlayers, int roundsLeft, boolean isFun){
        SleeperLeague sl = null;

        if(isFun) {
            sl = SleeperLeague.getFunLeague();
        }
        else{
            sl = SleeperLeague.getSeriousLeague();
        }
        double scoreDraftHumanFun = runSimulationDraftPermPartial(sl, isFun, humanPermutation, draftedPlayers, roundsLeft);
        return new SimulationDraft(sl, scoreDraftHumanFun);
    }




    public static SimulationDraft getFunSimulationPerm(ArrayList<Position> humanPermutation){
        SleeperLeague sl = SleeperLeague.getFunLeague();
        double scoreDraftHumanFun = runSimulationDraftPerm(sl, true, humanPermutation);
        return new SimulationDraft(sl, scoreDraftHumanFun);
    }
    public static SimulationDraft getSeriousSimulationPerm(ArrayList<Position> humanPermutation){
        SleeperLeague sl = SleeperLeague.getSeriousLeague();
        double scoreDraftHumanSerious = runSimulationDraftPerm(sl, false, humanPermutation);
        return new SimulationDraft(sl, scoreDraftHumanSerious);
    }

    //TODO score keeps increasing after 6th run
    public static double runSimulationDraft(SleeperLeague sl, boolean isFun){

        for(User user : sl.sleeperDraftInfo.usersInfo){
            if(user.userID.equals(myID)){
                if(isFun) {
                    user.strategy = HumanStrategy.getFPProjectionHumanStrategyFun();
                }
                else{
                    user.strategy = HumanStrategy.getFPProjectionHumanStrategySerious();
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

        //TODO check new addition is ok
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
        //end new patch addition to fix stdev mushing together

        ArrayList<User> usersAtDraft = sl.sleeperDraftInfo.usersInfo;

        for(int i=1; i<=numRounds; i++){

            Collections.sort(usersAtDraft, new UserComparator());



            for(User user : usersAtDraft){
                Player draftedPlayer = user.strategy.selectPlayer();//moved this up
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

        PrintDraftCSV();

        double scoreDraftHuman = SleeperLeague.scoreSleeperDraft(sl, isFun);
        return scoreDraftHuman;
    }


    public static double runSimulationDraftPerm(SleeperLeague sl, boolean isFun, ArrayList<Position> humanPerm){

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

        ArrayList<User> usersAtDraft = sl.sleeperDraftInfo.usersInfo;

        for(int i=1; i<=numRounds; i++){
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

        PrintDraftCSV();

        double scoreDraftHuman = SleeperLeague.scoreSleeperDraft(sl, isFun);
        return scoreDraftHuman;
    }



    public static void PrintDraftCSV(){

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

        //
        //
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
        // not even patched everywhere
        //end new patch addition to fix stdev mushing together


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

        PrintDraftCSV();

        double scoreDraftHuman = SleeperLeague.scoreSleeperDraft(sl, isFun);
        return scoreDraftHuman;
    }



//TODO report instead of print
    public static void main(String[] args){
        SimulationDraft simulationDraftFun = getFunSimulation();
        SimulationDraft simulationDraftSerious = getSeriousSimulation();
        System.out.println(simulationDraftFun.scoreDraft(true));
        System.out.println(simulationDraftSerious.scoreDraft(false));

    }

}
