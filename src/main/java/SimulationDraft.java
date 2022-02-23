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
    DraftReport draftReport;

    public SimulationDraft(SleeperLeague sl, DraftReport dr){
        sleeperLeague = sl;
        draftReport = dr;
    }

    public static SimulationDraft getFunSimulation(){
        SleeperLeague sl = SleeperLeague.getFunLeague();
        DraftReport draftReportFun = runSimulationDraft(sl, true);
        return new SimulationDraft(sl, draftReportFun);
    }

    public static SimulationDraft getSeriousSimulation(){
        SleeperLeague sl = SleeperLeague.getSeriousLeague();
        DraftReport draftReportSerious = runSimulationDraft(sl, false);
        return new SimulationDraft(sl, draftReportSerious);
    }

    public static SimulationDraft getFunSimulationPermPartial(ArrayList<Position> humanPermutation, ArrayList<Player> draftedPlayers, int roundsLeft){
        SleeperLeague sl = SleeperLeague.getFunLeague();
        DraftReport draftReportFun = runSimulationDraftPermPartial(sl, true, humanPermutation, draftedPlayers, roundsLeft);
        return new SimulationDraft(sl, draftReportFun);
    }

    public static SimulationDraft getFunSimulationPerm(ArrayList<Position> humanPermutation){
        SleeperLeague sl = SleeperLeague.getFunLeague();
        DraftReport draftReportFun = runSimulationDraftPerm(sl, true, humanPermutation);
        return new SimulationDraft(sl, draftReportFun);
    }
    public static SimulationDraft getSeriousSimulationPerm(ArrayList<Position> humanPermutation){
        SleeperLeague sl = SleeperLeague.getSeriousLeague();
        DraftReport draftReportSerious = runSimulationDraftPerm(sl, false, humanPermutation);
        return new SimulationDraft(sl, draftReportSerious);
    }

    //TODO score keeps increasing after 6th run
    public static DraftReport runSimulationDraft(SleeperLeague sl, boolean isFun){

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

        ArrayList<RoundReport> roundReports = new ArrayList<RoundReport>();

        for(int i=1; i<=numRounds; i++){

            Collections.sort(usersAtDraft, new UserComparator());



            for(User user : usersAtDraft){
                Player draftedPlayer = user.strategy.selectPlayer();//moved this up
                if(user.userID.equals(myID)){
                    HumanStrategy myStrategy = (HumanStrategy) user.strategy;
                    int[] topTiers = HumanStrategy.getTopTiers(myStrategy);
                    //used to be subList(0, i-1) but cause arrangements to be off by 1 for my database
                    ArrayList<Position> partialArrangement = new ArrayList<Position>(myStrategy.initialPositionDraftOrder.subList(0,i));
                    RoundReport roundReport = new RoundReport(partialArrangement, topTiers);
                    roundReports.add(roundReport);
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

        DraftReport draftReport = new DraftReport(roundReports);
        double scoreDraftHuman = SleeperLeague.scoreSleeperDraft(sl, isFun);
        draftReport.setEndScoreAll(scoreDraftHuman);
        return draftReport;
    }


    public static DraftReport runSimulationDraftPerm(SleeperLeague sl, boolean isFun, ArrayList<Position> humanPerm){

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

        ArrayList<RoundReport> roundReports = new ArrayList<RoundReport>();
        for(int i=1; i<=numRounds; i++){
            Collections.sort(usersAtDraft, new UserComparator());


            //System.out.println("Round:\t" + i);
            for(User user : usersAtDraft){
                Player draftedPlayer = user.strategy.selectPlayer();//moved this up
                //System.out.println(draftedPlayer.firstName + " " + draftedPlayer.lastName);
                if(user.userID.equals(myID)){
                    HumanStrategy myStrategy = (HumanStrategy) user.strategy;
                    int[] topTiers = HumanStrategy.getTopTiers(myStrategy);
                    //used to be subList(0, i-1) but cause arrangements to be off by 1 for my database
                    ArrayList<Position> partialArrangement = new ArrayList<Position>(myStrategy.initialPositionDraftOrder.subList(0,i));
                    RoundReport roundReport = new RoundReport(partialArrangement, topTiers);
                    roundReports.add(roundReport);
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

        DraftReport draftReport = new DraftReport(roundReports);
        double scoreDraftHuman = SleeperLeague.scoreSleeperDraft(sl, isFun);
        draftReport.setEndScoreAll(scoreDraftHuman);
        return draftReport;
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





    public static DraftReport runSimulationDraftPermPartial(SleeperLeague sl, boolean isFun, ArrayList<Position> humanPerm, ArrayList<Player> draftedPlayers, int roundsLeft){

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

        ArrayList<RoundReport> roundReports = new ArrayList<RoundReport>();
        for(int i=1; i<= roundsLeft; i++){
            Collections.sort(usersAtDraft, new UserComparator());


            //System.out.println("Round:\t" + i);
            for(User user : usersAtDraft){
                Player draftedPlayer = user.strategy.selectPlayer();//moved this up
                //System.out.println(draftedPlayer.firstName + " " + draftedPlayer.lastName);
                if(user.userID.equals(myID)){
                    HumanStrategy myStrategy = (HumanStrategy) user.strategy;
                    int[] topTiers = HumanStrategy.getTopTiers(myStrategy);
                    //used to be subList(0, i-1) but cause arrangements to be off by 1 for my database
                    ArrayList<Position> partialArrangement = new ArrayList<Position>(myStrategy.initialPositionDraftOrder.subList(0,i));
                    RoundReport roundReport = new RoundReport(partialArrangement, topTiers);
                    roundReports.add(roundReport);
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

        DraftReport draftReport = new DraftReport(roundReports);
        double scoreDraftHuman = SleeperLeague.scoreSleeperDraft(sl, isFun);
        draftReport.setEndScoreAll(scoreDraftHuman);
        return draftReport;
    }



//TODO report instead of print
    public static void main(String[] args){
        SimulationDraft simulationDraftFun = getFunSimulation();
        SimulationDraft simulationDraftSerious = getSeriousSimulation();
        System.out.println(simulationDraftFun.scoreDraft(true));
        System.out.println(simulationDraftSerious.scoreDraft(false));

    }

}
