import org.checkerframework.checker.units.qual.A;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.PriorityQueue;

public class OnTheFlySimulationRunner {

    public static void runDraftsOnTheFlyToChooseMyKeeperHardcoded(int n, ArrayList<Position> humanPermutationOld, LiveDraftInfo ldifb, String userID, boolean allowUndrafted, int undraftedRoundCost, int qbADPChange) throws Exception {
        boolean isFun = false;
        Keepers keepers = Keepers.getKeepersForUserHardcoded(isFun, userID, true, undraftedRoundCost);
        Keepers keepersWithoutUndrafted = Keepers.getKeepersForUserHardcoded(isFun, userID, false, undraftedRoundCost);
        ArrayList<Score> allKeeperScores = new ArrayList<>();
        for(Keeper keeper : keepers.keepers) {
            //System.out.println("My permutation size " + humanPermutationOld.size());
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
            //System.out.println("\t\t\t\tPick best QB to Score:\t" + avScoreQB);
            //System.out.println("\t\t\t\tPick best RB to Score:\t" + avScoreRB);
            //System.out.println("\t\t\t\tPick best WR to Score:\t" + avScoreWR);
            //System.out.println("\t\t\t\tPick best TE to Score:\t" + avScoreTE);
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
            keeperName += s.player.firstName + " " + s.player.lastName;
            System.out.println(keeperName + " :\t" + s.score);
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
            for(Position pos : oldHumanPermutationReduced){
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
        double avScoreQB = totalScoreQB.divide(totalNumSims, RoundingMode.HALF_UP).doubleValue();
        double avScoreRB = totalScoreRB.divide(totalNumSims, RoundingMode.HALF_UP).doubleValue();
        double avScoreWR = totalScoreWR.divide(totalNumSims, RoundingMode.HALF_UP).doubleValue();
        double avScoreTE = totalScoreTE.divide(totalNumSims, RoundingMode.HALF_UP).doubleValue();
        System.out.println("Pick best QB to Score:\t" + avScoreQB);
        System.out.println("Pick best RB to Score:\t" + avScoreRB);
        System.out.println("Pick best WR to Score:\t" + avScoreWR);
        System.out.println("Pick best TE to Score:\t" + avScoreTE);
    }


}
