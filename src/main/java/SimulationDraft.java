import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

public class SimulationDraft {

    //TODO Here I need to initialize users eventually and give them strategies
    private static String myID(){
        return HumanOfInterest.humanID();
    }
    SleeperLeague sleeperLeague;
    double scoreDraftHuman;

    public SimulationDraft(SleeperLeague sl, double sdh){
        sleeperLeague = sl;
        scoreDraftHuman = sdh;
    }

    public static SimulationDraft getSimulationPermPartialForKeeperSerious(Keeper keeper, ArrayList<Position> humanPermutation, ArrayList<Player> draftedPlayers, int roundsLeft, int qbADPChange){
        SleeperLeague sl = SleeperLeague.getSeriousLeague();
        ArrayList<User> xyz = sl.sleeperDraftInfo.usersInfo;
        double scoreDraftHuman = runSimulationDraftPermPartialForKeeperSerious(keeper, sl, humanPermutation, draftedPlayers, roundsLeft, qbADPChange);
        return new SimulationDraft(sl, scoreDraftHuman);
    }

    public static SimulationDraft getSimulationPermPartialWithHardcodedKeepers(HashSet<Keeper> keepers, ArrayList<Position> humanPermutation, ArrayList<Player> draftedPlayers, int roundsLeft, int qbADPChange, ArrayList<Keeper> leagueWideKeepers){
        SleeperLeague sl = SleeperLeague.getSeriousLeague();
        ArrayList<User> xyz = sl.sleeperDraftInfo.usersInfo;
        double scoreDraftHuman = runSimulationDraftPermPartialWithHardcodedKeepers(keepers, sl, humanPermutation, draftedPlayers, roundsLeft, qbADPChange, leagueWideKeepers);
        return new SimulationDraft(sl, scoreDraftHuman);
    }

    public static SimulationDraft getSimulationPermPartial(ArrayList<Position> humanPermutation, ArrayList<Player> draftedPlayers, int roundsLeft, boolean isFun, int qbADPChange){
        SleeperLeague sl = SleeperLeague.getSeriousLeague();
        double scoreDraftHumanFun = runSimulationDraftPermPartial(sl, isFun, humanPermutation, draftedPlayers, roundsLeft, qbADPChange);
        return new SimulationDraft(sl, scoreDraftHumanFun);
    }

    public double scoreDraft(){
        ArrayList<Score> scoreList = SleeperLeague.getScoreList();
        double totalScore = 0;
        for(User user : sleeperLeague.sleeperDraftInfo.usersInfo) {
            if (user.userID.equals(myID())) {
                Roster roster = user.roster;
                for(Player player : roster.draftedPlayers){
                    double playerScore = Player.scorePlayer(scoreList, player);
                    totalScore += playerScore;
                }
            }
        }
        return totalScore;
    }


    /**
     * Clears every roster before a simulated draft.
     *
     * SleeperLeague caches its parsed league, so the User objects persist
     * between simulations, and addToRoster only ever appends. Without this the
     * second simulation scores a roster holding both drafts' picks, the third
     * holds three, and the Monte Carlo average climbs with the number of runs
     * instead of converging. Measured before the fix: 10 players, then 20,
     * then 30.
     */
    private static void resetRostersForNewSimulation(SleeperLeague sl){
        for(User user : sl.sleeperDraftInfo.usersInfo){
            user.setRoster(new Roster());
        }
    }

    public static double runSimulationDraftPermPartialForKeeperSerious(Keeper keeper, SleeperLeague sl, ArrayList<Position> humanPerm, ArrayList<Player> draftedPlayers, int roundsLeft, int qbADPChange){
        resetRostersForNewSimulation(sl);
        boolean isFun = false;
        for(User user : sl.sleeperDraftInfo.usersInfo){
            if(user.userID.equals(myID())){
                //todo, i think this should work, but this area could also cause problems
                user.strategy = HumanStrategy.getFPHumanStrategySeriousFromPerm(humanPerm);
            }
            else{
                user.setStrategy(StrategyBot.getSleeperSeriousStrategy(qbADPChange));
            }
        }
        StrategyBot commonBotStrategy;
        if(sl.sleeperDraftInfo.usersInfo.get(0).userID.equals(myID())) {
            commonBotStrategy = (StrategyBot) sl.sleeperDraftInfo.usersInfo.get(1).strategy;
        }
        else{
            commonBotStrategy = (StrategyBot) sl.sleeperDraftInfo.usersInfo.get(0).strategy;
        }
        for(User user : sl.sleeperDraftInfo.usersInfo){
            if(!user.userID.equals(myID())){
                user.strategy = commonBotStrategy;
            }
        }
        for(User user : sl.sleeperDraftInfo.usersInfo){
            for(Player player : draftedPlayers){
                user.strategy.removeDraftedPlayer(player);
            }
            user.strategy.removeDraftedPlayer(keeper.player);
        }
        ArrayList<User> usersAtDraft = sl.sleeperDraftInfo.usersInfo;
        for(int i=1; i<= roundsLeft; i++){
            Collections.sort(usersAtDraft, new UserComparator());
            for(User user : usersAtDraft){
                Player draftedPlayer;
                if(user.userID.equals(myID()) && i==keeper.roundCanBeKept){
                    draftedPlayer = keeper.player;
                    user.addToRoster(draftedPlayer);
                }
                else {
                    draftedPlayer = user.strategy.selectPlayer();
                    if(draftedPlayer == null){
                        continue;  // board exhausted
                    }
                    user.addToRoster(draftedPlayer);
                }
                for (User userToAlert : usersAtDraft) {
                    userToAlert.strategy.removeDraftedPlayer(draftedPlayer);
                }
            }
            Collections.reverse(usersAtDraft);
        }
        Collections.sort(usersAtDraft, new UserComparator());
        double scoreDraftHuman = SleeperLeague.scoreSleeperDraft(sl, isFun);
        return scoreDraftHuman;
    }

    public static HashSet<Integer> getRoundsOfKeepers(HashSet<Keeper> keepers){
        HashSet<Integer> rounds = new HashSet<>();
        for(Keeper keeper : keepers){
            rounds.add(keeper.roundCanBeKept);
        }
        return rounds;
    }

    public static Player getKeeperPlayerAtRound(HashSet<Keeper> keepers, Integer i){
        for(Keeper keeper : keepers){
            if(keeper.roundCanBeKept == i){
                return keeper.player;
            }
        }
        throw new RuntimeException("keeper round not matching");
    }

    public static double runSimulationDraftPermPartialWithHardcodedKeepers(HashSet<Keeper> keepers, SleeperLeague sl, ArrayList<Position> humanPerm, ArrayList<Player> draftedPlayers, int roundsLeft, int qbADPChange, ArrayList<Keeper> leagueWideKeepers){
        resetRostersForNewSimulation(sl);
        boolean isFun = false;
        for(User user : sl.sleeperDraftInfo.usersInfo){
            if(user.userID.equals(myID())){
                //todo, i think this should work, but this area could also cause problems
                user.strategy = HumanStrategy.getFPHumanStrategySeriousFromPerm(humanPerm);;
            }
            else{
                user.setStrategy(StrategyBot.getSleeperSeriousStrategy(qbADPChange));
            }
        }
        StrategyBot commonBotStrategy;
        if(sl.sleeperDraftInfo.usersInfo.get(0).userID.equals(myID())) {
            commonBotStrategy = (StrategyBot) sl.sleeperDraftInfo.usersInfo.get(1).strategy;
        }
        else{
            commonBotStrategy = (StrategyBot) sl.sleeperDraftInfo.usersInfo.get(0).strategy;
        }
        for(User user : sl.sleeperDraftInfo.usersInfo){
            if(!user.userID.equals(myID())){
                user.strategy = commonBotStrategy;
            }
        }
        for(User user : sl.sleeperDraftInfo.usersInfo){
            for(Player player : draftedPlayers){
                user.strategy.removeDraftedPlayer(player);
            }
            for(Keeper keeper : keepers) {
                user.strategy.removeDraftedPlayer(keeper.player);
            }
            // Everyone else's keepers are off the board too. This list was
            // being passed in and ignored, so all eleven other managers' keepers
            // sat in the pool - the bots drafted them, my simulated team could
            // draft them, and every run was optimistic about who was available.
            if(leagueWideKeepers != null){
                for(Keeper keeper : leagueWideKeepers){
                    user.strategy.removeDraftedPlayer(keeper.player);
                }
            }
        }
        HashSet<Integer> roundsWithKeeper = getRoundsOfKeepers(keepers);
        ArrayList<User> usersAtDraft = sl.sleeperDraftInfo.usersInfo;
        for(int i=1; i<= roundsLeft; i++){
            Collections.sort(usersAtDraft, new UserComparator());
            for(User user : usersAtDraft){
                Player draftedPlayer;
                if(user.userID.equals(myID()) && roundsWithKeeper.contains(i)){
                    draftedPlayer = getKeeperPlayerAtRound(keepers, i);
                    user.addToRoster(draftedPlayer);
                }
                else {
                    draftedPlayer = user.strategy.selectPlayer();
                    if(draftedPlayer == null){
                        continue;  // board exhausted
                    }
                    user.addToRoster(draftedPlayer);
                }
                for (User userToAlert : usersAtDraft) {
                    userToAlert.strategy.removeDraftedPlayer(draftedPlayer);
                }
            }
            Collections.reverse(usersAtDraft);
        }
        Collections.sort(usersAtDraft, new UserComparator());
        double scoreDraftHuman = SleeperLeague.scoreSleeperDraft(sl, isFun);
        return scoreDraftHuman;
    }



    public static double runSimulationDraftPermPartial(SleeperLeague sl, boolean isFun, ArrayList<Position> humanPerm, ArrayList<Player> draftedPlayers, int roundsLeft, int qbADPChange){
        resetRostersForNewSimulation(sl);
        for(User user : sl.sleeperDraftInfo.usersInfo){
            if(user.userID.equals(myID())){
                user.strategy = HumanStrategy.getFPHumanStrategySeriousFromPerm(humanPerm);
            }
            else{
                    user.setStrategy(StrategyBot.getSleeperSeriousStrategy(qbADPChange));
            }
        }
        StrategyBot commonBotStrategy;
        if(sl.sleeperDraftInfo.usersInfo.get(0).userID.equals(myID())) {
            commonBotStrategy = (StrategyBot) sl.sleeperDraftInfo.usersInfo.get(1).strategy;
        }
        else{
            commonBotStrategy = (StrategyBot) sl.sleeperDraftInfo.usersInfo.get(0).strategy;
        }
        for(User user : sl.sleeperDraftInfo.usersInfo){
            if(!user.userID.equals(myID())){
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
                if(user.userID.equals(myID())){
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
