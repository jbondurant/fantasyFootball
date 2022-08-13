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

    public static ArrayList<ArrayList<Position>> createDraftOrdersWithOneListPerPosAtFirstIndex(ArrayList<Position> oldHumanPermutationReduced){
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
        ArrayList<ArrayList<Position>> allCopies = new ArrayList<>();
        allCopies.add(copy1);
        allCopies.add(copy2);
        allCopies.add(copy3);
        allCopies.add(copy4);
        return allCopies;
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
            ArrayList<ArrayList<Position>> allCopies = createDraftOrdersWithOneListPerPosAtFirstIndex(oldHumanPermutationReduced);
            ArrayList<Position> copy1 = allCopies.get(0);
            ArrayList<Position> copy2 = allCopies.get(1);
            ArrayList<Position> copy3 = allCopies.get(2);
            ArrayList<Position> copy4 = allCopies.get(3);

            ArrayList<ArrayList<Position>> allCopies1 = createDraftOrdersWithOneListPerPosAtFirstIndex(copy1);
            ArrayList<Position> copy11 = allCopies1.get(0);
            ArrayList<Position> copy12 = allCopies1.get(1);
            ArrayList<Position> copy13 = allCopies1.get(2);
            ArrayList<Position> copy14 = allCopies1.get(3);

            ArrayList<ArrayList<Position>> allCopies2 = createDraftOrdersWithOneListPerPosAtFirstIndex(copy2);
            ArrayList<Position> copy21 = allCopies2.get(0);
            ArrayList<Position> copy22 = allCopies2.get(1);
            ArrayList<Position> copy23 = allCopies2.get(2);
            ArrayList<Position> copy24 = allCopies2.get(3);

            ArrayList<ArrayList<Position>> allCopies3 = createDraftOrdersWithOneListPerPosAtFirstIndex(copy3);
            ArrayList<Position> copy31 = allCopies3.get(0);
            ArrayList<Position> copy32 = allCopies3.get(1);
            ArrayList<Position> copy33 = allCopies3.get(2);
            ArrayList<Position> copy34 = allCopies3.get(3);

            ArrayList<ArrayList<Position>> allCopies4 = createDraftOrdersWithOneListPerPosAtFirstIndex(copy4);
            ArrayList<Position> copy41 = allCopies4.get(0);
            ArrayList<Position> copy42 = allCopies4.get(1);
            ArrayList<Position> copy43 = allCopies4.get(2);
            ArrayList<Position> copy44 = allCopies4.get(3);


            ArrayList<Position> humanPermutationReducedRandom = oldHumanPermutationReduced;
            ArrayList<Position> humanPermutationPickQB1 = new ArrayList<>();
            humanPermutationPickQB1.add(Position.QB);
            humanPermutationPickQB1.add(Position.QB);
            ArrayList<Position> humanPermutationPickQB2 = new ArrayList<>();
            humanPermutationPickQB2.add(Position.QB);
            humanPermutationPickQB2.add(Position.RB);
            ArrayList<Position> humanPermutationPickQB3 = new ArrayList<>();
            humanPermutationPickQB3.add(Position.QB);
            humanPermutationPickQB3.add(Position.WR);
            ArrayList<Position> humanPermutationPickQB4 = new ArrayList<>();
            humanPermutationPickQB4.add(Position.QB);
            humanPermutationPickQB4.add(Position.TE);

            ArrayList<Position> humanPermutationPickRB1 = new ArrayList<>();
            humanPermutationPickRB1.add(Position.RB);
            humanPermutationPickRB1.add(Position.QB);
            ArrayList<Position> humanPermutationPickRB2 = new ArrayList<>();
            humanPermutationPickRB2.add(Position.RB);
            humanPermutationPickRB2.add(Position.RB);
            ArrayList<Position> humanPermutationPickRB3 = new ArrayList<>();
            humanPermutationPickRB3.add(Position.RB);
            humanPermutationPickRB3.add(Position.WR);
            ArrayList<Position> humanPermutationPickRB4 = new ArrayList<>();
            humanPermutationPickRB4.add(Position.RB);
            humanPermutationPickRB4.add(Position.TE);

            ArrayList<Position> humanPermutationPickWR1 = new ArrayList<>();
            humanPermutationPickWR1.add(Position.WR);
            humanPermutationPickWR1.add(Position.QB);
            ArrayList<Position> humanPermutationPickWR2 = new ArrayList<>();
            humanPermutationPickWR2.add(Position.WR);
            humanPermutationPickWR2.add(Position.RB);
            ArrayList<Position> humanPermutationPickWR3 = new ArrayList<>();
            humanPermutationPickWR3.add(Position.WR);
            humanPermutationPickWR3.add(Position.WR);
            ArrayList<Position> humanPermutationPickWR4 = new ArrayList<>();
            humanPermutationPickWR4.add(Position.WR);
            humanPermutationPickWR4.add(Position.TE);

            ArrayList<Position> humanPermutationPickTE1 = new ArrayList<>();
            humanPermutationPickTE1.add(Position.TE);
            humanPermutationPickTE1.add(Position.QB);
            ArrayList<Position> humanPermutationPickTE2 = new ArrayList<>();
            humanPermutationPickTE2.add(Position.TE);
            humanPermutationPickTE2.add(Position.RB);
            ArrayList<Position> humanPermutationPickTE3 = new ArrayList<>();
            humanPermutationPickTE3.add(Position.TE);
            humanPermutationPickTE3.add(Position.WR);
            ArrayList<Position> humanPermutationPickTE4 = new ArrayList<>();
            humanPermutationPickTE4.add(Position.TE);
            humanPermutationPickTE4.add(Position.TE);

            for(Position pos : copy11){
                humanPermutationPickQB1.add(pos);
            }
            for(Position pos : copy12){
                humanPermutationPickQB2.add(pos);
            }
            for(Position pos : copy13){
                humanPermutationPickQB3.add(pos);
            }
            for(Position pos : copy14){
                humanPermutationPickQB4.add(pos);
            }

            for(Position pos : copy21){
                humanPermutationPickRB1.add(pos);
            }
            for(Position pos : copy22){
                humanPermutationPickRB2.add(pos);
            }
            for(Position pos : copy23){
                humanPermutationPickRB3.add(pos);
            }
            for(Position pos : copy24){
                humanPermutationPickRB4.add(pos);
            }

            for(Position pos : copy31){
                humanPermutationPickWR1.add(pos);
            }
            for(Position pos : copy32){
                humanPermutationPickWR2.add(pos);
            }
            for(Position pos : copy33){
                humanPermutationPickWR3.add(pos);
            }
            for(Position pos : copy34){
                humanPermutationPickWR4.add(pos);
            }

            for(Position pos : copy41){
                humanPermutationPickTE1.add(pos);
            }
            for(Position pos : copy42){
                humanPermutationPickTE2.add(pos);
            }
            for(Position pos : copy43){
                humanPermutationPickTE3.add(pos);
            }
            for(Position pos : copy44){
                humanPermutationPickTE4.add(pos);
            }

            if(humanPermutationReducedRandom.contains(Position.QB)) {
                double draftScoreQB = 0.0;
                if(copy1.contains(Position.QB)) {
                    SimulationDraft simDraftQB1 = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickQB1, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                    draftScoreQB = Math.max(draftScoreQB, simDraftQB1.scoreDraft(isFun));
                }
                if(copy1.contains(Position.RB)) {
                    SimulationDraft simDraftQB2 = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickQB2, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                    draftScoreQB = Math.max(draftScoreQB, simDraftQB2.scoreDraft(isFun));
                }
                if(copy1.contains(Position.WR)) {
                    SimulationDraft simDraftQB3 = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickQB3, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                    draftScoreQB = Math.max(draftScoreQB, simDraftQB3.scoreDraft(isFun));
                }
                if(copy1.contains(Position.TE)) {
                    SimulationDraft simDraftQB4 = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickQB4, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                    draftScoreQB = Math.max(draftScoreQB, simDraftQB4.scoreDraft(isFun));
                }

                totalScoreQB = totalScoreQB.add(BigDecimal.valueOf(draftScoreQB));
            }


            if(humanPermutationReducedRandom.contains(Position.RB)) {
                double draftScoreRB = 0.0;
                if(copy2.contains(Position.QB)){
                    SimulationDraft simDraftRB1 = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickRB1, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                    draftScoreRB = Math.max(draftScoreRB, simDraftRB1.scoreDraft(isFun));
                }
                if(copy2.contains(Position.RB)){
                    SimulationDraft simDraftRB2 = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickRB2, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                    draftScoreRB = Math.max(draftScoreRB, simDraftRB2.scoreDraft(isFun));
                }
                if(copy2.contains(Position.WR)){
                    SimulationDraft simDraftRB3 = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickRB3, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                    draftScoreRB = Math.max(draftScoreRB, simDraftRB3.scoreDraft(isFun));
                }
                if(copy2.contains(Position.TE)){
                    SimulationDraft simDraftRB4 = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickRB4, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                    draftScoreRB = Math.max(draftScoreRB, simDraftRB4.scoreDraft(isFun));
                }
                totalScoreRB = totalScoreRB.add(BigDecimal.valueOf(draftScoreRB));
            }


            if(humanPermutationReducedRandom.contains(Position.WR)) {
                double draftScoreWR = 0.0;
                if(copy3.contains(Position.QB)) {
                    SimulationDraft simDraftWR1 = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickWR1, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                    draftScoreWR = Math.max(draftScoreWR, simDraftWR1.scoreDraft(isFun));
                }
                if(copy3.contains(Position.RB)) {
                    SimulationDraft simDraftWR2 = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickWR2, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                    draftScoreWR = Math.max(draftScoreWR, simDraftWR2.scoreDraft(isFun));
                }
                if(copy3.contains(Position.WR)) {
                    SimulationDraft simDraftWR3 = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickWR3, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                    draftScoreWR = Math.max(draftScoreWR, simDraftWR3.scoreDraft(isFun));
                }
                if(copy3.contains(Position.TE)) {
                    SimulationDraft simDraftWR4 = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickWR4, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                    draftScoreWR = Math.max(draftScoreWR, simDraftWR4.scoreDraft(isFun));
                }
                totalScoreWR = totalScoreWR.add(BigDecimal.valueOf(draftScoreWR));
            }
            if(humanPermutationReducedRandom.contains(Position.TE)) {
                double draftScoreTE = 0.0;
                if(copy4.contains(Position.QB)){
                    SimulationDraft simDraftTE1 = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickTE1, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                    draftScoreTE = simDraftTE1.scoreDraft(isFun);
                }
                if(copy4.contains(Position.RB)){
                    SimulationDraft simDraftTE2 = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickTE2, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                    draftScoreTE = simDraftTE2.scoreDraft(isFun);
                }
                if(copy4.contains(Position.WR)){
                    SimulationDraft simDraftTE3 = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickTE3, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                    draftScoreTE = simDraftTE3.scoreDraft(isFun);
                }
                if(copy4.contains(Position.TE)){
                    SimulationDraft simDraftTE4 = SimulationDraft.getSimulationPermPartialWithHardcodedKeepers(myKeeper, humanPermutationPickTE4, ldifb.draftedPlayers, numRoundsLeft, qbADPChange, hardcodedKeepers);
                    draftScoreTE = simDraftTE4.scoreDraft(isFun);
                }
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
