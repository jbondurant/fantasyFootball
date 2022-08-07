import org.checkerframework.checker.units.qual.A;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.PriorityQueue;
import java.util.Random;

public class OnTheFlySimulationRunner {

    public static void runDraftsOnTheFlyToChooseMyKeeperHardcoded(int n, ArrayList<Position> humanPermutationOld, LiveDraftInfo ldifb, String userID, boolean allowUndrafted, int undraftedRoundCost, int qbADPChange, int minKeeperRound) throws Exception {
        boolean isFun = false;
        Keepers keepers = Keepers.getKeepersForUserHardcoded(isFun, userID, true, undraftedRoundCost);
        Keepers keepersWithoutUndrafted = Keepers.getKeepersForUserHardcoded(isFun, userID, false, undraftedRoundCost);
        ArrayList<Score> allKeeperScores = new ArrayList<>();
        for(Keeper keeper : keepers.keepers) {
            int numRoundsLeft = 10;
            ArrayList<Position> oldHumanPermutationReduced = new ArrayList<>();
            for(Position p : humanPermutationOld){
                oldHumanPermutationReduced.add(p);
            }
            Position keeperPosition = keeper.player.position;
            oldHumanPermutationReduced.remove(keeperPosition);
            double numSimsDouble = n * 1.0;

            BigDecimal totalScoreQB = BigDecimal.ZERO;
            BigDecimal totalScoreRB = BigDecimal.ZERO;
            BigDecimal totalScoreWR = BigDecimal.ZERO;
            BigDecimal totalScoreTE = BigDecimal.ZERO;
            BigDecimal totalNumSims = BigDecimal.valueOf(numSimsDouble);

            for (int i = 0; i < n; i++) {
                Collections.shuffle(oldHumanPermutationReduced);
                ArrayList<Position> copy1 = new ArrayList<>();
                ArrayList<Position> copy2 = new ArrayList<>();
                ArrayList<Position> copy3 = new ArrayList<>();
                ArrayList<Position> copy4 = new ArrayList<>();
                for (Position pos : oldHumanPermutationReduced) {
                    copy1.add(pos);
                    copy2.add(pos);
                    copy3.add(pos);
                    copy4.add(pos);
                }
                copy1.remove(Position.QB);
                copy2.remove(Position.RB);
                copy3.remove(Position.WR);
                copy4.remove(Position.TE);
                ArrayList<Position> humanPermutationReducedRandom = oldHumanPermutationReduced;

                ArrayList<Position> humanPermutationPickQB = new ArrayList<>();
                humanPermutationPickQB.add(Position.QB);
                ArrayList<Position> humanPermutationPickRB = new ArrayList<>();
                humanPermutationPickRB.add(Position.RB);
                ArrayList<Position> humanPermutationPickWR = new ArrayList<>();
                humanPermutationPickWR.add(Position.WR);
                ArrayList<Position> humanPermutationPickTE = new ArrayList<>();
                humanPermutationPickTE.add(Position.TE);
                for(Position pos : copy1){
                    humanPermutationPickQB.add(pos);
                }
                for(Position pos : copy2){
                    humanPermutationPickRB.add(pos);
                }
                for(Position pos : copy3){
                    humanPermutationPickWR.add(pos);
                }
                for(Position pos : copy4){
                    humanPermutationPickTE.add(pos);
                }

                if(humanPermutationReducedRandom.contains(Position.QB)) {
                    SimulationDraft simDraftQB = SimulationDraft.getSimulationPermPartialForKeeperSerious(keeper, humanPermutationPickQB, ldifb.draftedPlayers, numRoundsLeft, qbADPChange);
                    double draftScoreQB = simDraftQB.scoreDraft(isFun);
                    totalScoreQB = totalScoreQB.add(BigDecimal.valueOf(draftScoreQB));
                }

                if(humanPermutationReducedRandom.contains(Position.RB)) {
                    SimulationDraft simDraftRB = SimulationDraft.getSimulationPermPartialForKeeperSerious(keeper, humanPermutationPickRB, ldifb.draftedPlayers, numRoundsLeft, qbADPChange);
                    double draftScoreRB = simDraftRB.scoreDraft(isFun);
                    totalScoreRB = totalScoreRB.add(BigDecimal.valueOf(draftScoreRB));
                }
                if(humanPermutationReducedRandom.contains(Position.WR)) {
                    SimulationDraft simDraftWR = SimulationDraft.getSimulationPermPartialForKeeperSerious(keeper, humanPermutationPickWR, ldifb.draftedPlayers, numRoundsLeft, qbADPChange);
                    double draftScoreWR = simDraftWR.scoreDraft(isFun);
                    totalScoreWR = totalScoreWR.add(BigDecimal.valueOf(draftScoreWR));
                }
                if(humanPermutationReducedRandom.contains(Position.TE)) {
                    SimulationDraft simDraftTE = SimulationDraft.getSimulationPermPartialForKeeperSerious(keeper, humanPermutationPickTE, ldifb.draftedPlayers, numRoundsLeft, qbADPChange);
                    double draftScoreTE = simDraftTE.scoreDraft(isFun);
                    totalScoreTE = totalScoreTE.add(BigDecimal.valueOf(draftScoreTE));
                }
            }
            double avScoreQB = totalScoreQB.divide(totalNumSims, RoundingMode.HALF_UP).doubleValue();
            double avScoreRB = totalScoreRB.divide(totalNumSims, RoundingMode.HALF_UP).doubleValue();
            double avScoreWR = totalScoreWR.divide(totalNumSims, RoundingMode.HALF_UP).doubleValue();
            double avScoreTE = totalScoreTE.divide(totalNumSims, RoundingMode.HALF_UP).doubleValue();

            String keeperName = keeper.player.firstName + " " + keeper.player.lastName;
            double bestScoreQBRB = Math.max(avScoreQB, avScoreRB);
            double bestScoreWRTE = Math.max(avScoreWR, avScoreTE);
            double bestPosScore = Math.max(bestScoreQBRB, bestScoreWRTE);
            Score score = new Score(bestPosScore, keeper.player);
            allKeeperScores.add(score);
            //System.out.println("Pick " + keeperName + " to score:\t" + bestPosScore);
        }
        PriorityQueue<Score> playersInBestScoreOrder = new PriorityQueue<Score>(5, new ScoreComparator());
        for(Score s : allKeeperScores){
            playersInBestScoreOrder.add(s);
        }
        while(playersInBestScoreOrder.size() != 0){
            Score s = playersInBestScoreOrder.poll();
            boolean keeperIsUndrafted = true;
            for(Keeper k : keepersWithoutUndrafted.keepers){
                if(k.player.sleeperID == s.player.sleeperID){
                    keeperIsUndrafted = false;
                }
            }
            String keeperName = "";
            if(keeperIsUndrafted){
                keeperName += "*";
            }
            int keeperRound = 0;
            for(Keeper k : keepers.keepers){
                if(k.player.equals(s.player)){
                    keeperRound = k.roundCanBeKept;
                }
            }
            if(keeperRound < minKeeperRound){
                keeperName += "xxxxx";
            }
            keeperName += s.player.firstName + " " + s.player.lastName;
            System.out.println(keeperName + " :\t" + s.score + "\t" + keeperRound + "\t" + s.player.sleeperID);
        }

    }

    public static void runDraftsOnTheFly(int n, int roundPick, boolean isFun, ArrayList<Position> humanPermutationOld, LiveDraftInfo ldifb, int qbADPChange) {
        int numRoundsTotal = 10;
        int numRoundsLeft = numRoundsTotal - roundPick + 1;
        ArrayList<Position> oldHumanPermutationReduced = humanPermutationOld;
        for(Player player : ldifb.rosterPlayers){
            Position pos = player.position;
            oldHumanPermutationReduced.remove(pos);
        }
        BigDecimal totalScoreQB = BigDecimal.ZERO;
        BigDecimal totalScoreRB = BigDecimal.ZERO;
        BigDecimal totalScoreWR = BigDecimal.ZERO;
        BigDecimal totalScoreTE = BigDecimal.ZERO;
        double numSimsDouble = n * 1.0;
        BigDecimal totalNumSims = BigDecimal.valueOf(numSimsDouble);

        for (int i = 0; i < n; i++) {
            Collections.shuffle(oldHumanPermutationReduced);
            ArrayList<Position> copy1 = new ArrayList<>();
            ArrayList<Position> copy2 = new ArrayList<>();
            ArrayList<Position> copy3 = new ArrayList<>();
            ArrayList<Position> copy4 = new ArrayList<>();
            ArrayList<Integer> qbIndices = new ArrayList<>();
            ArrayList<Integer> rbIndices = new ArrayList<>();
            ArrayList<Integer> wrIndices = new ArrayList<>();
            ArrayList<Integer> teIndices = new ArrayList<>();
            for(int j=0; j < oldHumanPermutationReduced.size(); j++){
                Position pos = oldHumanPermutationReduced.get(j);
                copy1.add(pos);
                copy2.add(pos);
                copy3.add(pos);
                copy4.add(pos);
                if(pos.equals(Position.QB)){
                    qbIndices.add(j);
                }
                if(pos.equals(Position.RB)){
                    rbIndices.add(j);
                }
                if(pos.equals(Position.WR)){
                    wrIndices.add(j);
                }
                if(pos.equals(Position.TE)){
                    teIndices.add(j);
                }
            }
            Random rand1 = new Random();
            Random rand2 = new Random();
            Random rand3 = new Random();
            Random rand4 = new Random();
            int qbIndexToRemove = qbIndices.get(rand1.nextInt(qbIndices.size()));
            int rbIndexToRemove = rbIndices.get(rand2.nextInt(rbIndices.size()));
            int wrIndexToRemove = wrIndices.get(rand3.nextInt(wrIndices.size()));
            int teIndexToRemove = teIndices.get(rand4.nextInt(teIndices.size()));
            copy1.remove(qbIndexToRemove);
            copy2.remove(rbIndexToRemove);
            copy3.remove(wrIndexToRemove);
            copy4.remove(teIndexToRemove);


            ArrayList<Position> humanPermutationReducedRandom = oldHumanPermutationReduced;
            ArrayList<Position> humanPermutationPickQB = new ArrayList<>();
            humanPermutationPickQB.add(Position.QB);
            ArrayList<Position> humanPermutationPickRB = new ArrayList<>();
            humanPermutationPickRB.add(Position.RB);
            ArrayList<Position> humanPermutationPickWR = new ArrayList<>();
            humanPermutationPickWR.add(Position.WR);
            ArrayList<Position> humanPermutationPickTE = new ArrayList<>();
            humanPermutationPickTE.add(Position.TE);
            for(Position pos : copy1){
                humanPermutationPickQB.add(pos);
            }
            for(Position pos : copy2){
                humanPermutationPickRB.add(pos);
            }
            for(Position pos : copy3){
                humanPermutationPickWR.add(pos);
            }
            for(Position pos : copy4){
                humanPermutationPickTE.add(pos);
            }
            if(humanPermutationReducedRandom.contains(Position.QB)) {
                SimulationDraft simDraftQB = SimulationDraft.getSimulationPermPartial(humanPermutationPickQB, ldifb.draftedPlayers, numRoundsLeft, isFun, qbADPChange);
                double draftScoreQB = simDraftQB.scoreDraft(isFun);
                totalScoreQB = totalScoreQB.add(BigDecimal.valueOf(draftScoreQB));
            }
            if(humanPermutationReducedRandom.contains(Position.RB)) {
                SimulationDraft simDraftRB = SimulationDraft.getSimulationPermPartial(humanPermutationPickRB, ldifb.draftedPlayers, numRoundsLeft, isFun, qbADPChange);
                double draftScoreRB = simDraftRB.scoreDraft(isFun);
                totalScoreRB = totalScoreRB.add(BigDecimal.valueOf(draftScoreRB));
            }
            if(humanPermutationReducedRandom.contains(Position.WR)) {
                SimulationDraft simDraftWR = SimulationDraft.getSimulationPermPartial(humanPermutationPickWR, ldifb.draftedPlayers, numRoundsLeft, isFun, qbADPChange);
                double draftScoreWR = simDraftWR.scoreDraft(isFun);
                totalScoreWR = totalScoreWR.add(BigDecimal.valueOf(draftScoreWR));
            }
            if(humanPermutationReducedRandom.contains(Position.TE)) {
                SimulationDraft simDraftTE = SimulationDraft.getSimulationPermPartial(humanPermutationPickTE, ldifb.draftedPlayers, numRoundsLeft, isFun, qbADPChange);
                double draftScoreTE = simDraftTE.scoreDraft(isFun);
                totalScoreTE = totalScoreTE.add(BigDecimal.valueOf(draftScoreTE));
            }
        }
        double scoreQB = totalScoreQB.divide(totalNumSims, RoundingMode.HALF_UP).doubleValue();
        double scoreRB = totalScoreRB.divide(totalNumSims, RoundingMode.HALF_UP).doubleValue();
        double scoreWR = totalScoreWR.divide(totalNumSims, RoundingMode.HALF_UP).doubleValue();
        double scoreTE = totalScoreTE.divide(totalNumSims, RoundingMode.HALF_UP).doubleValue();

        BestAvailablePlayers bap = ldifb.bestAvailablePlayers;
        ArrayList<Score> scoreList = SleeperLeague.getScoreList(ldifb.isFunLeague);

        Player pQB1 = bap.quarterbackRT1.player;
        double sQB1 = Player.scorePlayer(scoreList, pQB1);
        Player pQB2 = bap.quarterbackRT2.player;
        double sQB2 = Player.scorePlayer(scoreList, pQB2) - sQB1;
        Player pQB3 = bap.quarterbackRT3.player;
        double sQB3 = Player.scorePlayer(scoreList, pQB3) - sQB1;
        sQB1 = 0.0;
        Player pRB1 = bap.runningBackRT1.player;
        double sRB1 = Player.scorePlayer(scoreList, pRB1);
        Player pRB2 = bap.runningBackRT2.player;
        double sRB2 = Player.scorePlayer(scoreList, pRB2) - sRB1;
        Player pRB3 = bap.runningBackRT3.player;
        double sRB3 = Player.scorePlayer(scoreList, pRB3) - sRB1;
        sRB1 = 0.0;
        Player pWR1 = bap.wideReceiverRT1.player;
        double sWR1 = Player.scorePlayer(scoreList, pWR1);
        Player pWR2 = bap.wideReceiverRT2.player;
        double sWR2 = Player.scorePlayer(scoreList, pWR2) - sWR1;
        Player pWR3 = bap.wideReceiverRT3.player;
        double sWR3 = Player.scorePlayer(scoreList, pWR3) - sWR1;
        sWR1 = 0.0;
        Player pTE1 = bap.tightEndRT1.player;
        double sTE1 = Player.scorePlayer(scoreList, pTE1);
        Player pTE2 = bap.tightEndRT2.player;
        double sTE2 = Player.scorePlayer(scoreList, pTE2) - sTE1;
        Player pTE3 = bap.tightEndRT3.player;
        double sTE3 = Player.scorePlayer(scoreList, pTE3) - sTE1;
        sTE1 = 0.0;


        Score s1 = new Score(scoreQB + sQB1, pQB1);
        Score s2 = new Score(scoreQB + sQB2, pQB2);
        Score s3 = new Score(scoreQB + sQB3, pQB3);
        Score s4 = new Score(scoreRB + sRB1, pRB1);
        Score s5 = new Score(scoreRB + sRB2, pRB2);
        Score s6 = new Score(scoreRB + sRB3, pRB3);
        Score s7 = new Score(scoreWR + sWR1, pWR1);
        Score s8 = new Score(scoreWR + sWR2, pWR2);
        Score s9 = new Score(scoreWR + sWR3, pWR3);
        Score s10 = new Score(scoreTE + sTE1, pTE1);
        Score s11 = new Score(scoreTE + sTE2, pTE2);
        Score s12 = new Score(scoreTE + sTE3, pTE3);
        ArrayList<Score> allKeeperScores = new ArrayList<>();
        allKeeperScores.add(s1);
        allKeeperScores.add(s2);
        allKeeperScores.add(s3);
        allKeeperScores.add(s4);
        allKeeperScores.add(s5);
        allKeeperScores.add(s6);
        allKeeperScores.add(s7);
        allKeeperScores.add(s8);
        allKeeperScores.add(s9);
        allKeeperScores.add(s10);
        allKeeperScores.add(s11);
        allKeeperScores.add(s12);

        PriorityQueue<Score> playersInBestScoreOrder = new PriorityQueue<Score>(5, new ScoreComparator());
        for(Score s : allKeeperScores){
            playersInBestScoreOrder.add(s);
        }
        while(playersInBestScoreOrder.size() > 0) {
            Score s = playersInBestScoreOrder.poll();
            String name = s.player.firstName + " " + s.player.lastName;
            System.out.println("Pick " + name + " to Score:\t" + s.score);
        }


    }

    public static void runDraftsOnTheFlyWithHardcodedKeepers(int n, int roundPick, ArrayList<Position> humanPermutationOld, LiveDraftInfo ldifb, int qbADPChange, ArrayList<Keeper> hardcodedKeepers) {
        boolean isFun = false;
        Keeper myKeeper = null;
        for(Keeper k : hardcodedKeepers){
            if(k.humanWhoCanKeep.equals(HumanOfInterest.humanID)){
                myKeeper=k;
            }
        }
        int numRoundsTotal = 10;
        int numRoundsLeft = numRoundsTotal - roundPick + 1;
        ArrayList<Position> oldHumanPermutationReduced = humanPermutationOld;
        for(Player player : ldifb.rosterPlayers){
            Position pos = player.position;
            oldHumanPermutationReduced.remove(pos);
        }
        /*for(Position p : humanPermutationOld){
            oldHumanPermutationReduced.add(p);
        }
        Position keeperPosition = myKeeper.player.position;
        oldHumanPermutationReduced.remove(keeperPosition);*/
        double numSimsDouble = n * 1.0;

        BigDecimal totalScoreQB = BigDecimal.ZERO;
        BigDecimal totalScoreRB = BigDecimal.ZERO;
        BigDecimal totalScoreWR = BigDecimal.ZERO;
        BigDecimal totalScoreTE = BigDecimal.ZERO;
        BigDecimal totalNumSims = BigDecimal.valueOf(numSimsDouble);

        for (int i = 0; i < n; i++) {
            Collections.shuffle(oldHumanPermutationReduced);
            ArrayList<Position> copy1 = new ArrayList<>();
            ArrayList<Position> copy2 = new ArrayList<>();
            ArrayList<Position> copy3 = new ArrayList<>();
            ArrayList<Position> copy4 = new ArrayList<>();
            ArrayList<Integer> qbIndices = new ArrayList<>();
            ArrayList<Integer> rbIndices = new ArrayList<>();
            ArrayList<Integer> wrIndices = new ArrayList<>();
            ArrayList<Integer> teIndices = new ArrayList<>();
            for(int j=0; j < oldHumanPermutationReduced.size(); j++){
                Position pos = oldHumanPermutationReduced.get(j);
                copy1.add(pos);
                copy2.add(pos);
                copy3.add(pos);
                copy4.add(pos);
                if(pos.equals(Position.QB)){
                    qbIndices.add(j);
                }
                if(pos.equals(Position.RB)){
                    rbIndices.add(j);
                }
                if(pos.equals(Position.WR)){
                    wrIndices.add(j);
                }
                if(pos.equals(Position.TE)){
                    teIndices.add(j);
                }
            }
            Random rand1 = new Random();
            Random rand2 = new Random();
            Random rand3 = new Random();
            Random rand4 = new Random();

            if(qbIndices.size() > 0) {
                int qbIndexToRemove = qbIndices.get(rand1.nextInt(qbIndices.size()));
                copy1.remove(qbIndexToRemove);
            }
            if(rbIndices.size() > 0) {
                int rbIndexToRemove = rbIndices.get(rand2.nextInt(rbIndices.size()));
                copy2.remove(rbIndexToRemove);
            }
            if(wrIndices.size() > 0) {
                int wrIndexToRemove = wrIndices.get(rand3.nextInt(wrIndices.size()));
                copy3.remove(wrIndexToRemove);
            }
            if(teIndices.size() > 0) {
                int teIndexToRemove = teIndices.get(rand4.nextInt(teIndices.size()));
                copy4.remove(teIndexToRemove);
            }


            ArrayList<Position> humanPermutationReducedRandom = oldHumanPermutationReduced;
            ArrayList<Position> humanPermutationPickQB = new ArrayList<>();
            humanPermutationPickQB.add(Position.QB);
            ArrayList<Position> humanPermutationPickRB = new ArrayList<>();
            humanPermutationPickRB.add(Position.RB);
            ArrayList<Position> humanPermutationPickWR = new ArrayList<>();
            humanPermutationPickWR.add(Position.WR);
            ArrayList<Position> humanPermutationPickTE = new ArrayList<>();
            humanPermutationPickTE.add(Position.TE);
            for(Position pos : copy1){
                humanPermutationPickQB.add(pos);
            }
            for(Position pos : copy2){
                humanPermutationPickRB.add(pos);
            }
            for(Position pos : copy3){
                humanPermutationPickWR.add(pos);
            }
            for(Position pos : copy4){
                humanPermutationPickTE.add(pos);
            }
            if(humanPermutationReducedRandom.contains(Position.QB)) {
                SimulationDraft simDraftQB = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickQB, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                double draftScoreQB = simDraftQB.scoreDraft(isFun);
                totalScoreQB = totalScoreQB.add(BigDecimal.valueOf(draftScoreQB));
            }
            if(humanPermutationReducedRandom.contains(Position.RB)) {
                SimulationDraft simDraftRB = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickRB, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                double draftScoreRB = simDraftRB.scoreDraft(isFun);
                totalScoreRB = totalScoreRB.add(BigDecimal.valueOf(draftScoreRB));
            }
            if(humanPermutationReducedRandom.contains(Position.WR)) {
                SimulationDraft simDraftWR = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickWR, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                double draftScoreWR = simDraftWR.scoreDraft(isFun);
                totalScoreWR = totalScoreWR.add(BigDecimal.valueOf(draftScoreWR));
            }
            if(humanPermutationReducedRandom.contains(Position.TE)) {
                SimulationDraft simDraftTE = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickTE, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                double draftScoreTE = simDraftTE.scoreDraft(isFun);
                totalScoreTE = totalScoreTE.add(BigDecimal.valueOf(draftScoreTE));
            }
        }
        double scoreQB = totalScoreQB.divide(totalNumSims, RoundingMode.HALF_UP).doubleValue();
        double scoreRB = totalScoreRB.divide(totalNumSims, RoundingMode.HALF_UP).doubleValue();
        double scoreWR = totalScoreWR.divide(totalNumSims, RoundingMode.HALF_UP).doubleValue();
        double scoreTE = totalScoreTE.divide(totalNumSims, RoundingMode.HALF_UP).doubleValue();

        BestAvailablePlayers bap = ldifb.bestAvailablePlayers;
        ArrayList<Score> scoreList = SleeperLeague.getScoreList(ldifb.isFunLeague);

        Player pQB1 = bap.quarterbackRT1.player;
        double sQB1 = Player.scorePlayer(scoreList, pQB1);
        Player pQB2 = bap.quarterbackRT2.player;
        double sQB2 = Player.scorePlayer(scoreList, pQB2) - sQB1;
        Player pQB3 = bap.quarterbackRT3.player;
        double sQB3 = Player.scorePlayer(scoreList, pQB3) - sQB1;
        sQB1 = 0.0;
        Player pRB1 = bap.runningBackRT1.player;
        double sRB1 = Player.scorePlayer(scoreList, pRB1);
        Player pRB2 = bap.runningBackRT2.player;
        double sRB2 = Player.scorePlayer(scoreList, pRB2) - sRB1;
        Player pRB3 = bap.runningBackRT3.player;
        double sRB3 = Player.scorePlayer(scoreList, pRB3) - sRB1;
        sRB1 = 0.0;
        Player pWR1 = bap.wideReceiverRT1.player;
        double sWR1 = Player.scorePlayer(scoreList, pWR1);
        Player pWR2 = bap.wideReceiverRT2.player;
        double sWR2 = Player.scorePlayer(scoreList, pWR2) - sWR1;
        Player pWR3 = bap.wideReceiverRT3.player;
        double sWR3 = Player.scorePlayer(scoreList, pWR3) - sWR1;
        sWR1 = 0.0;
        Player pTE1 = bap.tightEndRT1.player;
        double sTE1 = Player.scorePlayer(scoreList, pTE1);
        Player pTE2 = bap.tightEndRT2.player;
        double sTE2 = Player.scorePlayer(scoreList, pTE2) - sTE1;
        Player pTE3 = bap.tightEndRT3.player;
        double sTE3 = Player.scorePlayer(scoreList, pTE3) - sTE1;
        sTE1 = 0.0;

        Score s1 = new Score(scoreQB + sQB1, pQB1);
        Score s2 = new Score(scoreQB + sQB2, pQB2);
        Score s3 = new Score(scoreQB + sQB3, pQB3);
        Score s4 = new Score(scoreRB + sRB1, pRB1);
        Score s5 = new Score(scoreRB + sRB2, pRB2);
        Score s6 = new Score(scoreRB + sRB3, pRB3);
        Score s7 = new Score(scoreWR + sWR1, pWR1);
        Score s8 = new Score(scoreWR + sWR2, pWR2);
        Score s9 = new Score(scoreWR + sWR3, pWR3);
        Score s10 = new Score(scoreTE + sTE1, pTE1);
        Score s11 = new Score(scoreTE + sTE2, pTE2);
        Score s12 = new Score(scoreTE + sTE3, pTE3);
        ArrayList<Score> allKeeperScores = new ArrayList<>();
        allKeeperScores.add(s1);
        allKeeperScores.add(s2);
        allKeeperScores.add(s3);
        allKeeperScores.add(s4);
        allKeeperScores.add(s5);
        allKeeperScores.add(s6);
        allKeeperScores.add(s7);
        allKeeperScores.add(s8);
        allKeeperScores.add(s9);
        allKeeperScores.add(s10);
        allKeeperScores.add(s11);
        allKeeperScores.add(s12);

        PriorityQueue<Score> playersInBestScoreOrder = new PriorityQueue<Score>(5, new ScoreComparator());
        for(Score s : allKeeperScores){
            playersInBestScoreOrder.add(s);
        }
        while(playersInBestScoreOrder.size() > 0) {
            Score s = playersInBestScoreOrder.poll();
            String name = s.player.firstName + " " + s.player.lastName;
            System.out.println("Pick " + name + " to Score:\t" + s.score);
        }
    }


}
