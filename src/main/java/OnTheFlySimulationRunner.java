import org.checkerframework.checker.units.qual.A;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;

public class OnTheFlySimulationRunner {

    public static void runDraftsOnTheFly(int n, int roundPick, boolean isFun, ArrayList<Position> humanPermutationOld, LiveDraftInfoFunBulk ldifb) {
        int numRoundsTotal = 9;
        int numRoundsLeft = numRoundsTotal - roundPick + 1;


        ArrayList<Position> positionsDraftedByHuman = new ArrayList<Position>();

        ArrayList<Position> oldHumanPermutationReduced = humanPermutationOld;
        for(Player player : ldifb.rosterPlayers){
            Position pos = player.position;
            positionsDraftedByHuman.add(pos);
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
                positionsDraftedByHuman.add(Position.QB);
                SimulationDraft simDraftQB = SimulationDraft.getFunSimulationPermPartial(humanPermutationPickQB, ldifb.draftedPlayers, numRoundsLeft);
                double draftScoreQB = simDraftQB.scoreDraft(isFun);
                totalScoreQB = totalScoreQB.add(BigDecimal.valueOf(draftScoreQB));
            }

            if(humanPermutationReducedRandom.contains(Position.RB)) {
                positionsDraftedByHuman.add(Position.RB);
                SimulationDraft simDraftRB = SimulationDraft.getFunSimulationPermPartial(humanPermutationPickRB, ldifb.draftedPlayers, numRoundsLeft);
                double draftScoreRB = simDraftRB.scoreDraft(isFun);
                totalScoreRB = totalScoreRB.add(BigDecimal.valueOf(draftScoreRB));
            }

            if(humanPermutationReducedRandom.contains(Position.WR)) {
                positionsDraftedByHuman.add(Position.WR);
                SimulationDraft simDraftWR = SimulationDraft.getFunSimulationPermPartial(humanPermutationPickWR, ldifb.draftedPlayers, numRoundsLeft);
                double draftScoreWR = simDraftWR.scoreDraft(isFun);
                totalScoreWR = totalScoreWR.add(BigDecimal.valueOf(draftScoreWR));
            }

            if(humanPermutationReducedRandom.contains(Position.TE)) {
                positionsDraftedByHuman.add(Position.TE);
                SimulationDraft simDraftTE = SimulationDraft.getFunSimulationPermPartial(humanPermutationPickTE, ldifb.draftedPlayers, numRoundsLeft);
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
